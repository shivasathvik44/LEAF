package com.leaf.reader.core.util

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

enum class ImportProblem {
    UNSUPPORTED_FORMAT,
    EMPTY_FILE,
    INVALID_CONTENT,
    FILE_TOO_LARGE,
    INSUFFICIENT_STORAGE,
    UNREADABLE_CONTENT,
}

/** Safe to display: messages never include a source URI, file path, or book content. */
class PublicationImportException(
    val problem: ImportProblem,
    cause: Throwable? = null,
) : IOException(
    when (problem) {
        ImportProblem.UNSUPPORTED_FORMAT -> "Choose an EPUB or PDF file."
        ImportProblem.EMPTY_FILE -> "This file is empty. Choose a readable EPUB or PDF."
        ImportProblem.INVALID_CONTENT -> "This book is damaged or is not a valid EPUB or PDF."
        ImportProblem.FILE_TOO_LARGE -> "This book exceeds the import size limit."
        ImportProblem.INSUFFICIENT_STORAGE -> "There is not enough free storage to import this book. Free some space and try again."
        ImportProblem.UNREADABLE_CONTENT -> "This file could not be read. Select it again and try importing it."
    },
    cause,
)

data class CopyResult(val byteCount: Long, val sha256: String)

/** Copies a stream in bounded memory. The caller owns streams and partial-file cleanup. */
object BoundedFileCopier {
    const val DEFAULT_MAX_BYTES: Long = 1_073_741_824L
    const val DEFAULT_RESERVE_BYTES: Long = 8_388_608L

    fun copy(
        input: InputStream,
        output: OutputStream,
        maxBytes: Long = DEFAULT_MAX_BYTES,
        availableBytes: () -> Long,
        reserveBytes: Long = DEFAULT_RESERVE_BYTES,
    ): CopyResult {
        require(maxBytes > 0) { "The size limit must be positive." }
        require(reserveBytes >= 0) { "The storage reserve cannot be negative." }
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        try {
            while (true) {
                var count = input.read(buffer)
                if (count < 0) break
                // Some ContentProviders return zero without reaching EOF. Make forward progress
                // without spinning, while preserving the same size/storage checks below.
                if (count == 0) {
                    val byte = input.read()
                    if (byte < 0) break
                    buffer[0] = byte.toByte()
                    count = 1
                }
                if (count.toLong() > maxBytes - total) {
                    throw PublicationImportException(ImportProblem.FILE_TOO_LARGE)
                }
                val available = availableBytes()
                if (available < reserveBytes || available - reserveBytes < count.toLong()) {
                    throw PublicationImportException(ImportProblem.INSUFFICIENT_STORAGE)
                }
                output.write(buffer, 0, count)
                digest.update(buffer, 0, count)
                total += count
            }
            if (total == 0L) throw PublicationImportException(ImportProblem.EMPTY_FILE)
            output.flush()
        } catch (error: PublicationImportException) {
            throw error
        } catch (error: IOException) {
            throw PublicationImportException(ImportProblem.UNREADABLE_CONTENT, error)
        }
        return CopyResult(total, digest.digest().joinToString("") { "%02x".format(it) })
    }
}
