package io.legado.app.help.cache

import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.help.book.isLocal
import io.legado.app.help.config.AppConfig
import io.legado.app.help.review.ReviewSnapshotManager
import io.legado.app.service.ReviewCacheService
import io.legado.app.data.appDb
import java.util.concurrent.ConcurrentHashMap

/** Coordinator adapter for the review queue hosted by ReviewCacheService. */
internal class ReviewWorkerAdapter(
    private val workerPort: CacheWorkerPort,
) {

    companion object {
        /** Compatibility entry for reader-triggered review caching. */
        internal fun enqueueLegacyIfEnabled(
            bookSource: BookSource?,
            book: Book,
            chapter: BookChapter,
            force: Boolean = false,
        ): Boolean {
            if (!AppConfig.syncCacheReview || book.isLocal || bookSource == null) return false
            ReviewSnapshotManager.enqueue(book, chapter, force)
            val started = ReviewCacheService.startSelf()
            if (!started) ReviewSnapshotManager.cancelBookTasks(book.bookUrl)
            return started
        }
    }

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
        task.units
            .filter { it.status == CacheUnitStatus.PENDING || it.status == CacheUnitStatus.REVIEW_ELIGIBLE }
            .forEach { unit ->
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
            .filter { key ->
                task.units.first { it.key == key }.status in setOf(
                    CacheUnitStatus.PENDING,
                    CacheUnitStatus.REVIEW_ELIGIBLE,
                    CacheUnitStatus.RUNNING,
                )
            }
            .forEach { key ->
                workerPort.updateUnit(
                    lease,
                    key,
                    CacheUnitStatus.FAILED,
                    "chapter not found: ${key.chapterIndex}",
                )
            }
        if (!CacheReviewWorkerRegistry.register(task, lease, validKeys)) {
            task.units
                .filter { it.status in setOf(
                    CacheUnitStatus.PENDING,
                    CacheUnitStatus.REVIEW_ELIGIBLE,
                    CacheUnitStatus.RUNNING,
                ) }
                .forEach { unit ->
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
            ReviewSnapshotManager.enqueue(
                book,
                chapter,
                force = true,
                executionLease = lease,
            )
        }
        if (!ReviewCacheService.startSelf()) {
            ReviewSnapshotManager.cancelBookTasks(book.bookUrl)
            CacheReviewWorkerRegistry.onServiceStartFailed(
                lease,
                "评论缓存宿主启动失败，任务未执行",
            )
        }
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

    fun hasCoordinatorTasks(): Boolean = bindings.isNotEmpty()

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
        val task = CacheCoordinator.currentTask(CacheSubmission(lease.sessionId, lease.taskId))
        keys.forEach { key ->
            val status = task?.units?.firstOrNull { it.key == key }?.status
            if (status == CacheUnitStatus.PENDING || status == CacheUnitStatus.REVIEW_ELIGIBLE) {
                requireWorkerPort().updateUnit(lease, key, CacheUnitStatus.RUNNING)
            }
            if (status == CacheUnitStatus.PENDING ||
                status == CacheUnitStatus.REVIEW_ELIGIBLE ||
                status == CacheUnitStatus.RUNNING
            ) {
                requireWorkerPort().updateUnit(lease, key, CacheUnitStatus.FAILED, error)
            }
        }
        finish(lease, failed = true, error = error)
    }

    fun finish(lease: CacheWorkerLease, failed: Boolean, error: String?) {
        val result = if (failed) CacheResult.FAILED else CacheResult.SUCCEEDED
        if (requireWorkerPort().finish(lease, result, error)) {
            CacheCoordinator.notifyTaskFinished(lease, result, error)
            bindings.remove(taskKey(lease))
        }
    }

    fun onServiceStartFailed(lease: CacheWorkerLease, error: String) {
        failTask(lease, error)
    }

    fun remove(sessionId: String, taskId: String) {
        bindings.remove("$sessionId/$taskId")
    }

    fun onChapterFinished(lease: CacheWorkerLease, chapterIndex: Int, success: Boolean) {
        val binding = bindingFor(lease, "review chapter=$chapterIndex") ?: return
        if (!binding.expected.any { it.chapterIndex == chapterIndex }) return
        val key = binding.expected.first { it.chapterIndex == chapterIndex }
        val accepted = synchronized(lock) {
            if (binding.completed.containsKey(key)) {
                false
            } else {
                binding.completed[key] = success
                true
            }
        }
        if (!accepted) return
        requireWorkerPort().updateUnit(
            binding.lease,
            key,
            if (success) CacheUnitStatus.SUCCEEDED else CacheUnitStatus.FAILED,
            if (success) null else "review worker reported failure",
        )
        finishIfComplete(binding)
    }

    /** A normal empty queue means chapters had no review work and therefore completed. */
    fun onServiceFinished(cancelled: Boolean = false) {
        val targets = synchronized(lock) { bindings.values.toList() }
        if (cancelled) {
            targets.forEach { binding ->
                failTask(binding.lease, "评论缓存宿主异常结束")
            }
            return
        }
        targets.forEach { binding ->
            val unfinished = synchronized(lock) { binding.expected - binding.completed.keys }
            unfinished.forEach { key ->
                onChapterFinished(binding.lease, key.chapterIndex, success = !cancelled)
            }
        }
    }

    private fun failTask(lease: CacheWorkerLease, error: String) {
        val finished = requireWorkerPort().finish(lease, CacheResult.FAILED, error)
        if (finished) {
            CacheCoordinator.notifyTaskFinished(lease, CacheResult.FAILED, error)
        }
        bindings.remove(taskKey(lease))
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

    private fun bindingFor(lease: CacheWorkerLease, detail: String): Binding? {
        val binding = bindings[taskKey(lease)] ?: run {
            val task = CacheCoordinator.currentTask(CacheSubmission(lease.sessionId, lease.taskId))
            if (task == null || CacheLifecycleRules.isTerminal(task.status)) return null
            AppLogCacheLogSink.record(
                CacheLogEvent(
                    CacheLogEventType.STALE_UPDATE_DROPPED,
                    lease.sessionId,
                    lease.taskId,
                    "$detail binding missing",
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
                    "$detail generation=${lease.generation}",
                )
            )
            return null
        }
        return binding
    }

    private fun requireWorkerPort(): CacheWorkerPort =
        checkNotNull(workerPort) { "cache review worker registry is not bound" }
}
