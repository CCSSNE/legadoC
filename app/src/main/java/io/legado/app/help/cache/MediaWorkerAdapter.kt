package io.legado.app.help.cache

import io.legado.app.data.appDb
import io.legado.app.data.entities.BookChapter
import io.legado.app.help.book.AudioOfflineState
import io.legado.app.help.book.CacheManifestHelper
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
        task.units
            .filter { it.status == CacheUnitStatus.PENDING || it.status == CacheUnitStatus.REVIEW_ELIGIBLE }
            .forEach { workerPort.updateUnit(lease, it.key, CacheUnitStatus.RUNNING) }
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
        if (!CacheMediaWorkerRegistry.register(task, lease, validKeys.toList())) {
            CacheMediaWorkerRegistry.fail(lease, "another media task is active for this book")
            return
        }
        if (validKeys.isEmpty()) {
            CacheMediaWorkerRegistry.fail(lease, "no media chapters found")
            return
        }
        val chapterFinished: (BookChapter, Boolean, String?) -> Unit = { chapter, success, error ->
            val audioState = if (success && task.kind == CacheKind.AUDIO) {
                AudioOfflineState.inspect(book, chapter)
            } else {
                null
            }
            CacheMediaWorkerRegistry.onChapterFinished(
                lease,
                chapter.index,
                success && audioState?.isComplete != false,
                audioState?.takeUnless { it.isComplete }?.incompleteReason() ?: error,
            )
        }
        val chapterStarted: (BookChapter) -> Unit = { chapter ->
            CacheMediaWorkerRegistry.onChapterStarted(lease, chapter.index)
        }
        val chapterProgress: (BookChapter, Long, Long?) -> Unit = { chapter, current, total ->
            CacheMediaWorkerRegistry.onChapterProgress(lease, chapter.index, current, total)
        }
        val finished = {
            // Media completion owns its own manifest lifecycle; it must not depend on
            // BookHelp.saveContent(), which is exclusively the text-body cache path.
            CacheManifestHelper.refreshAsync(book)
            CacheMediaWorkerRegistry.onFinished(lease)
        }
        val state = AudioCacheTaskManager.snapshot(task.bookUrl)
        if (state?.status == CacheTaskStatus.PAUSED) {
            if (!AudioCacheTaskManager.resume(
                    task.bookUrl,
                    chapterStarted,
                    chapterProgress,
                    chapterFinished,
                    finished,
                )
            ) {
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
            onChapterStarted = chapterStarted,
            onChapterProgress = chapterProgress,
            onChapterFinished = chapterFinished,
            onFinished = finished,
            diagnostics = CacheOperationDiagnostics.Context(
                domain = CacheOperationDiagnostics.Domain.MEDIA,
                sessionId = lease.sessionId,
                taskId = lease.taskId,
                generation = lease.generation,
                unitCount = validKeys.size,
            ),
        )
        if (!started) CacheMediaWorkerRegistry.fail(lease, "media task was already active")
    }

    fun pause(submission: CacheSubmission) {
        val task = CacheCoordinator.currentTask(submission) ?: return
        AudioCacheTaskManager.pause(task.bookUrl)
        CacheMediaWorkerRegistry.remove(submission.sessionId, submission.taskId)
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
        val bookUrl: String,
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
        bindings[task.bookUrl] = Binding(lease, task.bookUrl, expected)
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
                if (unit.status == CacheUnitStatus.PENDING) {
                    port.updateUnit(lease, unit.key, CacheUnitStatus.RUNNING)
                }
                port.updateUnit(lease, unit.key, CacheUnitStatus.FAILED, error)
            }
        if (port.finish(lease, CacheResult.FAILED, error)) {
            CacheCoordinator.notifyTaskFinished(lease, CacheResult.FAILED, error)
        }
        removeBinding(lease)
    }

    fun onChapterFinished(
        lease: CacheWorkerLease,
        chapterIndex: Int,
        success: Boolean,
        error: String?,
    ) {
        val binding = bindingFor(lease) ?: return
        if (!binding.expected.any { it.chapterIndex == chapterIndex }) return
        val key = binding.expected.first { it.chapterIndex == chapterIndex }
        requirePort().updateUnit(
            lease,
            key,
            if (success) CacheUnitStatus.SUCCEEDED else CacheUnitStatus.FAILED,
            error,
        )
    }

    fun onChapterStarted(lease: CacheWorkerLease, chapterIndex: Int) {
        val binding = bindingFor(lease) ?: return
        val key = binding.expected.firstOrNull { it.chapterIndex == chapterIndex } ?: return
        requirePort().updateProgress(
            binding.lease,
            key,
            CacheProgressMode.INDETERMINATE,
            current = 0L,
            total = null,
        )
    }

    fun onChapterProgress(
        lease: CacheWorkerLease,
        chapterIndex: Int,
        current: Long,
        total: Long?,
    ) {
        val binding = bindingFor(lease) ?: return
        val key = binding.expected.firstOrNull { it.chapterIndex == chapterIndex } ?: return
        requirePort().updateProgress(
            binding.lease,
            key,
            if (total == null) CacheProgressMode.INDETERMINATE else CacheProgressMode.BYTES,
            current = current,
            total = total,
        )
    }

    fun onFinished(lease: CacheWorkerLease) {
        val binding = bindingFor(lease) ?: return
        val state = AudioCacheTaskManager.snapshot(binding.bookUrl)
            ?: run {
                fail(lease, "media worker finished without a state")
                return
            }
        if (state.status == CacheTaskStatus.PAUSED) return
        val completed = state.completedChapters.coerceIn(0, binding.expected.size)
        val failed = state.status == CacheTaskStatus.FAILED || state.status == CacheTaskStatus.CANCELLED
        val current = CacheCoordinator.currentTask(CacheSubmission(lease.sessionId, lease.taskId))
        binding.expected.forEachIndexed { index, key ->
            val currentStatus = current?.units?.firstOrNull { it.key == key }?.status
            if (currentStatus == CacheUnitStatus.PENDING || currentStatus == CacheUnitStatus.RUNNING) {
                if (currentStatus == CacheUnitStatus.PENDING) {
                    requirePort().updateUnit(lease, key, CacheUnitStatus.RUNNING)
                }
                val status = if (index < completed && !failed) CacheUnitStatus.SUCCEEDED
                else CacheUnitStatus.FAILED
                requirePort().updateUnit(lease, key, status, state.message.takeIf { status == CacheUnitStatus.FAILED })
            }
        }
        val finishedTask = CacheCoordinator.currentTask(
            CacheSubmission(lease.sessionId, lease.taskId)
        )
        val unitFailed = finishedTask?.units?.any { it.status == CacheUnitStatus.FAILED } == true
        val result = if (failed || completed < binding.expected.size || unitFailed) {
            CacheResult.FAILED
        } else {
            CacheResult.SUCCEEDED
        }
        if (requirePort().finish(lease, result, state.message.takeIf { result == CacheResult.FAILED })) {
            CacheCoordinator.notifyTaskFinished(
                lease,
                result,
                state.message.takeIf { result == CacheResult.FAILED },
            )
        }
        removeBinding(lease)
    }

    private fun bindingFor(lease: CacheWorkerLease): Binding? {
        val binding = bindings.values.firstOrNull {
            it.lease.sessionId == lease.sessionId && it.lease.taskId == lease.taskId
        } ?: run {
            AppLogCacheLogSink.record(
                CacheLogEvent(
                    CacheLogEventType.STALE_UPDATE_DROPPED,
                    lease.sessionId,
                    lease.taskId,
                    "media binding missing",
                )
            )
            return null
        }
        if (binding.lease.generation != lease.generation) {
            AppLogCacheLogSink.record(
                CacheLogEvent(
                    CacheLogEventType.STALE_UPDATE_DROPPED,
                    lease.sessionId,
                    lease.taskId,
                    "media generation=${lease.generation}",
                )
            )
            return null
        }
        return binding
    }

    private fun removeBinding(lease: CacheWorkerLease) {
        bindings.entries.toList()
            .filter {
                it.value.lease.sessionId == lease.sessionId &&
                    it.value.lease.taskId == lease.taskId
            }
            .forEach { bindings.remove(it.key, it.value) }
    }

    private fun requirePort(): CacheWorkerPort =
        checkNotNull(workerPort) { "cache media worker registry is not bound" }

}
