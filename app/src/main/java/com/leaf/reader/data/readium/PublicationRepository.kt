package com.leaf.reader.data.readium

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt
import kotlin.reflect.KClass
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.readium.adapter.pdfium.document.PdfiumDocumentFactory
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.encryption.encryption
import org.readium.r2.shared.publication.services.isProtected
import org.readium.r2.shared.publication.services.isRestricted
import org.readium.r2.shared.util.Error as ReadiumError
import org.readium.r2.shared.util.ThrowableError
import org.readium.r2.shared.util.Try
import org.readium.r2.shared.util.asset.Asset
import org.readium.r2.shared.util.asset.AssetRetriever
import org.readium.r2.shared.util.format.Specification
import org.readium.r2.shared.util.getOrElse
import org.readium.r2.shared.util.data.ReadTry
import org.readium.r2.shared.util.data.ReadError
import org.readium.r2.shared.util.pdf.PdfDocument
import org.readium.r2.shared.util.pdf.PdfDocumentFactory
import org.readium.r2.shared.util.resource.Resource
import org.readium.r2.shared.util.http.HttpClient
import org.readium.r2.shared.util.http.HttpError
import org.readium.r2.shared.util.http.HttpRequest
import org.readium.r2.shared.util.http.HttpStreamResponse
import org.readium.r2.shared.util.http.HttpTry
import org.readium.r2.shared.util.toUrl
import org.readium.r2.streamer.PublicationOpener
import org.readium.r2.streamer.parser.DefaultPublicationParser

/** Metadata deliberately excludes Readium types so import/library code stays engine independent. */
data class PublicationInspection(
    val title: String?,
    val authors: List<String>,
    val description: String?,
    val cover: Bitmap?,
)

enum class PublicationAccessProblem(val userMessage: String) {
    MISSING_FILE("This imported copy is missing or unreadable. Import the original book again."),
    UNSUPPORTED_FORMAT("This file does not contain a supported EPUB or PDF publication."),
    PASSWORD_PROTECTED("This PDF requires a password. Import an unlocked copy that you are allowed to read."),
    DRM_PROTECTED("This book is protected by DRM. Import a DRM-free EPUB or PDF that you are allowed to read."),
    CORRUPT_PUBLICATION("This book could not be opened. It may be damaged or incomplete. Try another copy."),
}

/** Safe to display or record: never contains a private path, source URI, or upstream error text. */
class PublicationAccessException(val problem: PublicationAccessProblem) : IOException(problem.userMessage)

/** Rejects all online assets, including remote resources referenced from an otherwise local EPUB. */
internal class OfflineHttpClient : HttpClient {
    override suspend fun stream(request: HttpRequest): HttpTry<HttpStreamResponse> =
        Try.failure(HttpError.IO(IOException("Online resources are disabled in Leaf.")))
}

/**
 * Owns Readium opening configuration. It never caches a Publication across callers.
 * The caller of [open] owns the returned Publication and MUST close it when the reader is done.
 * [inspect] always closes its Publication; its optional thumbnail belongs to the caller.
 */
