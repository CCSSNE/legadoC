package io.legado.app.help.agent.mcp

import io.legado.app.help.agent.AgentConfig
import io.legado.app.help.agent.AgentControl
import io.legado.app.help.agent.AgentStore
import io.legado.app.help.agent.memory.AgentMemory
import io.legado.app.help.ai.AiBookSourceTool
import io.legado.app.help.ai.AiBookshelfTool
import io.legado.app.help.ai.AiLibraryTool
import io.legado.app.help.ai.AiResolvedTool
import io.legado.app.help.ai.AiSettingsTool
import io.legado.app.help.ai.AiTavilyTool
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

data class AgentTool(
    val moduleId: String,
    val toolId: String,
    val description: String,
    val schema: JSONObject,
    val descriptor: JSONObject? = null,
    val execute: suspend (JSONObject, AgentControl) -> JSONObject
) {
    private val digest: String = MessageDigest.getInstance("SHA-256")
        .digest("$moduleId\u0000$toolId".toByteArray()).joinToString("") { "%02x".format(it) }

    // 历史会话里的 tool_calls 记录的是旧哈希名；主循环用 legacyModelName 把旧名改写成当前名。
    val legacyModelName: String get() = "t_" + digest.take(60)
    val modelName: String get() = if (AgentCapabilities.names.containsKey(moduleId)) toolId else "t_" + digest.take(16)
    fun mcpDefinition() = (descriptor?.let { JSONObject(it.toString()) } ?: JSONObject())
        .put("name", toolId).put("description", description).put("inputSchema", JSONObject(schema.toString()))
    fun definition() = JSONObject().put("type", "function").put("function", JSONObject()
        .put("name", modelName).put("description", description).put("parameters", schema))
}

object AgentCapabilities {
    val names = linkedMapOf("bookshelf" to "书架", "reading" to "阅读与陪读", "library" to "找书与统计",
        "sources" to "书源", "settings" to "APP 设置", "web" to "联网", "memory" to "记忆")
    fun moduleNames(): Map<String, String> = names + AgentPluginTools.names()

    fun moduleFor(tool: String): String? = when (tool) {
        "list_book_chapters", "read_book_chapter_content" -> "reading"
        "query_read_records", "list_book_sources", "search_book_source" -> "library"
        "create_book_source", "get_book_source", "update_book_source", "fetch_source_html", "debug_book_source" -> "sources"
        "get_app_settings", "set_app_setting", "set_app_settings_batch" -> "settings"
        "search_web_tavily" -> "web"
        "query_bookshelf", "get_bookshelf_book_info", "manage_bookshelf_group", "manage_bookshelf_tag", "set_bookshelf_book_group", "set_bookshelf_book_tags" -> "bookshelf"
        else -> null
    }

    fun normalize(value: Any): JSONObject {
        val result = when (value) {
            is JSONObject -> JSONObject(value.toString())
            is String -> JSONObject(value)
            else -> error("工具必须返回 JSON 对象")
        }
        if (result.has("content") && result.opt("content") is JSONArray) {
            if (!result.has("isError")) result.put("isError", result.has("ok") && !result.getBoolean("ok"))
            require(result.opt("isError") is Boolean) { "工具 isError 必须为布尔值" }
            return result
        }
        return JSONObject().put("isError", result.has("ok") && !result.getBoolean("ok"))
            .put("structuredContent", result)
            .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", result.toString())))
    }

    private fun native(tool: AiResolvedTool): AgentTool {
        val definition = tool.definition.getJSONObject("function")
        val schema = JSONObject(definition.getJSONObject("parameters").toString())
        val properties = schema.getJSONObject("properties")
        if (properties.has("limit")) properties.put("cursor", property("integer", "列表游标；返回 pages/nextCursor 可遍历全部结果"))
        if (properties.has("maxChars")) properties.put("offset", property("integer", "原始正文偏移，不折叠空白"))
        if (tool.name == "search_book_source") properties.put("page", property("integer", "明确请求书源搜索页；返回当前页与下一页引用"))
        return AgentTool(moduleFor(tool.name) ?: error("原生工具未声明能力模块：${tool.name}"), tool.name, definition.getString("description"), schema) { args, control ->
            control.check()
            val value = JSONObject(tool.execute(args))
            val fields = when (tool.name) {
                "query_bookshelf" -> listOf("matchedBooks", "recentReading", "recentUpdated", "unreadRanking", "groups")
                "query_read_records" -> listOf("bookRecords", "dailyRecords")
                "search_book_source" -> listOf("results")
                "list_book_sources", "get_book_source" -> listOf("sources")
                else -> emptyList()
            }
            normalize(if (fields.isNotEmpty()) AgentPages.apply(value, args, fields) else value)
        }
    }

