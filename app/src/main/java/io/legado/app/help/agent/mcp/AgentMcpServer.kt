package io.legado.app.help.agent.mcp

import fi.iki.elonen.NanoHTTPD
import io.legado.app.BuildConfig
import io.legado.app.data.agent.AgentRun
import io.legado.app.help.agent.AgentConfig
import io.legado.app.help.agent.AgentControl
import io.legado.app.help.agent.AgentStore
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.net.URI
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class AgentMcpServer(val moduleId: String, private val config: JSONObject) :
    NanoHTTPD(config.getString("address"), config.getInt("port")) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sessions = ConcurrentHashMap<String, String>()
    private val calls = ConcurrentHashMap<String, AgentControl>()
    private class AuthenticationException(message: String) : SecurityException(message)
    fun activeRequests(): Int = calls.size

    override fun stop() {
        calls.values.forEach { it.cancel("MCP 监听已关闭") }
        scope.cancel()
        sessions.clear()
        super.stop()
    }

    override fun serve(session: IHTTPSession): Response {
        var requestId: Any? = null
        try {
            authorize(session)
            if (session.uri != "/mcp") return json(Response.Status.NOT_FOUND, error(null, -32601, "端点不存在"))
            if (session.method == Method.DELETE) {
                val id = session.headers["mcp-session-id"] ?: return json(Response.Status.BAD_REQUEST, error(null, -32600, "缺少会话 ID"))
                require(sessions.remove(id) != null) { "MCP 会话不存在" }
                calls.filterKeys { it.startsWith("$id/") }.values.forEach { it.cancel("MCP 会话已终止") }
                return newFixedLengthResponse(Response.Status.OK, "application/json", "")
            }
            if (session.method != Method.POST) return json(Response.Status.METHOD_NOT_ALLOWED, error(null, -32600, "仅支持 POST；未提供 GET 通知流"))
            if (!session.headers["content-type"].orEmpty().contains("application/json", true)) return json(Response.Status.UNSUPPORTED_MEDIA_TYPE, error(null, -32600, "需要 application/json"))
            val accept = session.headers["accept"].orEmpty()
            if (!accept.contains("application/json") || !accept.contains("text/event-stream")) return json(Response.Status.NOT_ACCEPTABLE, error(null, -32600, "Accept 必须包含 JSON 和 SSE"))
            val files = mutableMapOf<String, String>()
            session.parseBody(files)
            val request = try { JSONObject(files["postData"] ?: error("请求体缺失")) } catch (error: Exception) {
                return json(Response.Status.BAD_REQUEST, error(null, -32700, "JSON 解析失败：${error.message}"))
            }
            requestId = request.opt("id")
            if (request.optString("jsonrpc") != "2.0" || !request.has("method")) return json(Response.Status.BAD_REQUEST, error(requestId, -32600, "无效 JSON-RPC 请求"))
            val method = request.getString("method")
            val params = request.optJSONObject("params") ?: JSONObject()
            val headerVersion = session.headers["mcp-protocol-version"]
            require(requestId == null || requestId is String || requestId is Number) { "JSON-RPC id 必须为字符串或数字" }
            if (method == "initialize") {
                val requested = params.getString("protocolVersion")
                val version = if (requested in AgentMcpProtocol.versions && requested != AgentMcpProtocol.MODERN) requested else "2025-11-25"
                val id = UUID.randomUUID().toString()
                sessions[id] = version
                return json(Response.Status.OK, result(requestId, JSONObject().put("protocolVersion", version).put("capabilities", capabilities())
                    .put("serverInfo", JSONObject().put("name", "legado-$moduleId").put("version", BuildConfig.VERSION_NAME))))
                    .apply { addHeader("Mcp-Session-Id", id) }
            }
            val modern = headerVersion == AgentMcpProtocol.MODERN || params.optJSONObject("_meta")?.optString("io.modelcontextprotocol/protocolVersion") == AgentMcpProtocol.MODERN
            val sessionId = session.headers["mcp-session-id"]
            if (modern) {
                if (headerVersion != AgentMcpProtocol.MODERN || params.optJSONObject("_meta")?.optString("io.modelcontextprotocol/protocolVersion") != headerVersion || session.headers["mcp-method"] != method ||
                    (method == "tools/call" && AgentMcpProtocol.decode(session.headers["mcp-name"].orEmpty()) != params.getString("name"))) {
                    return json(Response.Status.BAD_REQUEST, error(requestId, -32020, "必需元数据或请求头缺失/不匹配"))
                }
                val metadata = params.getJSONObject("_meta")
                if (metadata.optJSONObject("io.modelcontextprotocol/clientInfo") == null || metadata.optJSONObject("io.modelcontextprotocol/clientCapabilities") == null)
                    return json(Response.Status.BAD_REQUEST, error(requestId, -32020, "缺少逐请求客户端信息或能力"))
            } else {
                if (headerVersion !in AgentMcpProtocol.versions) return json(Response.Status.BAD_REQUEST, error(requestId, -32022, "不支持的 MCP 版本",
                    JSONObject().put("supported", JSONArray(AgentMcpProtocol.versions)).put("requested", headerVersion ?: JSONObject.NULL)))
                if (sessionId == null || sessions[sessionId] != headerVersion) return json(Response.Status.NOT_FOUND, error(requestId, -32000, "MCP 会话不存在或版本不匹配，请重新握手"))
            }
            if (requestId == null) {
                when (method) {
                    "notifications/initialized" -> require(!modern) { "当前版本没有 initialize 通知" }
                    "notifications/cancelled" -> {
                        require(!modern) { "当前 HTTP 协议通过关闭响应流取消" }
                        calls["$sessionId/${params.get("requestId")}"]?.cancel("客户端取消")
                    }
                    else -> return json(Response.Status.BAD_REQUEST, error(null, -32601, "不支持的通知：$method"))
                }
                return newFixedLengthResponse(Response.Status.ACCEPTED, "application/json", "")
            }
            return when (method) {
                "server/discover" -> json(Response.Status.OK, result(requestId, JSONObject().put("supportedVersions", JSONArray(AgentMcpProtocol.versions)).put("capabilities", capabilities())))
                "ping" -> json(Response.Status.OK, result(requestId, JSONObject()))
                "tools/list" -> json(Response.Status.OK, result(requestId, list(params)))
                "tools/call" -> {
                    val tool = AgentCapabilities.localTools(moduleId).singleOrNull { it.toolId == params.getString("name") }
                        ?: return json(Response.Status.BAD_REQUEST, error(requestId, -32602, "工具不存在"))
                    val arguments = params.optJSONObject("arguments") ?: JSONObject()
                    AgentCapabilities.checkPermission(tool, external = true)
                    AgentSchema.validate(arguments, tool.schema)
                    stream(requestId!!, sessionId, tool, arguments)
                }
                else -> json(Response.Status.NOT_FOUND, error(requestId, -32601, "不支持的方法：$method"))
            }
        } catch (error: AuthenticationException) {
            return json(Response.Status.UNAUTHORIZED, error(requestId, -32000, error.message.orEmpty())).apply { addHeader("WWW-Authenticate", "Bearer realm=\"Agent MCP\"") }
        } catch (error: SecurityException) {
            return json(Response.Status.FORBIDDEN, error(requestId, -32000, error.message.orEmpty()))
        } catch (error: Exception) {
            return json(Response.Status.BAD_REQUEST, error(requestId, -32602, error.message.orEmpty()))
        }
    }

    private fun authorize(session: IHTTPSession) {
        val expected = config.getString("apiKey")
        require(expected.isNotBlank()) { "MCP 访问密钥不能为空" }
        val authorization = session.headers["authorization"].orEmpty()
        if (!authorization.startsWith("Bearer ", true) || !MessageDigest.isEqual(expected.toByteArray(), authorization.substring(7).toByteArray()))
            throw AuthenticationException("MCP 访问密钥错误或缺失")
        val host = session.headers["host"] ?: throw SecurityException("缺少 Host")
        val uri = URI("http://$host")
        val allowedHosts = config.getJSONArray("allowedHosts")
        if ((0 until allowedHosts.length()).none { allowedHosts.getString(it).equals(uri.host, true) } || uri.port != config.getInt("port")) throw SecurityException("Host 不在监听允许列表：$host")
        session.headers["origin"]?.let { origin ->
            val allowed = config.getJSONArray("allowedOrigins")
            if ((0 until allowed.length()).none { allowed.getString(it) == origin }) throw SecurityException("Origin 未授权：$origin")
        }
        check(AgentStore.get("mcp.servers", moduleId)?.getBoolean("enabled") == true) { "MCP 模块已关闭" }
    }

    private fun capabilities() = JSONObject().put("tools", JSONObject())
    private fun list(params: JSONObject): JSONObject {
        val tools = AgentCapabilities.localTools(moduleId).sortedBy { it.toolId }
        val signature = AgentReading.revision(tools.joinToString { it.mcpDefinition().toString() })
        val cursor = params.optString("cursor")
        val start = if (cursor.isBlank()) 0 else {
            require(cursor.substringBefore(':') == signature) { "工具目录已变化，请重新发现" }
            cursor.substringAfter(':').toInt()
        }
        require(start in 0..tools.size)
        val pageSize = config.getInt("pageSize")
        require(pageSize > 0)
        val end = (start.toLong() + pageSize).coerceAtMost(tools.size.toLong()).toInt()
        return JSONObject().put("tools", JSONArray(tools.subList(start, end).map { it.mcpDefinition() })).apply {
            if (end < tools.size) put("nextCursor", "$signature:$end")
        }
    }

    private fun stream(id: Any, sessionId: String?, tool: AgentTool, arguments: JSONObject): Response {
        val key = if (sessionId == null) UUID.randomUUID().toString() else "$sessionId/$id"
        val run = AgentRun(UUID.randomUUID().toString(), "mcp:$moduleId", UUID.randomUUID().toString(), "mcp.$moduleId", "host-api-1", arguments.toString())
        val job = Job(scope.coroutineContext.job)
        val control = AgentControl(job) { type, value ->
            AgentStore.database.runInTransaction {
                AgentStore.event(run.id, type, value)
                if (type in setOf("running", "paused", "waiting_input")) AgentStore.dao.state(run.id, type, null)
            }
        }
        require(calls.putIfAbsent(key, control) == null) { "请求 ID 正在执行：$id" }
        try {
            io.legado.app.help.agent.AgentRuntime.registerExternal(run.id, control)
            AgentStore.dao.put(run)
        } catch (error: Throwable) {
            calls.remove(key); job.cancel(); io.legado.app.help.agent.AgentRuntime.unregisterExternal(run.id); throw error
        }
        val pipe = object : PipedInputStream() {
            override fun close() { super.close(); control.cancel("客户端关闭响应流") }
        }
        val output = PipedOutputStream(pipe)
        fun send(text: String) = synchronized(output) { output.write(text.toByteArray(Charsets.UTF_8)); output.flush() }
        val finished = java.util.concurrent.atomic.AtomicBoolean(false)
        val cancellationGuard = scope.launch(job + Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { if (!finished.get()) control.cancel("MCP 请求上下文已取消") }
        }
        val task = scope.launch(job) {
            val heartbeat = launch {
                while (isActive) { send(": keepalive\n\n"); delay(10000) }
            }
            try {
                AgentStore.event(run.id, "tool.start", JSONObject().put("requestId", id).put("moduleId", moduleId).put("toolId", tool.toolId).put("arguments", arguments))
                val value = AgentCapabilities.call(tool, arguments, control, external = true)
                AgentStore.database.runInTransaction {
                    AgentStore.event(run.id, "tool.result", value)
                    AgentStore.dao.state(run.id, "completed", null)
                }
                send("event: message\ndata: ${result(id, value)}\n\n")
            } catch (error: Throwable) {
                AgentStore.database.runInTransaction {
                    AgentStore.event(run.id, "tool.unknown", JSONObject().put("outcome", "unknown").put("error", error.stackTraceToString()).put("replayed", false))
                    AgentStore.dao.state(run.id, if (error is CancellationException || control.cancelled) "cancelled" else "failed",
                        io.legado.app.help.agent.AgentDiagnostics.protect(JSONObject().put("error", error.stackTraceToString())).toString())
                }
                if (job.isActive) send("event: message\ndata: ${error(id, -32603, error.message.orEmpty())}\n\n")
            } finally {
                heartbeat.cancel()
            }
        }
        task.invokeOnCompletion { failure ->
            finished.set(true)
            cancellationGuard.cancel()
            try {
                val state = AgentStore.dao.run(run.id)?.state
                if (state in setOf("running", "paused", "waiting_input")) {
                    val details = JSONObject().put("error", failure?.toString() ?: "请求任务未完成").put("outcome", "unknown").put("replayed", false)
                    AgentStore.database.runInTransaction {
                        val event = AgentStore.event(run.id, "cancelled", details)
                        AgentStore.dao.state(run.id, "cancelled", event.json)
                    }
                }
                output.close()
            } finally {
                calls.remove(key); io.legado.app.help.agent.AgentRuntime.unregisterExternal(run.id); job.complete()
            }
        }
        return newChunkedResponse(Response.Status.OK, "text/event-stream", pipe).apply {
            addHeader("Cache-Control", "no-cache"); addHeader("X-Accel-Buffering", "no")
        }
    }

    private fun result(id: Any?, value: JSONObject) = JSONObject().put("jsonrpc", "2.0").put("id", id ?: JSONObject.NULL).put("result", value)
    private fun error(id: Any?, code: Int, message: String, data: JSONObject? = null) = JSONObject().put("jsonrpc", "2.0").put("id", id ?: JSONObject.NULL)
        .put("error", JSONObject().put("code", code).put("message", message).apply { data?.let { put("data", it) } })
    private fun json(status: Response.Status, body: JSONObject) = newFixedLengthResponse(status, "application/json", body.toString())

    companion object {
        fun validateConfig(moduleId: String, config: JSONObject) {
            AgentConfig.validateDocument("mcp.servers", moduleId, config)
            val address = config.getString("address")
            require(address.isNotBlank() && !address.contains(' ') && !address.contains("://")) { "MCP 监听地址无效：$address" }
            require(config.getString("apiKey").isNotBlank()) { "MCP 访问密钥不能为空" }
        }
    }
}
