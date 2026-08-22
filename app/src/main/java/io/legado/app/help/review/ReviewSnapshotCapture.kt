package io.legado.app.help.review

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.net.http.SslError
import android.webkit.SslErrorHandler
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import io.legado.app.constant.AppConst
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.http.CookieManager as AppCookieManager
import io.legado.app.help.http.okHttpClient
import io.legado.app.help.webView.WebViewPool
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.utils.GSON
import io.legado.app.utils.runOnUI
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import org.apache.commons.text.StringEscapeUtils
import splitties.init.appCtx
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 评论页快照抓取引擎。
 *
 * 两步走：
 * 1. 在书源 JS 上下文执行评论按钮的 click 代码，通过浏览器打开钩子拦截真实评论页地址；
 * 2. 用无头 WebView 加载该地址，循环穷尽“展开/查看回复/加载更多/懒加载”，
 *    把样式与图片内联为 data URI 后序列化 HTML 存为快照。
 * 快照完全离线可渲染；失败直接抛出，由调用方记日志，绝不影响正文。
 */
object ReviewSnapshotCapture {

    private val mHandler = Handler(Looper.getMainLooper())

    private const val PAGE_TIMEOUT_MS = 120_000L
    /** 穷尽循环轮数上限 */
    private const val MAX_EXPAND_ROUNDS = 40
    /** 每轮之间的等待 */
    private const val EXPAND_ROUND_INTERVAL_MS = 800L
    /** 连续几轮稳定才判定完成（含慢加载评论） */
    private const val STABLE_ROUNDS_TO_FINISH = 3
    /** 资源内联上限 */
    private const val MAX_INLINE_RESOURCES = 200
    private const val MAX_TOTAL_INLINE_BYTES = 30L * 1024 * 1024

    /**
     * 抓取结果（供诊断日志）：快照 + 展开检测轮数 + 实际点击“展开/加载更多”次数。
     */
    data class CaptureOutcome(
        val snapshot: ReviewSnapshot,
        /** 展开检测轮询轮数（包含“没点按钮”的稳定轮） */
        val expandRounds: Int,
        /** 实际点击“展开/回复/加载更多”按钮的次数（stats.clicked 累计） */
        val expandClickCount: Int
    )

    /**
     * 抓取真实评论页快照并序列化。
     * @param url 真实评论页地址（由调度器经与用户点击共用的执行逻辑解析）
     * @param initialHtml 书源 showBrowser 已用 ajax 取回的渲染 HTML；
     *        非空时作为 WebView 初始页面（不再发起网络请求），仍继续展开/内联/序列化
     */
    suspend fun capture(
        bookSource: BookSource,
        book: Book,
        chapter: BookChapter,
        buttonSrc: String,
        url: String,
        initialHtml: String? = null
    ): CaptureOutcome {
        val (html, expandRounds, expandClickCount) = snapshotPage(url, bookSource, initialHtml)
        return CaptureOutcome(
            snapshot = ReviewSnapshot(
                bookUrl = book.bookUrl,
                chapterUrl = chapter.url,
                chapterIndex = chapter.index,
                chapterTitle = chapter.title,
                buttonSrc = buttonSrc,
                url = url,
                title = "",
                html = html,
                savedAt = System.currentTimeMillis()
            ),
            expandRounds = expandRounds,
            expandClickCount = expandClickCount
        )
    }

