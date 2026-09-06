package io.legado.app.help.agent.mcp

import android.util.Base64
import io.legado.app.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Locale

class AgentRpcException(val code: Int, message: String, val data: JSONObject? = null,
                        val httpStatus: Int = 400, val recognizedRpc: Boolean = true) :
    IllegalStateException("MCP $code / HTTP $httpStatus：$message")

/** Version-dependent envelopes are owned by the transport, never by a tool executor. */
object AgentMcpProtocol {
    const val MODERN = "2026-07-28"
    val legacyVersions = listOf("2025-11-25", "2025-06-18", "2025-03-26")
    val versions = listOf(MODERN) + legacyVersions
    val modernErrors = setOf(-32020, -32021, -32022)

    fun implementation(name: String) = JSONObject().put("name", name).put("version", BuildConfig.VERSION_NAME)
    fun metadata() = JSONObject().put("io.modelcontextprotocol/protocolVersion", MODERN)
        .put("io.modelcontextprotocol/clientInfo", implementation("legado-agent"))
        .put("io.modelcontextprotocol/clientCapabilities", JSONObject())

    fun result(id: Any, value: JSONObject, version: String, serverName: String): JSONObject {
        val body = JSONObject(value.toString())
        if (version == MODERN) {
            body.put("resultType", "complete")
            val meta = body.optJSONObject("_meta") ?: JSONObject()
            body.put("_meta", meta.put("io.modelcontextprotocol/serverInfo", implementation(serverName)))
        }
        return JSONObject().put("jsonrpc", "2.0").put("id", id).put("result", body)
    }

    fun error(id: Any?, code: Int, message: String, data: JSONObject? = null) = JSONObject()
        .put("jsonrpc", "2.0").put("id", id ?: JSONObject.NULL)
        .put("error", JSONObject().put("code", code).put("message", message).apply { data?.let { put("data", it) } })

    fun encode(value: String): String = if (value != value.trim() || value.any { it.code !in 32..126 && it != '\t' } ||
        (value.startsWith("=?base64?") && value.endsWith("?="))) {
        "=?base64?" + Base64.encodeToString(value.toByteArray(Charsets.UTF_8), Base64.NO_WRAP) + "?="
    } else value

    fun decode(value: String): String {
        require(value.all { it.code in 32..126 || it == '\t' }) { "MCP 请求头含无效字符" }
        if (!value.startsWith("=?base64?") || !value.endsWith("?=")) return value
        val bytes = Base64.decode(value.removePrefix("=?base64?").removeSuffix("?="), Base64.NO_WRAP)
        return Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
    }

    data class HeaderParameter(val path: List<String>, val name: String, val type: String)

    fun headerParameters(schema: JSONObject): List<HeaderParameter> {
        val found = mutableListOf<HeaderParameter>()
        val used = mutableSetOf<String>()
        fun walk(value: Any, path: List<String>, reachable: Boolean) {
            when (value) {
                is JSONObject -> {
                    if (value.has("x-mcp-header")) {
                        val name = value.getString("x-mcp-header")
                        val type = value.getString("type")
                        require(reachable && path.isNotEmpty()) { "x-mcp-header 只能标记 properties 链上的参数" }
                        require(name.matches(Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+"))) { "无效 x-mcp-header 名称：$name" }
                        require(used.add(name.lowercase(Locale.ROOT))) { "重复 x-mcp-header：$name" }
                        require(type in setOf("string", "integer", "boolean")) { "x-mcp-header 仅支持 string/integer/boolean" }
                        found += HeaderParameter(path, name, type)
                    }
                    value.keys().forEach { key ->
                        val child = value.get(key)
                        if (key == "properties" && child is JSONObject && reachable) {
                            child.keys().forEach { property -> walk(child.get(property), path + property, true) }
                        } else if (child is JSONObject || child is JSONArray) walk(child, path, false)
                    }
                }
                is JSONArray -> for (i in 0 until value.length()) walk(value.get(i), path, false)
            }
        }
        walk(schema, emptyList(), true)
        return found
    }

    fun parameterValue(parameter: HeaderParameter, arguments: JSONObject): String? {
        var value: Any = arguments
        parameter.path.forEach { part ->
            if (value !is JSONObject || !(value as JSONObject).has(part)) return null
            value = (value as JSONObject).get(part)
            if (value === JSONObject.NULL) return null
        }
        return when (parameter.type) {
            "integer" -> {
                require(value is Number && (value as Number).toDouble().isFinite()) { "${parameter.path} 必须为整数" }
                val number = (value as Number).toDouble()
                require(number == (value as Number).toLong().toDouble() && kotlin.math.abs(number) <= 9007199254740991.0) { "MCP 头参数整数超出精确范围" }
                (value as Number).toLong().toString()
            }
            "boolean" -> { require(value is Boolean); value.toString() }
            else -> { require(value is String); value as String }
        }
    }
}
