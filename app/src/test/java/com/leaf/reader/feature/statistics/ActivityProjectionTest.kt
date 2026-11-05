package com.leaf.reader.feature.statistics

import com.leaf.reader.core.util.PublicationFormat
import com.leaf.reader.domain.model.Book
import com.leaf.reader.domain.model.ReadingSession
import com.leaf.reader.domain.model.ReadingStatus
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ActivityProjectionTest {
    private val base = Instant.parse("2026-11-03T12:00:00Z").toEpochMilli()
    private val utc = ZoneId.of("UTC")
    private fun book(title: String = "Silver fern", status: ReadingStatus = ReadingStatus.UNREAD, opened: Long? = null) = Book(
        id = UUID.nameUUIDFromBytes(title.toByteArray()).toString(), title = title, author = "Leaf",
        description = null, format = PublicationFormat.EPUB, storagePath = "/managed/test.epub",
        sourceUri = null, coverPath = null, fileSize = 1, contentHash = "a".repeat(64), importedAt = base,
        lastOpenedAt = opened, status = status)
    private fun session(book: Book, start: Long, end: Long, active: Long = end - start) =
        ReadingSession(UUID.randomUUID().toString(), book.id, start, end, active)

    @Test fun checkpointsCoalesceIntoOneBookAndDayHistoryItem() {
        val book = book()
        val result = ActivityProjection.build(listOf(book), listOf(session(book, base, base + 5_000),
            session(book, base + 5_000, base + 10_000)), utc, Instant.ofEpochMilli(base + 20_000))
        assertEquals(10_000L, result.summary.totalActiveMs)
        assertEquals(1, result.history.size)
        assertEquals(10_000L, result.history.single().activeDurationMs)
        assertEquals(1, result.booksStarted)
    }

    @Test fun historyAndSummarySplitAtTheDevicesLocalMidnight() {
        val book = book()
        val start = Instant.parse("2026-11-02T18:29:58Z").toEpochMilli()
        val result = ActivityProjection.build(listOf(book), listOf(session(book, start, start + 5_000)),
            ZoneId.of("Asia/Kolkata"), Instant.ofEpochMilli(start + 10_000))
        assertEquals(2_000L, result.summary.dailyActiveMs[LocalDate.parse("2026-11-02")])
        assertEquals(3_000L, result.summary.dailyActiveMs[LocalDate.parse("2026-11-03")])
        assertEquals(listOf(3_000L, 2_000L), result.history.map { it.activeDurationMs })
    }

    @Test fun daylightSavingHistoryUsesElapsedTimeOnTheTwentyFiveHourDay() {
        val book = book()
        val start = Instant.parse("2026-11-01T04:00:00Z").toEpochMilli()
        val end = Instant.parse("2026-11-02T05:00:00Z").toEpochMilli()
        val result = ActivityProjection.build(listOf(book), listOf(session(book, start, end)),
            ZoneId.of("America/New_York"), Instant.parse("2026-11-03T12:00:00Z"))
        assertEquals(25 * 3_600_000L, result.summary.totalActiveMs)
        assertEquals(LocalDate.parse("2026-11-01"), result.history.single().day)
        assertEquals(25 * 3_600_000L, result.history.single().activeDurationMs)
    }

    @Test fun overlappingReplaysAndDifferentBooksDoNotInflateHistoryTotals() {
        val first = book("First")
        val second = book("Second")
        val a = session(first, base, base + 1_000)
        val result = ActivityProjection.build(listOf(first, second), listOf(a, a,
            session(second, base + 500, base + 1_500)), utc, Instant.ofEpochMilli(base + 3_000))
        assertEquals(1_500L, result.summary.totalActiveMs)
        assertEquals(1_500L, result.history.sumOf { it.activeDurationMs })
        assertEquals(1_000L, result.history.first { it.bookId == first.id }.activeDurationMs)
        assertEquals(500L, result.history.first { it.bookId == second.id }.activeDurationMs)
    }

    @Test fun aLongWallSpanDoesNotBecomeReadingTime() {
        val book = book()
        val result = ActivityProjection.build(listOf(book), listOf(session(book, base, base + 10_000, 1_000)),
            utc, Instant.ofEpochMilli(base + 20_000))
        assertEquals(1_000L, result.summary.totalActiveMs)
        assertEquals(base + 9_000, result.history.single().firstReadAt)
    }

    @Test fun startedAndCompletedCountsUseSavedStatusesAndActualHistory() {
        val unread = book("Unread")
        val reading = book("Reading", ReadingStatus.READING)
        val completed = book("Completed", ReadingStatus.COMPLETED)
        val reset = book("Reset to unread")
        val opened = book("Previously opened", opened = base)
        val result = ActivityProjection.build(listOf(unread, reading, completed, reset, opened),
            listOf(session(reset, base, base + 1_000)), utc, Instant.ofEpochMilli(base + 2_000))
        assertEquals(3, result.booksStarted)
        assertEquals(1, result.booksCompleted)
    }

    @Test fun anOpeningTimestampAloneDoesNotCountAsStartedReading() {
        val opened = book("Failed opening", opened = base)
        val result = ActivityProjection.build(listOf(opened), emptyList(), utc,
            Instant.ofEpochMilli(base + 2_000))
        assertEquals(0, result.booksStarted)
        assertEquals(0L, result.summary.totalActiveMs)
    }

    @Test fun invalidAndFutureIntervalsAreNotCredited() {
        val book = book()
        val invalid = listOf(session(book, -1, 2), session(book, base, base),
            session(book, base, base + 1_000, 2_000), session(book, base + 5_000, base + 6_000))
        val result = ActivityProjection.build(listOf(book), invalid, utc, Instant.ofEpochMilli(base + 2_000))
        assertEquals(0L, result.summary.totalActiveMs)
        assertTrue(result.history.isEmpty())
        assertEquals(0, result.booksStarted)
    }

    @Test fun acceptedIntervalsAreClippedToTheCurrentTime() {
        val book = book("حديقة الورق")
        val result = ActivityProjection.build(listOf(book), listOf(session(book, base, base + 10_000)),
            utc, Instant.ofEpochMilli(base + 4_000))
        assertEquals(4_000L, result.summary.totalActiveMs)
        assertEquals("حديقة الورق", result.history.single().title)
        assertEquals(base + 4_000, result.history.single().lastReadAt)
    }

    @Test fun aTransientMissingBookCannotProduceAnUnsafeNavigationTarget() {
        val removed = book()
        val result = ActivityProjection.build(emptyList(), listOf(session(removed, base, base + 1_000)),
            utc, Instant.ofEpochMilli(base + 2_000))
        assertFalse(result.history.single().available)
        assertEquals("Book unavailable", result.history.single().title)
    }

    @Test fun displayedDurationsDoNotRoundShortReadingUpToAFullMinute() {
        assertEquals("0 min", formatReadingDuration(0))
        assertEquals("<1 min", formatReadingDuration(59_999))
        assertEquals("1 min", formatReadingDuration(119_999))
        assertEquals("1 hr 1 min", formatReadingDuration(3_660_000))
        assertEquals("less than 1 second", preciseReadingDuration(999))
        assertEquals("1 minute 1 second", preciseReadingDuration(61_000))
    }
}
