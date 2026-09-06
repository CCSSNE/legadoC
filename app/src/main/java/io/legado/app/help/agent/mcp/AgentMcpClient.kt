package io.legado.app.help.agent.mcp

import android.util.Base64
import io.legado.app.BuildConfig
import io.legado.app.help.agent.AgentControl
import io.legado.app.help.agent.AgentHttp
import io.legado.app.help.agent.AgentStore
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.Locale
import kotlinx.coroutines.CancellationException

class AgentRpcException(val code: Int, message: String, val data: JSONObject? = null, val httpStatus: Int = 400) :
    IllegalStateException("MCP $code / HTTP $httpStatus：$message")

object AgentMcpProtocol {
    const val MODERN = "2026-07-28"
    val versions = listOf(MODERN, "2025-11-25", "2025-06-18", "2025-03-26")
    fun metadata() = JSONObject().put("io.modelcontextprotocol/protocolVersion", MODERN)
        .put("io.modelcontextprotocol/clientInfo", JSONObject().put("name", "legado-agent").put("version", BuildConfig.VERSION_NAME))
        .put("io.modelcontextprotocol/clientCapabilities", JSONObject())
    fun encode(value: String): String = if (value.any { it.code !in 33..126 } || value.startsWith("=?base64?")) {
        "=?base64?" + Base64.encodeToString(value.toByteArray(Charsets.UTF_8), Base64.NO_WRAP) + "?="
    } else value
    fun decode(value: String): String = if (value.startsWith("=?base64?") && value.endsWith("?=")) {
        String(Base64.decode(value.removePrefix("=?base64?").removeSuffix("?="), Base64.DEFAULT), Charsets.UTF_8)
    } else value
}

object AgentMcpClient {
    private class Connection(val id: String, val config: JSONObject, val control: AgentControl) {
        var version = config.optString("protocolVersion", "auto")
        var sessionId: String? = null
        var lastHttpStatus = 0

        fun rpc(method: String, parameters: JSONObject = JSONObject(), notification: Boolean = false): JSONObject {
            control.check()
            val requestId = UUID.randomUUID().toString()
            val params = JSONObject(parameters.toString())
            if (version == AgentMcpProtocol.MODERN) params.put("_meta", AgentMcpProtocol.metadata())
            val rpc = JSONObject().put("jsonrpc", "2.0").put("method", method).put("params", params)
            if (!notification) rpc.put("id", requestId)
            val headers = JSONObject().put("MCP-Protocol-Version", version)
            if (version == AgentMcpProtocol.MODERN) {
                headers.put("Mcp-Method", method)
                if (params.has("name")) headers.put("Mcp-Name", AgentMcpProtocol.encode(params.getString("name")))
            }
            sessionId?.let { headers.put("Mcp-Session-Id", it) }
            config.optString("apiKey").takeIf { it.isNotBlank() }?.let { headers.put("Authorization", "Bearer $it") }
            val request = JSONObject().put("url", config.getString("endpoint")).put("method", "POST")
                .put("headers", headers).put("body", rpc.toString())
            var matched: JSONObject? = null
            val response = AgentHttp.exchange(request, control) { _, data ->
                val message = JSONObject(data)
                if (message.opt("id") == requestId) {
                    matched = message
                    true
                } else {
                    require(!message.has("id")) { "MCP $id 响应 ID 不匹配：${message.opt("id")} != $requestId" }
                    false
                }
            }
            lastHttpStatus = response.getInt("status")
            response.getJSONObject("headers").keys().forEach { name ->
                if (name.equals("Mcp-Session-Id", true)) sessionId = response.getJSONObject("headers").getJSONArray(name).getString(0)
            }
            if (notification) {
                check(lastHttpStatus == 202 || lastHttpStatus == 204) { "MCP 通知失败 HTTP $lastHttpStatus：${response.optString("body")}" }
                return JSONObject()
            }
            val message = matched ?: if (!response.getBoolean("stream")) {
                val body = response.getString("body")
                try { JSONObject(body) } catch (error: Exception) {
                    throw AgentRpcException(-32000, "模块 $id 响应不是 JSON：$body", httpStatus = lastHttpStatus)
                }
            } else error("MCP $id 的 SSE 在请求 $requestId 响应前结束；结果未知，未重发")
            require(message.opt("id") == requestId || (message.isNull("id") && message.has("error"))) { "MCP $id 响应 ID 不匹配" }
            message.optJSONObject("error")?.let {
                throw AgentRpcException(it.getInt("code"), "模块 $id，请求 $requestId：${it.getString("message")}", it.optJSONObject("data"), lastHttpStatus)
            }
            check(lastHttpStatus in 200..299) { "MCP $id HTTP $lastHttpStatus：$message" }
            return message.getJSONObject("result")
        }

