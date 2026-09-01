package io.legado.app.help.review.reviewoutbox

import io.legado.app.constant.AppLog
import io.legado.app.constant.LogModule
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.PendingReviewComment
import io.legado.app.help.book.BookImgClick
import io.legado.app.help.config.AppConfig
import io.legado.app.help.review.ReviewSnapshotManager
import io.legado.app.model.analyzeRule.AnalyzeUrl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 离线评论发送调度器：唯一发送入口（菜单"发送离线评论"手动触发）。
 * 逐条串行：解析评论页地址（buttonSrc 重执行书源 click JS，缺失时用记录地址）
 * → 无头回填回放（书源原有通道发送）→ 更新账本状态。
 * 开始前自动关闭离线评论模式；任一失败明确记录原因，绝不静默丢弃。
 */
object ReviewOutboxDispatcher {

    private val running = AtomicBoolean(false)

    /**
     * 批次独立作用域：发送与页面生命周期解耦，用户退出阅读页不会取消批次。
     * 回放需要主线程（WebView），回调用全局 toast 通知，不持有 Activity。
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    data class ItemResult(val item: PendingReviewComment, val ok: Boolean, val message: String)

    data class Summary(val total: Int, val success: Int, val failures: List<ItemResult>) {
        val ok: Boolean get() = failures.isEmpty()
    }

    /**
     * 后台启动批次（唯一发送入口），结束时回调结果。
     * 回调参数为 null 表示已有批次进行中，本次未启动。
     * 回调在主线程执行，内部不得引用 Activity。
     */
    fun sendInBackground(result: (Summary?) -> Unit) {
        scope.launch {
            result(sendAll())
        }
    }

    /** 发送全部待发送与失败记录；已在发送中返回 null（调用方提示进行中） */
    suspend fun sendAll(): Summary? {
        if (!running.compareAndSet(false, true)) return null
        try {
            val items = ReviewOutboxStore.sendable()
            if (items.isEmpty()) {
                AppLog.putDebug(
                    "${ReviewOutboxStore.LogTag} 发送批次：无可发送记录",
                    module = LogModule.REVIEW_OFFLINE
                )
                return Summary(0, 0, emptyList())
            }
            if (AppConfig.offlineReviewMode) {
                AppConfig.offlineReviewMode = false
                AppLog.putDebug(
                    "${ReviewOutboxStore.LogTag} 发送批次开始，离线评论模式已自动关闭",
                    module = LogModule.REVIEW_OFFLINE
                )
            }
            AppLog.putDebug(
                "${ReviewOutboxStore.LogTag} 发送批次开始：共 ${items.size} 条",
                module = LogModule.REVIEW_OFFLINE
            )
            val failures = mutableListOf<ItemResult>()
            var success = 0
            items.forEachIndexed { index, item ->
                AppLog.putDebug(
                    "${ReviewOutboxStore.LogTag} 开始发送 ${index + 1}/${items.size} " +
                        "id=${item.id} kind=${item.kindText()} 书=${item.bookName} 章=${item.chapterTitle}",
                    module = LogModule.REVIEW_OFFLINE
                )
                val result = sendOne(item)
                if (result.ok) success++ else failures.add(result)
            }
            AppLog.putDebug(
                "${ReviewOutboxStore.LogTag} 发送批次结束：成功 $success，失败 ${failures.size}",
                module = LogModule.REVIEW_OFFLINE
            )
            return Summary(items.size, success, failures)
        } finally {
            running.set(false)
        }
    }

