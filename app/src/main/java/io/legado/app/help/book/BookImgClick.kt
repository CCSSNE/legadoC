package io.legado.app.help.book

import androidx.appcompat.app.AppCompatActivity
import com.script.rhino.rhinoContext
import com.script.rhino.runScriptWithContext
import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.constant.BookType
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.help.config.AppConfig
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.http.BackstageWebView
import io.legado.app.help.http.StrResponse
import io.legado.app.help.review.ReviewSnapshot
import io.legado.app.help.review.ReviewSnapshotCapture
import io.legado.app.help.review.ReviewSnapshotManager
import io.legado.app.help.review.ReviewSnapshotStore
import io.legado.app.help.review.reviewoutbox.ReviewOutboxContext
import io.legado.app.help.source.SourceVerificationHelp
import io.legado.app.model.ReadBook
import io.legado.app.model.analyzeRule.AnalyzeRule
import io.legado.app.model.analyzeRule.AnalyzeRule.Companion.setChapter
import io.legado.app.model.analyzeRule.AnalyzeRule.Companion.setCoroutineContext
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.model.analyzeRule.AnalyzeUrl.Companion.paramPattern
import io.legado.app.ui.login.SourceLoginJsExtensions
import io.legado.app.ui.widget.dialog.BottomWebViewDialog
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.coroutines.coroutineContext

/**
 * 图片/评论点击统一入口。
 *
 * “评论打开方式”三模式（[AppConfig.reviewOpenMode]，默认网络优先）：
 * - 网络优先：点击评论立即按原 click/js 正常打开真实评论页，绝不因存在快照
 *   截断原链路；网络加载失败/超时且有快照 → 自动切换快照；无快照 → 正常错误；
 * - 快照优先：有快照 → 立即显示快照（0 秒可见），后台继续执行原 click/js 解析
 *   并加载最新网络评论页，成功后当前窗口覆盖为在线页；失败/超时停留快照；
 *   无快照 → 正常网络打开；
 * - 仅使用快照：有快照 → 打开快照，绝不执行 click/js、绝不联网；无快照 →
 *   明确提示“当前章节没有缓存评论”，不回退网络。
 *
 * 同一套 click/js 执行逻辑同时服务用户点击与后台抓取：抓取时传入拦截宿主
 * （click 分支替换 java 宿主；js 分支挂 AnalyzeRule 钩子），其余环境完全一致。
 */
object BookImgClick {

    private fun openMode(): String = AppConfig.reviewOpenMode

    /** 与图片点击入口共用同一判定，避免给没有 click/js 的零评论泡暴露段评菜单。 */
    fun hasAction(src: String, click: String?): Boolean {
        if (!click.isNullOrBlank()) return true
        val options = parseSrcOptions(src)?.second ?: return false
        return !options["click"].isNullOrBlank() || !options["js"].isNullOrBlank()
    }

    /**
     * 评论快照定位不依赖网络执行条件。书源缺失时仍可用 book/chapter 读取并打开
     * 已落盘的快照；只有真正执行 click/js 时才解析并要求书源存在。
     */
    private data class ReviewContext(
        val book: Book,
        val chapter: BookChapter,
        val useCurrentReadSource: Boolean,
    )

    /** 已在网络执行前读出的快照，失败回退时禁止重新查询或重新解析上下文。 */
    private data class CachedReviewSnapshot(
        val book: Book,
        val chapter: BookChapter,
        val snapshot: ReviewSnapshot,
    )

    private fun currentChapter(hostChapter: BookChapter?): BookChapter? {
        return hostChapter
            ?: ReadBook.book?.let {
                appDb.bookChapterDao.getChapter(it.bookUrl, ReadBook.durChapterIndex)
            }
    }

    /**
     * 解析评论所属文字书上下文。这里不读取书源：本地快照的主键只需要
     * book/chapter，不能因为任何网络执行条件而错过离线快照。
     */
    private fun reviewContext(chapter: BookChapter, src: String): ReviewContext? {
        val textContext = AudioTextFusion.findFusionTextContext(
            chapter.getVariable(AudioTextFusion.OVERLAY_KEY),
            src
        ) ?: run {
            val book = ReadBook.book ?: return null
            return ReviewContext(book, chapter, useCurrentReadSource = true)
        }
        val textBook = appDb.bookDao.getBook(textContext.first) ?: return null
        val textChapter = appDb.bookChapterDao.getChapterByUrl(textContext.first, textContext.second)
            ?: return null
        return ReviewContext(
            textBook,
            textChapter,
            useCurrentReadSource = false
        )
    }