        fun connect() {
            if (version == "auto") {
                version = AgentMcpProtocol.MODERN
                try {
                    val discovery = rpc("server/discover")
                    val supported = discovery.getJSONArray("supportedVersions")
                    check((0 until supported.length()).any { supported.getString(it) == version }) { "MCP $id 未声明支持 $version" }
                    return
                } catch (error: AgentRpcException) {
                    if (error.code == -32022) {
                        val supported = error.data?.getJSONArray("supported") ?: throw error
                        version = AgentMcpProtocol.versions.drop(1).firstOrNull { candidate ->
                            (0 until supported.length()).any { supported.getString(it) == candidate }
                        } ?: throw error
                    } else if (error.code == -32601 && error.httpStatus !in setOf(401, 403, 429)) {
                        version = "2025-11-25"
                    } else throw error
                }
            }
            require(version in AgentMcpProtocol.versions) { "不支持的 MCP 版本：$version" }
            if (version == AgentMcpProtocol.MODERN) { rpc("server/discover"); return }
            val initialized = rpc("initialize", JSONObject().put("protocolVersion", version)
                .put("capabilities", JSONObject()).put("clientInfo", JSONObject().put("name", "legado-agent").put("version", BuildConfig.VERSION_NAME)))
            val selected = initialized.getString("protocolVersion")
            require(selected in AgentMcpProtocol.versions && selected != AgentMcpProtocol.MODERN) { "旧握手返回不支持的版本：$selected" }
            version = selected
            rpc("notifications/initialized", notification = true)
        }
    }

    fun discover(id: String, config: JSONObject, control: AgentControl, migrateSelection: Boolean = true): List<AgentTool> {
        try {
            val connection = Connection(id, JSONObject(config.toString()), control)
            connection.connect()
            val tools = mutableListOf<AgentTool>()
            val cursors = mutableSetOf<String>()
            var cursor: String? = null
            do {
                val result = connection.rpc("tools/list", JSONObject().apply { cursor?.let { put("cursor", it) } })
                val definitions = result.getJSONArray("tools")
                for (index in 0 until definitions.length()) {
                    val definition = definitions.getJSONObject(index)
                    val name = definition.getString("name")
                    require(name.isNotBlank()) { "MCP $id 工具名为空" }
                    tools += AgentTool("external.$id", name, definition.optString("description", name), definition.getJSONObject("inputSchema")) { arguments, active ->
                        active.check()
                        val latest = AgentStore.get("mcp.clients", id) ?: error("MCP 已删除：$id")
                        require(latest.getString("endpoint") == config.getString("endpoint") &&
                            latest.optString("apiKey") == config.optString("apiKey") &&
                            latest.optString("protocolVersion", "auto") == config.optString("protocolVersion", "auto")) {
                            "MCP $id 配置已修改；请停止并重新运行以使用新快照"
                        }
                        connection.rpc("tools/call", JSONObject().put("name", name).put("arguments", arguments))
                    }
                }
                cursor = if (result.has("nextCursor")) result.getString("nextCursor") else null
                if (cursor != null) require(cursors.add(cursor!!)) { "MCP $id 返回重复分页游标：$cursor" }
            } while (cursor != null)
            val validated = AgentCapabilities.validate(tools)
            if (migrateSelection) migrateToolSelection(id, validated)
            AgentStore.put("mcp.status", id, JSONObject().put("state", "ready").put("protocolVersion", connection.version).put("toolCount", tools.size))
            return validated
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            AgentStore.put("mcp.status", id, JSONObject().put("state", "error").put("error", error.stackTraceToString()))
            throw IllegalStateException("MCP 模块发现失败：$id (${config.optString("name")})\n${error.message}", error)
        }
    }

    private fun migrateToolSelection(id: String, tools: List<AgentTool>) {
        val module = "external.$id"
        val legacy = AgentStore.get("tool.selection.legacy", module) ?: return
        if (AgentStore.get("tool.selection", module) != null) return
        fun slug(value: String) = value.lowercase(Locale.getDefault()).map { if (it.isLetterOrDigit()) it else '_' }
            .joinToString("").replace(Regex("_+"), "_").trim('_')
        val selected = legacy.getJSONArray("enabled").let { array -> (0 until array.length()).map { array.getString(it) }.toSet() }
        val serverSlug = slug(legacy.getString("serverName")).take(16).ifBlank { "server" }
        val others = legacy.getJSONArray("otherServerNames")
        require(selected.none { it.startsWith("mcp_${serverSlug}_") } || (0 until others.length()).none {
            slug(others.getString(it)).take(16).ifBlank { "server" } == serverSlug
        }) { "旧 MCP 显示名称寻址存在冲突，不能猜测选中工具；请在外部 MCP 的工具开关中明确重新选择，原配置保留" }
        val aliases = mutableSetOf<String>()
        val enabled = tools.mapIndexedNotNull { index, tool ->
            val toolSlug = slug(tool.toolId).take(32).ifBlank { "tool" }
            val base = "mcp_${serverSlug}_$toolSlug"
            val suffix = "_${id.filter { it.isLetterOrDigit() }.takeLast(6)}_${index + 1}"
            val alias = if (base.take(64) !in aliases) base.take(64) else (base.take(64 - suffix.length) + suffix).take(64)
            aliases.add(alias)
            tool.toolId.takeIf { alias in selected }
        }
        AgentStore.database.runInTransaction {
            AgentStore.put("tool.selection", module, JSONObject().put("enabled", JSONArray(enabled)))
            AgentStore.put("migration.tool.selection", module, legacy.put("mappedToolIds", JSONArray(enabled)))
            AgentStore.dao.deleteDocument("tool.selection.legacy", module)
        }
    }
}
