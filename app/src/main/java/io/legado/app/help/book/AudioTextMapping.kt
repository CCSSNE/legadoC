package io.legado.app.help.book

data class AudioTextMapping(
    val paragraphs: List<String>,
    val cues: List<Cue>,
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

            val timedCues = buildList {
                rawText.lineSequence().forEach { rawLine ->
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
                )
            }

            val paragraphs = rawText.lineSequence()
                .map(String::trim)
                .filter(String::isNotEmpty)
                .filterNot { metadataTag.matches(it) }
                .toList()
            return AudioTextMapping(paragraphs, emptyList())
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
