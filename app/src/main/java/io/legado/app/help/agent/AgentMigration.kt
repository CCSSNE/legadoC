package io.legado.app.help.agent

import androidx.preference.PreferenceManager
import android.content.SharedPreferences
import io.legado.app.data.agent.AgentMessage
import io.legado.app.help.agent.mcp.AgentCapabilities
import org.json.JSONArray
import org.json.JSONObject
import splitties.init.appCtx
import java.util.UUID

object AgentMigration {
    @Volatile private var initialized = false

    @Synchronized
    fun ensure() {
        if (initialized) return
        if (AgentStore.get("migration", "v1") == null) migrate()
        AgentStore.database.runInTransaction {
            AgentStore.dao.unfinished().forEach { run ->
                val reason = "应用进程在任务完成前终止；未完成写操作结果未知，未自动重放"
                AgentStore.dao.state(run.id, "interrupted", reason)
                AgentStore.event(run.id, "interrupted", JSONObject().put("reason", reason))
            }
            AgentStore.dao.documents("mcp.server.status").forEach { document ->
                if (JSONObject(document.json).optString("state") == "running") AgentStore.put("mcp.server.status", document.key,
                    JSONObject().put("state", "interrupted").put("reason", "上次进程已终止，等待监听服务重新启动"))
            }
        }
        initialized = true
    }

    fun resetAfterRestore() { initialized = false }

    @Synchronized
    fun restoreLegacy(preferences: SharedPreferences) {
        AgentRuntime.restoreData {
            io.legado.app.service.AgentMcpService.stopListeners()
            migrate(preferences, replace = true)
            resetAfterRestore()
            ensure()
        }
    }

