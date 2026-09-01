package io.legado.app.constant

import android.util.Base64
import io.legado.app.utils.LogUtils
import io.legado.app.utils.postEvent
import io.legado.app.utils.toastOnUi
import splitties.init.appCtx
import java.io.File

object AppLog {

    private const val AI_LOG_PREFIX = "[AI]"
    private val mLogs = arrayListOf<Entry>()
    private val logFile: File by lazy { File(appCtx.filesDir, "app.log") }

    /**
     * 一条日志：时间、内容、异常和归属模块。
     * 模块在写入时按调用方类名由 LogModule.classify 单点判定。
     */
    data class Entry(
        val time: Long,
        val message: String,
        val throwable: Throwable?,
        val module: LogModule,
    )

    init {
        loadPersisted()
    }

    val logs
        get() = synchronized(this) { mLogs.toList() }

    val aiLogs
        get() = logs.filter { it.message.startsWith("$AI_LOG_PREFIX ") }

    /**
     * 普通日志视图数据：只显示被勾选模块的条目，全部不勾选时为空。
     */
    fun logsForView(shownModules: Set<String>): List<Entry> {
        return synchronized(this) {
            mLogs.filter { shownModules.contains(it.module.name) }
        }
    }

    @Synchronized
    fun put(
        message: String?,
        throwable: Throwable? = null,
        toast: Boolean = false,
        module: LogModule? = null,
    ) {
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
        val entry = Entry(System.currentTimeMillis(), message, throwable, module ?: callerModule())
        mLogs.add(0, entry)
        persist(entry)
        postEvent(EventBus.APP_LOG_CHANGED, mLogs.size)
    }

    fun putAi(message: String?, throwable: Throwable? = null) {
        message ?: return
        put("$AI_LOG_PREFIX $message", throwable, module = LogModule.AI)
        postEvent(EventBus.AI_LOGS_CHANGED, aiLogs.size)
    }

    private fun callerModule(): LogModule {
        val selfClass = AppLog::class.java.name
        val callerClass = Thread.currentThread().stackTrace
            .firstOrNull { !it.className.startsWith(selfClass) && !it.className.startsWith("java.lang.Thread") }
            ?.className
        return LogModule.classify(callerClass)
    }

    @Synchronized
    fun putNotSave(
        message: String?,
        throwable: Throwable? = null,
        toast: Boolean = false,
        module: LogModule? = null,
    ) {
        message ?: return
        if (toast) {
            appCtx.toastOnUi(message)
        }
        if (mLogs.size > 100) {
            mLogs.removeLastOrNull()
        }
        mLogs.add(0, Entry(System.currentTimeMillis(), message, throwable, module ?: callerModule()))
    }

    @Synchronized
    fun clear() {
        mLogs.clear()
        logFile.delete()
        postEvent(EventBus.APP_LOG_CHANGED, 0)
    }

    fun clearAi() {
        synchronized(this) {
            mLogs.removeAll { it.message.startsWith("$AI_LOG_PREFIX ") }
            rewritePersisted()
        }
        postEvent(EventBus.AI_LOGS_CHANGED, 0)
    }

    fun formatLogs(logs: List<Entry>): String {
        return logs.joinToString("\n\n") { log ->
            val time = LogUtils.logTimeFormat.format(java.util.Date(log.time))
            val stack = log.throwable?.let { "\n${it.stackTraceToString()}" }.orEmpty()
            "$time\n${log.message}$stack"
        }
    }

    /** 调试日志始终记录，是否在普通日志弹窗显示由模块勾选决定 */
    @JvmStatic
    @JvmOverloads
    fun putDebug(
        message: String?,
        throwable: Throwable? = null,
        module: LogModule? = null,
    ) {
        put(message, throwable, module = module)
    }

    private fun persist(log: Entry) {
        runCatching {
            val message = Base64.encodeToString(log.message.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            val stack = log.throwable?.stackTraceToString()?.let {
                Base64.encodeToString(it.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            }.orEmpty()
            logFile.appendText("${log.time}\t$message\t$stack\t${log.module.name}\n", Charsets.UTF_8)
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
                    val module = decodeModule(parts.getOrNull(3), message)
                    mLogs.add(Entry(time, message, stack?.let { IllegalStateException(it) }, module))
                }
            }
            mLogs.reverse()
        }.onFailure {
            LogUtils.d("AppLog", "load failed: ${it.localizedMessage}")
        }
    }

    /** 兼容无模块列的历史文件：旧 [AI] 前缀日志归入 AI 模块，其余属当年未分类遗留，归入未分类 */
    private fun decodeModule(raw: String?, message: String): LogModule {
        raw?.let { name -> LogModule.entries.firstOrNull { it.name == name }?.let { return it } }
        return if (message.startsWith(AI_LOG_PREFIX)) LogModule.AI else LogModule.UNCLASSIFIED
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
