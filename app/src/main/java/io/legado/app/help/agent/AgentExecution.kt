package io.legado.app.help.agent

import android.util.Base64
import io.legado.app.data.agent.AgentRun
import io.legado.app.help.agent.mcp.AgentCapabilities
import io.legado.app.help.agent.mcp.AgentReading
import io.legado.app.help.agent.mcp.AgentTool
import io.legado.app.help.agent.memory.AgentMemory
import io.legado.app.help.config.AppConfig
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class AgentExecution(
    val run: AgentRun, val plugin: AgentPluginSnapshot, val control: AgentControl,
    private val external: Boolean = false,
    private val emit: (String, JSONObject) -> Unit
) {
    lateinit var script: AgentScript
    @Volatile var finished = false
    var lastStack = JSONObject()
    private var tools: List<AgentTool>? = null
    private val providers = AppConfig.aiProviderList.associateBy { it.id }
    private val model = AppConfig.aiCurrentModelConfig
    private val clients = AgentStore.dao.documents("mcp.clients").associate { it.key to JSONObject(it.json) }
    private val pluginTools = io.legado.app.help.agent.mcp.AgentPluginTools.snapshots(plugin,
        io.legado.app.help.agent.mcp.AgentPluginTools.names().keys.filter { AgentCapabilities.enabled(it, external) }.toSet())
    private val moduleSnapshots = AgentStore.dao.documents("module.settings").associate { it.key to JSONObject(it.json) }
    // 任务启动时的模块开关快照。权限检查仍走最新开关（AgentCapabilities.checkPermission），
    // 返回给插件的配置视图冻结，避免一次任务前半段旧配置、后半段新配置。
    private val modulesSnapshot = AgentConfig.value("modules")

    fun invoke(operation: String, arguments: JSONObject, owner: AgentPluginSnapshot = plugin, callback: (String, String) -> Boolean): Any? {
        control.check()
        return when (operation) {
            "config" -> JSONObject().put("plugin", owner.settings).put("revision", owner.revision)
                .put("modules", JSONObject(modulesSnapshot.toString())).put("memory", moduleSnapshots["memory"] ?: JSONObject())
            "context.snapshot" -> JSONObject(run.input).getJSONObject("reading")
            "context.refresh" -> AgentReading.current()
            "model.reference" -> {
                val selected = model ?: error("没有选择模型")
                val provider = providers[selected.providerId] ?: error("模型供应商不存在：${selected.providerId}")
                JSONObject().put("providerId", provider.id).put("model", selected.modelId).put("supportsVision", provider.supportsVision)
            }
            "model.request" -> {
                val providerId = arguments.optString("providerId", model?.providerId.orEmpty())
                val provider = providers[providerId] ?: error("供应商引用不存在：$providerId")
                val body = arguments.getJSONObject("body")
                require(body.has("messages") && body.has("model")) { "模型请求必须提供完整 messages 和 model" }
                val requestId = UUID.randomUUID().toString()
                val startedAt = System.currentTimeMillis()
                // display 标记区分主循环与附带调用（记忆提取/压缩）：展示层只认主循环。
                emit("model.request", JSONObject().put("requestId", requestId).put("providerId", providerId)
                    .put("display", arguments.optBoolean("display", false)).put("body", body))
                val response = try {
                    AgentHttp.exchange(AgentHttp.providerRequest(provider, "chat/completions", body), control) { event, data ->
                        emit("model.chunk", JSONObject().put("requestId", requestId).put("event", event).put("data", data))
                        callback(event, data)
                    }
                } catch (error: Throwable) {
                    emit("model.error", JSONObject().put("requestId", requestId).put("providerId", providerId)
                        .put("elapsedMs", System.currentTimeMillis() - startedAt).put("error", error.stackTraceToString()).put("replayed", false))
                    throw error
                }
                emit("model.response", JSONObject().put("requestId", requestId).put("response", response).put("elapsedMs", System.currentTimeMillis() - startedAt))
                response.put("requestId", requestId)
            }
            "http" -> {
                val requestId = UUID.randomUUID().toString()
                emit("http.request", JSONObject().put("requestId", requestId).put("request", arguments))
                val response = AgentHttp.exchange(arguments, control) { event, data ->
                    emit("http.chunk", JSONObject().put("requestId", requestId).put("event", event).put("data", data))
                    callback(event, data)
                }
                emit("http.response", JSONObject().put("requestId", requestId).put("response", response))
                response
            }
            "modules.list" -> JSONArray().apply {
                AgentCapabilities.moduleNames().forEach { (id, name) -> put(JSONObject().put("id", id).put("name", name).put("enabled", AgentConfig.moduleEnabled(id))) }
                AgentStore.dao.documents("mcp.clients").forEach { put(JSONObject(it.json).apply { remove("apiKey"); put("id", "external.${it.key}") }) }
            }
            "tools.discover" -> {
                val discovered = tools ?: AgentCapabilities.discover(control,
                    io.legado.app.help.agent.mcp.AgentPluginTools.tools(script = script, captured = pluginTools), clients, external)
                tools = discovered
                JSONArray().apply { discovered.filter { !arguments.has("moduleId") || it.moduleId == arguments.getString("moduleId") }.forEach {
                    put(JSONObject().put("moduleId", it.moduleId).put("toolId", it.toolId).put("name", it.modelName).put("definition", it.definition()))
                } }
            }
            "tools.call" -> {
                val moduleId = arguments.getString("moduleId")
                val toolId = arguments.getString("toolId")
                val selected = (tools ?: error("调用工具前必须 tools.discover；不会自动加载未声明目录")).singleOrNull { it.moduleId == moduleId && it.toolId == toolId }
                    ?: error("工具未加载或已关闭：$moduleId/$toolId")
                val args = arguments.getJSONObject("arguments")
                val invocationId = UUID.randomUUID().toString()
                emit("tool.start", JSONObject().put("invocationId", invocationId).put("moduleId", moduleId).put("toolId", toolId).put("arguments", args))
                try {
                    val result = runBlocking(control.job) { AgentCapabilities.call(selected, args, control, external) }
                    emit("tool.result", JSONObject().put("invocationId", invocationId).put("moduleId", moduleId).put("toolId", toolId).put("result", result))
                    result
                } catch (error: Throwable) {
                    emit("tool.unknown", JSONObject().put("invocationId", invocationId).put("moduleId", moduleId).put("toolId", toolId)
                        .put("error", error.toString()).put("outcome", "unknown").put("replayed", false))
                    throw error
                }
            }
            "messages.list" -> JSONArray().apply { AgentStore.dao.messages(run.sessionId).forEach { put(JSONObject(it.json)) } }
            "messages.append" -> { AgentRuntime.appendMessage(run, arguments); true }
            "events.list" -> JSONArray().apply { AgentStore.dao.events(run.id, arguments.optLong("after", 0)).forEach {
                put(JSONObject().put("sequence", it.sequence).put("type", it.type).put("value", JSONObject(it.json)))
            } }
            "emit" -> { emit(arguments.getString("type"), arguments.getJSONObject("value")); true }
            "log" -> { emit("log", arguments); true }
            "pause" -> { control.checkpoint(arguments, force = true); true }
            "checkpoint" -> { control.checkpoint(arguments); true }
            "input" -> control.input(arguments)
            "prompts.get" -> owner.prompts[arguments.getString("key")]?.getString("content") ?: error("提示词 key 不存在：${arguments.getString("key")}")
            "prompts.list" -> JSONArray(owner.prompts.keys.toList())
            "skills.list" -> JSONArray().apply { owner.skills.forEach { (key, value) -> put(JSONObject(value.toString()).put("key", key)) } }
            "skills.get" -> owner.skills[arguments.getString("key")] ?: error("Skill 不存在：${arguments.getString("key")}")
            "skills.resources" -> {
                val key = arguments.getString("key")
                owner.skills[key] ?: error("Skill 不存在：$key")
                if (arguments.has("path")) {
                    val resource = owner.skillResources[key]?.get(AgentPlugins.path(arguments.getString("path"))) ?: error("Skill 资源不存在")
                    if (arguments.optString("encoding", "utf8") == "base64") resource.getString("base64")
                    else AgentPlugins.text(Base64.decode(resource.getString("base64"), Base64.DEFAULT))
                } else JSONArray(owner.skillResources[key]?.keys?.toList() ?: emptyList<String>())
            }
            "resources.read" -> {
                val path = AgentPlugins.path(arguments.getString("path"))
                val bytes = owner.files[path] ?: error("资源不存在：$path")
                if (arguments.optString("encoding", "utf8") == "base64") Base64.encodeToString(bytes, Base64.NO_WRAP) else AgentPlugins.text(bytes)
            }
            "storage.get" -> AgentStore.get(namespace(owner, arguments), arguments.getString("key"))
            "storage.list" -> AgentStore.list(namespace(owner, arguments))
            "storage.put" -> AgentStore.put(namespace(owner, arguments), arguments.getString("key"), arguments.getJSONObject("value"),
                if (arguments.has("revision")) arguments.getLong("revision") else null)
            "storage.delete" -> {
                AgentStore.transaction(JSONArray().put(JSONObject(arguments.toString()).put("namespace", namespace(owner, arguments)).put("action", "delete"))); true
            }
            "storage.transaction" -> {
                val operations = arguments.getJSONArray("operations")
                for (index in 0 until operations.length()) operations.getJSONObject(index).let { it.put("namespace", namespace(owner, it)) }
                AgentStore.transaction(operations); true
            }
            "vectors.put", "vectors.search" -> AgentMemory.vectorOperation(operation, namespace(owner, arguments), arguments)
            else -> error("未知宿主 API：$operation")
        }
    }

    private fun namespace(owner: AgentPluginSnapshot, arguments: JSONObject) = "plugin.${owner.id}.${arguments.getString("namespace")}"
}
