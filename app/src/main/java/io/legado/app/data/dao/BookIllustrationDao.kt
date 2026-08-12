package io.legado.app.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import io.legado.app.data.entities.BookIllustration
import kotlinx.coroutines.flow.Flow

@Dao
interface BookIllustrationDao {

    @get:Query("select * from book_illustrations order by bookUrl, chapterIndex, sortOrder, id")
    val all: List<BookIllustration>

    @Query("select * from book_illustrations where bookUrl = :bookUrl order by chapterIndex, sortOrder, id")
    fun flowByBook(bookUrl: String): Flow<List<BookIllustration>>

    @Query("select * from book_illustrations where bookUrl = :bookUrl order by chapterIndex, sortOrder, id")
    fun getByBook(bookUrl: String): List<BookIllustration>

    @Query("select * from book_illustrations where bookUrl = :bookUrl and chapterIndex = :chapterIndex order by sortOrder, id")
    fun getByBookAndChapter(bookUrl: String, chapterIndex: Int): List<BookIllustration>

    @Query("select * from book_illustrations where bookUrl = :bookUrl and chapterIndex = :chapterIndex order by sortOrder, id")
    fun flowByBookAndChapter(bookUrl: String, chapterIndex: Int): Flow<List<BookIllustration>>

    @Query("select count(*) from book_illustrations where bookUrl = :bookUrl")
    fun countByBook(bookUrl: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(vararg illustrations: BookIllustration)

    @Update
    fun update(vararg illustrations: BookIllustration)

    @Delete
    fun delete(vararg illustrations: BookIllustration)

    @Query("delete from book_illustrations where id in (:ids)")
    fun deleteByIds(vararg ids: Long)

    @Query("delete from book_illustrations where bookUrl = :bookUrl")
    fun deleteByBook(bookUrl: String)
}
