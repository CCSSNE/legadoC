package io.legado.app.ui.main.ai

import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import io.legado.app.BuildConfig
import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.help.agent.AgentStore
import io.legado.app.help.ai.AiChatService
import io.legado.app.help.config.AppConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import splitties.init.appCtx
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class AiChatViewModel : ViewModel() {

    private val pendingThinkingLabel = appCtx.getString(R.string.ai_restore_thinking)

    val messagesLiveData = MutableLiveData<List<AiChatMessage>>(emptyList())
    val requestingLiveData = MutableLiveData(false)
    var isRequesting = false
        private set

    private val messages = mutableListOf<AiChatMessage>()
    private var currentSessionId: String = AppConfig.aiCurrentChatSessionId ?: UUID.randomUUID().toString()

    companion object {
        private val requestScope = CoroutineScope(SupervisorJob() + IO)
        private var activeJob: Job? = null
        private var activeSessionId: String? = null
        private var activeViewModel: AiChatViewModel? = null
        private var activePendingContent: String = ""
        private var activeThinkingMessageId: String? = null
        private var activePendingAssistantMessageId: String? = null
        private const val TOTAL_CARD_ID = "usage-total"
    }

    private data class ToolCallRecord(
        var title: String,
        var args: String = "",
        var stage: String = "running",
        var summary: String = "",
        var success: Boolean = true,
        var elapsedMs: Long = -1L
    )

    private data class TurnTrace(
        val calls: LinkedHashMap<String, ToolCallRecord> = LinkedHashMap(),
        var rounds: Int = 0,
        var modelMs: Long = 0L,
        var promptBase: org.json.JSONObject? = null,
        var recalled: org.json.JSONArray? = null,
        var lastRequest: org.json.JSONObject? = null,
        val mainRequestIds: MutableSet<String> = mutableSetOf(),
        val usage: AiUsageTotals = AiUsageTotals()
    ) {
        /** 本轮第一步的首 token 延迟（首字口径与 DSH 轮尾一致）；-1 表示尚未记录。 */
        var firstTtftMs: Long = -1L

        /** 本轮最近一次请求的真实 prompt（= 当时上下文锚点）；-1 表示尚未记录。 */
        var lastPromptTokens: Long = -1L
    }

    private val turnTraces = mutableMapOf<String, TurnTrace>()
    private var activeTurnKey: String? = null

    init {
        restoreCurrentSession()
        activeViewModel = this
    }

    fun append(message: AiChatMessage) {
        messages.add(message)
        publish()
    }

    fun startRequest(
        userContent: String,
        thinkingText: String,
        cancelledText: String,
        failureMessage: (String) -> String,
        readingContext: org.json.JSONObject? = null
    ) {
        if (isRequesting || activeJob?.isActive == true) return
        setRequesting(true)
        // 会话总计卡只属于“最后一轮之下”，新一轮开始即移除，本轮结束时按各轮统计卡重算。
        messages.removeAll { it.id == TOTAL_CARD_ID }
        activeSessionId = currentSessionId
        val requestSessionId = currentSessionId
        activeViewModel = this
        activeThinkingMessageId = null
        activePendingAssistantMessageId = null
        append(AiChatMessage(role = AiChatMessage.Role.USER, content = userContent))
        val pendingMessage = AiChatMessage(
            role = AiChatMessage.Role.ASSISTANT,
            content = pendingThinkingLabel,
            pending = true
        )
        activePendingAssistantMessageId = pendingMessage.id
        activeTurnKey = pendingMessage.id
        turnTraces[pendingMessage.id] = TurnTrace()
        append(pendingMessage)
        activePendingContent = ""
        val requestMessages = snapshotForRequest()
        activeJob = requestScope.launch {
            val result = runCatching {
                io.legado.app.help.agent.AgentRuntime.chat(
                    sessionId = "chat:$requestSessionId",
                    assistantMessageId = pendingMessage.id,
                    readingContext = readingContext
                        ?: io.legado.app.help.agent.mcp.AgentReading.current(),
                    messages = requestMessages,
                    onPartial = { partial ->
                        activePendingContent = partial
                        targetFor(requestSessionId).upsertPendingAssistant(partial.ifBlank { "" })
                    },
                    onThinking = { thinking ->
                        targetFor(requestSessionId).upsertThinkingStatus(thinkingText, thinking)
                    },
                    onStatus = { status ->
                        targetFor(requestSessionId).upsertStatus(status)
                    }
                )
            }
            targetFor(requestSessionId).setRequesting(false)
            activeJob = null
            activeSessionId = null
            result.onSuccess { content ->
                activePendingContent = ""
                targetFor(requestSessionId).endTurn(pendingMessage.id, stopped = false)
                targetFor(requestSessionId).replacePendingAssistant(content.ifBlank { pendingThinkingLabel })
            }.onFailure { throwable ->
                activePendingContent = ""
                if (throwable is CancellationException) {
                    targetFor(requestSessionId).endTurn(pendingMessage.id, stopped = true)
                    targetFor(requestSessionId).replacePendingAssistant(cancelledText)
                    return@onFailure
                }
                val chatError = throwable as? AiChatException ?: AiChatException(
                    message = throwable.localizedMessage ?: throwable.javaClass.simpleName,
                    debugLog = throwable.stackTraceToString(),
                    cause = throwable
                )
                AppLog.put("AI 请求失败\n${chatError.debugLog}", chatError)
                targetFor(requestSessionId).endTurn(pendingMessage.id, stopped = true)
                targetFor(requestSessionId).failPendingAssistant(failureMessage(chatError.message))
            }
        }
    }

    fun stopRequest(cancelledText: String) {
        val job = activeJob ?: return
        activeSessionId?.let { io.legado.app.help.agent.AgentRuntime.stop("chat:$it") }
        job.cancel(CancellationException("User stopped generation"))
        activeJob = null
        activeSessionId = null
        activePendingContent = ""
        activeThinkingMessageId = null
        activePendingAssistantMessageId = null
        finishTurnCard(activeTurnKey, stopped = true)
        activeTurnKey = null
        setRequesting(false)
        if (cancelledText.isNotBlank()) {
            replacePendingAssistant(cancelledText)
        }
    }

    fun replacePendingAssistant(content: String) {
        upsertPendingAssistant(content)
        finishPendingAssistant()
    }

    fun upsertPendingAssistant(content: String) {
        val messageId = activePendingAssistantMessageId
        val index = messageId?.let { id -> messages.indexOfFirst { it.id == id } } ?: -1
        if (index >= 0) {
            messages[index] = messages[index].copy(content = content, pending = true)
        } else {
            val newMessage = AiChatMessage(
                role = AiChatMessage.Role.ASSISTANT,
                content = content,
                pending = true
            )
            activePendingAssistantMessageId = newMessage.id
            messages.add(newMessage)
        }
        publish()
    }

    fun upsertThinkingStatus(thinkingTitle: String, thinking: String) {
        if (activePendingContent.isNotBlank()) return
        val messageId = activePendingAssistantMessageId ?: return
        val index = messages.indexOfFirst { it.id == messageId }
        if (index >= 0) {
            messages[index] = messages[index].copy(
                content = pendingThinkingLabel,
                pending = true
            )
            publish()
        }
    }

    fun upsertStatus(status: org.json.JSONObject) {
        when (status.optString("type")) {
            "paused", "waiting_input" -> {
                val content = if (status.optString("type") == "paused") {
                    "运行已暂停；请在 Agent 模式的运行诊断中继续或停止"
                } else {
                    "等待输入：${status.optString("prompt", status.toString())}"
                }
                val last = messages.lastOrNull()
                if (last?.kind == AiChatMessage.Kind.STATUS && last.content == content) return
                append(AiChatMessage(role = AiChatMessage.Role.ASSISTANT, content = content,
                    kind = AiChatMessage.Kind.STATUS, statusName = status.optString("type"),
                    statusStage = status.optString("type")))
                return
            }
            "tool.start", "tool.result", "tool.unknown", "model.request", "model.response", "model.usage", "prompt.context", "memory.recalled" -> Unit
            else -> return
        }
        val turnKey = activeTurnKey ?: return
        val trace = turnTraces.getOrPut(turnKey) { TurnTrace() }
        when (status.optString("type")) {
            "tool.start" -> {
                val key = status.optString("invocationId", status.optString("runId"))
                trace.calls[key] = ToolCallRecord(
                    title = if (status.has("toolId")) "${status.optString("moduleId")}/${status.getString("toolId")}" else "工具",
                    args = compactJson(status.optJSONObject("arguments")?.toString().orEmpty())
                )
            }
            "tool.result" -> {
                val key = status.optString("invocationId", status.optString("runId"))
                val result = status.optJSONObject("result") ?: org.json.JSONObject()
                val record = trace.calls.getOrPut(key) {
                    ToolCallRecord(
                        title = if (status.has("toolId")) "${status.optString("moduleId")}/${status.getString("toolId")}" else "工具")
                }
                record.stage = "done"
                record.success = !result.optBoolean("isError", false)
                record.elapsedMs = result.optJSONObject("_meta")?.optJSONObject("legado")?.optLong("elapsedMs", -1L) ?: -1L
                record.summary = summarizeResult(result)
            }
            "tool.unknown" -> {
                val key = status.optString("invocationId", status.optString("runId"))
                val record = trace.calls.getOrPut(key) { ToolCallRecord(title = "工具") }
                record.stage = "unknown"
                record.success = false
                record.summary = "结果未知，未自动重放"
            }
            "model.response" -> {
                // 只认主循环（display=true）的累计耗时与轮数：记忆提取/压缩是附带调用。
                if (trace.mainRequestIds.remove(status.optString("requestId"))) {
                    trace.rounds += 1
                    trace.modelMs += status.optLong("elapsedMs", 0L)
                }
            }
            "model.usage" -> {
                // 只累计主循环（display=true）的用量；记忆提取/压缩等附带调用不进统计。
                if (status.optBoolean("display", false)) {
                    trace.usage.inTokens += status.optLong("promptTokens", 0L)
                    trace.usage.outTokens += status.optLong("completionTokens", 0L)
                    trace.usage.cachedTokens += status.optLong("cachedTokens", 0L)
                    trace.usage.ttftMs += status.optLong("ttftMs", 0L)
                    trace.usage.llmMs += status.optLong("elapsedMs", 0L)
                    if (trace.firstTtftMs < 0) trace.firstTtftMs = status.optLong("ttftMs", 0L)
                    trace.lastPromptTokens = status.optLong("promptTokens", 0L)
                    if (status.optBoolean("estimated", false)) trace.usage.estimated = true
                }
            }
            // 注意：emit 事件的 value 就是事件本体（宿主入库前已解包），
            // 展示层直接读本体字段，只剥掉 runId/sequence/type 信封。
            "prompt.context" -> {
                trace.promptBase = org.json.JSONObject().apply {
                    status.keys().forEach { key ->
                        if (key !in setOf("runId", "sequence", "type")) put(key, status.get(key))
                    }
                }
            }
            "memory.recalled" -> {
                trace.recalled = status.optJSONArray("matches")
            }
            "model.request" -> {
                // display=true 的才是注入本轮问答的主循环请求（附带调用无此标记）；
                // 若插件/旧运行没打标记，有工具表的是主循环（附带调用 tools 为空）。
                val main = if (status.has("display")) status.optBoolean("display", false)
                    else status.optJSONObject("body")?.optJSONArray("tools")?.length() != 0
                if (main) {
                    status.optString("requestId").takeIf { it.isNotBlank() }?.let { trace.mainRequestIds.add(it) }
                    trace.lastRequest = status.optJSONObject("body")
                }
            }
        }
        refreshTurnCards(turnKey, trace)
    }

    private fun refreshTurnCards(turnKey: String, trace: TurnTrace) {
        renderPromptContext(trace)?.let { text ->
            upsertCard(id = "ctx:$turnKey", kind = AiChatMessage.Kind.CONTEXT, content = text)
        }
        if (trace.calls.isNotEmpty()) {
            upsertCard(id = "tools:$turnKey", kind = AiChatMessage.Kind.TOOLS, content = renderToolsCard(trace))
        }
        if (trace.usage.inTokens > 0 || trace.usage.outTokens > 0) {
            upsertCard(id = "stats:$turnKey", kind = AiChatMessage.Kind.STATS,
                content = renderTurnStats(trace), atEnd = true)
            // 会话总计 = 各轮统计卡之和；当前轮的统计卡刚写入，直接参与汇总。
            upsertCard(id = TOTAL_CARD_ID, kind = AiChatMessage.Kind.TOTAL,
                content = renderSessionTotal(), atEnd = true)
        }
    }

    private fun endTurn(turnKey: String, stopped: Boolean) {
        if (activeTurnKey == turnKey) activeTurnKey = null
        finishTurnCard(turnKey, stopped)
    }

    private fun finishTurnCard(turnKey: String?, stopped: Boolean) {
        if (turnKey == null) return
        val trace = turnTraces[turnKey] ?: return
        if (!stopped) return
        var changed = false
        trace.calls.values.forEach { record ->
            if (record.stage == "running") {
                record.stage = "stopped"
                record.success = false
                record.summary = "任务已停止"
                changed = true
            }
        }
        if (changed) refreshTurnCards(turnKey, trace)
    }

    private fun upsertCard(
        id: String,
        kind: AiChatMessage.Kind,
        content: String,
        atEnd: Boolean = false
    ) {
        val index = messages.indexOfFirst { it.id == id }
        val message = AiChatMessage(id = id, role = AiChatMessage.Role.ASSISTANT,
            content = content, kind = kind)
        if (index >= 0) {
            messages[index] = message
        } else if (atEnd) {
            // 用量统计与总计卡贴在最新一轮回复之下，始终追加到列表末尾。
            messages.add(message)
        } else {
            // 卡片属于本轮追问，插到正在流式输出的气泡之前，不抢最终回答的位置。
            val pending = activePendingAssistantMessageId?.let { pid ->
                messages.indexOfFirst { it.id == pid }
            } ?: -1
            if (pending >= 0) messages.add(pending, message) else messages.add(message)
        }
        publish(saveHistory = false)
    }

    private fun renderToolsCard(trace: TurnTrace): String {
        val done = trace.calls.values.count { it.stage == "done" }
        val toolMs = trace.calls.values.filter { it.elapsedMs >= 0 }.sumOf { it.elapsedMs }
        val header = "${trace.calls.size} 次工具调用"
        val debug = "已完成 ${done}/${trace.calls.size} · 模型${AiUsageFormat.duration(trace.modelMs)} · 工具${AiUsageFormat.duration(toolMs)}"
        val detail = trace.calls.values.joinToString("\n") { record ->
            val mark = when (record.stage) {
                "done" -> if (record.success) "✓" else "✗"
                "unknown" -> "?"
                "stopped" -> "■"
                else -> "…"
            }
            val args = record.args.ifBlank { "" }.let { if (it.isNotBlank()) " $it" else "" }
            val elapsed = if (record.elapsedMs >= 0) " · ${AiUsageFormat.duration(record.elapsedMs)}" else ""
            val tail = record.summary.ifBlank { "" }.let { if (it.isNotBlank()) "\n  $it" else "" }
            "$mark ${record.title}$args$elapsed$tail"
        }
        return "$header\n$debug\n$detail"
    }

    /** 单轮用量统计卡：收起一行摘要，展开显示 in/ctx/out/total；ctx = 本轮最后一次请求的真实输入。 */
    private fun renderTurnStats(trace: TurnTrace): String {
        val totals = AiUsageTotals(
            inTokens = trace.usage.inTokens,
            cachedTokens = trace.usage.cachedTokens,
            outTokens = trace.usage.outTokens,
            ttftMs = trace.usage.ttftMs,
            llmMs = trace.usage.llmMs,
            steps = trace.rounds.coerceAtLeast(1),
            toolMs = trace.calls.values.filter { it.elapsedMs >= 0 }.sumOf { it.elapsedMs },
            estimated = trace.usage.estimated,
            ctxAnchor = trace.lastPromptTokens
        )
        return listOf(
            AiUsageFormat.collapsed(totals, if (trace.firstTtftMs >= 0) trace.firstTtftMs else totals.ttftMs),
            AiUsageFormat.inRow(totals),
            AiUsageFormat.ctxRow(totals.ctxAnchor.coerceAtLeast(0), approx = false),
            AiUsageFormat.outRow(totals),
            AiUsageFormat.totalRow(totals),
            AiUsageFormat.meta(totals)
        ).joinToString("\n")
    }

    /**
     * 会话总计卡：轮 = 统计卡张数，步 = 各轮流模型步数之和，速度为加总后的平均。
     * ctx = 锚点（最后一张统计卡记录的最后一次请求真实 prompt）+ 其后新增的助手正文字符估算，
     * 即对下一次请求输入的预测；未发送的输入框草稿不计入（与 DSH 圈圈一致）。
     */
    private fun renderSessionTotal(): String {
        val totals = AiUsageTotals()
        var turns = 0
        var anchor = -1L
        messages.filter { (it.kind ?: AiChatMessage.Kind.TEXT) == AiChatMessage.Kind.STATS }
            .forEach { card ->
                AiUsageFormat.parseTurnCard(card.content)?.let {
                    totals.add(it)
                    turns += 1
                    if (it.ctxAnchor >= 0) anchor = it.ctxAnchor
                }
            }
        totals.rounds = turns
        val lines = mutableListOf(
            AiUsageFormat.header(totals) + if (totals.estimated) " <e>" else "",
            AiUsageFormat.inRow(totals)
        )
        if (anchor >= 0) {
            val tail = messages.lastOrNull {
                it.role == AiChatMessage.Role.ASSISTANT && (it.kind ?: AiChatMessage.Kind.TEXT) == AiChatMessage.Kind.TEXT
            }?.content.orEmpty()
            lines.add(AiUsageFormat.ctxRow(anchor + AiUsageFormat.estimate(tail), approx = true))
        }
        lines.add(AiUsageFormat.outRow(totals))
        lines.add(AiUsageFormat.totalRow(totals))
        return lines.joinToString("\n")
    }

    /**
     * 上下文注入卡：只显示实际存在的东西，不存在的不占位、不强调；
     * 存在的东西一律全文展示（系统全文以实际发出的 request body 为准）。
     */
    private fun renderPromptContext(trace: TurnTrace): String? {
        val base = trace.promptBase
        val body = trace.lastRequest
        // 系统全文只认实际发出去的那条 system 消息（body.messages[0] 即 context.build 结果）。
        val sentSystem = body?.optJSONArray("messages")?.let { messages ->
            (0 until messages.length()).mapNotNull { messages.optJSONObject(it) }
                .firstOrNull { it.optString("role") == "system" }
                ?.let { extractContentText(it.opt("content")) }
        }
        val key = base?.optString("systemKey").orEmpty()
        // 字数只认实际发出的 system 全文；body 还没到时先按事件里的计数占位。
        val chars = sentSystem?.length ?: base?.optInt("systemChars", 0) ?: 0
        val hasSystem = sentSystem != null || key.isNotBlank() || chars > 0
        val skills = base?.optJSONArray("skills")?.let { array ->
            (0 until array.length()).mapNotNull { index ->
                when (val item = array.opt(index)) {
                    is String -> item.takeIf { it.isNotBlank() }?.let { it to "" }
                    is org.json.JSONObject -> item.optString("key")
                        .takeIf { it.isNotBlank() }
                        ?.let { it to item.optString("content") }
                    else -> null
                }
            }
        }.orEmpty()
        val memories = trace.recalled?.let { array ->
            (0 until array.length()).mapNotNull { array.optJSONObject(it) }
        }.orEmpty()
        val tools = body?.optJSONArray("tools")?.let { array ->
            (0 until array.length()).mapNotNull { array.optJSONObject(it)?.optJSONObject("function") }
        }.orEmpty()
        if (!hasSystem && skills.isEmpty() && memories.isEmpty() && tools.isEmpty()) return null
        val summary = buildList {
            if (hasSystem) add("系统提示词 " + (if (key.isNotBlank()) "$key " else "") + "（${chars}字）")
            if (skills.isNotEmpty()) add("Skill ${skills.size} 个")
            if (memories.isNotEmpty()) add("记忆 ${memories.size} 条")
            if (tools.isNotEmpty()) add("MCP 工具 ${tools.size} 个")
        }.joinToString(" · ")
        val detail = buildList {
            if (sentSystem != null) {
                add("系统提示词" + (if (key.isNotBlank()) " $key" else "") + "（${sentSystem.length}字）\n$sentSystem")
            } else if (hasSystem) {
                add("系统提示词" + (if (key.isNotBlank()) " $key" else "") + "（${chars}字）")
            }
            skills.forEach { (name, content) ->
                add(if (content.isNotBlank()) "Skill $name（${content.length}字）\n$content" else "Skill $name")
            }
            memories.forEach {
                val content = it.optString("content")
                add(if (content.isNotBlank()) {
                    "记忆 ${it.optString("id")}（${it.optDouble("score", 0.0)}，${content.length}字）\n$content"
                } else {
                    "记忆 ${it.optString("id")}（${it.optDouble("score", 0.0)}）"
                })
            }
            val reading = base?.optJSONObject("reading")
            val readingLine = reading?.let {
                if (it.optBoolean("open", false)) {
                    "阅读快照：${it.optString("bookName")} · ${it.optString("chapterTitle")}"
                } else null
            }
            if (readingLine != null) add(readingLine)
            if (tools.isNotEmpty()) {
                add("MCP 工具 ${tools.size} 个")
                tools.forEach { fn ->
                    val title = fn.optString("description")
                    val schema = fn.optJSONObject("parameters")?.toString(2).orEmpty()
                    add("$title\n参数：$schema")
                }
            }
        }.joinToString("\n\n")
        return "上下文注入\n$summary\n$detail"
    }

    private fun extractContentText(content: Any?): String = when (content) {
        is String -> content
        is org.json.JSONArray -> buildString {
            for (index in 0 until content.length()) {
                val part = content.opt(index)
                if (part is org.json.JSONObject) append(part.optString("text"))
                else if (part is String) append(part)
            }
        }
        is org.json.JSONObject -> content.optString("text")
        else -> ""
    }

    private fun summarizeResult(result: org.json.JSONObject): String {
        if (result.optBoolean("isError", false)) {
            val structured = result.optJSONObject("structuredContent")
            val error = structured?.optString("error").orEmpty()
                .ifBlank { result.optString("error").ifBlank { "调用失败" } }
            return "失败：${singleLine(error).take(120)}"
        }
        val structured = result.optJSONObject("structuredContent")
        if (structured != null) {
            val keys = structured.keys().asSequence().toList()
            return if (keys.isEmpty()) "成功" else "成功：${singleLine(keys.joinToString("、")).take(120)}"
        }
        return singleLine(result.optString("content").ifBlank { "成功" }).take(120)
    }

    private fun compactJson(raw: String): String =
        singleLine(raw).take(80).trim().removePrefix("{").removeSuffix("}").trim()

    private fun singleLine(text: String): String =
        text.replace(Regex("\\s+"), " ").trim()

    fun finishPendingAssistant() {
        val messageId = activePendingAssistantMessageId
        val index = messageId?.let { id -> messages.indexOfFirst { it.id == id } } ?: -1
        if (index >= 0) {
            messages[index] = messages[index].copy(pending = false)
            publish()
        }
        activePendingAssistantMessageId = null
    }

    fun failPendingAssistant(content: String) {
        val messageId = activePendingAssistantMessageId
        val index = messageId?.let { id -> messages.indexOfFirst { it.id == id } } ?: -1
        if (index >= 0) {
            messages[index] = messages[index].copy(content = content, pending = false)
        } else {
            messages.add(AiChatMessage(role = AiChatMessage.Role.ASSISTANT, content = content))
        }
        activePendingAssistantMessageId = null
        publish()
    }

    fun clearCurrentSession() {
        messages.clear()
        AppConfig.aiChatSessionList =
            AppConfig.aiChatSessionList.filterNot { it.id == currentSessionId }
        currentSessionId = UUID.randomUUID().toString()
        AppConfig.aiCurrentChatSessionId = currentSessionId
        publish(saveHistory = false)
    }

    fun startNewSession() {
        currentSessionId = UUID.randomUUID().toString()
        AppConfig.aiCurrentChatSessionId = currentSessionId
        messages.clear()
        setRequesting(false)
        publish(saveHistory = false)
    }

    fun historySessions(): List<AiChatSession> {
        return AppConfig.aiChatSessionList.sortedByDescending { it.updatedAt }
    }

    /**
     * 导出当前会话完整原始数据：info + 协议消息全量 + 每次运行的完整事件。
     * 不设大小上限、不过滤事件类型：全部事件原样保留，文件流式写入，多大都导出。
     */
    fun exportCurrentChat(onResult: (File) -> Unit, onError: (String) -> Unit) {
        requestScope.launch {
            runCatching {
                val sessionId = "chat:$currentSessionId"
                val messageRows = AgentStore.dao.messages(sessionId)
                val runs = AgentStore.dao.runs().filter { it.sessionId == sessionId }
                if (messageRows.isEmpty() && runs.isEmpty()) {
                    error("当前对话还没有可导出的数据")
                }
                var promptTokens = 0L
                var completionTokens = 0L
                var cachedTokens = 0L
                var billedRequests = 0
                // 用量统计单走一遍事件，只解析 model.usage 小对象，不保留事件，内存不随导出体积增长。
                runs.forEach { run ->
                    AgentStore.dao.events(run.id).forEach { event ->
                        if (event.type == "model.usage") {
                            val value = org.json.JSONObject(event.json)
                            if (value.optBoolean("display", false)) {
                                billedRequests += 1
                                promptTokens += value.optLong("promptTokens", 0L)
                                completionTokens += value.optLong("completionTokens", 0L)
                                cachedTokens += value.optLong("cachedTokens", 0L)
                            }
                        }
                    }
                }
                val session = AppConfig.aiChatSessionList.firstOrNull { it.id == currentSessionId }
                val info = org.json.JSONObject()
                    .put("id", sessionId)
                    .put("title", session?.title ?: "")
                    .put("model", AppConfig.aiCurrentModelConfig?.modelId ?: "")
                    .put("provider", AppConfig.aiCurrentModelConfig?.providerId ?: "")
                    .put("appVersion", BuildConfig.VERSION_NAME)
                    .put("time", org.json.JSONObject().put("created", session?.updatedAt ?: 0L)
                        .put("updated", session?.updatedAt ?: 0L))
                    .put("usage", org.json.JSONObject().put("promptTokens", promptTokens)
                        .put("completionTokens", completionTokens).put("cachedTokens", cachedTokens)
                        .put("billedRequests", billedRequests).put("runs", runs.size))
                val dir = File(appCtx.cacheDir, "export").apply { mkdirs() }
                val safeTitle = (session?.title ?: "chat").replace(Regex("[\\\\/:*?\"<>|]"), "_")
                    .take(24).trim().ifBlank { "chat" }
                val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
                val file = File(dir, "ai-chat-$safeTitle-$stamp.json")
                try {
                    file.outputStream().bufferedWriter(Charsets.UTF_8).use { w ->
                        w.write("{\"info\":")
                        w.write(info.toString())
                        w.write(",\"messages\":[")
                        messageRows.forEachIndexed { index, row ->
                            if (index > 0) w.write(",")
                            w.write(row.json.ifBlank { "null" })
                        }
                        w.write("],\"runs\":[")
                        runs.forEachIndexed { runIndex, run ->
                            if (runIndex > 0) w.write(",")
                            w.write("{\"id\":")
                            w.write(org.json.JSONObject.quote(run.id))
                            w.write(",\"plugin\":")
                            w.write(org.json.JSONObject.quote(run.pluginId))
                            w.write(",\"state\":")
                            w.write(org.json.JSONObject.quote(run.state))
                            w.write(",\"input\":")
                            w.write(run.input.ifBlank { "null" })
                            w.write(",\"events\":[")
                            AgentStore.dao.events(run.id).forEachIndexed { eventIndex, event ->
                                if (eventIndex > 0) w.write(",")
                                w.write("{\"sequence\":")
                                w.write(event.sequence.toString())
                                w.write(",\"type\":")
                                w.write(org.json.JSONObject.quote(event.type))
                                w.write(",\"value\":")
                                w.write(event.json.ifBlank { "null" })
                                w.write("}")
                            }
                            w.write("]}")
                        }
                        w.write("]}")
                    }
                } catch (e: Exception) {
                    runCatching { if (file.exists()) file.delete() }
                    throw e
                }
                file
            }.onSuccess(onResult).onFailure { onError(it.localizedMessage ?: it.toString()) }
        }
    }

    /** 供阅读页悬浮窗等外部同会话视图在前台恢复时与磁盘对齐。请求进行中不碰内存，避免打断流式。 */
    fun syncFromStore() {
        if (activeJob?.isActive == true) return
        val stored = AppConfig.aiCurrentChatSessionId
        if (stored != null && stored != currentSessionId) {
            loadSession(stored)
            return
        }
        val session = AppConfig.aiChatSessionList.firstOrNull { it.id == currentSessionId }
        if (session != null) {
            messages.clear()
            messages.addAll(session.messages.map { it.copy(pending = false) })
            setRequesting(false)
            publish(saveHistory = false)
        } else if (stored == null && messages.isNotEmpty()
            && AppConfig.aiChatSessionList.none { it.id == currentSessionId }) {
            // 当前内存会话在别处被删：回到空新会话，保持指针合法悬空。
            currentSessionId = java.util.UUID.randomUUID().toString()
            AppConfig.aiCurrentChatSessionId = currentSessionId
            messages.clear()
            setRequesting(false)
            publish(saveHistory = false)
        }
    }

    fun loadSession(sessionId: String) {
        val session = AppConfig.aiChatSessionList.firstOrNull { it.id == sessionId } ?: return
        currentSessionId = session.id
        AppConfig.aiCurrentChatSessionId = session.id
        messages.clear()
        messages.addAll(session.messages.map { it.copy(pending = false) })
        setRequesting(activeJob?.isActive == true && activeSessionId == currentSessionId)
        publish(saveHistory = false)
    }

    fun deleteSession(sessionId: String) {
        AppConfig.aiChatSessionList = AppConfig.aiChatSessionList.filterNot { it.id == sessionId }
        if (currentSessionId == sessionId) {
            currentSessionId = UUID.randomUUID().toString()
            AppConfig.aiCurrentChatSessionId = currentSessionId
            messages.clear()
            setRequesting(false)
            publish(saveHistory = false)
        }
    }

    fun clearAllSessions() {
        AppConfig.aiChatSessionList = emptyList()
        currentSessionId = UUID.randomUUID().toString()
        AppConfig.aiCurrentChatSessionId = currentSessionId
        messages.clear()
        setRequesting(false)
        publish(saveHistory = false)
    }

    fun snapshotForRequest(): List<AiChatMessage> {
        return messages.filterNot { it.pending || (it.kind ?: AiChatMessage.Kind.TEXT) == AiChatMessage.Kind.STATUS }
    }

    fun restoreCurrentSession() {
        val sessions = AppConfig.aiChatSessionList
        val session = sessions.firstOrNull { it.id == currentSessionId } ?: sessions.firstOrNull()
        if (session != null) {
            currentSessionId = session.id
            AppConfig.aiCurrentChatSessionId = session.id
            messages.addAll(session.messages.map { it.copy(pending = false) })
        } else {
            // 空历史时新会话 id 尚无落盘条目，属合法悬空（AgentHistory 允许），直接持久化以保持内存与磁盘一致。
            AppConfig.aiCurrentChatSessionId = currentSessionId
        }
        val requesting = activeJob?.isActive == true && activeSessionId == currentSessionId
        if (requesting && messages.none { it.role == AiChatMessage.Role.ASSISTANT && it.pending }) {
            val restored = AiChatMessage(
                role = AiChatMessage.Role.ASSISTANT,
                content = activePendingContent.ifBlank { pendingThinkingLabel },
                pending = true
            )
            activePendingAssistantMessageId = restored.id
            messages.add(restored)
        }
        setRequesting(requesting)
        publish(saveHistory = false)
    }

    override fun onCleared() {
        super.onCleared()
        if (activeViewModel === this) {
            activeViewModel = null
        }
    }

    private fun setRequesting(value: Boolean) {
        isRequesting = value
        requestingLiveData.postValue(value)
    }

    private fun targetFor(sessionId: String): AiChatViewModel {
        return activeViewModel?.takeIf { it.currentSessionId == sessionId } ?: this
    }

    private fun publish(saveHistory: Boolean = true) {
        if (saveHistory) {
            saveCurrentSession()
        }
        messagesLiveData.postValue(messages.toList())
    }

    private fun saveCurrentSession() {
        val snapshot = messages.filterNot { it.pending }
            .filterNot {
                (it.kind ?: AiChatMessage.Kind.TEXT) == AiChatMessage.Kind.STATUS &&
                    it.content.isBlank()
            }
            .map { it.copy(pending = false) }
            .filter { it.content.isNotBlank() }
        val history = AppConfig.aiChatSessionList.toMutableList()
        val index = history.indexOfFirst { it.id == currentSessionId }
        if (snapshot.isEmpty()) {
            if (index >= 0) {
                history.removeAt(index)
                AppConfig.aiChatSessionList = history
            }
            return
        }
        val session = AiChatSession(
            id = currentSessionId,
            title = resolveSessionTitle(snapshot),
            updatedAt = System.currentTimeMillis(),
            messages = snapshot
        )
        if (index >= 0) {
            history[index] = session
        } else {
            history.add(0, session)
        }
        AppConfig.aiChatSessionList = history.sortedByDescending { it.updatedAt }
        AppConfig.aiCurrentChatSessionId = currentSessionId
    }

    private fun resolveSessionTitle(messages: List<AiChatMessage>): String {
        val titleSource = messages.firstOrNull { it.role == AiChatMessage.Role.USER }?.content
            ?: messages.first().content
        return titleSource.replace("\n", " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .let {
                if (it.length > 24) "${it.take(24)}…" else it
            }
            .ifBlank { "AI Chat" }
    }
}
