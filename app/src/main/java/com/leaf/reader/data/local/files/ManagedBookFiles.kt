package com.leaf.reader.data.local.files

import android.content.Context
import android.os.StatFs
import com.leaf.reader.core.util.PublicationFormat
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class BookStorageException(cause: Throwable? = null) : IOException(
    "Leaf could not update its private storage. Free some space and try again.", cause,
)

data class StorageRecoveryReport(
    val removedTemporaryFiles: Int,
    val quarantinedBooks: Int,
    val quarantinedCovers: Int,
    val retainedTemporaryEntries: Int,
    val missingBookCopies: Int,
)

data class RecoveredBookCopy(val token: String, val format: PublicationFormat, val bytes: Long, val modifiedAt: Long)

/** Owns private imported copies only. No operation accepts or removes an original source URI. */
@Singleton
class ManagedBookFiles @Inject constructor(@ApplicationContext context: Context) {
    private val applicationFiles = context.filesDir
    private val mutex = Mutex()

    internal suspend fun <T> exclusive(block: suspend ManagedBookFiles.() -> T): T =
        mutex.withLock { block() }

    internal fun newTemporary(format: PublicationFormat): File =
        File.createTempFile("leaf-import-", ".${format.extension}", temporaryDirectory())

    internal fun newCoverTemporary(): File = File.createTempFile("leaf-cover-", ".png", temporaryDirectory())
    internal fun availableBytes(): Long = StatFs(root().absolutePath).availableBytes
    internal fun sync(output: FileOutputStream) { output.flush(); output.fd.sync() }

    internal fun expectedBook(bookId: String, format: PublicationFormat): File =
        File(booksDirectory(), "${uuid(bookId)}.${format.extension}")

    internal fun expectedCover(bookId: String): File = File(coversDirectory(), "${uuid(bookId)}.png")

    internal fun commitBook(temporary: File, bookId: String, format: PublicationFormat): File =
        moveTemporary(temporary, expectedBook(bookId, format))

    internal fun commitCover(temporary: File, bookId: String): File = moveTemporary(temporary, expectedCover(bookId))

    internal fun isExpectedBook(path: String, bookId: String, format: PublicationFormat): Boolean =
        File(path).canonicalFile == expectedBook(bookId, format)

    internal fun isExpectedCover(path: String, bookId: String): Boolean = File(path).canonicalFile == expectedCover(bookId)

    internal fun discardTemporary(file: File?): Boolean {
        if (file == null || !file.exists()) return true
        return isDirectChild(file, temporaryDirectory()) && ownedTemporary(file) && file.delete()
    }

    /** Caller must obtain a complete database snapshot before invoking this under [exclusive]. */
    internal fun recoverLocked(bookPaths: List<String>, coverPaths: List<String>): StorageRecoveryReport {
        val books = bookPaths.map { File(it).canonicalPath }.toSet()
        val covers = coverPaths.map { File(it).canonicalPath }.toSet()
        val temporary = clearTemporaryLocked()
        return StorageRecoveryReport(
            temporary.first,
            quarantineUnreferenced(booksDirectory(), books, "books"),
            quarantineUnreferenced(coversDirectory(), covers, "covers"),
            temporary.second,
            books.count { !File(it).isFile },
        )
    }

    /** A cancelled insert may have committed. Preserve only files proven unreferenced afterward. */
    internal fun preserveUnreferencedAttempt(book: File?, cover: File?, bookPaths: List<String>, coverPaths: List<String>) {
        val books = bookPaths.map { File(it).canonicalPath }.toSet()
        val covers = coverPaths.map { File(it).canonicalPath }.toSet()
        if (book != null && book.canonicalPath !in books) quarantineBookCopy(book.path)
        if (cover != null && cover.canonicalPath !in covers) quarantineCoverCopy(cover.path)
    }

    internal fun deleteBookCopy(path: String): Boolean = deleteOwned(path, booksDirectory())
    internal fun deleteCoverCopy(path: String): Boolean = deleteOwned(path, coversDirectory())
    internal fun quarantineBookCopy(path: String): Boolean = quarantineOwned(path, booksDirectory(), "books")
    internal fun quarantineCoverCopy(path: String): Boolean = quarantineOwned(path, coversDirectory(), "covers")

