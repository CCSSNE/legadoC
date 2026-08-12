package io.legado.app.help.illustration

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import io.legado.app.data.entities.Book

/**
 * 阅读页内嵌音频块播放器：全局单实例，同一时间只播一个音频块。
 * 播放中翻页继续播放；退出阅读时调用 [stop]。
 */
object AudioBlockPlayer {

    private var player: ExoPlayer? = null
    private var currentSrc: String? = null

    var isPlaying = false
        private set
    var positionMs = 0L
        private set
    var durationMs = 0L
        private set

    /** 播放状态/进度变化回调（由阅读视图注册，用于重绘音频块；支持多实例同时监听） */
    private val stateChangeListeners = mutableListOf<() -> Unit>()

    fun addStateChangeListener(listener: () -> Unit) {
        if (listener !in stateChangeListeners) {
            stateChangeListeners.add(listener)
        }
    }

    fun removeStateChangeListener(listener: () -> Unit) {
        stateChangeListeners.remove(listener)
    }

    private fun notifyStateChange() {
        stateChangeListeners.toList().forEach { it() }
    }

    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            updateProgress()
            notifyStateChange()
            handler.postDelayed(this, 500L)
        }
    }

    fun playingSrc(): String? = currentSrc

    /** 点击音频块：同一块播放/暂停切换；切到其它块则重新加载 */
    fun toggle(context: Context, book: Book, src: String) {
        if (currentSrc == src && player != null) {
            if (player!!.isPlaying) {
                player!!.pause()
                handler.removeCallbacks(tick)
            } else {
                player!!.play()
                handler.post(tick)
            }
            notifyStateChange()
            return
        }
        stop()
        currentSrc = src
        val file = IllustrationHelp.getImageFile(book, src)
        if (!file.exists()) return
        player = ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
            prepare()
            playWhenReady = true
            addListener(object : Player.Listener {
                override fun onIsPlayingChanged(playing: Boolean) {
                    this@AudioBlockPlayer.isPlaying = playing
                    updateProgress()
                    notifyStateChange()
                    if (playing) {
                        handler.post(tick)
                    } else {
                        handler.removeCallbacks(tick)
                    }
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_READY) {
                        updateProgress()
                        notifyStateChange()
                    } else if (playbackState == Player.STATE_ENDED) {
                        this@AudioBlockPlayer.isPlaying = false
                        handler.removeCallbacks(tick)
                        notifyStateChange()
                    }
                }
            })
        }
        notifyStateChange()
    }

    fun updateProgress() {
        player?.let {
            positionMs = it.currentPosition.coerceAtLeast(0L)
            durationMs = it.duration.coerceAtLeast(0L)
        }
    }

    /** 拖动/点击进度条：跳转到指定位置（未加载时忽略） */
    fun seekTo(ms: Long) {
        val target = ms.coerceAtLeast(0L)
        player?.seekTo(target)
        positionMs = target
        notifyStateChange()
    }

    fun stop() {
        handler.removeCallbacks(tick)
        player?.release()
        player = null
        currentSrc = null
        isPlaying = false
        positionMs = 0L
        durationMs = 0L
    }
}
