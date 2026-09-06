package io.legado.app.help.agent

import org.json.JSONObject
import splitties.init.appCtx

object AgentConfig {
    private val bundledDefaults: JSONObject by lazy {
        JSONObject(appCtx.assets.open("agent/defaults.json").bufferedReader().use { it.readText() })
    }
    fun initialize() = AgentMigration.ensure()
    fun value(key: String): JSONObject {
        initialize()
        return AgentStore.get("config", key) ?: error("Agent 配置缺失：$key")
    }
    fun defaults(): JSONObject = JSONObject(bundledDefaults.toString())
    var enabled: Boolean
        get() = value("agent").getBoolean("enabled")
        set(enabled) {
            AgentStore.put("config", "agent", value("agent").put("enabled", enabled))
            if (!enabled) AgentRuntime.stopAll("Agent 已关闭")
        }
    var mode: String
        get() = value("agent").getString("mode")
        set(id) {
            AgentPlugins.snapshot(id)
            AgentStore.put("config", "agent", value("agent").put("mode", id))
        }
    fun moduleEnabled(id: String): Boolean {
        initialize()
        return if (id.startsWith("plugin.")) AgentStore.get("plugins", id.removePrefix("plugin."))?.getBoolean("enabled")
            ?: error("插件模块不存在：$id") else value("modules").getBoolean(id)
    }
    fun setModuleEnabled(id: String, enabled: Boolean) {
        if (id.startsWith("plugin.")) {
            val pluginId = id.removePrefix("plugin.")
            AgentStore.put("plugins", pluginId, (AgentStore.get("plugins", pluginId) ?: error("插件模块不存在：$id")).put("enabled", enabled))
            return
        }
        AgentStore.put("config", "modules", value("modules").put(id, enabled))
    }
    fun toolEnabled(moduleId: String, toolId: String): Boolean {
        val selection = AgentStore.get("tool.selection", moduleId) ?: return true
        val tools = selection.getJSONArray("enabled")
        return (0 until tools.length()).any { tools.getString(it) == toolId }
    }
    fun moduleSettings(id: String): JSONObject {
        initialize()
        return AgentStore.get("module.settings", id) ?: JSONObject()
    }
}
