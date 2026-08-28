package io.legado.app.help.cache

import io.legado.app.data.appDb
import io.legado.app.help.book.isAudio
import io.legado.app.help.book.isVideo
import io.legado.app.help.config.AppConfig
import io.legado.app.help.tts.TtsCacheManager
import io.legado.app.service.TtsCacheService
import java.util.concurrent.ConcurrentHashMap

/** Coordinator adapter for the TTS queue hosted by TtsCacheService. */
internal class TtsWorkerAdapter(
    private val workerPort: CacheWorkerPort,
) {

    init {
        CacheTtsWorkerRegistry.bind(workerPort)
    }

    fun start(task: CacheTaskState, lease: CacheWorkerLease) {
        require(task.phase == CachePhase.TTS && task.kind.ttsPrerequisitePhase() != null) {
            "tts adapter received ${task.kind}/${task.phase}"
        }
        require(AppConfig.ttsWavMode) { "tts cache requires TTS-Wav mode" }
        val runnableUnits = task.runnableUnits()
        if (runnableUnits.isEmpty()) {
            workerPort.finish(lease, CacheResult.SUCCEEDED)
            return
        }
        val book = appDb.bookDao.getBook(task.bookUrl)
            ?: run {
                CacheTtsWorkerRegistry.fail(
                    lease,
                    runnableUnits.map { it.key }.toSet(),
                    "book not found: ${task.bookUrl}",
                )
                return
            }
        require(!book.isAudio && !book.isVideo) {
            "tts task requires a text book: ${task.bookUrl}"
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
            .forEach { key ->
                workerPort.updateUnit(
                    lease,
                    key,
                    CacheUnitStatus.FAILED,
                    "chapter not found: ${key.chapterIndex}",
                )
            }
        if (!CacheTtsWorkerRegistry.register(task, lease, validKeys)) {
            val detail = "another tts task is active for this book"
            workerPort.skip(lease, CacheTaskSkipReason.ALREADY_RUNNING, detail)
            return
        }
        if (validKeys.isEmpty()) {
            CacheTtsWorkerRegistry.finish(
                lease,
                failed = true,
                error = "no tts chapters found",
            )
            return
        }
        chapters.forEach { (_, chapter) ->
            TtsCacheManager.enqueue(
                book,
                chapter,
                executionLease = lease,
                commitIfLeaseActive = { action -> workerPort.commitIfLeaseActive(lease, action) },
                reportProgress = { processedUnits, totalUnits, failedUnits ->
                    CacheTtsWorkerRegistry.onUnitProgress(
                        lease,
                        chapter.index,
                        processedUnits,
                        totalUnits,
                        failedUnits,
                    )
                },
            )
        }
        if (!TtsCacheService.startSelf()) {
            TtsCacheManager.stopTask(lease.sessionId, lease.taskId) {
                CacheTtsWorkerRegistry.onServiceStartFailed(
                    lease,
                    "TTS缓存宿主启动失败，任务未执行",
                )
            }
        }
    }

    fun cancel(submission: CacheSubmission) {
        TtsCacheManager.stopTask(submission.sessionId, submission.taskId) {
            CacheTtsWorkerRegistry.remove(submission.sessionId, submission.taskId)
            workerPort.confirmCancelled(submission)
        }
    }

    fun pause(submission: CacheSubmission) {
        if (CacheCoordinator.currentTask(submission) == null) return
        TtsCacheManager.stopTask(submission.sessionId, submission.taskId) {
            CacheTtsWorkerRegistry.remove(submission.sessionId, submission.taskId)
            workerPort.confirmPaused(submission)
        }
    }
}

internal object CacheTtsWorkerRegistry {
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

    /** 同一本书同一时刻只允许一个 TTS 任务持有章节（book 级去重）。 */
    fun register(
        task: CacheTaskState,
        lease: CacheWorkerLease,
        expected: Set<CacheUnitKey>,
    ): Boolean {
        synchronized(lock) {
            val existing = bindings.values.firstOrNull {
                it.bookUrl == task.bookUrl && it.lease.taskId != lease.taskId
            }
            if (existing != null) return false
            bindings[taskKey(lease)] = Binding(lease, task.bookUrl, expected)
        }
        return true
    }

    fun fail(lease: CacheWorkerLease, keys: Set<CacheUnitKey>, error: String) {
        val task = CacheCoordinator.currentTask(CacheSubmission(lease.sessionId, lease.taskId))
        keys.forEach { key ->
            val status = task?.units?.firstOrNull { it.key == key }?.status
            if (status == CacheUnitStatus.PENDING) {
                requireWorkerPort().updateUnit(lease, key, CacheUnitStatus.RUNNING)
            }
            if (status == CacheUnitStatus.PENDING || status == CacheUnitStatus.RUNNING) {
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
        val binding = bindingFor(lease, "tts chapter=$chapterIndex") ?: return
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
            if (success) null else "tts worker reported failure",
        )
        finishIfComplete(binding)
    }

    fun onUnitProgress(
        lease: CacheWorkerLease,
        chapterIndex: Int,
        processedUnits: Int,
        totalUnits: Int,
        failedUnits: Int,
    ) {
        val binding = bindingFor(lease, "tts progress chapter=$chapterIndex") ?: return
        val key = binding.expected.firstOrNull { it.chapterIndex == chapterIndex } ?: return
        require(totalUnits >= 0) { "tts unit total must not be negative" }
        require(failedUnits in 0..totalUnits) {
            "tts unit failed count is invalid: $failedUnits/$totalUnits"
        }
        require(processedUnits in 0..totalUnits) {
            "tts unit progress is invalid: $processedUnits/$totalUnits"
        }
        require(failedUnits <= processedUnits) {
            "tts unit failures exceed processed count: $failedUnits/$processedUnits"
        }
        requireWorkerPort().updateProgress(
            binding.lease,
            key,
            CacheProgressMode.UNITS,
            current = processedUnits.toLong(),
            total = totalUnits.toLong(),
            failed = failedUnits.toLong(),
        )
    }

    /** A normal empty queue means chapters had no tts work and therefore completed. */
    fun onServiceFinished(cancelled: Boolean = false) {
        val targets = synchronized(lock) { bindings.values.toList() }
        if (cancelled) {
            targets.forEach { binding ->
                failTask(binding.lease, "TTS缓存宿主异常结束")
            }
            return
        }
        targets.forEach { binding ->
            val unfinished = synchronized(lock) { binding.expected - binding.completed.keys }
            unfinished.forEach { key ->
                onChapterFinished(binding.lease, key.chapterIndex, success = true)
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
                if (failed) "one or more tts chapters failed" else null,
            )
        ) {
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
        checkNotNull(workerPort) { "cache tts worker registry is not bound" }
}
