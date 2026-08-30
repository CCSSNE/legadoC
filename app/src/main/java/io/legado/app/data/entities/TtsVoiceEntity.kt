package io.legado.app.data.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "ttsVoices",
    primaryKeys = ["engineId", "id"],
    indices = [Index(value = ["engineId"])]
)
data class TtsVoiceEntity(
    var engineId: String = "",
    var id: String = "",
    var name: String = "",
    var language: String? = null,
    var gender: String? = null,
    var style: String? = null,
    @ColumnInfo(defaultValue = "[]")
    var tagsJson: String = "[]",
    var sampleText: String? = null,
    @ColumnInfo(defaultValue = "{}")
    var extraJson: String = "{}",
    @ColumnInfo(defaultValue = "0")
    var updatedAt: Long = 0L
)
