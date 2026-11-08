package com.leaf.reader.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.leaf.reader.core.util.PublicationImportException
import com.leaf.reader.data.local.files.RecoveredBookCopy
import com.leaf.reader.data.readium.PublicationAccessException
import com.leaf.reader.data.repository.BookImportRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal data class StorageSettingsState(
    val bytes: Long? = null,
    val busy: Boolean = false,
    val message: String? = null,
    val recovered: List<RecoveredBookCopy> = emptyList(),
)

@HiltViewModel
internal class StorageSettingsViewModel @Inject constructor(private val importer: BookImportRepository) : ViewModel() {
    private val _state = MutableStateFlow(StorageSettingsState())
    val state = _state.asStateFlow()

    fun refresh() = runStorageAction { null }

    fun clearTemporaryFiles() = runStorageAction {
        val removed = importer.clearTemporaryCache()
        if (removed == 0) "No temporary import files to remove."
        else "Removed $removed temporary import ${if (removed == 1) "file" else "files"}."
    }

    fun restore(token: String) = runStorageAction {
        if (importer.restoreRecoveredBook(token)) "Recovered book is in your library."
        else "The book is in your library. The recovery copy is still in private storage."
    }

    private fun runStorageAction(action: suspend () -> String?) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, message = null) }
        viewModelScope.launch {
            try {
                val message = action()
                _state.value = StorageSettingsState(bytes = importer.storageUsageBytes(), message = message, recovered = importer.recoveredBooks())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                val message = when (failure) {
                    is PublicationImportException, is PublicationAccessException -> failure.message
                    else -> "Private storage could not be updated. Check free space and try again."
                }
                _state.update { it.copy(busy = false, message = message) }
            }
        }
    }
}
