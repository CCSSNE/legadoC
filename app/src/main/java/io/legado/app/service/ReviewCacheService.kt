package io.legado.app.service

import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.BaseService
import io.legado.app.constant.AppConst
import io.legado.app.constant.NotificationId
import io.legado.app.help.review.ReviewSnapshotManager
import io.legado.app.help.review.ReviewSnapshotManager.ReviewSyncState
import io.legado.app.utils.activityPendingIntent
import io.legado.app.utils.startService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import splitties.init.appCtx
import splitties.systemservices.notificationManager

/**
 * 评论页快照同步服务（Review Phase）。
 *
 * 批量缓存分两阶段：
 * - Body Phase（CacheBookService）：只下载正文，评论任务由调度器登记，
 *   绝不开 WebView 抓评论；
 * - Review Phase：整批目标正文结束后，[ReviewSnapshotManager.endBodyPhase]
 *   把登记任务入队并启动本服务。本服务以低优先级通知常驻前台，
 *   单线程串行抓取评论页快照，失败只记日志。
 *
 * 通知与音频缓存一致：低优先级通知 + 进度条，进度条 = 当前章的评论按钮进度
 * （done/total，未解析时 indeterminate），文本显示当前章与已完成章数，
 * 1 秒节流；一个任务失败只记日志，绝不打死整个 Review Phase。
 *
 * 生命周期保障：
 * - 评论进度不算正文下载进度，正文下载状态（CacheBookService）结束后才启动；
 * - 队列空时短暂重试确认后 stopSelf；App 被杀后下一次入队会重新拉起；
 * - 未完成的“评论待刷新”标记已落盘（filesDir），重启不受影响。
 */
class ReviewCacheService : BaseService() {

    companion object {
        var isRun = false
            private set

        /** 与音频缓存相同的通知更新节流 */
        private const val NOTIFICATION_INTERVAL_MS = 1000L

        /** 评论任务入队/批量结束（Review Phase 开始）时拉起本服务 */
        fun startSelf() {
            // 后台限制（Android 15+ 前台服务启动受限）等场景下启动失败不能影响正文：
            // 登记的任务仍在队列里，下次前台恢复/再次入队会重新拉起
            runCatching {
                appCtx.startService<ReviewCacheService> {}
            }.onFailure {
                io.legado.app.constant.AppLog.put(
                    "启动评论快照服务失败\n${it.localizedMessage}", it
                )
            }
        }
    }

    private var workJob: Job? = null
    private var notifyJob: Job? = null
    private var lastNotifyTime = 0L

    private val notificationBuilder by lazy {
        NotificationCompat.Builder(this, AppConst.channelIdReview)
            .setSmallIcon(R.drawable.ic_status_bar_r)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentTitle(getString(R.string.sync_cache_review))
            .setContentText(getString(R.string.review_sync_running))
            .setContentIntent(activityPendingIntent<io.legado.app.ui.book.cache.CacheActivity>("cacheActivity"))
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
    }

    override fun onCreate() {
        super.onCreate()
        isRun = true
        startWork()
        // 订阅进度：参照音频缓存 notifyState，1 秒节流更新通知与进度条
        notifyJob = lifecycleScope.launch(Dispatchers.IO) {
            ReviewSnapshotManager.syncState.collect { state ->
                upNotification(state)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (workJob?.isActive != true) {
            startWork()
        }
        return super.onStartCommand(intent, flags, startId)
    }

    private fun startWork() {
        workJob?.cancel()
        workJob = lifecycleScope.launch(Dispatchers.IO) {
            while (isActive) {
                // 先取出任务再判断，绝不用“取任务的函数”当“看看有没有”：
                // 取出来的任务必须被处理，否则任务会永久丢失
                var task = ReviewSnapshotManager.tryTakeTask()
                if (task == null) {
                    delay(1500)
                    task = ReviewSnapshotManager.tryTakeTask()
                    if (task == null) {
                        stopSelf()
                        break
                    }
                }
                // 单任务异常隔离：一个任务失败只记日志，绝不打死整个 Review Phase
                runCatching { ReviewSnapshotManager.processTask(task) }.onFailure {
                    io.legado.app.constant.AppLog.put(
                        "评论快照任务处理失败 ${task.key}\n${it.localizedMessage}", it
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        workJob?.cancel()
        notifyJob?.cancel()
        isRun = false
        super.onDestroy()
    }

    override fun startForegroundNotification() {
        val notification = notificationBuilder.build()
        startForeground(NotificationId.ReviewCacheService, notification)
        notificationManager.notify(NotificationId.ReviewCacheService, notification)
    }

    /** 进度通知：当前章按钮进度（进度条），文本带书名/当前章/已完成章数 */
    /** 通知文字：正文 x/y · 评论 a/b（总进度），与正文缓存通知同一格式 */
    private fun totalProgressText(state: ReviewSyncState): String {
        val book = state.bookUrl.takeIf { it.isNotBlank() }
            ?.let { io.legado.app.data.appDb.bookDao.getBook(it) }
        if (book != null) {
            val cachedText = io.legado.app.help.book.BookHelp.getChapterFiles(book)
                .count { it.endsWith(".nb") }
            val cachedReview = ReviewSnapshotManager.cachedReviewChapterCount(book)
            return getString(
                R.string.download_count_review,
                cachedText,
                book.totalChapterNum,
                cachedReview,
                book.totalChapterNum
            )
        }
        return getString(
            R.string.review_sync_running,
            state.bookName.ifBlank { getString(R.string.sync_cache_review) }
        )
    }

    private fun upNotification(state: ReviewSyncState) {
        val now = System.currentTimeMillis()
        if (now - lastNotifyTime < NOTIFICATION_INTERVAL_MS) return
        lastNotifyTime = now
        // 文字显示总进度（正文 x/y · 评论 a/b）
        val contentText = totalProgressText(state)
        val builder = notificationBuilder.setContentText(contentText)
        // 进度条 = 当前评论单章节处理进度（当前章按钮 done/total）
        if (state.totalButtons > 0) {
            builder.setProgress(
                state.totalButtons,
                state.completedButtons.coerceIn(0, state.totalButtons),
                false
            )
        } else {
            // 当前章按钮数未知（未解析/章与章之间）：不固定进度
            builder.setProgress(0, 0, true)
        }
        notificationManager.notify(NotificationId.ReviewCacheService, builder.build())
    }
}