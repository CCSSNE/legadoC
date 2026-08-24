package io.legado.app.help.cache

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.UUID

/**
 * The single in-process source of cache task state.
 *
 * A small snapshot persistence layer survives process death. Workers never
 * mutate a task directly; every update must carry the lease generation.
 */
internal class CacheTaskStore(
    private val logSink: CacheLogSink = AppLogCacheLogSink,
    private val persistence: CacheTaskPersistence = AppFileCacheTaskPersistence,
    private val onPublished: (CacheSnapshot, CacheProgressSnapshot) -> Unit = { _, _ -> },
) {

    private val lock = Any()
    private val sessions = LinkedHashMap<String, CacheSessionState>()
    private val _snapshot = MutableStateFlow(CacheSnapshot())
    val snapshot: StateFlow<CacheSnapshot> = _snapshot.asStateFlow()
    private val progressByKey = LinkedHashMap<ProgressKey, CacheProgressState>()
    private var displaySessionId: String? = null
    private var displayProgressKey: ProgressKey? = null
    private val _progress = MutableStateFlow(CacheProgressSnapshot())
    val progress: StateFlow<CacheProgressSnapshot> = _progress.asStateFlow()
    private val persistenceExecutor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "cache-task-persistence").apply { isDaemon = true }
    }
    private var pendingCheckpoint: ScheduledFuture<*>? = null
    private var persistenceVersion = 0L

    private data class ProgressKey(
        val sessionId: String,
        val taskId: String,
        val generation: Long,
        val unitKey: CacheUnitKey?,
    )

    init {
        val loaded = persistence.load()
        loaded.onFailure { error ->
            record(
                CacheLogEventType.PERSISTENCE_LOAD_FAILED,
                detail = "load=${error.localizedMessage}",
            )
            persistence.recoverLoadFailure().onFailure { recoveryError ->
                record(
                    CacheLogEventType.PERSISTENCE_LOAD_FAILED,
                    detail = "quarantine=${recoveryError.localizedMessage}",
                )
            }
        }
        loaded.getOrNull()?.let { restore(it) }
    }

    fun createSession(title: String): CacheSessionState {
        val now = System.currentTimeMillis()
        val session = CacheSessionState(
            sessionId = UUID.randomUUID().toString(),
            title = title,
            createdAt = now,
            updatedAt = now,
        )
        val trace = CacheOperationDiagnostics.begin(
            CacheOperationDiagnostics.Context(
                domain = CacheOperationDiagnostics.Domain.STORE,
                sessionId = session.sessionId,
            ),
            "SESSION_CREATE",
        )
        try {
            synchronized(lock) {
                sessions[session.sessionId] = session
                publishLocked()
            }
            trace.done(CacheOperationDiagnostics.Metrics(sessionCount = 1))
        } catch (error: Throwable) {
            trace.fail(error)
            throw error
        }
        record(CacheLogEventType.SESSION_CREATED, session.sessionId, detail = title)
        return session
    }

    fun addTask(sessionId: String, request: CacheRequest): CacheTaskState {
        require(request.units.isNotEmpty()) { "cache request must contain at least one unit" }
        val now = System.currentTimeMillis()
        val task = CacheTaskState(
            taskId = UUID.randomUUID().toString(),
            sessionId = sessionId,
            source = request.source,
            kind = request.kind,
            phase = request.phase,
            bookUrl = request.bookUrl,
            bookName = request.bookName,
            units = request.units.distinct().map { CacheUnitState(it, updatedAt = now) },
            reviewEnabled = request.reviewEnabled,
            reviewRetryTargets = request.reviewRetryTargets,
            updatedAt = now,
        )
        val trace = CacheOperationDiagnostics.begin(
            CacheOperationDiagnostics.Context(
                domain = CacheOperationDiagnostics.Domain.STORE,
                sessionId = sessionId,
                taskId = task.taskId,
                unitCount = task.units.size,
            ),
            "TASK_SUBMIT",
            CacheOperationDiagnostics.Metrics(unitCount = task.units.size),
        )
        try {
            synchronized(lock) {
                val session = requireSessionLocked(sessionId)
                sessions[sessionId] = aggregateSession(session.copy(
                    tasks = session.tasks + task,
                    updatedAt = now,
                ))
                publishLocked()
            }
            trace.done(CacheOperationDiagnostics.Metrics(unitCount = task.units.size))
        } catch (error: Throwable) {
            trace.fail(error, CacheOperationDiagnostics.Metrics(unitCount = task.units.size))
            throw error
        }
        record(
            CacheLogEventType.TASK_QUEUED,
            sessionId,
            task.taskId,
            detail = "kind=${task.kind} phase=${task.phase} units=${task.units.size}",
        )
        return task
    }

    /** Acquire the first worker lease for a queued task. */
    fun acquireWorker(sessionId: String, taskId: String): CacheWorkerLease? {
        synchronized(lock) {
            val task = requireTaskLocked(sessionId, taskId)
            if (task.status != CacheLifecycle.QUEUED) return null
            val lease = attachWorkerLocked(task)
            publishLocked()
            record(CacheLogEventType.TASK_STARTED, sessionId, taskId, "generation=${lease.generation}")
            return lease
        }
    }

    fun failQueuedTask(sessionId: String, taskId: String, error: String): Boolean {
        synchronized(lock) {
            val task = requireTaskLocked(sessionId, taskId)
            if (task.status != CacheLifecycle.QUEUED) return false
            check(CacheLifecycleRules.canTransition(task.status, CacheLifecycle.FAILED)) {
                "invalid cache transition ${task.status} -> ${CacheLifecycle.FAILED}"
            }
            replaceTaskLocked(task.copy(
                units = closeUnfinishedUnits(
                    task.units,
                    status = CacheUnitStatus.FAILED,
                    error = error,
                ),
                status = CacheLifecycle.FAILED,
                result = CacheResult.FAILED,
                error = error,
                terminalEffectsPending = true,
                updatedAt = System.currentTimeMillis(),
            ))
            removeTaskProgressLocked(sessionId, taskId)
            publishLocked()
        }
        record(CacheLogEventType.TASK_FINISHED, sessionId, taskId, "result=FAILED error=$error")
        return true
    }

    /** Reclaim a task after a worker/service restart. This invalidates the old lease. */
    fun reclaimWorker(sessionId: String, taskId: String): CacheWorkerLease? {
        synchronized(lock) {
            val task = requireTaskLocked(sessionId, taskId)
            if (CacheLifecycleRules.isTerminal(task.status) ||
                task.status == CacheLifecycle.CANCELLING
            ) {
                return null
            }
            val lease = attachWorkerLocked(task)
            publishLocked()
            record(CacheLogEventType.TASK_RECLAIMED, sessionId, taskId, "generation=${lease.generation}")
            return lease
        }
    }

    fun pauseTask(sessionId: String, taskId: String): Boolean {
        synchronized(lock) {
            val task = requireTaskLocked(sessionId, taskId)
            if (task.status != CacheLifecycle.RUNNING) return false
            replaceTaskLocked(task.copy(
                status = CacheLifecycle.PAUSING,
                generation = task.generation + 1,
                updatedAt = System.currentTimeMillis(),
            ))
            removeTaskProgressLocked(sessionId, taskId)
            publishLocked()
        }
        record(CacheLogEventType.TASK_PAUSING, sessionId, taskId)
        return true
    }

    fun confirmPaused(sessionId: String, taskId: String): Boolean {
        synchronized(lock) {
            val task = requireTaskLocked(sessionId, taskId)
            if (task.status != CacheLifecycle.PAUSING) return false
            replaceTaskLocked(task.copy(
                status = CacheLifecycle.PAUSED,
                updatedAt = System.currentTimeMillis(),
            ))
            publishLocked()
        }
        record(CacheLogEventType.TASK_PAUSED, sessionId, taskId)
        return true
    }

    /** Resume returns a new lease so the previous worker cannot write after a restart. */
    fun resumeTask(sessionId: String, taskId: String): CacheWorkerLease? {
        synchronized(lock) {
            val task = requireTaskLocked(sessionId, taskId)
            if (task.status != CacheLifecycle.PAUSED) return null
            val lease = attachWorkerLocked(task)
            publishLocked()
            record(CacheLogEventType.TASK_RESUMED, sessionId, taskId, "generation=${lease.generation}")
            return lease
        }
    }

    /** Invalidate the current worker before resources are cancelled. */
    fun beginCancel(sessionId: String, taskId: String): Boolean {
        synchronized(lock) {
            val task = requireTaskLocked(sessionId, taskId)
            if (CacheLifecycleRules.isTerminal(task.status) ||
                task.status == CacheLifecycle.CANCELLING
            ) {
                return false
            }
            check(CacheLifecycleRules.canTransition(task.status, CacheLifecycle.CANCELLING)) {
                "invalid cache transition ${task.status} -> CANCELLING"
            }
            replaceTaskLocked(task.copy(
                status = CacheLifecycle.CANCELLING,
                generation = task.generation + 1,
                updatedAt = System.currentTimeMillis(),
            ))
            removeTaskProgressLocked(sessionId, taskId)
            publishLocked()
        }
        record(CacheLogEventType.TASK_CANCELLING, sessionId, taskId)
        return true
    }

    fun confirmCancelled(sessionId: String, taskId: String): Boolean {
        synchronized(lock) {
            val task = requireTaskLocked(sessionId, taskId)
            if (task.status != CacheLifecycle.CANCELLING) return false
            replaceTaskLocked(task.copy(
                units = closeUnfinishedUnits(
                    task.units,
                    status = CacheUnitStatus.CANCELLED,
                    error = "task cancelled",
                ),
                status = CacheLifecycle.CANCELLED,
                result = CacheResult.CANCELLED,
                terminalEffectsPending = true,
                updatedAt = System.currentTimeMillis(),
            ))
            removeTaskProgressLocked(sessionId, taskId)
            publishLocked()
        }
        record(CacheLogEventType.TASK_CANCELLED, sessionId, taskId)
        return true
    }

    fun updateUnit(
        lease: CacheWorkerLease,
        key: CacheUnitKey,
        status: CacheUnitStatus,
        error: String? = null,
    ): Boolean {
        synchronized(lock) {
            val task = requireTaskLocked(lease.sessionId, lease.taskId)
            if (!isCurrentLease(task, lease) || task.status != CacheLifecycle.RUNNING) {
                logStaleUpdate(lease, "unit=${key.chapterIndex} status=$status")
                return false
            }
            val index = task.units.indexOfFirst { it.key == key }
            require(index >= 0) { "unit is not part of task: $key" }
            val previous = task.units[index]
            check(canUpdateUnit(previous.status, status)) {
                "invalid cache unit transition ${previous.status} -> $status"
            }
            val units = task.units.toMutableList().apply {
                this[index] = previous.copy(
                    status = status,
                    error = error,
                    updatedAt = System.currentTimeMillis(),
                )
            }
            val updatedTask = task.copy(units = units, updatedAt = System.currentTimeMillis())
            replaceTaskLocked(updatedTask)
            if (updatedTask.kind == CacheKind.TEXT && updatedTask.phase == CachePhase.BODY) {
                updateChapterProgressLocked(updatedTask, lease)
            }
            if (isTerminalUnitStatus(status)) {
                progressByKey.remove(progressKey(lease, key))
            }
            reconcileProgressLocked()
            publishLocked(persist = false)
        }
        record(
            if (status == CacheUnitStatus.FAILED) {
                CacheLogEventType.UNIT_FAILED
            } else {
                CacheLogEventType.UNIT_UPDATED
            },
            lease.sessionId,
            lease.taskId,
            "chapter=${key.chapterIndex} status=$status${error?.let { " error=$it" }.orEmpty()}",
        )
        return true
    }

    /**
     * Accept an executor progress report only for the currently owned generation. Runtime
     * progress never enters persistent [CacheSnapshot], so a new generation always starts clean.
     */
    fun updateProgress(
        lease: CacheWorkerLease,
        unitKey: CacheUnitKey?,
        mode: CacheProgressMode,
        current: Long,
        total: Long? = null,
        failed: Long = 0L,
    ): Boolean {
        require(current >= 0L) { "cache progress current must not be negative" }
        require(total == null || total >= 0L) { "cache progress total must not be negative" }
        require(failed >= 0L) { "cache progress failed must not be negative" }
        require(total == null || current <= total) {
            "cache progress current exceeds total: current=$current total=$total"
        }
        synchronized(lock) {
            val task = requireTaskLocked(lease.sessionId, lease.taskId)
            if (!isCurrentLease(task, lease) || task.status != CacheLifecycle.RUNNING) {
                logStaleUpdate(lease, "progress unit=${unitKey?.chapterIndex} mode=$mode")
                return false
            }
            unitKey?.let { key ->
                require(task.units.any { it.key == key }) { "progress unit is not part of task: $key" }
            }
            val key = progressKey(lease, unitKey)
            if (unitKey != null) {
                progressByKey.remove(progressKey(lease, null))
            }
            progressByKey[key] = CacheProgressState(
                sessionId = lease.sessionId,
                taskId = lease.taskId,
                generation = lease.generation,
                unitKey = unitKey,
                mode = mode,
                current = current,
                total = total,
                failed = failed,
                updatedAt = System.currentTimeMillis(),
            )
            if (displaySessionId == lease.sessionId &&
                (displayProgressKey == null ||
                    (displayProgressKey?.sameTask(lease) == true &&
                        displayProgressKey?.unitKey == null &&
                        unitKey != null)
                )
            ) {
                displayProgressKey = key
            }
            reconcileProgressLocked()
            publishProgressLocked()
        }
        return true
    }

    /** Finalize normal worker completion. PARTIAL is an aggregate result, not a lifecycle state. */
    fun finishTask(
        lease: CacheWorkerLease,
        result: CacheResult,
        error: String? = null,
    ): Boolean {
        require(result != CacheResult.CANCELLED && result != CacheResult.SKIPPED) {
            "cancelled and skipped tasks must use their dedicated terminal transitions"
        }
        var aggregateResult: CacheResult? = null
        var autoFailedUnits: List<CacheUnitKey> = emptyList()
        synchronized(lock) {
            val task = requireTaskLocked(lease.sessionId, lease.taskId)
            if (!isCurrentLease(task, lease) || task.status != CacheLifecycle.RUNNING) {
                logStaleUpdate(lease, "finish=$result")
                return false
            }
            val unfinished = task.units.any {
                it.status == CacheUnitStatus.PENDING ||
                    it.status == CacheUnitStatus.RUNNING ||
                    it.status == CacheUnitStatus.REVIEW_ELIGIBLE
            }
            check(!unfinished || result == CacheResult.FAILED) {
                "cannot finish cache task with unfinished units: task=${task.taskId} result=$result"
            }
            check(task.status == CacheLifecycle.RUNNING) {
                "task is not running: ${task.status}"
            }
            val normalizedTask = if (result == CacheResult.FAILED && unfinished) {
                val failure = error ?: "task failed before unit completion"
                val failedKeys = task.units
                    .filter {
                        it.status == CacheUnitStatus.PENDING ||
                            it.status == CacheUnitStatus.RUNNING ||
                            it.status == CacheUnitStatus.REVIEW_ELIGIBLE
                    }
                    .map { it.key }
                autoFailedUnits = failedKeys
                task.copy(
                    units = task.units.map { unit ->
                        if (unit.key in failedKeys) {
                            unit.copy(
                                status = CacheUnitStatus.FAILED,
                                error = failure,
                                updatedAt = System.currentTimeMillis(),
                            )
                        } else {
                            unit
                        }
                    }
                )
            } else {
                task
            }
            val finalResult = aggregateTaskResult(normalizedTask, result)
            aggregateResult = finalResult
            val lifecycle = if (unfinished) {
                CacheLifecycle.FAILED
            } else {
                CacheLifecycle.COMPLETED
            }
            replaceTaskLocked(normalizedTask.copy(
                status = lifecycle,
                result = finalResult,
                error = error,
                terminalEffectsPending = true,
                updatedAt = System.currentTimeMillis(),
            ))
            removeTaskProgressLocked(lease.sessionId, lease.taskId)
            publishLocked()
        }
        autoFailedUnits.forEach { key ->
            record(
                CacheLogEventType.UNIT_FAILED,
                lease.sessionId,
                lease.taskId,
                "chapter=${key.chapterIndex} status=${CacheUnitStatus.FAILED} reason=task_finish",
            )
        }
        record(
            CacheLogEventType.TASK_FINISHED,
            lease.sessionId,
            lease.taskId,
            "result=$aggregateResult requested=$result${error?.let { " error=$it" }.orEmpty()}",
        )
        return true
    }

    /**
     * Finish an unstarted task because scheduling rejected it. This transition is atomic so a
     * scheduler conflict cannot be represented as a row of synthetic chapter failures.
     */
    fun skipTask(
        lease: CacheWorkerLease,
        reason: CacheTaskSkipReason,
        detail: String,
    ): Boolean {
        require(detail.isNotBlank()) { "skipped cache task requires a diagnostic detail" }
        var skippedUnits: List<CacheUnitKey> = emptyList()
        synchronized(lock) {
            val task = requireTaskLocked(lease.sessionId, lease.taskId)
            if (!isCurrentLease(task, lease) || task.status != CacheLifecycle.RUNNING) {
                logStaleUpdate(lease, "skip=$reason")
                return false
            }
            val skippable = task.units.filter {
                it.status == CacheUnitStatus.PENDING ||
                    it.status == CacheUnitStatus.RUNNING ||
                    it.status == CacheUnitStatus.REVIEW_ELIGIBLE
            }
            check(skippable.size == task.units.size) {
                "cannot skip cache task after a unit reached a terminal state: task=${task.taskId}"
            }
            skippedUnits = skippable.map { it.key }
            val updatedAt = System.currentTimeMillis()
            replaceTaskLocked(task.copy(
                units = task.units.map { unit ->
                    unit.copy(
                        status = CacheUnitStatus.SKIPPED,
                        error = detail,
                        updatedAt = updatedAt,
                    )
                },
                status = CacheLifecycle.COMPLETED,
                result = CacheResult.SKIPPED,
                skipReason = reason,
                error = detail,
                terminalEffectsPending = true,
                updatedAt = updatedAt,
            ))
            removeTaskProgressLocked(lease.sessionId, lease.taskId)
            publishLocked()
        }
        skippedUnits.forEach { key ->
            record(
                CacheLogEventType.UNIT_SKIPPED,
                lease.sessionId,
                lease.taskId,
                "chapter=${key.chapterIndex} status=${CacheUnitStatus.SKIPPED} reason=$reason detail=$detail",
            )
        }
        record(
            CacheLogEventType.TASK_FINISHED,
            lease.sessionId,
            lease.taskId,
            "result=${CacheResult.SKIPPED} reason=$reason error=$detail",
        )
        return true
    }

    fun pendingTerminalEffects(): List<CacheTaskState> = synchronized(lock) {
        sessions.values.asSequence()
            .flatMap { it.tasks.asSequence() }
            .filter { CacheLifecycleRules.isTerminal(it.status) && it.terminalEffectsPending }
            .toList()
    }

    fun completeTerminalEffects(sessionId: String, taskId: String): Boolean {
        synchronized(lock) {
            val task = requireTaskLocked(sessionId, taskId)
            if (!CacheLifecycleRules.isTerminal(task.status) || !task.terminalEffectsPending) {
                return false
            }
            replaceTaskLocked(task.copy(
                terminalEffectsPending = false,
                updatedAt = System.currentTimeMillis(),
            ))
            publishLocked()
            return true
        }
    }

    private fun aggregateTaskResult(task: CacheTaskState, requested: CacheResult): CacheResult {
        val succeeded = task.units.count { it.status == CacheUnitStatus.SUCCEEDED }
        val failed = task.units.count { it.status == CacheUnitStatus.FAILED }
        val unfinished = task.units.any {
            it.status == CacheUnitStatus.PENDING ||
                it.status == CacheUnitStatus.RUNNING ||
                it.status == CacheUnitStatus.REVIEW_ELIGIBLE
        }
        return when {
            succeeded > 0 && failed > 0 -> CacheResult.PARTIAL
            failed > 0 -> CacheResult.FAILED
            unfinished && requested == CacheResult.FAILED -> CacheResult.FAILED
            succeeded > 0 -> CacheResult.SUCCEEDED
            requested == CacheResult.FAILED -> CacheResult.FAILED
            else -> requested
        }
    }

    fun currentTask(sessionId: String, taskId: String): CacheTaskState? = synchronized(lock) {
        sessions[sessionId]?.tasks?.firstOrNull { it.taskId == taskId }
    }

    /**
     * Execute one durable artifact commit while the lease is still owned by this RUNNING task.
     * The Store lock covers both the lease check and the write callback so pause/cancel cannot
     * invalidate the generation between the check and the commit boundary.
     */
    fun commitIfLeaseActive(lease: CacheWorkerLease, action: () -> Unit): Boolean = synchronized(lock) {
        val task = sessions[lease.sessionId]
            ?.tasks
            ?.firstOrNull { it.taskId == lease.taskId }
        if (task == null || !isCurrentLease(task, lease) || task.status != CacheLifecycle.RUNNING) {
            logStaleUpdate(lease, "artifact commit rejected")
            return@synchronized false
        }
        action()
        true
    }

    fun findTask(sessionId: String, kind: CacheKind, phase: CachePhase, bookUrl: String): CacheTaskState? =
        synchronized(lock) {
            sessions[sessionId]?.tasks?.firstOrNull {
                it.kind == kind && it.phase == phase && it.bookUrl == bookUrl
            }
        }

    fun reviewEligibleUnits(sessionId: String, prerequisiteTaskId: String): List<CacheUnitKey> =
        synchronized(lock) {
            val task = requireTaskLocked(sessionId, prerequisiteTaskId)
            require(task.kind.reviewPrerequisitePhase() == task.phase) {
                "review eligibility requires a review-capable primary task"
            }
            task.units
                .filter { it.status == CacheUnitStatus.SUCCEEDED }
                .map { it.key }
        }

    private fun attachWorkerLocked(task: CacheTaskState): CacheWorkerLease {
        val nextGeneration = task.generation + 1
        val nextStatus = CacheLifecycle.RUNNING
        check(CacheLifecycleRules.canTransition(task.status, nextStatus)) {
            "invalid cache transition ${task.status} -> $nextStatus"
        }
        replaceTaskLocked(task.copy(
            status = nextStatus,
            result = null,
            generation = nextGeneration,
            updatedAt = System.currentTimeMillis(),
        ))
        return CacheWorkerLease(task.sessionId, task.taskId, nextGeneration).also { lease ->
            seedProgressLocked(task.copy(generation = nextGeneration, status = nextStatus), lease)
        }
    }

    private fun progressKey(lease: CacheWorkerLease, unitKey: CacheUnitKey?): ProgressKey = ProgressKey(
        sessionId = lease.sessionId,
        taskId = lease.taskId,
        generation = lease.generation,
        unitKey = unitKey,
    )

    private fun ProgressKey.sameTask(lease: CacheWorkerLease): Boolean =
        sessionId == lease.sessionId && taskId == lease.taskId && generation == lease.generation

    private fun seedProgressLocked(task: CacheTaskState, lease: CacheWorkerLease) {
        removeTaskProgressLocked(task.sessionId, task.taskId)
        val mode = if (task.kind == CacheKind.TEXT && task.phase == CachePhase.BODY) {
            CacheProgressMode.CHAPTERS
        } else {
            CacheProgressMode.INDETERMINATE
        }
        val progress = CacheProgressState(
            sessionId = lease.sessionId,
            taskId = lease.taskId,
            generation = lease.generation,
            unitKey = displayUnitKey(task),
            mode = mode,
            current = if (mode == CacheProgressMode.CHAPTERS) {
                task.units.count { it.status == CacheUnitStatus.SUCCEEDED }.toLong()
            } else {
                0L
            },
            total = if (mode == CacheProgressMode.CHAPTERS) task.units.size.toLong() else null,
            updatedAt = System.currentTimeMillis(),
        )
        val key = progressKey(lease, null)
        progressByKey[key] = progress
        if (displaySessionId == lease.sessionId && displayProgressKey == null) {
            displayProgressKey = key
        }
        reconcileProgressLocked()
    }

    private fun updateChapterProgressLocked(task: CacheTaskState, lease: CacheWorkerLease) {
        progressByKey[progressKey(lease, null)] = CacheProgressState(
            sessionId = lease.sessionId,
            taskId = lease.taskId,
            generation = lease.generation,
            unitKey = displayUnitKey(task),
            mode = CacheProgressMode.CHAPTERS,
            current = task.units.count { it.status == CacheUnitStatus.SUCCEEDED }.toLong(),
            total = task.units.size.toLong(),
            updatedAt = System.currentTimeMillis(),
        )
        if (displaySessionId == lease.sessionId && displayProgressKey == null) {
            displayProgressKey = progressKey(lease, null)
        }
    }

    private fun displayUnitKey(task: CacheTaskState): CacheUnitKey? {
        return task.units.firstOrNull { !isTerminalUnitStatus(it.status) }?.key
    }

    private fun removeTaskProgressLocked(sessionId: String, taskId: String) {
        progressByKey.keys.removeAll { it.sessionId == sessionId && it.taskId == taskId }
        reconcileProgressLocked()
    }

    private fun reconcileProgressLocked() {
        progressByKey.entries.removeAll { (key, _) ->
            val task = sessions[key.sessionId]?.tasks?.firstOrNull { it.taskId == key.taskId }
            task == null ||
                task.generation != key.generation ||
                CacheLifecycleRules.isTerminal(task.status) ||
                key.unitKey?.let { unitKey ->
                    task.units.firstOrNull { it.key == unitKey }
                        ?.status
                        ?.let(::isTerminalUnitStatus)
                        ?: true
                } == true
        }
        reconcileDisplaySessionLocked()
        if (displayProgressKey !in progressByKey ||
            displayProgressKey?.sessionId != displaySessionId
        ) {
            displayProgressKey = progressByKey.keys.firstOrNull { key ->
                key.sessionId == displaySessionId
            }
        }
    }

    /**
     * The foreground notification represents one cache operation (a Session), never whichever
     * task happened to report progress most recently. A task/unit can change inside that Session.
     */
    private fun reconcileDisplaySessionLocked() {
        val current = displaySessionId?.let(sessions::get)
        if (current?.tasks?.any { !CacheLifecycleRules.isTerminal(it.status) } == true) return
        displaySessionId = sessions.values.firstOrNull { session ->
            session.tasks.any { !CacheLifecycleRules.isTerminal(it.status) }
        }?.sessionId
    }

    private fun isTerminalUnitStatus(status: CacheUnitStatus): Boolean = status in setOf(
        CacheUnitStatus.SUCCEEDED,
        CacheUnitStatus.FAILED,
        CacheUnitStatus.SKIPPED,
        CacheUnitStatus.REVIEW_BLOCKED,
        CacheUnitStatus.NOT_APPLICABLE,
        CacheUnitStatus.CANCELLED,
    )

    private fun closeUnfinishedUnits(
        units: List<CacheUnitState>,
        status: CacheUnitStatus,
        error: String,
    ): List<CacheUnitState> {
        val updatedAt = System.currentTimeMillis()
        return units.map { unit ->
            if (isTerminalUnitStatus(unit.status)) {
                unit
            } else {
                unit.copy(status = status, error = error, updatedAt = updatedAt)
            }
        }
    }

    private fun publishProgressLocked() {
        val published = progressSnapshotLocked()
        _progress.value = published
        onPublished(_snapshot.value, published)
    }

    private fun progressSnapshotLocked(): CacheProgressSnapshot {
        reconcileProgressLocked()
        return CacheProgressSnapshot(
            states = progressByKey.values.toList(),
            displaySessionId = displaySessionId,
            display = displayProgressKey?.let(progressByKey::get),
        )
    }

    private fun replaceTaskLocked(task: CacheTaskState) {
        val session = requireSessionLocked(task.sessionId)
        val tasks = session.tasks.map { if (it.taskId == task.taskId) task else it }
        sessions[task.sessionId] = aggregateSession(session.copy(
            tasks = tasks,
            updatedAt = System.currentTimeMillis(),
        ))
    }

    private fun aggregateSession(session: CacheSessionState): CacheSessionState {
        if (session.tasks.isEmpty()) return session
        val status = when {
            session.tasks.any { it.status == CacheLifecycle.CANCELLING } -> CacheLifecycle.CANCELLING
            session.tasks.any { it.status == CacheLifecycle.PAUSING } -> CacheLifecycle.PAUSING
            session.tasks.any { it.status == CacheLifecycle.RUNNING } -> CacheLifecycle.RUNNING
            session.tasks.any { it.status == CacheLifecycle.PAUSED } -> CacheLifecycle.PAUSED
            session.tasks.any { it.status == CacheLifecycle.INTERRUPTED } -> CacheLifecycle.INTERRUPTED
            session.tasks.any { it.status == CacheLifecycle.QUEUED } -> CacheLifecycle.QUEUED
            session.tasks.all { it.status == CacheLifecycle.CANCELLED } -> CacheLifecycle.CANCELLED
            session.tasks.all { it.status == CacheLifecycle.FAILED } -> CacheLifecycle.FAILED
            else -> CacheLifecycle.COMPLETED
        }
        val result = if (!CacheLifecycleRules.isTerminal(status)) {
            null
        } else when {
            session.tasks.all { it.status == CacheLifecycle.CANCELLED } -> CacheResult.CANCELLED
            session.tasks.any { it.status == CacheLifecycle.CANCELLED } -> CacheResult.PARTIAL
            session.tasks.any {
                it.status == CacheLifecycle.FAILED || it.result == CacheResult.FAILED
            } -> {
                if (session.tasks.any { it.result == CacheResult.SUCCEEDED || it.result == CacheResult.PARTIAL }) {
                    CacheResult.PARTIAL
                } else {
                    CacheResult.FAILED
                }
            }
            session.tasks.any { it.result == CacheResult.PARTIAL } -> CacheResult.PARTIAL
            session.tasks.all { it.result == CacheResult.SKIPPED } -> CacheResult.SKIPPED
            session.tasks.any { it.result == CacheResult.SKIPPED } -> CacheResult.PARTIAL
            else -> CacheResult.SUCCEEDED
        }
        return session.copy(status = status, result = result, updatedAt = System.currentTimeMillis())
    }

    private fun isCurrentLease(task: CacheTaskState, lease: CacheWorkerLease): Boolean {
        return task.sessionId == lease.sessionId &&
            task.taskId == lease.taskId &&
            task.generation == lease.generation
    }

    private fun canUpdateUnit(from: CacheUnitStatus, to: CacheUnitStatus): Boolean {
        if (from == to) return true
        return when (from) {
            CacheUnitStatus.PENDING -> to == CacheUnitStatus.RUNNING ||
                to == CacheUnitStatus.REVIEW_ELIGIBLE ||
                to == CacheUnitStatus.REVIEW_BLOCKED ||
                to == CacheUnitStatus.NOT_APPLICABLE ||
                to == CacheUnitStatus.SKIPPED ||
                to == CacheUnitStatus.CANCELLED
            CacheUnitStatus.RUNNING -> to == CacheUnitStatus.SUCCEEDED ||
                to == CacheUnitStatus.FAILED ||
                to == CacheUnitStatus.SKIPPED ||
                to == CacheUnitStatus.CANCELLED
            CacheUnitStatus.REVIEW_ELIGIBLE -> to == CacheUnitStatus.RUNNING ||
                to == CacheUnitStatus.REVIEW_BLOCKED ||
                to == CacheUnitStatus.SKIPPED ||
                to == CacheUnitStatus.CANCELLED
            CacheUnitStatus.SUCCEEDED,
            CacheUnitStatus.FAILED,
            CacheUnitStatus.SKIPPED,
            CacheUnitStatus.REVIEW_BLOCKED,
            CacheUnitStatus.NOT_APPLICABLE,
            CacheUnitStatus.CANCELLED -> false
        }
    }

    private fun logStaleUpdate(lease: CacheWorkerLease, detail: String) {
        record(
            CacheLogEventType.STALE_UPDATE_DROPPED,
            lease.sessionId,
            lease.taskId,
            "generation=${lease.generation} $detail",
        )
    }

    private fun requireSessionLocked(sessionId: String): CacheSessionState {
        return requireNotNull(sessions[sessionId]) { "unknown cache session: $sessionId" }
    }

    private fun requireTaskLocked(sessionId: String, taskId: String): CacheTaskState {
        return requireSessionLocked(sessionId).tasks.firstOrNull { it.taskId == taskId }
            ?: error("unknown cache task: session=$sessionId task=$taskId")
    }

    private fun record(
        type: CacheLogEventType,
        sessionId: String? = null,
        taskId: String? = null,
        detail: String? = null,
    ) {
        logSink.record(CacheLogEvent(type, sessionId, taskId, detail))
    }

    private fun restore(snapshot: CacheSnapshot) {
        synchronized(lock) {
            snapshot.sessions.forEach { savedSession ->
                val recoveredTasks = savedSession.tasks.map { task ->
                    when (task.status) {
                        CacheLifecycle.RUNNING -> task.copy(
                            status = CacheLifecycle.INTERRUPTED,
                            generation = task.generation + 1,
                            updatedAt = System.currentTimeMillis(),
                        )
                        CacheLifecycle.CANCELLING -> task.copy(
                            units = closeUnfinishedUnits(
                                task.units,
                                status = CacheUnitStatus.CANCELLED,
                                error = "task cancelled during process recovery",
                            ),
                            status = CacheLifecycle.CANCELLED,
                            result = CacheResult.CANCELLED,
                            generation = task.generation + 1,
                            terminalEffectsPending = true,
                            updatedAt = System.currentTimeMillis(),
                        )
                        CacheLifecycle.PAUSING -> task.copy(
                            status = CacheLifecycle.PAUSED,
                            generation = task.generation + 1,
                            updatedAt = System.currentTimeMillis(),
                        )
                        else -> task
                    }
                }
                val recovered = aggregateSession(savedSession.copy(tasks = recoveredTasks))
                sessions[recovered.sessionId] = recovered
                record(
                    CacheLogEventType.SESSION_RECOVERED,
                    recovered.sessionId,
                    detail = "tasks=${recovered.tasks.size} status=${recovered.status}",
                )
            }
            publishLocked(persist = true)
        }
    }

    private fun publishLocked(persist: Boolean = true) {
        val published = CacheSnapshot(sessions.values.toList())
        val progress = progressSnapshotLocked()
        val trace = CacheOperationDiagnostics.begin(
            CacheOperationDiagnostics.Context(domain = CacheOperationDiagnostics.Domain.STORE),
            "SNAPSHOT_PUBLISH",
            CacheOperationDiagnostics.Metrics(
                sessionCount = published.sessions.size,
                taskCount = published.sessions.sumOf { it.tasks.size },
                persisted = persist,
            ),
        )
        try {
            _snapshot.value = published
            _progress.value = progress
            onPublished(published, progress)
            val version = ++persistenceVersion
            if (!persist) {
                scheduleCheckpointLocked(version)
            } else {
                pendingCheckpoint?.cancel(false)
                pendingCheckpoint = null
                saveSnapshot(published)
            }
            trace.done(
                CacheOperationDiagnostics.Metrics(
                    sessionCount = published.sessions.size,
                    taskCount = published.sessions.sumOf { it.tasks.size },
                    persisted = persist,
                )
            )
        } catch (error: Throwable) {
            trace.fail(error, CacheOperationDiagnostics.Metrics(persisted = persist))
            throw error
        }
    }

    private fun scheduleCheckpointLocked(version: Long) {
        pendingCheckpoint?.cancel(false)
        pendingCheckpoint = persistenceExecutor.schedule(
            {
                synchronized(lock) {
                    if (version == persistenceVersion) saveSnapshot(_snapshot.value)
                }
            },
            500,
            TimeUnit.MILLISECONDS,
        )
    }

    private fun saveSnapshot(snapshot: CacheSnapshot) {
        persistence.save(snapshot).onFailure { error ->
            record(
                CacheLogEventType.PERSISTENCE_SAVE_FAILED,
                detail = error.localizedMessage,
            )
        }
    }
}
