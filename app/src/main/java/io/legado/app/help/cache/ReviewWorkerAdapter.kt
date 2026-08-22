package io.legado.app.help.cache

import io.legado.app.help.review.ReviewSnapshotManager
import io.legado.app.service.ReviewCacheService
import io.legado.app.data.appDb
import java.util.concurrent.ConcurrentHashMap

/** Adapter for the existing ReviewSnapshotManager/ReviewCacheService worker. */
internal class ReviewWorkerAdapter(
    private val workerPort: CacheWorkerPort,
) {

    init {
        CacheReviewWorkerRegistry.bind(workerPort)
    }

    fun start(task: CacheTaskState, lease: CacheWorkerLease) {
        require(task.kind == CacheKind.TEXT && task.phase == CachePhase.REVIEW) {
            "review adapter received ${task.kind}/${task.phase}"
        }
        val book = appDb.bookDao.getBook(task.bookUrl)
            ?: run {
                CacheReviewWorkerRegistry.fail(
                    lease,
                    task.units.map { it.key }.toSet(),
                    "book not found: ${task.bookUrl}",
                )
                return
            }
        task.units.forEach { unit ->
            workerPort.updateUnit(lease, unit.key, CacheUnitStatus.RUNNING)
        }
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
        if (!CacheReviewWorkerRegistry.register(task, lease, validKeys)) {
            task.units.forEach { unit ->
                workerPort.updateUnit(
                    lease,
                    unit.key,
                    CacheUnitStatus.FAILED,
                    "another review task is active for this book",
                )
            }
            if (workerPort.finish(lease, CacheResult.FAILED, "another review task is active for this book")) {
                CacheCoordinator.notifyTaskFinished(
                    lease,
                    CacheResult.FAILED,
                    "another review task is active for this book",
                )
            }
            return
        }
        if (validKeys.isEmpty()) {
            CacheReviewWorkerRegistry.finish(
                lease,
                failed = true,
                error = "no review chapters found",
            )
            return
        }
        chapters.forEach { (_, chapter) ->
            ReviewSnapshotManager.enqueue(book, chapter, force = true)
        }
        ReviewCacheService.startSelf()
    }

    fun cancel(submission: CacheSubmission) {
        CacheReviewWorkerRegistry.remove(submission.sessionId, submission.taskId)
        val task = CacheCoordinator.currentTask(submission)
        if (task != null) {
            ReviewSnapshotManager.cancelBookTasks(task.bookUrl)
        }
        if (workerPort.confirmCancelled(submission)) {
            CacheCoordinator.notifyTaskFinished(submission, CacheResult.CANCELLED)
        }
    }

    fun pause(submission: CacheSubmission) {
        val task = CacheCoordinator.currentTask(submission)
            ?: return
        CacheReviewWorkerRegistry.remove(submission.sessionId, submission.taskId)
        ReviewSnapshotManager.cancelBookTasks(task.bookUrl)
        workerPort.confirmPaused(submission)
    }
}

internal object CacheReviewWorkerRegistry {
    private var workerPort: CacheWorkerPort? = null
    private data class Binding(
        val lease: CacheWorkerLease,
        val bookUrl: String,
        val expected: Set<CacheUnitKey>,
        val completed: MutableMap<CacheUnitKey, Boolean> = linkedMapOf(),
    )

    private val lock = Any()
    private val bindings = ConcurrentHashMap<String, Binding>()

    fun bind(workerPort: CacheWorkerPort) {
        this.workerPort = workerPort
    }

    fun register(
        task: CacheTaskState,
        lease: CacheWorkerLease,
        expected: Set<CacheUnitKey> = task.units.map { it.key }.toSet(),
    ): Boolean {
        synchronized(lock) {
            val existing = bindings.values.firstOrNull {
                it.bookUrl == task.bookUrl && it.lease.taskId != lease.taskId
            }
            if (existing != null) return false
            bindings[taskKey(lease)] = Binding(
                lease = lease,
                bookUrl = task.bookUrl,
                expected = expected,
            )
        }
        return true
    }

    fun fail(lease: CacheWorkerLease, keys: Set<CacheUnitKey>, error: String) {
        keys.forEach { key ->
            requireWorkerPort().updateUnit(lease, key, CacheUnitStatus.FAILED, error)
        }
        finish(lease, failed = true, error = error)
    }

    fun finish(lease: CacheWorkerLease, failed: Boolean, error: String?) {
        val result = if (failed) CacheResult.FAILED else CacheResult.SUCCEEDED
        if (requireWorkerPort().finish(lease, result, error)) {
            CacheCoordinator.notifyTaskFinished(lease, result, error)
        }
    }

    fun remove(sessionId: String, taskId: String) {
        bindings.remove("$sessionId/$taskId")
    }

    fun onChapterFinished(bookUrl: String, chapterIndex: Int, success: Boolean) {
        val targets = synchronized(lock) {
            bindings.values.filter {
                it.bookUrl == bookUrl && it.expected.any { key -> key.chapterIndex == chapterIndex }
            }.toList()
        }
        targets.forEach { binding ->
            val key = binding.expected.first { it.chapterIndex == chapterIndex }
            val accepted = synchronized(lock) {
                if (binding.completed.containsKey(key)) {
                    false
                } else {
                    binding.completed[key] = success
                    true
                }
            }
            if (!accepted) return@forEach
            requireWorkerPort().updateUnit(
                binding.lease,
                key,
                if (success) CacheUnitStatus.SUCCEEDED else CacheUnitStatus.FAILED,
                if (success) null else "review worker reported failure",
            )
            finishIfComplete(binding)
        }
    }

    /** A normal empty queue means chapters had no review work and therefore completed. */
    fun onServiceFinished(cancelled: Boolean = false) {
        val targets = synchronized(lock) { bindings.values.toList() }
        targets.forEach { binding ->
            val unfinished = synchronized(lock) { binding.expected - binding.completed.keys }
            unfinished.forEach { key ->
                onChapterFinished(binding.bookUrl, key.chapterIndex, success = !cancelled)
            }
        }
    }

    private fun finishIfComplete(binding: Binding) {
        val complete = synchronized(lock) { binding.completed.size == binding.expected.size }
        if (!complete) return
        val failed = synchronized(lock) { binding.completed.values.any { !it } }
        val finished = requireWorkerPort().finish(
            binding.lease,
            if (failed) CacheResult.FAILED else CacheResult.SUCCEEDED,
            if (failed) "one or more review chapters failed" else null,
        )
        if (finished) {
            CacheCoordinator.notifyTaskFinished(
                binding.lease,
                if (failed) CacheResult.FAILED else CacheResult.SUCCEEDED,
                if (failed) "one or more review chapters failed" else null,
            )
            bindings.remove(taskKey(binding.lease))
        }
    }

    private fun taskKey(lease: CacheWorkerLease): String = "${lease.sessionId}/${lease.taskId}"

    private fun requireWorkerPort(): CacheWorkerPort =
        checkNotNull(workerPort) { "cache review worker registry is not bound" }
}
