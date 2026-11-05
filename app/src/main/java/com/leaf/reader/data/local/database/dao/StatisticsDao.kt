package com.leaf.reader.data.local.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.leaf.reader.data.local.database.entity.ReadingSessionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface StatisticsDao {
    @Query("SELECT * FROM reading_sessions ORDER BY startedAt DESC, id")
    fun observeSessions(): Flow<List<ReadingSessionEntity>>
    @Query("SELECT * FROM reading_sessions WHERE id = :id") suspend fun get(id: String): ReadingSessionEntity?
    @Query("SELECT EXISTS(SELECT 1 FROM books WHERE id = :bookId)") suspend fun bookExists(bookId: String): Boolean
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insert(session: ReadingSessionEntity): Long
}
