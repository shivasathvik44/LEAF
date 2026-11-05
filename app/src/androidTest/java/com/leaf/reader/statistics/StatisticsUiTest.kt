package com.leaf.reader.statistics

import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.leaf.reader.MainActivity
import com.leaf.reader.core.reading.ActiveReadingInterval
import com.leaf.reader.core.reading.StatisticsCalculator
import com.leaf.reader.core.util.PublicationFormat
import com.leaf.reader.domain.model.Book
import com.leaf.reader.domain.model.ReadingSession
import com.leaf.reader.domain.model.ReadingStatus
import com.leaf.reader.feature.statistics.StatisticsTestDependencies
import com.leaf.reader.feature.statistics.StatisticsUiState
import com.leaf.reader.feature.statistics.StatisticsViewModel
import com.leaf.reader.feature.statistics.preciseReadingDuration
import dagger.hilt.android.EntryPointAccessors
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Production MainActivity, Hilt ViewModel and Room; every case owns only its UUID books. */
@RunWith(AndroidJUnit4::class)
class StatisticsUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val dependencies get() = EntryPointAccessors.fromApplication(
        context.applicationContext, StatisticsTestDependencies::class.java)
    private val ownedBooks = mutableListOf<Book>()
    private lateinit var directory: File
    private lateinit var first: Book
    private lateinit var second: Book
    private lateinit var firstSlot: Slot
    private lateinit var secondSlot: Slot
    private lateinit var model: StatisticsViewModel
    private var baselineTotal = 0L
    private var baselineCompleted = 0
    private lateinit var zone: ZoneId

    @Before fun setUp() = runBlocking<Unit> {
        zone = ZoneId.systemDefault()
        directory = File(context.cacheDir, "statistics-ui/${UUID.randomUUID()}").apply { check(mkdirs()) }
        val oldBooks = dependencies.books().books.first()
        val oldSessions = dependencies.statistics().sessions.first()
        val now = Instant.now()
        baselineTotal = StatisticsCalculator.aggregate(oldSessions.mapNotNull(::acceptedInterval), zone, now).totalActiveMs
        baselineCompleted = oldBooks.count { it.status == ReadingStatus.COMPLETED }
        firstSlot = freePastSlot(oldSessions, 120_000L)
        secondSlot = freePastSlot(oldSessions, 60_000L, excludedDay = firstSlot.day)
        first = insertBook("Garden")
        second = insertBook("River")
    }

    @After fun tearDown() = runBlocking<Unit> {
        // Stop the real screen's subscription/ticker before removing only this case's records.
        compose.activityRule.scenario.close()
        ownedBooks.forEach { dependencies.books().removeRecord(it.id) }
        if (this@StatisticsUiTest::directory.isInitialized) directory.deleteRecursively()
    }

    @Test fun calendarFiltersRealHistoryAndRestoresItsSelectionAcrossRecreation() = runBlocking<Unit> {
        record(first, firstSlot.start, 60_000L)
        record(first, firstSlot.start + 60_000L, 60_000L)
        record(second, secondSlot.start, 60_000L)
        dependencies.books().setStatus(first.id, ReadingStatus.COMPLETED)
        dependencies.books().setStatus(second.id, ReadingStatus.READING)
        openActivity()
        awaitUi { !it.loading && it.activity.history.any { row -> row.bookId == first.id } &&
            it.activity.history.any { row -> row.bookId == second.id } }

        compose.onNodeWithTag("activity-total").assertContentDescriptionEquals(
            "Total active reading time: ${preciseReadingDuration(baselineTotal + 180_000L)}")
        compose.onNodeWithTag("activity-completed").assertContentDescriptionEquals("Books completed: ${baselineCompleted + 1}")
        assertEquals(1, model.state.value.activity.history.count { it.bookId == first.id && it.day == firstSlot.day })
        assertEquals(120_000L, model.state.value.activity.history.single { it.bookId == first.id }.activeDurationMs)

        showMonth(YearMonth.from(firstSlot.day))
        compose.onNodeWithTag("activity-day-${firstSlot.day}").performScrollTo().performClick().assertIsSelected()
        awaitUi { it.selectedDay == firstSlot.day && it.visibleHistory.any { row -> row.bookId == first.id } &&
            it.visibleHistory.none { row -> row.bookId == second.id } }
        showHistory(first, firstSlot.day)
        assertEquals(1, compose.onAllNodesWithTag(historyTag(first, firstSlot.day)).fetchSemanticsNodes().size)

        val retained = model
        compose.activityRule.scenario.recreate()
        compose.activityRule.scenario.onActivity { activity ->
            model = ViewModelProvider(activity)["statistics", StatisticsViewModel::class.java]
        }
        assertSame(retained, model)
        awaitUi { it.selectedDay == firstSlot.day && it.month == YearMonth.from(firstSlot.day) }
        compose.onNodeWithTag("activity-day-${firstSlot.day}").performScrollTo().assertIsSelected()
        showHistory(first, firstSlot.day)
        compose.onNodeWithTag(historyTag(first, firstSlot.day)).performClick()
        compose.onNodeWithText("Back", substring = false).assertIsDisplayed()
        compose.onNodeWithText("Open book").performScrollTo().assertIsDisplayed()
    }

    @Test fun visibleActivityUpdatesAfterARealCheckpointAndCompletedStatusChange() = runBlocking<Unit> {
        openActivity()
        awaitUi { !it.loading && it.error == null }
        assertTrue(model.state.value.activity.history.none { it.bookId == first.id })

        record(first, firstSlot.start, 90_000L)
        dependencies.books().setStatus(first.id, ReadingStatus.COMPLETED)
        awaitUi { it.activity.history.any { row -> row.bookId == first.id && row.activeDurationMs == 90_000L } &&
            it.activity.booksCompleted == baselineCompleted + 1 }
        compose.onNodeWithTag("activity-total").assertContentDescriptionEquals(
            "Total active reading time: ${preciseReadingDuration(baselineTotal + 90_000L)}")
        compose.onNodeWithTag("activity-completed").assertContentDescriptionEquals("Books completed: ${baselineCompleted + 1}")
        showHistory(first, firstSlot.day)
        compose.onNodeWithTag("activity-history-duration-${first.id}-${firstSlot.day}", useUnmergedTree = true)
            .assertContentDescriptionEquals("1 minute 30 seconds")
        assertEquals(1, dependencies.statistics().sessions.first().count { it.bookId == first.id })
    }

    private fun openActivity() {
        compose.onNodeWithTag("nav-Activity").performClick()
        compose.waitForIdle()
        compose.activityRule.scenario.onActivity { activity ->
            model = ViewModelProvider(activity)["statistics", StatisticsViewModel::class.java]
        }
        awaitUi { !it.loading }
        compose.onNodeWithText("Reading activity").assertIsDisplayed()
    }

    private fun awaitUi(condition: (StatisticsUiState) -> Boolean) {
        compose.waitUntil(20_000) { condition(model.state.value) }
        assertEquals(null, model.state.value.error)
    }

    private fun showMonth(target: YearMonth) {
        repeat(3) {
            if (model.state.value.month == target) return
            compose.onNodeWithTag("activity-list").performScrollToNode(hasTestTag("activity-calendar"))
            compose.onNodeWithText("Previous", substring = false).performClick()
            compose.waitForIdle()
        }
        assertEquals("The selected historical month must be reachable", target, model.state.value.month)
    }

    private fun showHistory(book: Book, day: LocalDate) {
        compose.onNodeWithTag("activity-list").performScrollToNode(hasTestTag(historyTag(book, day)))
        compose.onNodeWithTag(historyTag(book, day)).assertIsDisplayed()
    }

    private fun historyTag(book: Book, day: LocalDate) = "activity-history-${book.id}-$day"

    private suspend fun record(book: Book, start: Long, duration: Long) {
        dependencies.statistics().recordInterval(book.id, start, start + duration, duration)
    }

    private suspend fun insertBook(label: String): Book {
        val id = UUID.randomUUID().toString()
        val file = File(directory, "$id.epub")
        ZipInputStream(instrumentation.context.assets.open("publications/standard.epub")).use { input ->
            ZipOutputStream(file.outputStream()).use { output ->
                output.setComment("Leaf activity UI test $id")
                while (true) {
                    val source = input.nextEntry ?: break
                    val bytes = input.readBytes()
                    val entry = ZipEntry(source.name)
                    if (source.name == "mimetype") {
                        entry.method = ZipEntry.STORED
                        entry.size = bytes.size.toLong()
                        entry.crc = CRC32().apply { update(bytes) }.value
                    }
                    output.putNextEntry(entry); output.write(bytes); output.closeEntry(); input.closeEntry()
                }
            }
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
        val book = Book(id, "Activity $label ${id.take(8)}", "Leaf original fixture", null,
            PublicationFormat.EPUB, file.absolutePath, null, null, file.length(), digest, System.currentTimeMillis())
        dependencies.books().add(book)
        ownedBooks += book
        return book
    }

    private data class Slot(val day: LocalDate, val start: Long)

    /** Find unused historical time instead of clearing or overlapping another book's activity. */
    private fun freePastSlot(sessions: List<ReadingSession>, duration: Long, excludedDay: LocalDate? = null): Slot {
        val intervals = sessions.mapNotNull(::acceptedInterval).sortedBy { it.start }
        repeat(62) { offset ->
            val day = LocalDate.now(zone).minusDays(offset + 1L)
            if (day != excludedDay) {
                var cursor = day.atStartOfDay(zone).toInstant().toEpochMilli()
                val end = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
                for (interval in intervals) {
                    val start = interval.start.toEpochMilli()
                    val stop = interval.end.toEpochMilli()
                    if (stop <= cursor) continue
                    if (start >= end) break
                    if (start - cursor >= duration) return Slot(day, cursor)
                    cursor = maxOf(cursor, stop)
                }
                if (end - cursor >= duration) return Slot(day, cursor)
            }
        }
        error("The test needs one unoccupied historical interval; existing activity was preserved.")
    }

    private fun acceptedInterval(session: ReadingSession): ActiveReadingInterval? {
        if (session.startedAt < 0L || session.endedAt <= session.startedAt || session.activeDurationMs <= 0L ||
            session.activeDurationMs > session.endedAt - session.startedAt) return null
        return ActiveReadingInterval(session.bookId, Instant.ofEpochMilli(session.endedAt - session.activeDurationMs),
            Instant.ofEpochMilli(session.endedAt))
    }
}
