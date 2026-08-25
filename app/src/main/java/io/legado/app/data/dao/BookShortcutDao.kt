package io.legado.app.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import io.legado.app.data.entities.BookShortcut
import io.legado.app.data.entities.BookShortcutWithBook
import kotlinx.coroutines.flow.Flow

@Dao
interface BookShortcutDao {

    @Transaction
    @Query("SELECT * FROM book_shortcuts ORDER BY `order`, createdTime, shortcutId")
    fun flowAll(): Flow<List<BookShortcutWithBook>>

    @Transaction
    @Query(
        "SELECT * FROM book_shortcuts WHERE collectionId = :collectionId " +
            "ORDER BY `order`, createdTime, shortcutId"
    )
    fun flowByCollection(collectionId: Long): Flow<List<BookShortcutWithBook>>

    @Query("SELECT * FROM book_shortcuts WHERE shortcutId = :shortcutId")
    fun get(shortcutId: Long): BookShortcut?

    @Query(
        "SELECT COALESCE(MAX(`order`), 0) FROM book_shortcuts " +
            "WHERE `group` = :groupId AND collectionId IS NULL"
    )
    fun maxOrder(groupId: Long): Int

    @Query(
        "SELECT COALESCE(MAX(`order`), 0) FROM book_shortcuts " +
            "WHERE collectionId = :collectionId"
    )
    fun maxOrderInCollection(collectionId: Long): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insert(shortcuts: List<BookShortcut>)

    @Update
    fun update(shortcut: BookShortcut)

    @Delete
    fun delete(shortcut: BookShortcut)

    @Query("DELETE FROM book_shortcuts WHERE shortcutId = :shortcutId")
    fun delete(shortcutId: Long)

    @Query("DELETE FROM book_shortcuts WHERE shortcutId IN (:shortcutIds)")
    fun delete(shortcutIds: List<Long>)

    @Query("DELETE FROM book_shortcuts WHERE bookUrl = :bookUrl")
    fun deleteByBookUrl(bookUrl: String)

    @Transaction
    fun moveToCollection(collectionId: Long, shortcutIds: Collection<Long>) {
        val ids = shortcutIds.distinct().filter { it > 0L }
        if (collectionId <= 0L || ids.isEmpty()) return
        val startOrder = maxOrderInCollection(collectionId) + 1
        ids.forEachIndexed { index, shortcutId ->
            get(shortcutId)?.let { shortcut ->
                update(shortcut.copy(collectionId = collectionId, order = startOrder + index))
            }
        }
    }

    @Transaction
    fun moveToRoot(shortcutIds: Collection<Long>) {
        shortcutIds.distinct().filter { it > 0L }.forEach { shortcutId ->
            get(shortcutId)?.takeIf { it.collectionId != null }?.let { shortcut ->
                update(shortcut.copy(collectionId = null))
            }
        }
    }
}
