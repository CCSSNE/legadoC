package io.legado.app.help.tts

/**
 * 分镜数据模型（基础归因版，无演绎能力层）。
 * 段落由客户端预先切分成候选 unit，AI 只负责归因，不返回正文。
 */

enum class StoryboardSegmentType {
    NARRATION, DIALOGUE, THOUGHT;

    companion object {
        fun of(value: String): StoryboardSegmentType = when (value) {
            "character" -> DIALOGUE
            "thought" -> THOUGHT
            else -> NARRATION
        }
    }
}

data class StoryboardSegment(
    val type: StoryboardSegmentType,
    val paragraphIndex: Int,
    val text: String,
    val start: Int,
    val end: Int,
    val speakerName: String? = null,
    val speakerGender: String = BookRole.Gender.UNKNOWN,
    val characterId: Long = 0L,
    val castRoleId: Long = 0L,
    val identityType: String = IdentityType.NONE,
    val nameType: String = "unknown",
    val evidence: String = "",
    val confidence: Float = 0f
) {
    object IdentityType {
        const val NONE = "none"
        const val FORMAL_CHARACTER = "formal_character"
        const val CAST_ROLE = "cast_role"
        const val STABLE_CANDIDATE = "stable_candidate"
        const val PENDING = "pending"
        const val GUEST = "guest"
    }

    object NameType {
        const val PROPER_NAME = "proper_name"
        const val ALIAS = "alias"
        const val UNIQUE_TITLE = "unique_title"
        const val GENERIC_LABEL = "generic_label"
        const val UNKNOWN = "unknown"
    }

    object Evidence {
        const val EXPLICIT = "explicit"
        const val CONTEXTUAL = "contextual"
        const val INFERRED = "inferred"
        const val UNKNOWN = "unknown"
    }
}

data class StoryboardScene(
    val sceneId: String,
    val title: String,
    val startParagraphIndex: Int,
    val endParagraphIndex: Int,
    val segments: List<StoryboardSegment>
)

data class StoryboardIdentityLink(
    val aliasName: String,
    val characterId: Long = 0L,
    val castRoleId: Long = 0L,
    val evidence: String = ""
)

data class ChapterStoryboard(
    val bookUrl: String,
    val bookName: String,
    val bookAuthor: String,
    val chapterIndex: Int,
    val chapterTitle: String,
    val contentHash: String,
    val cacheVersion: Int = CACHE_VERSION,
    val paragraphs: List<String> = emptyList(),
    val scenes: List<StoryboardScene> = emptyList(),
    val identityLinks: List<StoryboardIdentityLink> = emptyList()
) {
    companion object {
        const val CACHE_VERSION = 1
    }

    fun allSegments(): List<StoryboardSegment> =
        scenes.flatMap { it.segments }.sortedWith(
            compareBy({ it.paragraphIndex }, { it.start })
        )

    fun segmentsForParagraph(paragraphIndex: Int): List<StoryboardSegment> =
        allSegments().filter { it.paragraphIndex == paragraphIndex }
}
