package com.leaf.reader.core.reading

/** An in-memory pairing only; no running wall-clock timestamp is persisted or recovered. */
data class ReadingClockSnapshot(val elapsedMs: Long, val epochMs: Long)

/** A closed interval that was observed in the foreground, including its exact idle boundary. */
data class ObservedReadingInterval(val startedAt: Long, val endedAt: Long) {
    init { require(startedAt >= 0L && endedAt > startedAt) { "An observed interval must be positive and closed." } }
    val activeDurationMs: Long get() = endedAt - startedAt
}

/**
 * Retains the location of active time, not merely its duration. This matters when the idle
 * deadline is between two heartbeats or straddles midnight. [ActiveReadingTracker] remains
 * the authority for accepted monotonic durations and missed-heartbeat rejection.
 */
class ObservedReadingSession(
    private val idleTimeoutMs: Long = 120_000L,
    maxObservationGapMs: Long = 15_000L,
) {
    private var sampledElapsed = 0L
    private val tracker = ActiveReadingTracker({ sampledElapsed }, idleTimeoutMs, maxObservationGapMs)
    private var eligible = false
    private var lastObservedElapsed = 0L
    private var lastObservedEpoch = 0L
    private var lastInteractionElapsed = 0L
    private val pending = mutableListOf<ObservedReadingInterval>()

    fun resume(at: ReadingClockSnapshot) {
        if (eligible) return
        sampledElapsed = at.elapsedMs
        tracker.resume()
        eligible = true
        lastObservedElapsed = at.elapsedMs
        lastObservedEpoch = at.epochMs
        lastInteractionElapsed = at.elapsedMs
    }

    fun interact(at: ReadingClockSnapshot) {
        observe(at, interaction = true)
    }

    fun checkpoint(at: ReadingClockSnapshot): List<ObservedReadingInterval> {
        observe(at, interaction = false)
        return drain()
    }

    /** The recorder can batch observed input intervals without keeping a running timestamp. */
    internal fun takeObservedIntervals(): List<ObservedReadingInterval> = drain()

    /**
     * Set includeTrailingObservation=false when a lock/power change is discovered after the
     * fact. Discarding that unproven tail is safer than crediting time after an unknown lock.
     */
    fun pause(at: ReadingClockSnapshot, includeTrailingObservation: Boolean = true): List<ObservedReadingInterval> {
        if (eligible) {
            if (includeTrailingObservation) observe(at, interaction = false)
            sampledElapsed = lastObservedElapsed
            tracker.pause()
            tracker.checkpoint()
            eligible = false
        }
        return drain()
    }

    private fun observe(at: ReadingClockSnapshot, interaction: Boolean) {
        if (!eligible) return
        val previousInteraction = lastInteractionElapsed
        val rolledBack = at.elapsedMs < lastObservedElapsed
        val clocksAgree = try {
            val elapsedDelta = Math.subtractExact(at.elapsedMs, lastObservedElapsed)
            val epochDelta = Math.subtractExact(at.epochMs, lastObservedEpoch)
            elapsedDelta >= 0L &&
                Math.subtractExact(epochDelta, elapsedDelta) in -CLOCK_DRIFT_TOLERANCE_MS..CLOCK_DRIFT_TOLERANCE_MS
        } catch (_: ArithmeticException) { false }
        sampledElapsed = at.elapsedMs
        if (interaction) tracker.interact()
        val duration = tracker.checkpoint()
        // A wall-clock jump cannot locate the accepted duration on a reliable calendar.
        // Discard that window instead of shifting it across days or inventing future time.
        if (duration > 0L && clocksAgree) {
            val deadline = if (previousInteraction > Long.MAX_VALUE - idleTimeoutMs) Long.MAX_VALUE
                else previousInteraction + idleTimeoutMs
            val activeEndElapsed = minOf(at.elapsedMs, deadline)
            val inactiveTail = (at.elapsedMs - activeEndElapsed).coerceAtLeast(0L)
            val endEpoch = if (at.epochMs >= inactiveTail) at.epochMs - inactiveTail else 0L
            val accepted = minOf(duration, endEpoch.coerceAtLeast(0L))
            if (accepted > 0) append(ObservedReadingInterval(endEpoch - accepted, endEpoch))
        }
        lastObservedElapsed = at.elapsedMs
        lastObservedEpoch = at.epochMs
        if (interaction || rolledBack) lastInteractionElapsed = at.elapsedMs
    }

    private fun append(interval: ObservedReadingInterval) {
        val previous = pending.lastOrNull()
        if (previous?.endedAt == interval.startedAt) {
            pending[pending.lastIndex] = ObservedReadingInterval(previous.startedAt, interval.endedAt)
        } else {
            pending += interval
        }
    }

    private fun drain(): List<ObservedReadingInterval> = pending.toList().also { pending.clear() }

    private companion object { const val CLOCK_DRIFT_TOLERANCE_MS = 1_000L }
}
