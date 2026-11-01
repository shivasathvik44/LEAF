package com.leaf.reader.core.reading

import org.junit.Assert.assertEquals
import org.junit.Test

class ActiveReadingTrackerTest {
    private var now = 0L
    private fun tracker(idle: Long = 120_000L) = ActiveReadingTracker({ now }, idle)

    @Test
    fun backgroundAndPausedTimeNeverCount() {
        val tracker = tracker()
        now = 5_000L
        assertEquals(0L, tracker.checkpoint())
        tracker.resume()
        now = 10_000L
        tracker.pause()
        now = 200_000L
        assertEquals(5_000L, tracker.checkpoint())
        assertEquals(0L, tracker.checkpoint())
    }

    @Test
    fun checkpointsDrainExactlyOnce() {
        val tracker = tracker()
        tracker.resume()
        now = 5_000L
        assertEquals(5_000L, tracker.checkpoint())
        assertEquals(0L, tracker.checkpoint())
        now = 9_000L
        assertEquals(4_000L, tracker.checkpoint())
    }

    @Test
    fun idleBoundaryOnlyAccruesUntilLastInteractionExpires() {
        val tracker = tracker(idle = 7_000L)
        tracker.resume()
        now = 5_000L
        assertEquals(5_000L, tracker.checkpoint())
        now = 10_000L
        assertEquals(2_000L, tracker.checkpoint())
        now = 15_000L
        assertEquals(0L, tracker.checkpoint())
        tracker.interact()
        now = 20_000L
        assertEquals(5_000L, tracker.checkpoint())
    }

    @Test
    fun suspendedProcessCannotAccrueAnUnobservedGap() {
        val tracker = tracker()
        tracker.resume()
        now = 5_000L
        assertEquals(5_000L, tracker.checkpoint())
        now = 65_000L
        assertEquals(0L, tracker.checkpoint())
        now = 70_000L
        assertEquals(5_000L, tracker.checkpoint())
    }

    @Test
    fun processDeathDoesNotReconstructUncheckpointedWallTime() {
        val beforeDeath = tracker()
        beforeDeath.resume()
        now = 5_000L
        assertEquals(5_000L, beforeDeath.checkpoint())
        now = 500_000L
        val afterDeath = tracker()
        assertEquals(0L, afterDeath.checkpoint())
        afterDeath.resume()
        now = 505_000L
        assertEquals(5_000L, afterDeath.checkpoint())
    }

    @Test
    fun duplicateResumeDoesNotResetTheIdleDeadline() {
        val tracker = tracker(idle = 7_000L)
        tracker.resume()
        now = 5_000L
        tracker.resume()
        now = 10_000L
        assertEquals(7_000L, tracker.checkpoint())
    }

    @Test
    fun invalidClockRollbackNeverCreatesNegativeDuration() {
        now = 10_000L
        val tracker = tracker()
        tracker.resume()
        now = 5_000L
        assertEquals(0L, tracker.checkpoint())
        now = 6_000L
        assertEquals(1_000L, tracker.checkpoint())
    }

    @Test
    fun lockPauseAndUnlockResumeExcludeTheLockedInterval() {
        val tracker = tracker()
        tracker.resume()
        now = 5_000L
        tracker.pause()
        now = 500_000L
        tracker.interact() // A stray gesture while paused cannot start a session.
        tracker.resume()
        now = 505_000L
        assertEquals(10_000L, tracker.checkpoint())
    }
}
