package com.leaf.reader.statistics

import android.app.KeyguardManager
import android.content.Context
import android.os.PowerManager
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.leaf.reader.core.reading.ReadingClockSnapshot
import com.leaf.reader.core.util.PublicationFormat
import com.leaf.reader.data.local.database.LeafDatabase
import com.leaf.reader.data.repository.BookRepository
import com.leaf.reader.data.repository.ReadingActivityRecorder
import com.leaf.reader.data.repository.ReadingActivityRecorderFactory
import com.leaf.reader.data.repository.StatisticsRepository
import com.leaf.reader.domain.model.Book
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.rules.Timeout

/** Real Room writes and device-state services; no personal library database is opened. */
class ReadingActivityRecorderTest {
    @get:Rule val testTimeout: TestRule = Timeout.seconds(120)
    private lateinit var context: Context
    private lateinit var database: LeafDatabase
    private lateinit var repository: StatisticsRepository
    private lateinit var scope: CoroutineScope
    private var recorder: ReadingActivityRecorder? = null
    private val bookId = UUID.randomUUID().toString()
    private val epoch = Instant.parse("2026-11-03T12:00:00Z").toEpochMilli()
    @Volatile private var now = ReadingClockSnapshot(0L, epoch)

    @Before fun setup() = runBlocking {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        assertTrue("The test device must be awake", context.getSystemService(PowerManager::class.java).isInteractive)
        assertFalse("The test device must be unlocked", context.getSystemService(KeyguardManager::class.java).isKeyguardLocked)
        database = Room.inMemoryDatabaseBuilder(context, LeafDatabase::class.java).build()
        repository = StatisticsRepository(database)
        BookRepository(database).add(Book(bookId, "Activity clock fixture", "Leaf", null, PublicationFormat.EPUB,
            "/test-fixtures/clock.epub", null, null, 1, "a".repeat(64), epoch))
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    }

    @After fun cleanup() = runBlocking {
        recorder?.close()
        recorder?.awaitClosed()
        if (::scope.isInitialized) scope.cancel()
        if (::database.isInitialized) database.close()
    }

    private fun createRecorder() = ReadingActivityRecorder(context, repository, scope, bookId) { now }.also { recorder = it }
    private fun time(value: Long) { now = ReadingClockSnapshot(value, epoch + value) }

    @Test fun foregroundTimePersistsButPausedAndClosedTimeNeverCounts() = runBlocking {
        val recorder = createRecorder()
        recorder.foregroundEligible(true)
        time(5_000); recorder.checkpoint()
        time(8_000); recorder.pause()
        time(80_000); recorder.interact(); recorder.checkpoint()
        recorder.close(); recorder.awaitClosed()
        time(90_000); recorder.foregroundEligible(true); recorder.interact(); recorder.checkpoint()
        val sessions = repository.sessions.first()
        assertEquals(8_000L, sessions.sumOf { it.activeDurationMs })
        assertTrue(sessions.all { it.endedAt <= epoch + 8_000 })
        assertEquals(sessions.size, sessions.map { it.id }.distinct().size)
    }

    @Test fun realDatabaseWriteFailureIsRetriedWithoutDuplicatingTimeOrLeakingItsError() = runBlocking {
        database.openHelper.writableDatabase.execSQL("""
            CREATE TRIGGER reject_activity BEFORE INSERT ON reading_sessions
            BEGIN SELECT RAISE(ABORT, 'private-sqlite-test-failure'); END
        """.trimIndent())
        val recorder = createRecorder()
        recorder.foregroundEligible(true)
        time(5_000); recorder.checkpoint()
        val safeError = withTimeout(5_000) { recorder.error.first { it != null } }.orEmpty()
        assertFalse(safeError.contains("private-sqlite-test-failure"))
        database.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_activity")
        recorder.checkpoint() // Same timestamp: retry the existing immutable checkpoint only.
        withTimeout(5_000) { repository.sessions.first { it.isNotEmpty() } }
        recorder.close(); recorder.awaitClosed()
        val sessions = repository.sessions.first()
        assertEquals(1, sessions.size)
        assertEquals(5_000L, sessions.single().activeDurationMs)
    }

    @Test fun ownerCancellationStillDrainsTheLastObservedInterval() = runBlocking {
        val recorder = createRecorder()
        withContext(Dispatchers.Main.immediate) {
            recorder.foregroundEligible(true)
            time(1_000)
            scope.cancel() // Cancel before the owner-dispatched heartbeat can record the tail.
        }
        // Cancellation can return before the owner's completion handler closes the recorder.
        // Require the independent writer to commit the tail before teardown can close it.
        val saved = withTimeout(5_000) { repository.sessions.first { it.isNotEmpty() } }
        recorder.awaitClosed()
        assertEquals(1_000L, saved.sumOf { it.activeDurationMs })
        time(100_000)
        recorder.interact()
        assertEquals(1_000L, repository.sessions.first().sumOf { it.activeDurationMs })
    }