    private suspend fun sendOne(item: PendingReviewComment): ItemResult {
        val sending = ReviewOutboxStore.markSending(item)
        val result = try {
            val resolved = withContext(Dispatchers.IO) { resolvePage(sending) }
            AppLog.putDebug(
                "${ReviewOutboxStore.LogTag} 评论页解析完成 id=${sending.id} url=${resolved.first}",
                module = LogModule.REVIEW_OFFLINE
            )
            replay(sending, resolved.first, resolved.second, resolved.third)
        } catch (e: kotlinx.coroutines.CancellationException) {
            val error = "发送被取消：${e.message ?: "unknown"}"
            ReviewOutboxStore.markFailed(sending, error)
            AppLog.putDebug(
                "${ReviewOutboxStore.LogTag} 发送取消 id=${sending.id}：$error",
                module = LogModule.REVIEW_OFFLINE
            )
            throw e
        } catch (e: Throwable) {
            val error = e.message?.takeIf { it.isNotBlank() } ?: e.toString()
            ReviewOutboxStore.markFailed(sending, error)
            AppLog.putDebug(
                "${ReviewOutboxStore.LogTag} 发送失败 id=${sending.id}：$error",
                e,
                module = LogModule.REVIEW_OFFLINE
            )
            ItemResult(sending, false, error)
        }
        if (result.ok) {
            AppLog.putDebug(
                "${ReviewOutboxStore.LogTag} 发送成功 id=${item.id}：${result.message}",
                module = LogModule.REVIEW_OFFLINE
            )
        } else {
            AppLog.putDebug(
                "${ReviewOutboxStore.LogTag} 发送失败 id=${item.id}：${result.message}",
                module = LogModule.REVIEW_OFFLINE
            )
        }
        return result
    }

    /**
     * 解析评论页地址：优先 buttonSrc 重执行书源 click JS（可取到新 sessionid），
     * 缺失或解析失败时回退记录时的评论页地址；两者皆无则报错。
     * 同时返回书源，供回放请求带上书源头信息。
     */
    private suspend fun resolvePage(
        item: PendingReviewComment
    ): Triple<String, String?, BookSource?> {
        val reviewPageUrl = item.reviewPageUrl
        val buttonSrc = item.buttonSrc
        if (buttonSrc.isNullOrBlank()) {
            requireNotNull(reviewPageUrl) { "缺少评论页地址，无法发送" }
            return Triple(reviewPageUrl, null, null)
        }
        val book = appDb.bookDao.getBook(item.bookUrl)
            ?: error("书籍已不存在（${item.bookName}），无法发送")
        val chapter = appDb.bookChapterDao.getChapterByUrl(item.bookUrl, item.chapterUrl)
            ?: error("章节已不存在（${item.chapterTitle}），无法发送")
        val origin = item.origin ?: book.origin
        val source = appDb.bookSourceDao.getBookSource(origin)
            ?: error("书源已不存在（$origin），无法发送")
        val parsed = BookImgClick.parseSrcOptions(buttonSrc)
            ?: error("评论按钮选项解析失败，无法定位评论页")
        val (urlNoOption, options) = parsed
        val button = ReviewSnapshotManager.ReviewButton(
            src = buttonSrc.trim(),
            click = options["click"]?.takeIf { it.isNotBlank() },
            js = options["js"]?.takeIf { it.isNotBlank() },
            urlNoOption = urlNoOption,
        )
        val page = try {
            ReviewSnapshotManager.resolveReviewPageUrl(book, source, chapter, button)
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            if (reviewPageUrl != null) {
                AppLog.putDebug(
                    "${ReviewOutboxStore.LogTag} 评论页重解析失败，回退记录地址：${e.localizedMessage}",
                    module = LogModule.REVIEW_OFFLINE
                )
                null
            } else {
                error("评论页地址解析失败：${e.localizedMessage ?: e}")
            }
        }
        val url = page?.url ?: reviewPageUrl
            ?: error("评论页地址解析失败：书源未返回评论页地址")
        return Triple(url, page?.html, source)
    }

    /** 无头回填回放（书源原有通道发送） */
    private suspend fun replay(
        item: PendingReviewComment,
        url: String,
        html: String?,
        source: BookSource?
    ): ReviewOutboxDispatcher.ItemResult {
        val analyzeUrl = AnalyzeUrl(url, source = source)
        val session = ReviewReplaySession(
            url = analyzeUrl.url,
            html = html,
            headerMap = analyzeUrl.headerMap,
            kind = item.kind,
            content = item.content,
        )
        val result = session.run()
        return if (result.ok) {
            val sent = ReviewOutboxStore.markSent(item)
            ItemResult(sent, true, result.message)
        } else {
            ReviewOutboxStore.markFailed(item, result.message)
            ItemResult(item, false, result.message)
        }
    }

    /** 供外部判断批次是否进行中 */
    fun isRunning(): Boolean = running.get()
}
