package com.leaf.reader.data.repository

import com.leaf.reader.data.local.database.LeafDatabase
import com.leaf.reader.data.local.database.entity.BookShelfCrossRef
import com.leaf.reader.data.local.database.entity.ShelfEntity
import com.leaf.reader.domain.model.Shelf
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

@Singleton
class ShelfRepository @Inject constructor(private val database: LeafDatabase) {
    val shelves: Flow<List<Shelf>> = database.shelves().observeShelves().map { rows -> rows.map { row ->
        Shelf(row.shelf.id, row.shelf.name, row.shelf.description, row.shelf.createdAt, row.memberships.map { it.bookId }.sorted())
    } }

    suspend fun create(name: String, description: String = ""): String {
        val normalized = name.trim()
        require(normalized.isNotEmpty()) { "Give your shelf a name." }
        val id = UUID.randomUUID().toString()
        database.shelves().insert(ShelfEntity(id, normalized, description, System.currentTimeMillis()))
        return id
    }
    suspend fun update(id: String, name: String, description: String) {
        val normalized = name.trim()
        require(normalized.isNotEmpty()) { "Give your shelf a name." }
        database.shelves().update(id, normalized, description)
    }
    suspend fun delete(id: String) = database.shelves().delete(id)
    suspend fun addBook(shelfId: String, bookId: String) = database.shelves().addBook(BookShelfCrossRef(bookId, shelfId))
    suspend fun removeBook(shelfId: String, bookId: String) = database.shelves().removeBook(shelfId, bookId)
}
