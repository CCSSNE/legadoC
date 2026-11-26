package io.legado.app.help.tts

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import io.legado.app.constant.AppLog
import java.io.ByteArrayOutputStream

/**
 * 音色试听播放器：把 WAV/PCM 用 AudioTrack 直放一次，不进朗读队列，播完即释放。
 *
 * 试听是「听一下这个音色」的一次性动作，与朗读链路的 ExoPlayer 队列无关；
 * 放在主代码是因为任何内置引擎的试听都需要它，且这里不含任何专有实现。
 */
object TtsPreviewPlayer {

    private const val MAX_PREVIEW_BYTES = 8 * 1024 * 1024
    private const val DEFAULT_SAMPLE_RATE = 24000

    @Volatile
    private var playing: AudioTrack? = null

    fun play(audio: ByteArray) {
        if (audio.size < 64 || audio.size > MAX_PREVIEW_BYTES) return
        runCatching {
            stop()
            val pcm = if (isWav(audio)) stripWavHeader(audio) else audio
            if (pcm.isEmpty()) return
            val sampleRate = wavSampleRate(audio).takeIf { it > 0 } ?: DEFAULT_SAMPLE_RATE
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(pcm.size)
                .build()
            track.write(pcm, 0, pcm.size)
            track.play()
            playing = track
            Thread {
                runCatching {
                    // 单声道 16bit：每采样 2 字节，播完即释放
                    Thread.sleep((pcm.size * 1000L / (sampleRate * 2)).coerceAtLeast(200))
                    stop()
                }
            }.start()
        }.onFailure {
            AppLog.put("试听播放失败\n${it.localizedMessage}", it)
        }
    }

    fun stop() {
        playing?.let { track ->
            runCatching { if (track.playState == AudioTrack.PLAYSTATE_PLAYING) track.stop() }
            runCatching { track.release() }
        }
        playing = null
    }

    private fun isWav(audio: ByteArray): Boolean {
        return audio.size >= 12 &&
            String(audio, 0, 4, Charsets.US_ASCII) == "RIFF" &&
            String(audio, 8, 4, Charsets.US_ASCII) == "WAVE"
    }

    private fun wavSampleRate(audio: ByteArray): Int {
        var offset = 12
        while (offset + 16 <= audio.size) {
            val id = String(audio, offset, 4, Charsets.US_ASCII)
            val size = leInt(audio, offset + 4)
            if (id == "fmt ") {
                return leInt(audio, offset + 12)
            }
            if (size <= 0) return 0
            offset += 8 + size + (size and 1)
        }
        return 0
    }

    private fun stripWavHeader(audio: ByteArray): ByteArray {
        var offset = 12
        while (offset + 8 <= audio.size) {
            val id = String(audio, offset, 4, Charsets.US_ASCII)
            val size = leInt(audio, offset + 4)
            if (id == "data") {
                val start = offset + 8
                val end = (start + size).coerceAtMost(audio.size)
                val out = ByteArrayOutputStream(end - start)
                out.write(audio, start, end - start)
                return out.toByteArray()
            }
            if (size <= 0) return ByteArray(0)
            offset += 8 + size + (size and 1)
        }
        return ByteArray(0)
    }

    private fun leInt(audio: ByteArray, offset: Int): Int {
        return (audio[offset].toInt() and 0xFF) or
            ((audio[offset + 1].toInt() and 0xFF) shl 8) or
            ((audio[offset + 2].toInt() and 0xFF) shl 16) or
            ((audio[offset + 3].toInt() and 0xFF) shl 24)
    }
}