    suspend fun storageUsageBytes(): Long = withContext(Dispatchers.IO) {
        exclusive {
            listOf(booksDirectory(), coversDirectory(), recoveryDirectory()).sumOf { directory ->
                directory.walkTopDown().onEnter { it.canonicalFile == it.absoluteFile }.filter { it.isFile && it.canonicalFile == it.absoluteFile }
                    .sumOf { it.length() }
            }
        }
    }

    /** Clears only Leaf-owned abandoned import files. Recovery copies are deliberately excluded. */
    suspend fun clearTemporaryCache(): Int = withContext(Dispatchers.IO) { exclusive { clearTemporaryLocked().first } }

    internal fun recoveredBooksLocked(): List<RecoveredBookCopy> {
        val directory = recoveryBooksDirectory()
        return directory.listFiles().orEmpty().mapNotNull { file ->
            val format = PublicationFormat.entries.find { it.extension == file.extension } ?: return@mapNotNull null
            if (!file.isFile || !isDirectChild(file, directory)) return@mapNotNull null
            RecoveredBookCopy(file.name, format, file.length(), file.lastModified())
        }.sortedWith(compareByDescending<RecoveredBookCopy> { it.modifiedAt }.thenBy { it.token })
    }

    internal fun recoveredBookFileLocked(token: String): File? {
        val directory = recoveryBooksDirectory()
        val file = File(directory, token)
        return file.takeIf { it.isFile && isDirectChild(it, directory) && PublicationFormat.entries.any { format -> format.extension == it.extension } }
    }

    private fun clearTemporaryLocked(): Pair<Int, Int> {
        var removed = 0
        var retained = 0
        temporaryDirectory().listFiles().orEmpty().forEach { file ->
            if (file.isFile && isDirectChild(file, temporaryDirectory()) && ownedTemporary(file) && file.delete()) removed++
            else retained++
        }
        return removed to retained
    }

    private fun quarantineUnreferenced(directory: File, referenced: Set<String>, category: String): Int {
        var moved = 0
        directory.listFiles().orEmpty().forEach { file ->
            if (file.isFile && isDirectChild(file, directory) && file.canonicalPath !in referenced && quarantineOwned(file.path, directory, category)) moved++
        }
        return moved
    }

    private fun deleteOwned(path: String, directory: File): Boolean {
        val file = File(path)
        if (!file.exists()) return true
        return file.isFile && isDirectChild(file, directory) && file.delete()
    }

    private fun quarantineOwned(path: String, directory: File, category: String): Boolean {
        val file = File(path)
        if (!file.exists()) return true
        if (!file.isFile || !isDirectChild(file, directory)) return false
        val destination = File(ensureDirectory(File(recoveryDirectory(), category)), "${UUID.randomUUID()}-${file.name}")
        return file.renameTo(destination)
    }

    private fun moveTemporary(temporary: File, target: File): File {
        if (!temporary.isFile || !isDirectChild(temporary, temporaryDirectory()) || !ownedTemporary(temporary) || target.exists()) throw BookStorageException()
        if (!temporary.renameTo(target)) throw BookStorageException()
        return target
    }

    private fun root(): File = applicationFiles.canonicalFile.also { if (!it.isDirectory && !it.mkdirs()) throw BookStorageException() }
    private fun booksDirectory(): File = ensureDirectory(File(root(), "books"))
    private fun coversDirectory(): File = ensureDirectory(File(root(), "covers"))
    private fun temporaryDirectory(): File = ensureDirectory(File(ensureDirectory(File(root(), "cache")), "temporary-imports"))
    private fun recoveryDirectory(): File = ensureDirectory(File(root(), "recovery"))
    private fun recoveryBooksDirectory(): File = ensureDirectory(File(recoveryDirectory(), "books"))

    private fun ensureDirectory(directory: File): File {
        if (directory.canonicalFile != directory.absoluteFile || (!directory.isDirectory && !directory.mkdirs())) throw BookStorageException()
        return directory
    }

    private fun isDirectChild(file: File, parent: File): Boolean =
        file.canonicalFile == file.absoluteFile && file.canonicalFile.parentFile == parent.canonicalFile

    private fun ownedTemporary(file: File): Boolean =
        (file.name.startsWith("leaf-import-") || file.name.startsWith("leaf-cover-")) && file.extension in setOf("epub", "pdf", "png")

    private fun uuid(id: String): String = UUID.fromString(id).toString()
}
