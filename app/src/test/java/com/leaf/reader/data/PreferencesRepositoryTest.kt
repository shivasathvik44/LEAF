package com.leaf.reader.data

import androidx.datastore.core.CorruptionException
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.leaf.reader.data.repository.PreferencesRepository
import com.leaf.reader.domain.model.AppTheme
import com.leaf.reader.domain.model.BookSort
import com.leaf.reader.domain.model.LeafPreferences
import com.leaf.reader.domain.model.ReaderTheme
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PreferencesRepositoryTest {
    @Test fun corruptPreferenceFileIsReportedWithoutErasingItsBytes() = runBlocking {
        val directory = Files.createTempDirectory("leaf-corrupt-preferences-test").toFile()
        val file = File(directory, "leaf.preferences_pb")
        // A protobuf field tag cannot use wire type 7. This is deliberately invalid storage,
        // rather than a mocked DataStore or a test-specific repository implementation.
        val original = byteArrayOf(0x0F, 0x01, 0x02, 0x03)
        file.writeBytes(original)
        val job = SupervisorJob()
        try {
            val store = PreferenceDataStoreFactory.create(
                scope = CoroutineScope(Dispatchers.IO + job), produceFile = { file })
            var corruptionReported = false
            try { PreferencesRepository(store).preferences.first() }
            catch (_: CorruptionException) { corruptionReported = true }
            assertTrue("Corruption must be reported instead of replaced with empty preferences", corruptionReported)
            assertArrayEquals(original, file.readBytes())
        } finally { job.cancelAndJoin(); directory.deleteRecursively() }
    }

    @Test fun preferencesSurviveARealDataStoreRestart() = runBlocking {
        val directory = Files.createTempDirectory("leaf-preferences-test").toFile()
        val file = File(directory, "leaf.preferences_pb")
        val expected = LeafPreferences(appTheme = AppTheme.LIGHT, accent = 0xFF668866L, sort = BookSort.AUTHOR, listView = true)
        try {
            val firstJob = SupervisorJob()
            val first = PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + firstJob), produceFile = { file })
            PreferencesRepository(first).update { expected }
            firstJob.cancelAndJoin()

            val secondJob = SupervisorJob()
            try {
                val second = PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + secondJob), produceFile = { file })
                assertEquals(expected, PreferencesRepository(second).preferences.first())
            } finally { secondJob.cancelAndJoin() }
        } finally { directory.deleteRecursively() }
    }

    @Test fun perBookOverridesPersistAndResetToCurrentGlobalDefaults() = runBlocking {
        val directory = Files.createTempDirectory("leaf-book-preferences-test").toFile()
        val file = File(directory, "leaf.preferences_pb")
        val firstJob = SupervisorJob()
        try {
            val store = PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + firstJob), produceFile = { file })
            val repository = PreferencesRepository(store)
            val custom = LeafPreferences().reader.copy(theme = ReaderTheme.DARK, fontSize = 175.0, scroll = true)
            repository.saveReaderPrefs("book-a", custom)
            repository.update { it.copy(reader = it.reader.copy(fontSize = 130.0, margins = 1.6)) }
            assertEquals(custom, repository.readerPrefs("book-a").first())
            assertEquals(130.0, repository.readerPrefs("book-b").first().fontSize, 0.0)
            firstJob.cancelAndJoin()

            val secondJob = SupervisorJob()
            try {
                val restarted = PreferencesRepository(PreferenceDataStoreFactory.create(
                    scope = CoroutineScope(Dispatchers.IO + secondJob), produceFile = { file }))
                assertEquals(custom, restarted.readerPrefs("book-a").first())
                restarted.resetReaderPrefs("book-a")
                assertEquals(restarted.preferences.first().reader, restarted.readerPrefs("book-a").first())
            } finally { secondJob.cancelAndJoin() }
        } finally {
            firstJob.cancelAndJoin()
            directory.deleteRecursively()
        }
    }

    @Test fun concurrentUpdatesAreAtomicAndUnknownEnumNamesUseDefaults() = runBlocking {
        val directory = Files.createTempDirectory("leaf-atomic-preferences-test").toFile()
        val file = File(directory, "leaf.preferences_pb")
        val job = SupervisorJob()
        try {
            val store = PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + job), produceFile = { file })
            val repository = PreferencesRepository(store)
            val updates = List(20) { launch { repository.update { it.copy(reader = it.reader.copy(fontSize = it.reader.fontSize + 1)) } } }
            updates.forEach { it.join() }
            assertEquals(120.0, repository.preferences.first().reader.fontSize, 0.0)
            store.edit { it[stringPreferencesKey("app_theme")] = "A_FUTURE_THEME" }
            assertEquals(AppTheme.DARK, repository.preferences.first().appTheme)
            assertEquals(120.0, repository.preferences.first().reader.fontSize, 0.0)
        } finally { job.cancelAndJoin(); directory.deleteRecursively() }
    }

    @Test fun invalidTypographyDoesNotOverwritePersistedPreferences() = runBlocking {
        val directory = Files.createTempDirectory("leaf-invalid-preferences-test").toFile()
        val file = File(directory, "leaf.preferences_pb")
        val job = SupervisorJob()
        try {
            val store = PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + job), produceFile = { file })
            val repository = PreferencesRepository(store)
            repository.update { it.copy(listView = true) }
            var rejected = false
            try { repository.update { it.copy(reader = it.reader.copy(fontSize = Double.NaN)) } }
            catch (_: IllegalArgumentException) { rejected = true }
            assertTrue(rejected)
            assertTrue(repository.preferences.first().listView)
            assertFalse(repository.preferences.first().reader.fontSize.isNaN())
        } finally { job.cancelAndJoin(); directory.deleteRecursively() }
    }
}
