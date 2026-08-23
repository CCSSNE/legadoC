package io.legado.app.help.cache

import io.legado.app.data.appDb
import io.legado.app.model.CacheBook
import splitties.init.appCtx
import java.util.concurrent.ConcurrentHashMap

/**
 * Coordinator adapter for the existing CacheBook execution core.
 * CacheBookService is only the Android execution host; it never owns Store state.
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
        if (!registry.register(task, lease, task.units.map { it.key }.toSet())) {
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
        registry.remove(submission.sessionId, submission.taskId)
        CacheBook.stop(task.bookUrl)
        CacheCoordinator.workerPort.confirmPaused(submission)
    }

    fun cancel(submission: CacheSubmission) {
        val task = CacheCoordinator.currentTask(submission)
            ?: return
        registry.remove(submission.sessionId, submission.taskId)
        CacheBook.stop(task.bookUrl)
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
        val started: MutableSet<CacheUnitKey> = linkedSetOf(),
        val completed: MutableMap<CacheUnitKey, CacheUnitStatus> = linkedMapOf(),
    )

    private val lock = Any()
    private val bindings = ConcurrentHashMap<String, Binding>()
    private val managedBooks = ConcurrentHashMap.newKeySet<String>()

    fun bind(workerPort: CacheWorkerPort) {
        this.workerPort = workerPort
    }

    fun register(task: CacheTaskState, lease: CacheWorkerLease, units: Set<CacheUnitKey>): Boolean {
        synchronized(lock) {
            val existing = bindings.values.firstOrNull {
                it.bookUrl == task.bookUrl && it.lease.taskId != lease.taskId
            }
            if (existing != null) return false
            if (CacheBook.hasActiveBook(task.bookUrl)) return false
            bindings[taskKey(lease)] = Binding(lease, task.bookUrl, units)
            managedBooks.add(task.bookUrl)
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
        removeBinding(sessionId, taskId)
    }

    fun onChapterSuccess(lease: CacheWorkerLease, chapterIndex: Int) {
        complete(lease, chapterIndex, CacheUnitStatus.SUCCEEDED, null)
    }

    fun onChapterFailed(lease: CacheWorkerLease, chapterIndex: Int, error: String?) {
        complete(lease, chapterIndex, CacheUnitStatus.FAILED, error)
    }

    /** A unit enters RUNNING only after the body executor has selected it for work. */
    fun onChapterStarted(lease: CacheWorkerLease, chapterIndex: Int) {
        val binding = bindingFor(lease, "body chapter=$chapterIndex") ?: return
        val key = binding.expected.firstOrNull { it.chapterIndex == chapterIndex } ?: return
        val started = synchronized(lock) {
            !binding.completed.containsKey(key) && binding.started.add(key)
        }
        if (started) {
            requireWorkerPort().updateUnit(binding.lease, key, CacheUnitStatus.RUNNING)
        }
    }

    fun onStartRejected(lease: CacheWorkerLease, error: String) {
        onExecutionFailed(lease, error)
    }

    /** Fail the task itself when the Android host dies before normal unit completion. */
    fun onExecutionFailed(lease: CacheWorkerLease, error: String) {
        val binding = bindings[taskKey(lease)]
        if (binding != null && binding.lease.generation != lease.generation) {
            logStale(lease, "body execution failed generation=${lease.generation}")
            return
        }
        val task = CacheCoordinator.currentTask(CacheSubmission(lease.sessionId, lease.taskId))
        if (task == null || task.status != CacheLifecycle.RUNNING || task.generation != lease.generation) {
            return
        }
        if (requireWorkerPort().finish(lease, CacheResult.FAILED, error)) {
            CacheCoordinator.notifyTaskFinished(lease, CacheResult.FAILED, error)
        }
        removeBinding(lease)
    }

    fun onWorkerFinished(lease: CacheWorkerLease, error: String? = null) {
        val binding = bindings[taskKey(lease)] ?: run {
            val task = CacheCoordinator.currentTask(CacheSubmission(lease.sessionId, lease.taskId))
            if (task == null) return
            if (CacheLifecycleRules.isTerminal(task.status)) {
                managedBooks.remove(task.bookUrl)
                return
            }
            logStale(lease, "body worker finished binding missing")
            return
        }
        if (binding.lease.generation != lease.generation) {
            logStale(lease, "body worker finished generation=${lease.generation}")
            return
        }
        val unfinished = binding.expected - binding.completed.keys
        if (unfinished.isNotEmpty()) {
            val failure = error ?: "body worker ended before chapter completion"
            if (requireWorkerPort().finish(lease, CacheResult.FAILED, failure)) {
                CacheCoordinator.notifyTaskFinished(lease, CacheResult.FAILED, failure)
            }
        }
        removeBinding(lease)
        managedBooks.remove(binding.bookUrl)
    }

    private fun complete(
        lease: CacheWorkerLease,
        chapterIndex: Int,
        status: CacheUnitStatus,
        error: String?,
    ) {
        val binding = bindingFor(lease, "body chapter=$chapterIndex") ?: return
        if (!binding.expected.any { it.chapterIndex == chapterIndex }) return
        val key = binding.expected.first { it.chapterIndex == chapterIndex }
        val accepted = synchronized(lock) {
            if (binding.completed.containsKey(key)) {
                false
            } else {
                binding.completed[key] = status
                true
            }
        }
        if (!accepted) return
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
            removeBinding(binding.lease)
        }
    }

    private fun taskKey(lease: CacheWorkerLease): String = "${lease.sessionId}/${lease.taskId}"

    private fun bindingFor(lease: CacheWorkerLease, detail: String): Binding? {
        val binding = bindings[taskKey(lease)] ?: run {
            logStale(lease, "$detail binding missing")
            return null
        }
        if (binding.lease.generation != lease.generation) {
            logStale(lease, "$detail generation=${lease.generation}")
            return null
        }
        return binding
    }

    private fun logStale(lease: CacheWorkerLease, detail: String) {
        AppLogCacheLogSink.record(
            CacheLogEvent(
                CacheLogEventType.STALE_UPDATE_DROPPED,
                lease.sessionId,
                lease.taskId,
                detail,
            )
        )
    }

    private fun requireWorkerPort(): CacheWorkerPort =
        checkNotNull(workerPort) { "cache body worker registry is not bound" }

    private fun removeBinding(lease: CacheWorkerLease) {
        removeBinding(lease.sessionId, lease.taskId)
    }

    private fun removeBinding(sessionId: String, taskId: String) {
        bindings.remove("$sessionId/$taskId")?.let { managedBooks.remove(it.bookUrl) }
    }
}
