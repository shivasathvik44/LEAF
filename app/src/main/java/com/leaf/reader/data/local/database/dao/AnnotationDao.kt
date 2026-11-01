package com.leaf.reader.data.local.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.leaf.reader.data.local.database.entity.BookmarkEntity
import com.leaf.reader.data.local.database.entity.HighlightEntity
import com.leaf.reader.data.local.database.entity.HighlightWithNote
import com.leaf.reader.data.local.database.entity.NoteEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AnnotationDao {
    @Query("SELECT * FROM bookmarks WHERE bookId = :bookId ORDER BY createdAt DESC, id")
    fun observeBookmarks(bookId: String): Flow<List<BookmarkEntity>>
    @Query("SELECT * FROM bookmarks WHERE bookId = :bookId AND locatorJson = :locatorJson LIMIT 1")
    suspend fun bookmarkAt(bookId: String, locatorJson: String): BookmarkEntity?
    @Insert suspend fun insertBookmark(bookmark: BookmarkEntity)
    @Query("DELETE FROM bookmarks WHERE id = :id") suspend fun deleteBookmark(id: String)

    @Transaction @Query("SELECT * FROM highlights WHERE bookId = :bookId ORDER BY createdAt DESC, id")
    fun observeHighlights(bookId: String): Flow<List<HighlightWithNote>>
    @Query("SELECT * FROM highlights WHERE id = :id") suspend fun getHighlight(id: String): HighlightEntity?
    @Insert suspend fun insertHighlight(highlight: HighlightEntity)
    @Query("UPDATE highlights SET color = :color WHERE id = :id") suspend fun updateColor(id: String, color: Long)
    @Query("DELETE FROM highlights WHERE id = :id") suspend fun deleteHighlight(id: String)

    @Query("SELECT * FROM notes WHERE highlightId = :highlightId LIMIT 1")
    suspend fun noteForHighlight(highlightId: String): NoteEntity?
    @Upsert suspend fun saveNote(note: NoteEntity)
    @Query("DELETE FROM notes WHERE highlightId = :highlightId") suspend fun deleteNote(highlightId: String)
}
