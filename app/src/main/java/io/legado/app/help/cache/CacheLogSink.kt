package io.legado.app.help.cache

import io.legado.app.constant.AppLog

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
    STALE_UPDATE_DROPPED,
    PERSISTENCE_LOAD_FAILED,
    PERSISTENCE_SAVE_FAILED,
    REQUEST_ACCEPTED,
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
        val scope = buildList {
            event.sessionId?.let { add("session=$it") }
            event.taskId?.let { add("task=$it") }
        }.joinToString(" ")
        val suffix = event.detail?.takeIf { it.isNotBlank() }?.let { " detail=$it" }.orEmpty()
        AppLog.put("cache_event=${event.type}${if (scope.isBlank()) "" else " $scope"}$suffix")
    }
}
