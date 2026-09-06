package io.legado.app.help.agent.mcp

import org.json.JSONArray
import org.json.JSONObject

object AgentPages {
    /** Only the named result collections are paged. Metadata, errors and arrays inside
     * an item are part of that item and must never be cut by a generic JSON walker. */
    fun apply(value: JSONObject, arguments: JSONObject, fields: List<String>): JSONObject {
        val start = arguments.optInt("cursor", 0)
        val count = if (arguments.has("limit")) arguments.getInt("limit") else null
        require(start >= 0 && (count == null || count > 0)) { "游标不能为负数，每块条数必须大于零" }
        val pages = JSONObject()
        val result = JSONObject(value.toString())
        fields.forEach { field ->
            val items = result.optJSONArray(field) ?: return@forEach
            val total = items.length()
            // Collections in a multi-list response have different lengths. An exhausted
            // collection stays empty while the caller advances the remaining collections.
            val begin = minOf(start, total)
            val end = if (count == null) total else minOf(begin.toLong() + count, total.toLong()).toInt()
            pages.put(field, JSONObject().put("total", total).put("offset", begin)
                .put("nextCursor", if (end < total) end else JSONObject.NULL).put("complete", end == total))
            result.put(field, JSONArray().apply { for (index in begin until end) put(items.get(index)) })
        }
        return result.put("pages", pages)
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
