package com.leaf.reader.reader

import android.content.Intent
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.leaf.reader.core.util.PublicationFormat
import com.leaf.reader.data.local.database.LeafDatabase
import com.leaf.reader.data.readium.PublicationRepository
import com.leaf.reader.data.readium.ReaderDocument
import com.leaf.reader.data.readium.ReaderHarnessActivity
import com.leaf.reader.data.repository.AnnotationRepository
import com.leaf.reader.data.repository.BookRepository
import com.leaf.reader.data.repository.PreferencesRepository
import com.leaf.reader.data.repository.ReadingRepository
import com.leaf.reader.domain.model.Book
import com.leaf.reader.feature.reader.ReaderViewModel
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Real native navigator + production ViewModel + file-backed Room and DataStore. */
@RunWith(AndroidJUnit4::class)
class ReaderViewModelIntegrationTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private lateinit var directory: File
    private lateinit var database: LeafDatabase
    private lateinit var reading: ReadingRepository
    private lateinit var annotations: AnnotationRepository
    private lateinit var store: ViewModelStore
    private lateinit var model: ReaderViewModel
    private lateinit var scenario: ActivityScenario<ReaderHarnessActivity>
    private lateinit var preferenceJob: Job
    private lateinit var id: String

    @Before fun setUp() = runBlocking {
        directory = File(context.cacheDir, "reader-viewmodel-tests/${UUID.randomUUID()}").apply { check(mkdirs()) }
        val file = File(directory, "standard.epub")
        instrumentation.context.assets.open("publications/standard.epub").use { input -> file.outputStream().use(input::copyTo) }
        database = Room.databaseBuilder(context, LeafDatabase::class.java, File(directory, "reader.db").absolutePath).build()
        val books = BookRepository(database)
        reading = ReadingRepository(database)
        annotations = AnnotationRepository(database)
        preferenceJob = SupervisorJob()
        val preferences = PreferencesRepository(PreferenceDataStoreFactory.create(
            scope = CoroutineScope(preferenceJob + Dispatchers.IO), produceFile = { File(directory, "reader.preferences_pb") }))
        id = UUID.randomUUID().toString()
        val hash = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
        books.add(Book(id, "ViewModel fixture", "Leaf original fixtures", null, PublicationFormat.EPUB,
            file.absolutePath, null, null, file.length(), hash, System.currentTimeMillis()))
        scenario = ActivityScenario.launch(Intent(context, ReaderHarnessActivity::class.java).putExtra("externalDocument", true))
        scenario.onActivity {
            store = ViewModelStore()
            model = ViewModelProvider(store, object : ViewModelProvider.Factory {
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    require(modelClass == ReaderViewModel::class.java)
                    @Suppress("UNCHECKED_CAST")
                    return ReaderViewModel(books, PublicationRepository(context), reading, preferences, annotations) as T
                }
            })[ReaderViewModel::class.java]
            model.initialize(id)
        }
        bindReadyDocument()
        awaitChapter("chapter1")
    }

    @After fun tearDown() = runBlocking {
        if (this@ReaderViewModelIntegrationTest::scenario.isInitialized) {
            scenario.onActivity { if (this@ReaderViewModelIntegrationTest::store.isInitialized) store.clear() }
            scenario.close()
        }
        if (this@ReaderViewModelIntegrationTest::preferenceJob.isInitialized) preferenceJob.cancelAndJoin()
        if (this@ReaderViewModelIntegrationTest::database.isInitialized) database.close()
        if (this@ReaderViewModelIntegrationTest::directory.isInitialized) directory.deleteRecursively()
    }

    @Test fun rapidReopenPrefersTheLatestNativeLocatorAndSerializesFinalDatabaseWrites() = runBlocking {
        val first = withTimeout(10_000) { reading.progress(id).first { it != null }!! }
        scenario.onActivity { model.goChapter(1) }
        awaitChapter("chapter2")
        var latestJson = ""
        scenario.onActivity {
            latestJson = requireNotNull(model.state.value.document?.currentLocation()).locatorJson
            model.closeReader()
            model.initialize(id)
        }
        bindReadyDocument()
        awaitChapter("chapter2")
        scenario.onActivity {
            val latest = ReaderDocument.parse(latestJson)!!
            assertEquals(latest.href, ReaderDocument.parse(model.state.value.document!!.currentLocation()!!.locatorJson)!!.href)
            model.goChapter(2)
        }
        awaitChapter("chapter3")
        repeat(2) {
            scenario.onActivity { model.closeReader(); model.initialize(id) }
            bindReadyDocument()
            awaitChapter("chapter3")
        }
        val final = withTimeout(10_000) { reading.progress(id).first { it?.locatorJson?.contains("chapter3") == true }!! }
        assertNotEquals(first.locatorJson, final.locatorJson)
        assertTrue(requireNotNull(ReaderDocument.parse(final.locatorJson)).href.toString().contains("chapter3"))
        // A closed screen's older writer must not overwrite the final reopened location later.
        delay(500)
        assertEquals(final.locatorJson, reading.getProgress(id)!!.locatorJson)
    }

    @Test fun viewModelBookmarksKeepOptionalLabelsAndNavigateUsingTheirCompleteLocators() = runBlocking {
        scenario.onActivity { model.goChapter(1) }
        awaitChapter("chapter2")
        scenario.onActivity { model.addBookmark("River passage") }
        val bookmark = withTimeout(10_000) { annotations.bookmarks(id).first { rows -> rows.any { it.label == "River passage" } }.single() }
        assertTrue(requireNotNull(ReaderDocument.parse(bookmark.locatorJson)).href.toString().contains("chapter2"))
        scenario.onActivity { model.goChapter(0) }
        awaitChapter("chapter1")
        scenario.onActivity { model.goBookmark(bookmark.locatorJson) }
        awaitChapter("chapter2")
        scenario.onActivity { model.removeBookmark(bookmark.id) }
        withTimeout(10_000) { annotations.bookmarks(id).first { it.isEmpty() } }
        scenario.onActivity { model.addBookmark("") }
        val unlabeled = withTimeout(10_000) { annotations.bookmarks(id).first { it.isNotEmpty() }.single() }
        assertNull(unlabeled.label)
    }

    private fun bindReadyDocument() {
        await { model.state.value.document != null }
        scenario.onActivity { it.bindExternal(requireNotNull(model.state.value.document), model.state.value.preferences) }
    }
    private fun awaitChapter(name: String) = await { activity ->
        activity.document?.currentLocation()?.locatorJson?.contains(name) == true && model.state.value.location?.locatorJson?.contains(name) == true
    }
    private fun await(condition: (ReaderHarnessActivity) -> Boolean) {
        val deadline = android.os.SystemClock.uptimeMillis() + 15_000
        while (android.os.SystemClock.uptimeMillis() < deadline) {
            var success = false
            scenario.onActivity { success = condition(it) }
            if (success) return
            android.os.SystemClock.sleep(50)
        }
        scenario.onActivity { assertNull("The actual native reader reported a failure", it.failure); assertNull("The actual ViewModel reported an opening error", model.state.value.error) }
        fail("The production reader ViewModel did not reach its expected native/database state")
    }
}
