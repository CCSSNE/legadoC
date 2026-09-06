package io.legado.app.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import io.legado.app.R
import io.legado.app.help.agent.AgentConfig
import io.legado.app.help.agent.AgentStore
import io.legado.app.help.agent.mcp.AgentMcpServer
import io.legado.app.utils.startForegroundServiceCompat
import org.json.JSONObject
import splitties.init.appCtx

class AgentMcpService : Service() {
    private val listeners = java.util.concurrent.ConcurrentHashMap<String, Pair<String, AgentMcpServer>>()
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(NotificationChannel(CHANNEL, "Agent MCP Server", NotificationManager.IMPORTANCE_LOW))
        startForeground(NOTIFICATION, NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_web_service_noti)
            .setContentTitle("Agent MCP Server").setContentText("独立能力服务；内部 Agent 开关不影响对外监听")
            .setOngoing(true).build())
    }

    @Synchronized
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        AgentConfig.initialize()
        val configurations = AgentStore.dao.documents("mcp.servers").associate { it.key to JSONObject(it.json) }
        listeners.keys.toList().forEach { id ->
            if (configurations[id]?.getBoolean("enabled") != true || listeners.getValue(id).first != configurations[id].toString()) {
                listeners.remove(id)!!.second.stop()
                AgentStore.put("mcp.server.status", id, JSONObject().put("state", "stopped"))
            }
        }
        configurations.forEach { (id, config) ->
            if (config.getBoolean("enabled") && !listeners.containsKey(id)) {
                try {
                    require(config.getString("apiKey").isNotBlank()) { "访问密钥不能为空" }
                    require(config.getInt("port") in 1..65535 && config.getInt("pageSize") > 0) { "端口或分页配置无效" }
                    val server = AgentMcpServer(id, config)
                    server.start()
                    listeners[id] = config.toString() to server
                    AgentStore.put("mcp.server.status", id, JSONObject().put("state", "running")
                        .put("address", "http://${config.getString("address")}:${config.getInt("port")}/mcp"))
                } catch (error: Exception) {
                    AgentStore.put("mcp.server.status", id, JSONObject().put("state", "error").put("error", error.stackTraceToString()))
                }
            }
        }
        if (listeners.isEmpty()) stopSelf()
        return START_STICKY
    }

    @Synchronized
    override fun onDestroy() {
        listeners.forEach { (id, listener) ->
            listener.second.stop()
            AgentStore.put("mcp.server.status", id, JSONObject().put("state", "stopped"))
        }
        listeners.clear()
        instance = null
        super.onDestroy()
    }

    @Synchronized
    override fun onTimeout(startId: Int, fgsType: Int) {
        listeners.forEach { (id, listener) ->
            listener.second.stop()
            AgentStore.put("mcp.server.status", id, JSONObject().put("state", "error")
                .put("error", "Android 前台服务运行配额已到期，监听被系统终止；请用户重新启动"))
        }
        listeners.clear()
        stopSelf()
    }

    companion object {
        @Volatile private var instance: AgentMcpService? = null
        private const val CHANNEL = "agent_mcp"
        private const val NOTIFICATION = 19241
        fun activeRequests(): Int = instance?.listeners?.values?.sumOf { it.second.activeRequests() } ?: 0
        fun stopListeners() {
            instance?.let { service ->
                synchronized(service) {
                    service.listeners.values.forEach { it.second.stop() }
                    service.listeners.clear()
                    service.stopSelf()
                }
            }
        }
        fun refresh() {
            appCtx.startForegroundServiceCompat(Intent(appCtx, AgentMcpService::class.java))
        }
    }
}
