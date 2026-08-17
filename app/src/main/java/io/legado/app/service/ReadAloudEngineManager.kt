package io.legado.app.service

import android.content.Context
import io.legado.app.constant.AppLog
import io.legado.app.constant.EventBus
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.model.ReadAloud
import io.legado.app.model.ReadBook
import io.legado.app.service.engine.HttpTtsEngine
import io.legado.app.service.engine.SourceAudioEngine
import io.legado.app.service.engine.SystemTtsEngine
import io.legado.app.utils.postEvent
import io.legado.app.utils.toastOnUi

/**
 * 融合第一阶段：引擎管理器
 *
 * 负责创建、切换和管理不同的朗读引擎
 */
class ReadAloudEngineManager(private val context: Context) {

    private var currentEngine: ReadAloudEngine? = null
    private var currentEngineType: ReadAloudEngine.Type? = null

    private val engineCallback = object : ReadAloudEngine.Callback {
        override fun onProgressUpdate(position: Int, duration: Int) {
            // 发送进度更新事件到 UI
            postEvent(EventBus.READ_ALOUD_PROGRESS, Pair(position, duration))
        }

        override fun onCompletion() {
            // 当前内容播放完成，通知服务播放下一章
            AppLog.put("引擎播放完成")
            // 服务会处理下一章的逻辑
        }

        override fun onError(error: String) {
            AppLog.put("引擎播放错误: $error")
            context.toastOnUi(error)
        }

        override fun onLoading(loading: Boolean) {
            // 可以更新加载状态
        }
    }

    /**
     * 创建合适的引擎
     */
    fun createEngine(book: Book, chapter: BookChapter): ReadAloudEngine {
        val newType = determineEngineType(book)

        // 如果引擎类型没变，复用现有引擎
        if (currentEngine != null && currentEngineType == newType) {
            currentEngine?.init(book, chapter)
            return currentEngine!!
        }

        // 释放旧引擎
        release()

        // 创建新引擎
        currentEngine = when (newType) {
            ReadAloudEngine.Type.SOURCE_AUDIO -> {
                SourceAudioEngine(context, engineCallback)
            }
            ReadAloudEngine.Type.HTTP_TTS -> {
                val httpTts = ReadAloud.httpTTS
                if (httpTts != null) {
                    HttpTtsEngine(context, engineCallback, httpTts.id)
                } else {
                    // 降级到系统 TTS
                    SystemTtsEngine(context, engineCallback)
                }
            }
            ReadAloudEngine.Type.SYSTEM_TTS -> {
                SystemTtsEngine(context, engineCallback)
            }
        }

        currentEngineType = newType
        currentEngine?.init(book, chapter)

        AppLog.put("创建引擎: ${newType.name}")
        return currentEngine!!
    }

    /**
     * 确定应该使用哪种引擎
     */
    private fun determineEngineType(book: Book): ReadAloudEngine.Type {
        // 音频书使用书源音频引擎
        if (book.isAudio) {
            return ReadAloudEngine.Type.SOURCE_AUDIO
        }

        // 普通书检查是否配置了 HTTP TTS
        val httpTts = ReadAloud.httpTTS
        if (httpTts != null) {
            return ReadAloudEngine.Type.HTTP_TTS
        }

        // 默认使用系统 TTS
        return ReadAloudEngine.Type.SYSTEM_TTS
    }

    /**
     * 获取当前引擎
     */
    fun getCurrentEngine(): ReadAloudEngine? {
        return currentEngine
    }

    /**
     * 获取当前引擎类型
     */
    fun getCurrentEngineType(): ReadAloudEngine.Type? {
        return currentEngineType
    }

    /**
     * 播放
     */
    fun play(content: String, startPos: Int = 0) {
        currentEngine?.play(content, startPos)
    }

    /**
     * 暂停
     */
    fun pause() {
        currentEngine?.pause()
    }

    /**
     * 恢复
     */
    fun resume() {
        currentEngine?.resume()
    }

    /**
     * 停止
     */
    fun stop() {
        currentEngine?.stop()
    }

    /**
     * 设置速度
     */
    fun setSpeed(speed: Float) {
        currentEngine?.setSpeed(speed)
    }

    /**
     * 跳转进度
     */
    fun seekTo(position: Int) {
        currentEngine?.seekTo(position)
    }

    /**
     * 获取当前位置
     */
    fun getCurrentPosition(): Int {
        return currentEngine?.getCurrentPosition() ?: 0
    }

    /**
     * 获取总时长/总数
     */
    fun getDuration(): Int {
        return currentEngine?.getDuration() ?: 0
    }

    /**
     * 是否正在播放
     */
    fun isPlaying(): Boolean {
        return currentEngine?.isPlaying() ?: false
    }

    /**
     * 释放引擎
     */
    fun release() {
        currentEngine?.release()
        currentEngine = null
        currentEngineType = null
    }
}
