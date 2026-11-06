package com.leaf.reader.reader

import android.app.KeyguardManager
import android.graphics.Rect
import android.os.PowerManager
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.espresso.Espresso.closeSoftKeyboard
import androidx.test.espresso.Espresso.pressBack
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.leaf.reader.MainActivity
import com.leaf.reader.core.util.PublicationFormat
import com.leaf.reader.data.readium.ReaderHarnessDependencies
import com.leaf.reader.domain.model.Book
import com.leaf.reader.domain.model.ReadingSession
import com.leaf.reader.feature.reader.ReaderViewModel
import com.leaf.reader.feature.statistics.StatisticsTestDependencies
import dagger.hilt.android.EntryPointAccessors
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real production reader eligibility and closed Room checkpoints, using actual device clocks.
 * Power commands are restricted to the isolated test emulator; every exit wakes/unlocks it.
 */
@RunWith(AndroidJUnit4::class)
class ReaderActivityUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val repositories get() = EntryPointAccessors.fromApplication(
        context.applicationContext, StatisticsTestDependencies::class.java)
    private val readerDependencies get() = EntryPointAccessors.fromApplication(
        context.applicationContext, ReaderHarnessDependencies::class.java)
    private val power get() = context.getSystemService(PowerManager::class.java)
    private val keyguard get() = context.getSystemService(KeyguardManager::class.java)
    private lateinit var directory: File
    private lateinit var id: String
    private lateinit var title: String
    private var model: ReaderViewModel? = null

    @Before fun setUp() {
        restoreAwakeDevice()
        assertTrue("The test emulator must be awake", power.isInteractive)
        assertFalse("The test emulator must be unlocked", keyguard.isKeyguardLocked)
        id = UUID.randomUUID().toString()
        title = "Reader activity ${id.take(8)}"
        directory = File(context.cacheDir, "reader-activity-ui/$id").apply { check(mkdirs()) }
    }

    @After fun tearDown() = runBlocking<Unit> {
        try {
            restoreAwakeDevice()
        } finally {
            try {
                // Cleanup only: closing the actual ViewModel drains its immutable final
                // checkpoint independently of the Activity's soon-to-be-cancelled scope.
                instrumentation.runOnMainSync { model?.closeReader() }
                compose.activityRule.scenario.close()
            } finally {
                if (this@ReaderActivityUiTest::id.isInitialized) {
                    readerDependencies.preferences().resetReaderPrefs(id)
                    repositories.books().removeRecord(id)
                }
                if (this@ReaderActivityUiTest::directory.isInitialized) directory.deleteRecursively()
            }
        }
    }

    @Test fun epubActivityStopsForAppearanceBackgroundAndLockedScreenThenResumes() = runBlocking<Unit> {
        openFixture(PublicationFormat.EPUB)
        awaitEpubText()
        val first = awaitHeartbeat("Opening the EPUB")
        assertTrue("Actual rendered foreground reading must produce a heartbeat", first.sumOf { it.activeDurationMs } > 0L)

        // The actual appearance sheet covers the reader and must stop accounting.
        compose.onNodeWithContentDescription("Reader options").performClick()
        compose.onNodeWithText("Appearance", substring = false).performClick()
        compose.onNodeWithText("Reading appearance").assertIsDisplayed()
        compose.waitForIdle()
        val appearanceShownAt = System.currentTimeMillis()
        SystemClock.sleep(QUIET_PERIOD_MS)
        assertStopsAt("Appearance", appearanceShownAt)
        pressBack()
        compose.waitForIdle()
        compose.onNodeWithTag("reader-screen").assertIsDisplayed()

        // Lifecycle suspension is exercised independently of the appearance modal.
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        val backgroundAt = System.currentTimeMillis()
        SystemClock.sleep(QUIET_PERIOD_MS)
        assertStopsAt("Background", backgroundAt)
        val foregroundRequestedAt = System.currentTimeMillis()
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.waitForIdle()
        compose.onNodeWithTag("reader-screen").assertIsDisplayed()
        awaitHeartbeat("Returning from the background", after = foregroundRequestedAt + 4_000L)

        // This is the real emulator screen-off path, not a manually forced eligibility flag.
        try {
            shell("input keyevent KEYCODE_SLEEP")
            waitForDevice("The sleep command must turn the screen off") { !power.isInteractive }
            val screenOffAt = System.currentTimeMillis()
            SystemClock.sleep(QUIET_PERIOD_MS)
            assertFalse("The device must stay non-interactive during the locked interval", power.isInteractive)
            assertStopsAt("Screen off", screenOffAt)

            val wakeRequestedAt = System.currentTimeMillis()
            restoreAwakeDevice()
            compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
            compose.waitForIdle()
            compose.onNodeWithTag("reader-screen").assertIsDisplayed()
            val resumed = awaitHeartbeat("Waking and unlocking the screen", after = wakeRequestedAt + 4_000L)
            val newlyObserved = resumed.filter { it.endedAt > screenOffAt + BOUNDARY_MARGIN_MS }
            assertTrue("Foreground reading must resume after the actual wake/unlock", newlyObserved.isNotEmpty())
            assertTrue("No checkpoint may bridge the unobserved locked interval",
                newlyObserved.all { it.startedAt >= wakeRequestedAt - BOUNDARY_MARGIN_MS })
        } finally {
            restoreAwakeDevice()
        }
        assertNull(requireNotNull(model).state.value.activityError)
    }

    @Test fun pdfActivityStopsAfterCloseAndLibraryRecreationDoesNotRestartItsTimer() = runBlocking<Unit> {
        openFixture(PublicationFormat.PDF)
        assertEquals(PublicationFormat.PDF, requireNotNull(model).state.value.format)
        assertTrue("The PDF must be ready only after native rendering", requireNotNull(model).state.value.contentReady)
        assertTrue("The actual PDF navigator must exist", nativePdfIsShown())
        assertTrue(awaitHeartbeat("Opening the PDF").sumOf { it.activeDurationMs } > 0L)

        compose.onNodeWithContentDescription("Close reader").performClick()
        compose.onNodeWithText("Search your library").assertIsDisplayed()
        compose.waitForIdle()
        val closedAt = System.currentTimeMillis()
        SystemClock.sleep(QUIET_PERIOD_MS)
        val closed = assertStopsAt("Closed PDF reader", closedAt)
        assertTrue(closed.isNotEmpty())

        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("Search your library").assertIsDisplayed()
        compose.waitForIdle()
        SystemClock.sleep(QUIET_PERIOD_MS)
        assertEquals("Recreating the library must not recreate an old reader recorder", closed, ownSessions())
    }

    private suspend fun openFixture(format: PublicationFormat) {
        val file = if (format == PublicationFormat.EPUB) epubFixture() else pdfFixture()
        val hash = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
        repositories.books().add(Book(id, title, "Leaf original fixtures", null, format,
            file.absolutePath, null, null, file.length(), hash, System.currentTimeMillis()))
        readerDependencies.preferences().resetReaderPrefs(id)
        compose.onNodeWithText("Search your library").performTextReplacement(title)
        closeSoftKeyboard()
        val card = hasText(title) and hasClickAction() and !hasSetTextAction()
        compose.waitUntil(15_000L) { compose.onAllNodes(card).fetchSemanticsNodes().size == 1 }
        compose.onNode(card).performClick()
        compose.onNodeWithTag("reader-screen").assertIsDisplayed()
        compose.activityRule.scenario.onActivity { activity ->
            model = ViewModelProvider(activity)["leaf-reader-$id", ReaderViewModel::class.java]
        }
        compose.waitUntil(20_000L) { model?.state?.value?.contentReady == true || model?.state?.value?.error != null }
        compose.waitForIdle()
        assertNull(requireNotNull(model).state.value.error)
        assertTrue(requireNotNull(model).state.value.contentReady)
    }

    private suspend fun awaitHeartbeat(stage: String, after: Long = 0L): List<ReadingSession> {
        val rows = withTimeoutOrNull(15_000L) {
            repositories.statistics().sessions.first { sessions ->
                sessions.any { it.bookId == id && it.activeDurationMs > 0L && it.endedAt >= after }
            }
        }
        if (rows == null) {
            var focused = false
            compose.activityRule.scenario.onActivity { focused = it.hasWindowFocus() }
            val ready = model?.state?.value?.contentReady
            val recorderError = model?.state?.value?.activityError
            throw AssertionError("$stage must produce a foreground reading checkpoint " +
                "(contentReady=$ready, windowFocused=$focused, interactive=${power.isInteractive}, " +
                "keyguardLocked=${keyguard.isKeyguardLocked}, recorderError=$recorderError)")
        }
        return rows.filter { it.bookId == id }.sortedBy(ReadingSession::id)
    }

    private suspend fun ownSessions(): List<ReadingSession> = repositories.statistics().sessions.first()
        .filter { it.bookId == id }.sortedBy(ReadingSession::id)

    private suspend fun assertStopsAt(reason: String, boundary: Long): List<ReadingSession> {
        val rows = ownSessions()
        assertTrue("$reason must not earn reading time after its actual boundary",
            rows.all { it.endedAt <= boundary + BOUNDARY_MARGIN_MS })
        assertTrue("Every saved row must be a positive closed observed interval", rows.all {
            it.activeDurationMs > 0L && it.startedAt >= 0L && it.endedAt >= it.startedAt &&
                it.activeDurationMs <= it.endedAt - it.startedAt
        })
        assertEquals("Checkpoint retries must not duplicate UUIDs", rows.size, rows.map(ReadingSession::id).distinct().size)
        return rows
    }

    private fun awaitEpubText() {
        val deadline = SystemClock.uptimeMillis() + 10_000L
        while (SystemClock.uptimeMillis() < deadline) {
            val latch = CountDownLatch(1)
            var displayed = false
            compose.activityRule.scenario.onActivity { activity ->
                val web = visibleWebView(activity.window.decorView)
                if (web == null) latch.countDown() else web.evaluateJavascript(
                    "(() => { const passage = document.querySelector('#passage'); " +
                        "return Boolean(passage && passage.innerText.toLowerCase().includes('silver fern')); })()") {
                    displayed = it == "true"
                    latch.countDown()
                }
            }
            assertTrue("The actual EPUB WebView must respond", latch.await(3, TimeUnit.SECONDS))
            if (displayed) return
            SystemClock.sleep(50L)
        }
        throw AssertionError("The real EPUB opening passage must be displayed before activity is counted")
    }

    private fun visibleWebView(view: View): WebView? {
        if (view is WebView && view.isShown && view.getGlobalVisibleRect(Rect())) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) visibleWebView(view.getChildAt(index))?.let { return it }
        return null
    }

    private fun nativePdfIsShown(): Boolean {
        var shown = false
        compose.activityRule.scenario.onActivity { activity ->
            fun find(view: View): Boolean {
                if (view.javaClass.name == "com.github.barteksc.pdfviewer.PDFView" && view.isShown &&
                    view.width > 0 && view.height > 0 && view.getGlobalVisibleRect(Rect())) return true
                return view is ViewGroup && (0 until view.childCount).any { find(view.getChildAt(it)) }
            }
            shown = find(activity.window.decorView)
        }
        return shown
    }

    private fun restoreAwakeDevice() {
        shell("input keyevent KEYCODE_WAKEUP")
        shell("wm dismiss-keyguard")
        if (keyguard.isKeyguardLocked) shell("input keyevent KEYCODE_MENU")
        waitForDevice("The test emulator must be awake and unlocked after cleanup") {
            power.isInteractive && !keyguard.isKeyguardLocked
        }
    }

    private fun shell(command: String) {
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command))
            .use { it.readBytes() }
    }

    private fun waitForDevice(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 5_000L
        while (!condition() && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(50L)
        assertTrue(message, condition())
    }

    private fun epubFixture(): File = File(directory, "standard.epub").also { file ->
        ZipInputStream(instrumentation.context.assets.open("publications/standard.epub")).use { input ->
            ZipOutputStream(file.outputStream()).use { output ->
                output.setComment("Leaf reader activity fixture $id")
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
    }

    private fun pdfFixture(): File = File(directory, "ordinary.pdf").also { file ->
        instrumentation.context.assets.open("publications/ordinary.pdf").use { input ->
            file.outputStream().use(input::copyTo)
        }
        // A legal trailing PDF comment keeps the CC0 publication bytes/offsets intact
        // while ensuring this case cannot collide with any pre-existing library hash.
        file.appendText("\n% Leaf reader activity fixture $id\n")
    }

    private companion object {
        const val QUIET_PERIOD_MS = 6_000L
        const val BOUNDARY_MARGIN_MS = 500L
    }
}
