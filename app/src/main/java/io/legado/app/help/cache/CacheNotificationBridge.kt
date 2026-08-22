package io.legado.app.help.cache

import android.app.Notification
import androidx.core.app.NotificationCompat
import io.legado.app.R
import io.legado.app.constant.AppConst
import io.legado.app.constant.NotificationId
import io.legado.app.utils.broadcastPendingIntent
import splitties.init.appCtx
import splitties.systemservices.notificationManager

internal object CacheNotificationBridge {
    fun started(task: CacheTaskState) {
        notify(
            title = task.bookName,
            text = "Cache task started: ${task.phase}",
            ongoing = true,
            submission = CacheSubmission(task.sessionId, task.taskId),
            paused = task.status == CacheLifecycle.PAUSED,
            progress = progress(task),
        )
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
        notify(
            title = "Offline cache",
            text = "$names: $done/$total",
            ongoing = true,
            submission = CacheSubmission(task.sessionId, task.taskId),
            paused = task.status == CacheLifecycle.PAUSED,
            progress = Progress(total, done),
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
            notify(
                title = "Offline cache",
                text = "$title: $text; active ${done}/${total}",
                ongoing = true,
                submission = CacheSubmission(first.sessionId, first.taskId),
                paused = first.status == CacheLifecycle.PAUSED,
                progress = Progress(total, done),
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
    ) {
        val notification: Notification = NotificationCompat.Builder(
            appCtx,
            AppConst.channelIdDownload,
        )
            .setSmallIcon(R.drawable.ic_status_bar_r)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOnlyAlertOnce(false)
            .setAutoCancel(false)
            .setOngoing(ongoing)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .apply {
                progress?.let { value ->
                    setProgress(value.max, value.current, value.indeterminate)
                }
                submission?.let { task ->
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

    private fun actionIntent(action: String, task: CacheSubmission) =
        appCtx.broadcastPendingIntent<CacheCoordinatorActionReceiver>(action) {
            putExtra(CacheCoordinatorActionReceiver.EXTRA_SESSION_ID, task.sessionId)
            putExtra(CacheCoordinatorActionReceiver.EXTRA_TASK_ID, task.taskId)
        }
}
