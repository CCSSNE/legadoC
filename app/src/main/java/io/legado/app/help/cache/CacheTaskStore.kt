package io.legado.app.help.cache

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/**
 * The single in-process source of cache task state.
 *
 * Persistence can be added behind this boundary later. Workers never mutate a
 * task directly; every update must carry the lease generation.
 */
class CacheTaskStore(
    private val logSink: CacheLogSink = AppLogCacheLogSink,
) {

    private val lock = Any()
    private val sessions = LinkedHashMap<String, CacheSessionState>()
    private val _snapshot = MutableStateFlow(CacheSnapshot())
    val snapshot: StateFlow<CacheSnapshot> = _snapshot.asStateFlow()

    fun createSession(title: String): CacheSessionState {
        val now = System.currentTimeMillis()
        val session = CacheSessionState(
            sessionId = UUID.randomUUID().toString(),
            title = title,
            createdAt = now,
            updatedAt = now,
        )
        synchronized(lock) {
            sessions[session.sessionId] = session
            publishLocked()
        }
        logSink.record("SESSION_CREATED", sessionId = session.sessionId, detail = title)
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
            updatedAt = now,
        )
        synchronized(lock) {
            val session = requireSessionLocked(sessionId)
            sessions[sessionId] = session.copy(
                tasks = session.tasks + task,
                updatedAt = now,
            )
            publishLocked()
        }
        logSink.record(
            "TASK_QUEUED",
            sessionId = sessionId,
            taskId = task.taskId,
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
            logSink.record("TASK_STARTED", sessionId, taskId, "generation=${lease.generation}")
            return lease
        }
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
            logSink.record("TASK_RECLAIMED", sessionId, taskId, "generation=${lease.generation}")
            return lease
        }
    }

    fun pauseTask(sessionId: String, taskId: String): Boolean {
        synchronized(lock) {
            val task = requireTaskLocked(sessionId, taskId)
            if (task.status != CacheLifecycle.RUNNING) return false
            replaceTaskLocked(task.copy(
                status = CacheLifecycle.PAUSED,
                updatedAt = System.currentTimeMillis(),
            ))
            publishLocked()
        }
        logSink.record("TASK_PAUSED", sessionId, taskId)
        return true
    }

    /** Resume returns a new lease so the previous worker cannot write after a restart. */
    fun resumeTask(sessionId: String, taskId: String): CacheWorkerLease? {
        synchronized(lock) {
            val task = requireTaskLocked(sessionId, taskId)
            if (task.status != CacheLifecycle.PAUSED) return null
            val lease = attachWorkerLocked(task)
            publishLocked()
            logSink.record("TASK_RESUMED", sessionId, taskId, "generation=${lease.generation}")
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
            publishLocked()
        }
        logSink.record("TASK_CANCELLING", sessionId, taskId)
        return true
    }

    fun confirmCancelled(sessionId: String, taskId: String): Boolean {
        synchronized(lock) {
            val task = requireTaskLocked(sessionId, taskId)
            if (task.status != CacheLifecycle.CANCELLING) return false
            replaceTaskLocked(task.copy(
                status = CacheLifecycle.CANCELLED,
                result = CacheResult.CANCELLED,
                updatedAt = System.currentTimeMillis(),
            ))
            publishLocked()
        }
        logSink.record("TASK_CANCELLED", sessionId, taskId)
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
            if (!isCurrentLease(task, lease)) {
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
            replaceTaskLocked(task.copy(units = units, updatedAt = System.currentTimeMillis()))
            publishLocked()
        }
        logSink.record(
            if (status == CacheUnitStatus.FAILED) "UNIT_FAILED" else "UNIT_UPDATED",
            lease.sessionId,
            lease.taskId,
            "chapter=${key.chapterIndex} status=$status${error?.let { " error=$it" }.orEmpty()}",
        )
        return true
    }

    /** Finalize normal worker completion. PARTIAL is an aggregate result, not a lifecycle state. */
    fun finishTask(
        lease: CacheWorkerLease,
        result: CacheResult,
        error: String? = null,
    ): Boolean {
        require(result != CacheResult.CANCELLED) {
            "cancelled tasks must use beginCancel/confirmCancelled"
        }
        synchronized(lock) {
            val task = requireTaskLocked(lease.sessionId, lease.taskId)
            if (!isCurrentLease(task, lease)) {
                logStaleUpdate(lease, "finish=$result")
                return false
            }
            check(task.status == CacheLifecycle.RUNNING) {
                "task is not running: ${task.status}"
            }
            val lifecycle = if (result == CacheResult.FAILED) {
                CacheLifecycle.FAILED
            } else {
                CacheLifecycle.SUCCEEDED
            }
            replaceTaskLocked(task.copy(
                status = lifecycle,
                result = result,
                error = error,
                updatedAt = System.currentTimeMillis(),
            ))
            publishLocked()
        }
        logSink.record(
            "TASK_FINISHED",
            lease.sessionId,
            lease.taskId,
            "result=$result${error?.let { " error=$it" }.orEmpty()}",
        )
        return true
    }

    fun currentTask(sessionId: String, taskId: String): CacheTaskState? = synchronized(lock) {
        sessions[sessionId]?.tasks?.firstOrNull { it.taskId == taskId }
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
        return CacheWorkerLease(task.sessionId, task.taskId, nextGeneration)
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
            session.tasks.any { it.status == CacheLifecycle.RUNNING } -> CacheLifecycle.RUNNING
            session.tasks.any { it.status == CacheLifecycle.PAUSED } -> CacheLifecycle.PAUSED
            session.tasks.any { it.status == CacheLifecycle.QUEUED } -> CacheLifecycle.QUEUED
            session.tasks.any { it.status == CacheLifecycle.CANCELLED } -> CacheLifecycle.CANCELLED
            session.tasks.any { it.status == CacheLifecycle.FAILED } -> CacheLifecycle.FAILED
            else -> CacheLifecycle.SUCCEEDED
        }
        val result = if (!CacheLifecycleRules.isTerminal(status)) {
            null
        } else when {
            session.tasks.any { it.status == CacheLifecycle.CANCELLED } -> CacheResult.CANCELLED
            session.tasks.any { it.status == CacheLifecycle.FAILED } -> {
                if (session.tasks.any { it.result == CacheResult.SUCCEEDED || it.result == CacheResult.PARTIAL }) {
                    CacheResult.PARTIAL
                } else {
                    CacheResult.FAILED
                }
            }
            session.tasks.any { it.result == CacheResult.PARTIAL } -> CacheResult.PARTIAL
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
            CacheUnitStatus.PENDING -> true
            CacheUnitStatus.RUNNING -> to == CacheUnitStatus.SUCCEEDED ||
                to == CacheUnitStatus.FAILED ||
                to == CacheUnitStatus.CANCELLED
            CacheUnitStatus.REVIEW_ELIGIBLE -> to == CacheUnitStatus.RUNNING ||
                to == CacheUnitStatus.REVIEW_BLOCKED ||
                to == CacheUnitStatus.CANCELLED
            CacheUnitStatus.SUCCEEDED,
            CacheUnitStatus.FAILED,
            CacheUnitStatus.REVIEW_BLOCKED,
            CacheUnitStatus.NOT_APPLICABLE,
            CacheUnitStatus.CANCELLED -> false
        }
    }

    private fun logStaleUpdate(lease: CacheWorkerLease, detail: String) {
        logSink.record(
            "STALE_UPDATE_DROPPED",
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

    private fun publishLocked() {
        _snapshot.value = CacheSnapshot(sessions.values.toList())
    }
}
