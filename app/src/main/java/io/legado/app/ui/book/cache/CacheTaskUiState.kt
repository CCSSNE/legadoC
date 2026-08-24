package io.legado.app.ui.book.cache

import io.legado.app.help.cache.CacheKind
import io.legado.app.help.cache.CacheLifecycle
import io.legado.app.help.cache.CacheLifecycleRules
import io.legado.app.help.cache.CacheSnapshot
import io.legado.app.help.cache.CacheSubmission
import io.legado.app.help.cache.CacheTaskState
import io.legado.app.help.cache.CacheUnitStatus
import io.legado.app.help.cache.supports

internal fun CacheTaskState.isCoordinatorDownloadTask(): Boolean = kind.supports(phase)

private fun CacheTaskState.isMediaCacheManageTask(): Boolean =
    kind != CacheKind.TEXT && isCoordinatorDownloadTask()

private val currentTaskComparator =
    compareBy<CacheTaskState> {
        if (CacheLifecycleRules.isTerminal(it.status)) 0 else 1
    }.thenBy { it.updatedAt }

internal fun CacheSnapshot.toMediaTaskStates(): Map<String, AudioCacheTaskState> {
    return sessions.asSequence()
        .flatMap { it.tasks.asSequence() }
        .filter { it.isMediaCacheManageTask() }
        .groupBy { it.bookUrl }
        .mapValues { (_, tasks) -> tasks.maxWith(currentTaskComparator).toAudioTaskState() }
}

internal fun CacheSnapshot.findMediaDownloadTask(
    bookUrl: String,
): Pair<CacheSubmission, CacheTaskState>? {
    val task = sessions.asSequence()
        .flatMap { it.tasks.asSequence() }
        .filter { it.bookUrl == bookUrl && it.isMediaCacheManageTask() }
        .maxWithOrNull(currentTaskComparator)
    return task?.let { CacheSubmission(it.sessionId, it.taskId) to it }
}

private fun CacheTaskState.toAudioTaskState(): AudioCacheTaskState {
    val completed = units.count { it.status == CacheUnitStatus.SUCCEEDED }
    val status = when (this.status) {
        CacheLifecycle.QUEUED -> CacheTaskStatus.PENDING
        CacheLifecycle.RUNNING,
        CacheLifecycle.PAUSING,
        CacheLifecycle.CANCELLING -> CacheTaskStatus.CACHING
        CacheLifecycle.PAUSED -> CacheTaskStatus.PAUSED
        CacheLifecycle.COMPLETED -> CacheTaskStatus.COMPLETED
        CacheLifecycle.CANCELLED -> CacheTaskStatus.CANCELLED
        CacheLifecycle.FAILED -> CacheTaskStatus.FAILED
        CacheLifecycle.INTERRUPTED -> CacheTaskStatus.PENDING
    }
    return AudioCacheTaskState(
        bookUrl = bookUrl,
        bookName = bookName,
        totalChapters = units.size,
        completedChapters = completed,
        currentChapterIndex = completed,
        status = status,
        message = error.orEmpty(),
        active = this.status == CacheLifecycle.RUNNING ||
            this.status == CacheLifecycle.PAUSING ||
            this.status == CacheLifecycle.CANCELLING,
    )
}
