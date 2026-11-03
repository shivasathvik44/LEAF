package com.leaf.reader.feature.reader

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.leaf.reader.data.readium.PublicationAccessException
import com.leaf.reader.data.readium.PublicationRepository
import com.leaf.reader.data.readium.ReaderDocument
import com.leaf.reader.data.readium.ReaderLocation
import com.leaf.reader.data.repository.AnnotationRepository
import com.leaf.reader.data.repository.BookRepository
import com.leaf.reader.data.repository.PreferencesRepository
import com.leaf.reader.data.repository.ReadingRepository
import com.leaf.reader.domain.model.ReaderPreferences
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
) : ViewModel() {
    private val _state = MutableStateFlow(ReaderUiState())
    val state: StateFlow<ReaderUiState> = _state.asStateFlow()
    private var bookId: String? = null
    private var opening: Job? = null
    private var seeking: Job? = null
    private val observers = mutableListOf<Job>()
    private var writer: PositionWriter? = null
    private var writerBookId: String? = null
    private var latestPosition: Pair<String, ReaderLocation>? = null

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
                books.markOpened(id)
                if (writer == null || writerBookId != id) {
                    writer?.close()
                    writerBookId = id
                    writer = PositionWriter(id, reading) { message("Your reading position could not be saved. Free some storage and try again.") }
                }
                _state.update { it.copy(loading = false, title = book.title, format = book.format, document = document, chapters = document.chapters) }
                observe { preferences.readerPrefs(id).collect { value -> _state.update { it.copy(preferences = value) } } }
                observe { annotations.bookmarks(id).collect { value -> _state.update { it.copy(bookmarks = value) } } }
                observe { document.location.collect { location ->
                    if (location != null) { _state.update { it.copy(location = location) }; writer?.offer(location) }
                } }
                opened = null // Ownership is now held by state until closeReader/onCleared.
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                _state.update { it.copy(loading = false, error = if (failure is PublicationAccessException) failure.message else "This imported book could not be opened. Import a fresh copy or try again.") }
            } finally { opened?.releaseOwner() }
        }
    }
    fun retry() { val id = bookId ?: return; closeReader(); initialize(id) }
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
    fun goBookmark(json: String) { if (_state.value.document?.go(json) != true) message("That saved location could not be found in this copy.") }
    fun goChapter(id: Int) { if (_state.value.document?.goChapter(id) != true) message("That chapter could not be opened.") }
    fun previous() { _state.value.document?.goBackward() }
    fun next() { _state.value.document?.goForward() }
    fun goProgression(value: Double) {
        val document = _state.value.document ?: return
        seeking?.cancel()
        seeking = viewModelScope.launch {
            try {
                if (!document.goProgression(value) && _state.value.document === document) message("That reading position is unavailable.")
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (_state.value.document === document) message("That reading position could not be opened. Please try again.")
            }
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
        seeking?.cancel(); seeking = null
        val location = _state.value.document?.currentLocation() ?: _state.value.location
        val currentBook = bookId
        if (location != null && currentBook != null) {
            latestPosition = currentBook to location
            writer?.offer(location)
        }
        opening?.cancel(); opening = null
        observers.forEach(Job::cancel); observers.clear()
        // Keep one actor per book across rapid screen reopenings. A newer position cannot
        // otherwise be overwritten by an older screen's still-draining writer.
        _state.value.document?.releaseOwner()
        _state.value = ReaderUiState(loading = false)
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
