package io.legado.app.help.cache

import io.legado.app.help.review.ReviewResourceEpoch
import io.legado.app.help.review.ReviewSnapshotManager
import io.legado.app.data.appDb
import io.legado.app.help.book.isAudio
import io.legado.app.help.book.isVideo
import io.legado.app.service.ReviewCacheService
import java.util.concurrent.ConcurrentHashMap

/** Coordinator adapter for the review queue hosted by ReviewCacheService. */
internal class ReviewWorkerAdapter(
    private val workerPort: CacheWorkerPort,
) {

    init {
        CacheReviewWorkerRegistry.bind(workerPort)
    }

    fun start(task: CacheTaskState, lease: CacheWorkerLease) {
        require(task.phase == CachePhase.REVIEW && task.kind.reviewPrerequisitePhase() != null) {
            "review adapter received ${task.kind}/${task.phase}"
        }
        // REVIEW worker 真正启动即推进资源 GC 的版本号：任何一次启动都会让
        // 正在扫描的 GC 因 epoch 变化而放弃，杜绝“快速开始又快速结束”的 ABA 误删。
        val runnableUnits = task.runnableUnits()
        if (runnableUnits.isEmpty()) {
            workerPort.finish(lease, CacheResult.SUCCEEDED)
            return
        }
        ReviewResourceEpoch.markReviewStarted()
        val book = appDb.bookDao.getBook(task.bookUrl)
            ?: run {
                CacheReviewWorkerRegistry.fail(
                    lease,
                    runnableUnits.map { it.key }.toSet(),
                    "book not found: ${task.bookUrl}",
                )
                return
            }
        val bookKind = when {
            book.isAudio -> CacheKind.AUDIO
            book.isVideo -> CacheKind.VIDEO
            else -> CacheKind.TEXT
        }
        require(task.kind == bookKind) {
            "review task kind ${task.kind} does not match book kind $bookKind"
        }
        runnableUnits
            .forEach { unit ->
                workerPort.updateUnit(lease, unit.key, CacheUnitStatus.RUNNING)
            }
        val chapters = runnableUnits.mapNotNull { unit ->
            appDb.bookChapterDao.getChapter(task.bookUrl, unit.key.chapterIndex)
                ?.let { unit.key to it }
        }
        val validKeys = chapters.mapTo(linkedSetOf()) { it.first }
        runnableUnits.asSequence()
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
            val error = "review chapter is already owned by another Coordinator task"
            runnableUnits
                .forEach { unit ->
                    workerPort.updateUnit(lease, unit.key, CacheUnitStatus.FAILED, error)
                }
            workerPort.finish(lease, CacheResult.FAILED, error)
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
        val retryTargetsByUnit = task.reviewRetryTargets.associateBy { it.unitKey }
        chapters.forEach { (unitKey, chapter) ->
            val retryButtonSources = retryTargetsByUnit[unitKey]
                ?.buttonSources
                ?.toSet()
            ReviewSnapshotManager.enqueue(
                book,
                chapter,
                // A management retry carries exact failed button identities. It must not
                // become a chapter-wide force refresh merely because it is a REVIEW task.
                force = retryButtonSources == null && (
                    task.source != CacheRequestSource.READER ||
                        ReviewSnapshotManager.isUserRefreshActive(book.bookUrl, chapter.index)
                    ),
                retryButtonSources = retryButtonSources,
                executionLease = lease,
                commitIfLeaseActive = { action -> workerPort.commitIfLeaseActive(lease, action) },
                reportProgress = { processedSnapshots, totalSnapshots, failedSnapshots ->
                    CacheReviewWorkerRegistry.onSnapshotProgress(
                        lease,
                        chapter.index,
                        processedSnapshots,
                        totalSnapshots,
                        failedSnapshots,
                    )
                },
            )
        }
        if (!ReviewCacheService.startSelf()) {
            ReviewSnapshotManager.stopTask(lease.sessionId, lease.taskId) {
                CacheReviewWorkerRegistry.onServiceStartFailed(
                    lease,
                    "评论缓存宿主启动失败，任务未执行",
                )
            }
        }
    }

    fun cancel(submission: CacheSubmission) {
        ReviewSnapshotManager.stopTask(submission.sessionId, submission.taskId) {
            CacheReviewWorkerRegistry.remove(submission.sessionId, submission.taskId)
            workerPort.confirmCancelled(submission)
        }
    }

    fun pause(submission: CacheSubmission) {
        if (CacheCoordinator.currentTask(submission) == null) return
        ReviewSnapshotManager.stopTask(submission.sessionId, submission.taskId) {
            CacheReviewWorkerRegistry.remove(submission.sessionId, submission.taskId)
            workerPort.confirmPaused(submission)
        }
    }
}

internal object CacheReviewWorkerRegistry {
    private var workerPort: CacheWorkerPort? = null
    private data class Binding(
        val lease: CacheWorkerLease,
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
            val conflict = bindings.values.any { binding ->
                binding.lease.taskId != lease.taskId &&
                    binding.expected.any(expected::contains)
            }
            if (conflict) return false
            bindings[taskKey(lease)] = Binding(
                lease = lease,
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

    fun onSnapshotProgress(
        lease: CacheWorkerLease,
        chapterIndex: Int,
        processedSnapshots: Int,
        totalSnapshots: Int,
        failedSnapshots: Int,
    ) {
        val binding = bindingFor(lease, "review progress chapter=$chapterIndex") ?: return
        val key = binding.expected.firstOrNull { it.chapterIndex == chapterIndex } ?: return
        require(totalSnapshots >= 0) { "review snapshot total must not be negative" }
        require(failedSnapshots >= 0) { "review snapshot failed count must not be negative" }
        require(failedSnapshots <= totalSnapshots) {
            "review snapshot failed count exceeds total: $failedSnapshots/$totalSnapshots"
        }
        require(processedSnapshots in 0..totalSnapshots) {
            "review snapshot progress is invalid: $processedSnapshots/$totalSnapshots"
        }
        require(failedSnapshots <= processedSnapshots) {
            "review snapshot failures exceed processed count: $failedSnapshots/$processedSnapshots"
        }
        requireWorkerPort().updateProgress(
            binding.lease,
            key,
            CacheProgressMode.SNAPSHOTS,
            current = processedSnapshots.toLong(),
            total = totalSnapshots.toLong(),
            failed = failedSnapshots.toLong(),
        )
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
        requireWorkerPort().finish(lease, CacheResult.FAILED, error)
        bindings.remove(taskKey(lease))
    }

    private fun finishIfComplete(binding: Binding) {
        val complete = synchronized(lock) { binding.completed.size == binding.expected.size }
        if (!complete) return
        val failed = synchronized(lock) { binding.completed.values.any { !it } }
        if (requireWorkerPort().finish(
            binding.lease,
            if (failed) CacheResult.FAILED else CacheResult.SUCCEEDED,
            if (failed) "one or more review chapters failed" else null,
        )) {
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
