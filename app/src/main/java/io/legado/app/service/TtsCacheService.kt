package io.legado.app.service

import android.content.Intent
import androidx.lifecycle.lifecycleScope
import io.legado.app.base.BaseService
import io.legado.app.constant.AppLog
import io.legado.app.constant.NotificationId
import io.legado.app.help.cache.CacheNotificationBridge
import io.legado.app.help.cache.CacheTtsWorkerRegistry
import io.legado.app.help.tts.TtsCacheManager
import io.legado.app.utils.startService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import splitties.init.appCtx
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Android execution host for Coordinator-owned TTS cache workers. */
class TtsCacheService : BaseService() {

    companion object {
        /** TTS 章节按顺序消费；章内单元也按顺序合成（系统 TTS 引擎不保证并发安全）。 */
        private const val CHAPTER_CONSUMER_COUNT = 1
        private val startRequested = AtomicBoolean(false)

        internal fun startSelf(): Boolean {
            if (!startRequested.compareAndSet(false, true)) return true
            return runCatching {
                appCtx.startService<TtsCacheService> { }
                true
            }.onFailure {
                startRequested.set(false)
                AppLog.put("TTS缓存宿主启动失败：${it.localizedMessage}", it)
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
            repeat(CHAPTER_CONSUMER_COUNT) {
                launch {
                    while (isActive) {
                        var task = TtsCacheManager.tryTakeTask()
                        if (task == null) {
                            delay(1500)
                            task = TtsCacheManager.tryTakeTask()
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
                            val result = runCatching { TtsCacheManager.processTask(task) }
                                .onFailure {
                                    AppLog.put(
                                        "TTS缓存任务处理失败 ${task.key}\n${it.localizedMessage}",
                                        it,
                                    )
                                }
                                .getOrElse { TtsCacheManager.TaskResult.FAILED }
                            val chapterIndex = task.key.substringAfter('|').toIntOrNull()
                            if (chapterIndex != null && result != TtsCacheManager.TaskResult.STOPPED) {
                                task.executionLease?.let { lease ->
                                    CacheTtsWorkerRegistry.onChapterFinished(
                                        lease,
                                        chapterIndex,
                                        result == TtsCacheManager.TaskResult.SUCCEEDED,
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
        resetStartRequest()
        if (CacheTtsWorkerRegistry.hasCoordinatorTasks()) {
            AppLog.put("TTS缓存宿主异常结束：收敛未完成的 Coordinator 任务")
            CacheTtsWorkerRegistry.onServiceFinished(cancelled = true)
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
