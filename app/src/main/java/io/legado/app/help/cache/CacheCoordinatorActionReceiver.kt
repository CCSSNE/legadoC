package io.legado.app.help.cache

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

internal class CacheCoordinatorActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.getBooleanExtra(EXTRA_ALL_TASKS, false)) {
            when (intent.action) {
                ACTION_PAUSE -> CacheCoordinator.pauseAll()
                ACTION_RESUME -> CacheCoordinator.resumeAll()
                ACTION_CANCEL -> CacheCoordinator.cancelAll()
            }
            return
        }
        val sessionId = intent.getStringExtra(EXTRA_SESSION_ID) ?: return
        val taskId = intent.getStringExtra(EXTRA_TASK_ID) ?: return
        val submission = CacheSubmission(sessionId, taskId)
        when (intent.action) {
            ACTION_PAUSE -> CacheCoordinator.pause(submission)
            ACTION_RESUME -> CacheCoordinator.resume(submission)
            ACTION_CANCEL -> CacheCoordinator.cancel(submission)
        }
    }

    companion object {
        const val ACTION_PAUSE = "io.legado.app.cache.PAUSE"
        const val ACTION_RESUME = "io.legado.app.cache.RESUME"
        const val ACTION_CANCEL = "io.legado.app.cache.CANCEL"
        const val EXTRA_SESSION_ID = "sessionId"
        const val EXTRA_TASK_ID = "taskId"
        const val EXTRA_ALL_TASKS = "allTasks"
    }
}
