package com.leaf.reader.data.local.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.leaf.reader.data.local.database.entity.ReadingProgressEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ReadingDao {
    @Query("SELECT * FROM reading_progress WHERE bookId = :bookId")
    fun observeProgress(bookId: String): Flow<ReadingProgressEntity?>
    @Query("SELECT * FROM reading_progress WHERE bookId = :bookId")
    suspend fun getProgress(bookId: String): ReadingProgressEntity?
    @Upsert suspend fun saveProgress(progress: ReadingProgressEntity)
    @Query("UPDATE books SET status = CASE WHEN :progression >= 1.0 THEN 'COMPLETED' WHEN status = 'UNREAD' AND :progression > 0 THEN 'READING' ELSE status END WHERE id = :bookId")
    suspend fun updateStatusForProgress(bookId: String, progression: Double)
}
