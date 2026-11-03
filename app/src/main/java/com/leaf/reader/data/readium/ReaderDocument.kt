package com.leaf.reader.data.readium

import androidx.fragment.app.Fragment
import com.leaf.reader.core.util.PublicationFormat
import com.leaf.reader.domain.model.ReaderPreferences
import com.leaf.reader.domain.model.ReaderTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONObject
import org.readium.adapter.pdfium.navigator.PdfiumEngineProvider
import org.readium.adapter.pdfium.navigator.PdfiumNavigatorFragment
import org.readium.adapter.pdfium.navigator.PdfiumPreferences
import org.readium.r2.navigator.Navigator
import org.readium.r2.navigator.OverflowableNavigator
import org.readium.r2.navigator.VisualNavigator
import org.readium.r2.navigator.epub.EpubNavigatorFactory
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.navigator.epub.EpubPreferences
import org.readium.r2.navigator.input.InputListener
import org.readium.r2.navigator.input.TapEvent
import org.readium.r2.navigator.input.DragEvent
import org.readium.r2.navigator.pdf.PdfNavigatorFactory
import org.readium.r2.navigator.pdf.PdfNavigatorFragment
import org.readium.r2.navigator.preferences.Axis
import org.readium.r2.navigator.preferences.Fit
import org.readium.r2.navigator.preferences.FontFamily
import org.readium.r2.navigator.preferences.Theme
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.Layout
import org.readium.r2.shared.publication.services.locateProgression
import org.readium.r2.shared.util.Url
import org.readium.r2.shared.util.AbsoluteUrl
import org.readium.r2.shared.util.data.ReadError

data class ReaderLocation(val locatorJson: String, val progression: Double, val title: String?, val position: Int?)
data class ReaderChapter(val id: Int, val title: String, val depth: Int)

