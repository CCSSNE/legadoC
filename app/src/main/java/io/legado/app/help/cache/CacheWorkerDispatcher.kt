package io.legado.app.help.cache

/** Internal command port between the coordinator and legacy worker adapters. */
internal interface CacheWorkerDispatcher {
    fun start(submission: CacheSubmission)
    fun resume(submission: CacheSubmission, lease: CacheWorkerLease)
    fun pause(submission: CacheSubmission)
    fun cancel(submission: CacheSubmission)
}

internal class CacheWorkerDispatcherImpl(
    private val workerPort: CacheWorkerPort,
) : CacheWorkerDispatcher {

    private val bodyAdapter = TextBodyWorkerAdapter(workerPort)
    private val reviewAdapter = ReviewWorkerAdapter(workerPort)

    override fun start(submission: CacheSubmission) {
        val lease = workerPort.acquire(submission)
        if (lease == null) {
            recordDispatchFailure(submission, "lease_not_acquired")
            CacheNotificationBridge.finished(
                CacheCoordinator.currentTask(submission),
                CacheResult.FAILED,
                "worker lease was not acquired",
            )
            return
        }
        dispatch(submission, lease)
    }

    override fun resume(submission: CacheSubmission, lease: CacheWorkerLease) {
        dispatch(submission, lease)
    }

    override fun pause(submission: CacheSubmission) {
        val task = CacheCoordinator.currentTask(submission) ?: return
        if (task.kind == CacheKind.TEXT) {
            when (task.phase) {
                CachePhase.BODY -> bodyAdapter.pause(submission)
                CachePhase.REVIEW -> reviewAdapter.pause(submission)
                CachePhase.MEDIA -> Unit
            }
        }
    }

    override fun cancel(submission: CacheSubmission) {
        val task = CacheCoordinator.currentTask(submission) ?: return
        if (task.kind == CacheKind.TEXT) {
            when (task.phase) {
                CachePhase.BODY -> bodyAdapter.cancel(submission)
                CachePhase.REVIEW -> reviewAdapter.cancel(submission)
                CachePhase.MEDIA -> workerPort.confirmCancelled(submission)
            }
        } else {
            workerPort.confirmCancelled(submission)
        }
    }

    private fun dispatch(submission: CacheSubmission, lease: CacheWorkerLease) {
        val task = CacheCoordinator.currentTask(submission)
        if (task == null) {
            recordDispatchFailure(submission, "task_missing")
            workerPort.finish(lease, CacheResult.FAILED, "task missing while dispatching")
            CacheCoordinator.notifyTaskFinished(lease, CacheResult.FAILED, "task missing while dispatching")
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
                else -> {
                    val message = "no worker adapter for kind=${task.kind} phase=${task.phase}"
                    recordDispatchFailure(submission, message)
                    workerPort.finish(lease, CacheResult.FAILED, message)
                    CacheCoordinator.notifyTaskFinished(lease, CacheResult.FAILED, message)
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
            workerPort.finish(lease, CacheResult.FAILED, message)
            CacheCoordinator.notifyTaskFinished(lease, CacheResult.FAILED, message)
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
