package com.leaf.reader.data.repository

import androidx.room.withTransaction
import com.leaf.reader.data.local.database.LeafDatabase
import com.leaf.reader.data.local.database.entity.BookShelfCrossRef
import com.leaf.reader.data.local.database.entity.ShelfEntity
import com.leaf.reader.domain.model.Shelf
import com.leaf.reader.domain.model.ShelfLimits
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class ShelfNameInUseException : IllegalArgumentException("A shelf with that name already exists. Choose another name.")

@Singleton
class ShelfRepository @Inject constructor(private val database: LeafDatabase) {
    val shelves: Flow<List<Shelf>> = database.shelves().observeShelves().map { rows -> rows.map { row ->
        Shelf(row.shelf.id, row.shelf.name, row.shelf.description, row.shelf.createdAt, row.memberships.map { it.bookId }.sorted())
    } }

    /** Creating from a book picker saves the shelf and its first membership together. */
    suspend fun create(name: String, description: String = "", bookId: String? = null): String {
        ShelfLimits.validate(name, description)
        val normalized = name.trim()
        val id = UUID.randomUUID().toString()
        database.withTransaction {
            if (database.shelves().findByName(normalized) != null) throw ShelfNameInUseException()
            database.shelves().insert(ShelfEntity(id, normalized, description, System.currentTimeMillis()))
            bookId?.let { database.shelves().addBook(BookShelfCrossRef(it, id)) }
        }
        return id
    }
    suspend fun update(id: String, name: String, description: String) {
        ShelfLimits.validate(name, description)
        val normalized = name.trim()
        database.withTransaction {
            requireNotNull(database.shelves().get(id)) { "This shelf is no longer available." }
            if (database.shelves().findByName(normalized)?.id?.let { it != id } == true) throw ShelfNameInUseException()
            database.shelves().update(id, normalized, description)
        }
    }
    suspend fun delete(id: String) = database.shelves().delete(id)
    suspend fun addBook(shelfId: String, bookId: String) = database.shelves().addBook(BookShelfCrossRef(bookId, shelfId))
    suspend fun removeBook(shelfId: String, bookId: String) = database.shelves().removeBook(shelfId, bookId)
}
