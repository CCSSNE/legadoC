package io.legado.app.help.agent

import org.json.JSONArray
import org.json.JSONObject
import splitties.init.appCtx
import java.net.URI

/** Configuration has one schema regardless of UI, script, migration or backup writer. */
object AgentConfig {
    // 破坏性升级版本号：只看它，对不上就删了重建，不迁移旧数据。
    // 内置插件、默认配置、记忆策略改了就升它（v4->v5：上下文卡全量呈现+Skill/系统全文事件+主循环标记）。
    const val SCHEMA_VERSION = 5
    private val bundledDefaults: JSONObject by lazy {
        JSONObject(appCtx.assets.open("agent/defaults.json").bufferedReader().use { it.readText() })
    }
    fun initialize() = AgentMigration.ensure()
    fun defaults(): JSONObject = JSONObject(bundledDefaults.toString())
    fun value(key: String): JSONObject {
        initialize()
        return AgentStore.get("config", key)?.also { validateDocument("config", key, it) }
            ?: error("Agent 配置缺失：$key")
    }
    fun saveValue(key: String, value: JSONObject, expectedRevision: Long? = null) {
        initialize()
        require(key in setOf("agent", "ui")) { "该配置须使用所属模块入口：$key" }
        AgentStore.put("config", key, value, expectedRevision)
        if (key == "agent" && !value.getBoolean("enabled")) AgentRuntime.stopAll("Agent 已关闭")
    }
    var enabled: Boolean
        get() = value("agent").getBoolean("enabled")
        set(enabled) {
            initialize()
            AgentStore.update("config", "agent") { it.put("enabled", enabled) }
            if (!enabled) AgentRuntime.stopAll("Agent 已关闭")
        }
    var mode: String
        get() = value("agent").getString("mode")
        set(id) {
            initialize()
            AgentPlugins.snapshot(id)
            AgentStore.update("config", "agent") { it.put("mode", id) }
        }
    fun moduleEnabled(id: String): Boolean {
        initialize()
        return if (id.startsWith("plugin.")) AgentStore.get("plugins", id.removePrefix("plugin."))?.getBoolean("enabled")
            ?: error("插件模块不存在：$id") else value("modules").getBoolean(id)
    }
    fun setModuleEnabled(id: String, enabled: Boolean) {
        initialize()
        if (id.startsWith("plugin.")) {
            AgentStore.update("plugins", id.removePrefix("plugin.")) { it.put("enabled", enabled) }
        } else {
            require(value("modules").has(id)) { "未知能力模块：$id" }
            AgentStore.update("config", "modules") { it.put(id, enabled) }
        }
    }
    fun toolEnabled(moduleId: String, toolId: String): Boolean {
        initialize()
        val selection = AgentStore.get("tool.selection", moduleId) ?: return true
        val tools = selection.getJSONArray("enabled")
        return (0 until tools.length()).any { tools.getString(it) == toolId }
    }
    fun moduleSettings(id: String): JSONObject {
        initialize()
        val settings = AgentStore.get("module.settings", id)
        if (settings == null) {
            require(id !in setOf("memory", "web")) { "模块配置缺失：$id" }
            return JSONObject()
        }
        validateDocument("module.settings", id, settings)
        return settings
    }
    fun saveModuleSettings(id: String, value: JSONObject, expectedRevision: Long? = null) {
        initialize()
        AgentStore.put("module.settings", id, value, expectedRevision)
    }

