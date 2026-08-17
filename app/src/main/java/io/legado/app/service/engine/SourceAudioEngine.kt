package io.legado.app.service.engine

import android.content.Context
import io.legado.app.constant.AppLog
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.help.exoplayer.ExoPlayerHelper
import io.legado.app.model.AudioPlay
import io.legado.app.service.ReadAloudEngine
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 融合第一阶段：书源音频引擎实现
 *
 * 将 AudioPlay 的逻辑封装为引擎，与 TTS 引擎平级
 */
class SourceAudioEngine(
    private val context: Context,
    private val callback: ReadAloudEngine.Callback?
) : ReadAloudEngine {

    private var book: Book? = null
    private var chapter: BookChapter? = null
    private var audioUrl: String = ""
    private var isPlaying = false
    private var currentPosition = 0
    private var duration = 0
    private val scope = CoroutineScope(Dispatchers.Main)
    private var progressJob: Job? = null

    override fun getType(): ReadAloudEngine.Type {
        return ReadAloudEngine.Type.SOURCE_AUDIO
    }

    override fun init(book: Book, chapter: BookChapter) {
        this.book = book
        this.chapter = chapter
    }

    override fun play(content: String, startPos: Int) {
        audioUrl = content
        currentPosition = startPos

        if (audioUrl.isEmpty()) {
            callback?.onError("音频地址为空")
            return
        }

        try {
            callback?.onLoading(true)

            // 使用 ExoPlayerHelper 播放音频
            ExoPlayerHelper.play(
                context = context,
                url = audioUrl,
                title = chapter?.title ?: "",
                coverUrl = book?.getDisplayCover(),
                startPosition = startPos.toLong()
            )

            isPlaying = true
            callback?.onLoading(false)

            // 启动进度更新
            startProgressUpdate()

        } catch (e: Exception) {
            AppLog.put("书源音频播放失败\n${e.localizedMessage}", e)
            callback?.onError("播放失败: ${e.localizedMessage}")
            callback?.onLoading(false)
        }
    }

    override fun pause() {
        if (isPlaying) {
            ExoPlayerHelper.pause()
            isPlaying = false
            stopProgressUpdate()
        }
    }

    override fun resume() {
        if (!isPlaying && audioUrl.isNotEmpty()) {
            ExoPlayerHelper.resume()
            isPlaying = true
            startProgressUpdate()
        }
    }

    override fun stop() {
        ExoPlayerHelper.stop()
        isPlaying = false
        currentPosition = 0
        stopProgressUpdate()
    }

    override fun setSpeed(speed: Float) {
        ExoPlayerHelper.setSpeed(speed.coerceIn(0.5f, 3.0f))
    }

    override fun seekTo(position: Int) {
        currentPosition = position
        ExoPlayerHelper.seekTo(position.toLong())
    }

    override fun getCurrentPosition(): Int {
        return ExoPlayerHelper.getCurrentPosition()?.toInt() ?: currentPosition
    }

    override fun getDuration(): Int {
        return ExoPlayerHelper.getDuration()?.toInt() ?: duration
    }

    override fun isPlaying(): Boolean {
        return isPlaying && ExoPlayerHelper.isPlaying()
    }

    override fun release() {
        stop()
        ExoPlayerHelper.release()
    }

    private fun startProgressUpdate() {
        stopProgressUpdate()
        progressJob = scope.launch {
            while (isActive && isPlaying) {
                try {
                    val pos = getCurrentPosition()
                    val dur = getDuration()

                    if (pos >= 0 && dur > 0) {
                        currentPosition = pos
                        duration = dur
                        callback?.onProgressUpdate(pos, dur)

                        // 检查是否播放完成
                        if (pos >= dur - 500) { // 提前500ms判断完成
                            isPlaying = false
                            callback?.onCompletion()
                            break
                        }
                    }

                    delay(500) // 每500ms更新一次进度
                } catch (e: Exception) {
                    AppLog.put("进度更新失败\n${e.localizedMessage}", e)
                }
            }
        }
    }

    private fun stopProgressUpdate() {
        progressJob?.cancel()
        progressJob = null
    }
}
