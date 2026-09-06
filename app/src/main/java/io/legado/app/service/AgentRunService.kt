package io.legado.app.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import io.legado.app.R
import io.legado.app.help.agent.AgentRuntime
import io.legado.app.utils.startForegroundServiceCompat
import splitties.init.appCtx

class AgentRunService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(NotificationChannel(CHANNEL, "Agent 运行任务", NotificationManager.IMPORTANCE_LOW))
        startForeground(NOTIFICATION, NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_web_service_noti)
            .setContentTitle("Agent 正在运行").setContentText("可在聊天页停止，或在 Agent 模式中暂停与继续")
            .setOngoing(true).build())
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!AgentRuntime.hasInternalRuns()) stopSelf()
        return START_NOT_STICKY
    }
    override fun onTimeout(startId: Int, fgsType: Int) {
        AgentRuntime.stopAll("Android 前台服务运行配额已到期；系统终止当前任务，未自动重放")
        stopSelf()
    }
    companion object {
        private const val CHANNEL = "agent_runs"
        private const val NOTIFICATION = 19242
        fun start() = appCtx.startForegroundServiceCompat(Intent(appCtx, AgentRunService::class.java))
        fun finish() { appCtx.stopService(Intent(appCtx, AgentRunService::class.java)) }
    }
}
