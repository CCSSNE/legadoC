package io.legado.app.help.tts

import io.legado.app.constant.AppLog
import io.legado.app.constant.LogModule

/**
 * TTS 缓存专用日志出口（实时调度 + 批量任务）：统一 [TTS缓存] 前缀与
 * [LogModule.TTS_CACHE] 模块归属，与朗读日志（[朗读] + READ_ALOUD）分离，
 * 避免缓存调度日志刷屏朗读日志。
 */
object TtsCacheLog {

    private const val PREFIX = "[TTS缓存]"

    fun put(message: String?, throwable: Throwable? = null) {
        AppLog.put("$PREFIX $message", throwable, module = LogModule.TTS_CACHE)
    }

    fun debug(message: String?) {
        AppLog.putDebug("$PREFIX $message", module = LogModule.TTS_CACHE)
    }
}
