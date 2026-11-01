package com.leaf.reader.core.reading

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** A checkpoint-sized interval known to have been actively read, not a wall-clock open session. */
data class ActiveReadingInterval(val bookId: String, val start: Instant, val end: Instant) {
    init {
        require(!end.isBefore(start)) { "An interval cannot end before it starts." }
    }
}

data class ReadingStatistics(
    val totalActiveMs: Long,
    val dailyActiveMs: Map<LocalDate, Long>,
    val currentStreak: Int,
    val longestStreak: Int,
    val booksRead: Int,
)

object StatisticsCalculator {
    /**
     * Splits actual active intervals at local midnights, including daylight-saving boundaries.
     * Overlapping/replayed checkpoints count once, and future time is clipped to [asOf].
     */
    fun aggregate(
        intervals: Iterable<ActiveReadingInterval>,
        zone: ZoneId,
        asOf: Instant,
    ): ReadingStatistics {
        val accepted = intervals.filter { it.start.isBefore(asOf) && it.end.isAfter(it.start) }
            .map { it.copy(end = minOf(it.end, asOf)) }
            .sortedBy(ActiveReadingInterval::start)
        val merged = mutableListOf<Pair<Instant, Instant>>()
        accepted.forEach { interval ->
            val previous = merged.lastOrNull()
            if (previous != null && !interval.start.isAfter(previous.second)) {
                merged[merged.lastIndex] = previous.first to maxOf(previous.second, interval.end)
            } else {
                merged += interval.start to interval.end
            }
        }
        val daily = sortedMapOf<LocalDate, Long>()
        merged.forEach { (start, end) ->
            var cursor = start
            while (cursor.isBefore(end)) {
                val day = cursor.atZone(zone).toLocalDate()
                val nextMidnight = day.plusDays(1).atStartOfDay(zone).toInstant()
                val stop = minOf(end, nextMidnight)
                val milliseconds = Duration.between(cursor, stop).toMillis()
                if (milliseconds > 0) daily[day] = (daily[day] ?: 0L) + milliseconds
                cursor = stop
            }
        }
        val today = asOf.atZone(zone).toLocalDate()
        var streakDay = if (daily.containsKey(today)) today else today.minusDays(1)
        var currentStreak = 0
        while (daily.containsKey(streakDay)) {
            currentStreak++
            streakDay = streakDay.minusDays(1)
        }
        var longestStreak = 0
        var run = 0
        var previousDay: LocalDate? = null
        daily.keys.forEach { day ->
            run = if (previousDay?.plusDays(1) == day) run + 1 else 1
            longestStreak = maxOf(longestStreak, run)
            previousDay = day
        }
        return ReadingStatistics(
            totalActiveMs = daily.values.sum(),
            dailyActiveMs = daily.toMap(),
            currentStreak = currentStreak,
            longestStreak = longestStreak,
            booksRead = accepted.map(ActiveReadingInterval::bookId).distinct().size,
        )
    }
}
