package io.legado.app.data.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * AI 创作素材卡片。
 * bookName 为空表示全局卡片；[AiCreationConfig] 中的分区作用域决定新卡片的归属。
 */
@Entity(
    tableName = "creation_cards",
    indices = [Index(value = ["section"]), Index(value = ["bookName"])]
)
data class CreationCard(
    @PrimaryKey(autoGenerate = true) val cardId: Long = 0,
    val section: String,
    val name: String,
    val content: String,
    val bookName: String = "",
    val updateTime: Long = System.currentTimeMillis()
)
