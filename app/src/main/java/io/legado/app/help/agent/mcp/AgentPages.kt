package io.legado.app.help.agent.mcp

import org.json.JSONArray
import org.json.JSONObject

object AgentPages {
    fun apply(value: JSONObject, arguments: JSONObject): JSONObject {
        val start = arguments.optInt("cursor", 0)
        val count = if (arguments.has("limit")) arguments.getInt("limit") else null
        require(start >= 0 && (count == null || count > 0)) { "游标不能为负数，每块条数必须大于零" }
        val pages = JSONObject()
        fun visit(value: Any, path: String): Any = when (value) {
            is JSONObject -> JSONObject().apply { value.keys().forEach { key -> put(key, visit(value.get(key), "$path/$key")) } }
            is JSONArray -> {
                val total = value.length()
                val begin = start.coerceAtMost(total)
                val end = if (count == null) total else (begin.toLong() + count).coerceAtMost(total.toLong()).toInt()
                pages.put(path, JSONObject().put("total", total).put("offset", begin)
                    .put("nextCursor", if (end < total) end else JSONObject.NULL).put("complete", end == total))
                JSONArray().apply { for (index in begin until end) put(value.get(index)) }
            }
            else -> value
        }
        return (visit(value, "") as JSONObject).put("pages", pages)
    }

    fun text(value: String, arguments: JSONObject): JSONObject {
        val offset = arguments.optInt("offset", 0)
        val count = if (arguments.has("maxChars")) arguments.getInt("maxChars") else value.length - offset
        require(offset in 0..value.length && (count > 0 || offset == value.length)) { "文本读取范围无效，每块长度必须大于零" }
        val end = (offset.toLong() + count).coerceAtMost(value.length.toLong()).toInt()
        return JSONObject().put("text", value.substring(offset, end)).put("offset", offset).put("total", value.length)
            .put("nextOffset", if (end < value.length) end else JSONObject.NULL).put("complete", end == value.length)
    }
}
