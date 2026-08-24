package io.legado.app.help.cache

import android.os.SystemClock
import io.legado.app.help.config.AppConfig
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Global start-rate policy for Coordinator-owned primary chapter downloads.
 *
 * The Coordinator owns task lifecycle, while BODY and MEDIA executors own their
 * actual chapter starts. Every network attempt, including a retry, reserves its
 * start slot here before it touches the source or media cache.
 */
internal object ChapterDownloadPacer {

    private val lock = Mutex()
    private var nextStartElapsedRealtimeMs = 0L

    suspend fun awaitStartSlot(canStart: () -> Boolean): Boolean {
        val intervalMs = AppConfig.downloadChapterIntervalMillis
        val scheduledStart = lock.withLock {
            if (intervalMs == 0L) {
                nextStartElapsedRealtimeMs = 0L
                return@withLock 0L
            }
            val now = SystemClock.elapsedRealtime()
            val scheduledStart = maxOf(now, nextStartElapsedRealtimeMs)
            nextStartElapsedRealtimeMs = saturatingAdd(scheduledStart, intervalMs)
            scheduledStart
        }
        while (true) {
            if (!canStart()) return false
            val waitMs = scheduledStart - SystemClock.elapsedRealtime()
            if (waitMs <= 0L) return canStart()
            delay(minOf(waitMs, CANCELLATION_CHECK_INTERVAL_MS))
        }
    }

    private fun saturatingAdd(left: Long, right: Long): Long {
        return if (right > Long.MAX_VALUE - left) Long.MAX_VALUE else left + right
    }

    private const val CANCELLATION_CHECK_INTERVAL_MS = 250L
}
