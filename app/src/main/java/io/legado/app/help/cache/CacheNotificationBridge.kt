package io.legado.app.help.cache

import android.app.Notification
import androidx.core.app.NotificationCompat
import io.legado.app.R
import io.legado.app.constant.AppConst
import io.legado.app.constant.NotificationId
import splitties.init.appCtx
import splitties.systemservices.notificationManager

/** Minimal durable notification for coordinator-owned work until the unified renderer lands. */
internal object CacheNotificationBridge {
    fun started(task: CacheTaskState) {
        notify(
            title = task.bookName,
            text = "缓存任务已开始：${task.phase}",
            ongoing = true,
        )
    }

    fun finished(task: CacheTaskState?, result: CacheResult, error: String? = null) {
        val title = task?.bookName ?: "缓存任务"
        val text = when (result) {
            CacheResult.SUCCEEDED -> "缓存完成"
            CacheResult.PARTIAL -> "缓存部分完成"
            CacheResult.FAILED -> "缓存失败${error?.let { "：$it" }.orEmpty()}"
            CacheResult.CANCELLED -> "缓存已停止"
        }
        notify(title, text, ongoing = false)
    }

    private fun notify(title: String, text: String, ongoing: Boolean) {
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
            .build()
        notificationManager.notify(NotificationId.CacheBookService, notification)
    }
}