    private fun migrate(preferences: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(appCtx), replace: Boolean = false) {
        val original = JSONObject()
        preferences.all.filterKeys { it.startsWith("ai", true) }.forEach { (key, value) ->
            original.put(key, if (value is Set<*>) JSONArray(value.toList()) else value ?: JSONObject.NULL)
        }
        val originalKey = if (replace) "restore.${UUID.randomUUID()}" else "original"
        if (AgentStore.get("migration", originalKey) == null) AgentStore.put("migration", originalKey, original)
        try {
            validateReferences(preferences)
            AgentPlugins.installBuiltin()
            AgentStore.database.runInTransaction {
                if (replace) {
                    listOf("mcp.clients", "tool.selection", "history.chat", "history.read", "history.origin").forEach { namespace ->
                        AgentStore.dao.documents(namespace).forEach { document ->
                            if (namespace == "history.origin") AgentStore.dao.deleteMessages(document.key)
                            AgentStore.dao.deleteDocument(namespace, document.key)
                        }
                    }
                    AgentStore.dao.allMessages().map { it.sessionId }.distinct().filter { it.startsWith("chat:") || it.startsWith("read:") }
                        .forEach { AgentStore.dao.deleteMessages(it) }
                }
                val defaults = AgentConfig.defaults()
                AgentStore.put("config", "agent", JSONObject().put("enabled",
                    if (preferences.contains("aiAssistantEnabled")) preferences.getBoolean("aiAssistantEnabled", false) else defaults.getBoolean("enabled"))
                    .put("mode", defaults.getString("mode")))
                val modules = defaults.getJSONObject("modules")
                if (preferences.contains("aiTavilyEnabled")) modules.put("web", preferences.getBoolean("aiTavilyEnabled", false))
                AgentStore.put("config", "modules", modules)
                if (!replace) AgentStore.put("module.settings", "memory", defaults.getJSONObject("memory"))
                AgentStore.put("module.settings", "web", JSONObject()
                    .put("apiKey", preferences.getString("aiTavilyApiKey", ""))
                    .put("baseUrl", preferences.getString("aiTavilyBaseUrl", "https://api.tavily.com/search"))
                    .put("searchDepth", preferences.getString("aiTavilySearchDepth", "basic"))
                    .put("topic", preferences.getString("aiTavilyTopic", "general"))
                    .put("maxResults", preferences.getInt("aiTavilyMaxResults", 5)))
                AgentStore.put("config", "ui", JSONObject().put("enterToSend", preferences.getBoolean("aiEnterToSend", true))
                    .put("showToolSummary", preferences.getBoolean("aiShowToolSummary", false)))
                fun array(key: String): JSONArray {
                    val raw = preferences.getString(key, null) ?: return JSONArray()
                    return try { JSONArray(raw) } catch (error: Exception) { throw IllegalStateException("迁移 $key 失败；原数据已保留", error) }
                }
                val clients = array("aiMcpServerList")
                val ids = mutableSetOf<String>()
                for (index in 0 until clients.length()) {
                    val client = clients.getJSONObject(index)
                    val id = client.getString("id")
                    require(id.isNotBlank() && ids.add(id)) { "aiMcpServerList[$index] ID 为空或重复" }
                    require(client.getString("endpoint").isNotBlank()) { "aiMcpServerList[$index] endpoint 为空" }
                    client.put("protocolVersion", "2025-06-18")
                    if (!client.has("enabled")) client.put("enabled", true)
                    AgentStore.put("mcp.clients", id, client)
                }
                val skills = array("aiSkillList")
                val skillIds = mutableSetOf<String>()
                for (index in 0 until skills.length()) {
                    val skill = skills.getJSONObject(index)
                    val id = skill.getString("id")
                    require(id.isNotBlank() && skillIds.add(id)) { "aiSkillList[$index] ID 为空或重复" }
                    skill.getString("content")
                    if (!skill.has("enabled")) skill.put("enabled", true)
                    AgentStore.put("skills", id, skill)
                }
                listOf("aiSystemPrompt" to "legacy.system", "aiSkillPrompt" to "legacy.skill-prompt").forEach { (old, key) ->
                    preferences.getString(old, null)?.let { content ->
                        AgentStore.put("prompts", key, JSONObject().put("name", "迁移：$old").put("content", content))
                        if (old == "aiSkillPrompt" && skills.length() == 0 && content.isNotBlank()) {
                            AgentStore.put("skills", key, JSONObject().put("name", "迁移 Skill").put("content", content).put("enabled", true))
                        }
                    }
                }
                if (preferences.contains("aiEnabledToolNames")) {
                    val selection = preferences.getStringSet("aiEnabledToolNames", emptySet()) ?: error("aiEnabledToolNames 类型损坏")
                    AgentCapabilities.names.keys.forEach { module ->
                        if (module != "memory") {
                            val selected = selection.filter { AgentCapabilities.moduleFor(it) == module && !it.startsWith("mcp_") }
                            AgentStore.put("tool.selection", module, JSONObject().put("enabled", JSONArray(selected)))
                            if (selected.isEmpty()) modules.put(module, false)
                        }
                    }
                    for (index in 0 until clients.length()) {
                        val client = clients.getJSONObject(index)
                        val id = client.getString("id")
                        AgentStore.put("tool.selection.legacy", "external.$id", JSONObject().put("enabled", JSONArray(selection.toList()))
                            .put("serverName", client.getString("name")).put("otherServerNames", JSONArray((0 until clients.length())
                                .filter { it != index }.map { clients.getJSONObject(it).getString("name") })))
                        if (selection.isEmpty()) AgentStore.put("mcp.clients", id, client.put("enabled", false))
                    }
                    AgentStore.put("config", "modules", modules)
                }
                val chat = array("aiChatSessionList")
                for (index in 0 until chat.length()) {
                    val session = chat.getJSONObject(index)
                    val id = session.getString("id")
                    require(id.isNotBlank() && AgentStore.get("history.chat", id) == null) { "aiChatSessionList[$index] ID 重复或为空" }
                    importMessages("chat:$id", session.getJSONArray("messages"), "aiChatSessionList[$index]")
                    AgentStore.put("history.chat", id, session)
                }
                preferences.getString("aiCurrentChatSessionId", null)?.let { AgentStore.put("config", "chat.current", JSONObject().put("id", it)) }
                val reading = array("aiReadHistoryList")
                for (index in 0 until reading.length()) {
                    val history = reading.getJSONObject(index)
                    val bookUrl = history.getString("bookUrl")
                    require(bookUrl.isNotBlank() && AgentStore.get("history.read", bookUrl) == null) { "aiReadHistoryList[$index] bookUrl 重复或为空" }
                    if (!history.has("sessions")) history.put("sessions", legacyReading(history.getJSONArray("records"), bookUrl))
                    val sessions = history.getJSONArray("sessions")
                    val sessionIds = mutableSetOf<String>()
                    for (sessionIndex in 0 until sessions.length()) {
                        val session = sessions.getJSONObject(sessionIndex)
                        val id = session.getString("id")
                        require(id.isNotBlank() && sessionIds.add(id)) { "aiReadHistoryList[$index] 会话 ID 重复或为空" }
                        importMessages("read:$bookUrl:$id", session.getJSONArray("messages"), "aiReadHistoryList[$index]/$sessionIndex")
                    }
                    if (!history.has("currentSessionId")) history.put("currentSessionId", if (sessions.length() > 0) sessions.getJSONObject(0).getString("id") else "")
                    AgentStore.put("history.read", bookUrl, history)
                }
                AgentStore.put("migration", "v1", JSONObject().put("complete", true).put("completedAt", System.currentTimeMillis()))
            }
        } catch (error: Exception) {
            AgentStore.put("migration", "error", JSONObject().put("error", error.stackTraceToString()).put("original", "migration/$originalKey"))
            throw IllegalStateException("Agent 迁移失败；没有清理或覆盖原始配置。${error.message}", error)
        }
    }

