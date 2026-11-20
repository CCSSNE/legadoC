package io.legado.app.ui.association

import com.google.gson.JsonIOException
import com.google.gson.JsonObject
import com.google.gson.JsonSyntaxException
import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import io.legado.app.data.entities.BookSource
import io.legado.app.exception.NoStackTraceException
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.isJsonArray
import io.legado.app.utils.isJsonObject
import java.io.Reader
import java.io.StringReader

internal sealed interface BookSourceImportJson {
    data class Sources(val items: List<BookSource>) : BookSourceImportJson
    data class SourceUrls(val items: List<String>) : BookSourceImportJson
}

/**
 * 统一书源 JSON 解析：数组、单对象、sourceUrls 均支持，并校验 bookSourceUrl。
 * 移植自 LegadoTeam/legado（喵公子版）ui/association/BookSourceImport.kt。
 *
 * 内存口径：文本入参版本保持原语义（前缀路由＋整体语义校验）；Reader 入参版本
 * 逐元素流式解析，网络/文件导入不再把全文与解析结果同时驻留内存（防大合集 OOM）。
 * 两条入口的错误契约一致：非 JSON →「不是书源」，数组含 null 元素 → 原提示语，
 * 尾部有多余数据 → JSON document was not fully consumed.（与 Gson 整体解析对齐）。
 */
internal fun parseBookSourceJson(
    text: String,
    allowSourceUrls: Boolean = true,
): BookSourceImportJson {
    val json = text.trim()
    return when {
        json.isJsonArray() -> parseSourceArray(StringReader(json))
        json.isJsonObject() -> parseSourceObject(StringReader(json), allowSourceUrls)
        else -> throw NoStackTraceException("不是书源")
    }
}

internal fun parseBookSourceJson(
    reader: Reader,
    allowSourceUrls: Boolean = true,
): BookSourceImportJson {
    val jsonReader = JsonReader(reader)
    jsonReader.setStrictness(Strictness.LENIENT)
    return when (jsonReader.peek()) {
        JsonToken.BEGIN_ARRAY -> parseSourceArray(jsonReader)
        JsonToken.BEGIN_OBJECT -> parseSourceObject(jsonReader, allowSourceUrls)
        else -> throw NoStackTraceException("不是书源")
    }
}

private fun parseSourceArray(jsonReader: JsonReader): BookSourceImportJson {
    val sources = ArrayList<BookSource>()
    jsonReader.beginArray()
    while (jsonReader.hasNext()) {
        val source: BookSource? = GSON.fromJson(jsonReader, BookSource::class.java)
        if (source == null) {
            throw JsonSyntaxException(
                "列表不能存在null元素，可能是json格式错误，通常为列表存在多余的逗号所致"
            )
        }
        sources.add(source)
    }
    jsonReader.endArray()
    assertFullyConsumed(jsonReader)
    sources.forEach { it.requireBookSourceUrl() }
    return BookSourceImportJson.Sources(sources)
}

private fun parseSourceObject(
    jsonReader: JsonReader,
    allowSourceUrls: Boolean,
): BookSourceImportJson {
    val jsonObject = JsonParser.parseReader(jsonReader).asJsonObject
    assertFullyConsumed(jsonReader)
    if (jsonObject.has("sourceUrls")) {
        if (!allowSourceUrls) {
            throw NoStackTraceException("不是书源")
        }
        val sourceUrlsElement = jsonObject.get("sourceUrls")
        if (sourceUrlsElement?.isJsonNull == true) {
            throw NoStackTraceException("不是书源")
        }
        val sourceUrls = sourceUrlsElement
            ?.let { GSON.fromJsonArray<String>(it.toString()).getOrThrow() }
            .orEmpty()
        if (sourceUrls.any { it.isBlank() }) {
            throw NoStackTraceException("不是书源")
        }
        return BookSourceImportJson.SourceUrls(sourceUrls)
    }
    val source = GSON.fromJson(jsonObject, BookSource::class.java)
    source.requireBookSourceUrl()
    return BookSourceImportJson.Sources(listOf(source))
}

private fun assertFullyConsumed(jsonReader: JsonReader) {
    // 与 Gson.fromJson(String) 的整体语义对齐：解析值之后还有多余数据即报错
    if (jsonReader.peek() != JsonToken.END_DOCUMENT) {
        throw JsonIOException("JSON document was not fully consumed.")
    }
}

private fun BookSource.requireBookSourceUrl() {
    if (bookSourceUrl.isNullOrBlank()) {
        throw NoStackTraceException("不是书源")
    }
}
