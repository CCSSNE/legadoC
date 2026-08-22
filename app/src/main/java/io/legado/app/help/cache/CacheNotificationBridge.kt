package io.legado.app.help.cache

import android.app.Notification
import androidx.core.app.NotificationCompat
import io.legado.app.R
import io.legado.app.constant.AppConst
import io.legado.app.constant.NotificationId
import io.legado.app.ui.book.cache.CacheActivity
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
        render(CacheCoordinator.snapshot.value)
    }

    fun render(snapshot: CacheSnapshot) {
        val active = snapshot.sessions.asSequence()
            .flatMap { it.tasks.asSequence() }
            .filter { !CacheLifecycleRules.isTerminal(it.status) }
            .toList()
        if (active.isEmpty()) return
        val done = active.sumOf { task ->
            task.units.count { it.status == CacheUnitStatus.SUCCEEDED }
        }
        val total = active.sumOf { it.units.size }
        val names = active.map { it.bookName }.distinct().take(2).joinToString(", ")
        val task = active.first()
        val allPaused = active.all { it.status == CacheLifecycle.PAUSED }
        notify(
            title = "Offline cache",
            text = "All cache tasks: $names: $done/$total",
            ongoing = true,
            submission = CacheSubmission(task.sessionId, task.taskId),
            paused = allPaused,
            progress = Progress(total, done),
            allTasks = true,
        )
    }

    fun finished(task: CacheTaskState?, result: CacheResult, error: String? = null) {
        val title = task?.bookName ?: "Cache task"
        val text = when (result) {
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
            val first = active.first()
            val done = active.sumOf { current ->
                current.units.count { it.status == CacheUnitStatus.SUCCEEDED }
            }
            val total = active.sumOf { it.units.size }
            val allPaused = active.all { it.status == CacheLifecycle.PAUSED }
            notify(
                title = "Offline cache",
                text = "$title: $text; all active ${done}/${total}",
                ongoing = true,
                submission = CacheSubmission(first.sessionId, first.taskId),
                paused = allPaused,
                progress = Progress(total, done),
                allTasks = true,
            )
            return
        }
        notify(
            title,
            text,
            ongoing = false,
            submission = null,
            paused = false,
            progress = task?.let(::progress),
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

    private fun progress(task: CacheTaskState): Progress {
        val total = task.units.size
        return Progress(
            max = total,
            current = task.units.count { it.status == CacheUnitStatus.SUCCEEDED },
            indeterminate = total == 0,
        )
    }

    private data class Progress(
        val max: Int,
        val current: Int,
        val indeterminate: Boolean,
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
}
