package io.legado.app.help.bgm

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import io.legado.app.constant.AppLog

/**
 * 听书背景音乐播放控制器（适配 joestar817/legado_NG BgmPlaybackController 最小子集）。
 *
 * 硬约束（NG/MD3 两路共同验证）：
 * - 第二 ExoPlayer 旁路混音，绝不抢音频焦点（setAudioAttributes 第二参 false），
 *   焦点仍在朗读服务手里；
 * - 不注册 BECOMING_NOISY（拔耳机暂停由主服务统一处理并回调 onPlaybackPaused）；
 * - 连续失败 3 次整段停（失败预算），单次成功即清零。
 *
 * 简化点：NG 的 BgmSpeechGate 依赖引擎「真实出声」回调，本仓三引擎无该回调，
 * 最小版以朗读服务 play/pause 为节拍（段间隙不停 BGM），语义级静音留作后续增强。
 */
class BgmPlaybackController(private val context: Context) {

    private val mainHandler = Handler(Looper.getMainLooper())

    private var player: ExoPlayer? = null
    private var settings: BgmSettings = BgmSettingsStore.read()
    private var queueSignature: List<String>? = null
    private var consecutiveFailures = 0
    private var currentVolume = 0f
    private var fadeRunnable: Runnable? = null

    /** 朗读开始/恢复（BaseReadAloudService.play / resumeReadAloudInternal） */
    fun onPlaybackStarted() {
        settings = BgmSettingsStore.read()
        if (!settings.enabled) {
            stopPlayer()
            return
        }
        if (settings.tracks.isEmpty()) {
            AppLog.put("BGM 未选择曲目，跳过播放")
            return
        }
        mainHandler.post {
            val p = ensurePlayer() ?: return@post
            p.playWhenReady = true
            p.play()
            fadeTo(settings.volume)
        }
    }

    /** 朗读暂停（pauseReadAloud / BECOMING_NOISY 等） */
    fun onPlaybackPaused() {
        mainHandler.post {
            val p = player ?: return@post
            fadeTo(0f)
            mainHandler.postDelayed({
                player?.pause()
            }, FADE_TOTAL_MS + 40)
        }
    }

    /** 设置变化（prefs 监听转发） */
    fun reloadSettings() {
        mainHandler.post {
            val wasActive = player?.isPlaying == true
            settings = BgmSettingsStore.read()
            if (!settings.enabled) {
                stopPlayer()
                return@post
            }
            if (!wasActive) {
                return@post
            }
            if (queueSignature != settings.tracks) {
                // 曲目列表变了：整队列重建（下次起播从头开始，属可接受语义）
                stopPlayer()
                val fresh = ensurePlayer() ?: return@post
                fresh.playWhenReady = true
                fresh.play()
                fadeTo(settings.volume)
                return@post
            }
            val p = ensurePlayer() ?: return@post
            p.playWhenReady = true
            p.play()
            fadeTo(settings.volume)
        }
    }

    fun release() {
        mainHandler.post {
            fadeRunnable?.let { mainHandler.removeCallbacks(it) }
            fadeRunnable = null
            player?.release()
            player = null
            queueSignature = null
        }
    }

    private fun stopPlayer() {
        mainHandler.post {
            fadeRunnable?.let { mainHandler.removeCallbacks(it) }
            fadeRunnable = null
            player?.release()
            player = null
            queueSignature = null
        }
    }

    private fun ensurePlayer(): ExoPlayer? {
        player?.let { return it }
        val tracks = settings.tracks
        if (tracks.isEmpty()) {
            return null
        }
        val newPlayer = ExoPlayer.Builder(context).build().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                false
            )
            when (settings.repeatMode) {
                BgmRepeatMode.ONE -> {
                    repeatMode = Player.REPEAT_MODE_ONE
                    shuffleModeEnabled = false
                }

                BgmRepeatMode.SHUFFLE -> {
                    repeatMode = Player.REPEAT_MODE_ALL
                    shuffleModeEnabled = true
                }

                BgmRepeatMode.LIST -> {
                    repeatMode = Player.REPEAT_MODE_ALL
                    shuffleModeEnabled = false
                }
            }
            addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_READY) {
                        consecutiveFailures = 0
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    consecutiveFailures++
                    AppLog.put("BGM 播放出错(${consecutiveFailures}):${error.localizedMessage}", error)
                    if (consecutiveFailures >= FAILURE_BUDGET) {
                        AppLog.put("BGM 连续失败 $FAILURE_BUDGET 次，停止背景音乐")
                        stopPlayer()
                    }
                }
            })
            volume = 0f
            currentVolume = 0f
        }
        val items = tracks.map { MediaItem.fromUri(it) }
        newPlayer.setMediaItems(items)
        newPlayer.prepare()
        player = newPlayer
        queueSignature = tracks
        return newPlayer
    }

    private fun fadeTo(target: Float) {
        val p = player ?: return
        fadeRunnable?.let { mainHandler.removeCallbacks(it) }
        fadeRunnable = null
        val from = currentVolume
        if (from == target) {
            p.volume = target
            return
        }
        var step = 0
        val runnable = object : Runnable {
            override fun run() {
                step++
                currentVolume = from + (target - from) * step / FADE_STEPS
                player?.volume = currentVolume
                if (step < FADE_STEPS && player != null) {
                    mainHandler.postDelayed(this, FADE_STEP_MS)
                }
            }
        }
        fadeRunnable = runnable
        mainHandler.postDelayed(runnable, FADE_STEP_MS)
    }

    companion object {
        private const val FADE_STEPS = 20
        private const val FADE_STEP_MS = 20L
        private const val FADE_TOTAL_MS = FADE_STEPS * FADE_STEP_MS
        private const val FAILURE_BUDGET = 3
    }
}
