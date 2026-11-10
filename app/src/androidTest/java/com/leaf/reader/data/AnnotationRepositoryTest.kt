package com.leaf.reader.data

import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.leaf.reader.core.util.PublicationFormat
import com.leaf.reader.data.local.database.LeafDatabase
import com.leaf.reader.data.repository.AnnotationRepository
import com.leaf.reader.data.repository.BookRepository
import com.leaf.reader.domain.model.AnnotationLimits
import com.leaf.reader.domain.model.Book
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

/** File-backed Room checks. Each test owns its database and never touches the app's library. */
@RunWith(AndroidJUnit4::class)
class AnnotationRepositoryTest {
    @get:Rule val testTimeout: TestRule = Timeout.seconds(120)
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var directory: File
    private lateinit var database: LeafDatabase
    private lateinit var annotations: AnnotationRepository
    private lateinit var bookId: String

    @Before fun setUp() = runBlocking {
        directory = File(context.cacheDir, "annotation-repository-tests/${UUID.randomUUID()}").apply { check(mkdirs()) }
        openDatabase()
        bookId = UUID.randomUUID().toString()
        BookRepository(database).add(Book(bookId, "Annotation fixture", "Leaf fixture contributors", null,
            PublicationFormat.EPUB, File(directory, "book.epub").absolutePath, null, null,
            20L, "a".repeat(64), 100L))
    }

    @After fun tearDown() {
        if (this::database.isInitialized) database.close()
        if (this::directory.isInitialized) directory.deleteRecursively()
    }

    @Test fun editedHighlightAndNoteReopenWithFullLocatorAndStableIdentities() = runBlocking {
        val id = annotations.addHighlight(bookId, LOCATOR, "A silver fern", YELLOW, "First note — नमस्ते")
        val initial = annotations.highlights(bookId).first().single()
        val originalNote = requireNotNull(database.annotations().noteForHighlight(id))
        annotations.updateHighlight(id, GREEN, "Edited note 🌿")

        reopenDatabase()

        val restored = annotations.highlights(bookId).first().single()
        val restoredNote = requireNotNull(database.annotations().noteForHighlight(id))
        assertEquals(id, restored.id)
        assertEquals(LOCATOR, restored.locatorJson)
        assertEquals(initial.selectedText, restored.selectedText)
        assertEquals(initial.createdAt, restored.createdAt)
        assertEquals(GREEN, restored.color)
        assertEquals("Edited note 🌿", restored.note)
        assertEquals(originalNote.id, restoredNote.id)
        assertTrue(restoredNote.updatedAt >= originalNote.updatedAt)
    }

    @Test fun deletingAHighlightCascadesOnlyItsOwnNote() = runBlocking {
        val first = annotations.addHighlight(bookId, LOCATOR, "A silver fern", YELLOW, "Remove this note")
        val second = annotations.addHighlight(bookId, OTHER_LOCATOR, "The river path", GREEN, "Keep this note")
        annotations.deleteHighlight(first)

        reopenDatabase()

        assertNull(database.annotations().getHighlight(first))
        assertNull(database.annotations().noteForHighlight(first))
        assertEquals(listOf(second), annotations.highlights(bookId).first().map { it.id })
        assertEquals("Keep this note", database.annotations().noteForHighlight(second)?.content)
        assertNotNull(BookRepository(database).get(bookId))
    }

    @Test fun clearingANoteKeepsTheHighlightAndAllowsLaterNoteCreation() = runBlocking {
        val id = annotations.addHighlight(bookId, LOCATOR, "A silver fern", YELLOW, "Original")
        annotations.updateNote(id, " \n\t")
        assertNull(database.annotations().noteForHighlight(id))
        assertNull(annotations.highlights(bookId).first().single().note)
        assertEquals(YELLOW, annotations.highlights(bookId).first().single().color)
        annotations.updateNote(id, "Written again")

        reopenDatabase()

        assertEquals("Written again", annotations.highlights(bookId).first().single().note)
        assertEquals(LOCATOR, annotations.highlights(bookId).first().single().locatorJson)
    }

    @Test fun oversizedNewAnnotationsDoNotLeavePartialRowsOrAlterExistingRecords() = runBlocking {
        val id = annotations.addHighlight(bookId, LOCATOR, "Keep this passage", GREEN, "Keep this note")
        val original = annotations.highlights(bookId).first().single()
        val originalNote = database.annotations().noteForHighlight(id)
        val bookmark = annotations.addBookmark(bookId, LOCATOR, "Keep this label")

        expectFailure<IllegalArgumentException> {
            annotations.addHighlight(bookId, OTHER_LOCATOR, "Text", YELLOW, "n".repeat(AnnotationLimits.MAX_NOTE_BYTES + 1))
        }
        expectFailure<IllegalArgumentException> {
            annotations.addHighlight(bookId, OTHER_LOCATOR, "s".repeat(AnnotationLimits.MAX_TEXT_BYTES + 1), YELLOW)
        }
        expectFailure<IllegalArgumentException> {
            annotations.addHighlight(bookId, "l".repeat(AnnotationLimits.MAX_LOCATOR_BYTES + 1), "Text", YELLOW)
        }
        expectFailure<IllegalArgumentException> {
            annotations.addBookmark(bookId, OTHER_LOCATOR, "b".repeat(AnnotationLimits.MAX_LABEL_BYTES + 1))
        }
        expectFailure<IllegalArgumentException> {
            annotations.addBookmark(bookId, "l".repeat(AnnotationLimits.MAX_LOCATOR_BYTES + 1), "Label")
        }

        reopenDatabase()

        assertEquals(listOf(original), annotations.highlights(bookId).first())
        assertEquals(originalNote, database.annotations().noteForHighlight(id))
        assertEquals(listOf(bookmark), annotations.bookmarks(bookId).first().map { it.id })
        assertEquals("Keep this label", annotations.bookmarks(bookId).first().single().label)
    }

