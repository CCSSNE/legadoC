package io.legado.app.ui.main.ai

import androidx.annotation.Keep
import java.util.Locale
import java.util.UUID

@Keep
data class AiChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val role: Role,
    val content: String,
    val pending: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val kind: Kind? = Kind.TEXT,
    val statusName: String? = null,
    val statusStage: String? = null,
    val statusSuccess: Boolean = true
) {
    @Keep
    enum class Role {
        USER,
        ASSISTANT
    }

    @Keep
    enum class Kind {
        TEXT,
        STATUS,
        TOOLS,
        CONTEXT,
        STATS,
        TOTAL
    }
}

@Keep
data class AiChatSession(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val updatedAt: Long = System.currentTimeMillis(),
    val messages: List<AiChatMessage> = emptyList()
)

@Keep
open class AiChatException(
    override val message: String,
    val debugLog: String,
    cause: Throwable? = null
) : IllegalStateException(message, cause)

/** 单轮或会话累计的模型用量原始值；速度等衍生值在渲染时计算。 */
@Keep
data class AiUsageTotals(
    var inTokens: Long = 0,
    var cachedTokens: Long = 0,
    var outTokens: Long = 0,
    var ttftMs: Long = 0,
    var llmMs: Long = 0,
    var rounds: Int = 0,
    var steps: Int = 0,
    var toolMs: Long = 0,
    var estimated: Boolean = false
) {
    fun add(other: AiUsageTotals) {
        inTokens += other.inTokens
        cachedTokens += other.cachedTokens
        outTokens += other.outTokens
        ttftMs += other.ttftMs
        llmMs += other.llmMs
        rounds += other.rounds
        steps += other.steps
        toolMs += other.toolMs
        estimated = estimated || other.estimated
    }
}

/**
 * 用量统计卡的统一渲染与解析；正文格式即协议，渲染与解析必须同步修改。
 * 单轮卡正文固定 5 行：收起摘要 / in 行 / out 行 / total 行 / 轮步头。
 */
object AiUsageFormat {
    private val headerRegex = Regex("(\\d+) 轮 · (\\d+) 步 \\| LLM (\\S+) · Tool (\\S+)")

    fun tokens(count: Long): String = String.format(Locale.US, "%,d", count) + "t"

    fun duration(ms: Long): String = when {
        ms < 0 -> "--"
        ms < 1000 -> "${ms}ms"
        ms < 60_000 -> String.format(Locale.US, "%.1fs", ms / 1000.0)
        ms < 3_600_000 -> {
            val minutes = ms / 60_000
            val seconds = ms % 60_000 / 1000
            if (seconds > 0) "${minutes}m${seconds}s" else "${minutes}m"
        }
        else -> {
            val hours = ms / 3_600_000
            val minutes = ms % 3_600_000 / 60_000
            "${hours}h${minutes}m"
        }
    }

    fun speed(tokens: Long, ms: Long): String =
        if (tokens <= 0 || ms <= 0) "--"
        else String.format(Locale.US, "%,d", (tokens * 1000.0 / ms).toLong()) + "t/s"

    fun msPerToken(ms: Long, tokens: Long): String =
        if (tokens <= 0 || ms <= 0) "--"
        else String.format(Locale.US, "%.1f", ms.toDouble() / tokens) + "ms/t"

    fun collapsed(totals: AiUsageTotals): String =
        "total-${tokens(totals.inTokens + totals.outTokens)}" +
            " ${speed(totals.outTokens, totals.llmMs)} ${duration(totals.ttftMs)}" +
            if (totals.estimated) " <e>" else ""

    fun inRow(totals: AiUsageTotals): String =
        "in-${tokens(totals.inTokens)} c-${tokens(totals.cachedTokens)}" +
            " ${speed(totals.inTokens, totals.ttftMs)} ${msPerToken(totals.ttftMs, totals.inTokens)}" +
            " ${duration(totals.ttftMs)}"

    fun outRow(totals: AiUsageTotals): String =
        "out-${tokens(totals.outTokens)} ${speed(totals.outTokens, totals.llmMs)}" +
            " ${msPerToken(totals.llmMs, totals.outTokens)} ${duration(totals.llmMs)}"

    fun totalRow(totals: AiUsageTotals): String = "total-${tokens(totals.inTokens + totals.outTokens)}"

    fun header(totals: AiUsageTotals): String =
        "${totals.rounds} 轮 · ${totals.steps} 步 | LLM ${duration(totals.llmMs)} · Tool ${duration(totals.toolMs)}"

    /** 解析单轮统计卡正文；只认自己渲染的固定格式，解析失败返回 null（不计入会话总计）。 */
    fun parseTurnCard(content: String): AiUsageTotals? {
        val lines = content.lines()
        if (lines.size < 5) return null
        val inParts = lines[1].split(' ')
        val outParts = lines[2].split(' ')
        if (inParts.size < 5 || outParts.size < 4) return null
        val header = headerRegex.find(lines[4]) ?: return null
        return AiUsageTotals(
            inTokens = tokenCount(inParts[0], "in-") ?: return null,
            cachedTokens = tokenCount(inParts[1], "c-") ?: 0,
            outTokens = tokenCount(outParts[0], "out-") ?: return null,
            ttftMs = parseDuration(inParts.last()),
            llmMs = parseDuration(outParts.last()),
            rounds = header.groupValues[1].toIntOrNull() ?: 0,
            steps = header.groupValues[2].toIntOrNull() ?: 0,
            toolMs = parseDuration(header.groupValues[4]),
            estimated = lines[0].endsWith("<e>")
        )
    }

    private fun tokenCount(token: String, prefix: String): Long? =
        token.removePrefix(prefix).removeSuffix("t").replace(",", "").toLongOrNull()

    private fun parseDuration(text: String): Long {
        if (text == "--") return 0
        Regex("(\\d+)ms").find(text)?.groupValues?.get(1)?.toLongOrNull()?.let { return it }
        val hours = Regex("(\\d+)h").find(text)?.groupValues?.get(1)?.toLongOrNull() ?: 0
        val minutes = Regex("(\\d+)m(?!s)").find(text)?.groupValues?.get(1)?.toLongOrNull() ?: 0
        val seconds = Regex("(\\d+(?:\\.\\d+)?)s").find(text)?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0
        return ((hours * 3600 + minutes * 60 + seconds) * 1000).toLong()
    }
}
