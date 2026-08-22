package io.legado.app.help.review

import io.legado.app.constant.AppLog
import io.legado.app.constant.AppPattern
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.isLocal
import io.legado.app.help.config.AppConfig
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import java.util.concurrent.ConcurrentHashMap

/**
 * 评论快照抓取调度器。
 *
 * 正文永远优先：这里只在章节正文保存完成后入队，由单条低优先级协程串行处理，
 * 每个按钮之间主动让出（delay），失败只记日志，绝不影响正文下载与刷新体验。
 * “正文已缓存”不跳过补评论：是否需要抓取以 [ReviewSnapshotStore] 中
 * 章节每个评论按钮的快照是否存在为准，重新缓存/刷新会再次入队补齐缺失快照。
 */
object ReviewSnapshotManager {

    private data class Task(
        val bookUrl: String,
        val chapterIndex: Int
    )

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val channel = Channel<Task>(Channel.UNLIMITED)
    private val pendingKeys = ConcurrentHashMap.newKeySet<String>()

    /** 按钮之间的间隔，把网络与 WebView 占用压到最低 */
    private const val BUTTON_INTERVAL_MS = 800L

    /** 单章最多处理的评论按钮数，防止异常书源拖垮后台 */
    private const val MAX_BUTTONS_PER_CHAPTER = 30

    @Volatile
    private var workerStarted = false

    /**
     * 正文保存成功后调用：开关打开时该章入队，由工作协程判断哪些按钮缺快照。
     */
    fun enqueueIfEnabled(bookSource: BookSource?, book: Book, chapter: BookChapter) {
        if (!AppConfig.syncCacheReview) return
        if (book.isLocal) return
        if (bookSource == null) return
        enqueue(chapter)
    }

    /**
     * 已缓存正文的章节在重新缓存/刷新时也必须走“是否需要补评论”的判断，
     * 因此缓存流程在跳过正文下载前调用本方法直接入队。
     */
    fun enqueue(chapter: BookChapter) {
        val key = "${chapter.bookUrl}|${chapter.index}"
        if (!pendingKeys.add(key)) return
        channel.trySend(Task(chapter.bookUrl, chapter.index))
        startWorkerIfNeeded()
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
            pendingKeys.remove("${task.bookUrl}|${task.chapterIndex}")
            runCatching { processTask(task) }
        }
    }

    private suspend fun processTask(task: Task) {
        val book = appDb.bookDao.getBook(task.bookUrl) ?: return
        val bookSource = appDb.bookSourceDao.getBookSource(book.origin) ?: return
        // 处理时再查一次开关：入队后关闭开关不应继续抓取
        if (!AppConfig.syncCacheReview) return
        val chapter = appDb.bookChapterDao.getChapter(task.bookUrl, task.chapterIndex) ?: return
        val content = BookHelp.getContent(book, chapter) ?: return
        val buttons = extractReviewButtons(content).take(MAX_BUTTONS_PER_CHAPTER)
        for ((src, clickJs) in buttons) {
            if (clickJs.isBlank()) continue
            if (ReviewSnapshotStore.has(book, chapter.index, src)) continue
            // 失败只记日志：重新缓存/刷新会再次入队重试，绝不影响正文
            runCatching {
                val snapshot = ReviewSnapshotCapture.capture(
                    bookSource, book, chapter, src, clickJs
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

    /** 提取正文里的评论按钮：img 标签选项 JSON 带 style=TEXT 的评论泡 → (src, clickJs) */
    fun extractReviewButtons(content: String): List<Pair<String, String>> {
        if (!content.contains("<img", ignoreCase = true)) return emptyList()
        val result = linkedMapOf<String, String>()
        val matcher = AppPattern.imgPattern.matcher(content)
        while (matcher.find()) {
            val src = matcher.group(1) ?: continue
            val options = extractUrlOptions(src) ?: continue
            val style = options["style"] ?: continue
            if (!style.equals("TEXT", ignoreCase = true)) continue
            val click = options["click"].orEmpty().ifBlank { options["js"].orEmpty() }
            result.putIfAbsent(src.trim(), click)
        }
        return result.map { it.key to it.value }
    }

    /** 与 AnalyzeUrl.paramPattern 一致：URL 后第一个 `,` 到 `{` 的选项 JSON */
    private fun extractUrlOptions(src: String): Map<String, String>? {
        val matcher = AnalyzeUrl.paramPattern.matcher(src)
        if (!matcher.find()) return null
        val optionJson = src.substring(matcher.end())
        return GSON.fromJsonObject<Map<String, String>>(optionJson).getOrNull()
    }
}
