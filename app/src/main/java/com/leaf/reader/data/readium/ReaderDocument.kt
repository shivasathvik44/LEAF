package com.leaf.reader.data.readium

import androidx.fragment.app.Fragment
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import com.leaf.reader.core.util.PublicationFormat
import com.leaf.reader.domain.model.ReaderPreferences
import com.leaf.reader.domain.model.AnnotationLimits
import com.leaf.reader.domain.model.Highlight
import com.leaf.reader.domain.model.ReaderTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONObject
import org.readium.adapter.pdfium.navigator.PdfiumEngineProvider
import org.readium.adapter.pdfium.navigator.PdfiumNavigatorFragment
import org.readium.adapter.pdfium.navigator.PdfiumPreferences
import org.readium.r2.navigator.Navigator
import org.readium.r2.navigator.OverflowableNavigator
import org.readium.r2.navigator.VisualNavigator
import org.readium.r2.navigator.DecorableNavigator
import org.readium.r2.navigator.Decoration
import org.readium.r2.navigator.SelectableNavigator
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
import org.readium.r2.shared.publication.services.search.search
import org.readium.r2.shared.util.ThrowableError
import org.readium.r2.shared.util.getOrElse
import org.readium.r2.shared.util.Url
import org.readium.r2.shared.util.AbsoluteUrl
import org.readium.r2.shared.util.data.ReadError

data class ReaderLocation(val locatorJson: String, val progression: Double, val title: String?, val position: Int?)
data class ReaderChapter(val id: Int, val title: String, val depth: Int)
data class ReaderSelection(val locatorJson: String, val selectedText: String)
data class ReaderSearchResult(val id: Int, val locatorJson: String, val title: String?, val before: String, val match: String, val after: String)
class ReaderSearchException(val displayMessage: String) : Exception(displayMessage)

