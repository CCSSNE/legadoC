package io.legado.app.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import io.legado.app.data.entities.TtsVoiceEntity

@Dao
interface TtsVoiceDao {

    @Query("select * from ttsVoices where engineId = :engineId order by name asc, id asc")
    fun getByEngine(engineId: String): List<TtsVoiceEntity>

    @Query("select * from ttsVoices where engineId = :engineId and id = :id limit 1")
    fun get(engineId: String, id: String): TtsVoiceEntity?

    @Query("select engineId, count(*) as count from ttsVoices group by engineId")
    fun countByEngine(): List<TtsVoiceCount>

    @Query("select max(updatedAt) from ttsVoices where engineId = :engineId")
    fun lastUpdatedAt(engineId: String): Long?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(voices: List<TtsVoiceEntity>)

    @Query("delete from ttsVoices where engineId = :engineId")
    fun deleteByEngine(engineId: String)

    @Transaction
    fun replaceForEngine(engineId: String, voices: List<TtsVoiceEntity>) {
        deleteByEngine(engineId)
        if (voices.isNotEmpty()) {
            insert(voices)
        }
    }
}

data class TtsVoiceCount(
    val engineId: String,
    val count: Int
)
