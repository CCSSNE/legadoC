package io.legado.app.help.agent.mcp

import io.legado.app.help.agent.AgentControl
import io.legado.app.help.agent.AgentHttp
import io.legado.app.help.agent.AgentStore
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.util.UUID

object AgentMcpClient {
    fun validateConfig(config: JSONObject) {
        require(config.getString("name").isNotBlank()) { "MCP 名称不能为空" }
        val uri = URI(config.getString("endpoint"))
        require(uri.scheme in setOf("http", "https") && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.fragment == null) {
            "MCP endpoint 必须为不含用户信息和片段的 HTTP(S) 地址"
        }
        require(uri.port == -1 || uri.port in 1..65535) { "MCP 端口无效" }
        require(config.optString("protocolVersion", "auto") in AgentMcpProtocol.versions + "auto") { "不支持的 MCP 协议版本" }
        require(config.opt("enabled") is Boolean) { "MCP enabled 必须为布尔值" }
        require(config.opt("apiKey") is String) { "MCP apiKey 必须为字符串，可明确留空" }
        require(config.getString("apiKey").none { it == '\r' || it == '\n' }) { "MCP 密钥含无效换行" }
    }

    /** One connection belongs to one discovery snapshot. No task-global active control,
     * mutable tool registry, tool-call retry, or lock around a network exchange. */
    private class Connection(val id: String, val config: JSONObject) {
        var version = config.optString("protocolVersion", "auto")
            private set
        private var sessionId: String? = null
        var capabilities = JSONObject()
            private set

        fun rpc(method: String, parameters: JSONObject, control: AgentControl,
                notification: Boolean = false, headerParameters: List<AgentMcpProtocol.HeaderParameter> = emptyList()): JSONObject {
            control.check()
            val requestId = UUID.randomUUID().toString()
            val params = JSONObject(parameters.toString())
            if (version == AgentMcpProtocol.MODERN) params.put("_meta", AgentMcpProtocol.metadata())
            val body = JSONObject().put("jsonrpc", "2.0").put("method", method).put("params", params)
            if (!notification) body.put("id", requestId)
            val headers = JSONObject().put("MCP-Protocol-Version", version)
            if (version == AgentMcpProtocol.MODERN) {
                headers.put("Mcp-Method", method)
                if (params.has("name")) headers.put("Mcp-Name", AgentMcpProtocol.encode(params.getString("name")))
                val arguments = params.optJSONObject("arguments") ?: JSONObject()
                headerParameters.forEach { parameter -> AgentMcpProtocol.parameterValue(parameter, arguments)?.let {
                    headers.put("Mcp-Param-${parameter.name}", AgentMcpProtocol.encode(it))
                } }
            } else sessionId?.let { headers.put("Mcp-Session-Id", it) }
            config.getString("apiKey").takeIf { it.isNotBlank() }?.let { headers.put("Authorization", "Bearer $it") }
            val request = JSONObject().put("url", config.getString("endpoint")).put("method", "POST")
                .put("headers", headers).put("body", body.toString())
            var matched: JSONObject? = null
            val response = AgentHttp.exchange(request, control) { _, data ->
                val message = JSONObject(data)
                require(message.optString("jsonrpc") == "2.0") { "MCP $id 收到无效 JSON-RPC SSE 事件" }
                if (message.opt("id") == requestId) {
                    matched = message
                    true
                } else {
                    require(!message.has("id") && message.has("method")) {
                        "MCP $id 收到未声明支持的服务端请求或其他请求的响应：$message"
                    }
                    false
                }
            }
            val status = response.getInt("status")
            if (method == "initialize" && status in 200..299) {
                response.getJSONObject("headers").keys().forEach { name ->
                    if (name.equals("Mcp-Session-Id", true)) {
                        sessionId = response.getJSONObject("headers").getJSONArray(name).getString(0).also { value ->
                            require(value.isNotEmpty() && value.all { it.code in 33..126 }) { "MCP 服务端返回无效会话 ID" }
                        }
                    }
                }
            }
            if (notification) {
                check(status == 202) { "MCP 通知失败 HTTP $status：${response.optString("body")}" }
                return JSONObject()
            }
            val message = matched ?: if (!response.getBoolean("stream")) {
                val payload = response.getString("body")
                try { JSONObject(payload) } catch (error: Exception) {
                    throw AgentRpcException(-32000, "模块 $id 响应不是 JSON：$payload", httpStatus = status, recognizedRpc = false)
                }
            } else error("MCP $id SSE 在请求 $requestId 响应前结束；结果未知，未重发")
            val validRpc = message.optString("jsonrpc") == "2.0" && message.optJSONObject("error") != null
            if (status !in 200..299 && !validRpc) {
                throw AgentRpcException(-32000, "模块 $id HTTP $status：$message", httpStatus = status, recognizedRpc = false)
            }
            require(message.optString("jsonrpc") == "2.0" &&
                (message.opt("id") == requestId || (message.isNull("id") && message.has("error")))) { "MCP $id 响应 ID 或 jsonrpc 不匹配" }
            message.optJSONObject("error")?.let {
                throw AgentRpcException(it.getInt("code"), "模块 $id，请求 $requestId：${it.getString("message")}", it.optJSONObject("data"), status)
            }
            check(status in 200..299) { "MCP $id HTTP $status：$message" }
            val result = message.getJSONObject("result")
            if (version == AgentMcpProtocol.MODERN) {
                require(result.getString("resultType") == "complete") {
                    "MCP $id 返回未协商的 resultType：${result.optString("resultType")}；未重新执行工具"
                }
            }
            return result
        }

