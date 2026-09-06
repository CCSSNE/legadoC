package io.legado.app.help.agent

import io.legado.app.data.agent.AgentMessage
import io.legado.app.data.agent.AgentRun
import io.legado.app.help.agent.mcp.AgentReading
import io.legado.app.ui.main.ai.AiChatException
import io.legado.app.ui.main.ai.AiChatMessage
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.json.JSONArray
import org.json.JSONObject
import org.mozilla.javascript.RhinoException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

object AgentRuntime {
    private val active = ConcurrentHashMap<String, AgentExecution>()
    private val externalControls = ConcurrentHashMap<String, AgentControl>()
    @Volatile private var restoring = false
    private val sessions = ConcurrentHashMap.newKeySet<String>()
    private val changes = MutableSharedFlow<io.legado.app.data.agent.AgentEvent>(extraBufferCapacity = 128)
    val events = changes.asSharedFlow()

    fun stopAll(reason: String = "用户停止") { active.values.forEach { it.control.cancel(reason) } }
    fun stop(sessionId: String) { active.values.filter { it.run.sessionId == sessionId }.forEach { it.control.cancel() } }
    fun usesPlugin(id: String): Boolean {
        fun contains(snapshot: AgentPluginSnapshot): Boolean = snapshot.id == id || snapshot.dependencies.values.any(::contains)
        return active.values.any { contains(it.plugin) } || externalControls.keys.any { runId ->
            val run = AgentStore.dao.run(runId)
            run?.pluginId == id || run?.pluginId == "mcp.plugin.$id" || run?.let {
                AgentStore.get("plugin.revisions", "${it.pluginId}@${it.revision}")?.optJSONObject("dependencies")?.optJSONObject("plugins")?.has(id)
            } == true
        }
    }
    fun controls(): Map<String, AgentControl> = active.mapValues { it.value.control } + externalControls
    @Synchronized
    fun registerExternal(runId: String, control: AgentControl) {
        check(!restoring) { "Agent 数据正在恢复，当前请求未执行" }
        externalControls[runId] = control
    }
    fun unregisterExternal(runId: String) { externalControls.remove(runId) }
    fun hasInternalRuns() = active.isNotEmpty()
    @Synchronized
    private fun enterSession(sessionId: String) {
        check(!restoring) { "Agent 数据正在恢复，当前请求未执行" }
        check(sessions.add(sessionId)) { "同一会话已有运行任务" }
    }

    fun restoreData(action: () -> Unit) {
        synchronized(this) {
            check(!restoring && sessions.isEmpty() && externalControls.isEmpty()) { "恢复 Agent 数据前请停止全部任务" }
            restoring = true
        }
        try { action() } finally { synchronized(this) { restoring = false } }
    }
    fun isRunning(sessionId: String) = sessionId in sessions

    private fun event(run: AgentRun, type: String, value: JSONObject, observer: (String, JSONObject) -> Unit) {
        val event = AgentStore.event(run.id, type, value)
        if (type in setOf("running", "paused", "waiting_input", "completed", "failed", "cancelled", "interrupted")) {
            AgentStore.dao.state(run.id, type, if (type == "failed") event.json else null)
        }
        changes.tryEmit(event)
        observer(type, JSONObject(event.json).put("runId", run.id).put("sequence", event.sequence).put("type", type))
    }

    suspend fun chat(
        sessionId: String,
        messages: List<AiChatMessage>,
        readingContext: JSONObject? = null,
        assistantMessageId: String = UUID.randomUUID().toString(),
        onPartial: (String) -> Unit = {},
        onThinking: (String) -> Unit = {},
        onStatus: (JSONObject) -> Unit = {},
        includeStructuredBlocks: Boolean = true
    ): String {
        AgentConfig.initialize()
        check(AgentConfig.enabled) { "Agent 已关闭" }
        val user = messages.lastOrNull { it.role == AiChatMessage.Role.USER } ?: error("缺少用户消息")
        val input = JSONObject().put("user", user.content).put("messageId", user.id).put("assistantMessageId", assistantMessageId)
            .put("reading", readingContext ?: AgentReading.current())
        val cards = JSONArray()
        val output = execute(sessionId, input) { type, value ->
            when (type) {
                "output" -> onPartial(value.getString("text"))
                "thinking" -> onThinking(value.getString("text"))
                "tool.result" -> {
                    if (value.getString("toolId") == "search_book_source") {
                        val results = value.getJSONObject("result").optJSONObject("structuredContent")?.optJSONArray("results")
                        if (results != null) for (index in 0 until results.length()) cards.put(results.getJSONObject(index))
                    }
                    onStatus(value)
                }
                else -> onStatus(value)
            }
        }
        return if (includeStructuredBlocks && cards.length() > 0) {
            output + "\n\n```legado-search-results\n" + JSONObject().put("type", "search_book_results").put("results", cards) + "\n```"
        } else output
    }

