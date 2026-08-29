package io.legado.app.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import io.legado.app.data.entities.CreationCard

@Dao
interface CreationCardDao {

    @Query("select * from creation_cards where cardId = :cardId limit 1")
    fun getById(cardId: Long): CreationCard?

    @Query(
        "select * from creation_cards " +
            "where section = :section and bookName in ('', :bookName) " +
            "order by updateTime"
    )
    fun listBySection(section: String, bookName: String): List<CreationCard>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(card: CreationCard): Long

    @Update
    fun update(card: CreationCard)

    @Delete
    fun delete(card: CreationCard)

    @Query("delete from creation_cards where cardId = :cardId")
    fun deleteById(cardId: Long)

    @Query("delete from creation_cards where bookName = :bookName")
    fun deleteByBookName(bookName: String)

    @Query(
        "select * from creation_cards " +
            "where section = :section and bookName in ('', :bookName) " +
            "and (name like '%' || :keyword || '%' or content like '%' || :keyword || '%') " +
            "order by updateTime"
    )
    fun search(section: String, bookName: String, keyword: String): List<CreationCard>
}
