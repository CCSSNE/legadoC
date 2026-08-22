package io.legado.app.help.cache

import io.legado.app.data.appDb
import io.legado.app.help.book.BookHelp
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
        val valid = task.units.map { it.key }.filter { key ->
            appDb.bookChapterDao.getChapter(task.bookUrl, key.chapterIndex) != null
        }
        task.units.forEach { unit ->
            workerPort.updateUnit(lease, unit.key, CacheUnitStatus.RUNNING)
        }
        val pending = linkedSetOf<CacheUnitKey>()
        valid.forEach { key ->
            val chapter = appDb.bookChapterDao.getChapter(task.bookUrl, key.chapterIndex)
            if (chapter == null) {
                workerPort.updateUnit(
                    lease,
                    key,
                    CacheUnitStatus.FAILED,
                    "chapter not found: ${key.chapterIndex}",
                )
            } else if (chapter.isVolume || BookHelp.hasImageContent(book, chapter)) {
                workerPort.updateUnit(lease, key, CacheUnitStatus.SUCCEEDED)
            } else {
                pending += key
            }
        }
        task.units.filter { it.key !in valid }.forEach { unit ->
            workerPort.updateUnit(
                lease,
                unit.key,
                CacheUnitStatus.FAILED,
                "chapter not found: ${unit.key.chapterIndex}",
            )
        }
        if (pending.isEmpty()) {
            val failed = task.units.any { current ->
                val state = CacheCoordinator.currentTask(CacheSubmission(task.sessionId, task.taskId))
                    ?.units?.firstOrNull { it.key == current.key }
                state?.status == CacheUnitStatus.FAILED
            }
            finishBody(
                lease,
                if (failed) CacheResult.FAILED else CacheResult.SUCCEEDED,
                if (failed) "one or more chapters could not be resolved" else null,
            )
            return
        }
        registry.register(task, lease, pending)
        ranges(pending.map { it.chapterIndex }).forEach { (start, end) ->
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
        CacheBook.pauseDownload()
        CacheCoordinator.workerPort.confirmPaused(submission)
    }

    fun cancel(submission: CacheSubmission) {
        registry.remove(submission.sessionId, submission.taskId)
        CacheBook.stopAll()
        if (workerPort.confirmCancelled(submission)) {
            CacheCoordinator.notifyTaskFinished(submission, CacheResult.CANCELLED)
        }
    }

    private fun finishBody(
        lease: CacheWorkerLease,
        result: CacheResult,
        error: String?,
    ) {
        if (workerPort.finish(lease, result, error)) {
            CacheCoordinator.notifyTaskFinished(lease, result, error)
            val task = CacheCoordinator.currentTask(CacheSubmission(lease.sessionId, lease.taskId))
            if (task?.reviewEnabled == true) {
                CacheCoordinator.appendReviewTask(lease.sessionId, lease.taskId)
            }
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

    fun bind(workerPort: CacheWorkerPort) {
        this.workerPort = workerPort
    }

    fun register(task: CacheTaskState, lease: CacheWorkerLease, units: Set<CacheUnitKey>) {
        synchronized(lock) {
            bindings[taskKey(lease)] = Binding(lease, task.bookUrl, units)
        }
    }

    fun remove(sessionId: String, taskId: String) {
        bindings.remove("$sessionId/$taskId")
    }

    fun onChapterSuccess(bookUrl: String, chapterIndex: Int) {
        complete(bookUrl, chapterIndex, CacheUnitStatus.SUCCEEDED, null)
    }

    fun onChapterFailed(bookUrl: String, chapterIndex: Int, error: String?) {
        complete(bookUrl, chapterIndex, CacheUnitStatus.FAILED, error)
    }

    fun onStartRejected(lease: CacheWorkerLease, error: String) {
        requireWorkerPort().finish(lease, CacheResult.FAILED, error)
        bindings.remove(taskKey(lease))
    }

    fun onWorkerFinished(bookUrl: String? = null, error: String? = null) {
        val targets = synchronized(lock) {
            bindings.values.filter { bookUrl == null || it.bookUrl == bookUrl }.toList()
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
