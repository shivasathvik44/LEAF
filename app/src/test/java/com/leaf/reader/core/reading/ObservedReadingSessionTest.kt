package com.leaf.reader.core.reading

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ObservedReadingSessionTest {
    private val epoch = Instant.parse("2026-11-03T12:00:00Z").toEpochMilli()
    private fun at(elapsed: Long) = ReadingClockSnapshot(elapsed, epoch + elapsed)

    @Test fun idleBoundaryRetainsItsActualPositionAcrossMidnight() {
        val start = Instant.parse("2026-11-02T23:59:54Z").toEpochMilli()
        fun time(elapsed: Long) = ReadingClockSnapshot(elapsed, start + elapsed)
        val session = ObservedReadingSession(idleTimeoutMs = 7_000L)
        session.resume(time(0))
        val first = session.checkpoint(time(5_000))
        val last = session.checkpoint(time(10_000))
        assertEquals(listOf(ObservedReadingInterval(start + 5_000, start + 7_000)), last)
        val totals = StatisticsCalculator.aggregate((first + last).map {
            ActiveReadingInterval("book", Instant.ofEpochMilli(it.startedAt), Instant.ofEpochMilli(it.endedAt))
        }, ZoneId.of("UTC"), Instant.ofEpochMilli(start + 10_000))
        assertEquals(6_000L, totals.dailyActiveMs[LocalDate.parse("2026-11-02")])
        assertEquals(1_000L, totals.dailyActiveMs[LocalDate.parse("2026-11-03")])
    }

    @Test fun duplicateResumeDoesNotExtendTheIdleDeadline() {
        val session = ObservedReadingSession(idleTimeoutMs = 7_000)
        session.resume(at(0)); session.resume(at(5_000))
        assertEquals(listOf(ObservedReadingInterval(epoch, epoch + 7_000)), session.checkpoint(at(10_000)))
    }

    @Test fun backgroundAndStrayInputDoNotResumeReading() {
        val session = ObservedReadingSession()
        session.resume(at(0))
        assertEquals(5_000L, session.pause(at(5_000)).sumOf { it.activeDurationMs })
        session.interact(at(100_000))
        assertTrue(session.checkpoint(at(100_000)).isEmpty())
        session.resume(at(100_000))
        assertEquals(listOf(ObservedReadingInterval(epoch + 100_000, epoch + 105_000)), session.checkpoint(at(105_000)))
    }

    @Test fun unknownLockDiscardsTheUnprovenTailButKeepsObservedInputTime() {
        val session = ObservedReadingSession()
        session.resume(at(0)); session.interact(at(3_000))
        assertEquals(listOf(ObservedReadingInterval(epoch, epoch + 3_000)),
            session.pause(at(5_000), includeTrailingObservation = false))
        assertTrue(session.checkpoint(at(50_000)).isEmpty())
    }

    @Test fun missedHeartbeatsNeverCreditUnobservedSuspension() {
        val session = ObservedReadingSession()
        session.resume(at(0))
        assertEquals(5_000L, session.checkpoint(at(5_000)).sumOf { it.activeDurationMs })
        assertTrue(session.checkpoint(at(65_000)).isEmpty())
        assertEquals(listOf(ObservedReadingInterval(epoch + 65_000, epoch + 70_000)), session.checkpoint(at(70_000)))
    }

    @Test fun frequentInputDoesNotDuplicateCheckpointTime() {
        val session = ObservedReadingSession()
        session.resume(at(0))
        (1L..4L).forEach { session.interact(at(it * 1_000)) }
        assertEquals(listOf(ObservedReadingInterval(epoch, epoch + 5_000)), session.checkpoint(at(5_000)))
        assertTrue(session.checkpoint(at(5_000)).isEmpty())
        assertTrue(session.pause(at(5_000)).isEmpty())
    }

    @Test fun invalidMonotonicRollbackDoesNotProduceNegativeTime() {
        val session = ObservedReadingSession()
        session.resume(at(10_000))
        assertTrue(session.checkpoint(at(5_000)).isEmpty())
        assertEquals(listOf(ObservedReadingInterval(epoch + 5_000, epoch + 6_000)), session.checkpoint(at(6_000)))
    }

    @Test fun aNewProcessCannotReconstructAnOldRunningInterval() {
        val old = ObservedReadingSession()
        old.resume(at(0)); old.interact(at(2_000))
        val restarted = ObservedReadingSession()
        assertTrue(restarted.checkpoint(at(100_000)).isEmpty())
        restarted.resume(at(100_000))
        assertEquals(listOf(ObservedReadingInterval(epoch + 100_000, epoch + 105_000)), restarted.checkpoint(at(105_000)))
    }

    @Test fun aWallClockJumpDiscardsOnlyTheUnlocatableWindow() {
        val session = ObservedReadingSession()
        session.resume(at(0))
        assertEquals(listOf(ObservedReadingInterval(epoch, epoch + 5_000)), session.checkpoint(at(5_000)))
        val movedEpoch = epoch + 86_400_000L
        assertTrue(session.checkpoint(ReadingClockSnapshot(10_000, movedEpoch + 10_000)).isEmpty())
        assertEquals(listOf(ObservedReadingInterval(movedEpoch + 10_000, movedEpoch + 15_000)),
            session.checkpoint(ReadingClockSnapshot(15_000, movedEpoch + 15_000)))
        assertTrue(session.checkpoint(ReadingClockSnapshot(20_000, epoch + 20_000)).isEmpty())
        assertEquals(listOf(ObservedReadingInterval(epoch + 20_000, epoch + 25_000)), session.checkpoint(at(25_000)))
    }
}
