package io.legado.app.ui.main.ai

import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.help.ai.AiChatService
import io.legado.app.help.config.AppConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import splitties.init.appCtx
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
        var recallSkipped: String? = null
    )

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
        failureMessage: (String) -> String
    ) {
        if (isRequesting || activeJob?.isActive == true) return
        setRequesting(true)
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
            "tool.start", "tool.result", "tool.unknown", "model.response", "prompt.context", "memory.recalled" -> Unit
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
                trace.rounds += 1
                trace.modelMs += status.optLong("elapsedMs", 0L)
            }
            "prompt.context" -> {
                trace.promptBase = status.optJSONObject("value") ?: org.json.JSONObject()
                trace.recalled = null
                trace.recallSkipped = null
            }
            "memory.recalled" -> {
                val value = status.optJSONObject("value") ?: org.json.JSONObject()
                trace.recalled = value.optJSONArray("matches")
                trace.recallSkipped = value.optString("skipped").ifBlank { null }
            }
        }
        refreshTurnCards(turnKey, trace)
    }

    private fun refreshTurnCards(turnKey: String, trace: TurnTrace) {
        trace.promptBase?.let { base ->
            upsertCard(id = "ctx:$turnKey", kind = AiChatMessage.Kind.CONTEXT,
                content = renderPromptContext(base, trace))
        }
        if (trace.calls.isNotEmpty()) {
            upsertCard(id = "tools:$turnKey", kind = AiChatMessage.Kind.TOOLS, content = renderToolsCard(trace))
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

    private fun upsertCard(id: String, kind: AiChatMessage.Kind, content: String) {
        val index = messages.indexOfFirst { it.id == id }
        val message = AiChatMessage(id = id, role = AiChatMessage.Role.ASSISTANT,
            content = content, kind = kind)
        if (index >= 0) {
            messages[index] = message
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
        val debug = "第${trace.rounds.coerceAtLeast(1)}轮 · ${done}步完成 · 模型${formatSeconds(trace.modelMs)} · 工具${formatSeconds(toolMs)}"
        val detail = trace.calls.values.joinToString("\n") { record ->
            val mark = when (record.stage) {
                "done" -> if (record.success) "✓" else "✗"
                "unknown" -> "?"
                "stopped" -> "■"
                else -> "…"
            }
            val args = record.args.ifBlank { "" }.let { if (it.isNotBlank()) " $it" else "" }
            val elapsed = if (record.elapsedMs >= 0) " · ${formatSeconds(record.elapsedMs)}" else ""
            val tail = record.summary.ifBlank { "" }.let { if (it.isNotBlank()) "\n  $it" else "" }
            "$mark ${record.title}$args$elapsed$tail"
        }
        return "$header\n$debug\n$detail"
    }

    private fun renderPromptContext(base: org.json.JSONObject, trace: TurnTrace): String {
        val skills = base.optJSONArray("skills")?.let { array ->
            (0 until array.length()).mapNotNull { array.optString(it).takeIf { s -> s.isNotBlank() } }
        }.orEmpty()
        val memories = trace.recalled?.let { array ->
            (0 until array.length()).mapNotNull { array.optJSONObject(it) }
        }.orEmpty()
        val reading = base.optJSONObject("reading")
        val header = "上下文注入"
        val memorySummary = trace.recallSkipped?.let { "记忆跳过（$it）" } ?: "记忆 ${memories.size} 条"
        val summary = buildList {
            add("系统提示词 ${base.optString("systemKey").ifBlank { "默认" }}（${base.optInt("systemChars", 0)}字）")
            add("Skill ${skills.size} 个")
            add(memorySummary)
        }.joinToString(" · ")
        val detail = buildList {
            if (skills.isNotEmpty()) add("Skill：" + skills.joinToString("、"))
            memories.forEach { add("记忆 ${it.optString("id")}（${it.optDouble("score", 0.0)}）") }
            if (reading != null && reading.optBoolean("open", false)) {
                add("阅读：${reading.optString("bookName")} · ${reading.optString("chapterTitle")}")
            } else {
                add("阅读：未打开阅读页")
            }
        }.joinToString("\n")
        return "$header\n$summary\n$detail"
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

    private fun formatSeconds(ms: Long): String =
        if (ms < 0) "--" else "${ms / 1000}.${(ms % 1000) / 100}s"

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
