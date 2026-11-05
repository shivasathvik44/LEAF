package com.leaf.reader.reader

import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.lifecycle.lifecycleScope
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.leaf.reader.core.util.PublicationFormat
import com.leaf.reader.data.readium.PublicationRepository
import com.leaf.reader.data.readium.ReaderDocument
import com.leaf.reader.data.readium.ReaderHarnessActivity
import com.leaf.reader.domain.model.ReaderPreferences
import com.leaf.reader.domain.model.ReaderTheme
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.publication.services.positions

/** Real parsers, WebViews and PDFium; no app library data is created or cleared. */
@OptIn(ExperimentalReadiumApi::class)
@RunWith(AndroidJUnit4::class)
class ReaderEngineTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private lateinit var directory: File
    private var scenario: ActivityScenario<ReaderHarnessActivity>? = null

    @Before fun setUp() { directory = File(context.cacheDir, "reader-engine-tests/${UUID.randomUUID()}").apply { check(mkdirs()) } }
    @After fun tearDown() { scenario?.close(); if (this::directory.isInitialized) directory.deleteRecursively() }

    @Test fun fullLocatorRoundTripPreservesTextFragmentsAndProgress() = runBlocking {
        val publication = PublicationRepository(context).open(fixture("standard.epub"))
        try {
            val source = publication.positions().last().toJSON().apply {
                put("text", org.json.JSONObject().put("before", "A ").put("highlight", "silver fern").put("after", " remembers the light."))
            }.toString()
            val restored = requireNotNull(ReaderDocument.parse(ReaderDocument.parse(source)?.toJSON()?.toString()))
            assertEquals("silver fern", restored.text.highlight)
            assertEquals("A ", restored.text.before)
            assertNotNull(restored.locations.totalProgression)
            assertEquals(ReaderDocument.parse(source), restored)
        } finally { publication.close() }
    }

    @Test fun publicationParserRecognizesRealEpubVariantsAndPdfPages() = runBlocking {
        val repository = PublicationRepository(context)
        for (name in listOf("standard.epub", "image-heavy.epub", "rtl.epub", "fixed-layout.epub")) {
            val publication = repository.open(fixture(name))
            try { assertTrue("$name must have readable content", publication.readingOrder.isNotEmpty()); assertTrue(publication.positions().isNotEmpty()) }
            finally { publication.close() }
        }
        for ((name, pages) in listOf("ordinary.pdf" to 3, "scanned.pdf" to 2)) {
            val publication = repository.open(fixture(name))
            try { assertEquals(1, publication.readingOrder.size); assertEquals(pages, publication.positions().size) }
            finally { publication.close() }
        }
    }

    @Test fun epubNavigatorDisplaysPublicationTextAndRecreatesAtSavedChapter() {
        launch("standard.epub", PublicationFormat.EPUB)
        await { activity -> activity.document?.currentLocation() != null }
        awaitWebText("silver fern")
        goChapter(2)
        await { activity -> activity.document?.currentLocation()?.locatorJson?.contains("chapter3") == true }
        var before: String? = null
        scenario!!.onActivity { before = it.document!!.currentLocation()!!.locatorJson }
        scenario!!.recreate()
        await { activity -> activity.document?.currentLocation()?.locatorJson?.contains("chapter3") == true }
        awaitWebText("silver fern")
        scenario!!.onActivity { activity ->
            assertNull(activity.failure)
            val old = ReaderDocument.parse(before)!!
            val restored = ReaderDocument.parse(activity.document!!.currentLocation()!!.locatorJson)!!
            assertEquals(old.href, restored.href)
        }
    }

    @Test fun epubTypographyChangesKeepMeaningfulLocator() {
        launch("standard.epub", PublicationFormat.EPUB)
        await { it.document?.currentLocation() != null }
        goChapter(1)
        await { it.document?.currentLocation()?.locatorJson?.contains("chapter2") == true }
        val originalFontSize = webValue("parseFloat(getComputedStyle(document.body).fontSize)").toDouble()
        scenario!!.onActivity { it.typography(ReaderPreferences(theme = ReaderTheme.DARK, fontSize = 180.0,
            fontFamily = "monospace", lineSpacing = 2.0, margins = 1.8, scroll = true)) }
        awaitWebText("silver fern")
        awaitWebCondition("parseFloat(getComputedStyle(document.body).fontSize) > ${originalFontSize * 1.5} && getComputedStyle(document.body).fontFamily.toLowerCase().includes('monospace')")
        scenario!!.onActivity { activity ->
            val locator = requireNotNull(ReaderDocument.parse(activity.document!!.currentLocation()!!.locatorJson))
            assertTrue(locator.href.toString().contains("chapter2"))
            assertNull(activity.failure)
        }
    }

    @Test fun imageHeavyEpubActuallyDisplaysAndDecodesAllIllustrations() {
        launch("image-heavy.epub", PublicationFormat.EPUB)
        await { it.document?.currentLocation() != null }
        awaitWebText("Leaf illustration 6")
        awaitWebCondition("document.images.length === 6 && Array.from(document.images).every(image => image.complete && image.naturalWidth > 0)")
        scenario!!.onActivity { assertTrue(it.document!!.supportsTypography); assertNull(it.failure) }
    }

    @Test fun rtlEpubDisplaysArabicAndNavigatesToItsSecondChapter() {
        launch("rtl.epub", PublicationFormat.EPUB)
        await { it.document?.currentLocation() != null }
        awaitWebText("هذه حديقة هادئة")
        awaitWebCondition("getComputedStyle(document.documentElement).direction === 'rtl'")
        goChapter(1)
        awaitWebText("هذه هي الصفحة الأخيرة من القصة")
        scenario!!.onActivity { assertNull(it.failure) }
    }

    @Test fun fixedLayoutEpubRendersAndPreservesPublisherTypography() {
        launch("fixed-layout.epub", PublicationFormat.EPUB)
        await { it.document?.currentLocation() != null }
        awaitWebText("This page uses a 600 by 800 viewport")
        val originalSize = webValue("getComputedStyle(document.body).fontSize")
        scenario!!.onActivity { activity ->
            assertFalse(activity.document!!.supportsTypography)
            activity.typography(ReaderPreferences(fontSize = 250.0, fontFamily = "monospace", lineSpacing = 3.0, margins = 3.0, scroll = true))
        }
        awaitWebText("This page uses a 600 by 800 viewport")
        assertEquals(originalSize, webValue("getComputedStyle(document.body).fontSize"))
        goChapter(1)
        awaitWebText("LEAF-FXL-TWO")
        scenario!!.onActivity { assertNull(it.failure) }
    }

    @Test fun orientationChangeReopensTheSameMeaningfulChapter() {
        launch("standard.epub", PublicationFormat.EPUB)
        await { it.document?.currentLocation() != null }
        goChapter(1)
        await { it.document?.currentLocation()?.locatorJson?.contains("chapter2") == true }
        scenario!!.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        await { activity -> activity.window.decorView.width > activity.window.decorView.height && activity.document?.currentLocation()?.locatorJson?.contains("chapter2") == true }
        awaitWebText("silver fern")
        scenario!!.onActivity { assertNull(it.failure) }
    }

    @Test fun pdfNavigatorRendersNativeInkAndRestoresLastPage() {
        launch("ordinary.pdf", PublicationFormat.PDF)
        await { it.document?.currentLocation() != null }
        await { activity -> nativePdfView(activity.window.decorView)?.let { view ->
            (view.javaClass.getMethod("getPageCount").invoke(view) as Int) == 3
        } == true }
        await { activity -> nativePdfView(activity.window.decorView)?.let(::hasRenderedInk) == true }
        scenario!!.onActivity { activity -> assertTrue(activity.document!!.goForward()) }
        await { it.document?.currentLocation()?.position == 2 }
        scenario!!.onActivity { activity -> assertTrue(activity.document!!.goForward()) }
        await { it.document?.currentLocation()?.position == 3 }
        scenario!!.recreate()
        await { it.document?.currentLocation()?.position == 3 }
        scenario!!.onActivity { assertNull(it.failure) }
    }

    @Test fun scannedPdfDisplaysItsRasterArtworkAndRestoresSecondPage() {
        launch("scanned.pdf", PublicationFormat.PDF)
        await { activity -> activity.document?.currentLocation() != null && nativePdfView(activity.window.decorView)?.let { view ->
            (view.javaClass.getMethod("getPageCount").invoke(view) as Int) == 2
        } == true }
        await { activity -> nativePdfView(activity.window.decorView)?.let(::hasScannedArtwork) == true }
        scenario!!.onActivity { activity -> assertEquals(2, activity.document!!.pageCount); assertTrue(activity.document!!.goForward()) }
        await { it.document?.currentLocation()?.position == 2 }
        scenario!!.recreate()
        await { it.document?.currentLocation()?.position == 2 }
        await { activity -> nativePdfView(activity.window.decorView)?.let(::hasScannedArtwork) == true }
        scenario!!.onActivity { assertNull(it.failure) }
    }

    private fun fixture(name: String): File = File(directory, name).also { file ->
        instrumentation.context.assets.open("publications/$name").use { input -> file.outputStream().use(input::copyTo) }
    }
    private fun goChapter(id: Int) = runBlocking {
        val result = CompletableDeferred<Boolean>()
        scenario!!.onActivity { activity ->
            activity.lifecycleScope.launch {
                try { result.complete(activity.document!!.goChapter(id)) }
                catch (failure: Throwable) { result.completeExceptionally(failure) }
            }
        }
        assertTrue(withTimeout(15_000) { result.await() })
    }

    private fun launch(name: String, format: PublicationFormat) {
        scenario = ActivityScenario.launch(Intent(context, ReaderHarnessActivity::class.java)
            .putExtra("fixturePath", fixture(name).absolutePath).putExtra("fixtureFormat", format.name))
        await { it.document?.rendered?.value == true }
    }
    private fun await(timeout: Long = 15_000, condition: (ReaderHarnessActivity) -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeout
        while (SystemClock.uptimeMillis() < deadline) {
            var success = false
            scenario!!.onActivity { success = condition(it) }
            if (success) return
            SystemClock.sleep(50)
        }
        scenario!!.onActivity { assertNull("Native reader reported an error", it.failure) }
        fail("The real native reader did not reach the expected state within $timeout ms")
    }
    private fun awaitWebText(text: String) {
        awaitWebCondition("document.body.innerText.toLowerCase().includes(${org.json.JSONObject.quote(text.lowercase())})")
    }
    private fun awaitWebCondition(expression: String) {
        val deadline = SystemClock.uptimeMillis() + 15_000
        while (SystemClock.uptimeMillis() < deadline) {
            val latch = CountDownLatch(1)
            var found = false
            scenario!!.onActivity { activity ->
                val webViews = views(activity.window.decorView).filterIsInstance<WebView>().filter { it.isShown && it.getGlobalVisibleRect(Rect()) }
                if (webViews.isEmpty()) latch.countDown() else {
                    var pending = webViews.size
                    webViews.forEach { view -> view.evaluateJavascript(expression) {
                        found = found || it == "true"
                        pending--
                        if (pending == 0) latch.countDown()
                    } }
                }
            }
            assertTrue("WebView JavaScript callback timed out", latch.await(3, TimeUnit.SECONDS))
            if (found) return
            SystemClock.sleep(100)
        }
        fail("Actual EPUB WebView did not reach the expected fixture/rendering state: $expression")
    }
    private fun webValue(expression: String): String {
        val latch = CountDownLatch(1)
        var result: String? = null
        scenario!!.onActivity { activity ->
            val web = views(activity.window.decorView).filterIsInstance<WebView>().firstOrNull { it.isShown && it.getGlobalVisibleRect(Rect()) }
            assertNotNull("A visible native EPUB WebView is required", web)
            web!!.evaluateJavascript(expression) { result = it; latch.countDown() }
        }
        assertTrue("WebView evaluation timed out", latch.await(3, TimeUnit.SECONDS))
        return requireNotNull(result)
    }
    private fun views(view: View): List<View> = listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
    private fun nativePdfView(view: View): View? = views(view).firstOrNull { it.javaClass.name == "com.github.barteksc.pdfviewer.PDFView" }
    private fun hasRenderedInk(view: View): Boolean {
        if (view.width <= 0 || view.height <= 0) return false
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        try {
            view.draw(Canvas(bitmap))
            var white = 0
            var ink = 0
            for (y in view.height / 4 until view.height * 3 / 4 step 3) for (x in view.width / 10 until view.width * 9 / 10 step 3) {
                val pixel = bitmap.getPixel(x, y)
                val r = pixel shr 16 and 255; val g = pixel shr 8 and 255; val b = pixel and 255
                if (r > 245 && g > 245 && b > 245) white++
                if (r < 100 && g < 100 && b < 100) ink++
            }
            return white > 1_000 && ink > 30
        } finally { bitmap.recycle() }
    }
    private fun hasScannedArtwork(view: View): Boolean {
        if (view.width <= 0 || view.height <= 0) return false
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        try {
            view.draw(Canvas(bitmap))
            var green = 0
            var warmPaper = 0
            for (y in view.height / 10 until view.height * 9 / 10 step 3) for (x in view.width / 10 until view.width * 9 / 10 step 3) {
                val pixel = bitmap.getPixel(x, y)
                val r = pixel shr 16 and 255; val g = pixel shr 8 and 255; val b = pixel and 255
                if (g > r + 20 && g > b + 20 && r < 150) green++
                if (r > 220 && g > 210 && b in 180..235) warmPaper++
            }
            return green > 30 && warmPaper > 1_000
        } finally { bitmap.recycle() }
    }
}
