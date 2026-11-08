package com.leaf.reader.data.repository

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import android.provider.OpenableColumns
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.leaf.reader.core.util.*
import com.leaf.reader.data.local.files.ManagedBookFiles
import com.leaf.reader.data.local.files.RecoveredBookCopy
import com.leaf.reader.data.local.files.StorageRecoveryReport
import com.leaf.reader.data.readium.PublicationAccessException
import com.leaf.reader.data.readium.PublicationRepository
import com.leaf.reader.domain.model.Book
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.util.UUID
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.*

data class ImportResult(val bookId: String, val title: String, val duplicate: Boolean, val restored: Boolean = false)

class BookImportException(cause: Throwable? = null) : IOException(
    "This book could not be saved. Check free storage and select the file again to retry.", cause,
)

@Singleton
class BookImportRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val files: ManagedBookFiles,
    private val books: BookRepository,
    private val publications: PublicationRepository,
) {
    private var recoveryCompleted = false // Guarded by the shared managed-storage lock.

    suspend fun importBook(uri: Uri): ImportResult = safeImport {
        withContext(Dispatchers.IO) {
            files.exclusive {
                if (!recoveryCompleted) {
                    recoverLocked(books.allStoragePaths(), books.allCoverPaths())
                    recoveryCompleted = true
                }
                importLocked(uri)
            }
        }
    }

    suspend fun recoverManagedFiles(): StorageRecoveryReport = safeImport {
        withContext(Dispatchers.IO) {
            files.exclusive {
                recoverLocked(books.allStoragePaths(), books.allCoverPaths()).also { recoveryCompleted = true }
            }
        }
    }

    suspend fun storageUsageBytes(): Long = safeImport { files.storageUsageBytes() }
    suspend fun clearTemporaryCache(): Int = safeImport { files.clearTemporaryCache() }

    suspend fun recoveredBooks(): List<RecoveredBookCopy> = safeImport {
        withContext(Dispatchers.IO) { files.exclusive { recoveredBooksLocked() } }
    }

    /** The source is consumed only after validated bytes are available in the library. */
    suspend fun restoreRecoveredBook(token: String): Boolean = safeImport {
        withContext(Dispatchers.IO) {
            files.exclusive {
                val source = recoveredBookFileLocked(token) ?: throw PublicationImportException(ImportProblem.UNREADABLE_CONTENT)
                importLocked(Uri.fromFile(source), DocumentInfo("Recovered book.${source.extension}", source.length()))
                source.delete()
            }
        }
    }

    private suspend fun ManagedBookFiles.importLocked(uri: Uri, recovered: DocumentInfo? = null): ImportResult {
        val document = recovered ?: documentInfo(uri)
        val format = PublicationValidation.formatFor(document.name)
        if (document.size != null && document.size > BoundedFileCopier.DEFAULT_MAX_BYTES) throw PublicationImportException(ImportProblem.FILE_TOO_LARGE)
        var temporary: File? = null
        var coverTemporary: File? = null
        var committedBook: File? = null
        var committedCover: File? = null
        var completed = false
        try {
            temporary = newTemporary(format)
            val coroutine = currentCoroutineContext()
            val copied = context.contentResolver.openInputStream(uri)?.use { source ->
                val cancellable = object : FilterInputStream(source) {
                    override fun read(): Int { coroutine.ensureActive(); return super.read() }
                    override fun read(buffer: ByteArray, offset: Int, length: Int): Int { coroutine.ensureActive(); return `in`.read(buffer, offset, length) }
                }
                FileOutputStream(temporary).use { output ->
                    BoundedFileCopier.copy(cancellable, output, availableBytes = ::availableBytes).also { sync(output) }
                }
            } ?: throw PublicationImportException(ImportProblem.UNREADABLE_CONTENT)
            currentCoroutineContext().ensureActive()
            PublicationValidation.validate(temporary, format)
            val existing = books.findByHash(copied.sha256)
            if (existing != null && managedCopyHealthy(existing)) {
                completed = true
                return ImportResult(existing.id, existing.title, duplicate = true)
            }
            val id = existing?.id ?: UUID.randomUUID().toString()
            if (existing != null && (!isExpectedBook(existing.storagePath, id, format) || existing.format != format)) throw BookImportException()
            val inspection = publications.inspect(temporary)
            val metadata = try {
                MetadataFallback.resolve(inspection.title, inspection.authors, inspection.description, document.name).also(::validateMetadata)
            } catch (failure: Throwable) {
                inspection.cover?.recycle()
                throw failure
            }
            val coverBitmap = inspection.cover ?: fallbackCover(metadata.title, metadata.author)
            try {
                val shouldCreateCover = existing == null || existing.coverPath?.let { !File(it).isFile && isExpectedCover(it, id) } == true
                if (shouldCreateCover) {
                    coverTemporary = newCoverTemporary()
                    FileOutputStream(coverTemporary).use { output ->
                        if (!coverBitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) throw BookImportException()
                        sync(output)
                    }
                }
            } finally { coverBitmap.recycle() }
            currentCoroutineContext().ensureActive()
            if (existing != null && File(existing.storagePath).exists() && !quarantineBookCopy(existing.storagePath)) throw BookImportException()
            committedBook = commitBook(temporary, id, format)
            temporary = null
            coverTemporary?.let { committedCover = commitCover(it, id); coverTemporary = null }
            if (existing == null) {
                books.add(Book(id, metadata.title, metadata.author, metadata.description, format,
                    checkNotNull(committedBook).absolutePath, uri.takeIf { recovered == null }?.toString(), committedCover?.absolutePath,
                    copied.byteCount, copied.sha256, System.currentTimeMillis()))
            }
            // The existing row, locator and annotations survive restoration unchanged.
            completed = true
            return ImportResult(id, existing?.title ?: metadata.title, duplicate = existing != null, restored = existing != null)
        } finally {
            withContext(NonCancellable) {
                try { discardTemporary(temporary) } catch (_: Exception) { }
                try { discardTemporary(coverTemporary) } catch (_: Exception) { }
                if (!completed && (committedBook != null || committedCover != null)) {
                    // A Room write may commit just before cancellation. Never remove a referenced file.
                    try { preserveUnreferencedAttempt(committedBook, committedCover, books.allStoragePaths(), books.allCoverPaths()) }
                    catch (_: Exception) { /* Keep files intact until a successful recovery snapshot. */ }
                }
            }
        }
    }

    private data class DocumentInfo(val name: String, val size: Long?)

    private suspend fun ManagedBookFiles.managedCopyHealthy(book: Book): Boolean {
        if (!isExpectedBook(book.storagePath, book.id, book.format)) return false
        val file = File(book.storagePath)
        if (!file.isFile || !file.canRead() || file.length() != book.fileSize || file.length() > BoundedFileCopier.DEFAULT_MAX_BYTES) return false
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) } == book.contentHash
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: IOException) { false }
    }

    private fun validateMetadata(metadata: BookMetadata) {
        if (exceedsUtf8Bytes(metadata.title, 16 * 1024) || exceedsUtf8Bytes(metadata.author, 64 * 1024) ||
            exceedsUtf8Bytes(metadata.description.orEmpty(), 512 * 1024)) throw PublicationImportException(ImportProblem.RESOURCE_LIMIT)
    }

    /** Count without allocating another potentially multi-megabyte UTF-8 byte array. */
    private fun exceedsUtf8Bytes(value: String, maximum: Int): Boolean {
        var bytes = 0
        var index = 0
        while (index < value.length) {
            val character = value[index]
            bytes += when {
                character.code < 0x80 -> 1
                character.code < 0x800 -> 2
                character.isHighSurrogate() && index + 1 < value.length && value[index + 1].isLowSurrogate() -> { index++; 4 }
                character.isSurrogate() -> 1
                else -> 3
            }
            if (bytes > maximum) return true
            index++
        }
        return false
    }

    private fun documentInfo(uri: Uri): DocumentInfo {
        if (uri.scheme !in setOf("content", "file")) throw PublicationImportException(ImportProblem.UNREADABLE_CONTENT)
        var name: String? = null
        var size: Long? = null
        try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { if (!cursor.isNull(it)) name = cursor.getString(it) }
                    cursor.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 }?.let { if (!cursor.isNull(it)) size = cursor.getLong(it).takeIf { value -> value >= 0 } }
                }
            }
        } catch (failure: SecurityException) { throw PublicationImportException(ImportProblem.UNREADABLE_CONTENT, failure) }
        catch (_: IllegalArgumentException) { /* A file URI or provider may not implement metadata queries. */ }
        val typeExtension = when (context.contentResolver.getType(uri)) {
            "application/epub+zip" -> ".epub"
            "application/pdf" -> ".pdf"
            else -> ""
        }
        return DocumentInfo(name?.takeIf { it.isNotBlank() } ?: uri.lastPathSegment?.takeIf { it.substringAfterLast('.', "").isNotEmpty() } ?: "Imported book$typeExtension", size)
    }

    private fun fallbackCover(title: String, author: String): Bitmap {
        val bitmap = Bitmap.createBitmap(512, 768, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.rgb(38, 61, 46))
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(161, 193, 157); strokeWidth = 2f }
        canvas.drawLine(48f, 114f, 464f, 114f, line)
        val label = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(214, 225, 201); textSize = 18f }
        canvas.drawText("LEAF LIBRARY", 48f, 90f, label)
        drawCoverText(canvas, title, 48f, 185f, 416, 42f, 8, Typeface.create("serif", Typeface.NORMAL))
        drawCoverText(canvas, author, 48f, 652f, 416, 22f, 2, Typeface.create("sans-serif", Typeface.NORMAL))
        return bitmap
    }

    private fun drawCoverText(canvas: Canvas, text: String, x: Float, y: Float, width: Int, size: Float, lines: Int, typeface: Typeface) {
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(239, 236, 216); textSize = size; this.typeface = typeface }
        val display = text.take(2_048)
        val layout = StaticLayout.Builder.obtain(display, 0, display.length, paint, width)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL).setMaxLines(lines).setEllipsize(android.text.TextUtils.TruncateAt.END).build()
        canvas.save(); canvas.translate(x, y); layout.draw(canvas); canvas.restore()
    }

    private suspend fun <T> safeImport(block: suspend () -> T): T = try { block() }
    catch (cancelled: CancellationException) { throw cancelled }
    catch (failure: PublicationImportException) { throw failure }
    catch (failure: PublicationAccessException) { throw failure }
    catch (failure: BookImportException) { throw failure }
    catch (failure: Exception) { throw BookImportException(failure) }
}
