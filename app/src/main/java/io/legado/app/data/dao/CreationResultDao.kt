package io.legado.app.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import io.legado.app.data.entities.CreationResult

@Dao
interface CreationResultDao {

    @Query("select * from creation_results order by createdAt desc")
    fun getAll(): List<CreationResult>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(result: CreationResult): Long

    @Delete
    fun delete(results: List<CreationResult>)

    @Query("delete from creation_results")
    fun deleteAll()

    @Query("select count(*) from creation_results")
    fun count(): Int
}
