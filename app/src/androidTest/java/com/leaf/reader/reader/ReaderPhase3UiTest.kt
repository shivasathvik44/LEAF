package com.leaf.reader.reader

import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Rect
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.webkit.WebView
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextReplacement
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.ViewModelProvider
import androidx.room.withTransaction
import androidx.test.espresso.Espresso.closeSoftKeyboard
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.pressBack
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.matcher.RootMatchers.withDecorView
import androidx.test.espresso.matcher.ViewMatchers.hasDescendant
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.leaf.reader.MainActivity
import com.leaf.reader.core.util.PublicationFormat
import com.leaf.reader.data.readium.ReaderDocument
import com.leaf.reader.data.readium.ReaderHarnessDependencies
import com.leaf.reader.data.readium.ReaderUiDependencies
import com.leaf.reader.domain.model.Book
import com.leaf.reader.domain.model.Highlight
import com.leaf.reader.feature.reader.ReaderViewModel
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
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Uses MainActivity's real Hilt ViewModel, singleton Room/DataStore repositories and Readium.
 * Every reader action is a visible UI interaction; the ViewModel is read only for observability
 * and closed during cleanup. Each case owns one UUID book, archive and optional TEMP trigger.
 */
@RunWith(AndroidJUnit4::class)
class ReaderPhase3UiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val dependencies get() = EntryPointAccessors.fromApplication(
        context.applicationContext, ReaderHarnessDependencies::class.java)
    private val database get() = EntryPointAccessors.fromApplication(
        context.applicationContext, ReaderUiDependencies::class.java).database()
    private lateinit var directory: File
    private lateinit var id: String
    private lateinit var title: String
    private var model: ReaderViewModel? = null
    private var triggerName: String? = null
    private var lastPointerDescription = "No native pointer injected"
    private var immersiveConfirmationChecked = false
    private var lastSystemPromptDescription = "No fullscreen confirmation observed"

    @Before fun setUp() = runBlocking<Unit> {
        // The Compose rule has already launched MainActivity. Inserting through its singleton
        // repository must update the production library Flow without relaunching the app.
        id = UUID.randomUUID().toString()
        title = "Leaf UI $id"
        directory = File(context.cacheDir, "reader-ui-tests/$id").apply { check(mkdirs()) }
        val file = fixture()
        val hash = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
            .joinToString("") { "%02x".format(it) }
        dependencies.books().add(Book(id, title, "Leaf original fixtures", null,
            PublicationFormat.EPUB, file.absolutePath, null, null, file.length(), hash,
            System.currentTimeMillis()))
        dependencies.preferences().resetReaderPrefs(id)
        compose.onNodeWithText("Search your library").performTextReplacement(title)
        closeSoftKeyboard()
        val bookCard = hasText(title) and hasClickAction() and !hasSetTextAction()
        compose.waitUntil(15_000) {
            compose.onAllNodes(bookCard).fetchSemanticsNodes().size == 1
        }
        compose.onNode(bookCard).performClick()
        compose.onNodeWithTag("reader-screen").assertIsDisplayed()
        compose.activityRule.scenario.onActivity { activity ->
            model = ViewModelProvider(activity)["leaf-reader-$id", ReaderViewModel::class.java]
        }
        awaitState { it.document?.currentLocation() != null && !it.highlightsLoading }
        assertNull(requireNotNull(model).state.value.error)
        awaitJavascript("Boolean(document.querySelector('#passage'))", "true")
    }

    @After fun tearDown() = runBlocking<Unit> {
        try {
            dropTrigger()
        } finally {
            compose.activityRule.scenario.onActivity { model?.closeReader() }
            compose.activityRule.scenario.close()
            if (this@ReaderPhase3UiTest::id.isInitialized) {
                dependencies.preferences().resetReaderPrefs(id)
                dependencies.books().removeRecord(id)
            }
            if (this@ReaderPhase3UiTest::directory.isInitialized) directory.deleteRecursively()
        }
    }

    @Test fun realSelectionCanSaveEditRecreateAndDeleteHighlightAndNote() = runBlocking<Unit> {
        selectSilverThroughNativeMenu()
        val originalNote = "Original UI note $id"
        saveEditor("Blue", originalNote)
        val saved = awaitAnnotation(BLUE, originalNote)
        assertEquals("silver", saved.selectedText.trim().lowercase())
        assertEquals("silver", JSONObject(saved.locatorJson).getJSONObject("text")
            .getString("highlight").trim().lowercase())
        awaitJavascript(visibleColor("148,201,232"), "true")

        // Leave the saved chapter, then navigate back through its actual annotation row.
        openReaderPanel("Contents")
        compose.onNodeWithText("The river path").performClick()
        awaitState { it.document?.currentLocation()?.locatorJson?.contains("chapter2") == true }
        awaitJavascript(HEADING_TEXT, "\"The river path\"")
        openReaderPanel("Highlights and notes")
        compose.onNodeWithText(originalNote).assertIsDisplayed()
        compose.onNodeWithText("Go to passage").performClick()
        awaitState { it.document?.currentLocation()?.locatorJson?.contains("chapter1") == true }
        awaitJavascript(visibleColor("148,201,232"), "true")

        // A real tap on the rendered decoration must activate the production note editor.
        openReaderPanel("Hide controls")
        awaitState { !it.controlsVisible }
        pointerOnText("#passage", 2, 8, longPress = false)
        compose.waitUntil(15_000) {
            compose.onAllNodesWithText("Highlight and note").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Highlight and note").assertIsDisplayed()
        assertEquals(saved.id, requireNotNull(model).state.value.editingHighlight)
        val editedNote = "Edited UI note survives recreation $id"
        saveEditor("Green", editedNote)
        val edited = awaitAnnotation(GREEN, editedNote)
        assertEquals(saved.id, edited.id)
        assertEquals(saved.locatorJson, edited.locatorJson)
        assertEquals(editedNote, database.annotations().noteForHighlight(saved.id)?.content)
        awaitJavascript(visibleColor("156,204,155"), "true")

        openReaderPanel("Highlights and notes")
        compose.onNodeWithText(editedNote).assertIsDisplayed()
        val retainedModel = model
        compose.activityRule.scenario.recreate()
        compose.activityRule.scenario.onActivity { activity ->
            model = ViewModelProvider(activity)["leaf-reader-$id", ReaderViewModel::class.java]
        }
        assertSame("Configuration changes must retain the actual reader ViewModel", retainedModel, model)
        compose.onNodeWithText("Highlights and notes").assertIsDisplayed()
        compose.onNodeWithText(editedNote).assertIsDisplayed()
        assertEquals(edited, dependencies.annotations().highlights(id).first().single())

        // Verify the restored sheet first, then uncover the WebView before checking its viewport.
        pressBack()
        compose.onNodeWithText("Highlights and notes").assertDoesNotExist()
        try {
            awaitState { it.contentReady }
        } catch (timeout: ComposeTimeoutException) {
            throw AssertionError("The recreated reader must render after the restored sheet is dismissed. ${nativeReaderDiagnostic()}", timeout)
        }
        awaitJavascript(visibleColor("156,204,155"), "true")
        openReaderPanel("Highlights and notes")
        compose.onNodeWithText(editedNote).assertIsDisplayed()
        compose.onNodeWithText("Edit note").performClick()
        compose.onNodeWithText("Delete highlight and note").performScrollTo().performClick()
        withTimeout(15_000) { dependencies.annotations().highlights(id).first { it.isEmpty() } }
        awaitState { it.editingHighlight == null && !it.annotationSaving }
        awaitJavascript("document.querySelectorAll('[class*=\"r2-highlight-\"]').length", "0")
        assertNull(database.annotations().getHighlight(saved.id))
        assertNull("Deleting through the editor must cascade to its note", database.annotations().noteForHighlight(saved.id))
        openReaderPanel("Highlights and notes")
        compose.onNodeWithText("Select text in the book, then choose Highlight.").assertIsDisplayed()
    }

    @Test fun readerSearchShowsEmptyStateContextAndNavigatesToLastResult() = runBlocking<Unit> {
        openReaderPanel("Search this book")
        compose.onNodeWithTag("reader-search-query").performTextReplacement("absent-$id")
        compose.onNodeWithText("Search", substring = false).performClick()
        compose.waitUntil(15_000) {
            compose.onAllNodesWithText("No matching passages. Try another phrase.")
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("No matching passages. Try another phrase.").assertIsDisplayed()
        compose.onNodeWithTag("reader-search-query").performTextReplacement("silver fern")
        compose.onNodeWithText("Search", substring = false).performClick()
        awaitState { it.searchStarted && !it.searchRunning && it.searchResults.size == 3 }
        assertNull(requireNotNull(model).state.value.searchError)
        val results = requireNotNull(model).state.value.searchResults
        assertTrue(results.all { it.match.equals("silver fern", true) && it.before.isNotBlank() && it.after.isNotBlank() })
        compose.onNodeWithText("3 matching passages").assertIsDisplayed()
        compose.onNodeWithTag("reader-search-results").performScrollToIndex(2)
        compose.onAllNodesWithTag("reader-search-result").onLast()
            .assert(hasText("silver fern", substring = true, ignoreCase = true))
            .assert(hasText("searchable passage 3", substring = true))
            .performClick()

        awaitState { it.document?.currentLocation()?.locatorJson?.contains("chapter3") == true }
        awaitJavascript(HEADING_TEXT, "\"A warm return\"")
        val progress = withTimeout(15_000) {
            dependencies.reading().progress(id).first { it?.locatorJson?.contains("chapter3") == true }
        }
        assertTrue(requireNotNull(ReaderDocument.parse(requireNotNull(progress).locatorJson))
            .href.toString().contains("chapter3"))
        assertTrue(JSONObject(progress.locatorJson).getString("href").contains("chapter3"))
        assertTrue(JSONObject(progress.locatorJson).has("locations"))
    }

    @Test fun rapidChapterRequestsReturnToFirstVisiblePassage() {
        // Both requests run in the same UI turn, before Readium's debounced locator Flow
        // can report the intermediate chapter. The second must cancel the first seek.
        compose.activityRule.scenario.onActivity {
            requireNotNull(model).goChapter(1)
            requireNotNull(model).goChapter(0)
        }
        compose.waitForIdle()
        val firstPageVisible = """
            (()=>{
                if(!location.pathname.endsWith('/chapter1.xhtml'))return false;
                const viewport=window.visualViewport,left=viewport?viewport.offsetLeft:0,top=viewport?viewport.offsetTop:0;
                const width=viewport?viewport.width:innerWidth,height=viewport?viewport.height:innerHeight;
                const visible=element=>{
                    if(!element)return false;
                    const range=document.createRange();range.selectNodeContents(element);
                    return Array.from(range.getClientRects()).some(r=>r.width>0&&r.height>0&&
                        r.right>left&&r.left<left+width&&r.bottom>top&&r.top<top+height);
                };
                return visible(document.querySelector('h1'))&&visible(document.querySelector('#passage'));
            })()
        """.trimIndent()
        val deadline = SystemClock.uptimeMillis() + 20_000
        var stableSince: Long? = null
        while (SystemClock.uptimeMillis() < deadline) {
            var locationIsAtStart = false
            compose.activityRule.scenario.onActivity {
                val location = requireNotNull(model).state.value.document?.currentLocation()
                locationIsAtStart = location != null && location.locatorJson.contains("chapter1") && location.progression <= 0.01
            }
            val visible = javascript(firstPageVisible) == "true"
            if (locationIsAtStart && visible) {
                val now = SystemClock.uptimeMillis()
                if (stableSince == null) stableSince = now
                // Keep observing beyond Readium's 100 ms pagination debounce, so an old
                // chapter-switch callback cannot move the returned chapter to its last page.
                if (now - requireNotNull(stableSince) >= 600) return
            } else stableSince = null
            SystemClock.sleep(75)
        }
        fail("Rapid chapter navigation must leave the first passage visible at the saved start: ${annotationViewportDiagnostic()}")
    }

    @Test fun noteDatabaseFailureKeepsVisibleDraftAndRollsBackUntilUserRetries() = runBlocking<Unit> {
        selectSilverThroughNativeMenu()
        val draft = "Retryable UI note $id"
        triggerName = "leaf_ui_note_${id.replace("-", "") }"
        // A real SQLite failure on the writer connection, restricted to this test's own note.
        // TEMP keeps the fault out of the persistent schema and all unrelated books are ignored.
        database.withTransaction {
            database.openHelper.writableDatabase.execSQL("""
                CREATE TEMP TRIGGER $triggerName BEFORE INSERT ON main.notes
                WHEN NEW.content = '$draft'
                  AND EXISTS (SELECT 1 FROM main.highlights WHERE id = NEW.highlightId AND bookId = '$id')
                BEGIN SELECT RAISE(ABORT, 'expected UI note insertion failure'); END
            """.trimIndent())
        }
        saveEditor("Blue", draft, expectSuccess = false)
        awaitState { it.annotationError != null && !it.annotationSaving }
        compose.onNodeWithText(SAVE_FAILURE).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Highlight passage").assertIsDisplayed()
        compose.onNodeWithTag("annotation-note").performScrollTo().assertTextContains(draft)
        compose.onNodeWithText("Blue").performScrollTo().assertIsSelected()
        compose.onNodeWithTag("annotation-save").assertIsEnabled()
        assertTrue("A failed note insert must roll back the new highlight", dependencies.annotations().highlights(id).first().isEmpty())
        database.openHelper.readableDatabase.query("SELECT COUNT(*) FROM notes WHERE content = ?", arrayOf(draft)).use {
            assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0))
        }
        awaitJavascript("document.querySelectorAll('[class*=\"r2-highlight-\"]').length", "0")

        dropTrigger()
        // Retry the very same retained draft; no repository or ViewModel save calls bypass the UI.
        compose.onNodeWithTag("annotation-save").performClick()
        val saved = awaitAnnotation(BLUE, draft)
        awaitState { it.selection == null && !it.annotationSaving }
        assertEquals(draft, database.annotations().noteForHighlight(saved.id)?.content)
        awaitJavascript(visibleColor("148,201,232"), "true")
    }

    private fun selectSilverThroughNativeMenu() {
        openReaderPanel("Hide controls")
        awaitState { !it.controlsVisible }
        pointerOnText("#passage", 2, 8, longPress = true)
        awaitJavascript(SELECTED_TEXT, "\"silver\"")
        // Android's floating text toolbar has a non-focusable native PopupWindow root.
        onView(withText("Highlight"))
            .inRoot(withDecorView(hasDescendant(withText("Highlight"))))
            .perform(click())
        try {
            compose.waitUntil(15_000) {
                requireNotNull(model).state.value.let { it.selection != null || it.message != null || it.error != null }
            }
        } catch (failure: Exception) {
            throw AssertionError("The real native Highlight action did not publish a selection: ${selectionDiagnostic()}", failure)
        }
        assertNotNull("The native Highlight action must open an editable selection: ${selectionDiagnostic()}",
            requireNotNull(model).state.value.selection)
        compose.waitUntil(15_000) {
            compose.onAllNodesWithText("Highlight passage").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Highlight passage").assertIsDisplayed()
        assertEquals("silver", requireNotNull(model).state.value.selection?.selectedText?.trim()?.lowercase())
    }

    private fun saveEditor(color: String, note: String, expectSuccess: Boolean = true) {
        compose.onNodeWithText(color).performScrollTo().performClick().assertIsSelected()
        compose.onNodeWithTag("annotation-note").performScrollTo().performTextReplacement(note)
        closeSoftKeyboard()
        compose.onNodeWithTag("annotation-save").assertIsEnabled().performClick()
        if (expectSuccess) awaitState { it.selection == null && it.editingHighlight == null && !it.annotationSaving }
    }

    private suspend fun awaitAnnotation(color: Long, note: String): Highlight = withTimeout(15_000) {
        dependencies.annotations().highlights(id).first { rows ->
            rows.size == 1 && rows.single().color == color && rows.single().note == note
        }.single()
    }

    private fun openReaderPanel(label: String) {
        if (requireNotNull(model).state.value.controlsVisible.not()) {
            // Tap real unannotated text, so this opens the toolbar without activating a highlight.
            tapVisibleUnannotatedText()
            awaitState { it.controlsVisible }
        }
        compose.onNodeWithContentDescription("Reader options").performClick()
        compose.onNodeWithText(label).performClick()
    }

    private fun awaitState(condition: (com.leaf.reader.feature.reader.ReaderUiState) -> Boolean) {
        compose.waitUntil(20_000) {
            var satisfied = false
            instrumentation.runOnMainSync { satisfied = model?.state?.value?.let(condition) == true }
            satisfied
        }
    }

    private suspend fun dropTrigger() {
        val name = triggerName ?: return
        database.withTransaction { database.openHelper.writableDatabase.execSQL("DROP TRIGGER IF EXISTS temp.$name") }
        triggerName = null
    }

    private fun fixture(): File = File(directory, "standard.epub").also { file ->
        ZipInputStream(instrumentation.context.assets.open("publications/standard.epub")).use { input ->
            ZipOutputStream(file.outputStream()).use { output ->
                // Preserve the CC0 fixture content, while each test gets a unique import hash.
                output.setComment("Leaf production UI test $id")
                while (true) {
                    val source = input.nextEntry ?: break
                    val bytes = input.readBytes()
                    val entry = ZipEntry(source.name)
                    if (source.name == "mimetype") {
                        entry.method = ZipEntry.STORED; entry.size = bytes.size.toLong()
                        entry.crc = CRC32().apply { update(bytes) }.value
                    }
                    output.putNextEntry(entry); output.write(bytes); output.closeEntry(); input.closeEntry()
                }
            }
        }
    }

    private fun javascript(script: String): String {
        val latch = CountDownLatch(1)
        var result = "null"
        compose.activityRule.scenario.onActivity { activity ->
            val web = visibleWebView(activity.window.decorView)
            if (web == null) latch.countDown()
            else web.evaluateJavascript(script) { result = it; latch.countDown() }
        }
        assertTrue("Native WebView callback timed out", latch.await(3, TimeUnit.SECONDS))
        return result
    }

    private fun awaitJavascript(script: String, expected: String) {
        // Panel dismissal and the reader's updated layout must run on the Compose clock
        // before evaluating the native viewport reached by a user navigation action.
        compose.waitForIdle()
        val deadline = SystemClock.uptimeMillis() + 20_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (javascript(script) == expected) return
            SystemClock.sleep(75)
        }
        assertEquals("The production EPUB DOM must reflect the requested UI action; $lastPointerDescription; ${annotationViewportDiagnostic()}", expected, javascript(script))
    }

    private fun annotationViewportDiagnostic(): String {
        val dom = javascript("""
            (()=>{
                const rect=r=>({x:r.left,y:r.top,width:r.width,height:r.height});
                const viewport=window.visualViewport, passage=document.querySelector('#passage');
                const range=document.createRange();if(passage)range.selectNodeContents(passage);
                const highlights=Array.from(document.querySelectorAll('[class*=\"r2-highlight-\"]')).slice(0,20).map(e=>{
                    const style=getComputedStyle(e);return {color:style.backgroundColor,opacity:style.opacity,
                        visibility:style.visibility,rects:Array.from(e.getClientRects()).map(rect)};
                });
                return {href:location.href,scrollX,scrollY,viewport:{width:viewport?viewport.width:innerWidth,
                    height:viewport?viewport.height:innerHeight,left:viewport?viewport.offsetLeft:0,
                    top:viewport?viewport.offsetTop:0,scale:viewport?viewport.scale:null},
                    passage:passage?Array.from(range.getClientRects()).map(rect):null,highlights};
            })()
        """.trimIndent())
        // Every locator below belongs to this test's own CC0 fixture and UUID book.
        return "savedLocators=${model?.state?.value?.highlights?.map { it.locatorJson }}; nativeDOM=$dom"
    }

    private fun nativeReaderDiagnostic(): String {
        fun fragments(manager: FragmentManager): String = manager.fragments.joinToString(prefix = "[", postfix = "]") { fragment ->
            val children = if (fragment.isAdded) fragments(fragment.childFragmentManager) else "[]"
            "${fragment.javaClass.simpleName}(${fragment.lifecycle.currentState},view=${fragment.view != null},children=$children)"
        }
        var diagnostic = ""
        compose.activityRule.scenario.onActivity { activity ->
            val state = requireNotNull(model).state.value
            diagnostic = "focus=${activity.hasWindowFocus()}, loading=${state.loading}, error=${state.error}, " +
                "fragments=${fragments(activity.supportFragmentManager)}"
        }
        return diagnostic
    }

    private fun visibleWebView(view: View): WebView? {
        if (view is WebView && view.isShown && view.getGlobalVisibleRect(Rect())) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) {
            visibleWebView(view.getChildAt(index))?.let { return it }
        }
        return null
    }

    private fun pointerOnText(selector: String, start: Int, end: Int, longPress: Boolean) {
        pointerAtDomPoint("""
            (()=>{const n=document.querySelector('$selector').firstChild;const r=document.createRange();
            r.setStart(n,$start);r.setEnd(n,$end);const b=r.getBoundingClientRect();
            const viewport=window.visualViewport;
            const x=b.left+b.width/2,y=b.top+b.height/2,hit=document.elementFromPoint(x,y);
            return {x,y,width:viewport?viewport.width:window.innerWidth,
            left:viewport?viewport.offsetLeft:0,top:viewport?viewport.offsetTop:0,
            hitTag:hit?hit.tagName:null,hitId:hit?hit.id:null,href:location.href};})()
        """.trimIndent(), "selector=$selector", longPress)
    }

    private fun tapVisibleUnannotatedText() {
        pointerAtDomPoint("""
            (()=>{
                const viewport=window.visualViewport,left=viewport?viewport.offsetLeft:0,top=viewport?viewport.offsetTop:0;
                const width=viewport?viewport.width:innerWidth,height=viewport?viewport.height:innerHeight;
                const highlights=Array.from(document.querySelectorAll('[class*="r2-highlight-"]'))
                    .flatMap(e=>Array.from(e.getClientRects()));
                for(const element of document.querySelectorAll('h1,p:not(#passage)')) {
                    const range=document.createRange();range.selectNodeContents(element);
                    for(const rect of range.getClientRects()) {
                        const x=rect.left+rect.width/2,y=rect.top+rect.height/2;
                        if(rect.width<=0||rect.height<=0||x<=left||x>=left+width||y<=top||y>=top+height) continue;
                        if(highlights.some(r=>x>=r.left&&x<=r.right&&y>=r.top&&y<=r.bottom)) continue;
                        const hit=document.elementFromPoint(x,y);
                        if(!hit||hit.closest('a,[class*="r2-highlight-"]')) continue;
                        return {x,y,width,left,top,hitTag:hit.tagName,hitId:hit.id,href:location.href};
                    }
                }
                return null;
            })()
        """.trimIndent(), "visible unannotated text", longPress = false)
    }

    private fun pointerAtDomPoint(script: String, target: String, longPress: Boolean) {
        awaitStableReaderViewport()
        val rendered = CountDownLatch(1)
        compose.activityRule.scenario.onActivity { activity ->
            val web = requireNotNull(visibleWebView(activity.window.decorView))
            web.postVisualStateCallback(SystemClock.uptimeMillis(), object : WebView.VisualStateCallback() {
                override fun onComplete(requestId: Long) { rendered.countDown() }
            })
        }
        assertTrue("The native EPUB page did not finish rendering", rendered.await(5, TimeUnit.SECONDS))
        val result = javascript(script)
        assertNotEquals("The current EPUB page must contain $target", "null", result)
        val point = JSONObject(result)
        var x = 0f; var y = 0f
        compose.activityRule.scenario.onActivity { activity ->
            val web = requireNotNull(visibleWebView(activity.window.decorView))
            val location = IntArray(2); web.getLocationOnScreen(location)
            // Overview zoom means devicePixelRatio alone does not map CSS to screen pixels.
            val scale = web.width / point.getDouble("width")
            x = location[0] + ((point.getDouble("x") - point.getDouble("left")) * scale).toFloat()
            y = location[1] + ((point.getDouble("y") - point.getDouble("top")) * scale).toFloat()
            val visible = Rect()
            val isVisible = web.getGlobalVisibleRect(visible)
            lastPointerDescription = "$target CSS=$point screen=($x,$y) visible=$visible controls=${model?.state?.value?.controlsVisible}"
            assertTrue("The target must be inside the native WebView: $lastPointerDescription", isVisible && visible.contains(x.toInt(), y.toInt()))
        }
        val down = SystemClock.uptimeMillis()
        MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, x, y, 0).also {
            instrumentation.sendPointerSync(it); it.recycle()
        }
        SystemClock.sleep(if (longPress) 1_100 else 80)
        MotionEvent.obtain(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x, y, 0).also {
            instrumentation.sendPointerSync(it); it.recycle()
        }
        instrumentation.waitForIdleSync()
        lastPointerDescription += " controlsAfter=${model?.state?.value?.controlsVisible} DOMselectionAfter=${javascript(SELECTED_TEXT)} " +
            "activeAccessibilityPackage=${instrumentation.uiAutomation.rootInActiveWindow?.packageName} fullscreen=$lastSystemPromptDescription"
    }

    private fun selectionDiagnostic(): String = requireNotNull(model).state.value.let {
        "UIselection=${it.selection?.selectedText?.length}, documentSelection=${it.document?.selection?.value?.selectedText?.length}, " +
            "controls=${it.controlsVisible}, message=${it.message}, error=${it.error}, annotationError=${it.annotationError}; $lastPointerDescription"
    }

    private fun awaitStableReaderViewport() {
        val hidden = requireNotNull(model).state.value.controlsVisible.not()
        if (hidden) {
            // An existing system prompt can own the window before Espresso synchronizes.
            dismissFullscreenConfirmation(timeoutMillis = 250)
        }
        compose.waitForIdle()
        if (hidden && !immersiveConfirmationChecked) {
            // Advancing Compose above actually closes the menu and applies immersive mode.
            // Android's delayed first-use prompt can only appear after those effects run.
            dismissFullscreenConfirmation(timeoutMillis = 2_000)
            immersiveConfirmationChecked = true
            compose.waitForIdle()
        }
        if (requireNotNull(model).state.value.controlsVisible.not()) {
            compose.onNodeWithContentDescription("Reader options").assertDoesNotExist()
            compose.onNodeWithText("Hide controls").assertDoesNotExist()
        }
        val deadline = SystemClock.uptimeMillis() + 5_000
        var previous: String? = null
        var stableFrames = 0
        while (SystemClock.uptimeMillis() < deadline) {
            var frame: String? = null
            compose.activityRule.scenario.onActivity { activity ->
                val web = visibleWebView(activity.window.decorView) ?: return@onActivity
                val bounds = Rect()
                val barsVisible = ViewCompat.getRootWindowInsets(web)?.isVisible(WindowInsetsCompat.Type.systemBars())
                val barsSettled = requireNotNull(model).state.value.controlsVisible || barsVisible == false
                if (activity.hasWindowFocus() && !web.isLayoutRequested && web.getGlobalVisibleRect(bounds) && barsSettled) {
                    frame = "$bounds:${web.width}:${web.height}:$barsVisible"
                }
            }
            stableFrames = if (frame != null && frame == previous) stableFrames + 1 else 0
            if (stableFrames >= 3) return
            previous = frame
            SystemClock.sleep(50)
        }
        fail("The reader's physical viewport and system insets did not settle before native input: $previous")
    }

    /** Acknowledge Android's real first-immersive-use prompt, which can cover the EPUB. */
    private fun dismissFullscreenConfirmation(timeoutMillis: Long) {
        val automation = instrumentation.uiAutomation
        val serviceInfo = automation.serviceInfo
        val originalFlags = serviceInfo.flags
        serviceInfo.flags = originalFlags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        automation.serviceInfo = serviceInfo
        try {
            // Android posts this onboarding window after the system-bar exit animation.
            val deadline = SystemClock.uptimeMillis() + timeoutMillis
            while (SystemClock.uptimeMillis() < deadline) {
                val roots = automation.windows.mapNotNull { it.root } + listOfNotNull(automation.rootInActiveWindow)
                lastSystemPromptDescription = roots.take(8).joinToString { root ->
                    "package=${root.packageName}, title=${root.findAccessibilityNodeInfosByText("Viewing full screen").map { it.text }}, " +
                        "confirmation=${root.findAccessibilityNodeInfosByText("Got it").map { "${it.text}/clickable=${it.isClickable}" }}"
                }
                for (root in roots) {
                    if (root.packageName?.toString() !in setOf("android", "com.android.systemui")) continue
                    if (root.findAccessibilityNodeInfosByText("Viewing full screen").none { it.text?.toString() == "Viewing full screen" }) continue
                    val confirmation = root.findAccessibilityNodeInfosByText("Got it").firstOrNull {
                        it.text?.toString() == "Got it" && it.isClickable && it.isEnabled &&
                            it.packageName?.toString() in setOf("android", "com.android.systemui")
                    } ?: continue
                    assertTrue("Android's fullscreen confirmation must accept its actual Got it action",
                        confirmation.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                    lastSystemPromptDescription += "; actualSystemConfirmationClicked=true"
                    return
                }
                SystemClock.sleep(50)
            }
        } finally {
            serviceInfo.flags = originalFlags
            automation.serviceInfo = serviceInfo
        }
    }

    private fun visibleColor(rgb: String): String =
        "Array.from(document.querySelectorAll('[class*=\"r2-highlight-\"]')).some(e=>{" +
            "const v=window.visualViewport,l=v?v.offsetLeft:0,t=v?v.offsetTop:0,w=v?v.width:innerWidth,h=v?v.height:innerHeight,s=getComputedStyle(e);" +
            "return s.visibility!=='hidden'&&Number(s.opacity)>0&&s.backgroundColor.replace(/\\s/g,'').includes('$rgb')&&" +
            "Array.from(e.getClientRects()).some(r=>r.width>0&&r.height>0&&r.right>l&&r.left<l+w&&r.bottom>t&&r.top<t+h);})"

    companion object {
        // The API 24 CI image uses Chrome 69 for WebView, which predates optional chaining.
        private const val HEADING_TEXT = "(() => { const heading = document.querySelector('h1'); return heading && heading.innerText; })()"
        private const val SELECTED_TEXT = "(() => { const selection = window.getSelection(); return selection && selection.toString().trim().toLowerCase(); })()"
        private const val BLUE = 0xFF94C9E8L
        private const val GREEN = 0xFF9CCC9BL
        private const val SAVE_FAILURE = "This annotation could not be saved. Your draft has been kept. Please retry."
    }
}
