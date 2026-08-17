package io.legado.app.service.engine

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import io.legado.app.constant.AppLog
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.help.exoplayer.ExoPlayerHelper
import io.legado.app.service.ReadAloudEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 融合第一阶段：书源音频引擎实现
 *
 * 使用 ExoPlayer 播放书源音频
 */
class SourceAudioEngine(
    private val context: Context,
    private val callback: ReadAloudEngine.Callback?
) : ReadAloudEngine, Player.Listener {

    private var book: Book? = null
    private var chapter: BookChapter? = null
    private var audioUrl: String = ""
    private var isPlayingFlag = false
    private var currentPosition = 0
    private var duration = 0
    private val scope = CoroutineScope(Dispatchers.Main)
    private var progressJob: Job? = null

    private val exoPlayer: ExoPlayer by lazy {
        ExoPlayerHelper.createHttpExoPlayer(context).apply {
            addListener(this@SourceAudioEngine)
        }
    }

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

            // 创建 MediaItem
            val mediaItem = ExoPlayerHelper.createMediaItem(audioUrl, emptyMap())

            // 准备播放
            exoPlayer.setMediaItem(mediaItem)
            exoPlayer.prepare()

            // 跳转到起始位置
            if (startPos > 0) {
                exoPlayer.seekTo(startPos.toLong())
            }

            // 开始播放
            exoPlayer.play()

            isPlayingFlag = true
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
        if (isPlayingFlag) {
            exoPlayer.pause()
            isPlayingFlag = false
            stopProgressUpdate()
        }
    }

    override fun resume() {
        if (!isPlayingFlag && audioUrl.isNotEmpty()) {
            exoPlayer.play()
            isPlayingFlag = true
            startProgressUpdate()
        }
    }

    override fun stop() {
        exoPlayer.stop()
        isPlayingFlag = false
        currentPosition = 0
        stopProgressUpdate()
    }

    override fun setSpeed(speed: Float) {
        exoPlayer.setPlaybackSpeed(speed.coerceIn(0.5f, 3.0f))
    }

    override fun seekTo(position: Int) {
        currentPosition = position
        exoPlayer.seekTo(position.toLong())
    }

    override fun getCurrentPosition(): Int {
        return exoPlayer.currentPosition.toInt()
    }

    override fun getDuration(): Int {
        return exoPlayer.duration.toInt()
    }

    override fun isPlaying(): Boolean {
        return isPlayingFlag && exoPlayer.isPlaying
    }

    override fun release() {
        stop()
        exoPlayer.release()
    }

    // ExoPlayer.Listener 实现
    override fun onPlaybackStateChanged(playbackState: Int) {
        when (playbackState) {
            Player.STATE_ENDED -> {
                isPlayingFlag = false
                callback?.onCompletion()
            }
            Player.STATE_READY -> {
                // 准备就绪
            }
            Player.STATE_BUFFERING -> {
                callback?.onLoading(true)
            }
            Player.STATE_IDLE -> {
                // 空闲
            }
        }
    }

    override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
        AppLog.put("ExoPlayer 错误: ${error.message}", error)
        callback?.onError("播放错误: ${error.message}")
    }

    private fun startProgressUpdate() {
        stopProgressUpdate()
        progressJob = scope.launch {
            while (isActive && isPlayingFlag) {
                try {
                    val pos = getCurrentPosition()
                    val dur = getDuration()

                    if (pos >= 0 && dur > 0) {
                        currentPosition = pos
                        duration = dur
                        callback?.onProgressUpdate(pos, dur)

                        // 检查是否播放完成
                        if (pos >= dur - 500) { // 提前500ms判断完成
                            isPlayingFlag = false
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