    @Test fun oversizedNoteEditsCannotChangeExistingColorOrNote() = runBlocking {
        val id = annotations.addHighlight(bookId, LOCATOR, "A silver fern", YELLOW, "Keep me")
        val original = annotations.highlights(bookId).first().single()
        val note = database.annotations().noteForHighlight(id)
        val tooLarge = "🌿".repeat(AnnotationLimits.MAX_NOTE_BYTES / 4) + "x"
        expectFailure<IllegalArgumentException> { annotations.updateHighlight(id, GREEN, tooLarge) }
        expectFailure<IllegalArgumentException> { annotations.updateNote(id, tooLarge) }

        reopenDatabase()

        assertEquals(original, annotations.highlights(bookId).first().single())
        assertEquals(note, database.annotations().noteForHighlight(id))
    }

    @Test fun databaseFailureRollsBackHighlightAndNoteCreationTogether() = runBlocking {
        database.openHelper.writableDatabase.execSQL("""
            CREATE TRIGGER abort_annotation_note_insert BEFORE INSERT ON notes
            BEGIN SELECT RAISE(ABORT, 'test note insertion failure'); END
        """.trimIndent())
        try {
            expectFailure<SQLiteConstraintException> { annotations.addHighlight(bookId, LOCATOR, "Text", YELLOW, "Note") }
            assertTrue(annotations.highlights(bookId).first().isEmpty())
            assertEquals(0, noteCount())
            assertNotNull(BookRepository(database).get(bookId))
        } finally {
            database.openHelper.writableDatabase.execSQL("DROP TRIGGER abort_annotation_note_insert")
        }
        val saved = annotations.addHighlight(bookId, LOCATOR, "Text", YELLOW, "Retry succeeds")
        assertEquals(saved, annotations.highlights(bookId).first().single().id)
        assertEquals("Retry succeeds", database.annotations().noteForHighlight(saved)?.content)
    }

    @Test fun databaseFailureDuringNoteEditAlsoRollsBackTheColorChange() = runBlocking {
        val id = annotations.addHighlight(bookId, LOCATOR, "Text", YELLOW, "Original note")
        val original = annotations.highlights(bookId).first().single()
        val note = database.annotations().noteForHighlight(id)
        database.openHelper.writableDatabase.execSQL("""
            CREATE TRIGGER abort_annotation_note_update BEFORE UPDATE ON notes
            BEGIN SELECT RAISE(ABORT, 'test note update failure'); END
        """.trimIndent())
        try {
            expectFailure<SQLiteConstraintException> { annotations.updateHighlight(id, GREEN, "Must roll back") }
            assertEquals(original, annotations.highlights(bookId).first().single())
            assertEquals(note, database.annotations().noteForHighlight(id))
        } finally {
            database.openHelper.writableDatabase.execSQL("DROP TRIGGER abort_annotation_note_update")
        }
        annotations.updateHighlight(id, GREEN, "Retry succeeds")
        assertEquals(GREEN, annotations.highlights(bookId).first().single().color)
        assertEquals("Retry succeeds", database.annotations().noteForHighlight(id)?.content)
    }

    private fun openDatabase() {
        database = Room.databaseBuilder(context, LeafDatabase::class.java, File(directory, "annotations.db").absolutePath)
            .addMigrations(*LeafDatabase.ALL_MIGRATIONS).build()
        annotations = AnnotationRepository(database)
    }
    private fun reopenDatabase() { database.close(); openDatabase() }
    private fun noteCount(): Int = database.openHelper.readableDatabase.query("SELECT COUNT(*) FROM notes").use {
        check(it.moveToFirst()); it.getInt(0)
    }
    private suspend inline fun <reified T : Throwable> expectFailure(noinline block: suspend () -> Unit) {
        try { block() }
        catch (failure: Throwable) {
            if (failure is T) return
            throw AssertionError("Expected ${T::class.java.simpleName}, got ${failure.javaClass.simpleName}", failure)
        }
        throw AssertionError("Expected ${T::class.java.simpleName}")
    }

    companion object {
        private const val YELLOW = 0xFFFFD54FL
        private const val GREEN = 0xFF81C784L
        private const val LOCATOR = """{"href":"OEBPS/chapter1.xhtml","type":"application/xhtml+xml","locations":{"fragments":["id=passage"],"progression":0.2,"position":1},"text":{"before":"A ","highlight":"silver fern","after":" remembers the light."}}"""
        private const val OTHER_LOCATOR = """{"href":"OEBPS/chapter2.xhtml","type":"application/xhtml+xml","locations":{"fragments":["id=passage"],"progression":0.3,"position":2},"text":{"highlight":"river path"}}"""
    }
}
