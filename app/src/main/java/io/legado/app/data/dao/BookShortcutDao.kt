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

    @Query("SELECT * FROM book_shortcuts WHERE shortcutId = :shortcutId")
    fun get(shortcutId: Long): BookShortcut?

    @Query("SELECT COALESCE(MAX(`order`), 0) FROM book_shortcuts WHERE `group` = :groupId")
    fun maxOrder(groupId: Long): Int

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
}
