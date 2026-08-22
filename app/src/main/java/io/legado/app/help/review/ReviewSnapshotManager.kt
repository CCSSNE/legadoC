package io.legado.app.help.review

import io.legado.app.constant.AppLog
import io.legado.app.constant.AppPattern
import io.legado.app.constant.BookType
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.BookImgClick
import io.legado.app.help.book.isLocal
import io.legado.app.help.config.AppConfig
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.http.StrResponse
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.ui.rss.read.RssJsExtensions
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * 评论快照抓取调度器。
 *
 * 正文永远优先：这里只在章节正文全部完成（文本/图片/成功状态/刷新状态）之后由
 * [CacheBook] 入队，由单条低优先级协程串行处理，每个按钮之间主动让出（delay），
 * 失败只记日志，绝不影响正文下载与刷新体验。
 *
 * 快照刷新语义：
 * - 普通后台重复触发（force=false）：已有快照可跳过；
 * - 用户明确重新缓存/刷新该章节（force=true 或 [markUserRefresh] 标记有效期内）：
 *   重新抓取并覆盖旧快照，不依赖 has()。
 */
object ReviewSnapshotManager {

    private data class Task(val key: String)

    /** 评论按钮模型：click / 旧源 js 二选一 */
    data class ReviewButton(
        val src: String,
        val click: String? = null,
        val js: String? = null,
        val urlNoOption: String? = null
    ) {
        val hasAction: Boolean get() = !click.isNullOrBlank() || !js.isNullOrBlank()
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val channel = Channel<Task>(Channel.UNLIMITED)

    /**
     * 待处理任务的 force 聚合表（key = bookUrl|chapterIndex）。
     * 负责两件事：
     * - 去重：已存在代表该章已在队列中，不重复入队；
     * - force 合并：后入队的 force=true 必须提升已排队任务的 force，
     *   绝不允许先 false 后 true 时把 true 丢掉。
     */
    private val pendingForce = ConcurrentHashMap<String, Boolean>()

    /** 用户刷新标记：按具体章节追踪（key = bookUrl|chapterIndex → 标记时间） */
    private val userRefreshMarks = ConcurrentHashMap<String, Long>()
    private const val MARK_TTL_MS = 15 * 60 * 1000L

    /** 按钮之间的间隔，把网络与 WebView 占用压到最低 */
    private const val BUTTON_INTERVAL_MS = 800L

    /** 单章最多处理的评论按钮数，防止异常书源拖垮后台 */
    private const val MAX_BUTTONS_PER_CHAPTER = 30

    /** 单个按钮解析评论页地址的超时 */
    private const val RESOLVE_TIMEOUT_MS = 20_000L

    @Volatile
    private var workerStarted = false

    /**
     * 正文保存/成功状态结束后调用。
     * @param force true = 用户明确重新缓存/刷新该章节，忽略旧快照直接重抓
     */
    fun enqueueIfEnabled(
        bookSource: BookSource?,
        book: Book,
        chapter: BookChapter,
        force: Boolean = false
    ) {
        if (!AppConfig.syncCacheReview) return
        if (book.isLocal) return
        if (bookSource == null) return
        enqueue(book, chapter, force)
    }

    /**
     * 已缓存正文的章节在重新缓存/刷新时也必须走“是否需要补评论”的判断，
     * 因此缓存流程在跳过正文下载前调用本方法直接入队。
     */
    fun enqueue(book: Book, chapter: BookChapter, force: Boolean = false) {
        val key = keyOf(book, chapter)
        val existed = pendingForce.putIfAbsent(key, force)
        if (existed == null) {
            channel.trySend(Task(key))
            startWorkerIfNeeded()
        } else if (force && existed != true) {
            // force 合并：已排队任务升级为强制重抓，不丢 true
            pendingForce.replace(key, existed, true)
        }
    }

    private fun keyOf(book: Book, chapter: BookChapter) = "${book.bookUrl}|${chapter.index}"

    /** 用户明确刷新某章：记录时间戳，该章下载完成后强制重抓评论 */
    fun markUserRefresh(bookUrl: String, chapterIndex: Int) {
        userRefreshMarks["$bookUrl|$chapterIndex"] = System.currentTimeMillis()
    }

    /** 该章是否处于用户刷新标记有效期内（顺带惰性清理过期标记） */
    fun isUserRefreshActive(bookUrl: String, chapterIndex: Int): Boolean {
        val now = System.currentTimeMillis()
        val key = "$bookUrl|$chapterIndex"
        val markedAt = userRefreshMarks[key] ?: return false
        if (now - markedAt > MARK_TTL_MS) {
            userRefreshMarks.remove(key)
            return false
        }
        return true
    }

    fun clearUserRefresh(bookUrl: String, chapterIndex: Int) {
        userRefreshMarks.remove("$bookUrl|$chapterIndex")
    }

    private fun startWorkerIfNeeded() {
        if (workerStarted) return
        synchronized(this) {
            if (workerStarted) return
            workerStarted = true
            Coroutine.async(scope, executeContext = Dispatchers.IO) {
                consume()
            }.start()
        }
    }

    private suspend fun consume() {
        for (task in channel) {
            // 取出聚合后的 force（队列去重期间可能被提升为 true）
            val force = pendingForce.remove(task.key) ?: false
            runCatching { processTask(task.key, force) }
        }
    }

    private suspend fun processTask(key: String, force: Boolean) {
        val bookUrl = key.substringBefore('|')
        val chapterIndex = key.substringAfter('|').toIntOrNull() ?: return
        val book = appDb.bookDao.getBook(bookUrl) ?: return
        val bookSource = appDb.bookSourceDao.getBookSource(book.origin) ?: return
        // 处理时再查一次开关：入队后关闭开关不应继续抓取
        if (!AppConfig.syncCacheReview) return
        val chapter = appDb.bookChapterDao.getChapter(bookUrl, chapterIndex) ?: return
        val content = BookHelp.getContent(book, chapter) ?: return
        val buttons = extractReviewButtons(content).take(MAX_BUTTONS_PER_CHAPTER)
        for (button in buttons) {
            if (!button.hasAction) continue
            // 普通触发有快照可跳过；force（明确重新缓存/刷新）必须重抓覆盖
            if (!force && ReviewSnapshotStore.has(book, chapter, button.src)) continue
            val url = runCatching {
                resolveReviewPageUrl(book, bookSource, chapter, button)
            }.onFailure {
                AppLog.put(
                    "解析评论页地址失败 ${book.name} ${chapter.title}\n${it.localizedMessage}", it
                )
            }.getOrNull()
            if (url.isNullOrBlank()) continue
            // 失败只记日志：重新缓存/刷新会再次入队重试，绝不影响正文
            runCatching {
                val snapshot = ReviewSnapshotCapture.capture(
                    bookSource, book, chapter, button.src, url
                )
                ReviewSnapshotStore.put(book, snapshot)
            }.onFailure {
                AppLog.put(
                    "评论快照抓取失败 ${book.name} ${chapter.title}\n${it.localizedMessage}", it
                )
            }
            delay(BUTTON_INTERVAL_MS)
        }
    }

    /**
     * 解析评论按钮对应的真实评论页地址。
     * 与用户点击共用 [BookImgClick.executeClick]/[executeJs]：
     * click 分支替换 java 拦截宿主，旧源 js 分支挂 AnalyzeRule 钩子，
     * 执行环境与真实点击完全一致。
     */
    private suspend fun resolveReviewPageUrl(
        book: Book,
        bookSource: BookSource,
        chapter: BookChapter,
        button: ReviewButton
    ): String? {
        val resolved = AtomicReference<String>()
        val latch = CountDownLatch(1)
        fun record(url: String): Boolean {
            resolved.compareAndSet(null, url)
            latch.countDown()
            return true
        }
        if (!button.click.isNullOrBlank()) {
            val host = object : RssJsExtensions(null, bookSource, BookType.text) {
                override fun onBrowserOpenRequested(url: String, title: String, html: String?): Boolean {
                    return record(url)
                }

                override fun onBrowserAwaitRequested(
                    url: String,
                    title: String,
                    html: String?
                ): StrResponse {
                    record(url)
                    return StrResponse(url, "")
                }
            }
            BookImgClick.executeClick(book, bookSource, chapter, button.click, button.src) { host }
        } else {
            val js = button.js.orEmpty()
            val urlNoOption = button.urlNoOption.orEmpty()
            BookImgClick.executeJs(book, bookSource, chapter, js, urlNoOption) {
                onBrowserOpenRequestedHook = { url, _, _ -> record(url) }
                onBrowserAwaitRequestedHook = { url, _, _ ->
                    record(url)
                    StrResponse(url, "")
                }
            }
        }
        latch.await(RESOLVE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        return resolved.get()
    }

    /**
     * 提取正文里的评论按钮：img 标签选项 JSON 带 style=TEXT 的评论泡。
     * click 与旧源 js 原样带出，交给与用户点击共用的执行逻辑。
     */
    fun extractReviewButtons(content: String): List<ReviewButton> {
        if (!content.contains("<img", ignoreCase = true)) return emptyList()
        val result = linkedMapOf<String, ReviewButton>()
        val matcher = AppPattern.imgPattern.matcher(content)
        while (matcher.find()) {
            val src = matcher.group(1) ?: continue
            val options = extractUrlOptions(src) ?: continue
            val style = options["style"] ?: continue
            if (!style.equals("TEXT", ignoreCase = true)) continue
            val key = src.trim()
            if (result.containsKey(key)) continue
            val urlNoOption = AnalyzeUrl.paramPattern.matcher(src).let { m ->
                if (m.find()) src.take(m.start()) else null
            }
            result[key] = ReviewButton(
                src = key,
                click = options["click"]?.takeIf { it.isNotBlank() },
                js = options["js"]?.takeIf { it.isNotBlank() },
                urlNoOption = urlNoOption
            )
        }
        return result.values.toList()
    }

    /** 与 AnalyzeUrl.paramPattern 一致：URL 后第一个 `,` 到 `{` 的选项 JSON */
    private fun extractUrlOptions(src: String): Map<String, String>? {
        val matcher = AnalyzeUrl.paramPattern.matcher(src)
        if (!matcher.find()) return null
        val optionJson = src.substring(matcher.end())
        return GSON.fromJsonObject<Map<String, String>>(optionJson).getOrNull()
    }
}