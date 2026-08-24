package io.legado.app.help.cache

import android.app.Notification
import androidx.core.app.NotificationCompat
import io.legado.app.R
import io.legado.app.constant.AppConst
import io.legado.app.constant.NotificationId
import io.legado.app.help.review.ReviewSnapshotManager
import io.legado.app.ui.book.cache.AudioCacheTaskManager
import io.legado.app.ui.book.cache.CacheActivity
import io.legado.app.utils.ConvertUtils
import io.legado.app.utils.activityPendingIntent
import io.legado.app.utils.broadcastPendingIntent
import splitties.init.appCtx
import splitties.systemservices.notificationManager

internal object CacheNotificationBridge {
    /** The only foreground notification construction path for cache hosts. */
    fun foregroundNotification(): Notification {
        return NotificationCompat.Builder(appCtx, AppConst.channelIdDownload)
            .setSmallIcon(R.drawable.ic_status_bar_r)
            .setContentTitle(appCtx.getString(R.string.offline_cache))
            .setContentIntent(appCtx.activityPendingIntent<CacheActivity>("cacheActivity"))
            .setOnlyAlertOnce(true)
            .setAutoCancel(false)
            .setOngoing(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
    }

    fun started(task: CacheTaskState) {
        renderCurrent()
    }

    fun renderCurrent() {
        render(CacheCoordinator.snapshot.value)
    }

    fun render(snapshot: CacheSnapshot) {
        val active = snapshot.sessions.asSequence()
            .flatMap { it.tasks.asSequence() }
            .filter { !CacheLifecycleRules.isTerminal(it.status) }
            .toList()
        if (active.isEmpty()) return
        renderActive(active)
    }

    /** A notification has one progress bar, so it represents the most recently changed task. */
    private fun renderActive(active: List<CacheTaskState>) {
        val (task, presentation) = active.asSequence()
            .map { it to presentation(it) }
            .maxByOrNull { (_, current) -> current.updatedAt }
            ?: return
        notify(
            title = presentation.title,
            text = presentation.text,
            ongoing = true,
            submission = CacheSubmission(task.sessionId, task.taskId),
            paused = active.all { it.status == CacheLifecycle.PAUSED },
            progress = presentation.progress,
            allTasks = true,
        )
    }

    fun finished(task: CacheTaskState?, result: CacheResult, error: String? = null) {
        val session = task?.let { finishedTask ->
            CacheCoordinator.snapshot.value.sessions.firstOrNull {
                it.sessionId == finishedTask.sessionId
            }
        }
        val finalResult = session?.result ?: task?.result ?: result
        val title = session?.title ?: task?.bookName ?: "Cache task"
        val text = when (finalResult) {
            CacheResult.SUCCEEDED -> "Cache completed"
            CacheResult.PARTIAL -> "Cache partially completed"
            CacheResult.FAILED -> "Cache failed${error?.let { ": $it" }.orEmpty()}"
            CacheResult.CANCELLED -> "Cache stopped"
        }
        val active = CacheCoordinator.snapshot.value.sessions.asSequence()
            .flatMap { it.tasks.asSequence() }
            .filter { !CacheLifecycleRules.isTerminal(it.status) }
            .toList()
        if (active.isNotEmpty()) {
            renderActive(active)
            return
        }
        notify(
            title,
            text,
            ongoing = false,
            submission = null,
            paused = false,
            progress = task?.let(::chapterProgress),
        )
    }

    private fun presentation(task: CacheTaskState): Presentation = when {
        task.kind == CacheKind.TEXT && task.phase == CachePhase.BODY -> bodyPresentation(task)
        task.kind == CacheKind.TEXT && task.phase == CachePhase.REVIEW -> reviewPresentation(task)
        task.phase == CachePhase.MEDIA &&
            (task.kind == CacheKind.AUDIO || task.kind == CacheKind.VIDEO) ->
            mediaPresentation(task)
        else -> error("unsupported cache notification task: ${task.kind}/${task.phase}")
    }

    private fun bodyPresentation(task: CacheTaskState): Presentation {
        val completed = completedChapters(task)
        val total = task.units.size
        return Presentation(
            title = "缓存正文",
            text = chapterText(completed, total),
            progress = chapterProgress(total, completed),
            updatedAt = task.updatedAt,
        )
    }

    private fun mediaPresentation(task: CacheTaskState): Presentation {
        val state = AudioCacheTaskManager.snapshot(task.bookUrl)
        val totalChapters = state?.totalChapters?.takeIf { it > 0 } ?: task.units.size
        val chapter = state?.currentChapterIndex?.takeIf { it > 0 }
            ?: currentChapterOrdinal(task)
        val downloadedBytes = state?.currentChapterBytes ?: 0L
        val totalBytes = state?.currentChapterTotalBytes
        return Presentation(
            title = if (task.kind == CacheKind.VIDEO) "缓存视频" else "缓存音频",
            text = "${formatBytes(downloadedBytes)} / ${totalBytes?.let(::formatBytes) ?: "?"} " +
                chapterText(chapter, totalChapters),
            progress = byteProgress(downloadedBytes, totalBytes),
            updatedAt = state?.updatedAt ?: task.updatedAt,
        )
    }

    private fun reviewPresentation(task: CacheTaskState): Presentation {
        val progress = ReviewSnapshotManager.notificationProgress(
            CacheWorkerLease(task.sessionId, task.taskId, task.generation),
        )
        val chapter = progress?.chapterIndex?.let { index ->
            task.units.indexOfFirst { it.key.chapterIndex == index }
                .takeIf { it >= 0 }
                ?.plus(1)
        } ?: currentChapterOrdinal(task)
        val completed = progress?.completedSnapshots ?: 0
        val total = progress?.totalSnapshots ?: 0
        return Presentation(
            title = "缓存评论",
            text = "快照：$completed/$total  ${chapterText(chapter, task.units.size)}",
            progress = chapterProgress(total, completed),
            updatedAt = progress?.updatedAt ?: task.updatedAt,
        )
    }

    private fun notify(
        title: String,
        text: String,
        ongoing: Boolean,
        submission: CacheSubmission?,
        paused: Boolean,
        progress: Progress? = null,
        allTasks: Boolean = false,
    ) {
        val notification: Notification = NotificationCompat.Builder(
            appCtx,
            AppConst.channelIdDownload,
        )
            .setSmallIcon(R.drawable.ic_status_bar_r)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOnlyAlertOnce(true)
            .setAutoCancel(false)
            .setOngoing(ongoing)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .apply {
                progress?.let { value ->
                    setProgress(value.max, value.current, value.indeterminate)
                }
                if (allTasks) {
                    addAction(
                        if (paused) R.drawable.ic_play_24dp else R.drawable.ic_pause_24dp,
                        appCtx.getString(if (paused) R.string.resume else R.string.pause),
                        actionIntent(
                            if (paused) CacheCoordinatorActionReceiver.ACTION_RESUME
                            else CacheCoordinatorActionReceiver.ACTION_PAUSE,
                            task = null,
                            allTasks = true,
                        ),
                    )
                    addAction(
                        R.drawable.ic_stop_black_24dp,
                        appCtx.getString(R.string.stop),
                        actionIntent(
                            CacheCoordinatorActionReceiver.ACTION_CANCEL,
                            task = null,
                            allTasks = true,
                        ),
                    )
                } else submission?.let { task ->
                    addAction(
                        if (paused) R.drawable.ic_play_24dp else R.drawable.ic_pause_24dp,
                        appCtx.getString(if (paused) R.string.resume else R.string.pause),
                        actionIntent(
                            if (paused) CacheCoordinatorActionReceiver.ACTION_RESUME
                            else CacheCoordinatorActionReceiver.ACTION_PAUSE,
                            task,
                        ),
                    )
                    addAction(
                        R.drawable.ic_stop_black_24dp,
                        appCtx.getString(R.string.stop),
                        actionIntent(CacheCoordinatorActionReceiver.ACTION_CANCEL, task),
                    )
                }
            }
            .build()
        notificationManager.notify(NotificationId.CacheCoordinator, notification)
    }

    private fun chapterProgress(task: CacheTaskState): Progress =
        chapterProgress(task.units.size, completedChapters(task))

    private fun chapterProgress(total: Int, current: Int): Progress {
        return Progress(
            max = total,
            current = current.coerceIn(0, total),
            indeterminate = total == 0,
        )
    }

    private fun byteProgress(currentBytes: Long, totalBytes: Long?): Progress {
        val total = totalBytes?.takeIf { it > 0L }
            ?: return Progress(max = 0, current = 0, indeterminate = true)
        val current = currentBytes.coerceIn(0L, total)
        return Progress(
            max = BYTE_PROGRESS_MAX,
            current = (current.toDouble() / total * BYTE_PROGRESS_MAX).toInt()
                .coerceIn(0, BYTE_PROGRESS_MAX),
        )
    }

    private fun completedChapters(task: CacheTaskState): Int =
        task.units.count { it.status == CacheUnitStatus.SUCCEEDED }

    private fun currentChapterOrdinal(task: CacheTaskState): Int {
        if (task.units.isEmpty()) return 0
        val next = task.units.indexOfFirst { it.status != CacheUnitStatus.SUCCEEDED }
        return if (next >= 0) next + 1 else task.units.size
    }

    private fun chapterText(current: Int, total: Int): String = "$current/$total章"

    private fun formatBytes(bytes: Long): String = ConvertUtils.formatFileSize(bytes.coerceAtLeast(0L))

    private data class Presentation(
        val title: String,
        val text: String,
        val progress: Progress,
        val updatedAt: Long,
    )

    private data class Progress(
        val max: Int,
        val current: Int,
        val indeterminate: Boolean = false,
    )

    private fun actionIntent(
        action: String,
        task: CacheSubmission? = null,
        allTasks: Boolean = false,
    ) =
        appCtx.broadcastPendingIntent<CacheCoordinatorActionReceiver>(action) {
            task?.let {
                putExtra(CacheCoordinatorActionReceiver.EXTRA_SESSION_ID, it.sessionId)
                putExtra(CacheCoordinatorActionReceiver.EXTRA_TASK_ID, it.taskId)
            }
            putExtra(CacheCoordinatorActionReceiver.EXTRA_ALL_TASKS, allTasks)
        }

    private const val BYTE_PROGRESS_MAX = 10_000
}