    /**
     * 无头加载页面并穷尽展开后返回最终 HTML 与展开诊断数据。
     *
     * WebView 生命周期唯一化：成功/失败/超时/cancel 四条路径都只释放一次，
     * 通过 [java.util.concurrent.atomic.AtomicReference] + [AtomicBoolean] 保证。
     */
    private suspend fun snapshotPage(
        url: String,
        bookSource: BookSource,
        initialHtml: String? = null
    ): Triple<String, Int, Int> =
        withTimeout(PAGE_TIMEOUT_MS) {
            val analyzeUrl = AnalyzeUrl(url, source = bookSource)
            val headerMap = analyzeUrl.headerMap
            suspendCancellableCoroutine { block ->
                val pooledRef = AtomicReference<io.legado.app.help.webView.PooledWebView?>()
                val released = java.util.concurrent.atomic.AtomicBoolean(false)
                // 唯一释放点：重复调用被 AtomicBoolean 挡住
                fun releaseOnce() {
                    val pooled = pooledRef.getAndSet(null) ?: return
                    if (released.compareAndSet(false, true)) {
                        runOnUI { WebViewPool.release(pooled) }
                    }
                }
                runOnUI {
                    try {
                        val pooled = WebViewPool.acquire(appCtx)
                        pooledRef.set(pooled)
                        val webView = pooled.realWebView
                        webView.onResume()
                        webView.setBackgroundColor(android.graphics.Color.WHITE)
                        webView.settings.apply {
                            blockNetworkImage = false
                            headerMap[AppConst.UA_NAME]?.let { userAgentString = it }
                        }
                        AppCookieManager.applyToWebView(url)
                        val session = SnapshotSession(webView, url) { result, error, rounds, clicks ->
                            releaseOnce()
                            if (block.isActive) {
                                if (error != null) block.resumeWithException(error)
                                else block.resume(Triple(result ?: "", rounds, clicks))
                            }
                        }
                        webView.webViewClient = session.client
                        if (!initialHtml.isNullOrBlank()) {
                            // 书源 showBrowser 已取回渲染 HTML：直接作为初始页面，
                            // 仍走 onPageFinished → 展开/内联/序列化全流程
                            webView.loadDataWithBaseURL(url, initialHtml, "text/html", "utf-8", url)
                        } else {
                            webView.loadUrl(url, headerMap)
                        }
                        // 超时/cancel（withTimeout 抛 TimeoutCancellationException）路径
                        block.invokeOnCancellation {
                            runOnUI { session.destroy() }
                            releaseOnce()
                        }
                    } catch (e: Throwable) {
                        releaseOnce()
                        if (block.isActive) block.resumeWithException(e)
                    }
                }
            }
        }

    /**
     * 一个页面的穷尽会话：onPageFinished 后循环执行展开脚本直到稳定，
     * 再收集图片/样式资源内联，最后序列化 HTML。
     */
    private class SnapshotSession(
        private val webView: WebView,
        private val url: String,
        private val done: (String?, Throwable?, Int, Int) -> Unit
    ) {

        private var destroyed = false
        private var expandRounds = 0
        private var totalExpandClicks = 0
        private var stableRounds = 0
        private var lastTextLen = -1
        private var lastHeight = -1
        private var lastNodes = -1

        val client = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, finishedUrl: String?) {
                if (destroyed) return
                mHandler.postDelayed({ expandRound() }, 1500L)
            }

