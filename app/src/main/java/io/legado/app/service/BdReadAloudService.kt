package io.legado.app.service

import android.app.PendingIntent
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import io.legado.app.constant.AppLog
import io.legado.app.help.bdtts.BdEngineAdapter
import io.legado.app.help.bdtts.BdMultiVoice
import io.legado.app.help.bdtts.BdSpeakerRecord
import io.legado.app.help.bdtts.BdSpeakerStore
import io.legado.app.help.bdtts.BdSynthCallback
import io.legado.app.help.config.AppConfig
import io.legado.app.model.ReadAloud
import io.legado.app.utils.getPrefString
import io.legado.app.utils.servicePendingIntent
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.Future

/**
 * 百度离线 TTS 朗读服务：PCM 合成 → wav → ExoPlayer 播放。
 */
class BdReadAloudService : BaseReadAloudService() {

    companion object {
        private const val SYNTH_THREAD = "bdtts-synth"
    }

    private val synthExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, SYNTH_THREAD)
    }
    private var synthFuture: Future<*>? = null
    private var adapter: BdEngineAdapter? = null
    private var adapterSpeakerId: String? = null

    private val player: ExoPlayer by lazy {
        ExoPlayer.Builder(this).build().apply { addListener(playerListener) }
    }
    private var playingIndex = -1
    private var currentWavFile: File? = null

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) {
                advanceSegment()
            }
        }
    }

    private fun selectedSpeaker(): BdSpeakerRecord? {
        val id = getPrefString(io.legado.app.constant.PreferKey.bdSelectedSpeaker)
        return BdSpeakerStore.load().firstOrNull { it.id == id }
            ?: BdSpeakerStore.load().firstOrNull()
    }

    private fun obtainAdapter(record: BdSpeakerRecord): BdEngineAdapter {
        if (adapter != null && adapterSpeakerId == record.id) {
            return adapter!!
        }
        adapter?.release()
        val newAdapter = BdEngineAdapter(this, record.code, record.param)
        newAdapter.init()
        adapter = newAdapter
        adapterSpeakerId = record.id
        return newAdapter
    }

    override fun play() {
        super.play()
        synthesizeCurrent()
    }

    private var segmentQueue: List<BdMultiVoice.Segment> = emptyList()
    private var segmentIndex = 0

    private fun synthesizeCurrent() {
        val record = selectedSpeaker()
        if (record == null) {
            AppLog.putDebug("[朗读][百度] 未导入语音包或未选择发音人")
            pauseReadAloud()
            return
        }
        if (contentList.isEmpty() || nowSpeak >= contentList.size) {
            nextChapter()
            return
        }
        val text = contentList[nowSpeak].let {
            if (paragraphStartPos in 1..it.length) it.substring(paragraphStartPos) else it
        }
        if (text.isBlank()) {
            if (!moveToNextParagraph()) {
                nextChapter()
            } else {
                synthesizeCurrent()
            }
            return
        }
        playingIndex = nowSpeak
        segmentQueue = BdMultiVoice.expand(text, record)
        segmentIndex = 0
        synthesizeNextSegment()
    }

    private fun synthesizeNextSegment() {
        if (segmentIndex >= segmentQueue.size) {
            if (!moveToNextParagraph()) {
                nextChapter()
            } else {
                synthesizeCurrent()
            }
            return
        }
        val segment = segmentQueue[segmentIndex]
        val speed = (AppConfig.ttsSpeechRate + 5) / 10f
        val engine = obtainAdapter(segment.speaker)
        val pcmBuffer = mutableListOf<ByteArray>()
        synthFuture?.cancel(true)
        synthFuture = synthExecutor.submit {
            try {
                upTtsProgress(0)
                engine.synthesize(speed, 1.0f, segment.text, object : BdSynthCallback {
                    override fun onStart() = Unit
                    override fun onError(message: String) {
                        AppLog.putDebug("[朗读][百度] 合成失败：$message")
                        runOnUiThread { advanceSegment(skipError = true) }
                    }

                    override fun onDone(message: String) {
                        val wav = pcmToWav(pcmBuffer, segment.speaker.sampleRate)
                        runOnUiThread {
                            if (wav != null) playWav(wav) else advanceSegment(skipError = true)
                        }
                    }

                    override fun onAudioData(length: Int, data: ByteArray) {
                        if (!cancelled) {
                            synchronized(pcmBuffer) { pcmBuffer.add(data.copyOf(length)) }
                        }
                    }
                })
            } catch (e: Exception) {
                AppLog.putDebug("[朗读][百度] 合成异常：${e.message}")
                runOnUiThread { advanceSegment(skipError = true) }
            }
        }
    }

    /** 当前子段播完：推进子段队列；队列耗尽后推进段落。 */
    private fun advanceSegment(skipError: Boolean = false) {
        if (!isRun || pause) return
        if (!skipError && playingIndex != nowSpeak) return
        segmentIndex++
        synthesizeNextSegment()
    }

    private var cancelled = false

    private fun runOnUiThread(action: () -> Unit) {
        android.os.Handler(android.os.Looper.getMainLooper()).post(action)
    }

    private fun moveToNextParagraph(): Boolean {
        if (nowSpeak >= contentList.size) return false
        readAloudNumber += contentList[nowSpeak].length + 1 - paragraphStartPos
        paragraphStartPos = 0
        nowSpeak++
        return nowSpeak < contentList.size
    }

    private fun playWav(file: File) {
        if (!isRun || pause || playingIndex != nowSpeak) {
            file.delete()
            return
        }
        currentWavFile?.let { if (it != file) it.delete() }
        currentWavFile = file
        player.setMediaItem(MediaItem.fromUri(android.net.Uri.fromFile(file)))
        player.prepare()
        player.play()
        upTtsProgress(0)
    }

    private fun pcmToWav(pcmBuffer: List<ByteArray>, sampleRate: Int): File? {
        val total = pcmBuffer.sumOf { it.size }
        if (total <= 0) return null
        val dir = File(cacheDir, "bdtts").apply { mkdirs() }
        val file = File(dir, "seg_${System.currentTimeMillis()}.wav")
        try {
            FileOutputStream(file).use { out ->
                val header = wavHeader(total, sampleRate, 1, 16)
                out.write(header)
                for (chunk in pcmBuffer) {
                    out.write(chunk)
                }
            }
            return file
        } catch (e: Exception) {
            file.delete()
            return null
        }
    }

    private fun wavHeader(pcmLength: Int, sampleRate: Int, channels: Int, bitsPerSample: Int): ByteArray {
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        val header = ByteArray(44)
        fun putString(offset: Int, value: String) {
            System.arraycopy(value.toByteArray(Charsets.US_ASCII), 0, header, offset, value.length)
        }
        fun putInt(offset: Int, value: Int) {
            header[offset] = (value and 0xff).toByte()
            header[offset + 1] = (value shr 8 and 0xff).toByte()
            header[offset + 2] = (value shr 16 and 0xff).toByte()
            header[offset + 3] = (value shr 24 and 0xff).toByte()
        }
        putString(0, "RIFF")
        putInt(4, 36 + pcmLength)
        putString(8, "WAVE")
        putString(12, "fmt ")
        putInt(16, 16)
        header[20] = 1
        header[22] = channels.toByte()
        putInt(24, sampleRate)
        putInt(28, byteRate)
        header[32] = blockAlign.toByte()
        header[34] = bitsPerSample.toByte()
        putString(36, "data")
        putInt(40, pcmLength)
        return header
    }

    override fun playStop() {
        cancelled = true
        synthFuture?.cancel(true)
        runOnUiThread {
            player.stop()
            player.clearMediaItems()
        }
    }

    override fun upSpeechRate(reset: Boolean) {
        if (isRun && !pause) {
            synthesizeCurrent()
        }
    }

    override fun aloudServicePendingIntent(actionStr: String): PendingIntent? {
        return servicePendingIntent<BdReadAloudService>(actionStr)
    }

    override fun pauseReadAloud(abandonFocus: Boolean) {
        super.pauseReadAloud(abandonFocus)
        player.pause()
    }

    override fun resumeReadAloud() {
        super.resumeReadAloud()
        if (playingIndex == nowSpeak && currentWavFile?.isFile == true) {
            player.play()
        } else {
            synthesizeCurrent()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        playStop()
        adapter?.release()
        adapter = null
        player.release()
        currentWavFile?.delete()
    }
}
