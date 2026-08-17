package io.legado.app.service

import android.app.PendingIntent
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.constant.AppConst
import io.legado.app.constant.AppLog
import io.legado.app.constant.IntentAction
import io.legado.app.data.entities.BookChapter
import io.legado.app.help.MediaHelp
import io.legado.app.help.config.AppConfig
import io.legado.app.model.ReadAloud
import io.legado.app.model.ReadBook
import io.legado.app.service.help.ReadBook as ReadBookHelper
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.servicePendingIntent
import io.legado.app.utils.toastOnUi
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
    private var playIndexJob: Job? = null
    private var dsJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        engineManager = ReadAloudEngineManager(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        engineManager.release()
    }

    override fun newReadAloud(dataChanged: Boolean) {
        val book = ReadBook.book ?: return
        val chapter = ReadBook.curTextChapter ?: return

        if (dataChanged) {
            playIndexJob?.cancel()
            playIndexJob = null
        }

        // 创建合适的引擎
        try {
            val engine = engineManager.createEngine(book, chapter.getBookChapter())

            // 获取内容
            val content = getReadContent(chapter)

            if (content.isEmpty()) {
                toastOnUi("内容为空")
                ReadAloud.stop(this)
                return
            }

            // 使用引擎播放
            val startPos = if (dataChanged) 0 else engineManager.getCurrentPosition()
            engine.play(content, startPos)

            // 启动进度监控
            startPlayIndexJob()

        } catch (e: Exception) {
            AppLog.put("朗读启动失败\n${e.localizedMessage}", e)
            toastOnUi("朗读启动失败: ${e.localizedMessage}")
        }
    }

    /**
     * 获取要朗读的内容
     */
    private fun getReadContent(chapter: io.legado.app.model.readBook.TextChapter): String {
        val book = ReadBook.book ?: return ""

        // 音频书：返回音频URL
        if (book.isAudio) {
            val bookChapter = chapter.getBookChapter()
            // 从 chapter 中获取音频 URL
            // 这里简化处理，实际应该通过 AudioPlay 逻辑获取
            return bookChapter.url ?: ""
        }

        // 普通书：返回文本内容
        val content = StringBuilder()
        chapter.getContents().forEach { line ->
            if (line.isNotEmpty()) {
                content.append(line).append("\n")
            }
        }
        return content.toString()
    }

    override fun pauseReadAloud(pause: Boolean) {
        super.pauseReadAloud(pause)
        if (pause) {
            engineManager.pause()
        } else {
            engineManager.resume()
        }
    }

    override fun resumeReadAloud() {
        super.resumeReadAloud()
        engineManager.resume()
        startPlayIndexJob()
    }

    private fun startPlayIndexJob() {
        playIndexJob?.cancel()
        playIndexJob = lifecycleScope.launch {
            while (isActive) {
                // 检查是否播放完成
                if (!engineManager.isPlaying()) {
                    // 播放下一章
                    delay(100)
                    if (!engineManager.isPlaying()) {
                        ReadAloud.nextParagraph(this@UnifiedReadAloudService)
                    }
                    break
                }
                delay(100)
            }
        }
    }

    override fun upMediaSessionMetadata() {
        super.upMediaSessionMetadata()
        val book = ReadBook.book ?: return
        val chapter = ReadBook.durChapterTitle

        mediaSessionCompat?.setMetadata(
            buildMediaMetadata(
                title = chapter,
                subtitle = book.name,
                artist = book.getRealAuthor(),
                mediaUri = book.bookUrl,
                iconUri = book.getDisplayCover()
            )
        )
    }

    /**
     * 更新播放速度
     */
    override fun upSpeechRate() {
        val speed = if (ReadBook.book?.isAudio == true) {
            // 音频书使用音频速度
            AppConfig.readAloudSpeed
        } else {
            // TTS 使用 TTS 速度
            AppConfig.ttsSpeechRate / 10f + 0.5f
        }
        engineManager.setSpeed(speed.coerceIn(0.5f, 3.0f))
    }

    override fun clearTTS() {
        engineManager.stop()
    }

    override fun currentReadAloudChapterIndex(): Int {
        return ReadBook.durChapterIndex
    }

    override fun currentReadAloudChapterPos(): Int {
        return engineManager.getCurrentPosition()
    }

    /**
     * 处理媒体按钮
     */
    override fun onMediaButton(action: String) {
        when (action) {
            IntentAction.play -> {
                if (engineManager.isPlaying()) {
                    pauseReadAloud(true)
                } else {
                    resumeReadAloud()
                }
            }
            IntentAction.pause -> pauseReadAloud(true)
            IntentAction.resume -> resumeReadAloud()
            IntentAction.prev -> ReadAloud.prevParagraph(this)
            IntentAction.next -> ReadAloud.nextParagraph(this)
            IntentAction.adjustSpeed -> upSpeechRate()
            IntentAction.adjustProgress -> {
                // 调整进度
            }
        }
    }
}
