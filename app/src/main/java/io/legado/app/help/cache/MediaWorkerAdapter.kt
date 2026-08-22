package io.legado.app.help.cache

import io.legado.app.data.appDb
import io.legado.app.ui.book.cache.AudioCacheTaskManager
import io.legado.app.ui.book.cache.CacheTaskStatus
import java.util.concurrent.ConcurrentHashMap

/** Coordinator adapter over AudioCacheTaskManager's media execution core. */
internal class MediaWorkerAdapter(
    private val workerPort: CacheWorkerPort,
) {
    init {
        CacheMediaWorkerRegistry.bind(workerPort)
    }

    fun start(task: CacheTaskState, lease: CacheWorkerLease) {
        require(task.phase == CachePhase.MEDIA) { "media adapter received ${task.phase}" }
        val book = appDb.bookDao.getBook(task.bookUrl)
            ?: error("book not found: ${task.bookUrl}")
        task.units.forEach { workerPort.updateUnit(lease, it.key, CacheUnitStatus.RUNNING) }
        val chapters = task.units.mapNotNull { unit ->
            appDb.bookChapterDao.getChapter(task.bookUrl, unit.key.chapterIndex)
                ?.let { unit.key to it }
        }
        val validKeys = chapters.mapTo(linkedSetOf()) { it.first }
        task.units.asSequence()
            .map { it.key }
            .filterNot(validKeys::contains)
            .forEach { key ->
                workerPort.updateUnit(
                    lease,
                    key,
                    CacheUnitStatus.FAILED,
                    "chapter not found: ${key.chapterIndex}",
                )
            }
        if (!CacheMediaWorkerRegistry.register(task, lease, validKeys)) {
            CacheMediaWorkerRegistry.fail(lease, "another media task is active for this book")
            return
        }
        if (validKeys.isEmpty()) {
            CacheMediaWorkerRegistry.fail(lease, "no media chapters found")
            return
        }
        val state = AudioCacheTaskManager.snapshot(task.bookUrl)
        if (state?.status == CacheTaskStatus.PAUSED) {
            if (!AudioCacheTaskManager.resume(task.bookUrl)) {
                CacheMediaWorkerRegistry.fail(lease, "paused media task could not resume")
            }
            return
        }
        val started = AudioCacheTaskManager.start(
            book = book,
            chapters = chapters.map { it.second },
            resolver = MediaCacheResolver::resolve,
            onChapterResolved = { chapter, request ->
                if (chapter.resourceUrl != request.url) {
                    chapter.resourceUrl = request.url
                    appDb.bookChapterDao.update(chapter)
                }
            },
            onFinished = { CacheMediaWorkerRegistry.onFinished(task.bookUrl) },
            coordinatorManaged = true,
        )
        if (!started) CacheMediaWorkerRegistry.fail(lease, "media task was already active")
    }

    fun pause(submission: CacheSubmission) {
        val task = CacheCoordinator.currentTask(submission) ?: return
        AudioCacheTaskManager.pause(task.bookUrl)
        CacheCoordinator.workerPort.confirmPaused(submission)
    }

    fun cancel(submission: CacheSubmission) {
        val task = CacheCoordinator.currentTask(submission) ?: return
        CacheMediaWorkerRegistry.remove(submission.sessionId, submission.taskId)
        AudioCacheTaskManager.cancel(task.bookUrl)
        if (workerPort.confirmCancelled(submission)) {
            CacheCoordinator.notifyTaskFinished(submission, CacheResult.CANCELLED)
        }
    }
}

private object CacheMediaWorkerRegistry {
    private data class Binding(
        var lease: CacheWorkerLease,
        val expected: List<CacheUnitKey>,
    )

    private var workerPort: CacheWorkerPort? = null
    private val bindings = ConcurrentHashMap<String, Binding>()

    fun bind(workerPort: CacheWorkerPort) {
        this.workerPort = workerPort
    }

    fun register(
        task: CacheTaskState,
        lease: CacheWorkerLease,
        expected: List<CacheUnitKey>,
    ): Boolean {
        val existing = bindings[task.bookUrl]
        if (existing != null && existing.lease.taskId != lease.taskId) return false
        bindings[task.bookUrl] = Binding(lease, expected)
        return true
    }

    fun remove(sessionId: String, taskId: String) {
        bindings.entries.toList()
            .filter { it.value.lease.sessionId == sessionId && it.value.lease.taskId == taskId }
            .forEach { bindings.remove(it.key, it.value) }
    }

    fun fail(lease: CacheWorkerLease, error: String) {
        val port = requirePort()
        CacheCoordinator.currentTask(CacheSubmission(lease.sessionId, lease.taskId))
            ?.units
            ?.filter { it.status == CacheUnitStatus.PENDING || it.status == CacheUnitStatus.RUNNING }
            ?.forEach { unit ->
                port.updateUnit(lease, unit.key, CacheUnitStatus.FAILED, error)
            }
        if (port.finish(lease, CacheResult.FAILED, error)) {
            CacheCoordinator.notifyTaskFinished(lease, CacheResult.FAILED, error)
        }
        bindings.remove(lease.bookKey())
    }

    fun onFinished(bookUrl: String) {
        val binding = bindings[bookUrl] ?: return
        val state = AudioCacheTaskManager.snapshot(bookUrl) ?: return
        if (state.status == CacheTaskStatus.PAUSED) return
        val completed = state.completedChapters.coerceIn(0, binding.expected.size)
        val failed = state.status == CacheTaskStatus.FAILED || state.status == CacheTaskStatus.CANCELLED
        binding.expected.forEachIndexed { index, key ->
            val status = if (index < completed) CacheUnitStatus.SUCCEEDED
            else CacheUnitStatus.FAILED
            requirePort().updateUnit(
                binding.lease,
                key,
                status,
                if (status == CacheUnitStatus.FAILED) state.message else null,
            )
        }
        val result = if (failed || completed < binding.expected.size) {
            CacheResult.FAILED
        } else {
            CacheResult.SUCCEEDED
        }
        if (requirePort().finish(binding.lease, result, state.message.takeIf { result == CacheResult.FAILED })) {
            CacheCoordinator.notifyTaskFinished(
                binding.lease,
                result,
                state.message.takeIf { result == CacheResult.FAILED },
            )
        }
        bindings.remove(bookUrl)
    }

    private fun requirePort(): CacheWorkerPort =
        checkNotNull(workerPort) { "cache media worker registry is not bound" }

    private fun CacheWorkerLease.bookKey(): String =
        CacheCoordinator.currentTask(CacheSubmission(sessionId, taskId))?.bookUrl.orEmpty()
}
