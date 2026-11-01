package com.leaf.reader.core.reading

/**
 * Counts only observed, foreground reading intervals using a monotonic clock.
 *
 * Call [resume] only when the reader is resumed, the screen is on and unlocked;
 * [pause] on any of those becoming false. Call [interact] for reader gestures and
 * navigation. Persist [checkpoint]'s returned delta every five seconds and on
 * pause. No running timestamp is persisted/recovered after process death.
 *
 * A missed heartbeat longer than [maxObservationGapMs] is deliberately discarded:
 * a suspended process must not earn reading time when it later resumes.
 */
class ActiveReadingTracker(
    private val monotonicMs: () -> Long,
    private val idleTimeoutMs: Long = 120_000L,
    private val maxObservationGapMs: Long = 15_000L,
) {
    init {
        require(idleTimeoutMs > 0)
        require(maxObservationGapMs > 0)
    }

    private var foreground = false
    private var lastObserved = 0L
    private var lastInteraction = 0L
    private var pending = 0L

    @Synchronized
    fun resume() {
        if (foreground) return
        val now = monotonicMs()
        foreground = true
        lastObserved = now
        lastInteraction = now
    }

    @Synchronized
    fun interact() {
        if (!foreground) return
        val now = monotonicMs()
        accrue(now)
        lastInteraction = now
    }

    @Synchronized
    fun pause() {
        if (!foreground) return
        accrue(monotonicMs())
        foreground = false
    }

    /** Returns unreported active milliseconds once; repeated calls never count them again. */
    @Synchronized
    fun checkpoint(): Long {
        if (foreground) accrue(monotonicMs())
        return pending.also { pending = 0L }
    }

    private fun accrue(now: Long) {
        val gap = now - lastObserved
        if (gap < 0) {
            // Defensive against an invalid clock: never produce a negative or future interval.
            lastInteraction = now
            lastObserved = now
            return
        }
        if (gap <= maxObservationGapMs) {
            val remainingUntilIdle = (idleTimeoutMs - (lastObserved - lastInteraction))
                .coerceAtLeast(0L)
            val delta = minOf(gap, remainingUntilIdle)
            pending = if (Long.MAX_VALUE - pending < delta) Long.MAX_VALUE else pending + delta
        }
        lastObserved = now
    }
}
