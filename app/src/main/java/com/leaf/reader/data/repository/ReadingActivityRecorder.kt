package com.leaf.reader.data.repository

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.leaf.reader.core.reading.ObservedReadingInterval
import com.leaf.reader.core.reading.ObservedReadingSession
import com.leaf.reader.core.reading.ReadingClockSnapshot
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.ArrayDeque
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@Singleton
class ReadingActivityRecorderFactory internal constructor(
    private val context: Context,
    private val statistics: StatisticsRepository,
    private val clock: () -> ReadingClockSnapshot,
) {
    @Inject constructor(@ApplicationContext context: Context, statistics: StatisticsRepository) :
        this(context.applicationContext, statistics, {
            ReadingClockSnapshot(SystemClock.elapsedRealtime(), System.currentTimeMillis())
        })

    private val lock = Any()
    private val capacity = CheckpointCapacity()
    private val failed = linkedSetOf<ReadingActivityRecorder>()
    private val _pendingNotice = MutableStateFlow<String?>(null)
    val pendingNotice: StateFlow<String?> = _pendingNotice.asStateFlow()

    fun dismissNotice() { _pendingNotice.value = null }

    fun create(bookId: String, scope: CoroutineScope): ReadingActivityRecorder {
        // A permanently failing database must not create an unbounded backlog each time a
        // reader is reopened. Closed failures retain their exact UUIDs in the shared budget.
        synchronized(lock) { failed.toList() }.forEach(ReadingActivityRecorder::retryClosed)
        return ReadingActivityRecorder(context, statistics, scope, bookId, capacity,
            onTerminalFailure = { recorder -> synchronized(lock) {
                failed += recorder
                _pendingNotice.value = "Some recent reading activity could not be saved. Leaf kept it in memory and will retry when you open a book. It may be lost if the app is closed."
            } },
            onRecovered = { recorder -> synchronized(lock) {
                failed -= recorder
                if (failed.isEmpty()) _pendingNotice.value = null
            } }, clock = clock)
    }
}

/** Shared across the visible reader and any closed, retryable checkpoints. */
internal class CheckpointCapacity {
    private var used = 0
    @Synchronized fun hasRoom(): Boolean = used < ReadingActivityRecorder.MAX_PENDING_CHECKPOINTS
    @Synchronized fun reserve(): Boolean {
        if (!hasRoom()) return false
        used++
        return true
    }
    @Synchronized fun release() { check(used > 0); used-- }
}

/**
 * One recorder belongs to one open reader. Its caller controls actual reader visibility,
 * resumed lifecycle, window focus and modal inactivity through [foregroundEligible].
 * Device screen/keyguard checks are additional requirements, never substitutes for that flag.
 *
 * Only immutable closed checkpoints are written. A failed write retains the same UUID for
 * retry; process death never reconstructs time from a persisted running timestamp. Under
 * healthy storage the uncommitted tail is one five-second heartbeat plus write latency.
 */
