package io.legado.app.data.entities

import androidx.room.Entity
import androidx.room.Index

/**
 * Records the latest AI processing outcome for one exact cached chapter revision.
 * Only completed records suppress another automatic pass for the same revision.
 *
 * contentFingerprint 是「章节缓存原文」的 SHA-256（不含任何规则/设置），
 * processedTypes 记录该章实际被哪几个 AI 功能（ad/typo/noise 的子集）处理过。
 */
@Entity(
    tableName = "ai_chapter_purify_records",
    primaryKeys = ["bookUrl", "chapterIndex"],
    indices = [Index(value = ["bookUrl"])]
)
data class AiChapterPurifyRecord(
    val bookUrl: String,
    val chapterIndex: Int,
    val contentFingerprint: String,
    val completedAt: Long = System.currentTimeMillis(),
    val ruleCount: Int = 0,
    val processedTypes: String = "",
    val state: Int = STATE_COMPLETED,
    val failureMessage: String? = null
) {
    companion object {
        const val STATE_COMPLETED = 1
        const val STATE_FAILED = 2
    }
}