/** The only layer exposing a publication to its native navigators. UI handles opaque locations. */
@OptIn(ExperimentalReadiumApi::class)
class ReaderDocument(
    private val publication: Publication,
    val format: PublicationFormat,
    initialLocatorJson: String?,
) {
    val supportsTypography: Boolean = format == PublicationFormat.EPUB && publication.metadata.layout != Layout.FIXED
    val pageCount: Int? = publication.metadata.numberOfPages?.takeIf { it > 0 }
    private val links = mutableListOf<Link>()
    val chapters: List<ReaderChapter> = buildList {
        // Keep unusually deep tables of contents off the Kotlin/Java call stack.
        val pending = ArrayDeque<Pair<Link, Int>>()
        publication.tableOfContents.ifEmpty { publication.readingOrder }.asReversed().forEach { pending.addLast(it to 0) }
        while (pending.isNotEmpty()) {
            val (link, depth) = pending.removeLast()
            val id = links.size
            links += link
            add(ReaderChapter(id, link.title?.takeIf { it.isNotBlank() } ?: "Chapter ${id + 1}", depth))
            link.children.asReversed().forEach { pending.addLast(it to depth + 1) }
        }
    }
    private val _location = MutableStateFlow<ReaderLocation?>(null)
    val location: StateFlow<ReaderLocation?> = _location
    private var latestLocator: Locator? = parse(initialLocatorJson)?.takeIf { saved ->
        publication.readingOrder.any { it.href.toString().substringBefore('#') == saved.href.toString().substringBefore('#') }
    }
    private var navigator: Navigator? = null
    private var ownerReleased = false
    private var hostReferences = 0
    private var operationReferences = 0
    private var closed = false

    @Synchronized internal fun retainHost() { check(!closed); hostReferences++ }
    @Synchronized internal fun releaseHost() { check(hostReferences > 0); hostReferences--; closeWhenUnused() }
    @Synchronized fun releaseOwner() { if (!ownerReleased) { ownerReleased = true; closeWhenUnused() } }
    @Synchronized private fun retainOperation(): Boolean {
        if (closed || ownerReleased) return false
        operationReferences++
        return true
    }
    @Synchronized private fun releaseOperation() { operationReferences--; closeWhenUnused() }
    private fun closeWhenUnused() {
        if (ownerReleased && hostReferences == 0 && operationReferences == 0 && !closed) {
            closed = true
            try { publication.close() } catch (_: Exception) { /* Native/ZIP ownership is already relinquished. */ }
        }
    }

    internal fun createNavigator(
        classLoader: ClassLoader,
        preferences: ReaderPreferences,
        onTap: () -> Unit,
        onInteraction: () -> Unit,
        onError: (String) -> Unit,
    ): Fragment {
        check(!closed)
        val fragment = when (format) {
            PublicationFormat.EPUB -> EpubNavigatorFactory(publication).createFragmentFactory(
                initialLocator = latestLocator,
                initialPreferences = preferences.epub(),
                listener = object : EpubNavigatorFragment.Listener {
                    override fun onExternalLinkActivated(url: AbsoluteUrl) {
                        onError("External links are unavailable in offline reading.")
                    }
                    override fun onResourceLoadFailed(href: Url, error: ReadError) {
                        onError("This part of the book could not be displayed. Try another chapter or import a fresh copy.")
                    }
                },
            ).instantiate(classLoader, EpubNavigatorFragment::class.java.name)
            PublicationFormat.PDF -> PdfNavigatorFactory(publication, PdfiumEngineProvider()).createFragmentFactory(
                initialLocator = latestLocator,
                initialPreferences = preferences.pdf(),
                listener = object : PdfNavigatorFragment.Listener {
                    override fun onResourceLoadFailed(href: Url, error: ReadError) {
                        onError("This PDF page could not be displayed. Try another page or import a fresh copy.")
                    }
                },
            ).instantiate(classLoader, PdfNavigatorFragment::class.java.name)
        }
        (fragment as VisualNavigator).addInputListener(object : InputListener {
            override fun onTap(event: TapEvent): Boolean { onInteraction(); onTap(); return true }
            override fun onDrag(event: DragEvent): Boolean { onInteraction(); return false }
        })
        return fragment
    }

    internal fun attach(navigator: Navigator) { this.navigator = navigator }
    internal fun detach(navigator: Navigator) { if (this.navigator === navigator) this.navigator = null }
    internal fun accept(locator: Locator) {
        latestLocator = locator
        val progress = locator.locations.totalProgression?.takeIf(Double::isFinite)?.coerceIn(0.0, 1.0) ?: 0.0
        _location.value = ReaderLocation(locator.toJSON().toString(), progress, locator.title, locator.locations.position)
    }
    internal fun submitPreferences(navigator: Navigator, preferences: ReaderPreferences) {
        when (navigator) {
            is EpubNavigatorFragment -> navigator.submitPreferences(preferences.epub())
            is PdfNavigatorFragment<*, *> -> {
                @Suppress("UNCHECKED_CAST")
                (navigator as PdfiumNavigatorFragment).submitPreferences(preferences.pdf())
            }
        }
    }
    fun goForward(): Boolean = (navigator as? OverflowableNavigator)?.goForward(animated = false) ?: false
    fun goBackward(): Boolean = (navigator as? OverflowableNavigator)?.goBackward(animated = false) ?: false
    fun goChapter(id: Int): Boolean = links.getOrNull(id)?.let { navigator?.go(it, animated = false) } ?: false
    fun go(locatorJson: String): Boolean = parse(locatorJson)?.let { navigator?.go(it, animated = false) } ?: false
    suspend fun goProgression(value: Double): Boolean {
        if (!value.isFinite() || !retainOperation()) return false
        try {
            val locator = publication.locateProgression(value.coerceIn(0.0, 1.0)) ?: return false
            currentCoroutineContext().ensureActive()
            return navigator?.go(locator, animated = false) ?: false
        } finally { releaseOperation() }
    }
    fun currentLocation(): ReaderLocation? {
        // Snapshot the navigator synchronously before a close/reopen can outrun Flow collection.
        navigator?.currentLocator?.value?.let(::accept)
        return _location.value
    }

    private fun ReaderPreferences.epub() = EpubPreferences(
        fontSize = if (supportsTypography) fontSize / 100.0 else null,
        fontFamily = if (supportsTypography) FontFamily(fontFamily) else null,
        lineHeight = if (supportsTypography) lineSpacing else null,
        pageMargins = if (supportsTypography) margins else null,
        publisherStyles = !supportsTypography,
        scroll = supportsTypography && scroll,
        theme = when (theme) { ReaderTheme.LIGHT -> Theme.LIGHT; ReaderTheme.DARK -> Theme.DARK; ReaderTheme.SEPIA -> Theme.SEPIA },
    )
    private fun ReaderPreferences.pdf() = PdfiumPreferences(
        fit = Fit.WIDTH, scroll = scroll, scrollAxis = Axis.VERTICAL,
    )

    companion object {
        fun parse(json: String?): Locator? = try { json?.let { Locator.fromJSON(JSONObject(it)) } } catch (_: Exception) { null }
    }
}
