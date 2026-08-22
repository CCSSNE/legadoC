package io.legado.app.help.cache

import io.legado.app.data.appDb
import io.legado.app.model.CacheBook
import splitties.init.appCtx
import java.util.concurrent.ConcurrentHashMap

/**
 * Coordinator adapter for the existing CacheBook execution core.
 * The legacy service remains the executor; it never receives a Store lease.
 */
internal class TextBodyWorkerAdapter(
    private val workerPort: CacheWorkerPort,
) {

    private val registry = CacheBodyWorkerRegistry.also { it.bind(workerPort) }

    fun start(task: CacheTaskState, lease: CacheWorkerLease) {
        require(task.kind == CacheKind.TEXT && task.phase == CachePhase.BODY) {
            "text body adapter received ${task.kind}/${task.phase}"
        }
        val book = appDb.bookDao.getBook(task.bookUrl)
            ?: error("book not found: ${task.bookUrl}")
        task.units.forEach { unit ->
            workerPort.updateUnit(lease, unit.key, CacheUnitStatus.RUNNING)
        }
        if (!registry.register(task, lease, task.units.map { it.key }.toSet())) {
            task.units.forEach { unit ->
                workerPort.updateUnit(
                    lease,
                    unit.key,
                    CacheUnitStatus.FAILED,
                    "another body task is active for this book",
                )
            }
            if (workerPort.finish(lease, CacheResult.FAILED, "another body task is active for this book")) {
                CacheCoordinator.notifyTaskFinished(
                    lease,
                    CacheResult.FAILED,
                    "another body task is active for this book",
                )
            }
            return
        }
        ranges(task.units.map { it.key.chapterIndex }).forEach { (start, end) ->
            CacheBook.start(
                appCtx,
                book,
                start,
                end,
                coordinatorSessionId = lease.sessionId,
                coordinatorTaskId = lease.taskId,
                coordinatorGeneration = lease.generation,
            )
        }
    }

    fun pause(submission: CacheSubmission) {
        val task = CacheCoordinator.currentTask(submission)
            ?: return
        registry.markPaused(submission.sessionId, submission.taskId)
        CacheBook.stop(task.bookUrl, releaseReviewPhase = false)
        CacheCoordinator.workerPort.confirmPaused(submission)
    }

    fun cancel(submission: CacheSubmission) {
        val task = CacheCoordinator.currentTask(submission)
            ?: return
        registry.remove(submission.sessionId, submission.taskId)
        CacheBook.stop(task.bookUrl, releaseReviewPhase = false)
        if (workerPort.confirmCancelled(submission)) {
            CacheCoordinator.notifyTaskFinished(submission, CacheResult.CANCELLED)
        }
    }

    private fun ranges(indexes: List<Int>): List<Pair<Int, Int>> {
        if (indexes.isEmpty()) return emptyList()
        val sorted = indexes.distinct().sorted()
        val result = ArrayList<Pair<Int, Int>>()
        var start = sorted.first()
        var end = start
        sorted.drop(1).forEach { index ->
            if (index == end + 1) {
                end = index
            } else {
                result += start to end
                start = index
                end = index
            }
        }
        result += start to end
        return result
    }
}

internal object CacheBodyWorkerRegistry {
    private var workerPort: CacheWorkerPort? = null
    private data class Binding(
        val lease: CacheWorkerLease,
        val bookUrl: String,
        val expected: Set<CacheUnitKey>,
        val completed: MutableMap<CacheUnitKey, CacheUnitStatus> = linkedMapOf(),
    )

    private val lock = Any()
    private val bindings = ConcurrentHashMap<String, Binding>()
    private val managedBooks = ConcurrentHashMap.newKeySet<String>()
    private val pausedBooks = ConcurrentHashMap.newKeySet<String>()

    fun bind(workerPort: CacheWorkerPort) {
        this.workerPort = workerPort
    }

    fun register(task: CacheTaskState, lease: CacheWorkerLease, units: Set<CacheUnitKey>): Boolean {
        synchronized(lock) {
            val existing = bindings.values.firstOrNull {
                it.bookUrl == task.bookUrl && it.lease.taskId != lease.taskId
            }
            if (existing != null) return false
            if (CacheBook.cacheBookMap[task.bookUrl]?.isRun() == true) return false
            bindings[taskKey(lease)] = Binding(lease, task.bookUrl, units)
            managedBooks.add(task.bookUrl)
            pausedBooks.remove(task.bookUrl)
        }
        return true
    }

    fun isCoordinatorManaged(bookUrl: String): Boolean = managedBooks.contains(bookUrl)