    private fun cachedSnapshot(context: ReviewContext, src: String): CachedReviewSnapshot? {
        val snapshot = ReviewSnapshotStore.get(context.book, context.chapter, src.trim())
            ?: return null
        return CachedReviewSnapshot(context.book, context.chapter, snapshot)
    }

    private fun resolveNetworkSource(context: ReviewContext): BookSource? {
        return if (context.useCurrentReadSource) {
            ReadBook.bookSource
        } else {
            appDb.bookSourceDao.getBookSource(context.book.origin)
        }
    }

    private fun showSnapshot(
        context: AppCompatActivity,
        cached: CachedReviewSnapshot,
        source: BookSource? = null,
        networkRefresher: (suspend () -> Pair<String, String>?)? = null,
        offlineOnly: Boolean,
    ): Boolean {
        if (context.isFinishing || context.isDestroyed) return false
        context.runOnUiThread {
            if (context.isFinishing || context.isDestroyed) return@runOnUiThread
            context.showDialogFragment(
                BottomWebViewDialog(
                    source?.getKey().orEmpty(),
                    BookType.text,
                    cached.snapshot.url.ifBlank { "about:blank" },
                    cached.snapshot.html,
                    networkRefresher = networkRefresher,
                    offlineOnly = offlineOnly,
                    reviewResourceBook = cached.book,
                    outboxContext = ReviewOutboxContext(
                        bookUrl = cached.book.bookUrl,
                        bookName = cached.book.name,
                        chapterUrl = cached.chapter.url,
                        chapterIndex = cached.chapter.index,
                        chapterTitle = cached.chapter.title,
                        origin = source?.getKey() ?: cached.book.origin,
                        buttonSrc = cached.snapshot.buttonSrc.ifBlank { null },
                        pageUrl = cached.snapshot.url,
                    ),
                )
            )
        }
        return true
    }

    /**
     * 打开评论快照。
     * @param refreshToNetwork 快照优先：后台刷新为最新网络评论页（成功后覆盖）
     * @param offlineOnly 仅使用快照：WebView 禁止一切 http/https 网络请求
     * @return true 表示已用快照打开
     */
    private fun openSnapshotIfCached(
        context: AppCompatActivity,
        src: String,
        hostChapter: BookChapter?,
        refreshToNetwork: Boolean,
        offlineOnly: Boolean
    ): Boolean {
        val chapter = currentChapter(hostChapter) ?: return false
        // 先持有本地快照；网络刷新条件不得影响离线快照可用性。
        val resolvedContext = reviewContext(chapter, src) ?: return false
        val cached = cachedSnapshot(resolvedContext, src) ?: return false
        // 快照优先：后台解析真实评论页并加载在线内容，成功后覆盖当前快照
        var refresher: (suspend () -> Pair<String, String>?)? = null
        val snapshotSource = resolveNetworkSource(resolvedContext)
        if (refreshToNetwork && snapshotSource != null) {
            val button = reviewButtonOf(src)
            if (button != null) {
                refresher = refresh@{
                    val page = ReviewSnapshotManager.resolveReviewPageUrl(
                        cached.book, snapshotSource, cached.chapter, button
                    )
                    val onlineUrl = page.url ?: return@refresh null
                    // showBrowser 已带回渲染 HTML 且有效：直接用，不再重复请求
                    val onlineHtml = when {
                        !page.html.isNullOrBlank() &&
                            ReviewSnapshotCapture.isValidCommentHtml(page.html) -> page.html
                        else -> fetchOnlineHtml(onlineUrl, snapshotSource)?.second
                    } ?: return@refresh null
                    onlineUrl to onlineHtml
                }
            }
        }
        return showSnapshot(context, cached, snapshotSource, refresher, offlineOnly)
    }

