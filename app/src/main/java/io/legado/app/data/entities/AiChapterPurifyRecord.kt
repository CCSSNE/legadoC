package io.legado.app.data.entities

import androidx.room.Entity
import androidx.room.Index

/**
 * Records the latest AI processing outcome for one exact cached chapter revision.
 * Only completed records suppress another automatic pass for the same revision.
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
    val state: Int = STATE_COMPLETED,
    val failureMessage: String? = null
) {
    companion object {
        const val STATE_COMPLETED = 1
        const val STATE_FAILED = 2
    }
}
