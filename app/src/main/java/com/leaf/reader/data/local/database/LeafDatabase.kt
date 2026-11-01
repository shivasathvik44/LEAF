package com.leaf.reader.data.local.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import com.leaf.reader.data.local.database.dao.AnnotationDao
import com.leaf.reader.data.local.database.dao.BookDao
import com.leaf.reader.data.local.database.dao.ReadingDao
import com.leaf.reader.data.local.database.dao.ShelfDao
import com.leaf.reader.data.local.database.dao.StatisticsDao
import com.leaf.reader.data.local.database.entity.BookEntity
import com.leaf.reader.data.local.database.entity.BookShelfCrossRef
import com.leaf.reader.data.local.database.entity.BookmarkEntity
import com.leaf.reader.data.local.database.entity.HighlightEntity
import com.leaf.reader.data.local.database.entity.NoteEntity
import com.leaf.reader.data.local.database.entity.ReadingProgressEntity
import com.leaf.reader.data.local.database.entity.ReadingSessionEntity
import com.leaf.reader.data.local.database.entity.ShelfEntity

@Database(entities = [BookEntity::class, ReadingProgressEntity::class, BookmarkEntity::class,
    HighlightEntity::class, NoteEntity::class, ShelfEntity::class, BookShelfCrossRef::class,
    ReadingSessionEntity::class], version = 1, exportSchema = true)
@TypeConverters(EnumConverters::class)
abstract class LeafDatabase : RoomDatabase() {
    abstract fun books(): BookDao
    abstract fun reading(): ReadingDao
    abstract fun annotations(): AnnotationDao
    abstract fun shelves(): ShelfDao
    abstract fun statistics(): StatisticsDao

    companion object {
        const val FILE_NAME = "leaf.db"
        // Version 1 is the initial schema. Every future version needs a data-preserving migration.
        val ALL_MIGRATIONS: Array<Migration> = emptyArray()
    }
}
