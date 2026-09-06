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
        private val activeToolMessageIds = linkedMapOf<String, String>()
    }

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
        activeToolMessageIds.clear()
        append(AiChatMessage(role = AiChatMessage.Role.USER, content = userContent))
        val pendingMessage = AiChatMessage(
            role = AiChatMessage.Role.ASSISTANT,
            content = pendingThinkingLabel,
            pending = true
        )
        activePendingAssistantMessageId = pendingMessage.id
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
                activeToolMessageIds.clear()
                targetFor(requestSessionId).replacePendingAssistant(content.ifBlank { pendingThinkingLabel })
            }.onFailure { throwable ->
                activePendingContent = ""
                activeToolMessageIds.clear()
                if (throwable is CancellationException) {
                    targetFor(requestSessionId).replacePendingAssistant(cancelledText)
                    return@onFailure
                }
                val chatError = throwable as? AiChatException ?: AiChatException(
                    message = throwable.localizedMessage ?: throwable.javaClass.simpleName,
                    debugLog = throwable.stackTraceToString(),
                    cause = throwable
                )
                AppLog.put("AI 请求失败\n${chatError.debugLog}", chatError)
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
        activeToolMessageIds.clear()
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
        val type = status.optString("type")
        if (type !in setOf("tool.start", "tool.result", "tool.unknown", "paused", "waiting_input")) return
        val key = status.optString("invocationId", status.optString("runId"))
        val title = if (status.has("toolId")) "${status.optString("moduleId")}/${status.getString("toolId")}" else type
        val content = when (type) {
            "paused" -> "运行已暂停；请在 Agent 模式的运行诊断中继续或停止"
            "waiting_input" -> "等待输入：${status.optString("prompt", status.toString())}"
            "tool.start" -> "$title · 执行中"
            "tool.unknown" -> "$title · 结果未知，未自动重放"
            else -> "$title · ${status.getJSONObject("result")}"
        }
        val existing = activeToolMessageIds[key]
        val index = messages.indexOfFirst { it.id == existing }
        val message = AiChatMessage(id = existing ?: UUID.randomUUID().toString(),
            role = AiChatMessage.Role.ASSISTANT, content = content, kind = AiChatMessage.Kind.STATUS,
            statusName = title, statusStage = type, statusSuccess = type != "tool.unknown" &&
                status.optJSONObject("result")?.optBoolean("isError", false) != true)
        if (index >= 0) messages[index] = message else messages.add(message)
        activeToolMessageIds[key] = message.id
        publish()
    }

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
            // 无历史会话时不写 current 指针：history.chat 为空，任何 id 都是悬空，
            // 写进去会被 currentChat 的存在性校验拒绝导致开局崩溃。
            // 新会话 id 只留内存，首条消息落盘时 saveCurrentSession 再建指针。
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
