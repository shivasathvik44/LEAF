package com.leaf.reader.feature.statistics

import com.leaf.reader.core.reading.ActiveReadingInterval
import com.leaf.reader.core.reading.StatisticsCalculator
import com.leaf.reader.domain.model.Book
import com.leaf.reader.domain.model.ReadingSession
import com.leaf.reader.domain.model.ReadingStatus
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Projects closed, observed checkpoints off the UI thread; history is coalesced by book/day. */
object ActivityProjection {
    fun build(books: List<Book>, sessions: List<ReadingSession>, zone: ZoneId, asOf: Instant): ActivitySnapshot {
        val booksById = books.associateBy(Book::id)
        val accepted = sessions.asSequence()
            .filter { it.activeDurationMs > 0 && it.startedAt >= 0 && it.endedAt > it.startedAt &&
                it.activeDurationMs <= it.endedAt - it.startedAt }
            .map { session ->
                // New recorder checkpoints retain exact active endpoints. For a legacy
                // record containing inactive wall time, credit only its trailing active
                // duration; the unknown part of its wall span never earns reading time.
                ActiveReadingInterval(session.bookId,
                    Instant.ofEpochMilli(session.endedAt - session.activeDurationMs),
                    Instant.ofEpochMilli(session.endedAt))
            }
            .filter { it.start.isBefore(asOf) }
            .map { it.copy(end = minOf(it.end, asOf)) }
            .sortedWith(compareBy<ActiveReadingInterval> { it.start }.thenByDescending { it.end }.thenBy { it.bookId })
            .toList()
        val summary = StatisticsCalculator.aggregate(accepted, zone, asOf)

        // Allocate each wall-clock instant only once even if overlapping/replayed checkpoints
        // reference different books. This keeps history totals consistent with the summary.
        val unique = mutableListOf<ActiveReadingInterval>()
        var consumedUntil: Instant? = null
        accepted.forEach { interval ->
            val start = consumedUntil?.let { maxOf(it, interval.start) } ?: interval.start
            if (start.isBefore(interval.end)) {
                unique += interval.copy(start = start)
                consumedUntil = interval.end
            }
        }
        data class HistoryKey(val bookId: String, val day: LocalDate)
        data class Part(var duration: Long, var first: Instant, var last: Instant)
        val parts = linkedMapOf<HistoryKey, Part>()
        unique.forEach { interval ->
            var cursor = interval.start
            while (cursor.isBefore(interval.end)) {
                val day = cursor.atZone(zone).toLocalDate()
                val stop = minOf(interval.end, day.plusDays(1).atStartOfDay(zone).toInstant())
                val duration = Duration.between(cursor, stop).toMillis()
                if (duration > 0L) {
                    val key = HistoryKey(interval.bookId, day)
                    val part = parts[key]
                    if (part == null) parts[key] = Part(duration, cursor, stop)
                    else { part.duration += duration; part.first = minOf(part.first, cursor); part.last = maxOf(part.last, stop) }
                }
                cursor = stop
            }
        }
        val history = parts.map { (key, part) ->
            val book = booksById[key.bookId]
            ReadingHistoryItem(key.bookId, book?.title ?: "Book unavailable", book?.author.orEmpty(),
                key.day, part.duration, part.first.toEpochMilli(), part.last.toEpochMilli(), book != null)
        }.sortedWith(compareByDescending<ReadingHistoryItem> { it.day }.thenByDescending { it.lastReadAt }.thenBy { it.bookId })
        val startedIds = accepted.mapTo(mutableSetOf()) { it.bookId }
        // An opening timestamp alone can describe a failed reader launch. Count actual
        // observed reading or an explicit/in-progress reading status instead.
        books.asSequence().filter { it.status != ReadingStatus.UNREAD }
            .forEach { startedIds += it.id }
        return ActivitySnapshot(summary, startedIds.size, books.count { it.status == ReadingStatus.COMPLETED },
            history, asOf.atZone(zone).toLocalDate(), zone)
    }
}
