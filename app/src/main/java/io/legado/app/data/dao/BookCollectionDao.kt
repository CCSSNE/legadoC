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

    @Query("DELETE FROM book_collections WHERE collectionId IN (:collectionIds)")
    fun deleteByIds(collectionIds: List<Long>)

    @Query("SELECT bookUrl FROM book_collection_items WHERE collectionId = :collectionId ORDER BY `order`, addedTime")
    fun bookUrlsInCollection(collectionId: Long): List<String>

    @Query("SELECT childCollectionId FROM book_collection_children WHERE parentCollectionId = :collectionId ORDER BY `order`, addedTime")
    fun childCollectionIds(collectionId: Long): List<Long>

    @Query("SELECT parentCollectionId FROM book_collection_children WHERE childCollectionId = :collectionId")
    fun parentCollectionIds(collectionId: Long): List<Long>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertItems(items: List<BookCollectionItem>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertChildren(children: List<BookCollectionChild>)

    @Query("DELETE FROM book_collection_items WHERE bookUrl IN (:bookUrls)")
    fun deleteItemsByBookUrls(bookUrls: List<String>)

    @Query("DELETE FROM book_collection_items WHERE collectionId = :collectionId")
    fun deleteItemsByCollectionId(collectionId: Long)

    @Query("DELETE FROM book_collection_children WHERE childCollectionId IN (:childCollectionIds)")
    fun deleteParentsByChildCollectionIds(childCollectionIds: List<Long>)

    @Query("DELETE FROM book_collection_children WHERE parentCollectionId = :collectionId")
    fun deleteChildrenByParentCollectionId(collectionId: Long)

    @Query(
        """
        DELETE FROM book_collection_items
        WHERE EXISTS (
            SELECT 1 FROM book_collection_items AS newer
            WHERE newer.bookUrl = book_collection_items.bookUrl
                AND (
                    newer.addedTime > book_collection_items.addedTime
                    OR (
                        newer.addedTime = book_collection_items.addedTime
                        AND newer.collectionId > book_collection_items.collectionId
                    )
                )
        )
        """
    )
    fun deleteDuplicateBookItems()

    @Query(
        """
        DELETE FROM book_collection_children
        WHERE EXISTS (
            SELECT 1 FROM book_collection_children AS newer
            WHERE newer.childCollectionId = book_collection_children.childCollectionId
                AND (
                    newer.addedTime > book_collection_children.addedTime
                    OR (
                        newer.addedTime = book_collection_children.addedTime
                        AND newer.parentCollectionId > book_collection_children.parentCollectionId
                    )
                )
        )
        """
    )
    fun deleteDuplicateChildCollections()

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
        val uniqueBookUrls = bookUrls.distinct()
        if (uniqueBookUrls.isEmpty()) return
        val now = System.currentTimeMillis()
        deleteItemsByBookUrls(uniqueBookUrls)
        val startOrder = maxItemOrder(collectionId) + 1
        insertItems(
            uniqueBookUrls.mapIndexed { index, bookUrl ->
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
    fun normalizeLocations() {
        deleteDuplicateBookItems()
        deleteDuplicateChildCollections()
    }

    @Transaction
    fun deleteCollectionsAndRelease(collectionIds: List<Long>) {
        collectionIds.distinct().filter { it > 0 }.forEach { collectionId ->
            deleteCollectionAndRelease(collectionId)
        }
    }

    @Transaction
    fun deleteCollectionAndRelease(collectionId: Long) {
        val bookUrls = bookUrlsInCollection(collectionId)
        val childIds = childCollectionIds(collectionId)
        val parentIds = parentCollectionIds(collectionId).filter { it > 0 && it != collectionId }
        if (parentIds.isEmpty()) {
            deleteItemsByCollectionId(collectionId)
            deleteChildrenByParentCollectionId(collectionId)
        } else {
            parentIds.forEach { parentId ->
                addBookUrls(parentId, bookUrls)
                addChildCollectionIds(parentId, childIds)
            }
        }
        deleteByIds(listOf(collectionId))
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
        deleteParentsByChildCollectionIds(validChildIds)
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
