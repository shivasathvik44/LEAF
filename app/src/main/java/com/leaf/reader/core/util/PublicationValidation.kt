package com.leaf.reader.core.util

import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.zip.ZipException
import java.util.zip.ZipFile

enum class PublicationFormat(val extension: String, val mediaType: String) {
    EPUB("epub", "application/epub+zip"),
    PDF("pdf", "application/pdf"),
}

/** An inexpensive structural gate before the publication engine performs full parsing. */
object PublicationValidation {
    fun formatFor(fileName: String): PublicationFormat =
        when (fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
            "epub" -> PublicationFormat.EPUB
            "pdf" -> PublicationFormat.PDF
            else -> throw PublicationImportException(ImportProblem.UNSUPPORTED_FORMAT)
        }

    fun validate(file: File, format: PublicationFormat) {
        if (!file.isFile || !file.canRead()) {
            throw PublicationImportException(ImportProblem.UNREADABLE_CONTENT)
        }
        if (file.length() == 0L) throw PublicationImportException(ImportProblem.EMPTY_FILE)
        try {
            when (format) {
                PublicationFormat.EPUB -> validateEpub(file)
                PublicationFormat.PDF -> validatePdf(file)
            }
        } catch (error: PublicationImportException) {
            throw error
        } catch (error: ZipException) {
            throw PublicationImportException(ImportProblem.INVALID_CONTENT, error)
        } catch (error: IOException) {
            throw PublicationImportException(ImportProblem.UNREADABLE_CONTENT, error)
        }
    }

    private fun validatePdf(file: File) {
        val header = ByteArray(1024)
        val count = file.inputStream().use { it.read(header) }
        val signature = "%PDF-".toByteArray(Charsets.US_ASCII)
        val found = (0..(count - signature.size)).any { offset ->
            signature.indices.all { header[offset + it] == signature[it] }
        }
        if (!found) throw PublicationImportException(ImportProblem.INVALID_CONTENT)
        // Password protection and page-tree validity are checked by Readium's PDF parser.
    }

    private fun validateEpub(file: File) {
        ZipFile(file).use { archive ->
            val mime = archive.getEntry("mimetype")
            val container = archive.getEntry("META-INF/container.xml")
            if (mime == null || mime.isDirectory || container == null || container.isDirectory ||
                container.size == 0L || container.size > 1_048_576L
            ) {
                throw PublicationImportException(ImportProblem.INVALID_CONTENT)
            }
            // Read no more than the fixed MIME value plus one byte, even for a malicious ZIP.
            val expected = PublicationFormat.EPUB.mediaType.toByteArray(Charsets.US_ASCII)
            val actual = archive.getInputStream(mime).use { stream ->
                val value = ByteArray(expected.size + 1)
                var size = 0
                while (size < value.size) {
                    val count = stream.read(value, size, value.size - size)
                    if (count < 0) break
                    if (count == 0) {
                        val byte = stream.read()
                        if (byte < 0) break
                        value[size++] = byte.toByte()
                    } else {
                        size += count
                    }
                }
                value.copyOf(size)
            }
            if (!actual.contentEquals(expected)) {
                throw PublicationImportException(ImportProblem.INVALID_CONTENT)
            }
            // No ZIP entries are extracted. Full package/DRM validation belongs to Readium.
        }
    }
}

data class BookMetadata(val title: String, val author: String, val description: String?)

object MetadataFallback {
    fun resolve(
        title: String?,
        authors: List<String>,
        description: String?,
        fileName: String,
    ): BookMetadata {
        val displayName = fileName.substringAfterLast('/').substringAfterLast('\\')
        val extension = displayName.substringAfterLast('.', "").lowercase(Locale.ROOT)
        val stem = if (extension == "epub" || extension == "pdf") {
            displayName.substringBeforeLast('.')
        } else {
            displayName
        }
        return BookMetadata(
            title = title?.trim()?.takeIf(String::isNotEmpty)
                ?: stem.trim().takeIf(String::isNotEmpty)
                ?: "Untitled book",
            author = authors.map(String::trim).filter(String::isNotEmpty)
                .joinToString(", ").ifEmpty { "Unknown author" },
            description = description?.trim()?.takeIf(String::isNotEmpty),
        )
    }
}
