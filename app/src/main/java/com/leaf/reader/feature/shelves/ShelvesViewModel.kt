package com.leaf.reader.feature.shelves

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.leaf.reader.data.repository.BookRepository
import com.leaf.reader.data.repository.ShelfRepository
import com.leaf.reader.domain.model.Book
import com.leaf.reader.domain.model.Shelf
import com.leaf.reader.domain.model.ShelfLimits
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class ShelfEditor(
    val id: String? = null,
    val name: String = "",
    val description: String = "",
    val addBookId: String? = null,
    val saving: Boolean = false,
    val error: String? = null,
)

data class ShelvesUiState(
    val loading: Boolean = true,
    val shelves: List<Shelf> = emptyList(),
    val books: List<Book> = emptyList(),
    val error: String? = null,
)

@HiltViewModel
@OptIn(ExperimentalCoroutinesApi::class)
class ShelvesViewModel @Inject constructor(
    private val repository: ShelfRepository,
    books: BookRepository,
    private val savedState: SavedStateHandle,
) : ViewModel() {
    private val retries = MutableStateFlow(0)
    private val editorState = MutableStateFlow(if (savedState.get<Boolean>("shelf-editor-open") == true) {
        ShelfEditor(savedState["shelf-editor-id"], savedState["shelf-editor-name"] ?: "",
            savedState["shelf-editor-description"] ?: "", savedState["shelf-editor-book"])
    } else null)
    private val notice = MutableStateFlow<String?>(null)
    private val pendingMemberships = MutableStateFlow<Set<String>>(emptySet())
    private val pendingDeletions = MutableStateFlow<Set<String>>(emptySet())
    val editor = editorState.asStateFlow()
    val message = notice.asStateFlow()
    val membershipBusy = pendingMemberships.asStateFlow()
    val deletionBusy = pendingDeletions.asStateFlow()
    val state = retries.flatMapLatest {
        combine(repository.shelves, books.books) { shelves, library -> ShelvesUiState(false, shelves, library) }
            .onStart { emit(ShelvesUiState()) }
            .catch { failure ->
                if (failure is CancellationException) throw failure
                emit(ShelvesUiState(loading = false, error = "Shelves could not be loaded. Please retry."))
            }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ShelvesUiState())

    fun retry() { retries.value++ }
    fun message(value: String?) { notice.value = value }
    fun edit(shelf: Shelf? = null, addBookId: String? = null) {
        if (editorState.value?.saving == true) return
        setEditor(ShelfEditor(shelf?.id, shelf?.name.orEmpty(), shelf?.description.orEmpty(), addBookId))
    }
    fun editorName(value: String) {
        editorState.value?.takeUnless { it.saving }?.let { current ->
            // Reject the entire over-limit edit, rather than retaining a potentially huge
            // clipboard payload in SavedStateHandle or silently shortening the user's text.
            // An IME can report the unchanged value when it resets after rejection.
            if (ShelfLimits.characters(value) > ShelfLimits.MAX_NAME_CHARACTERS) {
                setEditor(current.copy(error = "That name is too long. The new text was not added; use up to 100 characters."))
            } else if (value != current.name) setEditor(current.copy(name = value, error = null))
        }
    }
    fun editorDescription(value: String) {
        editorState.value?.takeUnless { it.saving }?.let { current ->
            if (!ShelfLimits.validDescription(value)) {
                setEditor(current.copy(error = "That description is too long. The new text was not added; use up to 2000 characters."))
            } else if (value != current.description) setEditor(current.copy(description = value, error = null))
        }
    }
    fun dismissEditor() { if (editorState.value?.saving != true) setEditor(null) }
    fun saveEditor() {
        val current = editorState.value ?: return
        if (current.saving) return
        try { ShelfLimits.validate(current.name, current.description) }
        catch (failure: IllegalArgumentException) { setEditor(current.copy(error = failure.message)); return }
        setEditor(current.copy(saving = true, error = null))
        viewModelScope.launch {
            try {
                if (current.id == null) repository.create(current.name, current.description, current.addBookId)
                else repository.update(current.id, current.name, current.description)
                setEditor(null)
            } catch (failure: CancellationException) { throw failure }
            catch (failure: Exception) {
                setEditor(current.copy(error = if (failure is IllegalArgumentException) failure.message
                    else "This shelf could not be saved. Your entered text has been kept. Please retry."))
            }
        }
    }
    fun delete(id: String) {
        if (id in pendingDeletions.value) return
        notice.value = null
        pendingDeletions.update { it + id }
        viewModelScope.launch {
            try { repository.delete(id) }
            catch (failure: CancellationException) { throw failure }
            catch (_: Exception) { notice.value = "This shelf could not be deleted. Your books and shelf have been kept. Please retry." }
            finally { pendingDeletions.update { it - id } }
        }
    }
    fun setMembership(shelfId: String, bookId: String, included: Boolean) {
        val key = "$shelfId/$bookId"
        if (key in pendingMemberships.value) return
        notice.value = null
        pendingMemberships.update { it + key }
        viewModelScope.launch {
            try {
                if (included) repository.addBook(shelfId, bookId) else repository.removeBook(shelfId, bookId)
            } catch (failure: CancellationException) { throw failure }
            catch (_: Exception) { notice.value = "The shelf change could not be saved. Please retry." }
            finally { pendingMemberships.update { it - key } }
        }
    }

    private fun setEditor(value: ShelfEditor?) {
        savedState["shelf-editor-open"] = value != null
        savedState["shelf-editor-id"] = value?.id
        savedState["shelf-editor-name"] = value?.name
        savedState["shelf-editor-description"] = value?.description
        savedState["shelf-editor-book"] = value?.addBookId
        editorState.value = value
    }
}