    private fun importMessages(sessionId: String, messages: JSONArray, origin: String) {
        val ids = mutableSetOf<String>()
        for (index in 0 until messages.length()) {
            val message = messages.getJSONObject(index)
            require(message.getString("id").isNotBlank() && ids.add(message.getString("id"))) { "$origin/messages[$index] 消息 ID 为空或重复" }
            if (message.optString("kind") == "STATUS") continue
            val role = message.getString("role").lowercase()
            require(role in setOf("user", "assistant")) { "$origin/messages[$index] 未知角色" }
            val content = message.getString("content")
            AgentStore.dao.append(AgentMessage(sessionId = sessionId, turnId = "legacy", runId = "legacy:${message.getString("id")}",
                json = JSONObject().put("role", role).put("content", content).toString()))
        }
        AgentStore.put("history.origin", sessionId, JSONObject().put("type", "legacy_text_only").put("source", origin)
            .put("notice", "旧记录仅含显示文本，无法还原工具往返；没有伪造工具调用"))
    }

    fun validateReferences(preferences: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(appCtx)) {
        fun records(key: String): List<JSONObject> {
            val raw = preferences.getString(key, null) ?: return emptyList()
            return try {
                val values = JSONArray(raw)
                val records = (0 until values.length()).map { values.getJSONObject(it) }
                val ids = mutableSetOf<String>()
                records.forEachIndexed { index, record ->
                    val id = record.getString("id")
                    require(id.isNotBlank() && id == id.trim() && ids.add(id)) { "$key[$index] ID 无效或重复" }
                }
                records
            } catch (error: Exception) { throw IllegalStateException("$key 损坏；原始数据保留，不转换为空配置", error) }
        }
        val providers = records("aiProviderList")
        val models = records("aiModelConfigList")
        val providerIds = providers.map { it.getString("id") }.toSet()
        providers.forEachIndexed { index, provider ->
            require(provider.getString("name").isNotBlank() && provider.has("baseUrl")) { "aiProviderList[$index] 名称或地址缺失" }
        }
        models.forEachIndexed { index, model ->
            require(model.getString("providerId") in providerIds && model.getString("modelId").isNotBlank()) { "aiModelConfigList[$index] 供应商引用或模型参数无效" }
        }
        preferences.getString("aiCurrentProviderId", null)?.let { require(it in providerIds) { "aiCurrentProviderId 引用不存在：$it" } }
        preferences.getString("aiCurrentModelId", null)?.let { id ->
            val model = models.singleOrNull { it.getString("id") == id } ?: error("aiCurrentModelId 引用不存在：$id")
            require(model.getString("providerId") == preferences.getString("aiCurrentProviderId", null)) { "当前模型与供应商引用不一致" }
        }
    }

    private fun legacyReading(records: JSONArray, bookUrl: String) = JSONArray().apply {
        for (index in 0 until records.length()) {
            val record = records.getJSONObject(index)
            val id = record.optString("id").ifBlank { UUID.nameUUIDFromBytes("$bookUrl:$index".toByteArray()).toString() }
            val createdAt = record.optLong("createdAt", 0)
            put(JSONObject().put("id", id).put("title", record.getString("question")).put("createdAt", createdAt).put("updatedAt", createdAt)
                .put("chapterTitle", record.optString("chapterTitle")).put("chapterIndex", record.optInt("chapterIndex", -1))
                .put("messages", JSONArray().put(JSONObject().put("id", "$id:user").put("role", "USER").put("content", record.getString("question")).put("createdAt", createdAt))
                    .put(JSONObject().put("id", "$id:assistant").put("role", "ASSISTANT").put("content", record.getString("answer")).put("createdAt", createdAt))))
        }
    }
}