            @SuppressLint("WebViewClientOnReceivedSslError")
            override fun onReceivedSslError(
                view: WebView?,
                handler: SslErrorHandler?,
                error: SslError?
            ) {
                handler?.proceed()
            }

            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                // 评论页内的跳转不跟随，避免快照跑题
                return request?.isForMainFrame == true &&
                    !request.url.toString().startsWith(url.substringBefore("#"))
            }
        }

        fun destroy() {
            destroyed = true
        }

        private fun finish(html: String?) {
            if (destroyed) return
            destroyed = true
            done(html, null, expandRounds, totalExpandClicks)
        }

        private fun fail(error: Throwable) {
            if (destroyed) return
            destroyed = true
            done(null, error, expandRounds, totalExpandClicks)
        }

        private data class PageStats(val clicked: Int, val textLen: Int, val height: Int, val nodes: Int)

        private fun expandRound() {
            if (destroyed) return
            webView.evaluateJavascript(EXPAND_JS) { json ->
                mHandler.post {
                    if (destroyed) return@post
                    val stats = parseStats(json)
                    if (stats == null) {
                        // 页面还未就绪：下一轮再试
                        expandRounds++
                        if (expandRounds >= MAX_EXPAND_ROUNDS) {
                            inlineResources()
                        } else {
                            mHandler.postDelayed({ expandRound() }, EXPAND_ROUND_INTERVAL_MS)
                        }
                        return@post
                    }
                    // 稳定要求：本轮没点过展开按钮，且 文本长度/页面高度/DOM 节点数 全部一致
                    val stable = stats.clicked == 0 &&
                        stats.textLen == lastTextLen &&
                        stats.height == lastHeight &&
                        stats.nodes == lastNodes
                    // 累计“实际点击展开/回复/加载更多”次数
                    totalExpandClicks += stats.clicked
                    lastTextLen = stats.textLen
                    lastHeight = stats.height
                    lastNodes = stats.nodes
                    stableRounds = if (stable) stableRounds + 1 else 0
                    expandRounds++
                    if (stableRounds >= STABLE_ROUNDS_TO_FINISH || expandRounds >= MAX_EXPAND_ROUNDS) {
                        inlineResources()
                    } else {
                        mHandler.postDelayed({ expandRound() }, EXPAND_ROUND_INTERVAL_MS)
                    }
                }
            }
        }

        private fun parseStats(json: String?): PageStats? {
            json ?: return null
            return runCatching {
                val s = StringEscapeUtils.unescapeJson(json).trim('"')
                if (s == "null") return null
                val obj = GSON.fromJson(s, Map::class.java)
                PageStats(
                    clicked = (obj?.get("c") as? Double)?.toInt() ?: 0,
                    textLen = (obj?.get("t") as? Double)?.toInt() ?: 0,
                    height = (obj?.get("h") as? Double)?.toInt() ?: 0,
                    nodes = (obj?.get("n") as? Double)?.toInt() ?: 0
                )
            }.getOrNull()
        }

        /** 收集图片与样式表并内联，然后取最终 HTML */
        private fun inlineResources() {
            if (destroyed) return
            webView.evaluateJavascript(COLLECT_RESOURCES_JS) { json ->
                mHandler.post {
                    if (destroyed) return@post
                    val urls = parseResourceUrls(json)
                    Thread {
                        runCatching {
                            val inline = downloadResources(urls)
                            mHandler.post { applyInline(inline) }
                        }.onFailure {
                            // 资源下载失败不影响快照本体
                            mHandler.post { serialize() }
                        }
                    }.start()
                }
            }
        }

        private data class InlineResources(
            val imgMap: Map<String, String> = emptyMap(),
            val cssMap: Map<String, String> = emptyMap()
        )

        private fun parseResourceUrls(json: String?): Pair<List<String>, List<String>> {
            json ?: return emptyList<String>() to emptyList()
            return runCatching {
                val s = StringEscapeUtils.unescapeJson(json).trim('"')
                if (s == "null") return emptyList<String>() to emptyList()
                @Suppress("UNCHECKED_CAST")
                val obj = GSON.fromJson(s, Map::class.java) as? Map<String, List<String>>
                val img = obj?.get("img").orEmpty().filter { it.startsWith("http") }.distinct()
                val css = obj?.get("css").orEmpty().filter { it.startsWith("http") }.distinct()
                img to css
            }.getOrNull() ?: (emptyList<String>() to emptyList())
        }

        private fun downloadResources(urls: Pair<List<String>, List<String>>): InlineResources {
            val (imgUrls, cssUrls) = urls
            var totalBytes = 0L
            val imgMap = linkedMapOf<String, String>()
            for (rawUrl in imgUrls.take(MAX_INLINE_RESOURCES)) {
                if (totalBytes > MAX_TOTAL_INLINE_BYTES) break
                runCatching {
                    fetchBytes(rawUrl)
                }.getOrNull()?.let { bytes ->
                    if (bytes.size <= 8L * 1024 * 1024) {
                        val mime = guessMime(rawUrl, bytes)
                        imgMap[rawUrl] =
                            "data:$mime;base64," +
                                android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                        totalBytes += bytes.size
                    }
                }
            }
            val cssMap = linkedMapOf<String, String>()
            for (rawUrl in cssUrls.take(MAX_INLINE_RESOURCES)) {
                if (totalBytes > MAX_TOTAL_INLINE_BYTES) break
                runCatching {
                    fetchBytes(rawUrl).toString(Charsets.UTF_8)
                }.getOrNull()?.takeIf { it.isNotBlank() }?.let { cssText ->
                    cssMap[rawUrl] = cssText
                    totalBytes += cssText.toByteArray(Charsets.UTF_8).size
                }
            }
            return InlineResources(imgMap, cssMap)
        }

        /** 同步抓取资源字节（在后台线程调用） */
        private fun fetchBytes(resourceUrl: String): ByteArray {
            val request = okhttp3.Request.Builder()
                .url(resourceUrl)
                .header("Referer", url)
                .build()
            okHttpClient.newCall(request).execute().use { response ->
                check(response.isSuccessful) { "HTTP ${response.code}" }
                return response.body.bytes()
            }
        }

        private fun guessMime(rawUrl: String, bytes: ByteArray): String {
            val lower = rawUrl.substringBefore('?').lowercase()
            return when {
                lower.endsWith(".png") -> "image/png"
                lower.endsWith(".webp") -> "image/webp"
                lower.endsWith(".gif") -> "image/gif"
                lower.endsWith(".svg") -> "image/svg+xml"
                lower.endsWith(".css") -> "text/css"
                bytes.size > 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() -> "image/jpeg"
                else -> "image/jpeg"
            }
        }

        private fun applyInline(inline: InlineResources) {
            if (destroyed) return
            if (inline.imgMap.isEmpty() && inline.cssMap.isEmpty()) {
                serialize()
                return
            }
            webView.evaluateJavascript(buildApplyJs(inline)) { serialize() }
        }

        private fun serialize() {
            if (destroyed) return
            webView.evaluateJavascript("document.documentElement.outerHTML") { raw ->
                val html = raw?.let {
                    runCatching { StringEscapeUtils.unescapeJson(it).trim('"') }.getOrNull()
                }.orEmpty()
                if (html.isBlank()) {
                    fail(NoStackTraceException("评论页快照序列化为空 $url"))
                } else {
                    finish(stripScripts(html))
                }
            }
        }

        private fun stripScripts(html: String): String {
            // 快照离线渲染：去掉脚本，保留结构与内联样式/图片
            return html
                .replace(Regex("(?is)<script[^>]*>.*?</script>"), "")
                .replace(Regex("(?is)<script[^>]*/>"), "")
        }

        private fun buildApplyJs(inline: InlineResources): String {
            val imgJson = GSON.toJson(inline.imgMap)
            val cssJson = GSON.toJson(inline.cssMap)
            return "(function(){" +
                "var m=$imgJson;" +
                "var c=$cssJson;" +
                "document.querySelectorAll('img[src]').forEach(function(el){" +
                "var d=m[el.src];if(d)el.src=d;});" +
                "document.querySelectorAll('link[rel=\"stylesheet\"]').forEach(function(el){" +
                "var d=c[el.href];if(d){var s=document.createElement('style');" +
                "s.textContent=d;el.parentNode.insertBefore(s,el);el.remove();}});" +
                "})()"
        }
    }

    private const val EXPAND_JS =
        "(function(){" +
            "var pat=/(展开|更多回复|查看回复|加载更多|查看更多|查看全部|显示全部|点击查看|继续阅读|load\\s*more|show\\s*more|view\\s*more|expand)/i;" +
            "var clicked=0;" +
            "var els=document.querySelectorAll('a,button,[role=\"button\"],[onclick],div,span,p');" +
            "for(var i=0;i<els.length;i++){var el=els[i];" +
            "var t=(el.innerText||'').trim();" +
            "if(!t||t.length>24||!pat.test(t))continue;" +
            "var r=el.getBoundingClientRect();" +
            "if(r.width<1||r.height<1)continue;" +
            "if(el.children.length>2)continue;" +
            "el.scrollIntoView({block:'center'});" +
            "try{el.click();}catch(e){}" +
            "try{el.dispatchEvent(new MouseEvent('mousedown',{bubbles:true}));" +
            "el.dispatchEvent(new MouseEvent('mouseup',{bubbles:true}));" +
            "el.dispatchEvent(new TouchEvent('touchend',{bubbles:true}));}catch(e){}" +
            "clicked++;if(clicked>=6)break;}" +
            "try{window.scrollTo(0,document.body?document.body.scrollHeight:0);}catch(e){}" +
            "var h=document.body?document.body.scrollHeight:0;" +
            "var n=document.getElementsByTagName('*').length;" +
            "return JSON.stringify({c:clicked,t:document.body?document.body.innerText.length:0,h:h,n:n});" +
            "})()"

    private const val COLLECT_RESOURCES_JS =
        "(function(){" +
            "var img=[],css=[];" +
            "document.querySelectorAll('img[src]').forEach(function(el){img.push(el.src);});" +
            "document.querySelectorAll('link[rel=\"stylesheet\"][href]').forEach(function(el){css.push(el.href);});" +
            "return JSON.stringify({img:img,css:css});" +
            "})()"
}
