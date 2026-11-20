package io.legado.app.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import io.legado.app.data.entities.ReadRecordDetail

@Dao
interface ReadRecordDetailDao {

    @get:Query("select * from readRecordDetail")
    val all: List<ReadRecordDetail>

    @Query(
        "select * from readRecordDetail where deviceId = :device and bookName = :bookName and date = :date limit 1"
    )
    fun get(device: String, bookName: String, date: String): ReadRecordDetail?

    @Query("select * from readRecordDetail where date = :date order by readTime desc")
    fun getByDate(date: String): List<ReadRecordDetail>

    @Query("select * from readRecordDetail where deviceId = :device and bookName = :bookName order by date desc")
    fun getByBook(device: String, bookName: String): List<ReadRecordDetail>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(vararg detail: ReadRecordDetail)

    @Query("delete from readRecordDetail")
    fun clear()
}
