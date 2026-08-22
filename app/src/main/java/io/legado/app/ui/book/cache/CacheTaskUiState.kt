package io.legado.app.ui.book.cache

import io.legado.app.help.cache.CacheKind
import io.legado.app.help.cache.CacheLifecycle
import io.legado.app.help.cache.CacheSnapshot
import io.legado.app.help.cache.CacheTaskState
import io.legado.app.help.cache.CacheUnitStatus

internal fun CacheSnapshot.toMediaTaskStates(): Map<String, AudioCacheTaskState> {
    return sessions.asSequence()
        .flatMap { it.tasks.asSequence() }
        .filter { it.kind != CacheKind.TEXT && it.phase == io.legado.app.help.cache.CachePhase.MEDIA }
        .groupBy { it.bookUrl }
        .mapValues { (_, tasks) -> tasks.maxBy { it.updatedAt }.toAudioTaskState() }
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
