package com.leaf.reader.data.repository

import androidx.room.withTransaction
import com.leaf.reader.data.local.database.LeafDatabase
import com.leaf.reader.data.local.database.entity.ReadingSessionEntity
import com.leaf.reader.domain.model.ReadingSession
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

@Singleton
class StatisticsRepository @Inject constructor(private val database: LeafDatabase) {
    val sessions: Flow<List<ReadingSession>> = database.statistics().observeSessions().map { rows -> rows.map {
        ReadingSession(it.id, it.bookId, it.startedAt, it.endedAt, it.activeDurationMs)
    } }

    /** Explicit book removal also removes its history; an in-flight writer must not recreate it. */
    internal suspend fun bookExists(bookId: String): Boolean = database.statistics().bookExists(bookId)

    /** Persist an observed closed checkpoint; callers may retry safely with the same UUID. */
    suspend fun recordInterval(
        bookId: String,
        startedAt: Long,
        endedAt: Long,
        activeDurationMs: Long,
        id: String = UUID.randomUUID().toString(),
    ): String {
        UUID.fromString(id)
        require(startedAt >= 0 && endedAt >= startedAt) { "An interval needs ordered timestamps." }
        require(activeDurationMs > 0 && activeDurationMs <= endedAt - startedAt) { "Only observed active time may be recorded." }
        val session = ReadingSessionEntity(id, bookId, startedAt, endedAt, activeDurationMs)
        database.withTransaction {
            val existing = database.statistics().get(id)
            require(existing == null || existing == session) { "A checkpoint ID cannot describe different intervals." }
            database.statistics().insert(session)
        }
        return id
    }
}
