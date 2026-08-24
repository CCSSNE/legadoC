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
import io.legado.app.data.entities.BookShortcut
import io.legado.app.data.entities.ShelfEntry
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
          AND book_collection_items.entryType = ${BookCollectionItem.TYPE_BOOK}
        ORDER BY book_collection_items.`order`, book_collection_items.addedTime
        """
    )
    fun flowBooks(collectionId: Long): Flow<List<Book>>

    @Query(
        "SELECT book_shortcuts.* FROM book_shortcuts INNER JOIN book_collection_items ON book_shortcuts.shortcutId = book_collection_items.shortcutId " +
            "WHERE book_collection_items.collectionId = :collectionId AND book_collection_items.entryType = ${BookCollectionItem.TYPE_SHORTCUT} ORDER BY book_collection_items.`order`, book_collection_items.addedTime"
    )
    fun flowShortcuts(collectionId: Long): Flow<List<BookShortcut>>

    @Query(
        "SELECT book_shortcuts.* FROM book_shortcuts INNER JOIN book_collection_items ON book_shortcuts.shortcutId = book_collection_items.shortcutId WHERE book_collection_items.collectionId = :collectionId AND book_collection_items.entryType = ${BookCollectionItem.TYPE_SHORTCUT} ORDER BY book_collection_items.`order`, book_collection_items.addedTime"
    )
    fun shortcutsInCollection(collectionId: Long): List<BookShortcut>

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

    @Query("SELECT bookUrl FROM book_collection_items WHERE collectionId = :collectionId AND entryType = ${BookCollectionItem.TYPE_BOOK} AND bookUrl IS NOT NULL ORDER BY `order`, addedTime")
    fun bookUrlsInCollection(collectionId: Long): List<String>

    @Query("SELECT childCollectionId FROM book_collection_children WHERE parentCollectionId = :collectionId ORDER BY `order`, addedTime")
    fun childCollectionIds(collectionId: Long): List<Long>

    @Query("SELECT parentCollectionId FROM book_collection_children WHERE childCollectionId = :collectionId")
    fun parentCollectionIds(collectionId: Long): List<Long>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertItems(items: List<BookCollectionItem>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertEntryItems(items: List<BookCollectionItem>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertChildren(children: List<BookCollectionChild>)

    @Query("DELETE FROM book_collection_items WHERE entryType = ${BookCollectionItem.TYPE_BOOK} AND bookUrl IN (:bookUrls)")
    fun deleteItemsByBookUrls(bookUrls: List<String>)

    @Query("DELETE FROM book_collection_items WHERE entryType = ${BookCollectionItem.TYPE_SHORTCUT} AND shortcutId IN (:shortcutIds)")
    fun deleteItemsByShortcutIds(shortcutIds: List<Long>)

    @Query("DELETE FROM book_collection_children WHERE childCollectionId IN (:childCollectionIds)")
    fun deleteParentsByChildCollectionIds(childCollectionIds: List<Long>)

    @Query(
        """
        DELETE FROM book_collection_items
        WHERE EXISTS (
            SELECT 1 FROM book_collection_items AS newer
            WHERE newer.entryType = ${BookCollectionItem.TYPE_BOOK}
              AND newer.bookUrl = book_collection_items.bookUrl
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
        DELETE FROM book_collection_items
        WHERE entryType = ${BookCollectionItem.TYPE_SHORTCUT}
          AND EXISTS (
            SELECT 1 FROM book_collection_items AS newer
            WHERE newer.entryType = ${BookCollectionItem.TYPE_SHORTCUT}
              AND newer.shortcutId = book_collection_items.shortcutId
              AND (newer.addedTime > book_collection_items.addedTime
                OR (newer.addedTime = book_collection_items.addedTime
                    AND newer.collectionId > book_collection_items.collectionId))
          )
        """
    )
    fun deleteDuplicateShortcutItems()

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

    @Query("UPDATE book_collections SET updatedTime = :updatedTime")
    fun updateAllCollectionTimes(updatedTime: Long)

    @Query("SELECT DISTINCT bookUrl FROM book_collection_items WHERE entryType = ${BookCollectionItem.TYPE_BOOK} AND bookUrl IS NOT NULL")
    fun flowCollectedBookUrls(): Flow<List<String>>

    @Query("SELECT DISTINCT shortcutId FROM book_collection_items WHERE entryType = ${BookCollectionItem.TYPE_SHORTCUT} AND shortcutId IS NOT NULL")
    fun flowCollectedShortcutIds(): Flow<List<Long>>

    @Query(
        """
        WITH RECURSIVE collection_tree(collectionId, depth) AS (
            SELECT :collectionId, 0
            UNION
            SELECT book_collection_children.childCollectionId, collection_tree.depth + 1
            FROM book_collection_children
            INNER JOIN collection_tree
                ON book_collection_children.parentCollectionId = collection_tree.collectionId
            WHERE book_collection_children.childCollectionId != book_collection_children.parentCollectionId
        )
        SELECT books.* FROM books
        INNER JOIN book_collection_items
            ON books.bookUrl = book_collection_items.bookUrl
        INNER JOIN collection_tree
            ON book_collection_items.collectionId = collection_tree.collectionId
        ORDER BY collection_tree.depth,
            book_collection_items.entryType,
            book_collection_items.`order`,
            book_collection_items.addedTime
        LIMIT :limit
        """
    )
    fun previewBooksInCollection(collectionId: Long, limit: Int): List<Book>

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
                BookCollectionItem.book(
                    collectionId = collectionId,
                    bookUrl = bookUrl,
                    order = startOrder + index,
                    addedTime = now + index
                )
            }
        )
        updateCollectionTime(collectionId, now)
        updateAllCollectionTimes(now)
    }

    @Transaction
    fun addEntries(collectionId: Long, entries: List<ShelfEntry>) {
        val unique = entries.distinctBy { it.shelfKey }
        if (unique.isEmpty()) return
        val bookUrls = unique.filterIsInstance<ShelfEntry.Body>().map { it.book.bookUrl }
        val shortcutIds = unique.filterIsInstance<ShelfEntry.Shortcut>().map { it.shortcut.shortcutId }
        if (bookUrls.isNotEmpty()) deleteItemsByBookUrls(bookUrls)
        if (shortcutIds.isNotEmpty()) deleteItemsByShortcutIds(shortcutIds)
        val now = System.currentTimeMillis()
        val startOrder = maxItemOrder(collectionId) + 1
        insertEntryItems(unique.mapIndexed { index, entry ->
            when (entry) {
                is ShelfEntry.Body -> BookCollectionItem.book(collectionId, entry.book.bookUrl, startOrder + index, now + index)
                is ShelfEntry.Shortcut -> BookCollectionItem.shortcut(collectionId, entry.shortcut.shortcutId, entry.book.bookUrl, startOrder + index, now + index)
            }
        })
        updateAllCollectionTimes(now)
    }

    @Transaction
    fun normalizeLocations() {
        deleteDuplicateBookItems()
        deleteDuplicateShortcutItems()
        deleteDuplicateChildCollections()
        updateAllCollectionTimes(System.currentTimeMillis())
    }

    @Transaction
    fun moveEntriesToRoot(entries: List<ShelfEntry>, collectionIds: List<Long>) {
        val books = entries.filterIsInstance<ShelfEntry.Body>().map { it.book.bookUrl }
        val shortcuts = entries.filterIsInstance<ShelfEntry.Shortcut>().map { it.shortcut.shortcutId }
        if (books.isNotEmpty()) deleteItemsByBookUrls(books)
        if (shortcuts.isNotEmpty()) deleteItemsByShortcutIds(shortcuts)
        val ids = collectionIds.distinct().filter { it > 0 }
        if (ids.isNotEmpty()) deleteParentsByChildCollectionIds(ids)
        updateAllCollectionTimes(System.currentTimeMillis())
    }

    @Transaction
    fun moveItemsToRoot(bookUrls: List<String>, collectionIds: List<Long>) {
        val uniqueBookUrls = bookUrls.distinct()
        val uniqueCollectionIds = collectionIds.distinct().filter { it > 0 }
        if (uniqueBookUrls.isEmpty() && uniqueCollectionIds.isEmpty()) return
        if (uniqueBookUrls.isNotEmpty()) {
            deleteItemsByBookUrls(uniqueBookUrls)
        }
        if (uniqueCollectionIds.isNotEmpty()) {
            deleteParentsByChildCollectionIds(uniqueCollectionIds)
        }
        updateAllCollectionTimes(System.currentTimeMillis())
    }

    @Transaction
    fun deleteCollectionsAndRelease(collectionIds: List<Long>) {
        collectionIds.distinct().filter { it > 0 }.forEach { collectionId ->
            deleteCollectionAndRelease(collectionId)
        }
        updateAllCollectionTimes(System.currentTimeMillis())
    }

    @Transaction
    fun deleteCollectionAndRelease(collectionId: Long) {
        val bookUrls = bookUrlsInCollection(collectionId)
        val shortcuts = shortcutsInCollection(collectionId)
        val childIds = childCollectionIds(collectionId)
        val parentIds = parentCollectionIds(collectionId).filter { it > 0 && it != collectionId }
        parentIds.forEach { parentId ->
            addBookUrls(parentId, bookUrls)
            addShortcutEntries(parentId, shortcuts)
            addChildCollectionIds(parentId, childIds)
        }
        deleteByIds(listOf(collectionId))
    }

    @Transaction
    fun addShortcutEntries(collectionId: Long, shortcuts: List<BookShortcut>) {
        if (shortcuts.isEmpty()) return
        val entries = shortcuts.map { shortcut ->
            ShelfEntry.Shortcut(
                shortcut = shortcut,
                book = Book(
                    bookUrl = shortcut.bookUrl,
                    name = "",
                    author = ""
                )
            )
        }
        // Only the shortcut identity is used by addEntries; the joined book is resolved by FK.
        val now = System.currentTimeMillis()
        val startOrder = maxItemOrder(collectionId) + 1
        insertEntryItems(shortcuts.mapIndexed { index, shortcut ->
            BookCollectionItem.shortcut(collectionId, shortcut.shortcutId, shortcut.bookUrl, startOrder + index, now + index)
        })
        updateAllCollectionTimes(now)
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
        updateAllCollectionTimes(now)
    }
}
