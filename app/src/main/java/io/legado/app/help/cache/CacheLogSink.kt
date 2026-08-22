package io.legado.app.help.cache

import io.legado.app.constant.AppLog

interface CacheLogSink {
    fun record(
        event: String,
        sessionId: String? = null,
        taskId: String? = null,
        detail: String? = null,
    )
}

/** Human-readable sink for the existing log viewer. Persistence stays behind this contract. */
object AppLogCacheLogSink : CacheLogSink {
    override fun record(
        event: String,
        sessionId: String?,
        taskId: String?,
        detail: String?,
    ) {
        val scope = buildList {
            sessionId?.let { add("session=$it") }
            taskId?.let { add("task=$it") }
        }.joinToString(" ")
        val suffix = detail?.takeIf { it.isNotBlank() }?.let { " detail=$it" }.orEmpty()
        AppLog.put("cache_event=$event${if (scope.isBlank()) "" else " $scope"}$suffix")
    }
}
