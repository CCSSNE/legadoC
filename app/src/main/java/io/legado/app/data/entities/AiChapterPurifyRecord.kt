package io.legado.app.data.entities

import androidx.room.Entity
import androidx.room.Index

/**
 * Records the exact cached chapter revision that has completed AI rule generation.
 * A changed body fingerprint is eligible for another automatic pass.
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
    val ruleCount: Int = 0
)
