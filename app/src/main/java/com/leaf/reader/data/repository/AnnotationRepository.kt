package com.leaf.reader.data.repository

import androidx.room.withTransaction
import com.leaf.reader.data.local.database.LeafDatabase
import com.leaf.reader.data.local.database.entity.BookmarkEntity
import com.leaf.reader.data.local.database.entity.HighlightEntity
import com.leaf.reader.data.local.database.entity.NoteEntity
import com.leaf.reader.domain.model.Bookmark
import com.leaf.reader.domain.model.Highlight
import com.leaf.reader.domain.model.AnnotationLimits
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

@Singleton
class AnnotationRepository @Inject constructor(private val database: LeafDatabase) {
    fun bookmarks(bookId: String): Flow<List<Bookmark>> = database.annotations().observeBookmarks(bookId).map { rows ->
        rows.map { Bookmark(it.id, it.bookId, it.locatorJson, it.label, it.createdAt) }
    }
    fun highlights(bookId: String): Flow<List<Highlight>> = database.annotations().observeHighlights(bookId).map { rows ->
        rows.map { row -> row.highlight.let { Highlight(it.id, it.bookId, it.locatorJson, it.selectedText, it.color, it.createdAt, row.note?.content) } }
    }

    suspend fun addBookmark(bookId: String, locatorJson: String, label: String? = null): String {
        require(locatorJson.isNotBlank()) { "The complete locator JSON is required." }
        AnnotationLimits.validateLocator(locatorJson)
        AnnotationLimits.validateLabel(label)
        return database.withTransaction {
            database.annotations().bookmarkAt(bookId, locatorJson)?.id ?: UUID.randomUUID().toString().also { id ->
                database.annotations().insertBookmark(BookmarkEntity(id, bookId, locatorJson, label, System.currentTimeMillis()))
            }
        }
    }
    suspend fun removeBookmark(id: String) = database.annotations().deleteBookmark(id)

    suspend fun addHighlight(bookId: String, locatorJson: String, selectedText: String, color: Long, note: String? = null): String {
        require(locatorJson.isNotBlank()) { "The selection locator JSON is required." }
        require(selectedText.isNotBlank()) { "Select text before creating a highlight." }
        AnnotationLimits.validateLocator(locatorJson)
        AnnotationLimits.validateSelection(selectedText)
        AnnotationLimits.validateNote(note)
        require(color in 0L..0xFFFFFFFFL) { "Use an ARGB highlight color." }
        val id = UUID.randomUUID().toString()
        database.withTransaction {
            database.annotations().insertHighlight(HighlightEntity(id, bookId, locatorJson, selectedText, color, System.currentTimeMillis()))
            saveNote(id, note)
        }
        return id
    }

    suspend fun updateHighlight(id: String, color: Long, note: String?) {
        AnnotationLimits.validateNote(note)
        require(color in 0L..0xFFFFFFFFL) { "Use an ARGB highlight color." }
        database.withTransaction {
            requireNotNull(database.annotations().getHighlight(id)) { "This highlight no longer exists." }
            database.annotations().updateColor(id, color)
            saveNote(id, note)
        }
    }
    suspend fun updateNote(highlightId: String, content: String?) {
        AnnotationLimits.validateNote(content)
        database.withTransaction {
            requireNotNull(database.annotations().getHighlight(highlightId)) { "This highlight no longer exists." }
            saveNote(highlightId, content)
        }
    }
    suspend fun deleteHighlight(id: String) = database.annotations().deleteHighlight(id)

    private suspend fun saveNote(highlightId: String, content: String?) {
        if (content.isNullOrBlank()) {
            database.annotations().deleteNote(highlightId)
        } else {
            val id = database.annotations().noteForHighlight(highlightId)?.id ?: UUID.randomUUID().toString()
            database.annotations().saveNote(NoteEntity(id, highlightId, content, System.currentTimeMillis()))
        }
    }
}
