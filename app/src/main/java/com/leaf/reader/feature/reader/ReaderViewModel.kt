package com.leaf.reader.feature.reader

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.leaf.reader.data.readium.PublicationAccessException
import com.leaf.reader.data.readium.PublicationRepository
import com.leaf.reader.data.readium.ReaderDocument
import com.leaf.reader.data.readium.ReaderLocation
import com.leaf.reader.data.readium.ReaderSearchException
import com.leaf.reader.data.readium.ReaderSearchResult
import com.leaf.reader.data.repository.AnnotationRepository
import com.leaf.reader.data.repository.BookRepository
import com.leaf.reader.data.repository.PreferencesRepository
import com.leaf.reader.data.repository.ReadingRepository
import com.leaf.reader.data.repository.ReadingActivityRecorder
import com.leaf.reader.data.repository.ReadingActivityRecorderFactory
import com.leaf.reader.domain.model.ReaderPreferences
import com.leaf.reader.domain.model.AnnotationLimits
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@HiltViewModel
class ReaderViewModel @Inject constructor(
    private val books: BookRepository,
    private val publications: PublicationRepository,
    private val reading: ReadingRepository,
    private val preferences: PreferencesRepository,
    private val annotations: AnnotationRepository,
    private val activityRecorders: ReadingActivityRecorderFactory,
) : ViewModel() {
    private val _state = MutableStateFlow(ReaderUiState())
    val state: StateFlow<ReaderUiState> = _state.asStateFlow()
    private var bookId: String? = null
    private var opening: Job? = null
    private var seeking: Job? = null
    private var highlightsJob: Job? = null
    private val observers = mutableListOf<Job>()
    private var writer: PositionWriter? = null
    private var writerBookId: String? = null
    private var latestPosition: Pair<String, ReaderLocation>? = null
    private var searchJob: Job? = null
    @Volatile private var searchGeneration = 0L
    private var activityRecorder: ReadingActivityRecorder? = null
    private var hasReadInForeground = false
    private var markedOpened = false

    fun initialize(id: String) {
        if (bookId == id && (opening?.isActive == true || _state.value.document != null)) return
        closeReader()
        bookId = id
        _state.value = ReaderUiState()
        opening = viewModelScope.launch {
            var opened: ReaderDocument? = null
            try {
                val book = books.get(id) ?: error("missing")
                val saved = reading.getProgress(id)
                val publication = publications.open(File(book.storagePath))
                opened = ReaderDocument(publication, book.format, latestPosition?.takeIf { it.first == id }?.second?.locatorJson ?: saved?.locatorJson)
                val document = opened
                activityRecorder = activityRecorders.create(id, viewModelScope)
                if (writer == null || writerBookId != id) {
                    writer?.close()
                    writerBookId = id
                    writer = PositionWriter(id, reading) { message("Your reading position could not be saved. Free some storage and try again.") }
                }
                _state.update { it.copy(loading = false, title = book.title, format = book.format, document = document, chapters = document.chapters) }
                observe { preferences.readerPrefs(id).collect { value -> _state.update { it.copy(preferences = value) } } }
                observe { annotations.bookmarks(id).collect { value -> _state.update { it.copy(bookmarks = value) } } }
                observeHighlights(id, document)
                observe { document.selection.collect { value -> if (_state.value.document === document) _state.update { it.copy(selection = value, annotationError = null) } } }
                observe { document.activatedHighlight.collect { value -> if (_state.value.document === document) _state.update { it.copy(editingHighlight = value, annotationError = null) } } }
                observe { document.location.collect { location ->
                    if (location != null) {
                        _state.update { it.copy(location = location) }
                        if (hasReadInForeground && document.rendered.value) writer?.offer(location)
                    }
                } }
                observe { document.rendered.collect { ready ->
                    if (_state.value.document === document) {
                        if (!ready) activityRecorder?.foregroundEligible(false)
                        _state.update { it.copy(contentReady = ready) }
                    }
                } }
                val recorder = activityRecorder
                if (recorder != null) observe { recorder.error.collect { error ->
                    _state.update { if (it.document === document) it.copy(activityError = error) else it }
                } }
                opened = null // Ownership is now held by state until closeReader/onCleared.
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                _state.update { it.copy(loading = false, error = if (failure is PublicationAccessException) failure.message else "This imported book could not be opened. Import a fresh copy or try again.") }
            } finally { opened?.releaseOwner() }
        }
    }
    fun retry() { val id = bookId ?: return; closeReader(); initialize(id) }
    fun foregroundEligible(value: Boolean) {
        val document = _state.value.document
        val eligible = value && document?.rendered?.value == true && _state.value.error == null
        activityRecorder?.foregroundEligible(eligible)
        if (eligible) {
            hasReadInForeground = true
            document?.currentLocation()?.let { writer?.offer(it) }
            if (!markedOpened) {
                val id = bookId ?: return
                markedOpened = true
                viewModelScope.launch {
                    try { books.markOpened(id) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) {
                        if (_state.value.document === document) {
                            markedOpened = false
                            message("This book’s reading status could not be saved. Please try again.")
                        }
                    }
                }
            }
        }
    }
    fun interact() { activityRecorder?.interact() }
    fun toggleControls() { _state.update { it.copy(controlsVisible = !it.controlsVisible) } }
    fun showControls() { _state.update { it.copy(controlsVisible = true) } }
    fun message(value: String?) { _state.update { it.copy(message = value) } }
    fun updatePreferences(value: ReaderPreferences) { val id = bookId ?: return; launchSaved { preferences.saveReaderPrefs(id, value) } }
    fun resetPreferences() { val id = bookId ?: return; launchSaved { preferences.resetReaderPrefs(id) } }
    fun addBookmark(label: String) {
        val id = bookId ?: return
        val location = _state.value.location ?: return
        launchSaved { annotations.addBookmark(id, location.locatorJson, label.trim().takeIf(String::isNotBlank)); message("Bookmark saved") }
    }
    fun removeBookmark(id: String) { launchSaved { annotations.removeBookmark(id) } }
    fun retryHighlights() {
        val document = _state.value.document ?: return
        observeHighlights(bookId ?: return, document)
    }
    private fun observeHighlights(id: String, document: ReaderDocument) {
        highlightsJob?.cancel()
        _state.update { it.copy(highlightsLoading = true, highlightsError = null) }
        highlightsJob = viewModelScope.launch {
            try {
                annotations.highlights(id).collect { value ->
                    _state.update { if (it.document === document) it.copy(highlights = value, highlightsLoading = false, highlightsError = null) else it }
                    try { document.applyHighlights(value) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { _state.update { if (it.document === document) it.copy(highlightsError = "Saved highlights could not be displayed. Your annotations have been kept. Please retry.") else it } }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                _state.update { if (it.document === document) it.copy(highlightsLoading = false, highlightsError = "Highlights and notes could not be loaded. Your saved data has been kept.") else it }
            }
        }
    }
    fun dismissSelection() {
        if (_state.value.annotationSaving) return
        _state.value.document?.clearSelectionRequest()
        _state.update { it.copy(annotationError = null) }
    }
    fun saveSelection(color: Long, note: String) {
        val id = bookId ?: return
        val document = _state.value.document ?: return
        val selection = _state.value.selection ?: return
        if (_state.value.annotationSaving) return
        try { AnnotationLimits.validateNote(note) }
        catch (error: IllegalArgumentException) { _state.update { it.copy(annotationError = error.message) }; return }
        saveAnnotation(document) {
            annotations.addHighlight(id, selection.locatorJson, selection.selectedText, color, note)
            if (_state.value.document === document && _state.value.selection === selection) {
                document.clearSelectionRequest()
                message("Highlight saved")
            }
        }
    }
    fun editHighlight(id: String) { _state.update { it.copy(editingHighlight = id, annotationError = null) } }
    fun closeHighlightEditor() {
        if (_state.value.annotationSaving) return
        _state.value.document?.clearHighlightActivation()
        _state.update { it.copy(editingHighlight = null, annotationError = null) }
    }
    fun updateHighlight(id: String, color: Long, note: String) {
        val document = _state.value.document ?: return
        if (_state.value.annotationSaving) return
        try { AnnotationLimits.validateNote(note) }
        catch (error: IllegalArgumentException) { _state.update { it.copy(annotationError = error.message) }; return }
        saveAnnotation(document) {
            annotations.updateHighlight(id, color, note)
            if (_state.value.document === document && _state.value.editingHighlight == id) {
                document.clearHighlightActivation()
                _state.update { it.copy(editingHighlight = null) }
            }
        }
    }
    fun deleteHighlight(id: String) {
        val document = _state.value.document ?: return
        if (_state.value.annotationSaving) return
        saveAnnotation(document) {
            annotations.deleteHighlight(id)
            if (_state.value.document === document && _state.value.editingHighlight == id) {
                document.clearHighlightActivation()
                _state.update { it.copy(editingHighlight = null) }
            }
        }
    }
    private fun saveAnnotation(document: ReaderDocument, action: suspend () -> Unit) {
        _state.update { it.copy(annotationSaving = true, annotationError = null) }
        viewModelScope.launch {
            try { action() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (_state.value.document === document) _state.update { it.copy(annotationError =
                    if (error is IllegalArgumentException) error.message ?: "This annotation is invalid. Review it before saving."
                    else "This annotation could not be saved. Your draft has been kept. Please retry.") }
            } finally {
                if (_state.value.document === document) _state.update { it.copy(annotationSaving = false) }
            }
        }
    }
    fun changeSearchQuery(value: String) {
        stopSearch(markStopped = false)
        if (value.length > 200) {
            _state.update { it.copy(searchQueryRejected = true, searchError = "Search phrases can contain up to 200 characters.", searchStarted = false, searchResults = emptyList()) }
        } else {
            _state.update { it.copy(searchQuery = value, searchQueryRejected = false, searchStarted = false, searchError = null, searchResults = emptyList()) }
        }
    }
    fun search() {
        val document = _state.value.document ?: return
        if (_state.value.searchQueryRejected) return
        stopSearch(markStopped = false)
        val query = _state.value.searchQuery.trim()
        _state.update { it.copy(searchResults = emptyList(), searchRunning = query.isNotEmpty(), searchStarted = query.isNotEmpty(), searchError = null, searchStopped = false) }
        if (query.isEmpty()) return
        val generation = searchGeneration
        searchJob = viewModelScope.launch {
            try {
                document.clearSearchDecorations()
                document.search(query) { page -> _state.update {
                    if (it.document === document && searchGeneration == generation) it.copy(searchResults = it.searchResults + page) else it
                } }
            }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { _state.update {
                if (it.document === document && searchGeneration == generation) it.copy(searchError = (error as? ReaderSearchException)?.displayMessage ?: "This search could not be completed. Try another phrase.") else it
            } }
            finally { _state.update {
                if (it.document === document && searchGeneration == generation) it.copy(searchRunning = false) else it
            } }
        }
    }
    fun cancelSearch() { stopSearch(markStopped = true) }
    private fun stopSearch(markStopped: Boolean) {
        searchGeneration++
        searchJob?.cancel(); searchJob = null
        _state.update { it.copy(searchRunning = false, searchStopped = markStopped && (it.searchRunning || it.searchStopped)) }
    }
    fun goSearchResult(result: ReaderSearchResult) {
        val document = _state.value.document ?: return
        cancelSearch()
        navigate(document, "That matching passage could not be opened.") { goSearchResult(result) }
    }
    fun goBookmark(json: String) {
        val document = _state.value.document ?: return
        navigate(document, "That saved location could not be found in this copy.") { go(json) }
    }
    fun goChapter(id: Int) {
        val document = _state.value.document ?: return
        navigate(document, "That chapter could not be opened.") { goChapter(id) }
    }
    fun previous() { interact(); seeking?.cancel(); _state.value.document?.goBackward() }
    fun next() { interact(); seeking?.cancel(); _state.value.document?.goForward() }
    fun goProgression(value: Double) {
        val document = _state.value.document ?: return
        navigate(document, "That reading position is unavailable.") { goProgression(value) }
    }
    private fun navigate(document: ReaderDocument, unavailable: String, action: suspend ReaderDocument.() -> Boolean) {
        interact()
        seeking?.cancel()
        seeking = viewModelScope.launch {
            try { if (!document.action() && _state.value.document === document) message(unavailable) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (_state.value.document === document) message("That reading position could not be opened. Please try again.") }
        }
    }
    private fun observe(action: suspend () -> Unit) { observers += viewModelScope.launch {
        try { action() } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { message("Some reader settings or saved places could not be loaded. Your existing data has been kept.") }
    } }
    private fun launchSaved(action: suspend () -> Unit) { viewModelScope.launch {
        try { action() } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { message("That change could not be saved. Please try again.") }
    } }

    fun closeReader() {
        activityRecorder?.pause()
        activityRecorder?.close()
        activityRecorder = null
        seeking?.cancel(); seeking = null
        highlightsJob?.cancel(); highlightsJob = null
        val location = _state.value.document?.currentLocation() ?: _state.value.location
        val currentBook = bookId
        if (hasReadInForeground && location != null && currentBook != null) {
            latestPosition = currentBook to location
            writer?.offer(location)
        }
        opening?.cancel(); opening = null
        stopSearch(markStopped = false)
        observers.forEach(Job::cancel); observers.clear()
        // Keep one actor per book across rapid screen reopenings. A newer position cannot
        // otherwise be overwritten by an older screen's still-draining writer.
        _state.value.document?.releaseOwner()
        _state.value = ReaderUiState(loading = false)
        hasReadInForeground = false
        markedOpened = false
    }
    override fun onCleared() {
        closeReader()
        writer?.close(); writer = null; writerBookId = null
        super.onCleared()
    }
}

/** A conflated IO writer drains its last complete locator even when the screen is disposed. */
private class PositionWriter(bookId: String, reading: ReadingRepository, onFailure: () -> Unit) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val positions = Channel<ReaderLocation>(Channel.CONFLATED)
    init { scope.launch {
        try {
            for (location in positions) {
                try { reading.saveProgress(bookId, location.locatorJson, location.progression) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { onFailure() }
            }
        } finally { scope.cancel() }
    } }
    fun offer(location: ReaderLocation) { positions.trySend(location) }
    fun close() { positions.close() }
}
