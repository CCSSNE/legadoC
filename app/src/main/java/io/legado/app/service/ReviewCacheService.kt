package io.legado.app.service

import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.BaseService
import io.legado.app.constant.AppConst
import io.legado.app.constant.AppLog
import io.legado.app.constant.NotificationId
import io.legado.app.help.cache.CacheReviewWorkerRegistry
import io.legado.app.help.review.ReviewSnapshotManager
import io.legado.app.ui.book.cache.CacheActivity
import io.legado.app.utils.activityPendingIntent
import io.legado.app.utils.startService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import splitties.init.appCtx
import java.util.concurrent.atomic.AtomicInteger

/** Android execution host for Coordinator-owned REVIEW workers. */
class ReviewCacheService : BaseService() {

    companion object {
        internal fun startSelf() {
            runCatching {
                appCtx.startService<ReviewCacheService> { }
            }.onFailure {
                AppLog.put("评论缓存宿主启动失败：${it.localizedMessage}", it)
            }
        }
    }

    private var workJob: Job? = null
    private val activeCount = AtomicInteger(0)

    override fun onCreate() {
        super.onCreate()
        startWork()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (workJob?.isActive != true) startWork()
        return super.onStartCommand(intent, flags, startId)
    }

    private fun startWork() {
        workJob?.cancel()
        workJob = lifecycleScope.launch(Dispatchers.IO) {
            repeat(4) {
                launch {
                    while (isActive) {
                        var task = ReviewSnapshotManager.tryTakeTask()
                        if (task == null) {
                            delay(1500)
                            task = ReviewSnapshotManager.tryTakeTask()
                            if (task == null) {
                                if (activeCount.get() == 0) {
                                    stopSelf()
                                    break
                                }
                                continue
                            }
                        }
                        activeCount.incrementAndGet()
                        try {
                            val success = runCatching { ReviewSnapshotManager.processTask(task) }
                                .onFailure {
                                    AppLog.put(
                                        "评论快照任务处理失败 ${task.key}\n${it.localizedMessage}",
                                        it,
                                    )
                                }
                                .getOrDefault(false)
                            val chapterIndex = task.key.substringAfter('|').toIntOrNull()
                            if (chapterIndex != null) {
                                task.executionLease?.let { lease ->
                                    CacheReviewWorkerRegistry.onChapterFinished(
                                        lease,
                                        chapterIndex,
                                        success,
                                    )
                                }
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
        if (activeCount.get() > 0) {
            CacheReviewWorkerRegistry.onServiceFinished(cancelled = true)
        }
        workJob?.cancel()
        stopForeground(false)
        super.onDestroy()
    }

    override fun startForegroundNotification() {
        val notification = NotificationCompat.Builder(this, AppConst.channelIdDownload)
            .setSmallIcon(R.drawable.ic_status_bar_r)
            .setContentTitle(getString(R.string.offline_cache))
            .setContentIntent(activityPendingIntent<CacheActivity>("cacheActivity"))
            .setOnlyAlertOnce(true)
            .setAutoCancel(false)
            .setOngoing(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
        startForeground(NotificationId.CacheCoordinator, notification)
    }
}