@Singleton
class PublicationRepository @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val httpClient: HttpClient = OfflineHttpClient()
    private val assetRetriever = AssetRetriever(context.contentResolver, httpClient)
    private val opener = PublicationOpener(
        publicationParser = DefaultPublicationParser(
            context = context.applicationContext,
            httpClient = httpClient,
            assetRetriever = assetRetriever,
            pdfFactory = MetadataPdfDocumentFactory(context.applicationContext),
        ),
        contentProtections = emptyList(),
    )

    suspend fun open(file: File): Publication {
        // Keep ownership outside withContext: prompt cancellation while returning from IO must
        // still close a successfully created Publication instead of leaking its ZIP/PDF handles.
        var asset: Asset? = null
        var publication: Publication? = null
        var transferredToCaller = false
        try {
            withContext(Dispatchers.IO) {
                if (!file.isFile || !file.canRead() || file.length() == 0L) {
                    throw PublicationAccessException(PublicationAccessProblem.MISSING_FILE)
                }
                val retrieved = assetRetriever.retrieve(file.absoluteFile.toUrl(isDirectory = false))
                    .getOrElse { throw accessException(it) }
                asset = retrieved
                if (retrieved.format.conformsTo(Specification.Lcp) ||
                    retrieved.format.conformsTo(Specification.Adept)
                ) {
                    throw PublicationAccessException(PublicationAccessProblem.DRM_PROTECTED)
                }
                if (!retrieved.format.conformsTo(Specification.Epub) &&
                    !retrieved.format.conformsTo(Specification.Pdf)
                ) {
                    throw PublicationAccessException(PublicationAccessProblem.UNSUPPORTED_FORMAT)
                }
                val opened = opener.open(retrieved, allowUserInteraction = false)
                    .getOrElse { throw accessException(it) }
                publication = opened
                // Successful Publication creation transfers its asset/container ownership.
                asset = null
                if (opened.isProtected || opened.isRestricted || hasUnsupportedEncryption(opened)) {
                    throw PublicationAccessException(PublicationAccessProblem.DRM_PROTECTED)
                }
                if (opened.readingOrder.isEmpty()) {
                    throw PublicationAccessException(PublicationAccessProblem.CORRUPT_PUBLICATION)
                }
                currentCoroutineContext().ensureActive()
            }
            currentCoroutineContext().ensureActive()
            return checkNotNull(publication).also { transferredToCaller = true }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: PublicationAccessException) {
            throw failure
        } catch (failure: Exception) {
            throw PublicationAccessException(
                if (isPasswordThrowable(failure)) PublicationAccessProblem.PASSWORD_PROTECTED
                else PublicationAccessProblem.CORRUPT_PUBLICATION,
            )
        } finally {
            if (!transferredToCaller) {
                // Do not let an engine cleanup exception replace cancellation or a safe user error.
                try { publication?.close() } catch (_: Exception) { }
                try { asset?.close() } catch (_: Exception) { }
            }
        }
    }

    suspend fun inspect(file: File): PublicationInspection {
        val publication = open(file)
        var thumbnail: Bitmap? = null
        var thumbnailTransferred = false
        try {
            val inspection = withContext(Dispatchers.IO) {
                val metadata = publication.metadata
                thumbnail = try {
                    if (Publication.Profile.PDF in publication.metadata.conformsTo) {
                        pdfThumbnail(file)
                    } else {
                        epubThumbnail(publication)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null // A missing/broken cover must not make otherwise readable books unusable.
                } catch (_: OutOfMemoryError) {
                    null // Some upstream PDF covers allocate a full first page before downsampling.
                }
                currentCoroutineContext().ensureActive()
                PublicationInspection(
                    title = metadata.title,
                    authors = metadata.authors.map { it.name },
                    description = metadata.description,
                    cover = thumbnail,
                )
            }
            currentCoroutineContext().ensureActive()
            thumbnailTransferred = true
            return inspection
        } finally {
            if (!thumbnailTransferred) thumbnail?.recycle()
            try { publication.close() } catch (_: Exception) { }
        }
    }

    private suspend fun epubThumbnail(publication: Publication): Bitmap? {
        val link = publication.linkWithRel("cover") ?: return null
        val resource = publication.get(link) ?: return null
        try {
            // ResourceCoverService otherwise decodes the original bitmap before downsampling.
            // Bound both the encoded bytes and the decoded pixel allocation for local covers.
            val length = resource.length().getOrNull() ?: return null
            if (length <= 0L || length > MAX_COVER_BYTES) return null
            val bytes = resource.read(0L until length).getOrNull() ?: return null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            while (bounds.outWidth / sample > COVER_WIDTH || bounds.outHeight / sample > COVER_HEIGHT) {
                sample *= 2
            }
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            })
        } finally {
            try { resource.close() } catch (_: Exception) { }
        }
    }

    /** Only the thumbnail uses the platform renderer; reading itself uses Readium's navigator. */
    private fun pdfThumbnail(file: File): Bitmap? =
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                if (renderer.pageCount == 0) return null
                renderer.openPage(0).use { page ->
                    if (page.width <= 0 || page.height <= 0) return null
                    val scale = minOf(COVER_WIDTH.toFloat() / page.width, COVER_HEIGHT.toFloat() / page.height)
                    val width = (page.width * scale).roundToInt().coerceIn(1, COVER_WIDTH)
                    val height = (page.height * scale).roundToInt().coerceIn(1, COVER_HEIGHT)
                    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    try {
                        bitmap.eraseColor(Color.WHITE)
                        val matrix = Matrix().apply { setScale(scale, scale) }
                        page.render(bitmap, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        bitmap
                    } catch (failure: Throwable) {
                        bitmap.recycle()
                        throw failure
                    }
                }
            }
        }

    private fun hasUnsupportedEncryption(publication: Publication): Boolean =
        (publication.readingOrder + publication.resources).any { link ->
            link.properties.encryption?.algorithm?.let { it !in FONT_OBFUSCATION_ALGORITHMS } == true
        }

    private fun accessException(error: ReadiumError): PublicationAccessException {
        var current: ReadiumError? = error
        repeat(32) {
            val item = current ?: return PublicationAccessException(PublicationAccessProblem.CORRUPT_PUBLICATION)
            if (item is ThrowableError<*> && isPasswordThrowable(item.throwable)) {
                return PublicationAccessException(PublicationAccessProblem.PASSWORD_PROTECTED)
            }
            current = item.cause
        }
        return PublicationAccessException(PublicationAccessProblem.CORRUPT_PUBLICATION)
    }

    private fun isPasswordThrowable(throwable: Throwable): Boolean {
        var current: Throwable? = throwable
        repeat(32) {
            val item = current ?: return false
            // Avoid depending on PDFium's implementation-only Java classes or exposing messages.
            if (item.javaClass.simpleName.contains("Password", ignoreCase = true)) return true
            current = item.cause
        }
        return false
    }

    private companion object {
        const val COVER_WIDTH = 512
        const val COVER_HEIGHT = 768
        const val MAX_COVER_BYTES = 8L * 1024 * 1024
        val FONT_OBFUSCATION_ALGORITHMS = setOf(
            "http://www.idpf.org/2008/embedding",
            "http://ns.adobe.com/pdf/enc#RC",
        )
    }
}

