package com.leaf.reader.data.repository

import androidx.room.withTransaction
import com.leaf.reader.data.local.database.LeafDatabase
import com.leaf.reader.data.local.database.entity.ReadingProgressEntity
import com.leaf.reader.domain.model.ReadingProgress
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

@Singleton
class ReadingRepository @Inject constructor(private val database: LeafDatabase) {
    fun progress(bookId: String): Flow<ReadingProgress?> = database.reading().observeProgress(bookId)
        .map { it?.toDomain() }
    suspend fun getProgress(bookId: String): ReadingProgress? = database.reading().getProgress(bookId)?.toDomain()

    suspend fun saveProgress(bookId: String, locatorJson: String, progression: Double) {
        require(locatorJson.isNotBlank()) { "The complete locator JSON is required." }
        require(progression.isFinite() && progression in 0.0..1.0) { "Progress must be between zero and one." }
        database.withTransaction {
            database.reading().saveProgress(ReadingProgressEntity(bookId, locatorJson, progression, System.currentTimeMillis()))
            database.reading().updateStatusForProgress(bookId, progression)
        }
    }
}

private fun ReadingProgressEntity.toDomain() = ReadingProgress(bookId, locatorJson, progression, updatedAt)