    /**
     * 执行 src 附带的 click JS（用户点击路径）。
     * @param hostChapter 当前展示章节；融合挂载的评论按钮据此反查文字书
     * 上下文执行；为 null 时按当前阅读上下文（ReadBook）执行。
     */
    fun clickImg(
        context: AppCompatActivity,
        scope: CoroutineScope,
        click: String,
        src: String,
        hostChapter: BookChapter? = null,
    ) {
        when (openMode()) {
            AppConfig.ReviewOpenMode.SNAPSHOT_ONLY -> {
                // 仅使用快照：绝不执行 click/js，也绝不允许快照内残留资源联网
                if (!openSnapshotIfCached(
                        context, src, hostChapter,
                        refreshToNetwork = false, offlineOnly = true
                    )
                ) {
                    context.toastOnUi(R.string.review_no_cached_snapshot)
                }
            }
            AppConfig.ReviewOpenMode.SNAPSHOT_FIRST -> {
                if (openSnapshotIfCached(
                        context, src, hostChapter,
                        refreshToNetwork = true, offlineOnly = false
                    )
                ) {
                    return
                }
                // 无快照 → 直接按正常网络评论打开
                openNetwork(context, scope, click, null, null, src, hostChapter)
            }
            else -> openNetwork(context, scope, click, null, null, src, hostChapter)
        }
    }

    /**
     * 兼容旧源：click/js 写在 src 的 url 选项里（无独立 click 字段时走此入口）。
     * @param hostChapter 当前展示章节；融合挂载的评论按钮据此反查文字书上下文。
     * @return true 表示已处理
     */
    fun oldClickImg(
        context: AppCompatActivity,
        scope: CoroutineScope,
        src: String,
        hostChapter: BookChapter? = null,
    ): Boolean {
        val parsed = parseSrcOptions(src)
        if (parsed == null) {
            // 旧源选项 JSON 损坏时，网络链路没有可执行的 click/js。若已缓存，仍应
            // 直接展示持有的离线评论；无快照则交回原图片链路暴露其原始失败。
            return if (openMode() == AppConfig.ReviewOpenMode.NETWORK &&
                paramPattern.matcher(src).find()
            ) {
                openSnapshotIfCached(
                    context, src, hostChapter,
                    refreshToNetwork = false, offlineOnly = true
                )
            } else {
                false
            }
        }
        val (urlNoOption, options) = parsed
        val click = options["click"]
        val js = options["js"]
        if (click.isNullOrBlank() && js.isNullOrBlank()) {
            return if (openMode() == AppConfig.ReviewOpenMode.NETWORK) {
                openSnapshotIfCached(
                    context, src, hostChapter,
                    refreshToNetwork = false, offlineOnly = true
                )
            } else {
                false
            }
        }
        when (openMode()) {
            AppConfig.ReviewOpenMode.SNAPSHOT_ONLY -> {
                if (!openSnapshotIfCached(
                        context, src, hostChapter,
                        refreshToNetwork = false, offlineOnly = true
                    )
                ) {
                    context.toastOnUi(R.string.review_no_cached_snapshot)
                }
            }
            AppConfig.ReviewOpenMode.SNAPSHOT_FIRST -> {
                if (!openSnapshotIfCached(
                        context, src, hostChapter,
                        refreshToNetwork = true, offlineOnly = false
                    )
                ) {
                    openNetwork(context, scope, click, js, urlNoOption, src, hostChapter)
                }
            }
            else -> openNetwork(context, scope, click, js, urlNoOption, src, hostChapter)
        }
        return true
    }

