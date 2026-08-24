package io.legado.app.help.review

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.net.http.SslError
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.google.gson.stream.JsonReader
import io.legado.app.constant.AppConst
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.WebCacheManager
import io.legado.app.help.cache.CacheOperationDiagnostics
import io.legado.app.help.http.CookieManager as AppCookieManager
import io.legado.app.help.http.okHttpClient
import io.legado.app.help.webView.WebJsExtensions
import io.legado.app.help.webView.WebJsExtensions.Companion.JS_INJECTION
import io.legado.app.help.webView.WebJsExtensions.Companion.JS_URL
import io.legado.app.help.webView.WebJsExtensions.Companion.nameCache
import io.legado.app.help.webView.WebJsExtensions.Companion.nameJava
import io.legado.app.help.webView.WebJsExtensions.Companion.nameSource
import io.legado.app.help.webView.WebJsExtensions.Companion.nameUrl
import io.legado.app.help.webView.WebViewPool
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.utils.GSON
import io.legado.app.utils.runOnUI
import kotlinx.coroutines.suspendCancellableCoroutine
import org.apache.commons.text.StringEscapeUtils
import splitties.init.appCtx
import java.io.ByteArrayInputStream
import java.io.StringReader
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
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

    /** 页面加载与评论展开共享的执行预算；不包含重内存阶段的排队或执行。 */
    private const val PAGE_EXECUTION_TIMEOUT_MS = 60_000L
    /** 等待重内存 permit 的队列预算；到期直接失败，不占用后续 heavy work 预算。 */
    private const val HEAVY_STAGE_WAIT_TIMEOUT_MS = 60_000L
    /** 已取得 permit 后，资源内联、outerHTML 与解码共享的执行预算。 */
    private const val HEAVY_STAGE_WORK_TIMEOUT_MS = 60_000L
    /** 穷尽循环轮数上限 */
    private const val MAX_EXPAND_ROUNDS = 40
    /** 每轮之间的等待 */
    private const val EXPAND_ROUND_INTERVAL_MS = 800L
    /** 连续几轮稳定才判定完成（含慢加载评论） */
    private const val STABLE_ROUNDS_TO_FINISH = 3
    /** Resource budget for one complete offline snapshot. Budget excess is a visible failure. */
    private const val MAX_INLINE_RESOURCES = 200
    private const val MAX_TOTAL_INLINE_BYTES = 30L * 1024 * 1024
    private const val MAX_INLINE_RESOURCE_BYTES = 8L * 1024 * 1024
    /** 单个内联资源抓取超时：内联失败只丢该资源，不拖整章 */
    private const val RESOURCE_FETCH_TIMEOUT_MS = 8_000L
    /**
     * 内联资源构建、outerHTML 回传和 JSON 解码会短时保留完整页面及其副本；这是本进程
     * 唯一的重内存区段。页面加载/展开仍可并行，只有进入该区段才排队。
     */
    private val heavyStagePermits = Semaphore(1, true)

    private class ResourceBudgetExceededException(message: String) : IllegalStateException(message)

    private enum class CaptureStage(
        val diagnosticsStage: String,
        val executionTimeoutMs: Long?,
        val timeoutLabel: String,
    ) {
        PAGE_EXECUTION(
            diagnosticsStage = "PAGE_EXECUTION",
            executionTimeoutMs = PAGE_EXECUTION_TIMEOUT_MS,
            timeoutLabel = "评论页加载/展开",
        ),
        HEAVY_STAGE_WAIT(
            diagnosticsStage = "HEAVY_STAGE_WAIT",
            executionTimeoutMs = null,
            timeoutLabel = "评论快照重内存队列等待",
        ),
        HEAVY_STAGE_WORK(
            diagnosticsStage = "HEAVY_STAGE_WORK",
            executionTimeoutMs = HEAVY_STAGE_WORK_TIMEOUT_MS,
            timeoutLabel = "评论快照重内存处理",
        ),
    }

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
     * showBrowser 带回的 HTML 有效性校验。
     *
     * 按“结构”判定，而不是关键词黑名单：真实评论页里“登录/请先登录/操作频繁/
     * 失败”等字样非常常见（页脚、提示、meta 都可能出现），见词即死会把正常评论页
     * 误杀成整章 0/615 全部失败，代价远大于“偶尔把一个带这类字样的正常页存成快照”。
     *
     * 判为无效的情形：
     * - 空或纯空白
     * - 纯 JSON 错误负载（无任何 HTML 标签）
     * - 无 HTML 标签的纯文本且过短（<512 字符，基本是错误提示）
     * - 有 HTML 结构但极短（<256 字符）
     */
    fun isValidCommentHtml(html: String?): Boolean {
        if (html.isNullOrBlank()) return false
        val trimmed = html.trimStart()
        // 纯 JSON 错误负载（无任何 HTML 标签）
        if ((trimmed.startsWith("{") || trimmed.startsWith("[")) && !html.contains("<")) {
            return false
        }
        // 无 HTML 标签的纯文本：只有足够长才信，否则是错误提示
        if (!html.contains("<")) {
            return html.length >= 512
        }
        // 有 HTML 结构：过短按无效，其余视为有效评论页
        return html.length >= 256
    }

    /**
     * 抓取真实评论页快照并序列化。
     * @param url 真实评论页地址（由调度器经与用户点击共用的执行逻辑解析）
     * @param initialHtml 书源 showBrowser 已用 ajax 取回的渲染 HTML；
     *        非空且通过 [isValidCommentHtml] 时作为 WebView 初始页面（不再发起网络请求），
     *        仍继续展开/内联/序列化；无效则按失败处理
     * @param preloadJs 书源 showBrowser 传入的 preloadJs：随初始页面注入 JS bridge 环境
     */
    internal suspend fun capture(
        bookSource: BookSource,
        book: Book,
        chapter: BookChapter,
        buttonSrc: String,
        url: String,
        initialHtml: String? = null,
        preloadJs: String? = null,
        diagnostics: CacheOperationDiagnostics.Context? = null,
    ): CaptureOutcome {
        val trace = diagnostics?.let {
            CacheOperationDiagnostics.begin(
                it.forChapter(chapter.index),
                "CAPTURE",
                CacheOperationDiagnostics.Metrics(inputChars = initialHtml?.length),
                startAlways = true,
            )
        }
        return try {
            if (initialHtml != null && !isValidCommentHtml(initialHtml)) {
                throw NoStackTraceException(
                    "showBrowser 带回的 HTML 非有效评论页（疑似 ajax 错误/异常文本，" +
                        "${initialHtml.length} 字符，已按失败处理）"
                )
            }
            val (html, expandRounds, expandClickCount) = snapshotPage(
                url,
                bookSource,
                initialHtml,
                preloadJs,
                trace,
            )
            CaptureOutcome(
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
            ).also {
                trace?.done(CacheOperationDiagnostics.Metrics(outputChars = html.length))
            }
        } catch (error: Throwable) {
            trace?.fail(error)
            throw error
        }
    }

    /**
     * 无头加载页面并穷尽展开后返回最终 HTML 与展开诊断数据。
     *
     * WebView 生命周期唯一化：成功/失败/分阶段超时/cancel 四条路径都只释放一次，
     * 通过 [java.util.concurrent.atomic.AtomicReference] + [AtomicBoolean] 保证。
     */
    private suspend fun snapshotPage(
        url: String,
        bookSource: BookSource,
        initialHtml: String? = null,
        preloadJs: String? = null,
        diagnostics: CacheOperationDiagnostics.Operation? = null,
    ): Triple<String, Int, Int> {
        val analyzeUrl = AnalyzeUrl(url, source = bookSource)
        val headerMap = analyzeUrl.headerMap
        return suspendCancellableCoroutine { block ->
            val pooledRef = AtomicReference<io.legado.app.help.webView.PooledWebView?>()
            val sessionRef = AtomicReference<SnapshotSession?>()
            val released = java.util.concurrent.atomic.AtomicBoolean(false)
            // 唯一释放点：重复调用被 AtomicBoolean 挡住
            fun releaseOnce() {
                val pooled = pooledRef.getAndSet(null) ?: return
                if (released.compareAndSet(false, true)) {
                    runOnUI { WebViewPool.release(pooled) }
                }
            }
            block.invokeOnCancellation {
                runOnUI { sessionRef.getAndSet(null)?.destroy() }
                releaseOnce()
            }
            runOnUI {
                if (!block.isActive) return@runOnUI
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
                    val jsBridge = if (!initialHtml.isNullOrBlank()) {
                        PageJsBridge(preloadJs)
                    } else {
                        null
                    }
                    val session = SnapshotSession(webView, url, jsBridge, diagnostics) {
                        result, error, rounds, clicks ->
                        sessionRef.set(null)
                        releaseOnce()
                        if (block.isActive) {
                            if (error != null) block.resumeWithException(error)
                            else block.resume(Triple(result ?: "", rounds, clicks))
                        }
                    }
                    sessionRef.set(session)
                    webView.webViewClient = session.client
                    if (!initialHtml.isNullOrBlank()) {
                        // 书源 showBrowser 已取回渲染 HTML：作为初始页面并注入 JS bridge 环境
                        // （真实评论页可能依赖 window.java/run/ajaxAwait 等），
                        // 仍走 onPageFinished → 展开/内联/序列化全流程
                        webView.addJavascriptInterface(WebCacheManager, nameCache)
                        webView.addJavascriptInterface(bookSource, nameSource)
                        webView.addJavascriptInterface(WebJsExtensions(bookSource, null, webView), nameJava)
                        webView.loadDataWithBaseURL(
                            url, spliceJsUrl(initialHtml), "text/html", "utf-8", url
                        )
                    } else {
                        webView.loadUrl(url, headerMap)
                    }
                } catch (e: Throwable) {
                    sessionRef.getAndSet(null)?.destroy()
                    releaseOnce()
                    if (block.isActive) block.resumeWithException(e)
                }
            }
        }
    }

    /**
     * 初始 HTML 的 head 后插入 JS_URL（触发 shouldInterceptRequest 加载 preloadJs 桥）。
     * 与 BottomWebViewDialog 的 preloadJs 注入同一套机制。
     */
    private fun spliceJsUrl(html: String): String {
        val headIndex = html.indexOf("<head", ignoreCase = true)
        if (headIndex >= 0) {
            val closingHeadIndex = html.indexOf('>', startIndex = headIndex)
            if (closingHeadIndex >= 0) {
                return StringBuilder(html).insert(closingHeadIndex + 1, JS_URL).toString()
            }
        }
        return JS_URL + html
    }

    /**
     * 一个页面的穷尽会话：onPageFinished 后循环执行展开脚本直到稳定，
     * 再收集图片/样式资源内联，最后序列化 HTML。
     * jsBridge 非空时（初始 HTML 模式）拦截 nameUrl 注入书源 preloadJs 桥。
     */
    private class PageJsBridge(val preloadJs: String?)

    private class SnapshotSession(
        private val webView: WebView,
        private val url: String,
        private val jsBridge: PageJsBridge?,
        private val diagnostics: CacheOperationDiagnostics.Operation?,
        private val done: (String?, Throwable?, Int, Int) -> Unit
    ) {

        @Volatile
        private var destroyed = false
        private var expandRounds = 0
        private var totalExpandClicks = 0
        private var stableRounds = 0
        private var lastTextLen = -1
        private var lastHeight = -1
        private var lastNodes = -1

        private var jsInjectedForPage = false
        private val heavyStagePermitHeld = AtomicBoolean(false)
        private var activeStage: CaptureStage? = null
        private var stageTimeout: Runnable? = null

        init {
            startTimedStage(CaptureStage.PAGE_EXECUTION)
        }

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

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                // 主框架加载失败（DNS/连接/超时等）立即失败，不再干等到 60s 超时：
                // 部分评论页域名在本机网络解析/连接不通，串行等待会拖死整章
                if (request?.isForMainFrame == true) {
                    fail(NoStackTraceException("评论页加载失败 code=${error?.errorCode} ${error?.description}"))
                }
            }

            @Deprecated("API 23 以下")
            override fun onReceivedError(
                view: WebView?,
                errorCode: Int,
                description: String?,
                failingUrl: String?
            ) {
                if (failingUrl == url || failingUrl == url.substringBefore("#")) {
                    fail(NoStackTraceException("评论页加载失败 code=$errorCode $description"))
                }
            }

            override fun onReceivedHttpError(
                view: WebView?,
                request: WebResourceRequest?,
                errorResponse: WebResourceResponse?
            ) {
                if (request?.isForMainFrame == true) {
                    fail(NoStackTraceException("评论页 HTTP 错误 ${errorResponse?.statusCode}"))
                }
            }

            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                // 评论页内的跳转不跟随，避免快照跑题
                return request?.isForMainFrame == true &&
                    !request.url.toString().startsWith(url.substringBefore("#"))
            }

            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? {
                // 初始 HTML 模式：拦截 nameUrl，注入 JS_INJECTION + 书源 preloadJs，
                // 给真实评论页提供 window.java/run/ajaxAwait 等 JS bridge 环境
                val bridge = jsBridge
                if (bridge != null && !jsInjectedForPage &&
                    request.url.toString() == nameUrl
                ) {
                    jsInjectedForPage = true
                    return WebResourceResponse(
                        "text/javascript",
                        "utf-8",
                        ByteArrayInputStream(
                            ("(() => {$JS_INJECTION\n${bridge.preloadJs.orEmpty()}\n})();")
                                .toByteArray()
                        )
                    )
                }
                return super.shouldInterceptRequest(view, request)
            }
        }

        fun destroy() {
            destroyed = true
            clearStageTimeout()
            activeStage = null
            releaseHeavyStagePermit()
        }

        private fun finish(html: String?) {
            if (destroyed) return
            completeActiveStage(CacheOperationDiagnostics.Metrics(outputChars = html?.length))
            destroyed = true
            releaseHeavyStagePermit()
            done(html, null, expandRounds, totalExpandClicks)
        }

        private fun fail(error: Throwable) {
            if (destroyed) return
            failActiveStage(error)
            destroyed = true
            releaseHeavyStagePermit()
            done(null, error, expandRounds, totalExpandClicks)
        }

        /**
         * 只有页面执行与 permit 后的重内存处理拥有执行超时；排队由 tryAcquire 自己限时。
         * 这样队列中的时间永远不会消耗 heavy work 的执行预算。
         */
        private fun startTimedStage(stage: CaptureStage) {
            completeActiveStage()
            activeStage = stage
            diagnostics?.stageStart(stage.diagnosticsStage, startAlways = true)
            val timeoutMs = stage.executionTimeoutMs ?: return
            val timeout = Runnable {
                if (!destroyed && activeStage == stage) {
                    fail(
                        NoStackTraceException(
                            "${stage.timeoutLabel}执行超时 ${timeoutMs / 1000}s",
                        )
                    )
                }
            }
            stageTimeout = timeout
            mHandler.postDelayed(timeout, timeoutMs)
        }

        private fun startQueueWaitStage() {
            completeActiveStage()
            activeStage = CaptureStage.HEAVY_STAGE_WAIT
            diagnostics?.stageStart(CaptureStage.HEAVY_STAGE_WAIT.diagnosticsStage, startAlways = true)
        }

        private fun completeActiveStage(metrics: CacheOperationDiagnostics.Metrics = CacheOperationDiagnostics.Metrics()) {
            val stage = activeStage ?: return
            clearStageTimeout()
            activeStage = null
            diagnostics?.stageDone(stage.diagnosticsStage, metrics)
        }

        private fun failActiveStage(error: Throwable) {
            val stage = activeStage ?: return
            clearStageTimeout()
            activeStage = null
            diagnostics?.stageFail(stage.diagnosticsStage, error)
        }

        private fun clearStageTimeout() {
            stageTimeout?.let(mHandler::removeCallbacks)
            stageTimeout = null
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
            startQueueWaitStage()
            // 只限流会创建完整 Java 大对象的阶段；此时之前的页面加载与展开可以继续并行。
            Thread {
                try {
                    if (!heavyStagePermits.tryAcquire(HEAVY_STAGE_WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                        mHandler.post {
                            fail(
                                NoStackTraceException(
                                    "评论快照重内存队列等待超时 " +
                                        "${HEAVY_STAGE_WAIT_TIMEOUT_MS / 1000}s",
                                )
                            )
                        }
                    } else if (destroyed) {
                        heavyStagePermits.release()
                    } else {
                        heavyStagePermitHeld.set(true)
                        mHandler.post {
                            if (destroyed) {
                                releaseHeavyStagePermit()
                            } else {
                                diagnostics?.mark("HEAVY_STAGE_ACQUIRED")
                                startTimedStage(CaptureStage.HEAVY_STAGE_WORK)
                                collectAndInlineResources()
                            }
                        }
                    }
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    mHandler.post { fail(e) }
                }
            }.start()
        }

        private fun collectAndInlineResources() {
            if (destroyed) return
            webView.evaluateJavascript(COLLECT_RESOURCES_JS) { json ->
                mHandler.post {
                    if (destroyed) return@post
                    val urls = parseResourceUrls(json)
                    diagnostics?.mark(
                        "RESOURCE_LIST_READY",
                        CacheOperationDiagnostics.Metrics(resourceCount = urls.first.size + urls.second.size),
                    )
                    Thread {
                        runCatching {
                            val inline = downloadResources(urls)
                            diagnostics?.mark(
                                "RESOURCE_INLINE_READY",
                                CacheOperationDiagnostics.Metrics(
                                    resourceCount = inline.resourceCount,
                                    outputBytes = inline.inlineBytes,
                                ),
                            )
                            mHandler.post { applyInline(inline) }
                        }.onFailure {
                            diagnostics?.warn("RESOURCE_INLINE_FAILED", it)
                            mHandler.post {
                                if (it is ResourceBudgetExceededException) fail(it) else serialize()
                            }
                        }
                    }.start()
                }
            }
        }

        private fun releaseHeavyStagePermit() {
            if (heavyStagePermitHeld.compareAndSet(true, false)) {
                heavyStagePermits.release()
            }
        }

        private data class InlineResources(
            val imgMap: Map<String, String> = emptyMap(),
            val cssMap: Map<String, String> = emptyMap(),
            val inlineBytes: Long = 0L,
        ) {
            val resourceCount: Int get() = imgMap.size + cssMap.size
        }

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
            val resourceCount = imgUrls.size + cssUrls.size
            if (resourceCount > MAX_INLINE_RESOURCES) {
                throw ResourceBudgetExceededException(
                    "评论快照资源数 $resourceCount 超过预算 $MAX_INLINE_RESOURCES",
                )
            }
            val imgMap = linkedMapOf<String, String>()
            val cssMap = linkedMapOf<String, String>()
            // 内联资源的最终体积不可预知。此前一次把全部请求同时 async，单个
            // fetchBytes() 都会整块分配 byte[]，使“单个快照”在入 DOM 前就产生多份大对象。
            // 现在按队列逐项抓取；预算不够时整份快照明确失败，绝不悄悄少存资源。
            var totalBytes = 0L
            imgUrls.forEach { rawUrl ->
                val bytes = runCatching { fetchBytes(rawUrl) }.getOrNull() ?: return@forEach
                if (bytes.size > MAX_INLINE_RESOURCE_BYTES) {
                    throw ResourceBudgetExceededException(
                        "评论快照资源 ${bytes.size}B 超过单资源预算 $MAX_INLINE_RESOURCE_BYTES B: $rawUrl",
                    )
                }
                val mime = guessMime(rawUrl, bytes)
                val value = "data:$mime;base64," +
                    android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                val nextTotal = totalBytes + value.length
                if (nextTotal > MAX_TOTAL_INLINE_BYTES) {
                    throw ResourceBudgetExceededException(
                        "评论快照内联资源 $nextTotal B 超过总预算 $MAX_TOTAL_INLINE_BYTES B",
                    )
                }
                totalBytes = nextTotal
                imgMap[rawUrl] = value
            }
            cssUrls.forEach { rawUrl ->
                val bytes = runCatching { fetchBytes(rawUrl) }.getOrNull() ?: return@forEach
                if (bytes.size > MAX_INLINE_RESOURCE_BYTES) {
                    throw ResourceBudgetExceededException(
                        "评论快照资源 ${bytes.size}B 超过单资源预算 $MAX_INLINE_RESOURCE_BYTES B: $rawUrl",
                    )
                }
                val text = bytes.toString(Charsets.UTF_8).takeIf { it.isNotBlank() } ?: return@forEach
                val nextTotal = totalBytes + text.toByteArray(Charsets.UTF_8).size
                if (nextTotal > MAX_TOTAL_INLINE_BYTES) {
                    throw ResourceBudgetExceededException(
                        "评论快照内联资源 $nextTotal B 超过总预算 $MAX_TOTAL_INLINE_BYTES B",
                    )
                }
                totalBytes = nextTotal
                cssMap[rawUrl] = text
            }
            return InlineResources(imgMap, cssMap, totalBytes)
        }

        /** 同步抓取资源字节（在后台线程调用），单请求 8s 超时避免连不通的地址长期卡死 */
        private fun fetchBytes(resourceUrl: String): ByteArray {
            val request = okhttp3.Request.Builder()
                .url(resourceUrl)
                .header("Referer", url)
                .build()
            okHttpClient.newBuilder()
                .connectTimeout(RESOURCE_FETCH_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .readTimeout(RESOURCE_FETCH_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .build()
                .newCall(request).execute().use { response ->
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
            // 在 WebView DOM 内去掉脚本后再取 outerHTML，避免 Java Regex 对整份页面连续
            // 复制。JSON 解码也移出主线程，不能在 evaluateJavascript 回调中阻塞 UI。
            diagnostics?.stageStart("SANITIZE")
            webView.evaluateJavascript(SERIALIZE_SNAPSHOT_JS) { raw ->
                diagnostics?.mark(
                    "WEBVIEW_HTML_READY",
                    CacheOperationDiagnostics.Metrics(inputChars = raw?.length),
                )
                Thread {
                    runCatching { decodeJavascriptString(raw) }
                        .onSuccess { html ->
                            mHandler.post {
                                if (destroyed) return@post
                                if (html.isNullOrBlank()) {
                                    fail(NoStackTraceException("评论页快照序列化为空 $url"))
                                } else {
                                    diagnostics?.stageDone(
                                        "SANITIZE",
                                        CacheOperationDiagnostics.Metrics(outputChars = html.length),
                                    )
                                    finish(html)
                                }
                            }
                        }
                        .onFailure { error ->
                            diagnostics?.stageFail("SANITIZE", error)
                            mHandler.post { fail(error) }
                        }
                }.start()
            }
        }

        private fun decodeJavascriptString(raw: String?): String? {
            if (raw == null || raw == "null") return null
            // evaluateJavascript 的回调是一个 JSON string。JsonReader 直接解码这一层，
            // 避免 unescapeJson(...).trim(...) 产生两份完整 HTML 的中间字符串。
            return JsonReader(StringReader(raw)).use { reader -> reader.nextString() }
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

    /** 在 DOM 内移除脚本后再序列化，离线快照仍保留结构、样式与图片。 */
    private const val SERIALIZE_SNAPSHOT_JS =
        "(function(){" +
            "var scripts=document.querySelectorAll('script');" +
            "for(var i=scripts.length-1;i>=0;i--){var e=scripts[i];" +
            "if(e.parentNode)e.parentNode.removeChild(e);}" +
            "return document.documentElement.outerHTML;" +
            "})()"
}
