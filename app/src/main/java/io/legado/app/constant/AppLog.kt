package io.legado.app.constant

import android.util.Log
import io.legado.app.BuildConfig
import io.legado.app.help.config.AppConfig
import io.legado.app.utils.LogUtils
import io.legado.app.utils.toastOnUi
import splitties.init.appCtx

object AppLog {

    private const val AI_LOG_PREFIX = "[AI]"
    private val mLogs = arrayListOf<Triple<Long, String, Throwable?>>()

    val logs
        get() = synchronized(this) { mLogs.toList() }

    val aiLogs
        get() = logs.filter { it.second.startsWith("$AI_LOG_PREFIX ") }

    @Synchronized
    fun put(message: String?, throwable: Throwable? = null, toast: Boolean = false) {
        message ?: return
        if (toast) {
            appCtx.toastOnUi(message)
        }
        if (mLogs.size > 100) {
            mLogs.removeLastOrNull()
        }
        if (throwable == null) {
            LogUtils.d("AppLog", message)
        } else {
            LogUtils.d("AppLog", "$message\n${throwable.stackTraceToString()}")
        }
        mLogs.add(0, Triple(System.currentTimeMillis(), message, throwable))
        if (BuildConfig.DEBUG) {
            val stackTrace = Thread.currentThread().stackTrace
            Log.e(stackTrace[3].className, message, throwable)
        }
    }

    fun putAi(message: String?, throwable: Throwable? = null) {
        message ?: return
        put("$AI_LOG_PREFIX $message", throwable)
    }

    @Synchronized
    fun putNotSave(message: String?, throwable: Throwable? = null, toast: Boolean = false) {
        message ?: return
        if (toast) {
            appCtx.toastOnUi(message)
        }
        if (mLogs.size > 100) {
            mLogs.removeLastOrNull()
        }
        mLogs.add(0, Triple(System.currentTimeMillis(), message, throwable))
        if (BuildConfig.DEBUG) {
            val stackTrace = Thread.currentThread().stackTrace
            Log.e(stackTrace[3].className, message, throwable)
        }
    }

    @Synchronized
    fun clear() {
        mLogs.clear()
    }

    @Synchronized
    fun clearAi() {
        mLogs.removeAll { it.second.startsWith("$AI_LOG_PREFIX ") }
    }

    fun formatLogs(logs: List<Triple<Long, String, Throwable?>>): String {
        return logs.joinToString("\n\n") { log ->
            val time = LogUtils.logTimeFormat.format(java.util.Date(log.first))
            val stack = log.third?.let { "\n${it.stackTraceToString()}" }.orEmpty()
            "$time\n${log.second}$stack"
        }
    }

    fun putDebug(message: String?, throwable: Throwable? = null) {
        if (AppConfig.recordLog) {
            put(message, throwable)
        }
    }

}
