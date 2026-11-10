package com.leaf.reader.data

import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.leaf.reader.core.util.PublicationFormat
import com.leaf.reader.data.local.database.LeafDatabase
import com.leaf.reader.data.repository.AnnotationRepository
import com.leaf.reader.data.repository.BookRepository
import com.leaf.reader.data.repository.ReadingRepository
import com.leaf.reader.data.repository.ShelfNameInUseException
import com.leaf.reader.data.repository.ShelfRepository
import com.leaf.reader.data.repository.StatisticsRepository
import com.leaf.reader.domain.model.Book
import com.leaf.reader.domain.model.Shelf
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.rules.Timeout
import org.junit.runner.RunWith

/** Every case owns a file-backed database and files; no app-library data is cleared. */
@RunWith(AndroidJUnit4::class)
class ShelfRepositoryTest {
    @get:Rule val testTimeout: TestRule = Timeout.seconds(120)
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var directory: File
    private lateinit var database: LeafDatabase
    private lateinit var shelves: ShelfRepository
    private lateinit var bookId: String
    private lateinit var bookFile: File

    @Before fun setUp() = runBlocking<Unit> {
        directory = File(context.cacheDir, "shelf-repository-tests/${UUID.randomUUID()}").apply { check(mkdirs()) }
        openDatabase()
        bookId = UUID.randomUUID().toString()
        bookFile = File(directory, "managed-book.epub").apply { writeText("Owned metadata fixture") }
        BookRepository(database).add(Book(bookId, "Shelf fixture", "Leaf original fixtures", null,
            PublicationFormat.EPUB, bookFile.absolutePath, null, null, bookFile.length(), "a".repeat(64), 10L))
    }

    @After fun tearDown() {
        if (this::database.isInitialized) database.close()
        if (this::directory.isInitialized) directory.deleteRecursively()
    }

    @Test fun editingAndReopeningPreservesShelfIdentityCreationTimeAndExactMetadata() = runBlocking {
        val id = shelves.create("  Quiet books  ", "Original description")
        val original = all().single()
        assertEquals("Quiet books", original.name)
        val description = "A shelf for quiet afternoons — नमस्ते 🌿\nAnd a second line."
        shelves.update(id, "  Garden 🌿  ", description)
        reopenDatabase()
        val restored = all().single()
        assertEquals(id, restored.id)
        assertEquals(original.createdAt, restored.createdAt)
        assertEquals("Garden 🌿", restored.name)
        assertEquals(description, restored.description)
    }

    @Test fun booksCanBelongToSeveralShelvesAndDuplicateAddsStayIdempotent() = runBlocking {
        val first = shelves.create("First")
        val second = shelves.create("Second")
        shelves.addBook(first, bookId)
        shelves.addBook(first, bookId)
        shelves.addBook(second, bookId)
        reopenDatabase()
        assertEquals(listOf(bookId), all().first { it.id == first }.bookIds)
        assertEquals(listOf(bookId), all().first { it.id == second }.bookIds)
        shelves.removeBook(first, bookId)
        reopenDatabase()
        assertEquals(0, all().first { it.id == first }.count)
        assertEquals(1, all().first { it.id == second }.count)
        assertNotNull(BookRepository(database).get(bookId))
    }

    @Test fun deletingShelfRemovesOnlyMembershipsAndPreservesBooksAnnotationsProgressAndActivity() = runBlocking {
        val id = shelves.create("Remove only this shelf", bookId = bookId)
        val keep = shelves.create("Keep this shelf", bookId = bookId)
        val bookmark = AnnotationRepository(database).addBookmark(bookId, LOCATOR, "Keep this bookmark")
        val highlight = AnnotationRepository(database).addHighlight(bookId, LOCATOR, "silver", 0xFFFFD976L, "Keep this note")
        ReadingRepository(database).saveProgress(bookId, LOCATOR, 0.25)
        val checkpoint = StatisticsRepository(database).recordInterval(bookId, 1_000, 2_000, 1_000)
        val bytes = bookFile.readBytes()
        shelves.delete(id)
        reopenDatabase()
        assertEquals(listOf(keep), all().map(Shelf::id))
        assertEquals(listOf(bookId), all().single().bookIds)
        assertNotNull(BookRepository(database).get(bookId))
        assertEquals(LOCATOR, ReadingRepository(database).getProgress(bookId)?.locatorJson)
        assertEquals(bookmark, AnnotationRepository(database).bookmarks(bookId).first().single().id)
        assertEquals(highlight, AnnotationRepository(database).highlights(bookId).first().single().id)
        assertEquals("Keep this note", AnnotationRepository(database).highlights(bookId).first().single().note)
        assertEquals(checkpoint, StatisticsRepository(database).sessions.first().single().id)
        assertArrayEquals(bytes, bookFile.readBytes())
    }