    fun validateDocument(namespace: String, key: String, value: JSONObject) {
        fun string(name: String, nonBlank: Boolean = false): String {
            require(value.opt(name) is String) { "$namespace/$key.$name 必须是字符串" }
            return value.getString(name).also { require(!nonBlank || it.isNotBlank()) { "$namespace/$key.$name 不能为空" } }
        }
        fun boolean(name: String) { require(value.opt(name) is Boolean) { "$namespace/$key.$name 必须是布尔值" } }
        fun integer(name: String, minimum: Int, maximum: Int = Int.MAX_VALUE): Int {
            val number = value.opt(name) as? Number ?: error("$namespace/$key.$name 必须是整数")
            require(number.toDouble().isFinite() && number.toDouble() == number.toLong().toDouble() && number.toLong() in minimum.toLong()..maximum.toLong()) { "$namespace/$key.$name 超出整数范围 $minimum..$maximum" }
            return number.toInt()
        }
        fun strings(name: String, nonEmpty: Boolean = false): List<String> {
            val array = value.getJSONArray(name)
            val entries = (0 until array.length()).map { index ->
                require(array.opt(index) is String) { "$namespace/$key.$name[$index] 必须是字符串" }
                array.getString(index).also { require(it.isNotBlank()) { "$namespace/$key.$name[$index] 不能为空" } }
            }
            require(entries.distinct().size == entries.size && (!nonEmpty || entries.isNotEmpty())) { "$namespace/$key.$name 为空或重复" }
            return entries
        }
        fun url(name: String) {
            val uri = URI(string(name, true))
            require(uri.scheme in setOf("http", "https") && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.fragment == null) { "$namespace/$key.$name 不是有效 HTTP 地址" }
        }
        when (namespace) {
            "config" -> when (key) {
                "agent" -> {
                    require(integer("schemaVersion", 1) == SCHEMA_VERSION) { "Agent 配置版本不受支持，不能以旧版本覆盖：${value.opt("schemaVersion")}" }
                    boolean("enabled"); string("mode", true)
                }
                "modules" -> {
                    val required = defaults().getJSONObject("modules").keys().asSequence().toSet()
                    require(value.keys().asSequence().toSet() == required) { "内置模块配置必须完整匹配：$required" }
                    required.forEach(::boolean)
                }
                "ui" -> { boolean("enterToSend"); boolean("showToolSummary") }
                "chat.current" -> string("id", true)
            }
            "module.settings" -> when (key) {
                "memory" -> {
                    string("providerId"); string("model"); boolean("autoRecall"); boolean("autoSave")
                    require(string("scope") in setOf("book", "global")) { "记忆作用域必须为 book/global" }
                    integer("recallCount", 0)
                    val minimumScore = value.getDouble("minimumScore")
                    val fraction = value.getDouble("contextFraction")
                    require(minimumScore.isFinite() && minimumScore in -1.0..1.0) { "minimumScore 必须在 [-1,1]" }
                    require(fraction.isFinite() && fraction > 0 && fraction <= 1) { "contextFraction 必须在 (0,1]" }
                }
                "web" -> {
                    string("apiKey"); url("baseUrl")
                    require(string("topic") in setOf("general", "news", "finance")) { "未知 Tavily topic" }
                    require(string("searchDepth") in setOf("basic", "advanced", "fast", "ultra-fast")) { "未知 Tavily searchDepth" }
                    integer("maxResults", 1, 20)
                }
            }
            "mcp.clients" -> {
                string("name", true); string("apiKey"); url("endpoint"); boolean("enabled")
                require(string("protocolVersion") in setOf("auto", "2024-11-05", "2025-03-26", "2025-06-18", "2025-11-25", "2026-07-28")) { "不支持的 MCP 协议版本" }
            }
            "mcp.servers" -> {
                boolean("enabled"); string("address", true); string("apiKey", true)
                integer("port", 1, 65535); integer("pageSize", 1)
                strings("allowedHosts", true); strings("allowedOrigins")
                require(!key.startsWith("external.")) { "第三方 MCP 不能登记为本地对外服务" }
            }
            "tool.selection" -> strings("enabled")
            "prompts" -> { string("name", true); string("content") }
            "skills" -> { string("name", true); string("content", true); boolean("enabled") }
            "migration" -> if (key == "v1") {
                boolean("complete")
                require(value.getBoolean("complete") && integer("schemaVersion", 1) == SCHEMA_VERSION) { "迁移版本损坏或不受支持" }
            }
        }
    }
}
