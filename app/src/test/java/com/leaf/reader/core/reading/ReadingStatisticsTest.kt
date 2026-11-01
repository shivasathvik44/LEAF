package com.leaf.reader.core.reading

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ReadingStatisticsTest {
    private val utc = ZoneId.of("UTC")

    @Test
    fun intervalsSplitAtLocalMidnight() {
        val zone = ZoneId.of("Asia/Kolkata")
        val result = StatisticsCalculator.aggregate(
            listOf(interval("2026-11-01T18:15:00Z", "2026-11-01T18:45:00Z")),
            zone,
            Instant.parse("2026-11-02T12:00:00Z"),
        )
        assertEquals(1_800_000L, result.totalActiveMs)
        assertEquals(900_000L, result.dailyActiveMs[LocalDate.of(2026, 11, 1)])
        assertEquals(900_000L, result.dailyActiveMs[LocalDate.of(2026, 11, 2)])
        assertEquals(2, result.currentStreak)
    }

    @Test
    fun daylightSavingDayUsesActualElapsedTime() {
        val zone = ZoneId.of("America/New_York")
        val day = LocalDate.of(2026, 3, 8)
        val result = StatisticsCalculator.aggregate(
            listOf(ActiveReadingInterval("book", day.atStartOfDay(zone).toInstant(), day.plusDays(1).atStartOfDay(zone).toInstant())),
            zone,
            Instant.parse("2026-03-09T12:00:00Z"),
        )
        assertEquals(23 * 3_600_000L, result.totalActiveMs)
        assertEquals(23 * 3_600_000L, result.dailyActiveMs[day])
    }

    @Test
    fun repeatedAndOverlappingCheckpointsDoNotInflateReadingTime() {
        val first = interval("2026-11-01T10:00:00Z", "2026-11-01T10:10:00Z")
        val overlap = interval("2026-11-01T10:05:00Z", "2026-11-01T10:15:00Z", "second")
        val result = StatisticsCalculator.aggregate(listOf(first, first, overlap), utc, Instant.parse("2026-11-01T12:00:00Z"))
        assertEquals(900_000L, result.totalActiveMs)
        assertEquals(2, result.booksRead)
    }

    @Test
    fun futureIntervalsAndFuturePortionsAreExcluded() {
        val result = StatisticsCalculator.aggregate(
            listOf(
                interval("2026-11-01T10:00:00Z", "2026-11-01T11:00:00Z"),
                interval("2026-11-02T10:00:00Z", "2026-11-02T11:00:00Z", "future"),
            ),
            utc,
            Instant.parse("2026-11-01T10:15:00Z"),
        )
        assertEquals(900_000L, result.totalActiveMs)
        assertEquals(1, result.booksRead)
    }

    @Test
    fun streakAllowsTodayToRemainUnfinishedAndTracksLongestRun() {
        val intervals = listOf(25, 26, 27, 29, 30, 31).map { day ->
            interval("2026-10-${day}T10:00:00Z", "2026-10-${day}T10:01:00Z")
        }
        val result = StatisticsCalculator.aggregate(intervals, utc, Instant.parse("2026-11-01T12:00:00Z"))
        assertEquals(3, result.currentStreak)
        assertEquals(3, result.longestStreak)
        val later = StatisticsCalculator.aggregate(intervals, utc, Instant.parse("2026-11-02T12:00:00Z"))
        assertEquals(0, later.currentStreak)
    }

    @Test
    fun emptyHistoryHasZeroStatistics() {
        val result = StatisticsCalculator.aggregate(emptyList(), utc, Instant.parse("2026-11-01T12:00:00Z"))
        assertEquals(ReadingStatistics(0L, emptyMap(), 0, 0, 0), result)
    }

    @Test
    fun backwardsIntervalIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            interval("2026-11-01T11:00:00Z", "2026-11-01T10:00:00Z")
        }
    }

    private fun interval(start: String, end: String, bookId: String = "book") =
        ActiveReadingInterval(bookId, Instant.parse(start), Instant.parse(end))
}
