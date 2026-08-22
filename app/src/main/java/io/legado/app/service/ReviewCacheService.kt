package io.legado.app.service

import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.BaseService
import io.legado.app.constant.AppConst
import io.legado.app.constant.NotificationId
import io.legado.app.constant.AppLog
import io.legado.app.constant.IntentAction
import io.legado.app.help.config.AppConfig
import io.legado.app.help.review.ReviewSnapshotManager
import io.legado.app.help.review.ReviewSnapshotManager.ReviewSyncState
import io.legado.app.model.CacheBook
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
 *   4 个章节 worker 消费任务，章内评论按钮并行数由
 *
 * 通知与音频缓存一致：低优先级通知 + 进度条。并发下没有“当前章”概念，
 * 进度一律按实际完成量聚合：进度条 = 已完成评论章数/本次目标章数（只增不减），
 * 文本显示正文/评论总进度，并附跨并发章累计的“评论快照 c/d”；1 秒节流。
 * 一个任务失败只记日志，绝不打死整个 Review Phase。
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

        @Volatile
        private var stopRequestedGlobal = false

        val isStopRequested: Boolean
            get() = stopRequestedGlobal

        fun requestStop() {
            runCatching {
                appCtx.startService<ReviewCacheService> { action = IntentAction.stop }
            }.onFailure {
                AppLog.put("请求停止评论缓存失败\n${it.localizedMessage}", it)
            }
        }

        /** 与音频缓存相同的通知更新节流 */
        private const val NOTIFICATION_INTERVAL_MS = 1000L

        /** 评论任务入队/批量结束（Review Phase 开始）时拉起本服务 */
        fun startSelf() {
            // 后台限制（Android 15+ 前台服务启动受限）等场景下启动失败不能影响正文：
            // 登记的任务仍在队列里，下次前台恢复/再次入队会重新拉起
            runCatching {
                appCtx.startService<ReviewCacheService> {}
            }.onFailure {
                val message = "评论缓存服务启动失败：${it.localizedMessage}"
                io.legado.app.constant.AppLog.put(message, it)
                notificationManager.notify(
                    NotificationId.CacheBookService,
                    NotificationCompat.Builder(appCtx, AppConst.channelIdDownload)
                        .setSmallIcon(R.drawable.ic_status_bar_r)
                        .setContentTitle(appCtx.getString(R.string.cache_download_failed))
                        .setContentText(message)
                        .setStyle(NotificationCompat.BigTextStyle().bigText(message))
                        .setAutoCancel(false)
                        .setOngoing(false)
                        .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                        .build()
                )
            }
        }
    }

    private var workJob: Job? = null
    private var notifyJob: Job? = null
    private var lastNotifyTime = 0L
    private var stopRequested = false
    private var stopText: String? = null
    /** 正在执行任务的 worker 数：队列空时需全部 idle 才停服务 */
    private val activeCount = java.util.concurrent.atomic.AtomicInteger(0)

    private fun notificationBuilder(): NotificationCompat.Builder {
        NotificationCompat.Builder(this, AppConst.channelIdDownload)
            .setSmallIcon(R.drawable.ic_status_bar_r)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentTitle(getString(R.string.sync_cache_review))
            .setContentText(getString(R.string.review_sync_running))
            .setContentIntent(activityPendingIntent<io.legado.app.ui.book.cache.CacheActivity>("cacheActivity"))
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .also { builder -> addNotificationActions(builder) }
    }

    private fun addNotificationActions(builder: NotificationCompat.Builder) {
        if (CacheBook.isDownloadPaused) {
            builder.addAction(
                R.drawable.ic_play_24dp,
                getString(R.string.resume),
                servicePendingIntent<ReviewCacheService>(IntentAction.resume)
            )
        } else {
            builder.addAction(
                R.drawable.ic_pause_24dp,
                getString(R.string.pause),
                servicePendingIntent<ReviewCacheService>(IntentAction.pause)
            )
        }
        builder.addAction(
            R.drawable.ic_stop_black_24dp,
            getString(R.string.stop),
            servicePendingIntent<ReviewCacheService>(IntentAction.stop)
        )
    }

    override fun onCreate() {
        super.onCreate()
        stopRequestedGlobal = false
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
        when (intent?.action) {
            IntentAction.pause -> {
                CacheBook.pauseDownload()
                lastNotifyTime = 0L
                upNotification(ReviewSnapshotManager.syncState.value)
            }
            IntentAction.resume -> {
                CacheBook.resumeDownload()
                lastNotifyTime = 0L
                upNotification(ReviewSnapshotManager.syncState.value)
            }
            IntentAction.stop -> {
                stopRequested = true
                stopRequestedGlobal = true
                stopText = totalProgressText(ReviewSnapshotManager.syncState.value)
                AppLog.put("用户从评论缓存通知停止下载：$stopText")
                if (CacheBookService.isRun && !CacheBookService.isStopRequested) {
                    appCtx.startService<CacheBookService> { action = IntentAction.stop }
                }
                ReviewSnapshotManager.clearAllTasks()
                stopSelf()
                return super.onStartCommand(intent, flags, startId)
            }
        }
        if (workJob?.isActive != true) {
            startWork()
        }
        return super.onStartCommand(intent, flags, startId)
    }

    private fun startWork() {
        workJob?.cancel()
        // 章节 worker 只承担“取章节任务”的消费，固定 4 路足够；
        // worker 多路与按钮并发相乘会暴涨 WebView 数
        val concurrency = 4
        workJob = lifecycleScope.launch(Dispatchers.IO) {
            repeat(concurrency) {
                launch {
                    while (isActive) {
                        CacheBook.awaitDownloadResumed()
                        // 先取出任务再判断，绝不用“取任务的函数”当“看看有没有”：
                        // 取出来的任务必须被处理，否则任务会永久丢失
                        var task = ReviewSnapshotManager.tryTakeTask()
                        if (task == null) {
                            delay(1500)
                            task = ReviewSnapshotManager.tryTakeTask()
                            if (task == null) {
                                // 全部 worker 都空闲且队列空时才停服务
                                if (activeCount.get() == 0) {
                                    stopSelf()
                                    break
                                }
                                continue
                            }
                        }
                        activeCount.incrementAndGet()
                        try {
                            // 单任务异常隔离：一个任务失败只记日志，绝不打死整个 Review Phase
                            runCatching { ReviewSnapshotManager.processTask(task) }.onFailure {
                                io.legado.app.constant.AppLog.put(
                                    "评论快照任务处理失败 ${task.key}\n${it.localizedMessage}", it
                                )
                            }
                        } finally {
                            activeCount.decrementAndGet()
                        }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        val state = ReviewSnapshotManager.syncState.value
        val finalText = if (stopRequested) {
            stopText ?: getString(R.string.cache_manage_task_cancelled)
        } else {
            totalProgressText(state)
        }
        AppLog.put(
            if (stopRequested) "评论缓存已停止：$finalText" else "评论缓存结束：$finalText"
        )
        val finalNotification = notificationBuilder()
            .setContentText(finalText)
            .setOngoing(false)
            .setAutoCancel(false)
            .build()
        notificationManager.notify(NotificationId.CacheBookService, finalNotification)
        stopForeground(false)
        workJob?.cancel()
        notifyJob?.cancel()
        isRun = false
        stopRequestedGlobal = false
        super.onDestroy()
    }

    override fun startForegroundNotification() {
        val notification = notificationBuilder().build()
        startForeground(NotificationId.CacheBookService, notification)
        notificationManager.notify(NotificationId.CacheBookService, notification)
    }

    /** 通知文字：正文 x/y · 评论 a/b（y = 本次缓存目标章数），有已登记快照时附“评论快照 c/d” */
    private fun totalProgressText(state: ReviewSyncState): String {
        val bodyTotal = state.bodyTotal.takeIf { it > 0 }
            ?: state.bookUrl.takeIf { it.isNotBlank() }
                ?.let { io.legado.app.data.appDb.bookDao.getBook(it) }?.totalChapterNum
            ?: 0
        val bodyDone = state.bodyDone
        val reviewTotal = state.totalChapters.takeIf { it > 0 } ?: bodyTotal
        val reviewDone = state.completedChapters.coerceAtMost(reviewTotal)
        if (state.totalSnapshots > 0) {
            // 评论快照阶段：附跨并发章累计的已完成/已登记快照数
            return getString(
                R.string.review_notification_detail,
                bodyDone, bodyTotal,
                reviewDone, reviewTotal,
                state.completedSnapshots.coerceIn(0, state.totalSnapshots), state.totalSnapshots
            )
        }
        return getString(
            R.string.download_count_review,
            bodyDone, bodyTotal,
            reviewDone, reviewTotal
        )
    }

    private fun upNotification(state: ReviewSyncState) {
        val now = System.currentTimeMillis()
        if (now - lastNotifyTime < NOTIFICATION_INTERVAL_MS) return
        lastNotifyTime = now
        // 文字显示总进度（正文 x/y · 评论 a/b · 评论快照 c/d）
        val contentText = totalProgressText(state)
        val builder = notificationBuilder().setContentText(contentText)
        // 进度条 = 评论任务实际完成量（已完成章数/本次目标章数）：
        // 并发 worker 下只按完成数累计，绝不显示在途章节位置，避免前后跳动
        val reviewTotal = state.totalChapters.takeIf { it > 0 } ?: state.bodyTotal
        if (reviewTotal > 0) {
            builder.setProgress(
                reviewTotal,
                state.completedChapters.coerceIn(0, reviewTotal),
                false
            )
        } else {
            // 总章数未知（任务尚未登记完成）：不定进度
            builder.setProgress(0, 0, true)
        }
        notificationManager.notify(NotificationId.CacheBookService, builder.build())
    }
}
