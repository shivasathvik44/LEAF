package com.leaf.reader.data.local.database.entity

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation
import com.leaf.reader.core.util.PublicationFormat
import com.leaf.reader.domain.model.ReadingStatus

@Entity(tableName = "books", indices = [
    Index(value = ["contentHash"], unique = true),
    Index(value = ["storagePath"], unique = true),
    Index("title"), Index("author"), Index("importedAt"), Index("lastOpenedAt"), Index("status"),
])
data class BookEntity(
    @PrimaryKey val id: String,
    val title: String,
    val author: String,
    val description: String?,
    val format: PublicationFormat,
    val storagePath: String,
    val sourceUri: String?,
    val coverPath: String?,
    val fileSize: Long,
    val contentHash: String,
    val importedAt: Long,
    val lastOpenedAt: Long?,
    val status: ReadingStatus,
)

@Entity(tableName = "reading_progress", foreignKeys = [ForeignKey(
    entity = BookEntity::class, parentColumns = ["id"], childColumns = ["bookId"],
    onDelete = ForeignKey.CASCADE,
)])
data class ReadingProgressEntity(
    @PrimaryKey val bookId: String,
    val locatorJson: String,
    val progression: Double,
    val updatedAt: Long,
)

@Entity(tableName = "bookmarks", foreignKeys = [ForeignKey(
    entity = BookEntity::class, parentColumns = ["id"], childColumns = ["bookId"],
    onDelete = ForeignKey.CASCADE,
)], indices = [Index("bookId"), Index(value = ["bookId", "locatorJson"], unique = true)])
data class BookmarkEntity(
    @PrimaryKey val id: String,
    val bookId: String,
    val locatorJson: String,
    val label: String?,
    val createdAt: Long,
)

@Entity(tableName = "highlights", foreignKeys = [ForeignKey(
    entity = BookEntity::class, parentColumns = ["id"], childColumns = ["bookId"],
    onDelete = ForeignKey.CASCADE,
)], indices = [Index("bookId")])
data class HighlightEntity(
    @PrimaryKey val id: String,
    val bookId: String,
    val locatorJson: String,
    val selectedText: String,
    val color: Long,
    val createdAt: Long,
)

@Entity(tableName = "notes", foreignKeys = [ForeignKey(
    entity = HighlightEntity::class, parentColumns = ["id"], childColumns = ["highlightId"],
    onDelete = ForeignKey.CASCADE,
)], indices = [Index(value = ["highlightId"], unique = true)])
data class NoteEntity(
    @PrimaryKey val id: String,
    val highlightId: String,
    val content: String,
    val updatedAt: Long,
)

@Entity(tableName = "shelves", indices = [Index(value = ["name"], unique = true)])
data class ShelfEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(collate = ColumnInfo.NOCASE) val name: String,
    val description: String,
    val createdAt: Long,
)

@Entity(tableName = "book_shelf", primaryKeys = ["bookId", "shelfId"], foreignKeys = [
    ForeignKey(entity = BookEntity::class, parentColumns = ["id"], childColumns = ["bookId"], onDelete = ForeignKey.CASCADE),
    ForeignKey(entity = ShelfEntity::class, parentColumns = ["id"], childColumns = ["shelfId"], onDelete = ForeignKey.CASCADE),
], indices = [Index("shelfId")])
data class BookShelfCrossRef(val bookId: String, val shelfId: String)

@Entity(tableName = "reading_sessions", foreignKeys = [ForeignKey(
    entity = BookEntity::class, parentColumns = ["id"], childColumns = ["bookId"],
    onDelete = ForeignKey.CASCADE,
)], indices = [Index("bookId"), Index("startedAt"), Index("endedAt")])
data class ReadingSessionEntity(
    @PrimaryKey val id: String,
    val bookId: String,
    val startedAt: Long,
    val endedAt: Long,
    val activeDurationMs: Long,
)

data class BookWithProgress(
    @Embedded val book: BookEntity,
    @Relation(parentColumn = "id", entityColumn = "bookId") val progress: ReadingProgressEntity?,
)

data class HighlightWithNote(
    @Embedded val highlight: HighlightEntity,
    @Relation(parentColumn = "id", entityColumn = "highlightId") val note: NoteEntity?,
)

data class ShelfWithMemberships(
    @Embedded val shelf: ShelfEntity,
    @Relation(parentColumn = "id", entityColumn = "shelfId") val memberships: List<BookShelfCrossRef>,
)
