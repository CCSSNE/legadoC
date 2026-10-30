package io.legado.app.help

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import io.legado.app.help.config.AppConfig
import io.legado.app.utils.LogUtils

object AppFreezeMonitor {

    private const val TAG = "AppFreezeMonitor"

    val handler by lazy {
        Handler(HandlerThread("AppFreezeMonitor").apply { start() }.looper)
    }

    val screenStatusReceiver by lazy {
        ScreenStatusReceiver()
    }

    private var registeredReceiver = false
    private var monitoring = false
    private var previous = 0L

    private val detectRunnable = object : Runnable {
        override fun run() {
            val current = SystemClock.uptimeMillis()
            val elapsed = current - previous
            val extra = elapsed - 3000

            if (extra > 300) {
                LogUtils.d(TAG, "检测到应用被系统冻结，时长：$extra 毫秒")
            }

            previous = current

            if (AppConfig.debugLogEnabled) {
                handler.postDelayed(this, 3000)
            } else {
                monitoring = false
            }
        }
    }

    private fun stop() {
        handler.removeCallbacks(detectRunnable)
        monitoring = false
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    fun init(context: Context) {
        if (!AppConfig.debugLogEnabled) {
            if (registeredReceiver) {
                registeredReceiver = false
                context.unregisterReceiver(screenStatusReceiver)
            }
            stop()
            return
        }

        if (!registeredReceiver) {
            registeredReceiver = true
            context.registerReceiver(screenStatusReceiver, screenStatusReceiver.filter)
        }

        // init 会被 App.onCreate 与偏好变更多次调用；同一时刻只能维持一条检测链，
        // 否则每次重复 init 都会叠加一串 postDelayed 定时链（检测逻辑缺陷修复）
        if (monitoring) return
        monitoring = true
        previous = SystemClock.uptimeMillis()
        handler.postDelayed(detectRunnable, 3000)
    }

    class ScreenStatusReceiver : BroadcastReceiver() {

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }

        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_ON -> LogUtils.d(TAG, "SCREEN_ON")
                Intent.ACTION_SCREEN_OFF -> LogUtils.d(TAG, "SCREEN_OFF")
            }
        }
    }

}
