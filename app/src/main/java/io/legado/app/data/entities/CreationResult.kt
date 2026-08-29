package io.legado.app.data.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * AI 创作生成的图片索引。独立缓存，不与任何书籍/章节关联。
 * filePath 为应用私有目录 creation_results 下的文件名。
 */
@Entity(tableName = "creation_results")
data class CreationResult(
    @PrimaryKey(autoGenerate = true) val resultId: Long = 0,
    val fileName: String,
    val createdAt: Long = System.currentTimeMillis()
)
