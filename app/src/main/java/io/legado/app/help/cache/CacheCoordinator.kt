package io.legado.app.help.cache

import kotlinx.coroutines.flow.StateFlow
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.help.config.AppConfig
import io.legado.app.help.book.isLocal
import io.legado.app.help.review.ReviewSnapshotManager

data class CacheSubmission(
    val sessionId: String,
    val taskId: String,
)

/** User-facing port. Worker leases and unit mutation are intentionally absent. */
interface CacheUiPort {
    val snapshot: StateFlow<CacheSnapshot>
    val progress: StateFlow<CacheProgressSnapshot>

    fun submit(request: CacheRequest): CacheSubmission
    fun pause(submission: CacheSubmission): Boolean
    fun resume(submission: CacheSubmission): Boolean
    fun cancel(submission: CacheSubmission): Boolean
}

/** Internal worker-facing port. UI code must not depend on this interface. */
internal interface CacheWorkerPort {
    fun acquire(submission: CacheSubmission): CacheWorkerLease?
    fun reclaim(submission: CacheSubmission): CacheWorkerLease?
    fun failQueued(submission: CacheSubmission, error: String): Boolean
    fun confirmPaused(submission: CacheSubmission): Boolean
    fun updateUnit(
        lease: CacheWorkerLease,
        key: CacheUnitKey,
        status: CacheUnitStatus,
        error: String? = null,
    ): Boolean
    fun updateProgress(
        lease: CacheWorkerLease,
        unitKey: CacheUnitKey?,
        mode: CacheProgressMode,
        current: Long,
        total: Long? = null,
        failed: Long = 0L,
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
 * Execution workers are hosted behind [workerPort]; they cannot mutate UI state.
 */
object CacheCoordinator : CacheUiPort {

    private val store = CacheTaskStore(onPublished = CacheNotificationBridge::render)
    override val snapshot: StateFlow<CacheSnapshot> = store.snapshot
    override val progress: StateFlow<CacheProgressSnapshot> = store.progress

    internal val workerPort: CacheWorkerPort = object : CacheWorkerPort {
        override fun acquire(submission: CacheSubmission): CacheWorkerLease? {
            return store.acquireWorker(submission.sessionId, submission.taskId)
        }

        override fun reclaim(submission: CacheSubmission): CacheWorkerLease? {
            return store.reclaimWorker(submission.sessionId, submission.taskId)
        }

        override fun failQueued(submission: CacheSubmission, error: String): Boolean {
            return store.failQueuedTask(submission.sessionId, submission.taskId, error)
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

        override fun updateProgress(
            lease: CacheWorkerLease,
            unitKey: CacheUnitKey?,
            mode: CacheProgressMode,
            current: Long,
            total: Long?,
            failed: Long,
        ): Boolean {
            return store.updateProgress(lease, unitKey, mode, current, total, failed)
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

    private val workerDispatcher: CacheWorkerDispatcher =
        CacheWorkerDispatcherImpl(workerPort)
    private val readerReviewLock = Any()

    init {
        workerDispatcher.recover(snapshot.value)
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
        return CacheSubmission(session.sessionId, task.taskId).also(workerDispatcher::start)
    }

    override fun pause(submission: CacheSubmission): Boolean {
        return store.pauseTask(submission.sessionId, submission.taskId).also {
            if (it) workerDispatcher.pause(submission)
        }
    }

    override fun resume(submission: CacheSubmission): Boolean {
        val lease = store.resumeTask(submission.sessionId, submission.taskId) ?: return false
        workerDispatcher.resume(submission, lease)
        return true
    }

    override fun cancel(submission: CacheSubmission): Boolean {
        return store.beginCancel(submission.sessionId, submission.taskId).also {
            if (it) workerDispatcher.cancel(submission)
        }
    }

    /** Summary notification commands intentionally operate on all active tasks. */
    fun pauseAll(): Int = commandAll { pause(it) }

    fun resumeAll(): Int = commandAll { resume(it) }

    fun cancelAll(): Int = commandAll { cancel(it) }

    /** Record a reader-requested review refresh without exposing the queue manager. */
    fun markReviewRefresh(bookUrl: String, chapterIndex: Int) {
        ReviewSnapshotManager.markUserRefresh(bookUrl, chapterIndex)
    }

    private fun commandAll(command: (CacheSubmission) -> Boolean): Int {
        val submissions = snapshot.value.sessions
            .flatMap { it.tasks }
            .filter { !CacheLifecycleRules.isTerminal(it.status) }
            .map { CacheSubmission(it.sessionId, it.taskId) }
        return submissions.count(command)
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
        store.findTask(sessionId, CacheKind.TEXT, CachePhase.REVIEW, body.bookUrl)?.let {
            return CacheSubmission(sessionId, it.taskId)
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
        return CacheSubmission(sessionId, task.taskId).also(workerDispatcher::start)
    }

    /**
     * Reader-triggered review caching still belongs to the coordinator domain.
     * Reuse an active same-chapter task so repeated page callbacks do not create
     * competing REVIEW workers for one book.
     */
    internal fun submitReaderReview(
        book: Book,
        chapter: BookChapter,
        force: Boolean,
    ): Boolean {
        if (!AppConfig.syncCacheReview || book.isLocal) return false
        synchronized(readerReviewLock) {
            val existing = snapshot.value.sessions.asSequence()
                .flatMap { it.tasks.asSequence() }
                .filter {
                    it.kind == CacheKind.TEXT &&
                        it.phase == CachePhase.REVIEW &&
                        it.bookUrl == book.bookUrl &&
                        !CacheLifecycleRules.isTerminal(it.status)
                }
                .firstOrNull { task ->
                    task.units.any { it.key.chapterIndex == chapter.index }
                }
            if (existing != null) {
                // Keep an explicit refresh request durable while the active task
                // remains the single owner for this chapter.
                if (force) {
                    ReviewSnapshotManager.markUserRefresh(book.bookUrl, chapter.index)
                }
                return true
            }
            submit(
                CacheRequest(
                    source = CacheRequestSource.READER,
                    kind = CacheKind.TEXT,
                    phase = CachePhase.REVIEW,
                    bookUrl = book.bookUrl,
                    bookName = book.name,
                    units = listOf(CacheUnitKey(book.bookUrl, chapter.index)),
                    reviewEnabled = true,
                )
            )
            return true
        }
    }

    internal fun currentTask(submission: CacheSubmission): CacheTaskState? {
        return store.currentTask(submission.sessionId, submission.taskId)
    }

    internal fun notifyTaskFinished(
        lease: CacheWorkerLease,
        result: CacheResult,
        error: String? = null,
    ) {
        notifyTaskFinished(CacheSubmission(lease.sessionId, lease.taskId), result, error)
    }

    internal fun notifyTaskFinished(
        submission: CacheSubmission,
        result: CacheResult,
        error: String? = null,
    ) {
        val task = currentTask(submission)
        if (task != null &&
            task.kind == CacheKind.TEXT &&
            task.phase == CachePhase.BODY &&
            task.reviewEnabled &&
            task.status in setOf(CacheLifecycle.COMPLETED, CacheLifecycle.FAILED)
        ) {
            appendReviewTask(task.sessionId, task.taskId)
        }
        val finalTask = currentTask(submission)
        CacheNotificationBridge.finished(
            snapshot = snapshot.value,
            progress = progress.value,
            task = finalTask,
            result = finalTask?.result ?: result,
            error = error,
        )
    }

    private fun validateUiRequest(request: CacheRequest) {
        if (request.phase == CachePhase.REVIEW) {
            require(request.kind == CacheKind.TEXT) { "only text requests can use REVIEW phase" }
            require(request.source == CacheRequestSource.READER) {
                "REVIEW tasks must be appended by the text coordinator or submitted by READER"
            }
        } else {
            require(request.kind != CacheKind.TEXT || request.phase == CachePhase.BODY) {
                "text UI requests must use BODY phase"
            }
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
