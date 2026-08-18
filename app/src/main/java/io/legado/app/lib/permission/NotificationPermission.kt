package io.legado.app.lib.permission

import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import io.legado.app.R
import io.legado.app.constant.AppConst

object NotificationPermission {

    private data class PendingRequest(
        val onGranted: () -> Unit,
        val onDenied: () -> Unit,
    )

    private val lock = Any()
    private val pendingRequests = ArrayList<PendingRequest>()
    private var requestInFlight = false

    fun ensure(
        context: Context,
        onGranted: () -> Unit,
        onDenied: () -> Unit,
    ) {
        var grantImmediately = false
        var denyImmediately = false
        var startRequest = false
        synchronized(lock) {
            if (isEnabled(context)) {
                grantImmediately = true
            } else if (isDownloadChannelDisabled(context)) {
                denyImmediately = true
            } else {
                pendingRequests += PendingRequest(onGranted, onDenied)
                if (!requestInFlight) {
                    requestInFlight = true
                    startRequest = true
                }
            }
        }
        if (grantImmediately) {
            onGranted()
            return
        }
        if (denyImmediately) {
            onDenied()
            return
        }
        if (startRequest) {
            request(
                onGranted = { resolve(true) },
                onDenied = { resolve(false) },
            )
        }
    }

    private fun isEnabled(context: Context): Boolean {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            return false
        }
        return !isDownloadChannelDisabled(context)
    }

    private fun isDownloadChannelDisabled(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            !NotificationManagerCompat.from(context).areNotificationsEnabled()
        ) {
            return false
        }
        return context.getSystemService(NotificationManager::class.java)
            ?.getNotificationChannel(AppConst.channelIdDownload)
            ?.importance == NotificationManager.IMPORTANCE_NONE
    }

    private fun resolve(granted: Boolean) {
        val requests = synchronized(lock) {
            requestInFlight = false
            pendingRequests.toList().also { pendingRequests.clear() }
        }
        requests.forEach { request ->
            if (granted) request.onGranted() else request.onDenied()
        }
    }

    fun request(
        onGranted: () -> Unit,
        onDenied: () -> Unit,
    ) {
        PermissionsCompat.Builder()
            .addPermissions(Permissions.POST_NOTIFICATIONS)
            .rationale(R.string.notification_permission_rationale)
            .onGranted(onGranted)
            .onDenied { onDenied() }
            .onError { onDenied() }
            .request()
    }
}
