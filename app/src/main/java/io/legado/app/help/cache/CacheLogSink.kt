package io.legado.app.help.cache

import io.legado.app.constant.AppLog
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

enum class CacheLogEventType {
    SESSION_CREATED,
    SESSION_RECOVERED,
    TASK_QUEUED,
    TASK_STARTED,
    TASK_RECLAIMED,
    TASK_PAUSING,
    TASK_PAUSED,
    TASK_RESUMED,
    TASK_CANCELLING,
    TASK_CANCELLED,
    TASK_FINISHED,
    UNIT_UPDATED,
    UNIT_FAILED,
    UNIT_PROGRESS_SUMMARY,
    STALE_UPDATE_DROPPED,
    PERSISTENCE_LOAD_FAILED,
    PERSISTENCE_SAVE_FAILED,
    WORKER_DISPATCH_FAILED,
    REQUEST_ACCEPTED,
    REVIEW_RESOURCE_GC,
}

data class CacheLogEvent(
    val type: CacheLogEventType,
    val sessionId: String? = null,
    val taskId: String? = null,
    val detail: String? = null,
    val at: Long = System.currentTimeMillis(),
)

interface CacheLogSink {
    fun record(event: CacheLogEvent)
}

/** Human-readable sink for the existing log viewer. Persistence stays behind this contract. */
object AppLogCacheLogSink : CacheLogSink {
    override fun record(event: CacheLogEvent) {
        if (event.type == CacheLogEventType.UNIT_UPDATED && !shouldEmitUnitProgress(event)) return
        if (event.type in terminalTaskEvents) flushUnitProgress(event)
        emit(event)
    }

    private fun emit(event: CacheLogEvent) {
        val scope = buildList {
            event.sessionId?.let { add("session=$it") }
            event.taskId?.let { add("task=$it") }
        }.joinToString(" ")
        val suffix = event.detail?.takeIf { it.isNotBlank() }?.let { " detail=$it" }.orEmpty()
        AppLog.put("cache_event=${event.type}${if (scope.isBlank()) "" else " $scope"}$suffix")
    }

    /**
     * Store state still receives every unit transition. Only the AppLog projection is sampled:
     * first event and every 32nd event per task, while every UNIT_FAILED remains immediate.
     */
    private fun shouldEmitUnitProgress(event: CacheLogEvent): Boolean {
        val key = event.taskKey() ?: return true
        val count = unitProgressCounts.getOrPut(key) { AtomicLong() }.incrementAndGet()
        return count == 1L || count % UNIT_LOG_SAMPLE_EVERY == 0L
    }

    private fun flushUnitProgress(event: CacheLogEvent) {
        val key = event.taskKey() ?: return
        val total = unitProgressCounts.remove(key)?.get() ?: return
        val emitted = 1L + (total - 1L) / UNIT_LOG_SAMPLE_EVERY
        val suppressed = (total - emitted).coerceAtLeast(0L)
        if (suppressed == 0L) return
        emit(
            event.copy(
                type = CacheLogEventType.UNIT_PROGRESS_SUMMARY,
                detail = "unitEvents=$total sampled=$emitted suppressed=$suppressed",
            )
        )
    }

    private fun CacheLogEvent.taskKey(): String? =
        if (sessionId == null || taskId == null) null else "$sessionId/$taskId"

    private val unitProgressCounts = ConcurrentHashMap<String, AtomicLong>()

    private val terminalTaskEvents = setOf(
        CacheLogEventType.TASK_FINISHED,
        CacheLogEventType.TASK_CANCELLED,
    )

    private const val UNIT_LOG_SAMPLE_EVERY = 32L
}
