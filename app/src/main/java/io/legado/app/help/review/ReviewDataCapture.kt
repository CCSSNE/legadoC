package io.legado.app.help.review

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.legado.app.help.cache.CacheOperationDiagnostics
import io.legado.app.help.config.AppConfig
import io.legado.app.help.http.okHttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** idea_comment 协议适配器。只识别已验证的段评协议，域名由实际评论地址提供。 */
internal object ReviewDataCapture {
    // 实际并发由各阶段 Gate 统一控制，避免 OkHttp 默认单主机队列再次限流。
    private val client = okHttpClient.newBuilder().dispatcher(okhttp3.Dispatcher().apply {
        maxRequests = Int.MAX_VALUE
        maxRequestsPerHost = Int.MAX_VALUE
    }).build()
    fun supports(url: String, html: String?, tab: ReviewSnapshotCapture.ReviewTab?, protocol: String): Boolean {
        require(protocol.isEmpty() || protocol == "idea_comment") { "未知评论数据协议：$protocol" }
        val address = url.toHttpUrlOrNull() ?: return false
        return tab == null && address.encodedPath == "/idea_comment" &&
            listOf("book_id", "item_id", "para").all { address.queryParameter(it) != null } &&
            (protocol == "idea_comment" || (html != null && html.contains("loadParaFromApi") && html.contains("reply_api")))
    }

