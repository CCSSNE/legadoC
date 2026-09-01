package io.legado.app.help.review

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import io.legado.app.help.config.AppConfig
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
import io.legado.app.utils.configureOfflineResourceLoading
import io.legado.app.utils.runOnUI
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import org.apache.commons.text.StringEscapeUtils
import splitties.init.appCtx
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InterruptedIOException
import java.io.Reader
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
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
    /** 超时看门狗不能依赖 WebView 所在的主线程，否则主线程卡顿会同时拖迟所有 Capture。 */
    private val timeoutScheduler = ScheduledThreadPoolExecutor(2) { runnable ->
        Thread(runnable, "ReviewSnapshotTimeout").apply { isDaemon = true }
    }.apply {
        setRemoveOnCancelPolicy(true)
    }
    /** 等实际 Heavy Thread 退出后再做交接，避免线程尾部仍持有大对象时提前放行下一条。 */
    private val heavyWorkerReaper = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "ReviewSnapshotWorkerReaper").apply { isDaemon = true }
    }

    /** 页面加载与评论展开共享的执行预算；不包含重内存阶段的排队或执行。 */
    private const val PAGE_EXECUTION_TIMEOUT_MS = 60_000L
    /** 已取得 permit 后，资源内联、outerHTML 与解码共享的执行预算。 */
    private const val HEAVY_STAGE_WORK_TIMEOUT_MS = 60_000L
    /** 穷尽循环轮数上限 */
    private const val MAX_EXPAND_ROUNDS = 40
    /** 每轮之间的等待 */
    private const val EXPAND_ROUND_INTERVAL_MS = 800L
    /** 连续几轮稳定才判定完成（含慢加载评论） */
    private const val STABLE_ROUNDS_TO_FINISH = 3
    /** Resource budget for one complete offline snapshot. Budget excess is a visible failure. */
    private const val MAX_SNAPSHOT_RESOURCES = 200
    private const val MAX_TOTAL_RESOURCE_BYTES = 30L * 1024 * 1024
    private const val MAX_RESOURCE_BYTES = 8L * 1024 * 1024
    /** 单个资源抓取超时；传输失败会记录并移除该非关键资源，取消与预算失败仍会中止快照。 */
    private const val RESOURCE_FETCH_TIMEOUT_MS = 8_000L
    private const val HEAVY_STAGE_CONCURRENCY = 1
    private const val RESOURCE_COPY_BUFFER_BYTES = 32 * 1024
    /**
     * 内联资源构建、outerHTML 回传和 JSON 解码会短时保留完整页面及其副本；这是本进程
     * 唯一的重内存区段。页面加载/展开仍可并行，只有进入该区段才排队。
     */
    private val heavyStagePermits = Semaphore(HEAVY_STAGE_CONCURRENCY, true)

    private class ResourceBudgetExceededException(message: String) : IllegalStateException(message)

    private class CaptureStageTimeoutException(message: String) : NoStackTraceException(message)

    private class InterruptibleStringReader(private val value: String) : Reader() {
        private var position = 0

        override fun read(buffer: CharArray, offset: Int, length: Int): Int {
            if (Thread.currentThread().isInterrupted) {
                throw InterruptedIOException("评论快照 HTML 解码已取消")
            }
            if (length == 0) return 0
            if (position >= value.length) return -1
            val count = minOf(length, value.length - position)
            for (index in 0 until count) {
                buffer[offset + index] = value[position + index]
            }
            position += count
            return count
        }

        override fun close() = Unit
    }

    private enum class CaptureStage(val diagnosticsStage: String) {
        PAGE_EXECUTION("PAGE_EXECUTION"),
        HEAVY_STAGE_WAIT("HEAVY_STAGE_WAIT"),
        HEAVY_STAGE_WORK("HEAVY_STAGE_WORK"),
    }

    /**
     * 抓取结果（供诊断日志）：快照 + 展开检测轮数 + 实际点击“展开/加载更多”次数。
     */
    data class CaptureOutcome(
        val snapshot: ReviewSnapshot,
        /** 展开检测轮询轮数（包含“没点按钮”的稳定轮） */
        val expandRounds: Int,
        /** 实际点击“展开/回复/加载更多”按钮的次数（stats.clicked 累计） */
        val expandClickCount: Int,
        /** 未能入库、以 # 占位或从快照中剔除的资源数；>0 时快照为部分成功 */
        val droppedResources: Int = 0
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
        commitIfLeaseActive: ((() -> Unit) -> Boolean) = { action -> action(); true },
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
            val page = snapshotPage(
                url,
                bookSource,
                book,
                initialHtml,
                preloadJs,
                trace,
                commitIfLeaseActive,
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
                    html = page.html,
                    resourceKeys = page.resourceKeys,
                    partial = page.droppedResources > 0,
                    savedAt = System.currentTimeMillis()
                ),
                expandRounds = page.expandRounds,
                expandClickCount = page.expandClickCount,
                droppedResources = page.droppedResources
            ).also {
                trace?.done(CacheOperationDiagnostics.Metrics(outputChars = page.html.length))
            }
        } catch (error: Throwable) {
            trace?.fail(error)
            throw error
        }
    }

    /** 一次页面快照的结果：最终 HTML 与诊断数据，以及快照引用的资源库 key。 */
    private data class SnapshotPageResult(
        val html: String,
        val expandRounds: Int,
        val expandClickCount: Int,
        val resourceKeys: List<String>,
        /** 序列化时被剔除/占位的资源数；>0 表示快照为部分成功 */
        val droppedResources: Int = 0,
    )

    /**
     * 无头加载页面并穷尽展开后返回最终 HTML 与展开诊断数据。
     *
     * @return [SnapshotPageResult]：html、展开轮数、点击次数、本快照引用的资源 key 列表
     */
    private suspend fun snapshotPage(
        url: String,
        bookSource: BookSource,
        book: Book,
        initialHtml: String? = null,
        preloadJs: String? = null,
        diagnostics: CacheOperationDiagnostics.Operation? = null,
        commitIfLeaseActive: ((() -> Unit) -> Boolean),
    ): SnapshotPageResult {
        val analyzeUrl = AnalyzeUrl(url, source = bookSource)
        val headerMap = analyzeUrl.headerMap
        return suspendCancellableCoroutine { block ->
            val pooledRef = AtomicReference<io.legado.app.help.webView.PooledWebView?>()
            val sessionRef = AtomicReference<SnapshotSession?>()
            val released = java.util.concurrent.atomic.AtomicBoolean(false)
            // 唯一释放点：重复调用被 AtomicBoolean 挡住
            fun releaseOnce(discard: Boolean = false) {
                val pooled = pooledRef.getAndSet(null) ?: return
                if (released.compareAndSet(false, true)) {
                    runOnUI {
                        if (discard) WebViewPool.discard(pooled) else WebViewPool.release(pooled)
                    }
                }
            }
            block.invokeOnCancellation {
                val session = sessionRef.get()
                if (session == null) {
                    releaseOnce()
                } else {
                    session.destroy()
                }
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
                        configureOfflineResourceLoading(false)
                        headerMap[AppConst.UA_NAME]?.let { userAgentString = it }
                    }
                    AppCookieManager.applyToWebView(url)
                    val jsBridge = if (!initialHtml.isNullOrBlank()) {
                        PageJsBridge(preloadJs)
                    } else {
                        null
                    }
                    val session = SnapshotSession(
                        webView,
                        url,
                        book,
                        jsBridge,
                        diagnostics,
                        commitIfLeaseActive,
                    ) {
                        result, error, rounds, clicks, discardWebView, resourceKeys, droppedResources ->
                        sessionRef.set(null)
                        releaseOnce(discardWebView)
                        if (block.isActive) {
                            if (error != null) block.resumeWithException(error)
                            else block.resume(
                                SnapshotPageResult(
                                    result ?: "",
                                    rounds,
                                    clicks,
                                    resourceKeys,
                                    droppedResources
                                )
                            )
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
                    val session = sessionRef.get()
                    if (session == null) {
                        releaseOnce()
                        if (block.isActive) block.resumeWithException(e)
                    } else {
                        session.abort(e)
                    }
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
        private val book: Book,
        private val jsBridge: PageJsBridge?,
        private val diagnostics: CacheOperationDiagnostics.Operation?,
        private val commitIfLeaseActive: ((() -> Unit) -> Boolean),
        private val done: (String?, Throwable?, Int, Int, Boolean, List<String>, Int) -> Unit
    ) {

        private data class TerminalResult(
            val html: String?,
            val error: Throwable?,
            val discardWebView: Boolean,
            val resourceKeys: List<String> = emptyList(),
            /** 序列化阶段剔除/占位的资源数；>0 表示快照为部分成功 */
            val droppedResources: Int = 0,
        )

        /** 内联资源完成后记录的 key 列表；终态回调时随 [TerminalResult] 带出。 */
        @Volatile
        private var capturedResourceKeys: List<String> = emptyList()

        private data class CleanupHandles(
            val worker: Thread?,
            val calls: List<okhttp3.Call>,
        )

        private enum class ResourceKind {
            IMAGE,
            CSS,
            /** CSS 文本内的 url()/@import 子资源（字体、背景图等）；失败按非关键资源跳过。 */
            SUB_RESOURCE,
        }

        private data class CollectedImage(
            val url: String = "",
            val kind: String = "",
        )

        private data class CollectedResources(
            val img: List<CollectedImage> = emptyList(),
            val css: List<String> = emptyList(),
        )

        /** The immutable resource policy selected for one complete snapshot capture. */
        private data class ResourceUrls(
            val images: List<ImageResource> = emptyList(),
            val css: List<String> = emptyList(),
            val databaseImages: Map<String, ReviewSnapshotResourceEntry> = emptyMap(),
            val cacheAvatars: Boolean = false,
            val cacheCommentImages: Boolean = false,
        ) {
            val resourceCount: Int get() = images.size + css.size
        }

        private data class ImageResource(
            val url: String,
            val compressionMaxBytes: Long?,
        )

        /** A shared URL can occur in both an avatar and a comment image. */
        private data class ImageRoles(
            val url: String,
            var hasAvatar: Boolean = false,
            var hasCommentImage: Boolean = false,
        )

        private data class ResourceTarget(
            val index: Int,
            val url: String,
            val kind: ResourceKind,
            val compressionMaxBytes: Long? = null,
        )

        /** 已暂存 CSS 的文本与其解析出的子资源引用。 */
        private data class StagedCss(
            val url: String,
            val text: String,
            val refs: List<CssRefOccurrence>,
        )

        /** CSS 文本内一个 url()/@import 引用；absolute 非空才会尝试下载。 */
        private data class CssRefOccurrence(
            val valueStart: Int,
            val valueEnd: Int,
            val absolute: String?,
            val keepAsIs: Boolean,
        )

        /** CSS 子资源入库结果：绝对地址 → review-resource: 引用与入库字节总数。 */
        private data class CssSubStageResult(
            val references: Map<String, String>,
            val storedBytes: Long,
            /** 下载失败（按非关键资源跳过、引用以 # 占位）的子资源数 */
            val failedCount: Int,
        )

        private data class StagedResource(
            val target: ResourceTarget,
            val file: File,
            val byteCount: Long,
        )

        /**
         * CSS and image downloads enrich an already-captured comment page; they do not define
         * whether the page snapshot itself is usable. Keep the target in the error so a skipped
         * resource is visible in cache diagnostics instead of being silently discarded.
         */
        private class ResourceDownloadException(
            target: ResourceTarget,
            cause: Throwable? = null,
        ) : java.io.IOException(
            "评论快照非关键资源下载失败 kind=${target.kind} url=${target.url}",
            cause,
        )

        private data class PreparedImage(
            val file: File,
            val byteCount: Long,
            val mimeType: String,
        )

        /**
         * 并发下载时只允许受控大小的临时文件总量，避免多个完整响应同时占满可用资源。
         */
        private class ResourceStagingBudget {
            private var reservedBytes = 0L

            @Synchronized
            fun reserve(bytes: Long) {
                if (bytes <= 0L) return
                val next = reservedBytes + bytes
                if (next > MAX_TOTAL_RESOURCE_BYTES) {
                    throw ResourceBudgetExceededException(
                        "评论快照资源 $next B 超过总预算 $MAX_TOTAL_RESOURCE_BYTES B",
                    )
                }
                reservedBytes = next
            }

            @Synchronized
            fun release(bytes: Long) {
                if (bytes > 0L) {
                    reservedBytes = (reservedBytes - bytes).coerceAtLeast(0L)
                }
            }
        }

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
        private val lifecycleLock = Any()
        private val queueWaitThread = AtomicReference<Thread?>()
        private var terminalResult: TerminalResult? = null
        private var terminalFinalized = false
        private var activeHeavyWorker: Thread? = null
        private val activeResourceCalls = linkedSetOf<okhttp3.Call>()
        @Volatile
        private var activeStage: CaptureStage? = null
        @Volatile
        private var stageTimeout: ScheduledFuture<*>? = null

        init {
            startTimedStage(
                CaptureStage.PAGE_EXECUTION,
                PAGE_EXECUTION_TIMEOUT_MS,
                "评论页加载/展开",
            )
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
            val cleanup = acceptTerminal(
                TerminalResult(
                    html = null,
                    error = CancellationException("评论快照 Capture 已取消"),
                    discardWebView = true,
                )
            ) ?: return
            activeStage = null
            clearStageTimeout()
            cancelOutstandingWork(cleanup)
        }

        fun abort(error: Throwable) {
            fail(error, discardWebView = true)
        }

        private fun finish(html: String?, droppedResources: Int = 0) {
            val cleanup = acceptTerminal(
                TerminalResult(
                    html = html,
                    error = null,
                    discardWebView = false,
                    resourceKeys = capturedResourceKeys,
                    droppedResources = droppedResources,
                )
            ) ?: return
            completeActiveStage(CacheOperationDiagnostics.Metrics(outputChars = html?.length))
            cancelOutstandingWork(cleanup)
        }

        private fun fail(
            error: Throwable,
            discardWebView: Boolean = error is CaptureStageTimeoutException,
        ) {
            val cleanup = acceptTerminal(
                TerminalResult(
                    html = null,
                    error = error,
                    discardWebView = discardWebView,
                )
            ) ?: return
            failActiveStage(error)
            cancelOutstandingWork(cleanup)
        }

        private fun failStageTimeout(stage: CaptureStage, error: CaptureStageTimeoutException) {
            val cleanup = synchronized(lifecycleLock) {
                if (terminalResult != null || activeStage != stage) return
                terminalResult = TerminalResult(
                    html = null,
                    error = error,
                    discardWebView = true,
                )
                destroyed = true
                activeStage = null
                CleanupHandles(activeHeavyWorker, activeResourceCalls.toList())
            }
            clearStageTimeout()
            diagnostics?.stageFail(stage.diagnosticsStage, error)
            cancelOutstandingWork(cleanup)
        }

        private fun acceptTerminal(result: TerminalResult): CleanupHandles? {
            return synchronized(lifecycleLock) {
                if (terminalResult != null) return@synchronized null
                terminalResult = result
                destroyed = true
                CleanupHandles(activeHeavyWorker, activeResourceCalls.toList())
            }
        }

        private fun cancelOutstandingWork(cleanup: CleanupHandles) {
            clearStageTimeout()
            queueWaitThread.get()?.interrupt()
            cleanup.calls.forEach { it.cancel() }
            cleanup.worker?.interrupt()
            tryFinalizeTerminal()
        }

        /**
         * 只有 Java 侧 Heavy worker 已真实退出后才释放 permit。这样前一条流水线超时后，
         * 它残留的下载、资源处理或 decode 不会与下一条 Heavy 流水线重叠。
         */
        private fun tryFinalizeTerminal() {
            val terminal = synchronized(lifecycleLock) {
                val result = terminalResult
                if (result == null || terminalFinalized || activeHeavyWorker != null) {
                    return@synchronized null
                }
                terminalFinalized = true
                result
            } ?: return
            runOnUI {
                done(
                    terminal.html,
                    terminal.error,
                    expandRounds,
                    totalExpandClicks,
                    terminal.discardWebView,
                    terminal.resourceKeys,
                    terminal.droppedResources,
                )
                releaseHeavyStagePermit()
            }
        }

        /**
         * 只有页面执行与 permit 后的重内存处理拥有执行超时。permit 排队没有失败时限，
         * 只记录自己的等待耗时；这样队列中的时间永远不会消耗 heavy work 的执行预算。
         */
        private fun startTimedStage(stage: CaptureStage, timeoutMs: Long, timeoutLabel: String) {
            completeActiveStage()
            synchronized(lifecycleLock) {
                if (destroyed) return
                activeStage = stage
            }
            diagnostics?.stageStart(stage.diagnosticsStage, startAlways = true)
            stageTimeout = timeoutScheduler.schedule({
                failStageTimeout(
                    stage,
                    CaptureStageTimeoutException(
                        "${timeoutLabel}执行超时 ${timeoutMs / 1000}s",
                    )
                )
            }, timeoutMs, TimeUnit.MILLISECONDS)
        }

        private fun startQueueWaitStage() {
            completeActiveStage()
            synchronized(lifecycleLock) {
                if (destroyed) return
                activeStage = CaptureStage.HEAVY_STAGE_WAIT
            }
            diagnostics?.stageStart(CaptureStage.HEAVY_STAGE_WAIT.diagnosticsStage, startAlways = true)
        }

        private fun completeActiveStage(metrics: CacheOperationDiagnostics.Metrics = CacheOperationDiagnostics.Metrics()) {
            val stage = synchronized(lifecycleLock) {
                val current = activeStage ?: return
                activeStage = null
                current
            }
            clearStageTimeout()
            diagnostics?.stageDone(stage.diagnosticsStage, metrics)
        }

        private fun failActiveStage(error: Throwable) {
            val stage = synchronized(lifecycleLock) {
                val current = activeStage ?: return
                activeStage = null
                current
            }
            clearStageTimeout()
            diagnostics?.stageFail(stage.diagnosticsStage, error)
        }

        private fun clearStageTimeout() {
            stageTimeout?.cancel(false)
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
            val waiter = Thread {
                try {
                    heavyStagePermits.acquire()
                    if (destroyed) {
                        heavyStagePermits.release()
                    } else {
                        heavyStagePermitHeld.set(true)
                        mHandler.post {
                            if (destroyed) {
                                releaseHeavyStagePermit()
                            } else {
                                diagnostics?.mark("HEAVY_STAGE_ACQUIRED")
                                startTimedStage(
                                    CaptureStage.HEAVY_STAGE_WORK,
                                    HEAVY_STAGE_WORK_TIMEOUT_MS,
                                    "评论快照重内存处理",
                                )
                                collectAndInlineResources()
                            }
                        }
                    }
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    if (!destroyed) mHandler.post { fail(e) }
                } finally {
                    queueWaitThread.compareAndSet(Thread.currentThread(), null)
                }
            }
            queueWaitThread.set(waiter)
            if (destroyed) waiter.interrupt()
            waiter.start()
        }

        private fun collectAndInlineResources() {
            if (destroyed) return
            webView.evaluateJavascript(COLLECT_RESOURCES_JS) { json ->
                mHandler.post {
                    if (destroyed) return@post
                    val urls = parseResourceUrls(json)
                    diagnostics?.mark(
                        "RESOURCE_LIST_READY",
                        CacheOperationDiagnostics.Metrics(resourceCount = urls.resourceCount),
                    )
                    diagnostics?.stageStart(
                        "RESOURCE_DOWNLOAD",
                        CacheOperationDiagnostics.Metrics(resourceCount = urls.resourceCount),
                        startAlways = true,
                    )
                    launchHeavyWorker(
                        name = "ReviewSnapshotResources",
                        work = { downloadResources(urls) },
                        onSuccess = { inline ->
                            diagnostics?.stageDone(
                                "RESOURCE_DOWNLOAD",
                                CacheOperationDiagnostics.Metrics(
                                    resourceCount = inline.resourceCount,
                                    outputBytes = inline.resourceBytes,
                                ),
                            )
                            diagnostics?.mark(
                                "RESOURCE_INLINE_READY",
                                CacheOperationDiagnostics.Metrics(
                                    resourceCount = inline.resourceCount,
                                    outputBytes = inline.resourceBytes,
                                ),
                            )
                            applyInline(inline)
                        },
                        onFailure = { error ->
                            diagnostics?.stageFail("RESOURCE_DOWNLOAD", error)
                            diagnostics?.warn("RESOURCE_INLINE_FAILED", error)
                            fail(error)
                        },
                    )
                }
            }
        }

        private fun <T> launchHeavyWorker(
            name: String,
            work: () -> T,
            onSuccess: (T) -> Unit,
            onFailure: (Throwable) -> Unit,
        ) {
            lateinit var worker: Thread
            worker = Thread({
                val result = runCatching {
                    ensureHeavyActive()
                    work()
                }
                heavyWorkerReaper.execute {
                    worker.join()
                    synchronized(lifecycleLock) {
                        if (activeHeavyWorker === worker) activeHeavyWorker = null
                    }
                    if (destroyed) {
                        tryFinalizeTerminal()
                    } else {
                        mHandler.post {
                            if (!destroyed) result.fold(onSuccess, onFailure)
                        }
                    }
                }
            }, name)
            synchronized(lifecycleLock) {
                if (destroyed) return
                check(activeHeavyWorker == null) { "评论快照 Heavy worker 重叠" }
                activeHeavyWorker = worker
            }
            try {
                worker.start()
            } catch (error: Throwable) {
                synchronized(lifecycleLock) {
                    if (activeHeavyWorker === worker) activeHeavyWorker = null
                }
                if (destroyed) tryFinalizeTerminal() else onFailure(error)
            }
        }

        private fun ensureHeavyActive() {
            if (destroyed || Thread.currentThread().isInterrupted) {
                throw InterruptedIOException("评论快照 Heavy 工作已取消")
            }
        }

        private fun registerResourceCall(call: okhttp3.Call) {
            synchronized(lifecycleLock) {
                if (destroyed) {
                    call.cancel()
                    throw InterruptedIOException("评论快照资源请求已取消")
                }
                activeResourceCalls += call
            }
        }

        private fun unregisterResourceCall(call: okhttp3.Call) {
            synchronized(lifecycleLock) {
                activeResourceCalls -= call
            }
        }

        private fun cancelActiveResourceCalls() {
            val calls = synchronized(lifecycleLock) { activeResourceCalls.toList() }
            calls.forEach { it.cancel() }
        }

        private fun releaseHeavyStagePermit() {
            if (heavyStagePermitHeld.compareAndSet(true, false)) {
                heavyStagePermits.release()
            }
        }

        private data class InlineResources(
            val imgMap: Map<String, String> = emptyMap(),
            val cssMap: Map<String, String> = emptyMap(),
            /** CSS 子资源（字体、背景图等）入库后的资源库 key，必须进快照 resourceKeys。 */
            val subResourceKeys: List<String> = emptyList(),
            /** 下载阶段未能入库的资源数（下载失败或内容为空）；>0 表示快照为部分成功。 */
            val droppedResources: Int = 0,
            val resourceBytes: Long = 0L,
            val cacheAvatars: Boolean = false,
            val cacheCommentImages: Boolean = false,
            /** A resource target existed, so any unstaged external URL must be removed. */
            val removeUnstagedExternalResources: Boolean = false,
        ) {
            val resourceCount: Int get() = imgMap.size + cssMap.size

            /** 本快照 HTML 引用的全部资源库 key（imgMap 的 value 即 review-resource://<key>）。 */
            val resourceKeys: List<String>
                get() = (
                    imgMap.values.mapNotNull { value ->
                        ReviewSnapshotResourceStore.keyFromReference(value)
                    } + subResourceKeys
                    ).distinct()
        }

        private fun parseResourceUrls(json: String?): ResourceUrls {
            val cacheAvatars = AppConfig.cacheReviewAvatars
            val cacheCommentImages = AppConfig.cacheReviewImages
            val compressAvatars = cacheAvatars && AppConfig.compressReviewAvatars
            val compressCommentImages = cacheCommentImages && AppConfig.compressReviewImages
            val avatarCompressionMaxBytes = AppConfig.reviewAvatarCompressionMaxBytes
            val imageCompressionMaxBytes = AppConfig.reviewImageCompressionMaxBytes
            if (compressAvatars) {
                require(avatarCompressionMaxBytes > 0L) {
                    "评论头像压缩上限必须大于 0"
                }
            }
            if (compressCommentImages) {
                require(imageCompressionMaxBytes > 0L) {
                    "评论图片压缩上限必须大于 0"
                }
            }
            json ?: return ResourceUrls(
                cacheAvatars = cacheAvatars,
                cacheCommentImages = cacheCommentImages,
            )
            val s = StringEscapeUtils.unescapeJson(json).trim('"')
            if (s == "null") {
                return ResourceUrls(
                    cacheAvatars = cacheAvatars,
                    cacheCommentImages = cacheCommentImages,
                )
            }
            val collected = GSON.fromJson(s, CollectedResources::class.java)
                ?: error("评论快照资源列表为空")
            val imageRoles = linkedMapOf<String, ImageRoles>()
            collected.img.forEach { image ->
                val imageUrl = image.url.takeIf { it.startsWith("http") } ?: return@forEach
                val roles = imageRoles.getOrPut(imageUrl) { ImageRoles(imageUrl) }
                if (image.kind == "avatar") {
                    roles.hasAvatar = true
                } else {
                    roles.hasCommentImage = true
                }
            }
            val selectedImages = imageRoles.values.asSequence()
                .filter { roles ->
                    (roles.hasAvatar && cacheAvatars) ||
                        (roles.hasCommentImage && cacheCommentImages)
                }
                .map { roles ->
                    val maximums = buildList {
                        if (roles.hasAvatar && compressAvatars) add(avatarCompressionMaxBytes)
                        if (roles.hasCommentImage && compressCommentImages) {
                            add(imageCompressionMaxBytes)
                        }
                    }
                    ImageResource(roles.url, maximums.minOrNull())
                }
                .toList()
            val storedImages = ReviewSnapshotResourceStore.entries(book)
            val reusableImages = linkedMapOf<String, ReviewSnapshotResourceEntry>()
            val imagesToDownload = selectedImages.filter { image ->
                val stored = storedImages[image.url]
                if (stored != null &&
                    (image.compressionMaxBytes == null ||
                        stored.byteCount <= image.compressionMaxBytes)
                ) {
                    reusableImages[image.url] = stored
                    false
                } else {
                    true
                }
            }
            return ResourceUrls(
                images = imagesToDownload,
                css = collected.css.filter { it.startsWith("http") }.distinct(),
                databaseImages = reusableImages,
                cacheAvatars = cacheAvatars,
                cacheCommentImages = cacheCommentImages,
            )
        }

        private fun downloadResources(urls: ResourceUrls): InlineResources {
            val resourceCount = urls.resourceCount
            if (resourceCount > MAX_SNAPSHOT_RESOURCES) {
                throw ResourceBudgetExceededException(
                    "评论快照资源数 $resourceCount 超过预算 $MAX_SNAPSHOT_RESOURCES",
                )
            }
            if (resourceCount == 0) {
                return InlineResources(
                    imgMap = urls.databaseImages.mapValues { (_, entry) ->
                        ReviewSnapshotResourceStore.referenceFor(entry.key)
                    },
                    resourceBytes = urls.databaseImages.values.sumOf { it.byteCount },
                    cacheAvatars = urls.cacheAvatars,
                    cacheCommentImages = urls.cacheCommentImages,
                )
            }
            val targets = ArrayList<ResourceTarget>(resourceCount)
            urls.images.forEach { image ->
                targets += ResourceTarget(
                    index = targets.size,
                    url = image.url,
                    kind = ResourceKind.IMAGE,
                    compressionMaxBytes = image.compressionMaxBytes,
                )
            }
            urls.css.forEach { targets += ResourceTarget(targets.size, it, ResourceKind.CSS) }
            val stagingDir = File(
                appCtx.cacheDir,
                "review_snapshot_${System.nanoTime()}_${Thread.currentThread().id}",
            )
            check(stagingDir.mkdirs() || stagingDir.isDirectory) {
                "无法创建评论快照资源暂存目录"
            }
            val budget = ResourceStagingBudget()
            try {
                val stagedResources = stageResources(targets, stagingDir, budget)
                val imgMap = linkedMapOf<String, String>()
                val cssMap = linkedMapOf<String, String>()
                var embeddedTextBytes = 0L
                var resourceBytes = 0L
                urls.databaseImages.forEach { (imageUrl, entry) ->
                    imgMap[imageUrl] = ReviewSnapshotResourceStore.referenceFor(entry.key)
                    resourceBytes += entry.byteCount
                }
                val stagedCss = mutableListOf<StagedCss>()
                for (staged in stagedResources) {
                    ensureHeavyActive()
                    when (staged.target.kind) {
                        ResourceKind.IMAGE -> {
                            val image = prepareImage(staged)
                            resourceBytes += image.byteCount
                            val committed =
                                putResource(staged.target.url, image.mimeType, image.file)
                            imgMap[staged.target.url] =
                                ReviewSnapshotResourceStore.referenceFor(committed.key)
                        }

                        ResourceKind.CSS -> {
                            val bytes = staged.file.readBytes()
                            check(bytes.size.toLong() == staged.byteCount) {
                                "评论快照资源暂存文件长度异常: ${staged.target.url}"
                            }
                            val text = bytes.toString(Charsets.UTF_8).takeIf { it.isNotBlank() }
                                ?: continue
                            stagedCss += parseCssSubResources(staged.target.url, text)
                        }

                        ResourceKind.SUB_RESOURCE -> Unit
                    }
                }
                // CSS 文本内的 url()/@import 子资源（字体、背景图、@import 样式等）此前
                // 既不收集也不改写，却会被序列化完整性检查判死（凡样式带字体的评论页
                // 必失败）。统一入库并把引用改写为 review-resource:；下载失败的子资源
                // 按非关键资源策略改写为 #，stageResources 已留 RESOURCE_DOWNLOAD_SKIPPED
                // 诊断，不静默丢弃。
                val subStage = stageCssSubResources(stagedCss, stagingDir, budget)
                val subReferences = subStage.references
                resourceBytes += subStage.storedBytes
                val subResourceKeys = subReferences.values.mapNotNull { reference ->
                    ReviewSnapshotResourceStore.keyFromReference(reference)
                }
                for (css in stagedCss) {
                    val rewritten = rewriteCssSubResources(css, subReferences)
                    val textBytes = rewritten.toByteArray(Charsets.UTF_8).size.toLong()
                    val nextTotal = embeddedTextBytes + textBytes
                    if (nextTotal > MAX_TOTAL_RESOURCE_BYTES) {
                        throw ResourceBudgetExceededException(
                            "评论快照样式 $nextTotal B 超过总预算 $MAX_TOTAL_RESOURCE_BYTES B",
                        )
                    }
                    embeddedTextBytes = nextTotal
                    resourceBytes += textBytes
                    cssMap[css.url] = rewritten
                }
                return InlineResources(
                    imgMap = imgMap,
                    cssMap = cssMap,
                    resourceBytes = resourceBytes,
                    cacheAvatars = urls.cacheAvatars,
                    cacheCommentImages = urls.cacheCommentImages,
                    subResourceKeys = subResourceKeys,
                    droppedResources = urls.images.count { imgMap[it.url] == null } +
                        urls.css.count { cssMap[it] == null } +
                        subStage.failedCount,
                    removeUnstagedExternalResources = true,
                )
            } finally {
                stagingDir.deleteRecursively()
            }
        }

        /**
         * 把所有 CSS 文本引用的子资源批量下载入库（跨 CSS 按绝对地址去重），
         * 返回 绝对地址 → review-resource: 引用与入库字节总数；下载失败的地址不在
         * 引用表中，由 [rewriteCssSubResources] 改写为 #。
         */
        private fun stageCssSubResources(
            stagedCss: List<StagedCss>,
            stagingDir: File,
            budget: ResourceStagingBudget,
        ): CssSubStageResult {
            val subUrls = stagedCss.asSequence()
                .flatMap { css -> css.refs.asSequence() }
                .mapNotNull { it.absolute }
                .distinct()
                .toList()
            if (subUrls.isEmpty()) return CssSubStageResult(emptyMap(), 0L, 0)
            if (subUrls.size > MAX_SNAPSHOT_RESOURCES) {
                throw ResourceBudgetExceededException(
                    "评论快照 CSS 子资源数 ${subUrls.size} 超过预算 $MAX_SNAPSHOT_RESOURCES",
                )
            }
            val subTargets = subUrls.mapIndexed { index, url ->
                ResourceTarget(index, url, ResourceKind.SUB_RESOURCE)
            }
            val stagedSubs = stageResources(subTargets, stagingDir, budget)
            val subReferences = linkedMapOf<String, String>()
            var storedBytes = 0L
            for (staged in stagedSubs) {
                ensureHeavyActive()
                val committed = putResource(
                    staged.target.url,
                    cssSubResourceMime(staged.target.url),
                    staged.file,
                )
                subReferences[staged.target.url] =
                    ReviewSnapshotResourceStore.referenceFor(committed.key)
                storedBytes += staged.byteCount
            }
            return CssSubStageResult(
                subReferences,
                storedBytes,
                failedCount = subUrls.size - stagedSubs.size,
            )
        }

        /** 按解析结果改写 CSS 文本：入库引用 → review-resource:，失败引用 → #，本地形式保留。 */
        private fun rewriteCssSubResources(
            css: StagedCss,
            subReferences: Map<String, String>,
        ): String {
            if (css.refs.isEmpty()) return css.text
            val builder = StringBuilder(css.text)
            for (ref in css.refs.sortedByDescending { it.valueStart }) {
                val replacement = when {
                    ref.keepAsIs -> null
                    ref.absolute != null -> subReferences[ref.absolute] ?: "#"
                    else -> "#"
                } ?: continue
                builder.replace(ref.valueStart, ref.valueEnd, replacement)
            }
            return builder.toString()
        }

        /** 解析 CSS 文本内的 url() 与 @import 字符串引用，文法与序列化完整性检查一致。 */
        private fun parseCssSubResources(cssUrl: String, text: String): StagedCss {
            val base = cssUrl.toHttpUrlOrNull()
            val refs = mutableListOf<CssRefOccurrence>()
            CSS_URL_REF_REGEX.findAll(text).forEach { match ->
                val value = match.groupValues[2]
                if (value.isNotBlank()) {
                    val g = match.groups[2]!!
                    refs += cssRefOccurrence(base, value, g.range.first, g.range.last + 1)
                }
            }
            CSS_IMPORT_REF_REGEX.findAll(text).forEach { match ->
                val value = match.groupValues[1]
                if (value.isNotBlank()) {
                    val g = match.groups[1]!!
                    refs += cssRefOccurrence(base, value, g.range.first, g.range.last + 1)
                }
            }
            return StagedCss(cssUrl, text, refs)
        }

        private fun cssRefOccurrence(
            base: okhttp3.HttpUrl?,
            value: String,
            valueStart: Int,
            valueEnd: Int,
        ): CssRefOccurrence {
            if (CSS_LOCAL_REF_PREFIX_REGEX.containsMatchIn(value)) {
                return CssRefOccurrence(valueStart, valueEnd, absolute = null, keepAsIs = true)
            }
            val absolute = base?.resolve(value)?.toString()
            return CssRefOccurrence(valueStart, valueEnd, absolute, keepAsIs = false)
        }

        /** 单个资源入库统一走 lease 提交：lease 失效即取消整个 Capture，不落半套资源。 */
        private fun putResource(
            url: String,
            mimeType: String,
            file: File,
        ): ReviewSnapshotResourceEntry {
            var stored: ReviewSnapshotResourceEntry? = null
            val committedLease = commitIfLeaseActive.invoke {
                stored = ReviewSnapshotResourceStore.put(
                    book = book,
                    url = url,
                    mimeType = mimeType,
                    source = file,
                )
            }
            if (!committedLease) {
                throw CancellationException("review lease is no longer active at resource commit")
            }
            return checkNotNull(stored)
        }

        /**
         * 资源线程数只控制网络拉取。响应通过固定小缓冲区写入临时文件，随后才逐项转为
         * 资源库只保留文件引用，因此提高下载并发不会重新引入多份完整响应 byte[] 同驻 Java heap。
         */
        private fun stageResources(
            targets: List<ResourceTarget>,
            stagingDir: File,
            budget: ResourceStagingBudget,
        ): List<StagedResource> {
            val threadCount = AppConfig.reviewResourceDownloadConcurrency.coerceIn(1, 32)
            val executor = Executors.newFixedThreadPool(threadCount) { runnable ->
                Thread(runnable, "ReviewSnapshotResource").apply { isDaemon = true }
            }
            val futures = targets.map { target ->
                target to executor.submit(Callable { stageResource(target, stagingDir, budget) })
            }
            try {
                return futures.mapNotNull { (target, future) ->
                    try {
                        future.get()
                    } catch (error: java.util.concurrent.ExecutionException) {
                        val cause = error.cause ?: error
                        // 资源下载失败一律按非关键资源跳过（CSS 也一样）：快照以部分
                        // 成功落盘，剔除/占位数量计入 partial，等待重试补全。
                        if (cause is ResourceDownloadException) {
                            diagnostics?.warn("RESOURCE_DOWNLOAD_SKIPPED", cause)
                            null
                        } else {
                            throw cause
                        }
                    } catch (error: InterruptedException) {
                        Thread.currentThread().interrupt()
                        throw InterruptedIOException("评论快照资源下载已取消").also {
                            it.initCause(error)
                        }
                    }
                }
            } catch (error: Throwable) {
                futures.forEach { (_, future) -> future.cancel(true) }
                cancelActiveResourceCalls()
                throw error
            } finally {
                shutdownResourceWorkers(executor)
            }
        }

        private fun shutdownResourceWorkers(executor: ExecutorService) {
            executor.shutdownNow()
            var restoreInterrupt = Thread.interrupted()
            while (!executor.isTerminated) {
                try {
                    executor.awaitTermination(100L, TimeUnit.MILLISECONDS)
                } catch (_: InterruptedException) {
                    restoreInterrupt = true
                    cancelActiveResourceCalls()
                }
            }
            if (restoreInterrupt) Thread.currentThread().interrupt()
        }

        /** 单个资源以流式方式暂存；单请求仍保持 8s 网络超时。 */
        private fun stageResource(
            target: ResourceTarget,
            stagingDir: File,
            budget: ResourceStagingBudget,
        ): StagedResource {
            val targetFile = File(stagingDir, target.index.toString())
            val request = okhttp3.Request.Builder()
                .url(target.url)
                .header("Referer", url)
                .build()
            val call = okHttpClient.newBuilder()
                .connectTimeout(RESOURCE_FETCH_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .readTimeout(RESOURCE_FETCH_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .build()
                .newCall(request)
            registerResourceCall(call)
            var reservedBytes = 0L
            var completed = false
            try {
                ensureHeavyActive()
                try {
                    call.execute().use { response ->
                        if (!response.isSuccessful) {
                            throw ResourceDownloadException(
                                target,
                                IllegalStateException("HTTP ${response.code}"),
                            )
                        }
                        val body = response.body ?: throw ResourceDownloadException(
                            target,
                            IllegalStateException("评论快照资源响应为空"),
                        )
                        body.byteStream().use { input ->
                            targetFile.outputStream().buffered().use { output ->
                                val buffer = ByteArray(RESOURCE_COPY_BUFFER_BYTES)
                                var copiedBytes = 0L
                                while (true) {
                                    ensureHeavyActive()
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    val nextBytes = copiedBytes + count
                                    if (nextBytes > MAX_RESOURCE_BYTES) {
                                        throw ResourceBudgetExceededException(
                                            "评论快照资源 $nextBytes B 超过单资源预算 " +
                                                "$MAX_RESOURCE_BYTES B: ${target.url}",
                                        )
                                    }
                                    val nextReserved = estimatedStagingBytes(nextBytes)
                                    budget.reserve(nextReserved - reservedBytes)
                                    reservedBytes = nextReserved
                                    output.write(buffer, 0, count)
                                    copiedBytes = nextBytes
                                }
                                completed = true
                                return StagedResource(target, targetFile, copiedBytes)
                            }
                        }
                    }
                } catch (error: ResourceDownloadException) {
                    throw error
                } catch (error: java.io.IOException) {
                    if (destroyed || Thread.currentThread().isInterrupted) throw error
                    throw ResourceDownloadException(target, error)
                }
            } finally {
                unregisterResourceCall(call)
                if (!completed) {
                    budget.release(reservedBytes)
                    targetFile.delete()
                }
            }
        }

        private fun estimatedStagingBytes(rawBytes: Long): Long = rawBytes

        private fun prepareImage(staged: StagedResource): PreparedImage {
            check(staged.file.length() == staged.byteCount) {
                "评论快照图片暂存文件长度异常: ${staged.target.url}"
            }
            val maxBytes = staged.target.compressionMaxBytes
            if (maxBytes == null || staged.byteCount <= maxBytes) {
                return PreparedImage(
                    file = staged.file,
                    byteCount = staged.byteCount,
                    mimeType = guessMime(staged.target.url, staged.file),
                )
            }
            require(maxBytes > 0L) { "评论图片压缩上限必须大于 0" }
            return compressImage(staged.file, maxBytes, staged.target.url)
        }

        /**
         * Re-encodes one staged image directly to a bounded WebP file. No full byte[] or
         * encoded String is created here; sampling occurs before decode and every retry replaces
         * the same temporary file.
         */
        @Suppress("DEPRECATION")
        private fun compressImage(source: File, maxBytes: Long, sourceUrl: String): PreparedImage {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(source.absolutePath, bounds)
            check(bounds.outWidth > 0 && bounds.outHeight > 0) {
                "评论图片无法解码，不能压缩: $sourceUrl"
            }
            val targetPixels = (maxBytes * 4L).coerceAtMost(Int.MAX_VALUE.toLong())
            var sampleSize = 1
            while (
                bounds.outWidth.toLong() / sampleSize *
                (bounds.outHeight.toLong() / sampleSize) > targetPixels
            ) {
                sampleSize *= 2
            }
            var bitmap = BitmapFactory.decodeFile(
                source.absolutePath,
                BitmapFactory.Options().apply {
                    inSampleSize = sampleSize
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
            ) ?: error("评论图片无法解码，不能压缩: $sourceUrl")
            val output = File(source.parentFile, "${source.name}.webp")
            var completed = false
            try {
                while (true) {
                    for (quality in intArrayOf(92, 80, 65, 50, 35, 20, 10)) {
                        ensureHeavyActive()
                        FileOutputStream(output, false).buffered().use { stream ->
                            check(bitmap.compress(Bitmap.CompressFormat.WEBP, quality, stream)) {
                                "评论图片 WebP 压缩失败: $sourceUrl"
                            }
                        }
                        if (output.length() <= maxBytes) {
                            completed = true
                            return PreparedImage(output, output.length(), "image/webp")
                        }
                    }
                    check(bitmap.width > 1 || bitmap.height > 1) {
                        "评论图片无法压缩到 ${maxBytes}B: $sourceUrl"
                    }
                    val scaled = Bitmap.createScaledBitmap(
                        bitmap,
                        (bitmap.width / 2).coerceAtLeast(1),
                        (bitmap.height / 2).coerceAtLeast(1),
                        true,
                    )
                    bitmap.recycle()
                    bitmap = scaled
                }
            } finally {
                bitmap.recycle()
                if (!completed) output.delete()
            }
        }

        private fun guessMime(rawUrl: String, file: File): String {
            val header = ByteArray(16)
            val headerLength = file.inputStream().use { input -> input.read(header) }
            val lower = rawUrl.substringBefore('?').lowercase()
            return when {
                lower.endsWith(".png") -> "image/png"
                lower.endsWith(".webp") -> "image/webp"
                lower.endsWith(".gif") -> "image/gif"
                lower.endsWith(".svg") -> "image/svg+xml"
                lower.endsWith(".css") -> "text/css"
                headerLength > 3 && header[0] == 0xFF.toByte() &&
                    header[1] == 0xD8.toByte() -> "image/jpeg"
                else -> "image/jpeg"
            }
        }

        /** CSS 子资源入库 MIME：按扩展名判定，浏览器以此决定字体/图片如何消费。 */
        private fun cssSubResourceMime(url: String): String {
            val lower = url.substringBefore('?').substringBefore('#').lowercase()
            return when {
                lower.endsWith(".woff2") -> "font/woff2"
                lower.endsWith(".woff") -> "font/woff"
                lower.endsWith(".ttf") -> "font/ttf"
                lower.endsWith(".otf") -> "font/otf"
                lower.endsWith(".eot") -> "application/vnd.ms-fontobject"
                lower.endsWith(".svg") -> "image/svg+xml"
                lower.endsWith(".css") -> "text/css"
                lower.endsWith(".png") -> "image/png"
                lower.endsWith(".webp") -> "image/webp"
                lower.endsWith(".gif") -> "image/gif"
                lower.endsWith(".jpg") || lower.endsWith(".jpeg") -> "image/jpeg"
                else -> "application/octet-stream"
            }
        }

        private fun applyInline(inline: InlineResources) {
            if (destroyed) return
            // 内联完成后立即记录本快照引用的资源 key；终态回调据此携带 resourceKeys。
            capturedResourceKeys = inline.resourceKeys
            if (inline.imgMap.isEmpty() && inline.cssMap.isEmpty() &&
                !inline.removeUnstagedExternalResources
            ) {
                serialize(inline)
                return
            }
            // Gson 与大 String 的构建不能占用主线程；它仍属于当前 Heavy permit。
            launchHeavyWorker(
                name = "ReviewSnapshotInlineJs",
                work = {
                    ensureHeavyActive()
                    buildApplyJs(inline).also { ensureHeavyActive() }
                },
                onSuccess = { script ->
                    webView.evaluateJavascript(script) {
                        if (!destroyed) serialize(inline)
                    }
                },
                onFailure = { error -> fail(error) },
            )
        }

        private fun serialize(inline: InlineResources) {
            if (destroyed) return
            // 在冻结 DOM 内完成引用清理、完整性收口和 outerHTML，避免活页面在资源
            // 下载期间继续改变。JSON 解码移出主线程，不能在回调中阻塞 UI。
            diagnostics?.stageStart("SANITIZE")
            webView.evaluateJavascript(buildSerializeSnapshotJs(inline)) { raw ->
                diagnostics?.mark(
                    "WEBVIEW_HTML_READY",
                    CacheOperationDiagnostics.Metrics(inputChars = raw?.length),
                )
                launchHeavyWorker(
                    name = "ReviewSnapshotDecode",
                    work = { decodeSerializedSnapshot(raw) },
                    onSuccess = { outcome ->
                        val failureMessage = when {
                            outcome == null -> "评论页快照序列化为空 $url"
                            outcome.rootMissing -> "评论页快照冻结 DOM 丢失 $url"
                            outcome.html.isBlank() -> "评论页快照序列化为空 $url"
                            else -> null
                        }
                        if (failureMessage != null) {
                            val error = NoStackTraceException(failureMessage)
                            diagnostics?.stageFail("SANITIZE", error)
                            fail(error)
                        } else {
                            val completeHtml = outcome!!.html
                            // 部分成功：页面 HTML 已抓到，下载失败的资源只能剔除或以 #
                            // 占位；快照照常落盘渲染，按钮仍按失败计，等待重试补全。
                            val dropped = outcome!!.dropped + inline.droppedResources
                            if (dropped > 0) {
                                diagnostics?.mark(
                                    "SANITIZE_PARTIAL",
                                    CacheOperationDiagnostics.Metrics(resourceCount = dropped),
                                )
                            }
                            diagnostics?.stageDone(
                                "SANITIZE",
                                CacheOperationDiagnostics.Metrics(outputChars = completeHtml.length),
                            )
                            finish(completeHtml, dropped)
                        }
                    },
                    onFailure = { error ->
                        diagnostics?.stageFail("SANITIZE", error)
                        fail(error)
                    },
                )
            }
        }

        private data class SerializedSnapshot(
            val html: String,
            val dropped: Int,
            val rootMissing: Boolean,
        )

        private data class SerializedSnapshotJs(
            val h: String? = null,
            val d: Int = 0,
        )

        /**
         * 解码序列化 JS 的返回值：JSON {h: outerHTML, d: 剔除资源数}，或冻结 DOM
         * 丢失哨兵。返回 null 表示序列化为空。
         */
        private fun decodeSerializedSnapshot(raw: String?): SerializedSnapshot? {
            val value = decodeJavascriptString(raw) ?: return null
            if (value == SNAPSHOT_ROOT_MISSING) {
                return SerializedSnapshot(html = "", dropped = 0, rootMissing = true)
            }
            val parsed = GSON.fromJson(value, SerializedSnapshotJs::class.java) ?: return null
            return SerializedSnapshot(
                html = parsed.h.orEmpty(),
                dropped = parsed.d.coerceAtLeast(0),
                rootMissing = false,
            )
        }

        private fun decodeJavascriptString(raw: String?): String? {
            if (raw == null || raw == "null") return null
            // evaluateJavascript 的回调是一个 JSON string。JsonReader 直接解码这一层，
            // 避免 unescapeJson(...).trim(...) 产生两份完整 HTML 的中间字符串；Reader
            // 每次供数前检查 interrupt，使 Heavy 超时能够真实终止巨大 HTML 的解码。
            return JsonReader(InterruptibleStringReader(raw)).use { reader -> reader.nextString() }
        }

        private fun buildApplyJs(inline: InlineResources): String {
            val imgJson = GSON.toJson(inline.imgMap)
            val cssJson = GSON.toJson(inline.cssMap)
            return "(function(){" +
                "var root=window.__legadoReviewSnapshotRoot;" +
                "if(!root)throw new Error('评论快照冻结 DOM 丢失');" +
                "var m=$imgJson;" +
                "var c=$cssJson;" +
                "var cacheAvatars=${inline.cacheAvatars};" +
                "var cacheCommentImages=${inline.cacheCommentImages};" +
                "var removeUnstaged=${inline.removeUnstagedExternalResources};" +
                IMAGE_CLASSIFIER_HELPER_JS +
                "root.querySelectorAll('img').forEach(function(el){" +
                "if(!(isAvatarImage(el)?cacheAvatars:cacheCommentImages))return;" +
                "var source=el.getAttribute('src');" +
                "if(source&&/^https?:\\/\\//i.test(el.src)){var d=m[el.src]||m[source];" +
                "if(d)el.src=d;else if(removeUnstaged)el.removeAttribute('src');}" +
                "var set=el.getAttribute('srcset');if(!set)return;" +
                "var rewritten=[],unresolved=false;" +
                "set.split(',').forEach(function(part){var bits=part.trim().split(/\\s+/);" +
                "var raw=bits.shift();if(!raw)return;var resolved=raw;" +
                "try{resolved=new URL(raw,document.baseURI).href;}catch(e){}" +
                "var mapped=m[resolved];" +
                "if(mapped)rewritten.push(mapped+(bits.length?' '+bits.join(' '):''));" +
                "else if(/^(data:|review-resource:|#|about:blank)/i.test(resolved))rewritten.push(part.trim());" +
                "else unresolved=true;});" +
                "if(unresolved){if(removeUnstaged)el.removeAttribute('srcset');}" +
                "else if(rewritten.length)el.setAttribute('srcset',rewritten.join(', '));});" +
                "root.querySelectorAll('link[rel=\"stylesheet\"]').forEach(function(el){" +
                "if(!/^https?:\\/\\//i.test(el.href))return;var d=c[el.href];" +
                "if(d){var s=document.createElement('style');" +
                "s.textContent=d;el.parentNode.insertBefore(s,el);el.remove();}" +
                "else if(removeUnstaged)el.remove();});" +
                "})()"
        }

        private fun buildSerializeSnapshotJs(inline: InlineResources): String {
            return "(function(){" +
                "var root=window.__legadoReviewSnapshotRoot;" +
                "if(!root)return '$SNAPSHOT_ROOT_MISSING';" +
                "var cacheAvatars=${inline.cacheAvatars};" +
                "var cacheCommentImages=${inline.cacheCommentImages};" +
                IMAGE_CLASSIFIER_HELPER_JS +
                "var scripts=root.querySelectorAll('script');" +
                "for(var i=scripts.length-1;i>=0;i--){var e=scripts[i];" +
                "if(e.parentNode)e.parentNode.removeChild(e);}" +
                // 部分成功收口：无法入库的外部引用不再判死整份快照，而是剔除或以 #
                // 占位；剔除数量随结果带出，快照照常落盘渲染，按钮仍计失败等待重试。
                "var dropped=0;" +
                "function acceptedRef(v){return /^(data:|review-resource:|#|about:blank)/i.test(v);}" +
                "root.querySelectorAll('img').forEach(function(el){" +
                "if(!(isAvatarImage(el)?cacheAvatars:cacheCommentImages))return;" +
                "var source=el.getAttribute('src');" +
                "if(source&&/^(https?|blob):/i.test(el.src)){el.removeAttribute('src');dropped++;}" +
                "var set=el.getAttribute('srcset')||'';" +
                "if(set){var bad=false;set.split(',').forEach(function(part){" +
                "var raw=part.trim().split(/\\s+/)[0];if(!raw)return;var resolved=raw;" +
                "try{resolved=new URL(raw,document.baseURI).href;}catch(e){}" +
                "if(!acceptedRef(resolved))bad=true;});" +
                "if(bad){el.removeAttribute('srcset');dropped++;}}});" +
                "root.querySelectorAll('link[rel=\"stylesheet\"][href]').forEach(function(el){" +
                "if(!/^(data:|review-resource:|about:blank)/i.test(el.href)){" +
                "el.parentNode.removeChild(el);dropped++;}});" +
                "root.querySelectorAll('style').forEach(function(el){" +
                "var text=el.textContent||'';var changed=false;" +
                "text=text.replace(/url\\s*\\(\\s*([\"']?)([^\"'\\)\\s]+)\\1\\s*\\)/ig,function(m,q,v){" +
                "if(acceptedRef(v))return m;changed=true;return \"url('#')\";});" +
                "text=text.replace(/@import\\s*[\"']([^\"']+)[\"']/ig,function(m,v){" +
                "if(acceptedRef(v))return m;changed=true;return '@import \"#\"';});" +
                "if(changed){el.textContent=text;dropped++;}});" +
                "return JSON.stringify({h:root.outerHTML,d:dropped});" +
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

    /** Shared DOM classifier for collection and inlining so each image obeys the same switch. */
    private const val IMAGE_CLASSIFIER_HELPER_JS =
        "function isAvatarImage(el){" +
            "for(var n=el;n&&n.nodeType===1;n=n.parentElement){" +
            "var v=((n.className||'')+' '+(n.id||'')+' '+" +
            "(n.getAttribute('alt')||'')+' '+(n.getAttribute('data-role')||'')).toLowerCase();" +
            "if(/avatar|profile[-_]?image|head[-_]?img|头像/i.test(v))return true;" +
            "}return false;}"

    private val COLLECT_RESOURCES_JS =
        "(function(){" +
            // 后续异步下载期间只操作这份脱离活页面的 DOM，页面脚本无法再改变快照内容。
            "var root=document.documentElement.cloneNode(true);" +
            "window.__legadoReviewSnapshotRoot=root;" +
            IMAGE_CLASSIFIER_HELPER_JS +
            "var img=[],css=[];" +
            "function addImage(el,raw){" +
            "var value=(raw||'').trim();if(!value)return;" +
            "try{value=new URL(value,document.baseURI).href;}catch(e){}" +
            "img.push({url:value,kind:isAvatarImage(el)?'avatar':'comment'});}" +
            "root.querySelectorAll('img').forEach(function(el){" +
            "var source=el.getAttribute('src');if(source)addImage(el,source);" +
            "var set=el.getAttribute('srcset')||'';" +
            "set.split(',').forEach(function(part){var candidate=part.trim().split(/\\s+/)[0];" +
            "if(candidate)addImage(el,candidate);});});" +
            "root.querySelectorAll('link[rel=\"stylesheet\"][href]').forEach(function(el){css.push(el.href);});" +
            "return JSON.stringify({img:img,css:css});" +
            "})()"

    private const val SNAPSHOT_ROOT_MISSING = "__LEGADO_REVIEW_SNAPSHOT_ROOT_MISSING__"

    /** 与序列化完整性检查一致的 CSS 引用文法：url() 形式，值不含空白/引号/括号。 */
    private val CSS_URL_REF_REGEX =
        Regex("url\\s*\\(\\s*([\"']?)([^\"')\\s]+)\\1\\s*\\)", RegexOption.IGNORE_CASE)

    /** 序列化完整性检查同样判死的 @import 字符串形式。 */
    private val CSS_IMPORT_REF_REGEX =
        Regex("@import\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)

    /** 完整性检查认可的本地引用前缀，命中者原样保留。 */
    private val CSS_LOCAL_REF_PREFIX_REGEX =
        Regex("^(?:data:|review-resource:|#|about:blank)", RegexOption.IGNORE_CASE)
}
