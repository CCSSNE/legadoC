package io.legado.app.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.ReadRecentBook

@Dao
interface ReadRecentBookDao {

    @Query(
        """
        select books.* from readRecentBooks
        inner join books on readRecentBooks.bookUrl = books.bookUrl
        where books.name != ''
        and readRecentBooks.bookUrl = (
            select innerRecent.bookUrl from readRecentBooks as innerRecent
            inner join books as innerBooks on innerRecent.bookUrl = innerBooks.bookUrl
            where innerBooks.name = books.name
            and ifnull(innerBooks.author, '') = ifnull(books.author, '')
            order by innerRecent.lastRead desc
            limit 1
        )
        order by readRecentBooks.lastRead desc
        limit :limit
        """
    )
    fun recentBooks(limit: Int): List<Book>

    @Query("select max(lastRead) from readRecentBooks")
    fun latestReadTime(): Long?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(record: ReadRecentBook)

    @Query("delete from readRecentBooks")
    fun clear()

    @Query("delete from readRecentBooks where bookUrl = :bookUrl")
    fun delete(bookUrl: String)

    @Query(
        """
        delete from readRecentBooks
        where bookUrl in (
            select bookUrl from books
            where name = :name
            and ifnull(author, '') = ifnull(:author, '')
        )
        """
    )
    fun deleteSameBook(name: String, author: String?)
}
