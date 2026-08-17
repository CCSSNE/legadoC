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

    data class Cue(
        val startMs: Int,
        val text: String,
    )

    companion object {
        private val timeTag = Regex("\\[(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?]")
        private val metadataTag = Regex("^\\[[A-Za-z][^]]*]$")
        private val inlineTimeTag = Regex("<\\d{1,3}:\\d{1,2}(?:[.:]\\d{1,3})?>")

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
