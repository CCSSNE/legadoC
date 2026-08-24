package io.legado.app.service

import android.content.Intent
import androidx.lifecycle.lifecycleScope
import io.legado.app.base.BaseService
import io.legado.app.constant.AppLog
import io.legado.app.constant.NotificationId
import io.legado.app.help.cache.CacheNotificationBridge
import io.legado.app.help.cache.CacheReviewWorkerRegistry
import io.legado.app.help.review.ReviewSnapshotManager
import io.legado.app.utils.startService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import splitties.init.appCtx
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Android execution host for Coordinator-owned REVIEW workers. */
class ReviewCacheService : BaseService() {

    companion object {
        private val startRequested = AtomicBoolean(false)

        internal fun startSelf(): Boolean {
            if (!startRequested.compareAndSet(false, true)) return true
            return runCatching {
                appCtx.startService<ReviewCacheService> { }
                true
            }.onFailure {
                startRequested.set(false)
                AppLog.put("评论缓存宿主启动失败：${it.localizedMessage}", it)
            }.getOrDefault(false)
        }

        private fun resetStartRequest() {
            startRequested.set(false)
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
            // 完整评论快照的内存成本取决于页面和内联资源，无法用固定“每章并发数”安全
            // 估算。整个服务只保留一个活动快照任务，队列仍完整保留所有待处理章节。
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

    override fun onDestroy() {
        resetStartRequest()
        if (CacheReviewWorkerRegistry.hasCoordinatorTasks()) {
            AppLog.put("评论缓存宿主异常结束：收敛未完成的 Coordinator 任务")
            CacheReviewWorkerRegistry.onServiceFinished(cancelled = true)
        }
        workJob?.cancel()
        stopForeground(false)
        super.onDestroy()
    }

    override fun startForegroundNotification() {
        startForeground(
            NotificationId.CacheCoordinator,
            CacheNotificationBridge.foregroundNotification(),
        )
    }
}
