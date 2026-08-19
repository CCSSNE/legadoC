package io.legado.app.help.book

import io.legado.app.constant.AppPattern

/**
 * 音频正文映射。
 *
 * “正文段落 ↔ timeMs”只统计音频正文段落；`<usehtml>…</usehtml>` 结构块
 * 属于非音频正文的完整显示结构，既不拆成普通正文段落，也不占用
 * paragraph/cue 索引，但仍通过 [displayParts]/[displayContents] 保留原序，
 * 使它们能进入 TextChapter → TextChapterLayout 的 HTML 渲染。
 */
data class AudioTextMapping(
    val paragraphs: List<String>,
    val cues: List<Cue>,
    val displayParts: List<DisplayPart> = emptyList(),
) {
    val hasTimeMapping: Boolean get() = cues.isNotEmpty()

    fun timeForParagraph(paragraphIndex: Int): Int? {
        return cues.getOrNull(paragraphIndex)?.startMs
    }

    fun paragraphAt(timeMs: Int): Int? {
        if (cues.isEmpty()) return null
        val index = cues.binarySearchBy(timeMs.coerceAtLeast(0)) { it.startMs }
        return if (index >= 0) index else (-index - 2).coerceAtLeast(0)
    }

    fun bindLayout(layoutParagraphs: List<LayoutParagraph>): LayoutBinding {
        require(layoutParagraphs.zipWithNext().all { (left, right) -> left.index < right.index }) {
            "正文段落索引必须严格递增"
        }
        val contentParagraphs = layoutParagraphs.filterNot(LayoutParagraph::isStructural)
        require(paragraphs.size == contentParagraphs.size) {
            "字幕与正文段落数量不一致：subtitle=${paragraphs.size}, layout=${contentParagraphs.size}"
        }
        paragraphs.indices.firstOrNull { index ->
            normalizeParagraph(paragraphs[index]) != normalizeParagraph(contentParagraphs[index].text)
        }?.let { index ->
            throw IllegalArgumentException(
                "字幕与正文第 ${index + 1} 段内容不一致：" +
                    "subtitle=${paragraphs[index].take(80)}, " +
                    "layout=${contentParagraphs[index].text.take(80)}"
            )
        }
        return LayoutBinding(this, contentParagraphs.map(LayoutParagraph::index))
    }

    /**
     * 按显示顺序输出音频章节正文内容：
     * - 普通正文段落按段输出并加缩进；
     * - `<usehtml>…</usehtml>` 原块保持完整结构输出（不加缩进），
     *   由 TextChapterLayout 现有 HTML 渲染路径处理。
     */
    fun displayContents(paragraphIndent: String = ""): List<String> {
        return displayParts.map { part ->
            when (part) {
                is DisplayPart.Body -> "$paragraphIndent${part.text}"
                is DisplayPart.HtmlBlock -> part.raw
            }
        }
    }

    /**
     * 显示顺序中的一段内容：要么是音频正文段落，要么是完整的
     * `<usehtml>…</usehtml>` 显示结构。
     */
    sealed interface DisplayPart {
        /** 音频正文段落文本 */
        data class Body(val text: String) : DisplayPart

        /** 完整的 `<usehtml>…</usehtml>` 原块，非音频正文结构 */
        data class HtmlBlock(val raw: String) : DisplayPart
    }

    data class Cue(
        val startMs: Int,
        val text: String,
    )

    data class LayoutParagraph(
        val index: Int,
        val text: String,
        val isStructural: Boolean,
    )

    class LayoutBinding internal constructor(
        private val mapping: AudioTextMapping,
        private val layoutParagraphIndices: List<Int>,
    ) {
        val paragraphCount: Int get() = layoutParagraphIndices.size

        fun timeForLayoutParagraph(layoutParagraphIndex: Int): Int? {
            if (!mapping.hasTimeMapping) return null
            val result = layoutParagraphIndices.binarySearch(layoutParagraphIndex)
            val cueIndex = if (result >= 0) result else -result - 1
            return mapping.timeForParagraph(cueIndex)
        }

        fun layoutParagraphAt(timeMs: Int): Int? {
            val cueIndex = mapping.paragraphAt(timeMs) ?: return null
            return layoutParagraphIndices.getOrNull(cueIndex)
        }
    }

    companion object {
        private val timeTag = Regex("\\[(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?]")
        private val metadataTag = Regex("^\\[[A-Za-z][^]]*]$")
        private val inlineTimeTag = Regex("<\\d{1,3}:\\d{1,2}(?:[.:]\\d{1,3})?>")

        private fun normalizeParagraph(text: String): String {
            return text.trim { it.isWhitespace() || it == '\u00A0' }
        }

        fun parse(rawText: String?): AudioTextMapping {
            if (rawText.isNullOrBlank()) return AudioTextMapping(emptyList(), emptyList())

            // 先把 <usehtml>…</usehtml> 完整块从分段文本中剥离：
            // 结构块不进入 timed 行匹配，也不进入普通段分行，
            // 其中的 [mm:ss] 样式文本或评论按钮/图片不会占用 paragraph/cue 索引。
            val stripped = AppPattern.useHtmlRegex.replace(rawText) { "\n" }

            val timedCues = buildList {
                stripped.lineSequence().forEach { rawLine ->
                    val matches = timeTag.findAll(rawLine).toList()
                    if (matches.isEmpty()) return@forEach
                    val text = rawLine
                        .replace(timeTag, "")
                        .replace(inlineTimeTag, "")
                        .trim()
                    if (text.isEmpty()) return@forEach
                    matches.forEach { match ->
                        add(Cue(parseTimeMs(match), text))
                    }
                }
            }.sortedBy(Cue::startMs)

            if (timedCues.isNotEmpty()) {
                return AudioTextMapping(
                    paragraphs = timedCues.map(Cue::text),
                    cues = timedCues,
                    displayParts = buildDisplayParts(rawText, timed = true),
                )
            }

            // 无时间标记的正文：段落与显示顺序一致，直接由 displayParts 推导，
            // 保证“正文段落 ↔ timeMs”与页面显示的内容永远同序。
            val displayParts = buildDisplayParts(rawText, timed = false)
            return AudioTextMapping(
                paragraphs = displayParts.filterIsInstance<DisplayPart.Body>().map { it.text },
                cues = emptyList(),
                displayParts = displayParts,
            )
        }

        /**
         * 按原始文本顺序生成显示内容：usehtml 结构块整块保留，
         * 其余行按时间标记是否为音频正文行处理。
         */
        private fun buildDisplayParts(rawText: String, timed: Boolean): List<DisplayPart> {
            val parts = mutableListOf<DisplayPart>()
            var cursor = 0
            AppPattern.useHtmlRegex.findAll(rawText).forEach { match ->
                appendBodyParts(parts, rawText.substring(cursor, match.range.first), timed)
                parts += DisplayPart.HtmlBlock(match.value)
                cursor = match.range.last + 1
            }
            appendBodyParts(parts, rawText.substring(cursor), timed)
            return parts
        }

        private fun appendBodyParts(
            parts: MutableList<DisplayPart>,
            snippet: String,
            timed: Boolean,
        ) {
            snippet.lineSequence().forEach { rawLine ->
                val matches = timeTag.findAll(rawLine).toList()
                if (matches.isNotEmpty()) {
                    val text = rawLine
                        .replace(timeTag, "")
                        .replace(inlineTimeTag, "")
                        .trim()
                    if (text.isEmpty()) return@forEach
                    repeat(matches.size) {
                        parts += DisplayPart.Body(text)
                    }
                } else if (!timed) {
                    val trimmed = rawLine.trim()
                    if (trimmed.isNotEmpty() && !metadataTag.matches(trimmed)) {
                        parts += DisplayPart.Body(trimmed)
                    }
                }
            }
        }

        private fun parseTimeMs(match: MatchResult): Int {
            val minutes = match.groupValues[1].toInt()
            val seconds = match.groupValues[2].toInt()
            require(seconds in 0..59) { "Invalid LRC seconds: ${match.value}" }
            val fraction = match.groupValues[3]
            val fractionMs = when (fraction.length) {
                0 -> 0
                1 -> fraction.toInt() * 100
                2 -> fraction.toInt() * 10
                else -> fraction.take(3).toInt()
            }
            return (minutes * 60 + seconds) * 1000 + fractionMs
        }
    }
}
