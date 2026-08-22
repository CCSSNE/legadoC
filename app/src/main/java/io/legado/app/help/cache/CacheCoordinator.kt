package io.legado.app.help.cache

import kotlinx.coroutines.flow.StateFlow

data class CacheSubmission(
    val sessionId: String,
    val taskId: String,
)

/** User-facing port. Worker leases and unit mutation are intentionally absent. */
interface CacheUiPort {
    val snapshot: StateFlow<CacheSnapshot>

    fun submit(request: CacheRequest): CacheSubmission
    fun pause(submission: CacheSubmission): Boolean
    fun resume(submission: CacheSubmission): Boolean
    fun cancel(submission: CacheSubmission): Boolean
}

/** Internal worker-facing port. UI code must not depend on this interface. */
internal interface CacheWorkerPort {
    fun acquire(submission: CacheSubmission): CacheWorkerLease?
    fun reclaim(submission: CacheSubmission): CacheWorkerLease?
    fun confirmPaused(submission: CacheSubmission): Boolean
    fun updateUnit(
        lease: CacheWorkerLease,
        key: CacheUnitKey,
        status: CacheUnitStatus,
        error: String? = null,
    ): Boolean
    fun finish(
        lease: CacheWorkerLease,
        result: CacheResult,
        error: String? = null,
    ): Boolean
    fun confirmCancelled(submission: CacheSubmission): Boolean
}

/**
 * The only public submission/command boundary for offline cache work.
 * Existing workers are migrated behind [workerPort] in later phases.
 */
object CacheCoordinator : CacheUiPort {

    private val store = CacheTaskStore()
    override val snapshot: StateFlow<CacheSnapshot> = store.snapshot

    internal val workerPort: CacheWorkerPort = object : CacheWorkerPort {
        override fun acquire(submission: CacheSubmission): CacheWorkerLease? {
            return store.acquireWorker(submission.sessionId, submission.taskId)
        }

        override fun reclaim(submission: CacheSubmission): CacheWorkerLease? {
            return store.reclaimWorker(submission.sessionId, submission.taskId)
        }

        override fun confirmPaused(submission: CacheSubmission): Boolean {
            return store.confirmPaused(submission.sessionId, submission.taskId)
        }

        override fun updateUnit(
            lease: CacheWorkerLease,
            key: CacheUnitKey,
            status: CacheUnitStatus,
            error: String?,
        ): Boolean {
            return store.updateUnit(lease, key, status, error)
        }

        override fun finish(
            lease: CacheWorkerLease,
            result: CacheResult,
            error: String?,
        ): Boolean {
            return store.finishTask(lease, result, error)
        }

        override fun confirmCancelled(submission: CacheSubmission): Boolean {
            return store.confirmCancelled(submission.sessionId, submission.taskId)
        }
    }

    override fun submit(request: CacheRequest): CacheSubmission {
        validateUiRequest(request)
        val session = store.createSession(request.bookName)
        val task = store.addTask(session.sessionId, request)
        record(
            CacheLogEventType.REQUEST_ACCEPTED,
            session.sessionId,
            task.taskId,
            "source=${request.source} kind=${request.kind} phase=${request.phase}",
        )
        return CacheSubmission(session.sessionId, task.taskId)
    }

    override fun pause(submission: CacheSubmission): Boolean {
        return store.pauseTask(submission.sessionId, submission.taskId)
    }

    override fun resume(submission: CacheSubmission): Boolean {
        return store.resumeTask(submission.sessionId, submission.taskId) != null
    }

    override fun cancel(submission: CacheSubmission): Boolean {
        return store.beginCancel(submission.sessionId, submission.taskId)
    }

    /** Only the text coordinator may append the REVIEW task to an existing session. */
    internal fun appendReviewTask(
        sessionId: String,
        bodyTaskId: String,
    ): CacheSubmission? {
        val body = store.currentTask(sessionId, bodyTaskId)
            ?: error("unknown BODY task: session=$sessionId task=$bodyTaskId")
        require(body.kind == CacheKind.TEXT && body.phase == CachePhase.BODY) {
            "review task must follow a text BODY task"
        }
        require(body.reviewEnabled) { "BODY task did not request review caching" }
        require(
            body.status == CacheLifecycle.COMPLETED || body.status == CacheLifecycle.FAILED
        ) { "review task cannot be appended before BODY task completion" }
        require(!store.hasTask(sessionId, CacheKind.TEXT, CachePhase.REVIEW, body.bookUrl)) {
            "review task already exists for session=$sessionId book=${body.bookUrl}"
        }
        val eligible = store.reviewEligibleUnits(sessionId, bodyTaskId)
        if (eligible.isEmpty()) return null
        val request = CacheRequest(
            source = body.source,
            kind = CacheKind.TEXT,
            phase = CachePhase.REVIEW,
            bookUrl = body.bookUrl,
            bookName = body.bookName,
            units = eligible,
            reviewEnabled = true,
        )
        val task = store.addTask(sessionId, request)
        record(
            CacheLogEventType.TASK_QUEUED,
            sessionId,
            task.taskId,
            "appended=REVIEW units=${task.units.size}",
        )
        return CacheSubmission(sessionId, task.taskId)
    }

    internal fun currentTask(submission: CacheSubmission): CacheTaskState? {
        return store.currentTask(submission.sessionId, submission.taskId)
    }

    private fun validateUiRequest(request: CacheRequest) {
        require(request.phase != CachePhase.REVIEW) {
            "UI cannot submit a REVIEW task; the text coordinator appends it after BODY results"
        }
        require(request.kind != CacheKind.TEXT || request.phase == CachePhase.BODY) {
            "text UI requests must use BODY phase"
        }
        validateCommon(request)
    }

    private fun validateCommon(request: CacheRequest) {
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

    private fun record(
        type: CacheLogEventType,
        sessionId: String,
        taskId: String,
        detail: String,
    ) {
        AppLogCacheLogSink.record(CacheLogEvent(type, sessionId, taskId, detail))
    }
}
