package io.legado.app.service

import android.app.PendingIntent
import android.os.Bundle
import androidx.lifecycle.lifecycleScope
import io.legado.app.constant.AppLog
import io.legado.app.constant.IntentAction
import io.legado.app.help.book.isAudio
import io.legado.app.help.config.AppConfig
import io.legado.app.model.ReadBook
import io.legado.app.utils.servicePendingIntent
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 融合第一阶段：统一朗读服务
 *
 * 使用引擎管理器统一处理所有类型的朗读（音频书、系统TTS、HTTP TTS）
 */
class UnifiedReadAloudService : BaseReadAloudService() {

    private lateinit var engineManager: ReadAloudEngineManager
    private var playMonitorJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        engineManager = ReadAloudEngineManager(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        playMonitorJob?.cancel()
        engineManager.release()
    }

    override fun onTaskRemoved(rootIntent: android.content.Intent?) {
        super.onTaskRemoved(rootIntent)
        stopSelf()
    }

    override fun onStartCommand(intent: android.content.Intent?, flags: Int, startId: Int): Int {
        intent?.action?.let { action ->
            when (action) {
                IntentAction.play -> {
                    // 处理播放请求
                    startPlayback()
                }
                IntentAction.pause -> {
                    pauseReadAloud(true)
                }
                IntentAction.resume -> {
                    resumeReadAloud()
                }
                IntentAction.stop -> {
                    stopSelf()
                }
                IntentAction.adjustSpeed -> {
                    upSpeechRate()
                }
            }
        }
        return super.onStartCommand(intent, flags, startId)
    }

    private fun startPlayback() {
        val book = ReadBook.book
        val textChapter = ReadBook.curTextChapter

        if (book == null || textChapter == null) {
            AppLog.put("朗读启动失败：书籍或章节为空")
            stopSelf()
            return
        }

        try {
            // 创建合适的引擎
            val engine = engineManager.createEngine(book, textChapter.chapter)

            // 获取内容
            val content = getReadContent(textChapter)

            if (content.isEmpty()) {
                AppLog.put("朗读内容为空")
                nextChapter()
                return
            }

            // 使用引擎播放
            val startPos = 0 // 从头开始
            engine.play(content, startPos)

            // 启动播放监控
            startPlayMonitor()

        } catch (e: Exception) {
            AppLog.put("朗读启动失败\n${e.localizedMessage}", e)
            stopSelf()
        }
    }

    /**
     * 获取要朗读的内容
     */
    private fun getReadContent(textChapter: io.legado.app.ui.book.read.page.entities.TextChapter): String {
        val book = ReadBook.book ?: return ""

        // 音频书：返回音频URL（从 chapter 的 url 字段）
        if (book.isAudio) {
            return textChapter.chapter.url ?: ""
        }

        // 普通书：返回文本内容
        val content = StringBuilder()
        textChapter.pages.forEach { page ->
            page.lines.forEach { line ->
                content.append(line.text).append("\n")
            }
        }
        return content.toString()
    }

    override fun play() {
        super.play()
        startPlayback()
    }

    override fun pauseReadAloud(abandonFocus: Boolean) {
        super.pauseReadAloud(abandonFocus)
        engineManager.pause()
        playMonitorJob?.cancel()
    }

    override fun resumeReadAloud() {
        super.resumeReadAloud()
        engineManager.resume()
        startPlayMonitor()
    }

    private fun startPlayMonitor() {
        playMonitorJob?.cancel()
        playMonitorJob = lifecycleScope.launch {
            while (isActive) {
                // 检查是否播放完成
                if (!engineManager.isPlaying() && !pause) {
                    delay(100)
                    if (!engineManager.isPlaying()) {
                        // 播放下一章
                        nextChapter()
                        break
                    }
                }
                delay(500)
            }
        }
    }

    /**
     * 实现抽象方法：停止播放
     */
    override fun playStop() {
        engineManager.stop()
        playMonitorJob?.cancel()
    }

    /**
     * 实现抽象方法：更新播放速度
     */
    override fun upSpeechRate(reset: Boolean) {
        val book = ReadBook.book ?: return
        val speed = if (book.isAudio) {
            // 音频书使用音频速度
            1.0f // TODO: 从配置获取
        } else {
            // TTS 使用 TTS 速度
            AppConfig.ttsSpeechRate / 10f + 0.5f
        }
        engineManager.setSpeed(speed.coerceIn(0.5f, 3.0f))
    }

    /**
     * 实现抽象方法：创建通知栏的 PendingIntent
     */
    override fun aloudServicePendingIntent(actionStr: String): PendingIntent? {
        return servicePendingIntent<UnifiedReadAloudService>(actionStr)
    }
}
