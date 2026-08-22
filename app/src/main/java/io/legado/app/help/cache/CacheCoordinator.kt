package io.legado.app.help.cache

import kotlinx.coroutines.flow.StateFlow

data class CacheSubmission(
    val sessionId: String,
    val taskId: String,
)

/**
 * The only submission and command boundary for offline cache work.
 *
 * Existing workers are migrated behind this boundary in later phases. UI and
 * feature code should depend on this object, never on a worker or Service.
 */
object CacheCoordinator {

    private val store = CacheTaskStore()
    val snapshot: StateFlow<CacheSnapshot> = store.snapshot

    fun submit(request: CacheRequest): CacheSubmission {
        validateRequest(request)
        val session = store.createSession(request.bookName)
        val task = store.addTask(session.sessionId, request)
        AppLogCacheLogSink.record(
            "REQUEST_ACCEPTED",
            session.sessionId,
            task.taskId,
            "source=${request.source} kind=${request.kind} phase=${request.phase}",
        )
        return CacheSubmission(session.sessionId, task.taskId)
    }

    fun acquireWorker(submission: CacheSubmission): CacheWorkerLease? {
        return store.acquireWorker(submission.sessionId, submission.taskId)
    }

    fun reclaimWorker(submission: CacheSubmission): CacheWorkerLease? {
        return store.reclaimWorker(submission.sessionId, submission.taskId)
    }

    fun pause(submission: CacheSubmission): Boolean {
        return store.pauseTask(submission.sessionId, submission.taskId)
    }

    fun resume(submission: CacheSubmission): CacheWorkerLease? {
        return store.resumeTask(submission.sessionId, submission.taskId)
    }

    fun requestStop(submission: CacheSubmission): Boolean {
        return store.beginCancel(submission.sessionId, submission.taskId)
    }

    fun confirmStopped(submission: CacheSubmission): Boolean {
        return store.confirmCancelled(submission.sessionId, submission.taskId)
    }

    fun updateUnit(
        lease: CacheWorkerLease,
        key: CacheUnitKey,
        status: CacheUnitStatus,
        error: String? = null,
    ): Boolean {
        return store.updateUnit(lease, key, status, error)
    }

    fun finish(
        lease: CacheWorkerLease,
        result: CacheResult,
        error: String? = null,
    ): Boolean {
        return store.finishTask(lease, result, error)
    }

    fun currentTask(submission: CacheSubmission): CacheTaskState? {
        return store.currentTask(submission.sessionId, submission.taskId)
    }

    private fun validateRequest(request: CacheRequest) {
        require(request.bookUrl.isNotBlank()) { "cache request bookUrl is blank" }
        require(request.bookName.isNotBlank()) { "cache request bookName is blank" }
        require(request.units.isNotEmpty()) { "cache request has no units" }
        require(request.units.all { it.bookUrl == request.bookUrl }) {
            "cache request contains units from another book"
        }
        when (request.kind) {
            CacheKind.TEXT -> require(
                request.phase == CachePhase.BODY || request.phase == CachePhase.REVIEW
            ) { "text request must use BODY or REVIEW phase" }
            CacheKind.AUDIO,
            CacheKind.VIDEO -> require(request.phase == CachePhase.MEDIA) {
                "media request must use MEDIA phase"
            }
        }
        if (request.kind != CacheKind.TEXT) {
            require(!request.reviewEnabled) { "only text requests can enable review caching" }
        }
    }
}