    fun isLeaseActive(lease: CacheWorkerLease): Boolean {
        val task = CacheCoordinator.currentTask(CacheSubmission(lease.sessionId, lease.taskId))
        return task != null &&
            managedBooks.contains(task.bookUrl) &&
            task.status == CacheLifecycle.RUNNING &&
            task.generation == lease.generation &&
            bindings[taskKey(lease)] != null
    }

    fun remove(sessionId: String, taskId: String) {
        val binding = bindings.remove("$sessionId/$taskId") ?: return
        io.legado.app.help.review.ReviewSnapshotManager.endBodyPhase(binding.bookUrl)
        managedBooks.remove(binding.bookUrl)
    }

    fun markPaused(sessionId: String, taskId: String) {
        bindings["$sessionId/$taskId"]?.let { pausedBooks.add(it.bookUrl) }
    }

    fun onChapterSuccess(bookUrl: String, chapterIndex: Int) {
        complete(bookUrl, chapterIndex, CacheUnitStatus.SUCCEEDED, null)
    }

    fun onChapterFailed(bookUrl: String, chapterIndex: Int, error: String?) {
        complete(bookUrl, chapterIndex, CacheUnitStatus.FAILED, error)
    }

    fun onStartRejected(lease: CacheWorkerLease, error: String) {
        if (requireWorkerPort().finish(lease, CacheResult.FAILED, error)) {
            CacheCoordinator.notifyTaskFinished(lease, CacheResult.FAILED, error)
        }
        val binding = bindings.remove(taskKey(lease))
        binding?.let {
            io.legado.app.help.review.ReviewSnapshotManager.endBodyPhase(it.bookUrl)
            managedBooks.remove(it.bookUrl)
        }
    }

    fun onWorkerFinished(bookUrl: String? = null, error: String? = null) {
        val books = synchronized(lock) {
            if (bookUrl != null) setOf(bookUrl) else managedBooks.toSet()
        }
        val paused = books.filter { pausedBooks.remove(it) }.toSet()
        paused.forEach { pausedBook ->
            bindings.entries
                .filter { it.value.bookUrl == pausedBook }
                .forEach { bindings.remove(it.key, it.value) }
            managedBooks.remove(pausedBook)
        }
        val targets = synchronized(lock) {
            bindings.values.filter {
                (bookUrl == null || it.bookUrl == bookUrl) && it.bookUrl !in paused
            }.toList()
        }
        targets.forEach { binding ->
            val unfinished = binding.expected - binding.completed.keys
            unfinished.forEach { key ->
                complete(
                    binding.bookUrl,
                    key.chapterIndex,
                    CacheUnitStatus.FAILED,
                    error ?: "body worker ended before chapter completion",
                )
            }
        }
        books.asSequence()
            .filterNot(paused::contains)
            .forEach { io.legado.app.help.review.ReviewSnapshotManager.endBodyPhase(it) }
        books.forEach(managedBooks::remove)
    }

    private fun complete(
        bookUrl: String,
        chapterIndex: Int,
        status: CacheUnitStatus,
        error: String?,
    ) {
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
                    binding.completed[key] = status
                    true
                }
            }
            if (!accepted) return@forEach
            requireWorkerPort().updateUnit(binding.lease, key, status, error)
            val done = synchronized(lock) { binding.completed.size == binding.expected.size }
            if (done) {
                val failed = synchronized(lock) {
                    binding.completed.values.any { it == CacheUnitStatus.FAILED }
                }
                val finished = requireWorkerPort().finish(
                    binding.lease,
                    if (failed) CacheResult.FAILED else CacheResult.SUCCEEDED,
                    if (failed) "one or more chapters failed" else null,
                )
                if (finished) {
                    CacheCoordinator.notifyTaskFinished(
                        binding.lease,
                        if (failed) CacheResult.FAILED else CacheResult.SUCCEEDED,
                        if (failed) "one or more chapters failed" else null,
                    )
                }
                if (finished && CacheCoordinator.currentTask(
                        CacheSubmission(binding.lease.sessionId, binding.lease.taskId)
                    )?.reviewEnabled == true
                ) {
                    CacheCoordinator.appendReviewTask(
                        binding.lease.sessionId,
                        binding.lease.taskId,
                    )
                }
                bindings.remove(taskKey(binding.lease))
            }
        }
    }

    private fun taskKey(lease: CacheWorkerLease): String = "${lease.sessionId}/${lease.taskId}"

    private fun requireWorkerPort(): CacheWorkerPort =
        checkNotNull(workerPort) { "cache body worker registry is not bound" }
}
