package io.legado.app.service

import android.app.PendingIntent
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.help.ai.AiStoryboardConfig
import io.legado.app.help.bdtts.BdEngineAdapter
import io.legado.app.help.bdtts.BdMultiVoice
import io.legado.app.help.bdtts.BdSpeakerRecord
import io.legado.app.help.bdtts.BdSpeakerStore
import io.legado.app.help.bdtts.BdSynthCallback
import io.legado.app.help.config.AppConfig
import io.legado.app.help.tts.AiMultiVoiceConfig
import io.legado.app.help.tts.AiStoryboardBatchAnalyzer
import io.legado.app.help.tts.AiTtsStoryboardHelper
import io.legado.app.help.tts.BookTtsAutomationConfig
import io.legado.app.help.tts.BookTtsCastingCoordinator
import io.legado.app.help.tts.ChapterStoryboard
import io.legado.app.model.ReadAloud
import io.legado.app.model.ReadBook
import io.legado.app.utils.getPrefString
import io.legado.app.utils.servicePendingIntent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
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
        AppLog.putDebug("[百度TTS] 切换发音人：${record.name}（${record.code}）")
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

    private var storyboard: ChapterStoryboard? = null
    private var storyboardChapterIndex = -1
    private var storyboardJob: Job? = null

    private fun synthesizeCurrent() {
        val record = selectedSpeaker()
        if (record == null) {
            AppLog.putDebug("[百度TTS] 未导入语音包或未选择发音人")
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
        if (AiMultiVoiceConfig.enabled) {
            expandWithStoryboard(text, record)
        } else {
            segmentQueue = BdMultiVoice.expand(text, record)
            segmentIndex = 0
            synthesizeNextSegment()
        }
    }

    /**
     * AI 多角色路径：先确保本章分镜就绪（缓存优先，未命中现场调 1号AI），
     * 收编新角色并按需自动选音，然后按段落的分镜段逐段选发音人合成。
     */
    private fun expandWithStoryboard(text: String, record: BdSpeakerRecord) {
        val book = ReadBook.book
        val chapter = textChapter
        if (book == null || chapter == null) {
            AppLog.putDebug("[百度TTS][AI分镜] 缺少书或章节信息，回退旁白/对白池")
            segmentQueue = BdMultiVoice.expand(text, record)
            segmentIndex = 0
            synthesizeNextSegment()
            return
        }
        val workKey = BookTtsAutomationConfig.workKeyOf(book.name, book.author)
        if (storyboardChapterIndex == chapter.index && storyboard != null) {
            startStoryboardSegments(record, workKey)
            return
        }
        segmentQueue = emptyList()
        storyboardJob?.cancel()
        storyboardJob = lifecycleScope.launch(Dispatchers.Main) {
            val chapterTitle = chapter.title ?: ""
            val chapterContent = loadChapterContent()
            val generated = if (chapterContent != null) {
                runCatching {
                    AiTtsStoryboardHelper.getOrGenerate(book, chapter.index, chapterTitle, chapterContent)
                }.onFailure { error ->
                    if (error is CancellationException) return@launch
                    AppLog.put("[百度TTS][AI分镜] 生成失败，本章回退旁白/对白池\n${error.localizedMessage}")
                }.getOrNull()
            } else {
                null
            }
            if (generated != null) {
                AppLog.putDebug(
                    "[百度TTS][AI分镜] 分镜就绪：第${chapter.index + 1}章 ${chapterTitle}，" +
                        "段落 ${generated.paragraphs.size} 段"
                )
                val synced = BookTtsCastingCoordinator.syncCastRoles(workKey, chapter.index, generated)
                storyboard = synced
                storyboardChapterIndex = chapter.index
                val automation = BookTtsAutomationConfig.get(workKey)
                if (automation.autoAssignVoices) {
                    runCatching { BookTtsCastingCoordinator.assignMissingVoices(workKey) }
                        .onFailure { error ->
                            AppLog.put("[百度TTS][AI选音] 失败\n${error.localizedMessage}")
                        }
                }
            }
            if (!isRun || pause || playingIndex != nowSpeak) return@launch
            if (storyboard != null) {
                startStoryboardSegments(record, workKey)
                scheduleStoryboardPreload(book, chapter.index)
            } else {
                segmentQueue = BdMultiVoice.expand(text, record)
                segmentIndex = 0
                synthesizeNextSegment()
            }
        }
    }

    private var storyboardPreloadJob: Job? = null

    /** 播放时后台预生成后续 N 章分镜（缓存命中则秒回），为换章做准备。 */
    private fun scheduleStoryboardPreload(book: io.legado.app.data.entities.Book, chapterIndex: Int) {
        val preloadCount = AiStoryboardConfig.preloadCount
        if (preloadCount <= 0) return
        if (storyboardPreloadJob?.isActive == true) return
        storyboardPreloadJob = lifecycleScope.launch(Dispatchers.IO) {
            runCatching {
                val workKey = BookTtsAutomationConfig.workKeyOf(book.name, book.author)
                for (offset in 1..preloadCount) {
                    val nextIndex = chapterIndex + offset
                    val chapter = appDb.bookChapterDao.getChapter(book.bookUrl, nextIndex)
                        ?: continue
                    runCatching {
                        AiStoryboardBatchAnalyzer.analyzeChapter(book, nextIndex, chapter.title)
                    }
                    if (BookTtsAutomationConfig.get(workKey).autoAssignVoices) {
                        runCatching { BookTtsCastingCoordinator.assignMissingVoices(workKey) }
                    }
                }
            }
        }
    }

    /**
     * 整章原文：固定 pageSplit=false（完整自然段），与批量分析、预生成同源，
     * 保证三章一路径共享同一份分镜缓存；朗读单元与段落的对齐由段内偏移求交处理。
     */
    private fun loadChapterContent(): String? {
        val chapter = textChapter ?: return null
        if (!chapter.isCompleted) return null
        return chapter.getNeedReadAloud(0, false, 0).takeIf { it.isNotBlank() }
    }

    private var consumedParagraphIndex = -1
    private var consumedOffset = 0

    private fun startStoryboardSegments(record: BdSpeakerRecord, workKey: String) {
        val currentStoryboard = storyboard ?: return
        val speakText = contentList.getOrNull(nowSpeak).orEmpty().let {
            if (paragraphStartPos in 1..it.length) it.substring(paragraphStartPos) else it
        }
        val paragraphIndex = locateParagraph(currentStoryboard, speakText)
        if (paragraphIndex == null) {
            AppLog.putDebug("[百度TTS][AI分镜] 未命中分镜段，回退旁白/对白池")
            segmentQueue = BdMultiVoice.expand(speakText, record)
            segmentIndex = 0
            synthesizeNextSegment()
            return
        }
        val paragraphText = currentStoryboard.paragraphs.getOrNull(paragraphIndex).orEmpty()
        if (consumedParagraphIndex != paragraphIndex) {
            consumedParagraphIndex = paragraphIndex
            consumedOffset = 0
        }
        // 当前朗读单元在分镜段落内的起始偏移：优先从上次消费点向后找（pageSplit 半段续读）
        val unitStart = locateUnitOffset(paragraphText, speakText, consumedOffset)
        val unitEnd = (unitStart + speakText.length).coerceAtMost(paragraphText.length)
        consumedOffset = unitEnd
        val queue = arrayListOf<BdMultiVoice.Segment>()
        currentStoryboard.segmentsForParagraph(paragraphIndex)
            .filter { it.text.isNotBlank() }
            .forEach { segment ->
                val overlapStart = maxOf(segment.start, unitStart)
                val overlapEnd = minOf(segment.end, unitEnd)
                if (overlapEnd <= overlapStart) return@forEach
                val overlapText = paragraphText.substring(
                    overlapStart.coerceIn(0, paragraphText.length),
                    overlapEnd.coerceIn(0, paragraphText.length)
                )
                if (overlapText.isBlank()) return@forEach
                queue += BdMultiVoice.Segment(
                    overlapText,
                    BookTtsCastingCoordinator.resolveSpeaker(workKey, segment, record)
                )
            }
        if (queue.isEmpty()) {
            AppLog.putDebug("[百度TTS][AI分镜] 分镜段无重叠文本，回退旁白/对白池")
            segmentQueue = BdMultiVoice.expand(speakText, record)
        } else {
            AppLog.putDebug(
                "[百度TTS][AI分镜] 命中分镜段：第${paragraphIndex + 1}段，${queue.size} 个子段"
            )
            segmentQueue = queue
        }
        segmentIndex = 0
        synthesizeNextSegment()
    }

    /**
     * 定位朗读单元在分镜段落内的字符偏移：
     * 精确匹配（从上次消费点起）→ 精确匹配（从头）→ 后缀匹配（段内续读）→ 0。
     */
    private fun locateUnitOffset(paragraphText: String, speakText: String, fromOffset: Int): Int {
        if (speakText.isEmpty()) return 0
        val exact = paragraphText.indexOf(speakText, fromOffset.coerceIn(0, paragraphText.length))
        if (exact >= 0) return exact
        val exactFromStart = paragraphText.indexOf(speakText)
        if (exactFromStart >= 0) return exactFromStart
        if (paragraphText.endsWith(speakText)) {
            return paragraphText.length - speakText.length
        }
        return 0
    }

    /**
     * 定位朗读段在分镜中的段落下标：分镜段落与 contentList 同源，
     * 归一空白后先精确匹配，再包含匹配（段内偏移续读场景）。
     */
    private fun locateParagraph(
        storyboard: ChapterStoryboard,
        speakText: String
    ): Int? {
        val normalize: (String) -> String = { value ->
            value.filterNot { it.isWhitespace() }
        }
        val target = normalize(speakText)
        if (target.isEmpty()) return null
        storyboard.paragraphs.forEachIndexed { index, paragraph ->
            if (normalize(paragraph) == target) return index
        }
        storyboard.paragraphs.forEachIndexed { index, paragraph ->
            if (normalize(paragraph).contains(target)) return index
        }
        return null
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
        AppLog.putDebug(
            "[百度TTS] 合成子段 ${segmentIndex + 1}/${segmentQueue.size}：${segment.speaker.name}（${segment.text.length}字）"
        )
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
                        AppLog.putDebug("[百度TTS] 合成失败：$message")
                        runOnUiThread { advanceSegment(skipError = true) }
                    }

                    override fun onDone(message: String) {
                        val wav = pcmToWav(pcmBuffer, segment.speaker.sampleRate)
                        runOnUiThread {
                            if (wav != null) {
                                playWav(wav)
                            } else {
                                AppLog.putDebug("[百度TTS] 无音频数据，跳过子段")
                                advanceSegment(skipError = true)
                            }
                        }
                    }

                    override fun onAudioData(length: Int, data: ByteArray) {
                        if (!cancelled) {
                            synchronized(pcmBuffer) { pcmBuffer.add(data.copyOf(length)) }
                        }
                    }
                })
            } catch (e: Exception) {
                AppLog.putDebug("[百度TTS] 合成异常：${e.message}")
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
            AppLog.putDebug("[百度TTS] wav 写入异常：${e.message}")
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
        storyboardJob?.cancel()
        storyboardPreloadJob?.cancel()
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
