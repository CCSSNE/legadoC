package io.legado.app.help.cache

import android.app.Notification
import androidx.core.app.NotificationCompat
import io.legado.app.R
import io.legado.app.constant.AppConst
import io.legado.app.constant.NotificationId
import io.legado.app.data.appDb
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

    fun render(snapshot: CacheSnapshot, progress: CacheProgressSnapshot) {
        val active = snapshot.sessions.filter { session ->
            session.tasks.any { !CacheLifecycleRules.isTerminal(it.status) }
        }
        if (active.isEmpty()) return
        renderActive(active, progress)
    }

    /** The Store selects one stable Session; task/unit progress is only a child of that Session. */
    private fun renderActive(activeSessions: List<CacheSessionState>, progress: CacheProgressSnapshot) {
        val session = progress.displaySessionId
            ?.let { sessionId -> activeSessions.firstOrNull { it.sessionId == sessionId } }
            ?: activeSessions.first()
        val activeTasks = session.tasks.filter { !CacheLifecycleRules.isTerminal(it.status) }
        val display = progress.display?.takeIf { state ->
            activeTasks.any { task ->
                task.sessionId == state.sessionId &&
                    task.taskId == state.taskId &&
                    task.generation == state.generation
            }
        }
        val task = display?.let { state ->
            activeTasks.first { it.taskId == state.taskId }
        } ?: activeTasks.first()
        val presentation = presentation(task, display)
        notify(
            title = presentation.title,
            text = presentation.text,
            ongoing = true,
            submission = CacheSubmission(task.sessionId, task.taskId),
            paused = activeSessions.asSequence()
                .flatMap { it.tasks.asSequence() }
                .filter { !CacheLifecycleRules.isTerminal(it.status) }
                .all { it.status == CacheLifecycle.PAUSED },
            progress = presentation.progress,
            allTasks = true,
        )
    }

    fun finished(
        snapshot: CacheSnapshot,
        progress: CacheProgressSnapshot,
        task: CacheTaskState?,
        result: CacheResult,
        error: String? = null,
    ) {
        val session = task?.let { finishedTask ->
            snapshot.sessions.firstOrNull {
                it.sessionId == finishedTask.sessionId
            }
        }
        val finalResult = session?.result ?: task?.result ?: result
        val title = session?.title ?: task?.bookName ?: "Cache task"
        val text = when (finalResult) {
            CacheResult.SUCCEEDED -> "Cache completed"
            CacheResult.PARTIAL -> "Cache partially completed"
            CacheResult.FAILED -> "Cache failed${error?.let { ": $it" }.orEmpty()}"
            CacheResult.SKIPPED -> "Cache skipped${error?.let { ": $it" }.orEmpty()}"
            CacheResult.CANCELLED -> "Cache stopped"
        }
        val active = snapshot.sessions.filter { activeSession ->
            activeSession.tasks.any { !CacheLifecycleRules.isTerminal(it.status) }
        }
        if (active.isNotEmpty()) {
            renderActive(active, progress)
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

    private fun presentation(
        task: CacheTaskState,
        progress: CacheProgressState?,
    ): Presentation = when {
        task.kind == CacheKind.TEXT && task.phase == CachePhase.BODY -> bodyPresentation(task, progress)
        task.phase == CachePhase.REVIEW && task.kind.reviewPrerequisitePhase() != null ->
            reviewPresentation(task, progress)
        task.phase == CachePhase.MEDIA &&
            (task.kind == CacheKind.AUDIO || task.kind == CacheKind.VIDEO) ->
            mediaPresentation(task, progress)
        task.phase == CachePhase.TTS && task.kind.ttsPrerequisitePhase() != null ->
            ttsPresentation(task, progress)
        else -> error("unsupported cache notification task: ${task.kind}/${task.phase}")
    }

    private fun bodyPresentation(
        task: CacheTaskState,
        progress: CacheProgressState?,
    ): Presentation {
        val completed = (progress?.current ?: completedChapters(task).toLong()).toInt()
        val total = (progress?.total ?: task.units.size.toLong()).toInt()
        return Presentation(
            title = "缓存正文",
            text = "${displayUnitText(task, progress)}  ${chapterText(completed, total)}",
            progress = chapterProgress(total, completed),
        )
    }

    private fun mediaPresentation(
        task: CacheTaskState,
        progress: CacheProgressState?,
    ): Presentation {
        val downloadedBytes = progress?.current ?: 0L
        val totalBytes = progress?.total
        return Presentation(
            title = if (task.kind == CacheKind.VIDEO) "缓存视频" else "缓存音频",
            text = "${displayUnitText(task, progress)}  " +
                "${formatBytes(downloadedBytes)} / ${totalBytes?.let(::formatBytes) ?: "?"} " +
                chapterText(completedChapters(task), task.units.size),
            progress = byteProgress(downloadedBytes, totalBytes),
        )
    }

    private fun reviewPresentation(
        task: CacheTaskState,
        progress: CacheProgressState?,
    ): Presentation {
        val completed = progress?.current?.toInt() ?: 0
        val total = progress?.total?.toInt()
        val failed = progress?.failed?.toInt() ?: 0
        val chapters = reviewChapterProgress(task)
        val snapshotText = total?.let { "$completed/$it" } ?: "处理中"
        return Presentation(
            title = "缓存评论",
            text = "${displayUnitText(task, progress)}  快照：$snapshotText  失败：$failed  " +
                chapterText(chapters.completed, chapters.total),
            progress = total?.let { chapterProgress(it, completed) }
                ?: Progress(max = 0, current = 0, indeterminate = true),
        )
    }

    private fun ttsPresentation(
        task: CacheTaskState,
        progress: CacheProgressState?,
    ): Presentation {
        val completed = progress?.current?.toInt() ?: 0
        val total = progress?.total?.toInt()
        val failed = progress?.failed?.toInt() ?: 0
        val chapters = ttsChapterProgress(task)
        val unitText = total?.let { "$completed/$it" } ?: "处理中"
        return Presentation(
            title = "缓存TTS音频",
            text = "${displayUnitText(task, progress)}  单元：$unitText  失败：$failed  " +
                chapterText(chapters.completed, chapters.total),
            progress = total?.let { chapterProgress(it, completed) }
                ?: Progress(max = 0, current = 0, indeterminate = true),
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

    private fun displayUnitText(task: CacheTaskState, progress: CacheProgressState?): String {
        val unit = progress?.unitKey
            ?: task.units.firstOrNull {
                it.status == CacheUnitStatus.PENDING ||
                    it.status == CacheUnitStatus.RUNNING ||
                    it.status == CacheUnitStatus.REVIEW_ELIGIBLE
            }?.key
            ?: return "当前章节：无"
        val chapter = appDb.bookChapterDao.getChapter(task.bookUrl, unit.chapterIndex)
        return chapter?.title?.takeIf { it.isNotBlank() }?.let { title ->
            "第${unit.chapterIndex + 1}章 $title"
        } ?: "第${unit.chapterIndex + 1}章"
    }

    /** REVIEW owns its eligible chapter set, so both numerator and denominator come from it. */
    private fun reviewChapterProgress(review: CacheTaskState): ChapterProgress {
        return ChapterProgress(
            completed = completedChapters(review),
            total = review.units.size,
        )
    }

    /** TTS 同样只认自己任务的章节集，x/y章 不借用前置 BODY 任务的总数。 */
    private fun ttsChapterProgress(task: CacheTaskState): ChapterProgress {
        return ChapterProgress(
            completed = completedChapters(task),
            total = task.units.size,
        )
    }

    private fun chapterText(current: Int, total: Int): String = "$current/${total}章"

    private fun formatBytes(bytes: Long): String = ConvertUtils.formatFileSize(bytes.coerceAtLeast(0L))

    private data class Presentation(
        val title: String,
        val text: String,
        val progress: Progress,
    )

    private data class ChapterProgress(
        val completed: Int,
        val total: Int,
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
