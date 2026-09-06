package io.legado.app.help.agent

import io.legado.app.data.agent.AgentPayloadStore
import org.json.JSONObject

/**
 * 破坏性升级：AI 配置只看 AgentConfig.SCHEMA_VERSION，对不上就删了重建。
 * 不校验旧数据、不迁移旧配置，自用应用不保留历史数据。
 */
object AgentMigration {
    @Volatile private var initialized = false

    @Synchronized
    fun ensure() {
        if (initialized) return
        val marker = AgentStore.get("migration", "v1")
        if (marker == null || marker.optInt("schemaVersion", -1) != AgentConfig.SCHEMA_VERSION || !marker.optBoolean("complete", false)) {
            resetAll()
        } else {
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
        }
        initialized = true
    }

    fun resetAfterRestore() { initialized = false }

    private fun resetAll() {
        io.legado.app.service.AgentMcpService.stopListeners()
        AgentRuntime.stopAll("Agent 配置版本升级，已重置重建")
        AgentStore.database.runInTransaction {
            val dao = AgentStore.dao
            dao.clearDocuments()
            dao.clearRuns()
            dao.clearEvents()
            dao.clearMessages()
            dao.clearAllVectors()
            AgentPayloadStore.clear()
            val defaults = AgentConfig.defaults()
            AgentStore.put("config", "agent", JSONObject().put("enabled", defaults.getBoolean("enabled"))
                .put("mode", defaults.getString("mode")).put("schemaVersion", AgentConfig.SCHEMA_VERSION))
            AgentStore.put("config", "modules", defaults.getJSONObject("modules"))
            AgentStore.put("module.settings", "memory", defaults.getJSONObject("memory"))
            AgentStore.put("module.settings", "web", JSONObject()
                .put("apiKey", "")
                .put("baseUrl", "https://api.tavily.com/search")
                .put("searchDepth", "basic")
                .put("topic", "general")
                .put("maxResults", 5))
            AgentStore.put("config", "ui", JSONObject().put("enterToSend", true).put("showToolSummary", false))
            AgentStore.put("migration", "v1", JSONObject().put("schemaVersion", AgentConfig.SCHEMA_VERSION)
                .put("complete", true).put("completedAt", System.currentTimeMillis()))
        }
        AgentPlugins.root.deleteRecursively()
        AgentPlugins.installBuiltin()
    }
}