    suspend fun capture(url: String, headers: Map<String, String>, initialHtml: String?,
                        trace: CacheOperationDiagnostics.Operation?): String = coroutineScope {
        val base = requireNotNull(url.toHttpUrlOrNull())
        val emoji = Regex("""var\s+EM\s*=\s*(\{[^;]*});""").find(initialHtml.orEmpty())?.let {
            JsonParser.parseString(it.groupValues[1]).asJsonObject.entrySet().associate { entry ->
                entry.key to requireNotNull(base.resolve(entry.value.asString)).toString()
            }
        }.orEmpty()
        suspend fun fetch(reply: Boolean, params: Map<String, String>): JsonObject {
            val builder = base.newBuilder()
            listOf("api", "reply_api", "cursor", "count", "sort", "comment_id").forEach {
                builder.removeAllQueryParameters(it)
            }
            params.forEach { (key, value) -> builder.setQueryParameter(key, value) }
            val request = Request.Builder().url(builder.build()).apply {
                headers.forEach { (key, value) -> header(key, value) }
                header("Referer", url)
            }.build()
            var attempt = 0
            while (true) {
                try {
                    val gate = if (reply) ReviewDownloadScheduler.replies else ReviewDownloadScheduler.data
                    val json = gate.withPermit { executeRequest(request) }
                    trace?.mark(if (reply) "REPLY_PAGE_RECEIVED" else "DATA_PAGE_RECEIVED",
                        CacheOperationDiagnostics.Metrics(inputChars = json.length), force = true)
                    return withContext(Dispatchers.Default) {
                        ReviewDownloadScheduler.heavy.withPermit { JsonParser.parseString(json).asJsonObject }
                    }
                } catch (error: IOException) {
                    trace?.warn(if (reply) "REPLY_HTTP_FAILED" else "DATA_HTTP_FAILED", error)
                    if (attempt >= ReviewDownloadConfig.Number.RETRIES.value) throw error
                    attempt++
                    trace?.mark("HTTP_RETRY_$attempt", force = true)
                    delay(ReviewDownloadConfig.retryDelay(attempt))
                }
            }
        }
        val items = mutableListOf<JsonObject>()
        val cursors = hashSetOf<String>()
        val ids = hashSetOf<String>()
        var cursor = "0"
        var sourceText = ""
        while (true) {
            check(cursors.add(cursor)) { "段评游标循环: $cursor" }
            val response = fetch(false, mapOf("api" to "1", "cursor" to cursor,
                "count" to ReviewDownloadConfig.Number.PAGE_SIZE.value.toString(), "sort" to "1"))
            if (response.has("code")) check(response.get("code").asInt == 0) { "段评接口失败: $response" }
            val data = response.getAsJsonObject("data") ?: error("段评缺少 data")
            val page = data.getAsJsonArray("data_list") ?: error("段评缺少 data_list")
            trace?.mark("DATA_PAGE_COUNT_${page.size()}", force = true)
            data.get("para_src_content")?.takeUnless { it.isJsonNull }?.let { sourceText = it.asString }
            page.forEach { element ->
                val item = element.asJsonObject
                val id = item.getAsJsonObject("comment").get("comment_id").asString
                check(ids.add(id)) { "段评跨页重复: $id" }
                items += item
            }
            val meta = response.getAsJsonObject("common_list_info") ?: error("段评缺少分页状态")
            check(meta.has("has_more")) { "段评缺少 has_more" }
            if (!meta.hasMore()) break
            check(page.size() > 0 && meta.has("cursor")) { "段评分页没有推进" }
            cursor = meta.get("cursor").asString
        }
        val replies = if (!AppConfig.cacheReviewReplies) emptyList() else items.map { item -> async {
            val comment = item.getAsJsonObject("comment")
            val expected = comment.getAsJsonObject("stat").get("reply_count").asInt
            val embedded = item.getAsJsonArray("replies") ?: JsonArray()
            check(expected >= 0) { "楼中楼总数无效: $expected" }
            if (expected == 0 && embedded.size() == 0) return@async emptyList<JsonObject>()
            if (ReviewDownloadConfig.enabled(ReviewDownloadConfig.REUSE_REPLIES) && embedded.size() >= expected) {
                return@async embedded.map { it.asJsonObject }
            }
            val result = mutableListOf<JsonObject>()
            val seenCursors = hashSetOf<String>()
            val seenIds = hashSetOf<String>()
            var offset = "0"
            while (true) {
                check(seenCursors.add(offset)) { "楼中楼游标循环: $offset" }
                val response = fetch(true, mapOf("reply_api" to "1",
                    "comment_id" to comment.get("comment_id").asString, "cursor" to offset,
                    "count" to ReviewDownloadConfig.Number.REPLY_SIZE.value.toString()))
                check(response.get("code")?.asInt == 0) { "楼中楼接口失败: $response" }
                val data = response.getAsJsonObject("data") ?: error("楼中楼缺少 data")
                val page = data.getAsJsonArray("reply_list") ?: error("楼中楼缺少 reply_list")
                trace?.mark("REPLY_PAGE_COUNT_${page.size()}", force = true)
                page.forEach {
                    val record = it.asJsonObject
                    check(seenIds.add(record.get("reply_id").asString)) { "楼中楼跨页重复" }
                    result += record
                }
                check(data.has("has_more")) { "楼中楼缺少 has_more" }
                if (!data.hasMore()) break
                check(page.size() > 0 && data.has("next_offset")) { "楼中楼缺少 next_offset" }
                offset = data.get("next_offset").asString
            }
            check(result.size >= expected) { "楼中楼数量不足: ${result.size}/$expected" }
            result
        } }.awaitAll()
        withContext(Dispatchers.Default) {
            ReviewDownloadScheduler.heavy.withPermit {
                val doc = Jsoup.parse("<!doctype html><html><head><meta charset='utf-8'></head><body></body></html>", url)
                doc.body().attr("data-review-protocol", "idea_comment")
                doc.head().appendElement("style").text("body{font-family:sans-serif;padding:16px}article{border-bottom:1px solid #ccc;padding:12px}p{white-space:pre-wrap;overflow-wrap:anywhere}.avatar{width:36px;height:36px;border-radius:50%}.comment-image{max-width:100%}.replies{margin-left:24px}small{opacity:.7}")
                doc.body().appendElement("h2").text("段评")
                doc.body().appendElement("blockquote").text(sourceText)
                items.forEachIndexed { index, item ->
                    val comment = item.getAsJsonObject("comment")
                    val article = doc.body().appendElement("article").attr("data-comment-id", comment.get("comment_id").asString)
                    render(article, comment, emoji)
                    if (replies.isNotEmpty()) {
                        val list = article.appendElement("div").addClass("replies").addClass("reply-list")
                        replies[index].forEach { render(list.appendElement("article"), it, emoji) }
                    }
                }
                doc.outerHtml()
            }
        }
    }

