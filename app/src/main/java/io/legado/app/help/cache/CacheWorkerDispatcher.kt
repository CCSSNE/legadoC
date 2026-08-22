package io.legado.app.help.cache

/** Internal command port between the coordinator and worker adapters. */
internal interface CacheWorkerDispatcher {
    fun start(submission: CacheSubmission)
    fun resume(submission: CacheSubmission, lease: CacheWorkerLease)
    fun pause(submission: CacheSubmission)
    fun cancel(submission: CacheSubmission)
    fun recover(snapshot: CacheSnapshot)
}

internal class CacheWorkerDispatcherImpl(
    private val workerPort: CacheWorkerPort,
) : CacheWorkerDispatcher {

    private val bodyAdapter = TextBodyWorkerAdapter(workerPort)
    private val reviewAdapter = ReviewWorkerAdapter(workerPort)
    private val mediaAdapter = MediaWorkerAdapter(workerPort)

    override fun start(submission: CacheSubmission) {
        val lease = workerPort.acquire(submission)
        if (lease == null) {
            val error = "worker lease was not acquired"
            recordDispatchFailure(submission, "lease_not_acquired")
            if (workerPort.failQueued(submission, error)) {
                CacheCoordinator.notifyTaskFinished(submission, CacheResult.FAILED, error)
            }
            return
        }
        dispatch(submission, lease)
    }

    override fun resume(submission: CacheSubmission, lease: CacheWorkerLease) {
        dispatch(submission, lease)
    }

    override fun pause(submission: CacheSubmission) {
        val task = CacheCoordinator.currentTask(submission) ?: run {
            recordDispatchFailure(submission, "pause_task_missing")
            return
        }
        when {
            task.kind == CacheKind.TEXT && task.phase == CachePhase.BODY -> {
                bodyAdapter.pause(submission)
            }
            task.kind == CacheKind.TEXT && task.phase == CachePhase.REVIEW -> {
                reviewAdapter.pause(submission)
            }
            task.phase == CachePhase.MEDIA -> {
                mediaAdapter.pause(submission)
            }
            else -> {
                val error = "no pause adapter for kind=${task.kind} phase=${task.phase}"
                recordDispatchFailure(submission, error)
            }
        }
    }

    override fun cancel(submission: CacheSubmission) {
        val task = CacheCoordinator.currentTask(submission) ?: run {
            recordDispatchFailure(submission, "cancel_task_missing")
            return
        }
        when {
            task.kind == CacheKind.TEXT && task.phase == CachePhase.BODY -> {
                bodyAdapter.cancel(submission)
            }
            task.kind == CacheKind.TEXT && task.phase == CachePhase.REVIEW -> {
                reviewAdapter.cancel(submission)
            }
            task.phase == CachePhase.MEDIA -> {
                mediaAdapter.cancel(submission)
            }
            else -> {
                val error = "no cancel adapter for kind=${task.kind} phase=${task.phase}"
                recordDispatchFailure(submission, error)
                if (workerPort.confirmCancelled(submission)) {
                    CacheCoordinator.notifyTaskFinished(submission, CacheResult.CANCELLED, error)
                }
            }
        }
    }

    override fun recover(snapshot: CacheSnapshot) {
        snapshot.sessions.asSequence()
            .flatMap { it.tasks.asSequence() }
            .filter { it.status == CacheLifecycle.QUEUED || it.status == CacheLifecycle.INTERRUPTED }
            .forEach { task ->
                val submission = CacheSubmission(task.sessionId, task.taskId)
                if (task.status == CacheLifecycle.QUEUED) {
                    start(submission)
                } else {
                    val lease = workerPort.reclaim(submission)
                    if (lease == null) {
                        recordDispatchFailure(submission, "reclaim_failed")
                    } else {
                        dispatch(submission, lease)
                    }
                }
            }
    }

    private fun dispatch(submission: CacheSubmission, lease: CacheWorkerLease) {
        val task = CacheCoordinator.currentTask(submission)
        if (task == null) {
            recordDispatchFailure(submission, "task_missing")
            if (workerPort.finish(lease, CacheResult.FAILED, "task missing while dispatching")) {
                CacheCoordinator.notifyTaskFinished(lease, CacheResult.FAILED, "task missing while dispatching")
            }
            return
        }
        try {
            when {
                task.kind == CacheKind.TEXT && task.phase == CachePhase.BODY -> {
                    bodyAdapter.start(task, lease)
                }
                task.kind == CacheKind.TEXT && task.phase == CachePhase.REVIEW -> {
                    reviewAdapter.start(task, lease)
                }
                task.phase == CachePhase.MEDIA -> {
                    mediaAdapter.start(task, lease)
                }
                else -> {
                    val message = "no worker adapter for kind=${task.kind} phase=${task.phase}"
                    recordDispatchFailure(submission, message)
                    if (workerPort.finish(lease, CacheResult.FAILED, message)) {
                        CacheCoordinator.notifyTaskFinished(lease, CacheResult.FAILED, message)
                    }
                }
            }
        } catch (error: Throwable) {
            val message = "worker adapter failed: ${error.localizedMessage}"
            AppLogCacheLogSink.record(
                CacheLogEvent(
                    CacheLogEventType.WORKER_DISPATCH_FAILED,
                    task.sessionId,
                    task.taskId,
                    "$message cause=${error::class.simpleName}",
                )
            )
            if (workerPort.finish(lease, CacheResult.FAILED, message)) {
                CacheCoordinator.notifyTaskFinished(lease, CacheResult.FAILED, message)
            }
        }
    }

    private fun recordDispatchFailure(submission: CacheSubmission, detail: String) {
        AppLogCacheLogSink.record(
            CacheLogEvent(
                CacheLogEventType.WORKER_DISPATCH_FAILED,
                submission.sessionId,
                submission.taskId,
                detail,
            )
        )
    }
}
