package io.legado.app.data.entities

/**
 * 书签正文在阅读页中的显示样式（位掩码，可多选组合）
 */
object BookmarkStyle {
    const val NONE = 0
    const val SINGLE_UNDERLINE = 1
    const val DOUBLE_UNDERLINE = 1 shl 1
    const val WAVE_UNDERLINE = 1 shl 2
    const val HIGHLIGHT = 1 shl 3
    const val TEXT_COLOR = 1 shl 4
    const val STRIKETHROUGH = 1 shl 5

    /**
     * 解析每个效果的独立颜色（JSON：效果位 -> 颜色值）
     */
    fun parseStyleColors(json: String): Map<Int, Int> {
        if (json.isBlank()) return emptyMap()
        return runCatching {
            val obj = org.json.JSONObject(json)
            val map = HashMap<Int, Int>()
            val keys = obj.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val bit = key.toIntOrNull() ?: continue
                map[bit] = obj.getInt(key)
            }
            map
        }.getOrDefault(emptyMap())
    }

    /**
     * 序列化每个效果的独立颜色为 JSON 字符串
     */
    fun toStyleColorsJson(map: Map<Int, Int>): String {
        if (map.isEmpty()) return ""
        return runCatching {
            val obj = org.json.JSONObject()
            map.forEach { (bit, color) ->
                obj.put(bit.toString(), color)
            }
            obj.toString()
        }.getOrDefault("")
    }
}
