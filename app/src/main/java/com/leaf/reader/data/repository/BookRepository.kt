package com.leaf.reader.data.repository

import com.leaf.reader.data.local.database.LeafDatabase
import com.leaf.reader.data.local.database.entity.BookEntity
import com.leaf.reader.data.local.database.entity.BookWithProgress
import com.leaf.reader.domain.model.Book
import com.leaf.reader.domain.model.ReadingStatus
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

@Singleton
class BookRepository @Inject constructor(private val database: LeafDatabase) {
    val books: Flow<List<Book>> = database.books().observeBooks().map { rows -> rows.map { it.toDomain() } }

    suspend fun get(id: String): Book? = database.books().get(id)?.toDomain()
    suspend fun findByHash(hash: String): Book? = database.books().findByHash(hash)?.toDomain()

    suspend fun add(book: Book) {
        UUID.fromString(book.id)
        require(book.title.isNotBlank()) { "A book needs a title." }
        require(book.author.isNotBlank()) { "A book needs an author display value." }
        require(book.storagePath.isNotBlank()) { "A managed storage path is required." }
        require(book.fileSize > 0) { "A book cannot be empty." }
        require(book.contentHash.matches(Regex("[a-fA-F0-9]{64}"))) { "A SHA-256 digest is required." }
        database.books().insert(BookEntity(book.id, book.title, book.author, book.description,
            book.format, book.storagePath, book.sourceUri, book.coverPath, book.fileSize,
            book.contentHash.lowercase(), book.importedAt, book.lastOpenedAt, book.status))
    }

    suspend fun markOpened(id: String) = database.books().markOpened(id, System.currentTimeMillis())
    suspend fun setStatus(id: String, status: ReadingStatus) = database.books().setStatus(id, status)

    /** Database-only removal. The removal use case owns explicit managed-file confirmation. */
    suspend fun removeRecord(id: String) = database.books().delete(id)
    suspend fun allStoragePaths(): List<String> = database.books().allStoragePaths()
    suspend fun allCoverPaths(): List<String> = database.books().allCoverPaths()
}

private fun BookWithProgress.toDomain(): Book = Book(
    book.id, book.title, book.author, book.description, book.format, book.storagePath,
    book.sourceUri, book.coverPath, book.fileSize, book.contentHash, book.importedAt,
    book.lastOpenedAt, book.status, progress?.progression ?: 0.0,
)