    /**
     * 网络打开（网络优先默认路径 / 快照优先无快照回退）。
     * click 与旧源 js 走与用户点击完全一致的执行环境；
     * 网络优先模式下载入时若该按钮存在快照，将其作为“加载失败/超时兜底”
     * 传给浏览器（绝不因存在快照截断原链路）。
     */
    private fun openNetwork(
        context: AppCompatActivity,
        scope: CoroutineScope,
        click: String?,
        js: String?,
        urlNoOption: String?,
        src: String,
        hostChapter: BookChapter?
    ) {
        Coroutine.async(scope, Dispatchers.IO) {
            val chapter = currentChapter(hostChapter)
                ?: error("无法定位当前章节，无法执行评论网络打开")
            // 快照必须在任何网络执行条件、click/js 解析之前读出并持有。
            val resolvedContext = reviewContext(chapter, src)
            val fallback = resolvedContext?.let { cachedSnapshot(it, src) }
            try {
                val execution = resolvedContext
                    ?: error("无法解析评论所属书籍或章节，无法执行评论网络打开")
                val execSource = resolveNetworkSource(execution)
                    ?: error("评论书源不存在，无法执行评论网络打开")
                when {
                    !click.isNullOrBlank() -> {
                        // 评论 click 统一宿主：有无快照兜底都走本宿主，保证
                        // showBrowser 弹窗路径统一携带离线评论上下文
                        val host = SnapshotFallbackJsExtensions(
                            context,
                            execSource,
                            BookType.text,
                            fallback?.snapshot?.html,
                            execution.book,
                            execution.chapter,
                            src,
                        )
                        executeClick(
                            execution.book, execSource, execution.chapter, click, src
                        ) {
                            host
                        }
                        if (!host.browserRequested) {
                            error("评论 click 未发起浏览器打开")
                        }
                    }
                    else -> {
                        val fallbackHtml = fallback?.snapshot?.html
                        var browserRequested = false
                        executeJs(
                            execution.book, execSource, execution.chapter,
                            js.orEmpty(), urlNoOption.orEmpty()
                        ) {
                            fallbackBrowserHtml = fallbackHtml
                            fallbackReviewResourceBook = fallback?.book
                            if (fallbackHtml != null) {
                                onBrowserOpenRequestedHook = { _, _, _ ->
                                    browserRequested = true
                                    false
                                }
                                onBrowserAwaitRequestedHook = { _, _, _ ->
                                    browserRequested = true
                                    null
                                }
                            }
                        }
                        if (fallbackHtml != null && !browserRequested) {
                            error("评论 js 未发起浏览器打开")
                        }
                    }
                }
            } catch (error: Throwable) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                if (fallback != null && showSnapshot(
                        context, fallback,
                        networkRefresher = null,
                        offlineOnly = true
                    )
                ) {
                    AppLog.put(
                        "评论网络打开失败，已回退本地快照\n${error.localizedMessage}",
                        error
                    )
                } else {
                    throw error
                }
            }
        }.onError {
            AppLog.put("执行图片链接click/js键值出错\n${it.localizedMessage}", it, true)
        }
    }

    /** 从 src 构造评论按钮模型（后台解析/刷新用） */
    private fun reviewButtonOf(src: String): ReviewSnapshotManager.ReviewButton? {
        val (urlNoOption, options) = parseSrcOptions(src) ?: return null
        return ReviewSnapshotManager.ReviewButton(
            src = src.trim(),
            click = options["click"]?.takeIf { it.isNotBlank() },
            js = options["js"]?.takeIf { it.isNotBlank() },
            urlNoOption = urlNoOption
        )
    }

    /** 后台加载真实网络评论页（带书源 cookie/UA），失败或超时返回 null */
    private suspend fun fetchOnlineHtml(
        url: String,
        source: BookSource
    ): Pair<String, String>? {
        return runCatching {
            val analyzeUrl = AnalyzeUrl(url, source = source)
            val body = BackstageWebView(
                url = analyzeUrl.url,
                headerMap = analyzeUrl.headerMap,
                tag = source.getKey(),
                timeout = FETCH_TIMEOUT_MS
            ).getStrResponse().body
            body?.takeIf { it.isNotBlank() }?.let { analyzeUrl.url to it } ?: return null
        }.getOrNull()
    }

    /**
     * 解析 src 的选项 JSON：返回 (去选项地址, 选项Map)。无选项时返回 null。
     */
    fun parseSrcOptions(src: String): Pair<String, Map<String, String>>? {
        val urlMatcher = paramPattern.matcher(src)
        if (!urlMatcher.find()) return null
        val urlNoOption = src.take(urlMatcher.start())
        val urlOptionStr = src.substring(urlMatcher.end())
        val options = GSON.fromJsonObject<Map<String, String>>(urlOptionStr).getOrNull() ?: return null
        return urlNoOption to options
    }

    /**
     * click 分支统一执行：与用户点击完全一致的环境，java 宿主可替换为拦截宿主。
     */
    suspend fun executeClick(
        book: Book,
        source: BookSource,
        chapter: BookChapter,
        click: String,
        src: String,
        javaBuilder: () -> io.legado.app.help.JsExtensions
    ) {
        runScriptWithContext {
            source.evalJS(click) {
                val java = javaBuilder()
                put("java", java)
                put("book", book)
                put("chapter", chapter)
                put("result", src)
            }
        }
    }

    /**
     * 旧源 js 分支统一执行：AnalyzeRule 规则引擎环境，与用户点击完全一致。
     * 评论快照抓取可通过 [AnalyzeRule.onBrowserOpenRequestedHook] 拦截浏览器请求；
     * 网络优先的失败兜底可通过 [AnalyzeRule.fallbackBrowserHtml] 指定快照。
     */
    suspend fun executeJs(
        book: Book,
        source: BookSource,
        chapter: BookChapter,
        js: String,
        urlNoOption: String,
        ruleHook: (AnalyzeRule.() -> Unit)? = null
    ) {
        AnalyzeRule(book, source).apply {
            setCoroutineContext(coroutineContext)
            setBaseUrl(chapter.url)
            setChapter(chapter)
            ruleHook?.invoke(this)
            evalJS(js, urlNoOption)
        }
    }

    /**
     * 网络优先模式的浏览器宿主：打开真实评论页时附带快照兜底，
     * 网络加载失败/超时由 WebViewActivity 自动切换到快照。
     */
    private class SnapshotFallbackJsExtensions(
        context: AppCompatActivity?,
        source: BookSource?,
        bookType: Int,
        private val fallbackHtml: String?,
        private val reviewResourceBook: Book,
        private val chapter: BookChapter?,
        private val buttonSrc: String?,
    ) : SourceLoginJsExtensions(context, source, bookType) {

        var browserRequested = false
            private set

        override fun startBrowser(url: String, title: String) {
            startBrowser(url, title, null)
        }

        override fun startBrowser(url: String, title: String, html: String?) {
            browserRequested = true
            rhinoContext.ensureActive()
            SourceVerificationHelp.startBrowser(
                getSource(),
                url,
                title,
                html = html,
                fallbackHtml = fallbackHtml,
                fallbackReviewResourceBook = reviewResourceBook,
            )
        }

        override fun startBrowserAwait(url: String, title: String): StrResponse {
            return startBrowserAwait(url, title, true, null)
        }

        override fun startBrowserAwait(
            url: String,
            title: String,
            refetchAfterSuccess: Boolean
        ): StrResponse {
            return startBrowserAwait(url, title, refetchAfterSuccess, null)
        }

        override fun startBrowserAwait(
            url: String,
            title: String,
            refetchAfterSuccess: Boolean,
            html: String?
        ): StrResponse {
            browserRequested = true
            rhinoContext.ensureActive()
            val pair = SourceVerificationHelp.getVerificationResult(
                getSource(), url, title, true, refetchAfterSuccess, html,
                fallbackHtml = fallbackHtml,
                fallbackReviewResourceBook = reviewResourceBook,
            )
            val (url2, body) = pair
            return StrResponse(url2.ifEmpty { url }, body)
        }

        /** 评论底部弹窗路径同样支持“网络优先”：失败/超时切快照 */
        override fun showBrowser(
            url: String,
            html: String?,
            preloadJs: String?,
            config: String?
        ) {
            browserRequested = true
            val activity = activityRef.get() ?: return
            val source = getSource() ?: return
            if (callbackRef.get()?.showBrowser(url, html, preloadJs, config) == true) {
                return
            }
            activity.runOnUiThread {
                if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                activity.showDialogFragment(
                    BottomWebViewDialog(
                        source.getKey(),
                        bookType,
                        url,
                        html,
                        preloadJs,
                        config,
                        networkRefresher = null,
                        fallbackHtml = fallbackHtml,
                        reviewResourceBook = reviewResourceBook,
                        outboxContext = ReviewOutboxContext(
                            bookUrl = reviewResourceBook.bookUrl,
                            bookName = reviewResourceBook.name,
                            chapterUrl = chapter?.url.orEmpty(),
                            chapterIndex = chapter?.index ?: 0,
                            chapterTitle = chapter?.title.orEmpty(),
                            origin = getSource()?.getKey(),
                            buttonSrc = buttonSrc,
                            pageUrl = url,
                        ),
                    )
                )
            }
        }
    }

    private const val FETCH_TIMEOUT_MS = 60_000L
}
