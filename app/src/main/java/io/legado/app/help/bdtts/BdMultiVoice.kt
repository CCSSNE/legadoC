package io.legado.app.help.bdtts

import io.legado.app.constant.PreferKey
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.getPrefStringSet
import splitties.init.appCtx
import kotlin.random.Random

/**
 * 多角色朗读（简化版）：按引号把段落文本分成旁白/对白，
 * 旁白从旁白池取、对白从对白池取（各随机挑一个；池空回退当前音色）。
 */
object BdMultiVoice {

    private val dialogueRegex = Regex("\"[^\"]+\"|“[^”]+”|「[^」]+」|‘[^’]+’")

    class Segment(val text: String, val speaker: BdSpeakerRecord)

    fun enabled(): Boolean = appCtx.getPrefBoolean(PreferKey.bdMultiVoice, false)

    /**
     * 把段落文本展开为子段列表（多角色关或池空时为单段当前音色）。
     */
    fun expand(text: String, current: BdSpeakerRecord): List<Segment> {
        if (!enabled()) {
            return listOf(Segment(text, current))
        }
        val narrationPool = pickable(PreferKey.bdNarrationVoices, excludeDialogue = true)
        val dialoguePool = pickable(PreferKey.bdDialogueVoices, excludeDialogue = false)
        val narrationFallback = firstOf(narrationPool) ?: current
        val dialogueFallback = firstOf(dialoguePool) ?: current

        val segments = mutableListOf<Segment>()
        var last = 0
        for (match in dialogueRegex.findAll(text)) {
            val before = text.substring(last, match.range.first)
            if (before.isNotBlank()) {
                segments.add(Segment(before, narrationPool.randomOrNull() ?: narrationFallback))
            }
            val quote = match.value
            val inner = quote.substring(1, quote.length - 1)
            if (inner.isNotBlank()) {
                segments.add(Segment(inner, dialoguePool.randomOrNull() ?: dialogueFallback))
            }
            last = match.range.last + 1
        }
        val tail = text.substring(last)
        if (tail.isNotBlank()) {
            segments.add(Segment(tail, narrationPool.randomOrNull() ?: narrationFallback))
        }
        if (segments.isEmpty()) {
            segments.add(Segment(text, current))
        }
        return segments
    }

    private fun pickable(key: String, excludeDialogue: Boolean): List<BdSpeakerRecord> {
        val ids = appCtx.getPrefStringSet(key).orEmpty()
        if (ids.isEmpty()) {
            return emptyList()
        }
        return BdSpeakerStore.load().filter { it.id in ids }
    }

    private fun firstOf(pool: List<BdSpeakerRecord>): BdSpeakerRecord? = pool.firstOrNull()
}
