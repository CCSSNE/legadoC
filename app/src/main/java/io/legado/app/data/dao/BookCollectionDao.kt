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
import io.legado.app.data.entities.BookCollectionChild
import io.legado.app.data.entities.BookCollectionItem
import io.legado.app.data.entities.BookCollectionWithItems
import kotlinx.coroutines.flow.Flow

@Dao
interface BookCollectionDao {

    @Transaction
    @Query("SELECT * FROM book_collections ORDER BY `order`, updatedTime DESC, collectionId")
    fun flowCollections(): Flow<List<BookCollectionWithItems>>

    @Transaction
    @Query(
        """
        SELECT * FROM book_collections
        WHERE collectionId NOT IN (
            SELECT childCollectionId FROM book_collection_children
        )
        ORDER BY `order`, updatedTime DESC, collectionId
        """
    )
    fun flowRootCollections(): Flow<List<BookCollectionWithItems>>

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

    @Transaction
    @Query(
        """
        SELECT book_collections.* FROM book_collections
        INNER JOIN book_collection_children
            ON book_collections.collectionId = book_collection_children.childCollectionId
        WHERE book_collection_children.parentCollectionId = :collectionId
        ORDER BY book_collection_children.`order`, book_collection_children.addedTime
        """
    )
    fun flowChildCollections(collectionId: Long): Flow<List<BookCollectionWithItems>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(collection: BookCollection): Long

    @Update
    fun update(vararg collection: BookCollection)

    @Delete
    fun delete(vararg collection: BookCollection)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertItems(items: List<BookCollectionItem>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertChildren(children: List<BookCollectionChild>)

    @Query("SELECT COALESCE(MAX(`order`), 0) FROM book_collections")
    fun maxCollectionOrder(): Int

    @Query("SELECT COALESCE(MAX(`order`), 0) FROM book_collection_items WHERE collectionId = :collectionId")
    fun maxItemOrder(collectionId: Long): Int

    @Query("SELECT COALESCE(MAX(`order`), 0) FROM book_collection_children WHERE parentCollectionId = :collectionId")
    fun maxChildOrder(collectionId: Long): Int

    @Query("UPDATE book_collections SET updatedTime = :updatedTime WHERE collectionId = :collectionId")
    fun updateCollectionTime(collectionId: Long, updatedTime: Long)

    @Query("SELECT DISTINCT bookUrl FROM book_collection_items")
    fun flowCollectedBookUrls(): Flow<List<String>>

    @Query(
        """
        WITH RECURSIVE descendants(collectionId) AS (
            SELECT childCollectionId
            FROM book_collection_children
            WHERE parentCollectionId = :collectionId
            UNION
            SELECT book_collection_children.childCollectionId
            FROM book_collection_children
            INNER JOIN descendants
                ON book_collection_children.parentCollectionId = descendants.collectionId
        )
        SELECT collectionId FROM descendants
        """
    )
    fun descendantCollectionIds(collectionId: Long): List<Long>

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

    @Transaction
    fun addChildCollectionIds(parentCollectionId: Long, childCollectionIds: List<Long>) {
        if (parentCollectionId <= 0 || childCollectionIds.isEmpty()) return
        val validChildIds = childCollectionIds.distinct().filter { childCollectionId ->
            childCollectionId > 0 &&
                    childCollectionId != parentCollectionId &&
                    parentCollectionId !in descendantCollectionIds(childCollectionId)
        }
        if (validChildIds.isEmpty()) return
        val now = System.currentTimeMillis()
        val startOrder = maxChildOrder(parentCollectionId) + 1
        insertChildren(
            validChildIds.mapIndexed { index, childCollectionId ->
                BookCollectionChild(
                    parentCollectionId = parentCollectionId,
                    childCollectionId = childCollectionId,
                    order = startOrder + index,
                    addedTime = now + index
                )
            }
        )
        updateCollectionTime(parentCollectionId, now)
    }
}
