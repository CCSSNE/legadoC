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
import io.legado.app.help.review.ReviewSnapshotManager
import io.legado.app.help.review.ReviewSnapshotStore
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

    /**
     * 打开评论快照；refreshToNetwork=true（快照优先）时后台刷新为最新网络评论页。
     * @return true 表示已用快照打开
     */
    private fun openSnapshotIfCached(
        context: AppCompatActivity,
        src: String,
        hostChapter: BookChapter?,
        refreshToNetwork: Boolean
    ): Boolean {
        val chapter = hostChapter
            ?: ReadBook.book?.let {
                appDb.bookChapterDao.getChapter(it.bookUrl, ReadBook.durChapterIndex)
            }
            ?: return false
        val execution = executionContext(chapter, src) ?: return false
        val snapshotBook = execution.first
        val snapshotSource = execution.second
        val snapshotChapter = execution.third
        val snapshot = ReviewSnapshotStore.get(snapshotBook, snapshotChapter, src.trim())
            ?: return false
        // 快照优先：后台解析真实评论页并加载在线内容，成功后覆盖当前快照
        var refresher: (suspend () -> Pair<String, String>?)? = null
        if (refreshToNetwork) {
            val source = snapshotSource
            if (source != null) {
                val button = reviewButtonOf(src)
                if (button != null) {
                    refresher = refresh@{
                        val onlineUrl = ReviewSnapshotManager.resolveReviewPageUrl(
                            snapshotBook, source, snapshotChapter, button
                        ) ?: return@refresh null
                        fetchOnlineHtml(onlineUrl, source)
                    }
                }
            }
        }
        context.runOnUiThread {
            if (context.isFinishing || context.isDestroyed) return@runOnUiThread
            context.showDialogFragment(
                BottomWebViewDialog(
                    snapshotSource?.getKey().orEmpty(),
                    BookType.text,
                    snapshot.url.ifBlank { "about:blank" },
                    snapshot.html,
                    networkRefresher = refresher
                )
            )
        }
        return true
    }

    /**
     * 解析点击执行上下文：
     * - 当前章节的融合 overlay 未命中该 src → 原生按钮，回退当前阅读
     *   上下文（ReadBook）；
     * - 命中融合来源 → 必须解析出文字书、章节、书源，任一不存在返回
     *   null（直接失效，不回退到有声书上下文执行）。
     */
    private fun executionContext(
        chapter: BookChapter,
        src: String
    ): Triple<Book, BookSource?, BookChapter>? {
        val textContext = AudioTextFusion.findFusionTextContext(
            chapter.getVariable(AudioTextFusion.OVERLAY_KEY),
            src
        ) ?: run {
            // 未命中：原生按钮，走当前阅读上下文
            val book = ReadBook.book ?: return null
            return Triple(book, ReadBook.bookSource, chapter)
        }
        // 命中融合来源但书/章节/书源已不存在：直接失效，不回退
        val textBook = appDb.bookDao.getBook(textContext.first) ?: return null
        val textChapter = appDb.bookChapterDao.getChapterByUrl(textContext.first, textContext.second)
            ?: return null
        val textSource = appDb.bookSourceDao.getBookSource(textBook.origin) ?: return null
        return Triple(textBook, textSource, textChapter)
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
                if (!openSnapshotIfCached(context, src, hostChapter, refreshToNetwork = false)) {
                    context.toastOnUi(R.string.review_no_cached_snapshot)
                }
            }
            AppConfig.ReviewOpenMode.SNAPSHOT_FIRST -> {
                if (openSnapshotIfCached(context, src, hostChapter, refreshToNetwork = true)) {
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
        val (urlNoOption, options) = parseSrcOptions(src) ?: return false
        val click = options["click"]
        val js = options["js"]
        if (click.isNullOrBlank() && js.isNullOrBlank()) return false
        when (openMode()) {
            AppConfig.ReviewOpenMode.SNAPSHOT_ONLY -> {
                if (!openSnapshotIfCached(context, src, hostChapter, refreshToNetwork = false)) {
                    context.toastOnUi(R.string.review_no_cached_snapshot)
                }
            }
            AppConfig.ReviewOpenMode.SNAPSHOT_FIRST -> {
                if (!openSnapshotIfCached(context, src, hostChapter, refreshToNetwork = true)) {
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
            val chapter = hostChapter
                ?: ReadBook.book?.let {
                    appDb.bookChapterDao.getChapter(it.bookUrl, ReadBook.durChapterIndex)
                }
                ?: return@async
            // null = 融合来源实体缺失，直接失效不执行
            val (book, source, execChapter) = executionContext(chapter, src) ?: return@async
            val execSource = source ?: return@async
            // 网络优先：存在快照时仅作为失败兜底，不影响正常网络加载
            val fallback = when (openMode()) {
                AppConfig.ReviewOpenMode.NETWORK ->
                    ReviewSnapshotStore.get(book, execChapter, src.trim())?.html
                else -> null
            }
            when {
                !click.isNullOrBlank() -> executeClick(book, execSource, execChapter, click, src) {
                    if (fallback != null) {
                        SnapshotFallbackJsExtensions(context, execSource, BookType.text, fallback)
                    } else {
                        SourceLoginJsExtensions(context, execSource, BookType.text)
                    }
                }
                else -> executeJs(book, execSource, execChapter, js.orEmpty(), urlNoOption.orEmpty()) {
                    fallbackBrowserHtml = fallback
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
        private val fallbackHtml: String
    ) : SourceLoginJsExtensions(context, source, bookType) {

        override fun startBrowser(url: String, title: String) {
            startBrowser(url, title, null)
        }

        override fun startBrowser(url: String, title: String, html: String?) {
            rhinoContext.ensureActive()
            SourceVerificationHelp.startBrowser(
                getSource(), url, title, html = html, fallbackHtml = fallbackHtml
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
            rhinoContext.ensureActive()
            val pair = SourceVerificationHelp.getVerificationResult(
                getSource(), url, title, true, refetchAfterSuccess, html,
                fallbackHtml = fallbackHtml
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
                        fallbackHtml = fallbackHtml
                    )
                )
            }
        }
    }

    private const val FETCH_TIMEOUT_MS = 60_000L
}