/**
 * Readium 3.4's PdfParser eagerly requests a full-size cover and does not close the temporary
 * native PdfDocument used for metadata. Snapshot the public metadata and release that handle
 * here. Returning a cover-less document prevents that unbounded allocation; inspect() produces
 * a bounded thumbnail separately. The Readium PDF navigator still opens/renders the real file.
 */
private class MetadataPdfDocumentFactory(context: Context) : PdfDocumentFactory<PdfDocument> {
    override val documentType: KClass<PdfDocument> = PdfDocument::class
    private val delegate = PdfiumDocumentFactory(context)

    override suspend fun open(resource: Resource, password: String?): ReadTry<PdfDocument> =
        delegate.open(BoundedPdfFallbackResource(resource), password).map { document ->
            try {
                SnapshotPdfDocument(
                    identifier = document.identifier,
                    pageCount = document.pageCount,
                    title = document.title,
                    author = document.author,
                    subject = document.subject,
                    keywords = document.keywords.toList(),
                    outline = document.outline.toList(),
                    readingProgression = document.readingProgression,
                )
            } finally {
                document.close()
            }
        }
}

/**
 * PDFium normally opens our local managed file directly. Upstream falls back to reading every
 * byte when that native open fails (including damaged/password-protected files). Bound that
 * fallback before allocation; valid large local PDFs still use the normal file-descriptor path.
 */
private class BoundedPdfFallbackResource(private val delegate: Resource) : Resource by delegate {
    override suspend fun read(range: LongRange?): ReadTry<ByteArray> {
        val localLength = delegate.sourceUrl?.toFile()?.length()
        val requestedLength = range?.let { span ->
            if (span.last < span.first) 0L else (span.last - span.first).let { difference ->
                if (difference < 0L || difference == Long.MAX_VALUE) Long.MAX_VALUE else difference + 1
            }
        } ?: localLength ?: Long.MAX_VALUE
        if (requestedLength > MAX_FALLBACK_BYTES) {
            return Try.failure(ReadError.Decoding(IOException("The damaged PDF could not be decoded safely.")))
        }
        return delegate.read(range)
    }

    private companion object { const val MAX_FALLBACK_BYTES = 64L * 1024 * 1024 }
}

private data class SnapshotPdfDocument(
    override val identifier: String?,
    override val pageCount: Int,
    override val title: String?,
    override val author: String?,
    override val subject: String?,
    override val keywords: List<String>,
    override val outline: List<PdfDocument.OutlineNode>,
    override val readingProgression: org.readium.r2.shared.publication.ReadingProgression?,
) : PdfDocument {
    override fun close() { }
}
