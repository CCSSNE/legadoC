package io.legado.app.service.engine

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import io.legado.app.constant.AppLog
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.help.config.AppConfig
import io.legado.app.service.ReadAloudEngine
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * 融合第一阶段：系统 TTS 引擎实现
 */
class SystemTtsEngine(
    private val context: Context,
    private val callback: ReadAloudEngine.Callback?
) : ReadAloudEngine {

    private var tts: TextToSpeech? = null
    private var book: Book? = null
    private var chapter: BookChapter? = null
    private var paragraphs: List<String> = emptyList()
    private var currentParagraph = 0
    private var isPlaying = false
    private var isInitialized = false
    private val scope = CoroutineScope(Dispatchers.Main)

    init {
        initTts()
    }

    private fun initTts() {
        callback?.onLoading(true)
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.CHINESE
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        // 段落开始
                    }

                    override fun onDone(utteranceId: String?) {
                        // 当前段落完成，播放下一段
                        if (isPlaying && currentParagraph < paragraphs.size - 1) {
                            currentParagraph++
                            callback?.onProgressUpdate(currentParagraph, paragraphs.size)
                            speakCurrentParagraph()
                        } else {
                            // 所有段落完成
                            isPlaying = false
                            callback?.onCompletion()
                        }
                    }

                    override fun onError(utteranceId: String?) {
                        callback?.onError("TTS 朗读错误")
                    }
                })

                isInitialized = true
                callback?.onLoading(false)
            } else {
                callback?.onError("TTS 初始化失败")
                callback?.onLoading(false)
            }
        }
    }

    override fun getType(): ReadAloudEngine.Type {
        return ReadAloudEngine.Type.SYSTEM_TTS
    }

    override fun init(book: Book, chapter: BookChapter) {
        this.book = book
        this.chapter = chapter
    }

    override fun play(content: String, startPos: Int) {
        if (!isInitialized) {
            callback?.onError("TTS 未初始化")
            return
        }

        // 将内容按段落分割
        paragraphs = content.split("\n").filter { it.trim().isNotEmpty() }
        currentParagraph = startPos.coerceIn(0, paragraphs.size - 1)

        // 应用语速设置
        val speed = AppConfig.ttsSpeechRate / 10f + 0.5f
        tts?.setSpeechRate(speed.coerceIn(0.5f, 2.0f))

        isPlaying = true
        callback?.onProgressUpdate(currentParagraph, paragraphs.size)
        speakCurrentParagraph()
    }

    private fun speakCurrentParagraph() {
        if (currentParagraph < paragraphs.size) {
            val text = paragraphs[currentParagraph]
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "paragraph_$currentParagraph")
        }
    }

    override fun pause() {
        if (isPlaying) {
            tts?.stop()
            isPlaying = false
        }
    }

    override fun resume() {
        if (!isPlaying && paragraphs.isNotEmpty()) {
            isPlaying = true
            speakCurrentParagraph()
        }
    }

    override fun stop() {
        tts?.stop()
        isPlaying = false
        currentParagraph = 0
    }

    override fun setSpeed(speed: Float) {
        tts?.setSpeechRate(speed.coerceIn(0.5f, 2.0f))
    }

    override fun seekTo(position: Int) {
        currentParagraph = position.coerceIn(0, paragraphs.size - 1)
        if (isPlaying) {
            tts?.stop()
            speakCurrentParagraph()
        }
        callback?.onProgressUpdate(currentParagraph, paragraphs.size)
    }

    override fun getCurrentPosition(): Int {
        return currentParagraph
    }

    override fun getDuration(): Int {
        return paragraphs.size
    }

    override fun isPlaying(): Boolean {
        return isPlaying && tts?.isSpeaking == true
    }

    override fun release() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        isPlaying = false
    }
}