        fun connect(control: AgentControl) {
            if (version == "auto") {
                version = AgentMcpProtocol.MODERN
                try {
                    discoverModern(control)
                    return
                } catch (error: AgentRpcException) {
                    if (error.code == -32022 && error.recognizedRpc) {
                        val supported = error.data?.getJSONArray("supported") ?: throw error
                        version = AgentMcpProtocol.legacyVersions.firstOrNull { candidate ->
                            (0 until supported.length()).any { supported.getString(it) == candidate }
                        } ?: throw error
                    } else if (error.httpStatus == 400 && (!error.recognizedRpc || error.code !in AgentMcpProtocol.modernErrors)) {
                        version = AgentMcpProtocol.legacyVersions.first()
                    } else throw error
                }
            }
            require(version in AgentMcpProtocol.versions) { "不支持的 MCP 版本：$version" }
            if (version == AgentMcpProtocol.MODERN) { discoverModern(control); return }
            val initialized = rpc("initialize", JSONObject().put("protocolVersion", version)
                .put("capabilities", JSONObject()).put("clientInfo", AgentMcpProtocol.implementation("legado-agent")), control)
            val selected = initialized.getString("protocolVersion")
            require(selected in AgentMcpProtocol.legacyVersions) { "旧握手返回不支持的版本：$selected" }
            version = selected
            capabilities = initialized.getJSONObject("capabilities")
            rpc("notifications/initialized", JSONObject(), control, notification = true)
        }

        private fun discoverModern(control: AgentControl) {
            val discovery = rpc("server/discover", JSONObject(), control)
            val supported = discovery.getJSONArray("supportedVersions")
            require((0 until supported.length()).any { supported.getString(it) == version }) { "MCP $id 未声明支持 $version" }
            require(discovery.getLong("ttlMs") >= 0 && discovery.getString("cacheScope") in setOf("public", "private")) { "MCP 发现缓存元数据无效" }
            capabilities = discovery.getJSONObject("capabilities")
        }
    }

    fun discover(id: String, config: JSONObject, control: AgentControl): List<AgentTool> {
        try {
            validateConfig(config)
            val captured = JSONObject(config.toString())
            val connection = Connection(id, captured)
            connection.connect(control)
            val tools = mutableListOf<AgentTool>()
            val rejected = JSONArray()
            val names = mutableSetOf<String>()
            val cursors = mutableSetOf<String>()
            var cursor: String? = null
            if (connection.capabilities.has("tools")) do {
                val result = connection.rpc("tools/list", JSONObject().apply { cursor?.let { put("cursor", it) } }, control)
                val definitions = result.getJSONArray("tools")
                for (index in 0 until definitions.length()) {
                    control.check()
                    val definition = definitions.getJSONObject(index)
                    val name = definition.getString("name")
                    require(name.isNotBlank() && names.add(name)) { "MCP $id 工具名称为空或重复：$name" }
                    val schema = definition.getJSONObject("inputSchema")
                    val mirrored = try {
                        if (connection.version == AgentMcpProtocol.MODERN) AgentMcpProtocol.headerParameters(schema) else emptyList()
                    } catch (error: IllegalArgumentException) {
                        // The HTTP specification requires exclusion of invalid header annotations.
                        // Every exclusion is retained in the visible discovery status.
                        rejected.put(JSONObject().put("tool", name).put("error", error.message))
                        continue
                    }
                    tools += AgentTool("external.$id", name, definition.optString("description", name), schema, definition) { arguments, active ->
                        active.check()
                        val latest = AgentStore.get("mcp.clients", id) ?: error("MCP 已删除：$id")
                        require(listOf("endpoint", "apiKey", "protocolVersion").all { latest.opt(it) == captured.opt(it) }) {
                            "MCP $id 配置已修改；请停止并重新运行以使用新快照"
                        }
                        val result = connection.rpc("tools/call", JSONObject().put("name", name).put("arguments", arguments), active, headerParameters = mirrored)
                        require(result.opt("content") is JSONArray) { "MCP $id 工具 $name 缺少 content 数组" }
                        AgentCapabilities.normalize(result)
                    }
                }
                cursor = if (result.has("nextCursor") && !result.isNull("nextCursor")) result.getString("nextCursor") else null
                if (cursor != null) require(cursor!!.isNotBlank() && cursors.add(cursor!!)) { "MCP $id 返回空白或重复分页游标：$cursor" }
            } while (cursor != null)
            require(tools.isNotEmpty() || rejected.length() == 0) { "MCP $id 全部工具声明无效：$rejected" }
            val validated = AgentCapabilities.validate(tools)
            AgentStore.put("mcp.status", id, JSONObject().put("state", if (rejected.length() == 0) "ready" else "partial")
                .put("protocolVersion", connection.version).put("toolCount", tools.size).put("rejectedTools", rejected))
            return validated
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            control.check()
            AgentStore.put("mcp.status", id, JSONObject().put("state", "error").put("error", error.stackTraceToString()))
            throw IllegalStateException("MCP 模块发现失败：$id (${config.optString("name")})\n${error.message}", error)
        }
    }

}
