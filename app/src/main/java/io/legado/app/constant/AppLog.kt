package io.legado.app.constant

import android.util.Log
import android.util.Base64
import io.legado.app.BuildConfig
import io.legado.app.help.config.AppConfig
import io.legado.app.utils.LogUtils
import io.legado.app.utils.postEvent
import io.legado.app.utils.toastOnUi
import splitties.init.appCtx
import java.io.File

object AppLog {

    private const val AI_LOG_PREFIX = "[AI]"
    private val mLogs = arrayListOf<Triple<Long, String, Throwable?>>()
    private val logFile: File by lazy { File(appCtx.filesDir, "app.log") }

    init {
        loadPersisted()
    }

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
        val log = Triple(System.currentTimeMillis(), message, throwable)
        mLogs.add(0, log)
        persist(log)
        postEvent(EventBus.APP_LOG_CHANGED, mLogs.size)
        if (BuildConfig.DEBUG) {
            val stackTrace = Thread.currentThread().stackTrace
            Log.e(stackTrace[3].className, message, throwable)
        }
    }

    fun putAi(message: String?, throwable: Throwable? = null) {
        message ?: return
        put("$AI_LOG_PREFIX $message", throwable)
        postEvent(EventBus.AI_LOGS_CHANGED, aiLogs.size)
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
        logFile.delete()
        postEvent(EventBus.APP_LOG_CHANGED, 0)
    }

    fun clearAi() {
        synchronized(this) {
            mLogs.removeAll { it.second.startsWith("$AI_LOG_PREFIX ") }
            rewritePersisted()
        }
        postEvent(EventBus.AI_LOGS_CHANGED, 0)
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

    private fun persist(log: Triple<Long, String, Throwable?>) {
        runCatching {
            val message = Base64.encodeToString(log.second.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            val stack = log.third?.stackTraceToString()?.let {
                Base64.encodeToString(it.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            }.orEmpty()
            logFile.appendText("${log.first}\t$message\t$stack\n", Charsets.UTF_8)
        }.onFailure {
            LogUtils.d("AppLog", "persist failed: ${it.localizedMessage}")
        }
    }

    private fun loadPersisted() {
        runCatching {
            if (!logFile.exists()) return
            logFile.readLines(Charsets.UTF_8).takeLast(100).forEach { line ->
                val parts = line.split('\t')
                if (parts.size >= 2) {
                    val time = parts[0].toLongOrNull() ?: return@forEach
                    val message = String(Base64.decode(parts[1], Base64.DEFAULT), Charsets.UTF_8)
                    val stack = parts.getOrNull(2)?.takeIf { it.isNotBlank() }?.let {
                        String(Base64.decode(it, Base64.DEFAULT), Charsets.UTF_8)
                    }
                    mLogs.add(Triple(time, message, stack?.let { IllegalStateException(it) }))
                }
            }
            mLogs.reverse()
        }.onFailure {
            LogUtils.d("AppLog", "load failed: ${it.localizedMessage}")
        }
    }

    private fun rewritePersisted() {
        runCatching {
            logFile.writeText("", Charsets.UTF_8)
            mLogs.asReversed().forEach { persist(it) }
        }.onFailure {
            LogUtils.d("AppLog", "rewrite failed: ${it.localizedMessage}")
        }
    }

}
