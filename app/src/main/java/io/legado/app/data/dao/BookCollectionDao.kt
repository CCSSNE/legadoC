package io.legado.app.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookCollection
import io.legado.app.data.entities.BookCollectionItem
import io.legado.app.data.entities.BookCollectionWithBooks
import kotlinx.coroutines.flow.Flow

@Dao
interface BookCollectionDao {

    @Transaction
    @Query("SELECT * FROM book_collections ORDER BY `order`, updatedTime DESC, collectionId")
    fun flowCollections(): Flow<List<BookCollectionWithBooks>>

    @Query("SELECT * FROM book_collections WHERE collectionId = :collectionId")
    fun getCollection(collectionId: Long): BookCollection?

    @Transaction
    @Query(
        """
        SELECT books.* FROM books
        INNER JOIN book_collection_items ON books.bookUrl = book_collection_items.bookUrl
        WHERE book_collection_items.collectionId = :collectionId
        ORDER BY book_collection_items.`order`, book_collection_items.addedTime
        """
    )
    fun flowBooks(collectionId: Long): Flow<List<Book>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(collection: BookCollection): Long

    @Update
    fun update(vararg collection: BookCollection)

    @Delete
    fun delete(vararg collection: BookCollection)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertItems(items: List<BookCollectionItem>)

    @Query("SELECT COALESCE(MAX(`order`), 0) FROM book_collections")
    fun maxCollectionOrder(): Int

    @Query("SELECT COALESCE(MAX(`order`), 0) FROM book_collection_items WHERE collectionId = :collectionId")
    fun maxItemOrder(collectionId: Long): Int

    @Query("UPDATE book_collections SET updatedTime = :updatedTime WHERE collectionId = :collectionId")
    fun updateCollectionTime(collectionId: Long, updatedTime: Long)

    @Transaction
    fun createCollection(name: String): Long {
        val now = System.currentTimeMillis()
        return insert(
            BookCollection(
                name = name,
                order = maxCollectionOrder() + 1,
                createdTime = now,
                updatedTime = now
            )
        )
    }

    @Transaction
    fun addBookUrls(collectionId: Long, bookUrls: List<String>) {
        if (bookUrls.isEmpty()) return
        val now = System.currentTimeMillis()
        val startOrder = maxItemOrder(collectionId) + 1
        insertItems(
            bookUrls.distinct().mapIndexed { index, bookUrl ->
                BookCollectionItem(
                    collectionId = collectionId,
                    bookUrl = bookUrl,
                    order = startOrder + index,
                    addedTime = now + index
                )
            }
        )
        updateCollectionTime(collectionId, now)
    }
}
