package io.legado.app.data.entities

import androidx.room.ColumnInfo
import androidx.room.Entity

@Entity(
    tableName = "ttsEngineRuntime",
    primaryKeys = ["engineId"]
)
data class TtsEngineRuntimeEntity(
    var engineId: String = "",
    @ColumnInfo(defaultValue = "50")
    var speed: Int = 50,
    @ColumnInfo(defaultValue = "50")
    var volume: Int = 50,
    @ColumnInfo(defaultValue = "50")
    var pitch: Int = 50,
    @ColumnInfo(defaultValue = "0")
    var updatedAt: Long = 0L
)
