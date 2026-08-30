package io.legado.app.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import io.legado.app.data.entities.TtsEngineRuntimeEntity

@Dao
interface TtsEngineRuntimeDao {

    @Query("select * from ttsEngineRuntime where engineId = :engineId limit 1")
    fun get(engineId: String): TtsEngineRuntimeEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(runtime: TtsEngineRuntimeEntity)

    @Query("delete from ttsEngineRuntime where engineId = :engineId")
    fun deleteByEngine(engineId: String)
}
