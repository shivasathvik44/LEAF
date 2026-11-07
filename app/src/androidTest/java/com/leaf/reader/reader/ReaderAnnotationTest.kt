package com.leaf.reader.reader

import android.content.Intent
import android.graphics.Rect
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.lifecycle.lifecycleScope
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.matcher.RootMatchers.withDecorView
import androidx.test.espresso.matcher.ViewMatchers.hasDescendant
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.leaf.reader.core.util.PublicationFormat
import com.leaf.reader.data.readium.PublicationRepository
import com.leaf.reader.data.readium.ReaderDocument
import com.leaf.reader.data.readium.ReaderHarnessActivity
import com.leaf.reader.data.readium.ReaderHarnessDependencies
import com.leaf.reader.data.readium.ReaderSearchException
import com.leaf.reader.data.readium.ReaderSearchResult
import com.leaf.reader.domain.model.Book
import dagger.hilt.android.EntryPointAccessors
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import java.util.zip.ZipInputStream
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Native selection/decoration/search integration with real Room annotation persistence. */
@RunWith(AndroidJUnit4::class)
class ReaderAnnotationTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val dependencies get() = EntryPointAccessors.fromApplication(context.applicationContext, ReaderHarnessDependencies::class.java)
    private lateinit var directory: File
    private var bookId: String? = null
    private var scenario: ActivityScenario<ReaderHarnessActivity>? = null

    @Before fun setUp() {
        shell("settings put secure immersive_mode_confirmations confirmed")
        directory = File(context.cacheDir, "reader-annotation-tests/${UUID.randomUUID()}").apply { check(mkdirs()) }
    }
    @After fun tearDown() = runBlocking {
        scenario?.close()
        bookId?.let { dependencies.books().removeRecord(it) }
        if (this@ReaderAnnotationTest::directory.isInitialized) directory.deleteRecursively()
    }

    @Test fun userSelectionCreatesVisibleDecorationsAndNotesSurviveRecreation() = runBlocking {
        val file = fixture()
        val id = insertBook(file)
        launch(file, id)
        await { it.document?.currentLocation() != null }
        awaitJavascript("Boolean(document.querySelector('#passage'))", "true")
        longPressSilver()
        awaitJavascript("window.getSelection().toString().trim().toLowerCase()", "\"silver\"")
        // The native floating selection toolbar is a non-focusable PopupWindow, which
        // Espresso's default root matcher excludes. Require the actual menu item's root.
        onView(withText("Highlight"))
            .inRoot(withDecorView(hasDescendant(withText("Highlight"))))
            .perform(click())
        await { it.document?.selection?.value != null }
        var selection: com.leaf.reader.data.readium.ReaderSelection? = null
        scenario!!.onActivity { selection = it.document!!.selection.value }
        val selected = requireNotNull(selection)
        assertEquals("silver", selected.selectedText.trim().lowercase())
        val annotationId = dependencies.annotations().addHighlight(id, selected.locatorJson, selected.selectedText, 0xFFFFD976L, "Original private test note")
        awaitJavascript(visibleColor("255,217,118"), "true")
        dependencies.annotations().updateHighlight(annotationId, 0xFF94C9E8L, "Edited note survives reopening")
        awaitJavascript(visibleColor("148,201,232"), "true")
        assertEquals("Edited note survives reopening", dependencies.annotations().highlights(id).first().single().note)
        scenario!!.recreate()
        await { it.document?.currentLocation() != null }
        awaitJavascript(visibleColor("148,201,232"), "true")
        dependencies.annotations().deleteHighlight(annotationId)
        awaitJavascript("document.querySelectorAll('[class*=\"r2-highlight-\"]').length", "0")
        assertTrue(dependencies.annotations().highlights(id).first().isEmpty())
    }

    @Test fun readiumSearchReturnsContextAndNavigatesToMatchingChapter() = runBlocking {
        val file = fixture()
        launch(file, insertBook(file))
        await { it.document?.currentLocation() != null }
        lateinit var document: ReaderDocument
        scenario!!.onActivity { document = it.document!! }
        val results = mutableListOf<ReaderSearchResult>()
        document.search("silver fern") { results += it }
        assertEquals(3, results.size)
        assertTrue(results.all { it.match.equals("silver fern", true) && it.before.isNotEmpty() && it.after.isNotEmpty() })
        val last = results.last()
        var navigated = false
        scenario!!.onActivity { activity -> activity.lifecycleScope.launch { navigated = activity.document!!.goSearchResult(last) } }
        await { navigated && it.document?.currentLocation()?.locatorJson?.contains("chapter3") == true }
        assertTrue(navigated)
        awaitJavascript("document.body.innerText.toLowerCase().includes('silver fern')", "true")
        // Moving backward must land on the actual match, rather than the chapter's final page.
        navigated = false
        scenario!!.onActivity { activity -> activity.lifecycleScope.launch { navigated = activity.document!!.goSearchResult(results.first()) } }
        await { navigated && it.document?.currentLocation()?.locatorJson?.contains("chapter1") == true }
        awaitJavascript(visibleColor("142,201,222"), "true")
    }

    @Test fun overlyBroadSearchFailsClearlyBeforeUnboundedLocatorAllocation() = runBlocking {
        val original = fixture()
        val repeated = File(directory, "many-matches.epub")
        ZipFile(original).use { source -> ZipOutputStream(repeated.outputStream()).use { output ->
            source.entries().asSequence().forEach { entry ->
                val bytes = if (entry.name.endsWith("chapter1.xhtml"))
                    ("<html xmlns=\"http://www.w3.org/1999/xhtml\"><head><title>Many matches</title></head><body><p>" + "fern ".repeat(1_001) + "</p></body></html>").toByteArray()
                else source.getInputStream(entry).use { it.readBytes() }
                val target = ZipEntry(entry.name)
                if (entry.name == "mimetype") { target.method = ZipEntry.STORED; target.size = bytes.size.toLong(); target.crc = CRC32().apply { update(bytes) }.value }
                output.putNextEntry(target); output.write(bytes); output.closeEntry()
            }
        } }
        val document = ReaderDocument(PublicationRepository(context).open(repeated), PublicationFormat.EPUB, null)
        try {
            try { document.search("fern") {}; fail("A broad query must stop with a recoverable match-limit error") }
            catch (error: ReaderSearchException) { assertTrue(error.displayMessage.contains("more specific phrase")) }
        } finally { document.releaseOwner() }
    }

    @Test fun pdfDoesNotAdvertiseOrRunEpubTextSearch() = runBlocking {
        val file = File(directory, "ordinary.pdf")
        instrumentation.context.assets.open("publications/ordinary.pdf").use { input -> file.outputStream().use(input::copyTo) }
        val document = ReaderDocument(PublicationRepository(context).open(file), PublicationFormat.PDF, null)
        try {
            assertFalse(document.supportsTextSelection)
            var receivedResults = false
            try { document.search("river") { receivedResults = true }; fail("PDF search must remain unsupported") }
            catch (failure: ReaderSearchException) { assertTrue(failure.displayMessage.contains("EPUB")) }
            assertFalse(receivedResults)
        } finally { document.releaseOwner() }
    }

    @Test fun largeResultPayloadFailsClearlyBelowTheMatchCountLimit() = runBlocking {
        val original = fixture()
        val file = File(directory, "large-result-payload.epub")
        ZipFile(original).use { source -> ZipOutputStream(file.outputStream()).use { output ->
            source.entries().asSequence().forEach { entry ->
                val bytes = when {
                    entry.name.endsWith("chapter1.xhtml") ->
                        ("<html xmlns=\"http://www.w3.org/1999/xhtml\"><head><title>Results</title></head><body><p>" +
                            "BUDGETMATCH with nearby context. ".repeat(100) + "</p></body></html>").toByteArray()
                    entry.name.endsWith("nav.xhtml") -> source.getInputStream(entry).use { it.readBytes() }
                        .toString(Charsets.UTF_8).replace("A quiet beginning", "Publisher chapter title ".repeat(3_000)).toByteArray()
                    else -> source.getInputStream(entry).use { it.readBytes() }
                }
                val target = ZipEntry(entry.name)
                if (entry.name == "mimetype") { target.method = ZipEntry.STORED; target.size = bytes.size.toLong(); target.crc = CRC32().apply { update(bytes) }.value }
                output.putNextEntry(target); output.write(bytes); output.closeEntry()
            }
        } }
        val document = ReaderDocument(PublicationRepository(context).open(file), PublicationFormat.EPUB, null)
        try {
            try { document.search("BUDGETMATCH") {}; fail("Large publisher titles must not bypass the retained search-result byte limit") }
            catch (failure: ReaderSearchException) {
                assertTrue(failure.displayMessage.contains("8 MiB"))
                assertTrue(failure.displayMessage.contains("more specific phrase"))
            }
            val unrelated = mutableListOf<ReaderSearchResult>()
            document.search("a phrase absent from this book") { unrelated += it }
            assertTrue("The same readable EPUB must still support an unrelated search", unrelated.isEmpty())
        } finally { document.releaseOwner() }
    }

    @Test fun unbrokenMatchContextFailsClearlyButUnrelatedSearchStillReturnsNoResults() = runBlocking {
        val original = fixture()
        val repeated = File(directory, "unbroken-context.epub")
        ZipFile(original).use { source -> ZipOutputStream(repeated.outputStream()).use { output ->
            source.entries().asSequence().forEach { entry ->
                val bytes = if (entry.name.endsWith("chapter1.xhtml"))
                    ("<html xmlns=\"http://www.w3.org/1999/xhtml\"><head><title>Long context</title></head><body><p>" +
                        "x".repeat(6_000) + " TARGETBOUNDARY " + "y".repeat(6_000) + "</p></body></html>").toByteArray()
                else source.getInputStream(entry).use { it.readBytes() }
                val target = ZipEntry(entry.name)
                if (entry.name == "mimetype") { target.method = ZipEntry.STORED; target.size = bytes.size.toLong(); target.crc = CRC32().apply { update(bytes) }.value }
                output.putNextEntry(target); output.write(bytes); output.closeEntry()
            }
        } }
        val document = ReaderDocument(PublicationRepository(context).open(repeated), PublicationFormat.EPUB, null)
        try {
            try { document.search("TARGETBOUNDARY") {}; fail("Unbounded context must fail before Readium constructs huge snippets") }
            catch (failure: ReaderSearchException) {
                assertTrue(failure.displayMessage.contains("unbroken context"))
                assertTrue(failure.displayMessage.contains("4096"))
            }
            val noMatches = mutableListOf<ReaderSearchResult>()
            document.search("this phrase is absent everywhere") { noMatches += it }
            assertTrue("An unrelated long word must not reject a query without matches", noMatches.isEmpty())
        } finally { document.releaseOwner() }
    }

    @Test fun fixedLayoutSearchNavigatesWithoutPretendingToSupportTextSelection() = runBlocking {
        val file = fixture("fixed-layout.epub")
        launch(file, insertBook(file))
        await { it.document?.currentLocation() != null }
        lateinit var document: ReaderDocument
        scenario!!.onActivity { document = requireNotNull(it.document); assertFalse(document.supportsTextSelection) }
        val results = mutableListOf<ReaderSearchResult>()
        document.search("LEAF-FXL-TWO") { results += it }
        val result = results.single()
        assertTrue(requireNotNull(ReaderDocument.parse(result.locatorJson)).href.toString().contains("chapter2"))
        var navigated = false
        scenario!!.onActivity { activity -> activity.lifecycleScope.launch { navigated = document.goSearchResult(result) } }
        await { navigated }
        val deadline = SystemClock.uptimeMillis() + 15_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (javascriptInAnyVisibleView("document.body.innerText.includes('LEAF-FXL-TWO')")) return@runBlocking
            SystemClock.sleep(75)
        }
        fail("The actual fixed-layout reader must display the search result's publisher page")
    }

    private suspend fun insertBook(file: File): String {
        val id = UUID.randomUUID().toString()
        val hash = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
        dependencies.books().add(Book(id, "Reader test $id", "Original Leaf fixtures", null, PublicationFormat.EPUB,
            file.absolutePath, null, null, file.length(), hash, System.currentTimeMillis()))
        bookId = id
        return id
    }
    private fun fixture(name: String = "standard.epub"): File = File(directory, name).also { file ->
        ZipInputStream(instrumentation.context.assets.open("publications/$name")).use { input ->
            ZipOutputStream(file.outputStream()).use { output ->
                // Unique archive bytes avoid colliding with any pre-existing test-library book.
                output.setComment("Leaf annotation test ${UUID.randomUUID()}")
                while (true) {
                    val source = input.nextEntry ?: break
                    val bytes = input.readBytes()
                    val entry = ZipEntry(source.name)
                    if (source.name == "mimetype") { entry.method = ZipEntry.STORED; entry.size = bytes.size.toLong(); entry.crc = CRC32().apply { update(bytes) }.value }
                    output.putNextEntry(entry); output.write(bytes); output.closeEntry(); input.closeEntry()
                }
            }
        }
    }
    private fun launch(file: File, id: String) { scenario = ActivityScenario.launch(Intent(context, ReaderHarnessActivity::class.java)
        .putExtra("fixturePath", file.absolutePath).putExtra("fixtureFormat", PublicationFormat.EPUB.name).putExtra("bookId", id)) }
    private fun await(condition: (ReaderHarnessActivity) -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 15_000
        while (SystemClock.uptimeMillis() < deadline) {
            var ready = false
            scenario!!.onActivity { ready = condition(it) }
            if (ready) return
            SystemClock.sleep(50)
        }
        scenario!!.onActivity { assertNull("Native reader reported an error", it.failure) }
        fail("The actual reader did not reach the expected annotation/search state")
    }
    private fun javascript(script: String): String {
        val latch = CountDownLatch(1)
        var result = "null"
        scenario!!.onActivity { activity ->
            val web = visibleWebView(activity.window.decorView)
            if (web == null) latch.countDown() else web.evaluateJavascript(script) { result = it; latch.countDown() }
        }
        assertTrue("Native WebView callback timed out", latch.await(3, TimeUnit.SECONDS))
        return result
    }
    private fun awaitJavascript(script: String, expected: String) {
        val deadline = SystemClock.uptimeMillis() + 15_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (javascript(script) == expected) return
            SystemClock.sleep(75)
        }
        assertEquals("The actual EPUB DOM must reflect the requested selection/decoration", expected, javascript(script))
    }
    private fun visibleWebView(view: View): WebView? {
        if (view is WebView && view.isShown && view.getGlobalVisibleRect(Rect())) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) visibleWebView(view.getChildAt(index))?.let { return it }
        return null
    }
    private fun javascriptInAnyVisibleView(script: String): Boolean {
        val latch = CountDownLatch(1)
        var found = false
        scenario!!.onActivity { activity ->
            fun visible(view: View): List<WebView> = when {
                view is WebView && view.isShown && view.getGlobalVisibleRect(Rect()) -> listOf(view)
                view is ViewGroup -> (0 until view.childCount).flatMap { visible(view.getChildAt(it)) }
                else -> emptyList()
            }
            val views = visible(activity.window.decorView)
            var pending = views.size
            if (views.isEmpty()) latch.countDown()
            views.forEach { web -> web.evaluateJavascript(script) {
                found = found || it == "true"
                pending--
                if (pending == 0) latch.countDown()
            } }
        }
        assertTrue("Native fixed-layout WebView callback timed out", latch.await(3, TimeUnit.SECONDS))
        return found
    }
    private fun visibleColor(rgb: String): String =
        "Array.from(document.querySelectorAll('[class*=\"r2-highlight-\"]')).some(e=>{" +
            "const v=window.visualViewport,l=v?v.offsetLeft:0,t=v?v.offsetTop:0,w=v?v.width:innerWidth,h=v?v.height:innerHeight,s=getComputedStyle(e);" +
            "return s.visibility!=='hidden'&&Number(s.opacity)>0&&s.backgroundColor.replace(/\\s/g,'').includes('$rgb')&&" +
            "Array.from(e.getClientRects()).some(r=>r.width>0&&r.height>0&&r.right>l&&r.left<l+w&&r.bottom>t&&r.top<t+h);})"

    private fun longPressSilver() {
        val rendered = CountDownLatch(1)
        scenario!!.onActivity { activity ->
            requireNotNull(visibleWebView(activity.window.decorView)).postVisualStateCallback(
                0L,
                object : WebView.VisualStateCallback() {
                    override fun onComplete(requestId: Long) { rendered.countDown() }
                },
            )
        }
        assertTrue("The native EPUB frame must be rendered before selecting", rendered.await(5, TimeUnit.SECONDS))
        val point = JSONObject(javascript("""
            (()=>{
                const node=document.querySelector('#passage').firstChild;
                const range=document.createRange();range.setStart(node,2);range.setEnd(node,8);
                const rect=range.getBoundingClientRect(), viewport=window.visualViewport;
                return {x:rect.left+rect.width/2,y:rect.top+rect.height/2,
                    width:viewport?viewport.width:window.innerWidth,
                    left:viewport?viewport.offsetLeft:0,top:viewport?viewport.offsetTop:0};
            })()
        """.trimIndent()))
        var x = 0f; var y = 0f
        scenario!!.onActivity { activity ->
            val web = requireNotNull(visibleWebView(activity.window.decorView))
            val location = IntArray(2); web.getLocationOnScreen(location)
            // Readium enables overview/zoom; CSS pixels can have a different scale from DPR.
            val scale = web.width / point.getDouble("width")
            x = location[0] + ((point.getDouble("x") - point.getDouble("left")) * scale).toFloat()
            y = location[1] + ((point.getDouble("y") - point.getDouble("top")) * scale).toFloat()
            val bounds = Rect()
            assertTrue(web.getGlobalVisibleRect(bounds))
            assertTrue("Selection must target visible EPUB text: point=($x,$y), bounds=$bounds, viewport=$point",
                bounds.contains(x.toInt(), y.toInt()))
        }
        val start = SystemClock.uptimeMillis()
        MotionEvent.obtain(start, start, MotionEvent.ACTION_DOWN, x, y, 0).apply {
            source = InputDevice.SOURCE_TOUCHSCREEN
        }.also {
            assertTrue("Failed to inject pointer DOWN event", instrumentation.uiAutomation.injectInputEvent(it, true))
            it.recycle()
        }
        SystemClock.sleep(1_100)
        MotionEvent.obtain(start, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x, y, 0).apply {
            source = InputDevice.SOURCE_TOUCHSCREEN
        }.also {
            assertTrue("Failed to inject pointer UP event", instrumentation.uiAutomation.injectInputEvent(it, true))
            it.recycle()
        }
        instrumentation.waitForIdleSync()
    }

    private fun shell(command: String) {
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command))
            .use { it.readBytes() }
    }
}