    private fun JsonObject.hasMore(): Boolean = when (val value = get("has_more")?.asString) {
        "true", "1" -> true
        "false", "0" -> false
        else -> error("无效分页完成状态: $value")
    }

    private fun render(parent: Element, record: JsonObject, emoji: Map<String, String>) {
        val common = record.getAsJsonObject("common") ?: record.getAsJsonObject("Common") ?: record
        val info = common.getAsJsonObject("user_info") ?: error("评论缺少用户信息")
        val user = info.getAsJsonObject("base_info") ?: info
        fun JsonObject.text(key: String) = get(key)?.takeUnless { it.isJsonNull }?.asString.orEmpty()
        val avatar = user.text("user_avatar")
        if (AppConfig.cacheReviewAvatars && avatar.isNotEmpty()) parent.appendElement("img").addClass("avatar").attr("src", avatar)
        parent.appendElement("b").text(user.text("user_name").ifEmpty { user.text("nickname") })
        val content = common.get("content")?.takeIf { it.isJsonObject }?.asJsonObject
        val paragraph = parent.appendElement("p")
        val text = content?.text("text") ?: common.text("text")
        var offset = 0
        Regex("""\[[^\]]+]""").findAll(text).forEach { match ->
            paragraph.appendText(text.substring(offset, match.range.first))
            val address = emoji[match.value]
            if (address != null && AppConfig.cacheReviewImages) {
                paragraph.appendElement("img").attr("src", address).attr("alt", match.value)
                    .attr("style", "width:24px;height:24px;vertical-align:middle")
            } else paragraph.appendText(match.value)
            offset = match.range.last + 1
        }
        paragraph.appendText(text.substring(offset))
        parent.appendElement("small").text("${common.text("create_timestamp")} · 赞 ${record.getAsJsonObject("stat")?.text("digg_count") ?: record.text("digg_count")}")
        common.getAsJsonObject("reply_to_user_info")?.let { parent.appendElement("small").text(" 回复 ${it.text("user_name")}") }
        if (AppConfig.cacheReviewImages) {
            val images = content?.getAsJsonObject("image_data_list")?.getAsJsonArray("image_data")
                ?: common.getAsJsonArray("image_data") ?: JsonArray()
            images.forEach { image ->
                val address = image.asJsonObject.text("web_uri")
                check(address.isNotBlank()) { "评论图片缺少 web_uri" }
                parent.appendElement("img").addClass("comment-image").attr("src", address)
            }
        }
    }

    private suspend fun executeRequest(request: Request): String = suspendCancellableCoroutine { continuation ->
        val timeout = ReviewDownloadConfig.Number.DATA_TIMEOUT.value.toLong()
        val call = client.newBuilder().connectTimeout(timeout, TimeUnit.MILLISECONDS)
            .readTimeout(timeout, TimeUnit.MILLISECONDS).callTimeout(timeout, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(false).build().newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }
            override fun onResponse(call: Call, response: Response) {
                try {
                    val body = response.use {
                        if (!it.isSuccessful) throw IOException("评论数据 HTTP ${it.code}")
                        it.body?.string() ?: throw IOException("评论数据响应为空")
                    }
                    if (continuation.isActive) continuation.resume(body)
                } catch (error: Throwable) {
                    if (continuation.isActive) continuation.resumeWithException(error)
                }
            }
        })
    }
}
