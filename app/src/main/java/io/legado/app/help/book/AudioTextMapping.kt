package io.legado.app.help.book

import io.legado.app.constant.AppPattern

/**
 * 音频正文映射。
 *
 * “真实显示正文段 ↔ cue”是显式映射关系，两条序列各自独立：
 * - [displayParts]（显示顺序）：按原始 lyric 顺序输出，`<usehtml>…</usehtml>`
 *   结构块原位保留，不参与时间排序；
 * - [paragraphs]/[cues]（时间顺序）：按 startMs 排序的音频正文段落。
 *
 * 不再隐含“第 N 个显示正文段 == 第 N 个按时间排序 cue”的假设；
 * 每个显示正文段通过 [DisplayPart.Body.cueIndex] 指向对应 cue，
 * 因此乱序时间戳、插入评论/图片、重复字幕文本都不会破坏绑定。
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

    /**
     * 把页面真实正文段落（不含标题与 usehtml 结构段）绑定到本映射。
     *
     * 逐段核对用的是显示顺序的正文段（displayParts 中的 Body），
     * 与页面段落顺序天然一致；随后通过各 Body 携带的 cue 索引建立
     * “layout body index → cue index”的显式映射。
     */
    fun bindLayout(layoutParagraphs: List<LayoutParagraph>): LayoutBinding {
        require(layoutParagraphs.zipWithNext().all { (left, right) -> left.index < right.index }) {
            "正文段落索引必须严格递增"
        }
        val contentParagraphs = layoutParagraphs.filterNot(LayoutParagraph::isStructural)
        val displayBodyTexts = displayParts.filterIsInstance<DisplayPart.Body>().map { it.text }
        require(displayBodyTexts.size == contentParagraphs.size) {
            "字幕与正文段落数量不一致：subtitle=${displayBodyTexts.size}, layout=${contentParagraphs.size}"
        }
        displayBodyTexts.indices.firstOrNull { index ->
            normalizeParagraph(displayBodyTexts[index]) != normalizeParagraph(contentParagraphs[index].text)
        }?.let { index ->
            throw IllegalArgumentException(
                "字幕与正文第 ${index + 1} 段内容不一致：" +
                    "subtitle=${displayBodyTexts[index].take(80)}, " +
                    "layout=${contentParagraphs[index].text.take(80)}"
            )
        }
        return LayoutBinding(
            mapping = this,
            layoutParagraphIndices = contentParagraphs.map(LayoutParagraph::index),
            bodyCueIndices = displayParts.filterIsInstance<DisplayPart.Body>().map { it.cueIndex },
        )
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
        /**
         * 音频正文段落文本。
         *
         * [cueIndex] 是该段落对应的按时间排序 cue 索引（无时间映射时为 -1）；
         * 乱序时间戳时它不与显示位置同序，这就是绑定层的显式映射依据。
         */
        data class Body(val text: String, val cueIndex: Int = -1) : DisplayPart

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
        // 第 i 个真实显示正文段（layoutParagraphIndices[i]）对应的时间排序 cue 索引
        private val bodyCueIndices: List<Int>,
    ) {
        val paragraphCount: Int get() = layoutParagraphIndices.size

        fun timeForLayoutParagraph(layoutParagraphIndex: Int): Int? {
            if (!mapping.hasTimeMapping) return null
            val result = layoutParagraphIndices.binarySearch(layoutParagraphIndex)
            val cueIndex = if (result >= 0) {
                // 精确命中真实正文段：走显式 body→cue 映射
                bodyCueIndices.getOrNull(result) ?: return null
            } else {
                // 未命中（结构段或越界）：取其后的最近正文段时间
                bodyCueIndices.getOrNull(-result - 1) ?: return null
            }
            if (cueIndex < 0) return null
            return mapping.timeForParagraph(cueIndex)
        }

        fun layoutParagraphAt(timeMs: Int): Int? {
            val cueIndex = mapping.paragraphAt(timeMs) ?: return null
            val bodyIndex = bodyCueIndices.indexOf(cueIndex)
            if (bodyIndex < 0) return null
            return layoutParagraphIndices.getOrNull(bodyIndex)
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

            data class Candidate(val id: Int, val startMs: Int, val text: String)

            // 候选 cue：id 表示其在原文中的出现顺序（与显示遍历一致），
            // 时间排序后据此找回每个显示段落对应的 cue。
            val candidates = buildList {
                stripped.lineSequence().forEach { rawLine ->
                    val matches = timeTag.findAll(rawLine).toList()
                    if (matches.isEmpty()) return@forEach
                    val text = rawLine
                        .replace(timeTag, "")
                        .replace(inlineTimeTag, "")
                        .trim()
                    if (text.isEmpty()) return@forEach
                    matches.forEach { match ->
                        add(Candidate(id = size, startMs = parseTimeMs(match), text = text))
                    }
                }
            }
            val timed = candidates.isNotEmpty()

            // 显示顺序遍历：usehtml 原序原位保留；timed 行每个标签对应一个
            // 显示正文段，并记录其候选 id（作为排序后的 cueIndex 依据）。
            val displayParts = mutableListOf<DisplayPart>()
            var snippetStart = 0
            var candidateCursor = 0
            fun appendSnippet(snippet: String) {
                snippet.lineSequence().forEach { rawLine ->
                    val matches = timeTag.findAll(rawLine).toList()
                    if (matches.isNotEmpty()) {
                        val text = rawLine
                            .replace(timeTag, "")
                            .replace(inlineTimeTag, "")
                            .trim()
                        if (text.isEmpty()) return@forEach
                        repeat(matches.size) {
                            displayParts += DisplayPart.Body(text, cueIndex = candidateCursor)
                            candidateCursor++
                        }
                    } else if (!timed) {
                        val trimmed = rawLine.trim()
                        if (trimmed.isNotEmpty() && !metadataTag.matches(trimmed)) {
                            displayParts += DisplayPart.Body(trimmed)
                        }
                    }
                }
            }
            AppPattern.useHtmlRegex.findAll(rawText).forEach { match ->
                appendSnippet(rawText.substring(snippetStart, match.range.first))
                displayParts += DisplayPart.HtmlBlock(match.value)
                snippetStart = match.range.last + 1
            }
            appendSnippet(rawText.substring(snippetStart))

            if (timed) {
                // 按时间稳定排序（同时间戳保持原出现顺序），
                // 建立 id → 排序索引 映射并回填每个显示正文段的 cue 索引。
                val sorted = candidates.sortedBy { it.startMs }
                val cueIndexById = sorted
                    .mapIndexed { index, candidate -> candidate.id to index }
                    .toMap()
                val cues = sorted.map { Cue(it.startMs, it.text) }
                val parts = displayParts.map { part ->
                    if (part is DisplayPart.Body && part.cueIndex >= 0) {
                        DisplayPart.Body(part.text, cueIndexById[part.cueIndex] ?: -1)
                    } else {
                        part
                    }
                }
                return AudioTextMapping(
                    paragraphs = cues.map(Cue::text),
                    cues = cues,
                    displayParts = parts,
                )
            }

            return AudioTextMapping(
                paragraphs = displayParts.filterIsInstance<DisplayPart.Body>().map { it.text },
                cues = emptyList(),
                displayParts = displayParts,
            )
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