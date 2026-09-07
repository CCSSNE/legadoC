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
    var estimated: Boolean = false,
    /**
     * 最后一次请求的真实 prompt（= 当时上下文）；-1 表示未知。
     * 只作"最后一笔"取值，不参与 [add] 求和：会话上下文锚点取最新一张卡，不能累加。
     */
    var ctxAnchor: Long = -1
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
 * 单轮卡正文 6 行：收起摘要 / in 行 / ctx 行 / out 行 / total 行 / 隐藏元数据行（界面不显示）。
 * 口径：轮 = 用户一句话+一次回答（会话轮数）；步 = 模型请求次数；
 * 速度与 ms/t 按各自阶段耗时计算；ctx = 最近一次请求的真实 prompt（上下文锚点）。
 */
object AiUsageFormat {
    private val metaRegex = Regex("steps=(\\d+) tool=(\\d+)(?: ctx=(\\d+))?")

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

    fun speed(tokens: Long, ms: Long): String {
        if (tokens <= 0 || ms <= 0) return "--"
        val tps = tokens * 1000.0 / ms
        // DSH 同款：≥10 取整，<10 保留一位小数。
        val value = if (tps >= 10) String.format(Locale.US, "%,d", tps.toLong())
        else String.format(Locale.US, "%.1f", tps)
        return "${value}t/s"
    }

    fun msPerToken(ms: Long, tokens: Long): String =
        if (tokens <= 0 || ms <= 0) "--"
        else String.format(Locale.US, "%.1f", ms.toDouble() / tokens) + "ms/t"

    /**
     * 收起摘要行：总 token / 输出速度 / 首字。
     * 速度 = 输出 token ÷ 请求总时长（供应商可能静默思考后一次性吐字，
     * "总时长−首字"的纯解码窗口会塌缩成荒谬的几千 t/s，不作分母）。
     * 首字口径与 DSH 轮尾一致 = 本轮第一步的首 token 延迟（firstTtftMs；缺省回退累计 ttftMs）。
     */
    fun collapsed(totals: AiUsageTotals, firstTtftMs: Long = totals.ttftMs): String =
        "total-${tokens(totals.inTokens + totals.outTokens)}" +
            " ${speed(totals.outTokens, totals.llmMs)} ${duration(firstTtftMs)}" +
            if (totals.estimated) " <e>" else ""

    fun inRow(totals: AiUsageTotals): String =
        "in-${tokens(totals.inTokens)} c-${tokens(totals.cachedTokens)}" +
            " ${speed(totals.inTokens, totals.ttftMs)} ${msPerToken(totals.ttftMs, totals.inTokens)}" +
            " ${duration(totals.ttftMs)}"

    fun outRow(totals: AiUsageTotals): String =
        "out-${tokens(totals.outTokens)} ${speed(totals.outTokens, totals.llmMs)}" +
            " ${msPerToken(totals.llmMs, totals.outTokens)} ${duration(totals.llmMs)}"

    fun totalRow(totals: AiUsageTotals): String = "total-${tokens(totals.inTokens + totals.outTokens)}"

    /** 上下文行：单轮卡传真实锚点（approx=false）；总计卡传锚点+增量估算值（approx=true 加 ~ 号）。 */
    fun ctxRow(tokens: Long, approx: Boolean): String =
        (if (approx) "~" else "") + "ctx-${tokens(tokens)}"

    /** 与 model.js 同口径的字符估 token（len/4 上取整）；只用于上下文锚点之间的增量估算。 */
    fun estimate(text: String): Long = ((text.length + 3) / 4).toLong()

    /** 会话总计卡首行：轮 = 会话轮数，步 = 模型请求总次数；轮步只出现在这里。 */
    fun header(totals: AiUsageTotals): String =
        "${totals.rounds}-Turn ${totals.steps}-Step | LLM-${duration(totals.llmMs)} Tool-${duration(totals.toolMs)}"

    /** 单轮隐藏元数据行：本轮流模型步数、工具耗时与上下文锚点，供总计卡汇总，界面不渲染。 */
    fun meta(totals: AiUsageTotals): String =
        "steps=${totals.steps} tool=${totals.toolMs}" +
            if (totals.ctxAnchor >= 0) " ctx=${totals.ctxAnchor}" else ""

    /** 解析单轮统计卡正文；只认自己渲染的固定格式，解析失败返回 null（不计入会话总计）。 */
    fun parseTurnCard(content: String): AiUsageTotals? {
        val lines = content.lines()
        if (lines.size < 6) return null
        val inParts = lines[1].split(' ')
        val outParts = lines[3].split(' ')
        if (inParts.size < 5 || outParts.size < 4) return null
        val meta = metaRegex.find(lines[5]) ?: return null
        return AiUsageTotals(
            inTokens = tokenCount(inParts[0], "in-") ?: return null,
            cachedTokens = tokenCount(inParts[1], "c-") ?: 0,
            outTokens = tokenCount(outParts[0], "out-") ?: return null,
            ttftMs = parseDuration(inParts.last()),
            llmMs = parseDuration(outParts.last()),
            rounds = 0,
            steps = meta.groupValues[1].toIntOrNull() ?: 0,
            toolMs = meta.groupValues[2].toLongOrNull() ?: 0,
            estimated = lines[0].endsWith("<e>"),
            ctxAnchor = meta.groupValues[3].toLongOrNull() ?: -1
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
