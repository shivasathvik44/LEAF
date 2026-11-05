package com.leaf.reader.shelves

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.room.withTransaction
import androidx.lifecycle.ViewModelProvider
import androidx.test.espresso.Espresso.closeSoftKeyboard
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.leaf.reader.MainActivity
import com.leaf.reader.core.util.PublicationFormat
import com.leaf.reader.data.readium.ReaderUiDependencies
import com.leaf.reader.data.repository.BookRepository
import com.leaf.reader.data.repository.ShelfRepository
import com.leaf.reader.domain.model.Book
import com.leaf.reader.domain.model.Shelf
import com.leaf.reader.feature.shelves.ShelvesViewModel
import dagger.hilt.android.EntryPointAccessors
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Actual MainActivity + Hilt/Room flows; only UUID-scoped fixtures and shelves are removed. */
@RunWith(AndroidJUnit4::class)
class ShelvesUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val database get() = EntryPointAccessors.fromApplication(context.applicationContext, ReaderUiDependencies::class.java).database()
    private val books get() = BookRepository(database)
    private val shelves get() = ShelfRepository(database)
    private lateinit var directory: File
    private lateinit var token: String
    private lateinit var prefix: String
    private lateinit var bookId: String
    private lateinit var bookTitle: String
    private var trigger: String? = null
    private var model: ShelvesViewModel? = null

    @Before fun setUp() = runBlocking<Unit> {
        token = UUID.randomUUID().toString()
        prefix = "Leaf shelf $token"
        bookId = UUID.randomUUID().toString()
        bookTitle = "Shelf book $token"
        directory = File(context.cacheDir, "shelf-ui-tests/$token").apply { check(mkdirs()) }
        val file = fixture()
        val hash = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
        books.add(Book(bookId, bookTitle, "Leaf original fixtures", null, PublicationFormat.EPUB,
            file.absolutePath, null, null, file.length(), hash, System.currentTimeMillis()))
        compose.onNodeWithTag("nav-Shelves").performClick()
        compose.onNodeWithTag("shelves-screen").assertIsDisplayed()
        compose.activityRule.scenario.onActivity { activity ->
            model = ViewModelProvider(activity)["leaf-shelves", ShelvesViewModel::class.java]
        }
        compose.waitUntil(15_000) { model?.state?.value?.loading == false }
        compose.onNodeWithText("New shelf").assertIsEnabled()
    }

    @After fun tearDown() = runBlocking<Unit> {
        try { dropTrigger() }
        finally {
            compose.activityRule.scenario.close()
            if (this@ShelvesUiTest::prefix.isInitialized) {
                shelves.shelves.first().filter { it.name.startsWith(prefix) }.forEach { shelves.delete(it.id) }
            }
            if (this@ShelvesUiTest::bookId.isInitialized) books.removeRecord(bookId)
            if (this@ShelvesUiTest::directory.isInitialized) directory.deleteRecursively()
        }
    }

    @Test fun shelfCrudAndManyToManyMembershipPreserveBooksAcrossRecreation() = runBlocking<Unit> {
        val first = createShelf("$prefix A", "First description")
        val second = createShelf("$prefix B", "Second description")
        addOwnedBook(first)
        compose.onNodeWithContentDescription("All shelves").performClick()
        addOwnedBook(second)
        assertEquals(setOf(first.id, second.id), shelves.shelves.first().filter { bookId in it.bookIds }.map { it.id }.toSet())
        compose.onNodeWithContentDescription("All shelves").performClick()

        compose.onNodeWithContentDescription("Actions for ${second.name}").performClick()
        compose.onNodeWithText("Edit shelf").performClick()
        val editedName = "$prefix Edited"
        val editedDescription = "A personal shelf description — नमस्ते 🌿"
        compose.onNodeWithTag("shelf-name").performTextReplacement(editedName)
        compose.onNodeWithTag("shelf-description").performTextReplacement(editedDescription)
        // A clipboard-sized edit must never enter SavedStateHandle and break recreation.
        // Both rejected edits preserve the actual prior draft rather than shortening it.
        compose.onNodeWithTag("shelf-name").performTextReplacement("x".repeat(300_000))
        closeSoftKeyboard()
        compose.onNodeWithText("That name is too long. The new text was not added; use up to 100 characters.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("shelf-name").assertTextEquals(editedName)
        compose.onNodeWithTag("shelf-description").performTextReplacement("y".repeat(300_000))
        closeSoftKeyboard()
        compose.onNodeWithText("That description is too long. The new text was not added; use up to 2000 characters.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("shelf-description").assertTextEquals(editedDescription)
        closeSoftKeyboard()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("shelf-name").assertTextEquals(editedName)
        compose.onNodeWithTag("shelf-description").assertTextEquals(editedDescription)
        compose.onNodeWithTag("shelf-save").performClick()
        val edited = awaitShelf { it.id == second.id && it.name == editedName && it.description == editedDescription }
        assertEquals(second.createdAt, edited.createdAt)
        assertEquals(listOf(bookId), edited.bookIds)

        compose.onNodeWithTag("shelf-${first.id}").performClick()
        compose.onNodeWithContentDescription("Remove $bookTitle from this shelf").performClick()
        awaitShelf { it.id == first.id && it.count == 0 }
        compose.onNodeWithText("A little room for your favorite books. Tap Add books to fill this shelf.").assertIsDisplayed()
        compose.onNodeWithContentDescription("All shelves").performClick()
        compose.onNodeWithContentDescription("Actions for ${first.name}").performClick()
        compose.onNodeWithText("Delete shelf").performClick()
        compose.onNodeWithText("Only this shelf and its memberships will be removed. All books stay in your library.").assertIsDisplayed()
        compose.onNodeWithText("Delete shelf").performClick()
        withTimeout(15_000) { shelves.shelves.first { rows -> rows.none { it.id == first.id } } }
        assertNotNull(books.get(bookId))
        assertTrue(File(requireNotNull(books.get(bookId)).storagePath).isFile)
        assertEquals(listOf(bookId), shelves.shelves.first().single { it.id == second.id }.bookIds)
        compose.onNodeWithTag("shelf-${second.id}").performClick()
        compose.onNodeWithTag("shelf-book-$bookId").assertIsDisplayed()
        compose.onNodeWithText("Details").performClick()
        compose.onNodeWithText("Add to shelves").performScrollTo().assertIsDisplayed()
    }

    @Test fun bookDetailsPickerCreatesAndAssignsShelfWithAnAccessibleCheckedRow() = runBlocking<Unit> {
        val original = shelves.create("$prefix Existing", bookId = bookId)
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("shelf-$original").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("shelf-$original").performClick()
        compose.onNodeWithText("Details").performClick()
        compose.onNodeWithText("Add to shelves").performScrollTo().performClick()
        compose.onNodeWithTag("book-shelf-$original").assertIsOn()
        compose.onNodeWithText("Create a new shelf").performClick()
        val name = "$prefix From book"
        compose.onNodeWithTag("shelf-name").performTextReplacement(name)
        compose.onNodeWithTag("shelf-description").performTextReplacement("Created from book details")
        closeSoftKeyboard()
        compose.onNodeWithTag("shelf-save").performClick()
        val created = awaitShelf { it.name == name && it.bookIds == listOf(bookId) }
        compose.onNodeWithTag("book-shelf-${created.id}").assertIsOn()
        compose.onNodeWithTag("book-shelf-$original").performClick()
        awaitShelf { it.id == original && it.bookIds.isEmpty() }
        assertEquals(listOf(bookId), shelves.shelves.first().single { it.id == created.id }.bookIds)
        compose.onNodeWithText("Done").performClick()
        assertNotNull(books.get(bookId))
    }

    @Test fun duplicateAndDatabaseFailuresKeepEditorDraftUntilAVisibleRetrySucceeds() = runBlocking<Unit> {
        val existing = createShelf("$prefix Existing", "Keep this shelf")
        compose.onNodeWithText("New shelf").performClick()
        val duplicate = existing.name.uppercase()
        val draft = "Keep this entered description 🌿"
        compose.onNodeWithTag("shelf-name").performTextReplacement(duplicate)
        compose.onNodeWithTag("shelf-description").performTextReplacement(draft)
        closeSoftKeyboard()
        compose.onNodeWithTag("shelf-save").performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("shelf-editor-error").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("A shelf with that name already exists. Choose another name.").assertIsDisplayed()
        compose.onNodeWithTag("shelf-name").assertTextEquals(duplicate)
        compose.onNodeWithTag("shelf-description").assertTextEquals(draft)

        val retryName = "$prefix Retry"
        compose.onNodeWithTag("shelf-name").performTextReplacement(retryName)
        closeSoftKeyboard()
        trigger = "leaf_shelf_ui_${token.replace("-", "") }"
        database.withTransaction {
            database.openHelper.writableDatabase.execSQL("""
                CREATE TEMP TRIGGER $trigger BEFORE INSERT ON main.shelves
                WHEN NEW.name = '$retryName'
                BEGIN SELECT RAISE(ABORT, 'expected shelf UI insertion failure'); END
            """.trimIndent())
        }
        compose.onNodeWithTag("shelf-save").performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithText("This shelf could not be saved. Your entered text has been kept. Please retry.").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("This shelf could not be saved. Your entered text has been kept. Please retry.").assertIsDisplayed()
        compose.onNodeWithTag("shelf-name").assertTextEquals(retryName)
        compose.onNodeWithTag("shelf-description").assertTextEquals(draft)
        compose.onNodeWithTag("shelf-save").assertIsEnabled()
        assertFalse(shelves.shelves.first().any { it.name == retryName })
        assertEquals("Keep this shelf", shelves.shelves.first().single { it.id == existing.id }.description)
        dropTrigger()
        compose.onNodeWithTag("shelf-save").performClick()
        val saved = awaitShelf { it.name == retryName }
        assertEquals(draft, saved.description)
        assertEquals(1, shelves.shelves.first().count { it.name == retryName })
    }

    private suspend fun createShelf(name: String, description: String): Shelf {
        compose.onNodeWithText("New shelf").performClick()
        compose.onNodeWithTag("shelf-name").performTextReplacement(name)
        compose.onNodeWithTag("shelf-description").performTextReplacement(description)
        closeSoftKeyboard()
        compose.onNodeWithTag("shelf-save").performClick()
        val shelf = awaitShelf { it.name == name && it.description == description }
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("shelf-name").fetchSemanticsNodes().isEmpty() }
        return shelf
    }

    private suspend fun addOwnedBook(shelf: Shelf) {
        compose.onNodeWithTag("shelf-${shelf.id}").performClick()
        compose.onNodeWithText("Add books").performClick()
        compose.onNodeWithTag("shelf-membership-${shelf.id}-$bookId").performScrollTo().performClick()
        awaitShelf { it.id == shelf.id && bookId in it.bookIds }
        compose.onNodeWithText("Done").performClick()
        compose.onNodeWithTag("shelf-book-$bookId").assertIsDisplayed()
    }

    private suspend fun awaitShelf(predicate: (Shelf) -> Boolean): Shelf {
        val shelf = withTimeout(15_000) {
            shelves.shelves.first { rows -> rows.any(predicate) }.first(predicate)
        }
        compose.waitUntil(15_000) { model?.state?.value?.shelves?.any(predicate) == true }
        compose.waitForIdle()
        return shelf
    }

    private suspend fun dropTrigger() {
        val name = trigger ?: return
        database.withTransaction { database.openHelper.writableDatabase.execSQL("DROP TRIGGER IF EXISTS temp.$name") }
        trigger = null
    }

    private fun fixture(): File = File(directory, "standard.epub").also { file ->
        ZipInputStream(instrumentation.context.assets.open("publications/standard.epub")).use { input ->
            ZipOutputStream(file.outputStream()).use { output ->
                output.setComment("Leaf shelf UI fixture $token")
                while (true) {
                    val source = input.nextEntry ?: break
                    val bytes = input.readBytes()
                    val entry = ZipEntry(source.name)
                    if (source.name == "mimetype") {
                        entry.method = ZipEntry.STORED; entry.size = bytes.size.toLong()
                        entry.crc = CRC32().apply { update(bytes) }.value
                    }
                    output.putNextEntry(entry); output.write(bytes); output.closeEntry(); input.closeEntry()
                }
            }
        }
    }
}