    private fun domainTools(): List<AgentTool> = validate(
        (AiBookshelfTool.resolvedTools() + AiLibraryTool.resolvedTools() + AiBookSourceTool.resolvedTools() +
            AiSettingsTool.resolvedTools() + AiTavilyTool.resolvedTools()).map(::native) +
            AgentReading.tools() + AgentMemory.tools()
    )

    fun localTools(moduleId: String? = null): List<AgentTool> = validate(domainTools().filter { moduleId == null || it.moduleId == moduleId } +
        if (moduleId == null || moduleId.startsWith("plugin.")) AgentPluginTools.tools(moduleId = moduleId) else emptyList())

    fun validate(tools: List<AgentTool>): List<AgentTool> {
        val identifiers = mutableSetOf<Pair<String, String>>()
        val aliases = mutableSetOf<String>()
        tools.forEach {
            require(identifiers.add(it.moduleId to it.toolId)) { "工具 ID 冲突：${it.moduleId}/${it.toolId}" }
            require(aliases.add(it.modelName)) { "模型工具名冲突：${it.moduleId}/${it.toolId}" }
        }
        return tools
    }

    fun enabled(moduleId: String, external: Boolean): Boolean = if (external) {
        AgentStore.get("mcp.servers", moduleId)?.optBoolean("enabled", false) == true
    } else if (moduleId.startsWith("external.")) {
        AgentStore.get("mcp.clients", moduleId.removePrefix("external."))?.getBoolean("enabled") == true
    } else if (moduleId.startsWith("plugin.")) {
        AgentStore.get("plugins", moduleId.removePrefix("plugin."))?.getBoolean("enabled") == true
    } else AgentConfig.moduleEnabled(moduleId)

    fun checkPermission(tool: AgentTool, external: Boolean = false) {
        check(enabled(tool.moduleId, external)) { "能力模块已关闭：${tool.moduleId}" }
        if (!external) check(AgentConfig.toolEnabled(tool.moduleId, tool.toolId)) { "工具已关闭：${tool.moduleId}/${tool.toolId}" }
    }

    fun discover(control: AgentControl, scriptTools: List<AgentTool> = AgentPluginTools.tools(),
                 clients: Map<String, JSONObject> = AgentStore.dao.documents("mcp.clients").associate { it.key to JSONObject(it.json) }, external: Boolean = false): List<AgentTool> {
        AgentConfig.initialize()
        val tools = domainTools().filter { enabled(it.moduleId, external) && (external || AgentConfig.toolEnabled(it.moduleId, it.toolId)) }.toMutableList()
        if (!external) clients.forEach { (id, config) ->
            if (config.getBoolean("enabled")) tools += AgentMcpClient.discover(id, config, control).filter { AgentConfig.toolEnabled(it.moduleId, it.toolId) }
        }
        tools += scriptTools.filter { enabled(it.moduleId, external) && (external || AgentConfig.toolEnabled(it.moduleId, it.toolId)) }
        return validate(tools)
    }

    suspend fun call(tool: AgentTool, arguments: JSONObject, control: AgentControl, external: Boolean = false): JSONObject {
        val started = System.currentTimeMillis()
        return try {
            control.check()
            checkPermission(tool, external)
            AgentSchema.validate(arguments, tool.schema)
            val result = withContext(io.legado.app.help.agent.AgentIoContext(control)) { tool.execute(arguments, control) }
            normalize(result).apply {
                val metadata = optJSONObject("_meta") ?: JSONObject()
                put("_meta", metadata.put("legado", JSONObject().put("moduleId", tool.moduleId)
                    .put("toolId", tool.toolId).put("elapsedMs", System.currentTimeMillis() - started)))
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            normalize(JSONObject().put("ok", false).put("error", error.message).put("stack", error.stackTraceToString())
                .put("moduleId", tool.moduleId).put("toolId", tool.toolId).put("elapsedMs", System.currentTimeMillis() - started))
        }
    }

    fun tool(module: String, id: String, description: String, properties: JSONObject, required: List<String> = emptyList(),
             execute: suspend (JSONObject, AgentControl) -> JSONObject) = AgentTool(module, id, description,
        JSONObject().put("type", "object").put("properties", properties).put("required", JSONArray(required)).put("additionalProperties", false), execute = execute)

    fun property(type: String, description: String = "") = JSONObject().put("type", type).put("description", description)
}