    suspend fun execute(sessionId: String, input: JSONObject, observer: (String, JSONObject) -> Unit = { _, _ -> }): String = coroutineScope {
        check(AgentConfig.enabled) { "Agent 已关闭" }
        val mode = AgentConfig.mode
        val revision = AgentStore.get("plugins", mode)?.getString("revision") ?: "missing"
        val run = AgentRun(UUID.randomUUID().toString(), sessionId, UUID.randomUUID().toString(), mode, revision, input.toString())
        val control = AgentControl(coroutineContext.job) { type, value -> event(run, type, value, observer) }
        enterSession(sessionId)
        try { AgentStore.dao.put(run) } catch (error: Throwable) { sessions.remove(sessionId); throw error }
        var execution: AgentExecution? = null
        var guard: Job? = null
        try {
            val plugin = AgentPlugins.snapshot(mode, revision)
            val current = AgentExecution(run, plugin, control) { type, value -> event(run, type, value, observer) }
            execution = current
            active[run.id] = current
            guard = launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                try { awaitCancellation() } finally { if (!current.finished) control.cancel("运行上下文已取消") }
            }
            io.legado.app.service.AgentRunService.start()
            event(run, "running", JSONObject().put("plugin", plugin.id).put("revision", plugin.revision), observer)
            val result = withContext(Dispatchers.IO) {
                AgentScript(current).use { script ->
                    current.script = script
                    script.execute(plugin, plugin.manifest.getString("entry"), "run", JSONObject(input.toString()))
                }
            }
            control.check()
            val output = when (result) {
                is String -> result
                is JSONObject -> result.getString("output")
                else -> error("Agent 插件必须返回输出字符串或 {output}，实际为 $result")
            }
            event(run, "completed", JSONObject().put("output", output), observer)
            output
        } catch (error: Throwable) {
            val cancelled = error is CancellationException || !coroutineContext.isActive || control.cancelled
            val details = JSONObject().put("plugin", mode).put("revision", revision)
                .put("error", error.toString()).put("stack", error.stackTraceToString()).put("javascript", execution?.lastStack)
                .put("cancelReason", control.cancelReason ?: JSONObject.NULL)
            if (error is RhinoException) details.put("file", error.sourceName()).put("line", error.lineNumber()).put("scriptStack", error.scriptStackTrace)
            event(run, if (cancelled) "cancelled" else "failed", details, observer)
            if (cancelled) throw CancellationException("Agent 已停止；已发生的写入不会撤销，未返回的调用结果未知").apply { initCause(error) }
            val protected = AgentDiagnostics.protect(details)
            throw AiChatException("$mode@$revision\n${protected.getString("error")}", protected.toString(2), error)
        } finally {
            execution?.finished = true
            guard?.cancel()
            active.remove(run.id)
            sessions.remove(sessionId)
            if (active.isEmpty()) io.legado.app.service.AgentRunService.finish()
        }
    }

    fun appendMessage(run: AgentRun, message: JSONObject) {
        require(message.getString("role") in setOf("system", "user", "assistant", "tool")) { "未知消息角色" }
        if (message.getString("role") == "tool") require(message.getString("tool_call_id").isNotBlank())
        AgentStore.dao.append(AgentMessage(sessionId = run.sessionId, turnId = run.turnId, runId = run.id, json = message.toString()))
    }
}
