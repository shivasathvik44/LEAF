package com.leaf.reader.data.local.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.leaf.reader.data.local.database.entity.BookShelfCrossRef
import com.leaf.reader.data.local.database.entity.ShelfEntity
import com.leaf.reader.data.local.database.entity.ShelfWithMemberships
import kotlinx.coroutines.flow.Flow

@Dao
interface ShelfDao {
    @Transaction @Query("SELECT * FROM shelves ORDER BY name COLLATE NOCASE, id")
    fun observeShelves(): Flow<List<ShelfWithMemberships>>
    @Insert suspend fun insert(shelf: ShelfEntity)
    @Query("UPDATE shelves SET name = :name, description = :description WHERE id = :id")
    suspend fun update(id: String, name: String, description: String)
    @Query("DELETE FROM shelves WHERE id = :id") suspend fun delete(id: String)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun addBook(membership: BookShelfCrossRef)
    @Query("DELETE FROM book_shelf WHERE shelfId = :shelfId AND bookId = :bookId")
    suspend fun removeBook(shelfId: String, bookId: String)
}