    @Test fun storagePressurePausesNewTimeAndKeepsEveryAcceptedCheckpointForRetry() = runBlocking {
        database.openHelper.writableDatabase.execSQL("""
            CREATE TRIGGER reject_activity BEFORE INSERT ON reading_sessions
            BEGIN SELECT RAISE(ABORT, 'private-capacity-test-failure'); END
        """.trimIndent())
        val recorder = createRecorder()
        val capacity = ReadingActivityRecorder.MAX_PENDING_CHECKPOINTS
        repeat(capacity) { index ->
            time(index * 2_000L)
            recorder.foregroundEligible(true)
            time(index * 2_000L + 1_000L)
            recorder.pause()
        }
        recorder.foregroundEligible(true)
        time(capacity * 2_000L + 5_000L)
        recorder.checkpoint()
        assertTrue(requireNotNull(recorder.error.value).contains("paused"))
        assertTrue(repository.sessions.first().isEmpty())

        database.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_activity")
        recorder.checkpoint()
        withTimeout(15_000) { repository.sessions.first { rows -> rows.size == capacity } }
        // Writes do not reset idle eligibility by themselves. An actual new interaction does.
        time(capacity * 2_000L + 10_000L)
        recorder.checkpoint()
        assertEquals(capacity * 1_000L, repository.sessions.first().sumOf { it.activeDurationMs })
        recorder.interact()
        time(capacity * 2_000L + 11_000L)
        recorder.pause()
        recorder.close()
        recorder.awaitClosed()
        val saved = repository.sessions.first()
        assertEquals((capacity + 1) * 1_000L, saved.sumOf { it.activeDurationMs })
        assertEquals(saved.size, saved.map { it.id }.distinct().size)
    }

    @Test fun adjacentInputIsBatchedWithoutMutatingAFailedCheckpoint() = runBlocking {
        database.openHelper.writableDatabase.execSQL("""
            CREATE TRIGGER reject_activity BEFORE INSERT ON reading_sessions
            BEGIN SELECT RAISE(ABORT, 'private-checkpoint-test-failure'); END
        """.trimIndent())
        val recorder = createRecorder()
        recorder.foregroundEligible(true)
        time(1_000); recorder.interact()
        time(2_000); recorder.interact()
        time(3_000); recorder.checkpoint()
        withTimeout(5_000) { recorder.error.first { it != null } }
        time(4_000); recorder.interact()
        time(5_000); recorder.checkpoint()
        database.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_activity")
        recorder.checkpoint()
        recorder.close(); recorder.awaitClosed()
        val saved = repository.sessions.first()
        assertEquals(5_000L, saved.sumOf { it.activeDurationMs })
        assertEquals(2, saved.size)
        assertEquals(listOf(2_000L, 3_000L), saved.map { it.activeDurationMs }.sorted())
    }

    @Test fun aClosedFailureShowsAnApplicationNoticeAndRetriesTheSameIntervalOnReopen() = runBlocking {
        database.openHelper.writableDatabase.execSQL("""
            CREATE TRIGGER reject_activity BEFORE INSERT ON reading_sessions
            BEGIN SELECT RAISE(ABORT, 'private-terminal-test-failure'); END
        """.trimIndent())
        val factory = ReadingActivityRecorderFactory(context, repository) { now }
        val first = factory.create(bookId, scope).also { recorder = it }
        first.foregroundEligible(true)
        time(5_000)
        first.close(); first.awaitClosed()
        val notice = withTimeout(5_000) { factory.pendingNotice.first { it != null } }.orEmpty()
        assertFalse(notice.contains("private-terminal-test-failure"))
        assertTrue(notice.contains("retry"))
        factory.dismissNotice()
        assertEquals(null, factory.pendingNotice.value)
        scope.cancel()

        database.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_activity")
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val reopened = factory.create(bookId, scope).also { recorder = it }
        withTimeout(10_000) { repository.sessions.first { it.isNotEmpty() } }
        first.awaitClosed() // Also wait for the old recorder's independent retry drain.
        reopened.close(); reopened.awaitClosed()
        val saved = repository.sessions.first()
        assertEquals(1, saved.size)
        assertEquals(5_000L, saved.single().activeDurationMs)
        assertEquals(epoch, saved.single().startedAt)
        assertEquals(epoch + 5_000, saved.single().endedAt)
        assertEquals(null, factory.pendingNotice.value)
    }

    @Test fun explicitBookRemovalDoesNotLeaveAnOrphanFailureOrRecreateHistory() = runBlocking {
        val recorder = createRecorder()
        recorder.foregroundEligible(true)
        time(1_000)
        BookRepository(database).removeRecord(bookId)
        recorder.close(); recorder.awaitClosed()
        assertTrue(repository.sessions.first().isEmpty())
        assertEquals(null, recorder.error.value)
        assertEquals(null, BookRepository(database).get(bookId))
    }
}
