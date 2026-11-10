package com.leaf.reader.data

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.leaf.reader.core.util.PublicationFormat
import com.leaf.reader.data.local.database.LeafDatabase
import com.leaf.reader.data.local.database.entity.ShelfEntity
import com.leaf.reader.data.repository.AnnotationRepository
import com.leaf.reader.data.repository.BookRepository
import com.leaf.reader.data.repository.ReadingRepository
import com.leaf.reader.data.repository.ShelfRepository
import com.leaf.reader.data.repository.StatisticsRepository
import com.leaf.reader.domain.model.Book
import com.leaf.reader.domain.model.ReadingStatus
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.Timeout
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LeafDatabaseTest {
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val migrations = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), LeafDatabase::class.java)
    @get:Rule val rules: RuleChain = RuleChain.outerRule(Timeout.seconds(120)).around(migrations)
    private lateinit var database: LeafDatabase
    private lateinit var books: BookRepository
    private lateinit var annotations: AnnotationRepository
    private lateinit var reading: ReadingRepository
    private lateinit var shelves: ShelfRepository
    private lateinit var statistics: StatisticsRepository

    @Before fun setUp() {
        database = Room.inMemoryDatabaseBuilder(context, LeafDatabase::class.java).build()
        books = BookRepository(database)
        annotations = AnnotationRepository(database)
        reading = ReadingRepository(database)
        shelves = ShelfRepository(database)
        statistics = StatisticsRepository(database)
    }
    @After fun tearDown() = database.close()

    @Test fun deletingBookCascadesEveryDependentRecordButPreservesShelf() = runBlocking {
        val book = book()
        books.add(book)
        val shelfId = shelves.create("Favourites", "Keep for later")
        shelves.addBook(shelfId, book.id)
        reading.saveProgress(book.id, LOCATOR, 0.4)
        annotations.addBookmark(book.id, LOCATOR, "A favourite passage")
        annotations.addHighlight(book.id, LOCATOR, "Selected text", 0xFFFFD54FL, "A private note")
        statistics.recordInterval(book.id, 1_000, 2_000, 1_000)

        books.removeRecord(book.id)

        assertNull(books.get(book.id))
        assertNull(reading.getProgress(book.id))
        assertTrue(annotations.bookmarks(book.id).first().isEmpty())
        assertTrue(annotations.highlights(book.id).first().isEmpty())
        assertTrue(statistics.sessions.first().isEmpty())
        assertEquals(emptyList<String>(), shelves.shelves.first().single().bookIds)
        database.openHelper.readableDatabase.query("SELECT COUNT(*) FROM notes").use {
            assertTrue(it.moveToFirst())
            assertEquals(0, it.getInt(0))
        }
        assertEquals("Favourites", shelves.shelves.first().single().name)
    }

    @Test fun duplicateHashesAbortWithoutReplacingExistingBook() = runBlocking {
        val original = book()
        books.add(original)
        expectFailure<SQLiteConstraintException> {
            books.add(original.copy(id = id(), storagePath = "/managed/another.epub", title = "Duplicate"))
        }
        assertEquals(original.title, books.get(original.id)?.title)
        assertEquals(1, books.books.first().size)
    }

    @Test fun foreignKeysRejectOrphanAnnotationsAndMemberships() = runBlocking {
        expectFailure<SQLiteConstraintException> { annotations.addBookmark(id(), LOCATOR) }
        val shelf = shelves.create("Unread")
        expectFailure<SQLiteConstraintException> { shelves.addBook(shelf, id()) }
        assertEquals(0, shelves.shelves.first().single().count)
    }

    @Test fun relatedWritesRollBackTogether() = runBlocking {
        val book = book()
        expectFailure<IllegalStateException> {
            database.withTransaction {
                books.add(book)
                annotations.addHighlight(book.id, LOCATOR, "Text", 0xFFFFD54FL, "Note")
                throw IllegalStateException("Simulated transaction interruption")
            }
        }
        assertNull(books.get(book.id))
        assertTrue(annotations.highlights(book.id).first().isEmpty())
    }

    @Test fun bookmarksDeduplicateAndNotesEditThenCascade() = runBlocking {
        val book = book()
        books.add(book)
        val firstBookmark = annotations.addBookmark(book.id, LOCATOR, "Chapter")
        assertEquals(firstBookmark, annotations.addBookmark(book.id, LOCATOR))
        assertEquals(1, annotations.bookmarks(book.id).first().size)
        annotations.removeBookmark(firstBookmark)
        assertTrue(annotations.bookmarks(book.id).first().isEmpty())

        val highlight = annotations.addHighlight(book.id, LOCATOR, "Selected text", 0xFFFFD54FL, "First")
        val noteId = database.annotations().noteForHighlight(highlight)?.id
        annotations.updateHighlight(highlight, 0xFF81C784L, "Edited")
        assertEquals(noteId, database.annotations().noteForHighlight(highlight)?.id)
        assertEquals("Edited", annotations.highlights(book.id).first().single().note)
        assertEquals(0xFF81C784L, annotations.highlights(book.id).first().single().color)
        annotations.updateNote(highlight, null)
        assertNull(database.annotations().noteForHighlight(highlight))
        annotations.updateNote(highlight, "Again")
        annotations.deleteHighlight(highlight)
        assertNull(database.annotations().noteForHighlight(highlight))
    }

    @Test fun shelvesAllowMultipleMembershipsAndDeletionKeepsBooks() = runBlocking {
        val book = book()
        books.add(book)
        val first = shelves.create("Favourites")
        val second = shelves.create("Travel", "Books for a trip")
        shelves.addBook(first, book.id)
        shelves.addBook(first, book.id)
        shelves.addBook(second, book.id)
        assertEquals(listOf(1, 1), shelves.shelves.first().map { it.count })
        shelves.update(second, "Journeys", "A revised description")
        assertEquals("A revised description", shelves.shelves.first().first { it.id == second }.description)
        // Exercise the schema's NOCASE index directly, bypassing the repository's duplicate check.
        expectFailure<SQLiteConstraintException> {
            database.shelves().insert(ShelfEntity(id(), "favourites", "Duplicate", 1L))
        }
        shelves.removeBook(first, book.id)
        assertEquals(0, shelves.shelves.first().first { it.id == first }.count)
        shelves.delete(second)
        assertNotNull(books.get(book.id))
    }

    @Test fun fullLocatorRoundTripsAndInvalidProgressCannotReplaceIt() = runBlocking {
        val book = book()
        books.add(book)
        reading.saveProgress(book.id, LOCATOR, 0.6)
        assertEquals(LOCATOR, reading.getProgress(book.id)?.locatorJson)
        assertEquals(0.6, books.get(book.id)!!.progression, 0.0)
        assertEquals(ReadingStatus.READING, books.get(book.id)?.status)
        for (invalid in listOf(Double.NaN, Double.POSITIVE_INFINITY, -0.1, 1.1)) {
            expectFailure<IllegalArgumentException> { reading.saveProgress(book.id, "bad", invalid) }
        }
        assertEquals(LOCATOR, reading.getProgress(book.id)?.locatorJson)
        reading.saveProgress(book.id, LOCATOR, 1.0)
        assertEquals(ReadingStatus.COMPLETED, books.get(book.id)?.status)
    }

    @Test fun checkpointRetriesDoNotInflateStatistics() = runBlocking {
        val book = book()
        books.add(book)
        val checkpoint = id()
        statistics.recordInterval(book.id, 1_000, 2_000, 1_000, checkpoint)
        statistics.recordInterval(book.id, 1_000, 2_000, 1_000, checkpoint)
        assertEquals(1, statistics.sessions.first().size)
        assertEquals(1_000L, statistics.sessions.first().sumOf { it.activeDurationMs })
        expectFailure<IllegalArgumentException> { statistics.recordInterval(book.id, 1_000, 2_000, 2_000) }
        expectFailure<IllegalArgumentException> { statistics.recordInterval(book.id, 1_000, 2_001, 1_000, checkpoint) }
        assertEquals(1, statistics.sessions.first().size)
    }

    @Test fun initialExportedSchemaReopensWithoutLosingUserRecords() = runBlocking {
        val name = "leaf-schema-${id()}.db"
        val book = book()
        val highlight = id()
        val note = id()
        try {
            migrations.createDatabase(name, 1).apply {
                execSQL("INSERT INTO books (id,title,author,description,format,storagePath,sourceUri,coverPath,fileSize,contentHash,importedAt,lastOpenedAt,status) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    arrayOf<Any?>(book.id, book.title, book.author, book.description, "EPUB", book.storagePath, book.sourceUri, book.coverPath, book.fileSize, book.contentHash, book.importedAt, null, "READING"))
                execSQL("INSERT INTO reading_progress (bookId,locatorJson,progression,updatedAt) VALUES (?,?,?,?)", arrayOf<Any?>(book.id, LOCATOR, 0.7, 100L))
                execSQL("INSERT INTO highlights (id,bookId,locatorJson,selectedText,color,createdAt) VALUES (?,?,?,?,?,?)", arrayOf<Any?>(highlight, book.id, LOCATOR, "Saved passage", 0xFFFFD54FL, 100L))
                execSQL("INSERT INTO notes (id,highlightId,content,updatedAt) VALUES (?,?,?,?)", arrayOf<Any?>(note, highlight, "Preserve this note", 100L))
                close()
            }
            migrations.runMigrationsAndValidate(name, 1, true).close()
            val reopened = Room.databaseBuilder(context, LeafDatabase::class.java, name)
                .addMigrations(*LeafDatabase.ALL_MIGRATIONS).build()
            try {
                assertEquals(book.title, BookRepository(reopened).get(book.id)?.title)
                assertEquals(LOCATOR, ReadingRepository(reopened).getProgress(book.id)?.locatorJson)
                assertEquals("Preserve this note", AnnotationRepository(reopened).highlights(book.id).first().single().note)
            } finally { reopened.close() }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun unsupportedDowngradeFailsWithoutErasingExistingData() = runBlocking {
        val name = "leaf-future-schema-${id()}.db"
        val book = book()
        try {
            migrations.createDatabase(name, 1).apply {
                execSQL("INSERT INTO books (id,title,author,description,format,storagePath,sourceUri,coverPath,fileSize,contentHash,importedAt,lastOpenedAt,status) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    arrayOf<Any?>(book.id, book.title, book.author, null, "EPUB", book.storagePath, null, null, book.fileSize, book.contentHash, 100L, null, "UNREAD"))
                version = 99
                close()
            }
            val unsupported = Room.databaseBuilder(context, LeafDatabase::class.java, name)
                .addMigrations(*LeafDatabase.ALL_MIGRATIONS).build()
            try {
                expectFailure<IllegalStateException> { unsupported.books().get(book.id) }
            } finally { unsupported.close() }
            val raw = android.database.sqlite.SQLiteDatabase.openDatabase(
                context.getDatabasePath(name).absolutePath, null,
                android.database.sqlite.SQLiteDatabase.OPEN_READONLY)
            try {
                raw.rawQuery("SELECT title FROM books WHERE id = ?", arrayOf(book.id)).use {
                    assertTrue(it.moveToFirst())
                    assertEquals(book.title, it.getString(0))
                }
            } finally { raw.close() }
        } finally { context.deleteDatabase(name) }
    }

    private fun book() = Book(id(), "A test book", "Leaf tests", null, PublicationFormat.EPUB,
        "/managed/${id()}.epub", "content://test/book", null, 200, id().replace("-", "").repeat(2), 100)

    private fun id(): String = UUID.randomUUID().toString()

    private suspend inline fun <reified T : Throwable> expectFailure(noinline block: suspend () -> Unit) {
        try { block() } catch (error: Throwable) {
            if (error is T) return
            throw AssertionError("Expected ${T::class.java.simpleName}, got ${error.javaClass.simpleName}", error)
        }
        throw AssertionError("Expected ${T::class.java.simpleName}")
    }

    companion object {
        private const val LOCATOR = """{"href":"chapter.xhtml","type":"application/xhtml+xml","locations":{"fragments":["id-a"],"progression":0.6,"position":4},"text":{"highlight":"A saved passage — नमस्ते"}}"""
    }
}
