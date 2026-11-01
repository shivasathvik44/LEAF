package com.leaf.reader.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.leaf.reader.data.local.datastore.PreferenceCodec
import com.leaf.reader.domain.model.LeafPreferences
import com.leaf.reader.domain.model.ReaderPreferences
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

@Singleton
class PreferencesRepository @Inject constructor(private val store: DataStore<Preferences>) {
    // Storage/corruption failures are deliberately surfaced to the UI; no file is erased or replaced.
    val preferences: Flow<LeafPreferences> = store.data.map(PreferenceCodec::read).distinctUntilChanged()

    suspend fun update(transform: (LeafPreferences) -> LeafPreferences) {
        store.edit { values -> PreferenceCodec.write(values, transform(PreferenceCodec.read(values))) }
    }

    fun readerPrefs(bookId: String): Flow<ReaderPreferences> = store.data
        .map { PreferenceCodec.readBook(it, bookId) }.distinctUntilChanged()

    suspend fun saveReaderPrefs(bookId: String, preferences: ReaderPreferences) {
        store.edit { values -> PreferenceCodec.writeBook(values, bookId, preferences) }
    }

    suspend fun resetReaderPrefs(bookId: String) {
        store.edit { values -> PreferenceCodec.resetBook(values, bookId) }
    }
}