/** The only layer exposing a publication to its native navigators. UI handles opaque locations. */
@OptIn(ExperimentalReadiumApi::class)
class ReaderDocument(
    private val publication: Publication,
    val format: PublicationFormat,
    initialLocatorJson: String?,
) {
    val supportsTypography: Boolean = format == PublicationFormat.EPUB && publication.metadata.layout != Layout.FIXED
    val supportsTextSelection: Boolean = supportsTypography
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
    private var selectionScope: CoroutineScope? = null
    private val _selection = MutableStateFlow<ReaderSelection?>(null)
    val selection: StateFlow<ReaderSelection?> = _selection
    private val _activatedHighlight = MutableStateFlow<String?>(null)
    val activatedHighlight: StateFlow<String?> = _activatedHighlight
    private var savedHighlights: List<Highlight> = emptyList()
    private val decorationMutex = Mutex()
    private data class NativePageEvent(val generation: Long, val revision: Long, val locator: Locator)
    private var navigatorGeneration = 0L
    private var nativePageRevision = 0L
    private val nativePageEvents = MutableStateFlow<NativePageEvent?>(null)
    private val highlightListener = object : DecorableNavigator.Listener {
        override fun onDecorationActivated(event: DecorableNavigator.OnActivatedEvent): Boolean {
            if (ownerReleased || closed) return false
            _activatedHighlight.value = event.decoration.id
            return true
        }
    }

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
        val generation = ++navigatorGeneration
        val fragment = when (format) {
            PublicationFormat.EPUB -> EpubNavigatorFactory(publication).createFragmentFactory(
                initialLocator = latestLocator,
                initialPreferences = preferences.epub(),
                configuration = EpubNavigatorFragment.Configuration(
                    selectionActionModeCallback = if (supportsTextSelection) selectionMenu(onError) else null,
                ),
                listener = object : EpubNavigatorFragment.Listener {
                    override fun onExternalLinkActivated(url: AbsoluteUrl) {
                        onError("External links are unavailable in offline reading.")
                    }
                    override fun onResourceLoadFailed(href: Url, error: ReadError) {
                        onError("This part of the book could not be displayed. Try another chapter or import a fresh copy.")
                    }
                },
                paginationListener = object : EpubNavigatorFragment.PaginationListener {
                    override fun onPageChanged(pageIndex: Int, totalPages: Int, locator: Locator) {
                        if (generation != navigatorGeneration || ownerReleased) return
                        nativePageEvents.value = NativePageEvent(generation, ++nativePageRevision, locator)
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

    internal fun attach(navigator: Navigator, viewScope: CoroutineScope) {
        if (this.navigator !== navigator) {
            (this.navigator as? DecorableNavigator)?.removeDecorationListener(highlightListener)
            if (format == PublicationFormat.EPUB) (navigator as? DecorableNavigator)?.addDecorationListener(HIGHLIGHTS_GROUP, highlightListener)
        }
        this.navigator = navigator
        selectionScope = viewScope
    }
    internal fun detach(navigator: Navigator) {
        if (this.navigator === navigator) {
            (navigator as? DecorableNavigator)?.removeDecorationListener(highlightListener)
            this.navigator = null
            selectionScope = null
        }
    }
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
    suspend fun goChapter(id: Int): Boolean {
        if (!retainOperation()) return false
        try {
            val link = links.getOrNull(id) ?: return false
            val locator = publication.locatorFromLink(link) ?: return false
            return goToLocator(locator)
        } finally { releaseOperation() }
    }
    suspend fun go(locatorJson: String): Boolean {
        val locator = parse(locatorJson) ?: return false
        if (!retainOperation()) return false
        try { return goToLocator(locator) }
        finally { releaseOperation() }
    }
    suspend fun goProgression(value: Double): Boolean {
        if (!value.isFinite() || !retainOperation()) return false
        try {
            val locator = publication.locateProgression(value.coerceIn(0.0, 1.0)) ?: return false
            currentCoroutineContext().ensureActive()
            return goToLocator(locator)
        } finally { releaseOperation() }
    }

    /** Caller holds an operation lease while the native target resource is selected. */
    private suspend fun goToLocator(locator: Locator): Boolean {
        val target = navigator ?: return false
        if (ownerReleased) return false
        fun resourceHref(href: Url): Url? = Url(href.toString().removePrefix("/")).let { it?.removeFragment() }
        val href = resourceHref(locator.href) ?: return false
        if (publication.readingOrder.none { resourceHref(it.url())?.isEquivalent(href) == true }) return false
        val generation = navigatorGeneration
        val revision = nativePageRevision
        currentCoroutineContext().ensureActive()
        if (!target.go(locator, animated = false)) return false
        if (supportsTypography && target is EpubNavigatorFragment) {
            val selected = withTimeoutOrNull(10_000L) {
                nativePageEvents.first {
                    it != null && it.generation == generation && it.revision > revision &&
                        resourceHref(it.locator.href)?.isEquivalent(href) == true
                }
            } ?: return false
            currentCoroutineContext().ensureActive()
            if (ownerReleased || navigator !== target || navigatorGeneration != selected.generation) return false
            // Readium 3.4's resource-page listener can move a backward jump to the
            // chapter's final page after the first go(). Wait for its fresh loaded-page
            // notification, then reapply the full locator without a resource change.
            // Unlike currentLocator, the revision also signals rapid A -> B -> A jumps
            // whose final locator equals the previous StateFlow value.
            return target.go(locator, animated = false)
        }
        return true
    }
    fun currentLocation(): ReaderLocation? {
        // Snapshot the navigator synchronously before a close/reopen can outrun Flow collection.
        navigator?.currentLocator?.value?.let(::accept)
        return _location.value
    }

    fun clearSelectionRequest() { _selection.value = null }
    fun clearHighlightActivation() { _activatedHighlight.value = null }

    private fun selectionMenu(onError: (String) -> Unit) = object : ActionMode.Callback {
        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
            menu.add(Menu.NONE, HIGHLIGHT_ACTION, Menu.NONE, "Highlight").setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
            return true
        }
        override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean = false
        override fun onDestroyActionMode(mode: ActionMode) = Unit
        override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
            if (item.itemId != HIGHLIGHT_ACTION) return false
            val scope = selectionScope
            if (scope == null) { mode.finish(); return true }
            scope.launch {
                if (!retainOperation()) { mode.finish(); return@launch }
                try {
                    val selectable = navigator as? SelectableNavigator
                    val selected = selectable?.currentSelection()?.locator
                    currentCoroutineContext().ensureActive()
                    if (ownerReleased || navigator !== selectable) return@launch
                    val text = selected?.text?.highlight
                    when {
                        selected == null || text.isNullOrBlank() -> onError("Select some book text before highlighting.")
                        !AnnotationLimits.fitsText(text) -> onError("This passage is too long to highlight. Choose a shorter selection (up to 64 KiB).")
                        else -> {
                            val json = selected.toJSON().toString()
                            if (!AnnotationLimits.fitsLocator(json)) onError("This saved location is too large. Choose a shorter passage.")
                            else _selection.value = ReaderSelection(json, text)
                        }
                    }
                    selectable?.clearSelection()
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { if (!ownerReleased) onError("The selected passage could not be read. Select the text again.") }
                finally { releaseOperation(); mode.finish() }
            }
            return true
        }
    }

    suspend fun applyHighlights(highlights: List<Highlight>) {
        if (format != PublicationFormat.EPUB) return
        savedHighlights = highlights.toList()
        restoreHighlights()
    }
    internal suspend fun restoreHighlights() {
        if (format != PublicationFormat.EPUB || !retainOperation()) return
        try {
            decorationMutex.withLock {
                val decorable = navigator as? DecorableNavigator ?: return@withLock
                if (!decorable.supportsDecorationStyle(Decoration.Style.Highlight::class)) return@withLock
                val decorations = savedHighlights.mapNotNull { highlight ->
                    parse(highlight.locatorJson)?.let { Decoration(highlight.id, it, Decoration.Style.Highlight(highlight.color.toInt())) }
                }
                decorable.applyDecorations(decorations, HIGHLIGHTS_GROUP)
            }
        } finally { releaseOperation() }
    }

    suspend fun search(query: String, onPage: (List<ReaderSearchResult>) -> Unit) {
        if (format != PublicationFormat.EPUB) throw ReaderSearchException("Search is available for EPUB books.")
        val term = query.trim()
        if (term.isEmpty()) return
        if (term.length > MAX_SEARCH_QUERY_CHARACTERS) throw ReaderSearchException("Use a shorter search phrase (up to 512 characters).")
        if (!retainOperation()) throw ReaderSearchException("Return to the reader before searching this book.")
        try {
            withContext(Dispatchers.Default) {
                val iterator = publication.search(term) ?: throw ReaderSearchException("This EPUB does not provide searchable text.")
                try {
                    var count = 0
                    var retainedBytes = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val collection = iterator.next().getOrElse { error ->
                            var cause: org.readium.r2.shared.util.Error? = error
                            var limited = false
                            var contextLimited = false
                            repeat(32) {
                                (cause as? ThrowableError<*>)?.throwable?.let {
                                    if (it is SearchMatchLimitException) limited = true
                                    if (it is SearchContextLimitException) contextLimited = true
                                }
                                cause = cause?.cause
                            }
                            throw ReaderSearchException(when {
                                contextLimited -> "This match has an unbroken context segment longer than 4096 characters and cannot be searched safely. Reading this book is still available."
                                limited -> "This query has too many matches in one chapter. Use a more specific phrase."
                                else -> "This book could not be searched. Try another phrase or a fresh copy."
                            })
                        } ?: break
                        currentCoroutineContext().ensureActive()
                        if (collection.locators.size > MAX_SEARCH_RESULTS - count) throw ReaderSearchException("More than $MAX_SEARCH_RESULTS matches were found. Use a more specific phrase.")
                        val results = collection.locators.map { locator ->
                            val result = ReaderSearchResult(count, locator.toJSON().toString(), locator.title,
                                locator.text.before.orEmpty(), locator.text.highlight.orEmpty(), locator.text.after.orEmpty())
                            // The UI retains the full locator and its display context. Count both
                            // copies, including publisher chapter titles, across every result page.
                            val bytes = listOf(result.locatorJson, result.title, result.before, result.match, result.after)
                                .sumOf { it?.toByteArray(Charsets.UTF_8)?.size?.toLong() ?: 0L }
                            if (bytes > MAX_SEARCH_RESULT_BYTES - retainedBytes) {
                                throw ReaderSearchException("These search results contain too much text (over 8 MiB). Use a more specific phrase.")
                            }
                            retainedBytes += bytes
                            count++
                            result
                        }
                        onPage(results)
                    }
                } finally { iterator.close() }
            }
        } finally { releaseOperation() }
    }

    suspend fun clearSearchDecorations() {
        if (!supportsTextSelection || !retainOperation()) return
        try {
            decorationMutex.withLock { (navigator as? DecorableNavigator)?.applyDecorations(emptyList(), SEARCH_GROUP) }
        } finally { releaseOperation() }
    }

    suspend fun goSearchResult(result: ReaderSearchResult): Boolean {
        if (format != PublicationFormat.EPUB || !retainOperation()) return false
        try {
            val locator = parse(result.locatorJson) ?: return false
            currentCoroutineContext().ensureActive()
            val target = navigator ?: return false
            if (!goToLocator(locator)) return false
            decorationMutex.withLock {
                currentCoroutineContext().ensureActive()
                val decorable = target as? DecorableNavigator
                if (supportsTextSelection && navigator === target && !ownerReleased && decorable?.supportsDecorationStyle(Decoration.Style.Highlight::class) == true) {
                    decorable.applyDecorations(listOf(Decoration("match-${result.id}", locator, Decoration.Style.Highlight(0xFF8EC9DE.toInt()))), SEARCH_GROUP)
                }
            }
            return true
        } finally { releaseOperation() }
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
        private const val HIGHLIGHT_ACTION = 0x4C454146
        private const val HIGHLIGHTS_GROUP = "leaf-highlights"
        private const val SEARCH_GROUP = "leaf-search"
        private const val MAX_SEARCH_RESULTS = 5_000
        private const val MAX_SEARCH_RESULT_BYTES = 8L * 1_024 * 1_024
        private const val MAX_SEARCH_QUERY_CHARACTERS = 512
        fun parse(json: String?): Locator? = try { json?.let { Locator.fromJSON(JSONObject(it)) } } catch (_: Exception) { null }
    }
}