class ReadingActivityRecorder internal constructor(
    context: Context,
    private val repository: StatisticsRepository,
    private val ownerScope: CoroutineScope,
    private val bookId: String,
    private val capacity: CheckpointCapacity = CheckpointCapacity(),
    private val onTerminalFailure: (ReadingActivityRecorder) -> Unit = {},
    private val onRecovered: (ReadingActivityRecorder) -> Unit = {},
    private val clock: () -> ReadingClockSnapshot,
) {
    constructor(context: Context, repository: StatisticsRepository, scope: CoroutineScope, bookId: String) :
        this(context, repository, scope, bookId, clock = {
            ReadingClockSnapshot(SystemClock.elapsedRealtime(), System.currentTimeMillis())
        })

    private data class Checkpoint(val id: String, val interval: ObservedReadingInterval, var attempted: Boolean = false)
    private val context = context.applicationContext
    private val power = this.context.getSystemService(PowerManager::class.java)
    private val keyguard = this.context.getSystemService(KeyguardManager::class.java)
    private val lock = Any()
    private val session = ObservedReadingSession()
    private val pending = ArrayDeque<Checkpoint>()
    private val wakeWriter = Channel<Unit>(Channel.CONFLATED)
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()
    private var externallyEligible = false
    private var recording = false
    private var closed = false
    private var monitorAvailable = false
    private var storagePaused = false
    private var writeFailed = false
    private var closeFailures = 0
    private var lastInputElapsed: Long? = null
    private var heartbeat: Job? = null
    private var retryJob: Job? = null
    private var retryAfterWriter = false
    private var ownerCompletion: DisposableHandle? = null
    // A reader ViewModel can be cancelled immediately after close. Closed Room writes must
    // drain independently; this scope owns no renderer, running interval or Activity reference.
    private val writerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writer: Job
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            synchronized(lock) {
                if (closed) return
                if (intent?.action == Intent.ACTION_SCREEN_OFF) {
                    // A delayed screen-off broadcast still proves a gap, even if the
                    // device has already started waking when the callback arrives.
                    if (recording) stopRecordingLocked(includeTrailingObservation = false)
                } else {
                    refreshLocked(knownForegroundTransition = false)
                }
            }
        }
    }

    init {
        UUID.fromString(bookId)
        try {
            ContextCompat.registerReceiver(this.context, receiver, IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT)
            }, ContextCompat.RECEIVER_NOT_EXPORTED)
            monitorAvailable = true
        } catch (_: RuntimeException) {
            // Fail closed: without reliable device-state observation, do not earn reading time.
            _error.value = "Reading activity is paused because device state could not be monitored."
        }
        writer = writerScope.launch {
            try {
                for (ignored in wakeWriter) {
                    writePending()
                    if (synchronized(lock) { closed }) break
                }
                // Handle close racing with an unsuccessful foreground write. Reuse the
                // same immutable UUID, and share the final-attempt budget across wakeups.
                if (synchronized(lock) { closed && pending.isNotEmpty() && closeFailures < CLOSE_ATTEMPTS }) writePending()
                reportClosedOutcome()
            } finally { writerScope.cancel() }
        }
        ownerCompletion = ownerScope.coroutineContext[Job]?.invokeOnCompletion { close() }
    }

    fun foregroundEligible(value: Boolean): Unit = synchronized(lock) {
        if (closed) return@synchronized
        val enteredForeground = value && !externallyEligible
        if (value != externallyEligible) lastInputElapsed = null
        externallyEligible = value
        if (enteredForeground && capacity.hasRoom()) storagePaused = false
        refreshLocked(knownForegroundTransition = true)
    }

    fun interact(): Unit = synchronized(lock) {
        if (closed) return@synchronized
        val at = clock()
        val previous = lastInputElapsed
        // Drag/move callbacks can arrive at display refresh speed. Device broadcasts and
        // lifecycle transitions remain immediate; accepted input samples are at most 4 Hz.
        if (previous != null && at.elapsedMs >= previous && at.elapsedMs - previous < INPUT_SAMPLE_MS) return@synchronized
        lastInputElapsed = at.elapsedMs
        // Only an actual new reading interaction or foreground entry resumes recording
        // after storage pressure. A successful background write cannot reset idle time.
        if (storagePaused && capacity.hasRoom()) storagePaused = false
        refreshLocked(knownForegroundTransition = false)
        if (recording) {
            observeLocked(flush = false) {
                session.interact(clock())
                session.takeObservedIntervals()
            }
        }
    }

    fun checkpoint(): Unit = synchronized(lock) {
        if (closed) return@synchronized
        refreshLocked(knownForegroundTransition = false)
        if (recording) observeLocked { session.checkpoint(clock()) }
        if (pending.isNotEmpty()) wakeWriter.trySend(Unit)
    }

    fun pause(): Unit = foregroundEligible(false)

    fun close(): Unit = synchronized(lock) {
        if (closed) return@synchronized
        externallyEligible = false
        refreshLocked(knownForegroundTransition = true)
        closed = true
        heartbeat?.cancel()
        heartbeat = null
        if (monitorAvailable) {
            try { context.unregisterReceiver(receiver) } catch (_: IllegalArgumentException) { }
            monitorAvailable = false
        }
        ownerCompletion?.dispose()
        ownerCompletion = null
        wakeWriter.trySend(Unit)
        wakeWriter.close()
    }

    /** Optional synchronization for tests/owners; close drains even if the owner scope ends. */
    suspend fun awaitClosed() {
        check(synchronized(lock) { closed }) { "Close the recorder before awaiting its writes." }
        writer.join()
        while (true) {
            val retry = synchronized(lock) { retryJob } ?: return
            retry.join()
            if (synchronized(lock) { retryJob === retry }) return
        }
    }

    private fun systemEligible(): Boolean = monitorAvailable && power?.isInteractive == true &&
        keyguard?.isKeyguardLocked == false

    private fun refreshLocked(knownForegroundTransition: Boolean) {
        val systemEligible = systemEligible()
        if (!capacity.hasRoom()) storagePaused = true
        val eligible = externallyEligible && systemEligible && !storagePaused
        if (eligible && !recording) {
            session.resume(clock())
            recording = true
        } else if (!eligible && recording) {
            // Screen/lock changes discovered after the fact discard their unproven final gap.
            // A known caller transition while still unlocked can preserve the observed tail.
            stopRecordingLocked(includeTrailingObservation = knownForegroundTransition && systemEligible)
        }
        // Focus and screen-on can return before the keyguard or power state settles.
        // Keep rechecking while the UI is eligible; only verified device state earns time.
        if (!closed && externallyEligible && monitorAvailable) {
            if (heartbeat?.isActive != true) heartbeat = ownerScope.launch {
                while (isActive) { delay(HEARTBEAT_MS); checkpoint() }
            }
        } else {
            heartbeat?.cancel()
            heartbeat = null
        }
        updateErrorLocked()
    }

    private fun stopRecordingLocked(includeTrailingObservation: Boolean) {
        recording = false
        if (includeTrailingObservation && capacity.reserve()) {
            enqueueLocked(session.pause(clock()), reserved = true)
        } else {
            session.pause(clock(), includeTrailingObservation = false)
            if (includeTrailingObservation) storagePaused = true
        }
    }

    private fun observeLocked(flush: Boolean = true, observation: () -> List<ObservedReadingInterval>) {
        if (!capacity.reserve()) {
            storagePaused = true
            if (recording) stopRecordingLocked(includeTrailingObservation = false)
            updateErrorLocked()
            return
        }
        val intervals = try { observation() } catch (failure: Throwable) { capacity.release(); throw failure }
        enqueueLocked(intervals, reserved = true, flush = flush)
    }

    private fun enqueueLocked(intervals: List<ObservedReadingInterval>, reserved: Boolean, flush: Boolean = true) {
        // Every recorder observation drains the accounting core immediately, so it can
        // produce at most one interval. Reserve before observing to avoid accepting time
        // that cannot fit alongside another reader's final drain.
        check(reserved && intervals.size <= 1)
        if (intervals.isEmpty()) capacity.release()
        intervals.forEach { interval ->
            val tail = pending.peekLast()
            // Only a checkpoint never offered to Room can grow. Once an attempt begins,
            // the UUID's interval stays immutable even if the database reports a failure.
            if (tail != null && !tail.attempted && tail.interval.endedAt == interval.startedAt) {
                pending.removeLast()
                pending.addLast(Checkpoint(tail.id, ObservedReadingInterval(tail.interval.startedAt, interval.endedAt)))
                capacity.release()
            } else {
                pending.addLast(Checkpoint(UUID.randomUUID().toString(), interval))
            }
        }
        if (!capacity.hasRoom()) {
            storagePaused = true
            if (recording) stopRecordingLocked(includeTrailingObservation = false)
        }
        updateErrorLocked()
        if (flush && pending.isNotEmpty()) wakeWriter.trySend(Unit)
    }

    private suspend fun writePending() {
        while (true) {
            val next = synchronized(lock) { pending.peekFirst()?.also { it.attempted = true } } ?: return
            try {
                repository.recordInterval(bookId, next.interval.startedAt, next.interval.endedAt,
                    next.interval.activeDurationMs, id = next.id)
                synchronized(lock) {
                    if (pending.peekFirst()?.id == next.id) {
                        pending.removeFirst()
                        capacity.release()
                    }
                    closeFailures = 0
                    writeFailed = false
                    updateErrorLocked()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                val bookRemoved = try { !repository.bookExists(bookId) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { false }
                if (bookRemoved) {
                    // The user removed this book while its last write was draining. Its
                    // history belongs to the same explicit cascade; keep no orphan retry.
                    synchronized(lock) {
                        repeat(pending.size) { capacity.release() }
                        pending.clear()
                        writeFailed = false
                        updateErrorLocked()
                    }
                    return
                }
                val failures = synchronized(lock) {
                    writeFailed = true
                    if (closed) closeFailures++
                    updateErrorLocked()
                    if (closed) closeFailures else null
                }
                if (failures == null || failures >= CLOSE_ATTEMPTS) return
                delay(500L * failures)
            }
        }
    }

    /** Retry only immutable closed checkpoints; never restart a running reading session. */
    internal fun retryClosed(): Unit = synchronized(lock) {
        if (!closed || pending.isEmpty() || retryJob?.isActive == true) return@synchronized
        if (writer.isActive) {
            if (!retryAfterWriter) {
                retryAfterWriter = true
                writer.invokeOnCompletion {
                    synchronized(lock) { retryAfterWriter = false }
                    retryClosed()
                }
            }
            return@synchronized
        }
        closeFailures = 0
        val retryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        retryJob = retryScope.launch {
            try { writePending(); reportClosedOutcome() }
            finally { retryScope.cancel() }
        }
    }

    private fun reportClosedOutcome() {
        val remaining = synchronized(lock) { if (!closed) return; pending.isNotEmpty() }
        if (remaining) onTerminalFailure(this) else onRecovered(this)
    }

    private fun updateErrorLocked() {
        _error.value = when {
            closed && writeFailed -> "Some recent reading activity could not be saved."
            !closed && !monitorAvailable -> "Reading activity is paused because device state could not be monitored."
            !closed && storagePaused -> "Reading activity is paused until recent activity can be saved. Interact with the book to resume when storage is available."
            writeFailed -> "Recent reading activity could not be saved. Leaf will retry while the reader is open."
            else -> null
        }
    }

    internal companion object {
        const val HEARTBEAT_MS = 5_000L
        const val INPUT_SAMPLE_MS = 250L
        const val CLOSE_ATTEMPTS = 3
        const val MAX_PENDING_CHECKPOINTS = 128
    }
}
