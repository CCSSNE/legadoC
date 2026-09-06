package io.legado.app.help.agent.mcp

import org.json.JSONArray
import org.json.JSONObject

object AgentSchema {
    fun validate(value: Any?, schema: JSONObject, path: String = "arguments") {
        schema.optJSONArray("enum")?.let { options ->
            require((0 until options.length()).any { options.get(it) == value }) { "$path 不在枚举范围" }
        }
        when (schema.optString("type")) {
            "object" -> {
                require(value is JSONObject) { "$path 必须为对象" }
                val required = schema.optJSONArray("required") ?: JSONArray()
                for (index in 0 until required.length()) require(value.has(required.getString(index))) { "$path 缺少 ${required.getString(index)}" }
                val properties = schema.optJSONObject("properties") ?: JSONObject()
                value.keys().forEach { key ->
                    val child = properties.optJSONObject(key)
                    if (child != null) validate(value.get(key), child, "$path.$key")
                    else require(schema.opt("additionalProperties") != false) { "$path 未知参数：$key" }
                }
            }
            "array" -> {
                require(value is JSONArray) { "$path 必须为数组" }
                schema.optJSONObject("items")?.let { item ->
                    for (index in 0 until value.length()) validate(value.get(index), item, "$path[$index]")
                }
            }
            "string" -> require(value is String) { "$path 必须为字符串" }
            "boolean" -> require(value is Boolean) { "$path 必须为布尔值" }
            "integer", "number" -> {
                require(value is Number && value.toDouble().isFinite()) { "$path 必须为有限数值" }
                if (schema.optString("type") == "integer") require(value.toDouble() == value.toLong().toDouble()) { "$path 必须为整数" }
                if (schema.has("minimum")) require(value.toDouble() >= schema.getDouble("minimum")) { "$path 小于下限" }
                if (schema.has("maximum")) require(value.toDouble() <= schema.getDouble("maximum")) { "$path 超过外部 schema 上限" }
            }
        }
    }
}
