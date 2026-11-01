package com.leaf.reader.data.local.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import com.leaf.reader.data.local.database.entity.BookEntity
import com.leaf.reader.data.local.database.entity.BookWithProgress
import com.leaf.reader.domain.model.ReadingStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface BookDao {
    @Transaction @Query("SELECT * FROM books ORDER BY importedAt DESC, id")
    fun observeBooks(): Flow<List<BookWithProgress>>

    @Transaction @Query("SELECT * FROM books WHERE id = :id")
    suspend fun get(id: String): BookWithProgress?

    @Transaction @Query("SELECT * FROM books WHERE contentHash = :hash LIMIT 1")
    suspend fun findByHash(hash: String): BookWithProgress?

    @Insert suspend fun insert(book: BookEntity)

    @Query("UPDATE books SET lastOpenedAt = :now, status = CASE WHEN status = 'UNREAD' THEN 'READING' ELSE status END WHERE id = :id")
    suspend fun markOpened(id: String, now: Long)

    @Query("UPDATE books SET status = :status WHERE id = :id")
    suspend fun setStatus(id: String, status: ReadingStatus)

    @Query("DELETE FROM books WHERE id = :id") suspend fun delete(id: String)
    @Query("SELECT storagePath FROM books") suspend fun allStoragePaths(): List<String>
    @Query("SELECT coverPath FROM books WHERE coverPath IS NOT NULL") suspend fun allCoverPaths(): List<String>
}