    @Test fun duplicateCreateOrRenameKeepsExistingMetadataAndMemberships() = runBlocking {
        val first = shelves.create("Quiet books", "Keep original", bookId)
        val second = shelves.create("Other books", "Keep other")
        val before = all()
        expectFailure<ShelfNameInUseException> { shelves.create("quiet BOOKS", "Must not replace") }
        expectFailure<ShelfNameInUseException> { shelves.update(second, "QUIET BOOKS", "Must not replace") }
        reopenDatabase()
        assertEquals(before, all())
        assertEquals(listOf(bookId), all().first { it.id == first }.bookIds)
        shelves.update(first, "quiet BOOKS", "Same shelf, changed casing")
        assertEquals(first, all().first { it.name == "quiet BOOKS" }.id)
    }

    @Test fun oversizedMetadataIsRejectedWithoutChangingSavedValues() = runBlocking {
        val id = shelves.create("🌿".repeat(100), "d".repeat(2_000), bookId)
        val before = all()
        expectFailure<IllegalArgumentException> { shelves.create("n".repeat(101), "") }
        expectFailure<IllegalArgumentException> { shelves.create("New", "d".repeat(2_001)) }
        expectFailure<IllegalArgumentException> { shelves.update(id, "Edited", "d".repeat(2_001)) }
        expectFailure<IllegalArgumentException> { shelves.update(id, "n".repeat(101), "Edited") }
        reopenDatabase()
        assertEquals(before, all())
    }

    @Test fun missingBookRollsBackShelfAndMembershipCreationTogether() = runBlocking {
        expectFailure<SQLiteConstraintException> { shelves.create("Must roll back", "Draft remains with caller", UUID.randomUUID().toString()) }
        reopenDatabase()
        assertTrue(all().isEmpty())
        assertNotNull(BookRepository(database).get(bookId))
        val id = shelves.create("Retry succeeds", bookId = bookId)
        assertEquals(id, all().single().id)
        assertEquals(listOf(bookId), all().single().bookIds)
    }

    @Test fun actualSqliteUpdateFailurePreservesWholeShelfAndAllowsRetry() = runBlocking {
        val id = shelves.create("Original", "Original description", bookId)
        val before = all().single()
        database.openHelper.writableDatabase.execSQL("""
            CREATE TRIGGER abort_shelf_update BEFORE UPDATE ON shelves
            BEGIN SELECT RAISE(ABORT, 'expected shelf update failure'); END
        """.trimIndent())
        try {
            expectFailure<SQLiteConstraintException> { shelves.update(id, "Edited", "Edited description") }
            assertEquals(before, all().single())
        } finally { database.openHelper.writableDatabase.execSQL("DROP TRIGGER abort_shelf_update") }
        shelves.update(id, "Edited", "Edited description")
        reopenDatabase()
        assertEquals(id, all().single().id)
        assertEquals("Edited", all().single().name)
        assertEquals("Edited description", all().single().description)
        assertEquals(listOf(bookId), all().single().bookIds)
    }

    private suspend fun all(): List<Shelf> = shelves.shelves.first()
    private fun openDatabase() {
        database = Room.databaseBuilder(context, LeafDatabase::class.java, File(directory, "shelves.db").absolutePath)
            .addMigrations(*LeafDatabase.ALL_MIGRATIONS).build()
        shelves = ShelfRepository(database)
    }
    private fun reopenDatabase() { database.close(); openDatabase() }
    private suspend inline fun <reified T : Exception> expectFailure(crossinline action: suspend () -> Unit) {
        try { action(); fail("Expected ${T::class.java.simpleName}") }
        catch (failure: Exception) { if (failure !is T) throw failure }
    }
    private companion object {
        const val LOCATOR = """{"href":"chapter.xhtml","type":"application/xhtml+xml","locations":{"progression":0.25},"text":{"highlight":"silver"}}"""
    }
}
