package com.leaf.reader.feature.library

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.leaf.reader.data.repository.BookImportRepository
import com.leaf.reader.data.repository.BookRepository
import com.leaf.reader.data.repository.PreferencesRepository
import com.leaf.reader.domain.model.*
import com.leaf.reader.domain.usecase.QueryLibraryUseCase
import com.leaf.reader.domain.usecase.RemoveBookUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

@HiltViewModel
@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModel @Inject constructor(
    private val books: BookRepository,
    private val preferencesRepository: PreferencesRepository,
    private val importer: BookImportRepository,
    private val removeBook: RemoveBookUseCase,
    private val queryLibrary: QueryLibraryUseCase,
) : ViewModel() {
    private val query = MutableStateFlow("")
    private val filter = MutableStateFlow<ReadingStatus?>(null)
    private val retry = MutableStateFlow(0)
    private val busy = MutableStateFlow(false)
    private val progress = MutableStateFlow<String?>(null)
    private val notice = MutableStateFlow<String?>(null)
    val message = notice.asStateFlow()

    val preferences: StateFlow<LeafPreferences> = preferencesRepository.preferences.catch { error ->
        if (error is CancellationException) throw error
        notice.value = "Settings could not be loaded. Your saved data has been kept."
        emit(LeafPreferences())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LeafPreferences())

    private val library = retry.flatMapLatest {
        combine(books.books, preferencesRepository.preferences, query, filter) { all, prefs, term, status ->
            LibraryUiState(loading = false, books = all,
                visibleBooks = queryLibrary(all, term, status, prefs.sort),
                query = term, status = status, preferences = prefs)
        }.catch { error ->
            if (error is CancellationException) throw error
            emit(LibraryUiState(loading = false, loadError = "Your library could not be loaded. Please retry."))
        }
    }
    // The navigation shell observes preferences/messages separately. Book queries stop when the
    // reader replaces library/details, rather than reloading every book on each locator checkpoint.
    val state: StateFlow<LibraryUiState> = combine(library, busy, progress) { ui, importing, text ->
        ui.copy(importing = importing, importProgress = text)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryUiState())

    init {
        viewModelScope.launch {
            attempt {
                val recovered = importer.recoverManagedFiles()
                if (recovered.missingBookCopies > 0) {
                    notice.value = "Some imported copies are missing. Re-import the original files to restore them."
                } else if (recovered.quarantinedBooks > 0) {
                    notice.value = "Interrupted imports were kept safely in private recovery storage."
                }
            }
        }
    }

    fun search(value: String) { query.value = value }
    fun filter(status: ReadingStatus?) { filter.value = status }
    fun retry() { retry.value++ }
    fun dismissMessage() { notice.value = null }
    fun sort(sort: BookSort) = updatePreferences { it.copy(sort = sort) }
    fun toggleLayout() = updatePreferences { it.copy(listView = !it.listView) }
    fun updatePreferences(transform: (LeafPreferences) -> LeafPreferences) {
        viewModelScope.launch { attempt { preferencesRepository.update(transform) } }
    }
    fun setStatus(id: String, status: ReadingStatus) {
        viewModelScope.launch { attempt { books.setStatus(id, status) } }
    }

    fun importBooks(uris: List<Uri>) {
        if (busy.value || uris.isEmpty()) return
        busy.value = true
        viewModelScope.launch {
            val failures = mutableListOf<String>()
            var added = 0
            var duplicates = 0
            var restored = 0
            try {
                for ((index, uri) in uris.withIndex()) {
                    progress.value = "Importing ${index + 1} of ${uris.size}…"
                    try {
                        val result = importer.importBook(uri)
                        when { result.restored -> restored++; result.duplicate -> duplicates++; else -> added++ }
                    } catch (error: CancellationException) { throw error }
                    catch (error: Exception) {
                        // The import boundary exposes fixed, path-free recovery messages.
                        failures += error.message ?: "A book could not be imported. Please select it again."
                    }
                }
                notice.value = buildString {
                    if (added > 0) append("$added book${if (added == 1) "" else "s"} added. ")
                    if (duplicates > 0) append("$duplicates already in your library. ")
                    if (restored > 0) append("$restored missing cop${if (restored == 1) "y" else "ies"} restored. ")
                    append(failures.distinct().joinToString(" "))
                }.trim()
            } finally { progress.value = null; busy.value = false }
        }
    }

    fun remove(id: String) {
        viewModelScope.launch { attempt {
            val result = removeBook(id, deleteManagedCopyConfirmed = true)
            notice.value = when {
                !result.removed -> "This book was already removed."
                result.copiesRetainedForRecovery -> "Book removed. Some private files could not be deleted and were kept for recovery."
                else -> "Book removed. Your original document is unchanged."
            }
        } }
    }

    private suspend fun attempt(action: suspend () -> Unit) {
        try { action() }
        catch (error: CancellationException) { throw error }
        catch (_: Exception) { notice.value = "That change could not be saved. Your existing data has been kept. Please retry." }
    }
}
