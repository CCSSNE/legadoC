package io.legado.app.help.cache

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.help.book.isAudio
import io.legado.app.help.book.isVideo
import io.legado.app.help.config.AppConfig
import io.legado.app.help.book.isLocal
import io.legado.app.help.review.ReviewResourceEpoch
import io.legado.app.help.review.ReviewSnapshotManager
import io.legado.app.help.review.ReviewSnapshotResourceStore
import io.legado.app.help.review.ReviewSnapshotStore
import java.util.concurrent.ConcurrentHashMap

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
    fun skip(
        lease: CacheWorkerLease,
        reason: CacheTaskSkipReason,
        detail: String,
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

        override fun skip(
            lease: CacheWorkerLease,
            reason: CacheTaskSkipReason,
            detail: String,
        ): Boolean {
            return store.skipTask(lease, reason, detail)
        }

        override fun confirmCancelled(submission: CacheSubmission): Boolean {
            return store.confirmCancelled(submission.sessionId, submission.taskId)
        }
    }

    private val workerDispatcher: CacheWorkerDispatcher =
        CacheWorkerDispatcherImpl(workerPort)
    private val reviewTaskLock = Any()
    private val automaticSubmitLock = Any()
    private val resourceGcScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    /** 防止同一本书在短时间内重复排队 GC。 */
    private val resourceGcScheduled = ConcurrentHashMap.newKeySet<String>()

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

    /**
     * The only explicit-download entry point for book pages.
     *
     * The book type, rather than the screen that initiated the action, decides the worker.
     * Audio and video books must never be sent to the text body worker.
     */
    fun submitBookDownload(
        book: Book,
        chapterIndexes: Iterable<Int>,
        source: CacheRequestSource,
        reviewEnabled: Boolean = AppConfig.syncCacheReview,
    ): CacheSubmission {
        return if (book.isAudio || book.isVideo) {
            submitMediaDownload(book, chapterIndexes, source, reviewEnabled)
        } else {
            submitTextDownload(book, chapterIndexes, source, reviewEnabled)
        }
    }

    /** Shared BODY submission used by text-book download actions and automatic refresh. */
    fun submitTextDownload(
        book: Book,
        chapterIndexes: Iterable<Int>,
        source: CacheRequestSource,
        reviewEnabled: Boolean = AppConfig.syncCacheReview,
    ): CacheSubmission {
        require(!book.isAudio && !book.isVideo) {
            "text download is invalid for media book: ${book.bookUrl}"
        }
        val indexes = chapterIndexes.distinct().sorted()
        require(indexes.isNotEmpty()) { "text download has no chapters" }
        return submit(
            CacheRequest(
                source = source,
                kind = CacheKind.TEXT,
                phase = CachePhase.BODY,
                bookUrl = book.bookUrl,
                bookName = book.name,
                units = indexes.map { CacheUnitKey(book.bookUrl, it) },
                reviewEnabled = reviewEnabled,
            )
        )
    }

    /** Shared MEDIA submission used by every audio/video-book download action. */
    fun submitMediaDownload(
        book: Book,
        chapterIndexes: Iterable<Int>,
        source: CacheRequestSource,
        reviewEnabled: Boolean = AppConfig.syncCacheReview,
    ): CacheSubmission {
        require(book.isAudio || book.isVideo) { "media download requires an audio or video book" }
        val indexes = chapterIndexes.distinct().sorted()
        require(indexes.isNotEmpty()) { "media download has no chapters" }
        return submit(
            CacheRequest(
                source = source,
                kind = if (book.isVideo) CacheKind.VIDEO else CacheKind.AUDIO,
                phase = CachePhase.MEDIA,
                bookUrl = book.bookUrl,
                bookName = book.name,
                units = indexes.map { CacheUnitKey(book.bookUrl, it) },
                reviewEnabled = book.isAudio && !book.isLocal && reviewEnabled,
            )
        )
    }

    /** Current chapter counts as the first chapter in the configured predownload window. */
    fun automaticChapterIndexes(
        startIndex: Int,
        chapterCount: Int,
        preDownloadCount: Int,
    ): List<Int> {
        if (chapterCount <= 0 || startIndex !in 0 until chapterCount) return emptyList()
        val count = preDownloadCount.coerceAtLeast(1)
        val endIndex = (startIndex.toLong() + count - 1L)
            .coerceAtMost(chapterCount - 1L)
            .toInt()
        return (startIndex..endIndex).toList()
    }

    /**
     * Automatic review download never creates a competing same-domain session for the book.
     * Text waits for BODY; audio waits for MEDIA, which resolves raw lyric before REVIEW starts.
     */
    fun submitAutomaticBookDownload(
        book: Book,
        chapterIndexes: Iterable<Int>,
        source: CacheRequestSource,
    ): CacheSubmission? = synchronized(automaticSubmitLock) {
        require(!book.isVideo) { "automatic review download is invalid for video book" }
        val kind = if (book.isAudio) CacheKind.AUDIO else CacheKind.TEXT
        if (hasActiveDownload(book.bookUrl, kind)) return@synchronized null
        val reviewEnabled = AppConfig.syncCacheReview && AppConfig.autoDownloadReview
        if (book.isAudio) {
            submitMediaDownload(book, chapterIndexes, source, reviewEnabled)
        } else {
            submitTextDownload(book, chapterIndexes, source, reviewEnabled)
        }
    }

    private fun hasActiveDownload(bookUrl: String, kind: CacheKind): Boolean {
        return snapshot.value.sessions.asSequence()
            .flatMap { it.tasks.asSequence() }
            .any { task ->
                task.bookUrl == bookUrl &&
                    task.kind == kind &&
                    !CacheLifecycleRules.isTerminal(task.status)
            }
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

    /** Cache-management retry boundary for one chapter's recorded failed review buttons. */
    fun retryReviewSnapshots(book: Book, chapter: BookChapter): Boolean {
        return retryReviewSnapshots(book, listOf(chapter)) == 1
    }

    /** Retries only the durable failed-button identities as one Coordinator task. */
    fun retryReviewSnapshots(book: Book, chapters: List<BookChapter>): Int {
        if (!AppConfig.syncCacheReview || book.isLocal || book.isVideo) return 0
        val reviewKind = if (book.isAudio) CacheKind.AUDIO else CacheKind.TEXT
        val requested = chapters
            .asSequence()
            .filterNot { it.isVolume }
            .distinctBy { it.index }
            .toList()
        if (requested.isEmpty()) return 0
        val statusesByChapterUrl = ReviewSnapshotStore.chapterStatuses(book)
            .associateBy { it.chapterUrl.trim() }
        val retryTargets = requested.mapNotNull { chapter ->
            val failedButtonSources = statusesByChapterUrl[chapter.url.trim()]
                ?.failedButtonSourcesForRetry()
                ?: return@mapNotNull null
            CacheReviewRetryTarget(
                unitKey = CacheUnitKey(book.bookUrl, chapter.index),
                buttonSources = failedButtonSources,
            )
        }
        if (retryTargets.isEmpty()) return 0
        synchronized(reviewTaskLock) {
            val activeIndexes = snapshot.value.sessions.asSequence()
                .flatMap { it.tasks.asSequence() }
                .filter {
                    it.kind == reviewKind &&
                        it.phase == CachePhase.REVIEW &&
                        it.bookUrl == book.bookUrl &&
                        !CacheLifecycleRules.isTerminal(it.status)
                }
                .flatMap { task -> task.units.asSequence().map { it.key.chapterIndex } }
                .toHashSet()
            val unownedTargets = retryTargets.filterNot { target ->
                target.unitKey.chapterIndex in activeIndexes
            }
            if (unownedTargets.isNotEmpty()) {
                submit(
                    CacheRequest(
                        source = CacheRequestSource.READER,
                        kind = reviewKind,
                        phase = CachePhase.REVIEW,
                        bookUrl = book.bookUrl,
                        bookName = book.name,
                        units = unownedTargets.map { it.unitKey },
                        reviewEnabled = true,
                        reviewRetryTargets = unownedTargets,
                    )
                )
            }
            return unownedTargets.size
        }
    }

    private fun commandAll(command: (CacheSubmission) -> Boolean): Int {
        val submissions = snapshot.value.sessions
            .flatMap { it.tasks }
            .filter { !CacheLifecycleRules.isTerminal(it.status) }
            .map { CacheSubmission(it.sessionId, it.taskId) }
        return submissions.count(command)
    }

    /** Append REVIEW only after the same domain's primary artifact has reached a terminal state. */
    internal fun appendReviewTask(
        sessionId: String,
        prerequisiteTaskId: String,
    ): CacheSubmission? {
        val prerequisite = store.currentTask(sessionId, prerequisiteTaskId)
            ?: error("unknown review prerequisite: session=$sessionId task=$prerequisiteTaskId")
        require(prerequisite.kind.reviewPrerequisitePhase() == prerequisite.phase) {
            "review task cannot follow ${prerequisite.kind}/${prerequisite.phase}"
        }
        require(prerequisite.reviewEnabled) {
            "${prerequisite.phase} task did not request review caching"
        }
        require(
            prerequisite.status == CacheLifecycle.COMPLETED ||
                prerequisite.status == CacheLifecycle.FAILED
        ) { "review task cannot be appended before prerequisite completion" }
        require(prerequisite.result != CacheResult.SKIPPED) {
            "review task cannot follow a skipped prerequisite"
        }
        store.findTask(
            sessionId,
            prerequisite.kind,
            CachePhase.REVIEW,
            prerequisite.bookUrl,
        )?.let {
            return CacheSubmission(sessionId, it.taskId)
        }
        val eligible = store.reviewEligibleUnits(sessionId, prerequisiteTaskId)
        if (eligible.isEmpty()) return null
        val request = CacheRequest(
            source = prerequisite.source,
            kind = prerequisite.kind,
            phase = CachePhase.REVIEW,
            bookUrl = prerequisite.bookUrl,
            bookName = prerequisite.bookName,
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
            task.kind.reviewPrerequisitePhase() == task.phase &&
            task.reviewEnabled &&
            task.result != CacheResult.SKIPPED &&
            task.status in setOf(CacheLifecycle.COMPLETED, CacheLifecycle.FAILED)
        ) {
            appendReviewTask(task.sessionId, task.taskId)
        }
        val finalTask = currentTask(submission)
        maybeScheduleReviewResourceGc(finalTask)
        CacheNotificationBridge.finished(
            snapshot = snapshot.value,
            progress = progress.value,
            task = finalTask,
            result = finalTask?.result ?: result,
            error = error,
        )
    }

    /**
     * 本轮“正文 → 评论”收尾钩子：
     * 只有当整本书最后一个 REVIEW task 进入终态、且同书已无其它在跑/排队的
     * REVIEW task 时，才在后台做一次评论资源 GC。BODY 结束后还会追加 REVIEW，
     * 因此 BODY 终态不会触发（[finalTask] 已切换为新追加的 REVIEW task）。
     * 每本书同一时刻至多排一次，避免终态通知风暴重复 GC。
     *
     * 并发：扫描开始前快照 [ReviewResourceEpoch]；REVIEW worker 真正启动时会推进
     * epoch，因此即使扫描期间有“快速开始又快速结束”的 REVIEW，删除阶段也会因
     * epoch 已变化而放弃本次 GC，绝不基于过期引用集合误删新资源。
     */
    private fun maybeScheduleReviewResourceGc(task: CacheTaskState?) {
        if (task == null ||
            task.phase != CachePhase.REVIEW ||
            !CacheLifecycleRules.isTerminal(task.status)
        ) {
            return
        }
        val bookUrl = task.bookUrl
        if (hasActiveReviewTask(bookUrl)) return
        if (!resourceGcScheduled.add(bookUrl)) return
        // 排队成功瞬间快照基准 epoch：协程之后才真正执行，但基准必须固定在
        // “排队这一刻”。从此刻起的任何 REVIEW 启动都会推进 epoch，让本次 GC
        // 在扫描前/删除前的检查中放弃；绝不能在协程启动后才读 epoch（那会把
        // 排队后、执行前启动的 REVIEW 误当成自己的基准，ABA 窗口依旧存在）。
        val epoch = ReviewResourceEpoch.current()
        resourceGcScope.launch {
            try {
                val book = appDb.bookDao.getBook(bookUrl) ?: return@launch
                val result = ReviewSnapshotResourceStore.gc(
                    book = book,
                    expectedEpoch = epoch,
                ) {
                    !hasActiveReviewTask(bookUrl)
                }
                AppLogCacheLogSink.record(
                    CacheLogEvent(
                        type = CacheLogEventType.REVIEW_RESOURCE_GC,
                        detail = "book=$bookUrl " +
                            "aborted=${result.aborted} " +
                            "snapshots=${result.scannedSnapshots} " +
                            "blobs=${result.scannedBlobs} " +
                            "referenced=${result.referencedKeys} " +
                            "removedBlobs=${result.removedBlobs} " +
                            "removedEntries=${result.removedEntries} " +
                            "removedBytes=${result.removedBytes}",
                    )
                )
            } catch (error: Throwable) {
                // 任何一个快照缺 resourceKeys / 读取失败都会让 GC 放弃：
                // 记录原因，不删任何文件，等待下一次 REVIEW 终态再触发。
                AppLogCacheLogSink.record(
                    CacheLogEvent(
                        type = CacheLogEventType.REVIEW_RESOURCE_GC,
                        detail = "book=$bookUrl gc-abort reason=${error.localizedMessage}",
                    )
                )
            } finally {
                resourceGcScheduled.remove(bookUrl)
            }
        }
    }

    private fun hasActiveReviewTask(bookUrl: String): Boolean {
        return snapshot.value.sessions.asSequence()
            .flatMap { it.tasks.asSequence() }
            .any {
                it.phase == CachePhase.REVIEW &&
                    it.bookUrl == bookUrl &&
                    !CacheLifecycleRules.isTerminal(it.status)
            }
    }

    private fun validateUiRequest(request: CacheRequest) {
        if (request.phase == CachePhase.REVIEW) {
            require(request.kind.reviewPrerequisitePhase() != null) {
                "${request.kind} requests cannot use REVIEW phase"
            }
            require(request.source == CacheRequestSource.READER) {
                "REVIEW tasks must be appended by the coordinator or submitted by READER"
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
        if (request.reviewRetryTargets.isNotEmpty()) {
            require(request.phase == CachePhase.REVIEW) {
                "review retry targets require a REVIEW task"
            }
            val unitKeys = request.units.toSet()
            require(request.reviewRetryTargets.all { target ->
                target.unitKey in unitKeys &&
                    target.buttonSources.isNotEmpty() &&
                    target.buttonSources.all { it.isNotBlank() } &&
                    target.buttonSources.distinct().size == target.buttonSources.size
            }) { "review retry targets are invalid" }
            require(request.reviewRetryTargets.map { it.unitKey }.distinct().size ==
                request.reviewRetryTargets.size) {
                "review retry target units are duplicated"
            }
        }
        require(request.kind.supports(request.phase)) {
            "unsupported cache artifact ${request.kind}/${request.phase}"
        }
        if (request.reviewEnabled) {
            require(
                request.phase == CachePhase.REVIEW ||
                    request.kind.reviewPrerequisitePhase() == request.phase
            ) {
                "${request.kind}/${request.phase} cannot enable review caching"
            }
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
