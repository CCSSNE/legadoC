package io.legado.app.service

import android.app.PendingIntent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import io.legado.app.R
import io.legado.app.constant.AppConst
import io.legado.app.constant.AppLog
import io.legado.app.constant.AppPattern
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.MediaHelp
import io.legado.app.help.config.AppConfig
import io.legado.app.help.tts.TtsCacheStore
import io.legado.app.lib.dialogs.SelectItem
import io.legado.app.model.ReadAloud
import io.legado.app.model.ReadBook
import io.legado.app.utils.GSON
import io.legado.app.utils.LogUtils
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.servicePendingIntent
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class TTSReadAloudService : BaseReadAloudService() {

    private var textToSpeech: TextToSpeech? = null
    private var ttsInitFinish = false
    private val ttsUtteranceListener = TTSUtteranceListener()
    private var speakGeneration = 0
    private var ttsInitGeneration = 0
    private var retryParagraphKey: String? = null
    private var retryingTtsInit = false
    private var ttsVoiceName: String? = null
    private var queuedUntilIndex = -1

    @Volatile
    private var activeUtteranceId: String? = null

    // ---- 预测换页（页间分段 OFF 时的被动机制）----
    // TTS 拿不到整段音频时长，按“文字量 + 实测语速”估算读过页界的时刻；
    // onRangeStart 可用时用真实进度校准速率。契约：预测只影响位置事件的
    // 发布时机（upTtsProgress），显示是否翻页仍由 UI 侧跟随规则判定。
    private val predictHandler = Handler(Looper.getMainLooper())
    private var predictRunnable: Runnable? = null
    private var utteranceStartRealtime = 0L

    @Volatile
    private var lastRangeOffset = 0

    // 实测朗读速率（字/毫秒）：由上一句 onDone 的真实总时长滚动校准，
    // 初值按常见中文 TTS 语速约 480 字/分钟，只影响第一句的预估
    @Volatile
    private var measuredCharRate = 480.0 / 60_000.0

    // ---- TTS-Wav 模式：synthesizeToFile 合成本地 wav 后由应用自行播放 ----
    // 音频时长精确可知，句内进度按真实音频位置轮询发布（与 HTTP 引擎同款），
    // 翻页仍由 UI 侧跟随规则判定；播放当前句时后台预合成下一句保证无缝衔接。
    private val wavPlayer: ExoPlayer by lazy {
        ExoPlayer.Builder(this).build().apply { addListener(wavPlayerListener) }
    }
    private val wavDir: File by lazy {
        File(cacheDir, "ttsWav").apply { mkdirs() }
    }
    private var wavPosJob: Job? = null
    private var wavPendingSynthesisIndex = -1
    private var wavPendingSynthesisFile: File? = null
    private var wavReadyIndex = -1
    private var wavReadyFile: File? = null
    private var lastWavFile: File? = null

    /** 暂停恢复标志：区分“从暂停继续播放当前 wav”和“从头开始新句子”。 */
    @Volatile
    private var wavPausedPlayback = false

    // ---- 实时缓存：任务列表 + 在途合成 ----
    // 每当新的朗读单元成为当前单元：先解决当前段三态（命中→直接播；在途→等
    // 合成完成回调；缺失→立即派发，排在引擎队列最前），随后重建任务列表
    // （当前段之后 ttsCachePrefetchCount 个可读单元：命中跳过、在途跳过、
    // 缺失派发）。引擎串行合成，引擎忙碌时当前段要等手头的合成做完——这是
    // 开启实时缓存的固定代价。预取失败的段无需显式重试：变为当前段时自然
    // 落入“缺失”状态重新派发。

    private class RealtimeSynthesis(
        val index: Int,
        val tempFile: File,
        val key: TtsCacheStore.UnitKey,
        val watchdog: Runnable,
    )

    private val realtimePending = linkedMapOf<Int, RealtimeSynthesis>()

    private fun clearRealtimePending() {
        synchronized(realtimePending) {
            realtimePending.values.forEach { record ->
                predictHandler.removeCallbacks(record.watchdog)
                runCatching { record.tempFile.delete() }
            }
            realtimePending.clear()
        }
    }

    private val TAG = "TTSReadAloudService"

    override fun onCreate() {
        super.onCreate()
        kotlin.runCatching {
            initTts()
        }.onFailure {
            AppLog.put("${getString(R.string.tts_init_failed)}\n$it", it, true)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        clearTTS(forgetVoice = true)
        runCatching { wavPlayer.release() }
    }

    @Synchronized
    private fun initTts() {
        ttsInitFinish = false
        val initGeneration = ++ttsInitGeneration
        val engine = GSON.fromJsonObject<SelectItem<String>>(ReadAloud.ttsEngine).getOrNull()?.value
        LogUtils.d(TAG, "initTts engine:$engine")
        textToSpeech = if (engine.isNullOrBlank()) {
            TextToSpeech(this) { status -> onTtsInit(initGeneration, status) }
        } else {
            TextToSpeech(this, { status -> onTtsInit(initGeneration, status) }, engine)
        }
        upSpeechRate()
    }

    /**
     * 当前朗读单元实际传给 TTS 的文本长度（段内续读时扣除起始偏移）。
     */
    private fun currentUtteranceTextLength(): Int {
        val content = contentList.getOrNull(nowSpeak) ?: return 0
        return (content.length - paragraphStartPos).coerceAtLeast(0)
    }

    private fun cancelPageBreakPrediction() {
        predictRunnable?.let { predictHandler.removeCallbacks(it) }
        predictRunnable = null
    }

    /**
     * 预测换页调度：当前朗读单元跨越页边界时，按“页界前字符量 / 实测语速”
     * 估算读过页界的时刻，到点发布一次前进位置事件。
     * - 页间分段 ON 时单元已在页界裂开，本单元不跨页，天然不调度。
     * - onRangeStart 已发布过界位置的，预测回调自动跳过（不重复发布）。
     * - speakGeneration 防乱序：暂停/停止/出错重试后调度自动失效。
     */
    private fun schedulePageBreakPrediction(utteranceTextLength: Int) {
        cancelPageBreakPrediction()
        if (pageSplit) return
        val chapter = textChapter ?: return
        if (utteranceTextLength <= 0) return
        if (pageIndex + 1 >= chapter.pageSize) return
        val nextPageStart = chapter.getReadLength(pageIndex + 1)
        val utteranceStart = readAloudNumber
        val utteranceEnd = utteranceStart + utteranceTextLength
        if (nextPageStart <= utteranceStart || nextPageStart >= utteranceEnd) return
        val breakOffset = nextPageStart - utteranceStart
        val delayMs = (breakOffset / measuredCharRate).toLong().coerceAtLeast(0L)
        val generation = speakGeneration
        AppLog.putDebug(
            "[朗读] 预测换页调度 单元:$nowSpeak 长:$utteranceTextLength " +
                "页界偏移:$breakOffset 延时:${delayMs}ms 速率:${(measuredCharRate * 60_000).toInt()}/min"
        )
        val runnable = Runnable {
            predictRunnable = null
            if (generation != speakGeneration || pause) return@Runnable
            if (lastRangeOffset + utteranceStart >= nextPageStart) return@Runnable
            AppLog.putDebug("[朗读] 预测换页触发 pos:$nextPageStart")
            upTtsProgress(nextPageStart)
        }
        predictRunnable = runnable
        predictHandler.postDelayed(runnable, delayMs)
    }

    // ---- TTS-Wav 模式（synthesizeToFile 合成本地 wav + 应用自播）----

    private val wavPlayerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) {
                lifecycleScope.launch(Main) {
                    if (AppConfig.ttsWavMode && isRun && !pause) {
                        advanceWavParagraph()
                    }
                }
            }
        }

        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            AppLog.put("TTS-Wav 播放错误：${error.localizedMessage}", error)
            lifecycleScope.launch(Main) {
                if (AppConfig.ttsWavMode && isRun && !pause) {
                    lastWavFile?.delete()
                    lastWavFile = null
                    // 播放失败跳过本句，继续下一句（日志已暴露）
                    advanceWavParagraph()
                }
            }
        }
    }

    /** 停止/废弃播放现场：清播放器、轮询、在途与就绪的合成产物。 */
    private fun resetWavPlayback() {
        wavPosJob?.cancel()
        wavPosJob = null
        if (wavPlayer.isCommandAvailable(androidx.media3.common.Player.COMMAND_STOP)) {
            runCatching {
                wavPlayer.stop()
                wavPlayer.clearMediaItems()
            }
        }
        wavPendingSynthesisIndex = -1
        wavPendingSynthesisFile?.delete()
        wavPendingSynthesisFile = null
        wavReadyIndex = -1
        wavReadyFile?.delete()
        wavReadyFile = null
        // 实时缓存模式下 lastWavFile 是缓存文件，只能由缓存生命周期管理
        if (!AppConfig.ttsRealtimeCache) {
            lastWavFile?.delete()
        }
        lastWavFile = null
        wavPausedPlayback = false
        clearRealtimePending()
    }

    /** Wav 模式取当前朗读单元：跳过不可读单元（与 speak 模式的 while 循环同语义）。 */
    private fun speakWavCurrentParagraph() {
        if (AppConfig.ttsRealtimeCache) {
            speakRealtimeCurrentParagraph()
            return
        }
        while (nowSpeak < contentList.size) {
            val content = contentList[nowSpeak]
            val text = if (paragraphStartPos > 0) {
                content.substring(paragraphStartPos.coerceAtMost(content.length))
            } else {
                content
            }
            if (text.isNotEmpty() && !text.matches(AppPattern.notReadAloudRegex)) {
                synthesizeAndPlayCurrent(text)
                return
            }
            moveToNextParagraph()
        }
        nextChapter()
    }

    // ---- 实时缓存调度 ----

    /** 实时缓存取当前朗读单元：跳过不可读单元（与 speak 模式的 while 循环同语义）。 */
    private fun speakRealtimeCurrentParagraph() {
        while (nowSpeak < contentList.size) {
            val content = contentList[nowSpeak]
            val text = if (paragraphStartPos > 0) {
                content.substring(paragraphStartPos.coerceAtMost(content.length))
            } else {
                content
            }
            if (text.isNotEmpty() && !text.matches(AppPattern.notReadAloudRegex)) {
                speakRealtimeUnit(nowSpeak, text)
                return
            }
            moveToNextParagraph()
        }
        nextChapter()
    }

    /**
     * 当前单元三态解决：
     * 1. 命中缓存 → 直接播（任务列表照建，全命中则不派发任何合成）；
     * 2. 引擎正在合成 → 任务列表照建，等合成完成回调驱动播放；
     * 3. 未合成也不在缓存 → 立即派发（排在引擎队列最前），等回调。
     * 当前段先解决、任务列表随后建立：保证当前段在引擎队列中优先产出，
     * 已知代价是引擎忙碌时当前段要等手头的合成做完（引擎串行，无法插队）。
     */
    private fun speakRealtimeUnit(index: Int, text: String) {
        val book = ReadBook.book ?: return
        val chapter = textChapter?.chapter ?: return
        val key = TtsCacheStore.buildUnitKey(book, chapter, text, ttsVoiceName)
        if (TtsCacheStore.has(book, key)) {
            // 状态1：命中缓存直接播
            AppLog.putDebug("[朗读] 实时缓存命中 单元:$index")
            playWavFile(index, TtsCacheStore.unitFile(book, key))
        } else {
            val pending = synchronized(realtimePending) { realtimePending.containsKey(index) }
            if (!pending) {
                // 状态3：立即派发；状态2（在途）不做任何事，等回调
                synchronized(realtimePending) {
                    if (!realtimePending.containsKey(index)) {
                        dispatchRealtimeSynthesis(book, chapter, index, text, key)
                    }
                }
            }
        }
        // 任务列表（当前段之后 N 个可读单元）：命中/在途跳过，缺失派发
        buildRealtimeTaskList(book, chapter, index)
    }

    /**
     * 任务列表：从 fromIndex+1 起向后取 ttsCachePrefetchCount 个可读单元，
     * 命中缓存或在途的跳过，缺失的派发给引擎。预取失败的段无需显式重试：
     * 变为当前段时自然落入“缺失”状态重新派发。
     */
    private fun buildRealtimeTaskList(book: Book, chapter: BookChapter, fromIndex: Int) {
        val maxCount = AppConfig.ttsCachePrefetchCount
        var scheduled = 0
        var index = fromIndex + 1
        while (index < contentList.size && scheduled < maxCount) {
            val text = contentList[index]
            if (text.isEmpty() || text.matches(AppPattern.notReadAloudRegex)) {
                index++
                continue
            }
            scheduled++
            val pending = synchronized(realtimePending) { realtimePending.containsKey(index) }
            if (!pending) {
                val key = TtsCacheStore.buildUnitKey(book, chapter, text, ttsVoiceName)
                if (!TtsCacheStore.has(book, key)) {
                    synchronized(realtimePending) {
                        if (!realtimePending.containsKey(index)) {
                            dispatchRealtimeSynthesis(book, chapter, index, text, key)
                        }
                    }
                }
            }
            index++
        }
        AppLog.putDebug(
            "[朗读] 实时缓存任务列表 当前:$fromIndex 窗口:$maxCount " +
                "在途:${synchronized(realtimePending) { realtimePending.size }}"
        )
    }

    /**
     * 派发一个单元给引擎合成（临时文件），完成回调里提交进 TTS 缓存。
     * 每次派发挂一个超时看门狗：系统 TTS 引擎挂死时 onDone/onError 都不会
     * 到来，没有看门狗则该段永久占用在途表——预取段堵死引擎队列，成为
     * 当前段时静默等待 forever。超时按失败清理；当前段走重试链
     * （clearTTS+initTts 正好是解卡引擎的手段）。
     */
    private fun dispatchRealtimeSynthesis(
        book: Book,
        chapter: BookChapter,
        index: Int,
        text: String,
        key: TtsCacheStore.UnitKey,
    ) {
        val tts = textToSpeech ?: return
        val tempFile = File(wavDir, "rt_${speakGeneration}_$index.wav")
        val watchdog = Runnable { onRealtimeSynthesisTimeout(index) }
        realtimePending[index] = RealtimeSynthesis(index, tempFile, key, watchdog)
        val result = tts.runCatching {
            synthesizeToFile(text, Bundle(), tempFile, utteranceId(index))
        }.getOrElse {
            AppLog.put("[朗读] 实时缓存合成请求出错 单元:$index\n${it.localizedMessage}", it)
            TextToSpeech.ERROR
        }
        if (result == TextToSpeech.ERROR) {
            predictHandler.removeCallbacks(watchdog)
            realtimePending.remove(index)
            tempFile.delete()
            if (index == nowSpeak && isRun && !pause) {
                handleSpeakError("tts realtime synthesize error", retryWithReinit = true)
            }
        } else {
            predictHandler.postDelayed(
                watchdog,
                AppConfig.ttsCacheSegmentTimeoutSeconds * 1000L,
            )
        }
    }

    /** 看门狗触发：引擎在超时时限内没有回报任何回调，按合成失败清理。 */
    private fun onRealtimeSynthesisTimeout(index: Int) {
        val record = synchronized(realtimePending) { realtimePending.remove(index) } ?: return
        record.tempFile.delete()
        AppLog.put(
            "[朗读] 实时缓存合成超时(${AppConfig.ttsCacheSegmentTimeoutSeconds}s) 单元:$index"
        )
        if (index == nowSpeak && isRun && !pause) {
            handleSpeakError("tts realtime synthesize timeout", retryWithReinit = true)
        }
        // 预取段超时通常意味着引擎已挂死：当前段的看门狗随后也会触发并重init
    }

    /** 合成完成：提交进缓存；当前单元直接播，预取单元落库等翻句时命中。 */
    private fun onRealtimeSynthesisDone(index: Int) {
        val record = synchronized(realtimePending) { realtimePending.remove(index) } ?: return
        predictHandler.removeCallbacks(record.watchdog)
        lifecycleScope.launch {
            val book = ReadBook.book
            val cacheFile = if (book == null) {
                record.tempFile.delete()
                null
            } else {
                withContext(Dispatchers.IO) {
                    runCatching {
                        TtsCacheStore.commit(record.tempFile, TtsCacheStore.unitFile(book, record.key))
                    }.onFailure {
                        record.tempFile.delete()
                        AppLog.put("[朗读] 实时缓存提交失败 单元:$index\n${it.localizedMessage}", it)
                    }.isSuccess
                }.takeIf { it }?.let { TtsCacheStore.unitFile(book, record.key) }
            }
            if (index == nowSpeak && isRun && !pause) {
                playWavFile(index, cacheFile)
            }
        }
    }

    /** 合成失败：当前单元走既有错误链（重试一次/跳句），预取单元静默丢弃、后续重派发。 */
    private fun onRealtimeSynthesisError(index: Int?) {
        if (index == null) return
        val record = synchronized(realtimePending) { realtimePending.remove(index) } ?: return
        predictHandler.removeCallbacks(record.watchdog)
        record.tempFile.delete()
        AppLog.putDebug("[朗读] 实时缓存合成失败 单元:$index")
        if (index == nowSpeak && isRun && !pause) {
            handleSpeakError("tts realtime synthesize error", retryWithReinit = true)
        }
    }

    /** 合成当前朗读单元为本地 wav，完成后回调驱动播放。 */
    private fun synthesizeAndPlayCurrent(text: String) {
        val tts = textToSpeech ?: throw NoStackTraceException("tts is null")
        val index = nowSpeak
        if (wavPendingSynthesisIndex == index) {
            // 该句预合成已在途，直接等其完成回调驱动播放
            return
        }
        val file = File(wavDir, "utt_${speakGeneration}_$index.wav")
        wavPendingSynthesisIndex = index
        wavPendingSynthesisFile = file
        AppLog.putDebug("[朗读] WAV合成请求 单元:$index 长:${text.length}")
        val result = tts.runCatching {
            synthesizeToFile(text, Bundle(), file, utteranceId(index))
        }.getOrElse {
            AppLog.put("tts wav 合成请求出错\n${it.localizedMessage}", it, true)
            TextToSpeech.ERROR
        }
        if (result == TextToSpeech.ERROR) {
            wavPendingSynthesisIndex = -1
            wavPendingSynthesisFile = null
            file.delete()
            handleSpeakError("tts wav synthesize error", retryWithReinit = true)
        }
    }

    /** 合成完成回调：当前句直接播放，下一句暂存为预合成产物。 */
    private fun onWavSynthesisDone(utteranceIdStr: String) {
        val index = utteranceIndex(utteranceIdStr) ?: return
        if (AppConfig.ttsRealtimeCache) {
            onRealtimeSynthesisDone(index)
            return
        }
        if (index != wavPendingSynthesisIndex) return
        val file = wavPendingSynthesisFile
        wavPendingSynthesisIndex = -1
        wavPendingSynthesisFile = null
        lifecycleScope.launch(Main) {
            when {
                index == nowSpeak && isRun && !pause -> playWavFile(index, file)
                index == nowSpeak + 1 && file != null -> {
                    wavReadyIndex = index
                    wavReadyFile = file
                    AppLog.putDebug("[朗读] WAV预合成完成 单元:$index")
                }
                else -> file?.delete()
            }
        }
    }

    /** 合成失败：清理在途产物并走既有错误链（重试一次，仍失败跳句）。 */
    private fun onWavSynthesisError(index: Int?) {
        if (AppConfig.ttsRealtimeCache) {
            onRealtimeSynthesisError(index)
            return
        }
        if (wavPendingSynthesisIndex < 0) return
        wavPendingSynthesisFile?.delete()
        wavPendingSynthesisIndex = -1
        wavPendingSynthesisFile = null
        lifecycleScope.launch(Main) {
            if (isRun && !pause) {
                handleSpeakError("tts wav synthesize error", retryWithReinit = true)
            }
        }
    }

    /** 播放当前句的 wav 文件，并预合成下一句保证衔接。 */
    private fun playWavFile(index: Int, file: File?) {
        // 实时缓存模式下传入的是已提交的缓存文件，任何路径都不得误删
        if (index != nowSpeak) {
            if (!AppConfig.ttsRealtimeCache) file?.delete()
            return
        }
        if (file == null || !file.exists() || file.length() <= 0L) {
            file?.delete()
            handleSpeakError("tts wav file invalid", retryWithReinit = false)
            return
        }
        if (!AppConfig.ttsRealtimeCache) {
            lastWavFile?.delete()
        }
        lastWavFile = file
        wavPausedPlayback = false
        wavPlayer.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
        wavPlayer.prepare()
        wavPlayer.play()
        upWavPlayPos()
        scheduleWavPreSynthesis()
    }

    /** 当前句播完推进：优先使用预合成的下一句，未就绪则现场合成。 */
    private fun advanceWavParagraph() {
        wavPosJob?.cancel()
        wavPosJob = null
        if (!moveToNextParagraph()) {
            nextChapter()
            return
        }
        if (wavReadyIndex == nowSpeak && wavReadyFile != null) {
            val ready = wavReadyFile
            wavReadyIndex = -1
            wavReadyFile = null
            playWavFile(nowSpeak, ready)
        } else {
            speakWavCurrentParagraph()
        }
    }

    /**
     * 句内进度发布：wav 时长由播放器精确提供，按“位置/时长 × 句字符数”换算
     * 字符位置，扫过页界即发布一次前进位置事件——真实音频信号，不是估算。
     * 页间分段 ON 时单元已在页界裂开，句内无页界，无需轮询。
     */
    private fun upWavPlayPos() {
        wavPosJob?.cancel()
        val chapter = textChapter ?: return
        val utteranceLen = currentUtteranceTextLength()
        if (utteranceLen <= 0) return
        wavPosJob = lifecycleScope.launch {
            upTtsProgress(readAloudNumber + 1)
            if (pageSplit) return@launch
            while (isActive && isRun && !pause) {
                val duration = wavPlayer.duration
                if (duration > 0) {
                    val pos = (utteranceLen.toLong() * wavPlayer.currentPosition / duration).toInt()
                    if (pageIndex + 1 < chapter.pageSize
                        && readAloudNumber + pos > chapter.getReadLength(pageIndex + 1)
                    ) {
                        // 扫过页界：只推进引擎私有页光标并发布位置，
                        // 显示翻页由 UI 侧跟随规则处理
                        pageIndex++
                        AppLog.putDebug("[朗读] WAV过界发布 pos:${readAloudNumber + pos}")
                        upTtsProgress(readAloudNumber + pos)
                    }
                    if (wavPlayer.currentPosition >= duration) break
                }
                delay(80)
            }
        }
    }

    /** 预合成下一句（一个槽位），播放中完成，翻句时零等待衔接。 */
    private fun scheduleWavPreSynthesis() {
        // 实时缓存模式：预取由任务列表统一调度，单槽位不介入
        if (AppConfig.ttsRealtimeCache) return
        if (wavReadyIndex == nowSpeak + 1 && wavReadyFile != null) return
        val next = nowSpeak + 1
        val text = contentList.getOrNull(next)?.takeIf {
            it.isNotEmpty() && !it.matches(AppPattern.notReadAloudRegex)
        } ?: return
        val tts = textToSpeech ?: return
        val file = File(wavDir, "utt_${speakGeneration}_$next.wav")
        wavPendingSynthesisIndex = next
        wavPendingSynthesisFile = file
        val result = tts.runCatching {
            synthesizeToFile(text, Bundle(), file, utteranceId(next))
        }.getOrElse {
            wavPendingSynthesisIndex = -1
            wavPendingSynthesisFile = null
            file.delete()
            AppLog.put("tts wav 预合成请求出错\n${it.localizedMessage}", it)
            TextToSpeech.ERROR
        }
        if (result == TextToSpeech.ERROR) {
            // 预合成失败不影响当前句播放，翻句时现场合成兜底
            wavPendingSynthesisIndex = -1
            wavPendingSynthesisFile = null
        }
    }

    @Synchronized
    private fun clearTTS(forgetVoice: Boolean = false) {
        activeUtteranceId = null
        queuedUntilIndex = -1
        speakGeneration++
        cancelPageBreakPrediction()
        resetWavPlayback()
        ttsInitGeneration++
        if (forgetVoice) {
            ttsVoiceName = null
        }
        textToSpeech?.runCatching {
            stop()
            shutdown()
        }
        textToSpeech = null
        ttsInitFinish = false
    }

    private fun onTtsInit(initGeneration: Int, status: Int) {
        if (initGeneration != ttsInitGeneration) {
            return
        }
        if (status == TextToSpeech.SUCCESS) {
            textToSpeech?.let {
                restoreOrRememberVoice(it)
                it.setOnUtteranceProgressListener(ttsUtteranceListener)
                ttsInitFinish = true
                play()
            }
        } else {
            retryParagraphKey = null
            retryingTtsInit = false
            activeUtteranceId = null
            toastOnUi(R.string.tts_init_failed)
            pauseReadAloud(false)
        }
    }

    private fun restoreOrRememberVoice(tts: TextToSpeech) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            return
        }
        val voiceName = ttsVoiceName
        if (!voiceName.isNullOrBlank()) {
            val voice = tts.voices?.firstOrNull { it.name == voiceName }
            if (voice != null && tts.voice?.name != voiceName) {
                if (tts.setVoice(voice) != TextToSpeech.SUCCESS) {
                    AppLog.putDebug("restore tts voice failed:$voiceName")
                }
            }
            return
        }
        tts.voice?.name?.takeIf { it.isNotBlank() }?.let {
            ttsVoiceName = it
        }
    }

    @Synchronized
    override fun play() {
        if (!ttsInitFinish) return
        if (!requestFocus()) return
        if (contentList.isEmpty()) {
            AppLog.putDebug("Read aloud content list is empty")
            nextChapter()
            return
        }
        super.play()
        MediaHelp.playSilentSound(this@TTSReadAloudService)
        speakGeneration++
        if (retryingTtsInit) {
            retryingTtsInit = false
        } else {
            retryParagraphKey = null
        }
        LogUtils.d(TAG, "contentList size:${contentList.size}")
        LogUtils.d(TAG, "pageSize:${textChapter?.pageSize}")
        speakCurrentParagraph()
    }

    override fun playStop() {
        activeUtteranceId = null
        queuedUntilIndex = -1
        speakGeneration++
        cancelPageBreakPrediction()
        resetWavPlayback()
        retryParagraphKey = null
        retryingTtsInit = false
        textToSpeech?.runCatching {
            stop()
        }
    }

    @Synchronized
    private fun speakCurrentParagraph() {
        if (pause) return
        // 发起新朗读单元前作废旧页界预测，新单元 onStart 时按最新光标重新调度
        cancelPageBreakPrediction()
        if (AppConfig.ttsWavMode) {
            // 暂停恢复：播放器仍持有当前句现场，从暂停位置继续
            if (wavPausedPlayback
                && wavPlayer.currentMediaItem != null
                && wavPlayer.playbackState == Player.STATE_READY
                && !wavPlayer.isPlaying
            ) {
                wavPausedPlayback = false
                wavPlayer.play()
                upWavPlayPos()
                return
            }
            wavPausedPlayback = false
            speakWavCurrentParagraph()
            return
        }
        val tts = textToSpeech ?: throw NoStackTraceException("tts is null")
        while (nowSpeak < contentList.size) {
            var text = contentList[nowSpeak]
            if (paragraphStartPos > 0) {
                text = text.substring(paragraphStartPos.coerceAtMost(text.length))
            }
            if (!text.matches(AppPattern.notReadAloudRegex)) {
                val utteranceId = utteranceId(nowSpeak)
                activeUtteranceId = utteranceId
                queuedUntilIndex = nowSpeak
                val result = tts.runCatching {
                    speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
                }.getOrElse {
                    AppLog.put("tts error\n${it.localizedMessage}", it, true)
                    TextToSpeech.ERROR
                }
                if (result == TextToSpeech.ERROR) {
                    queuedUntilIndex = -1
                    handleSpeakError("tts speak error", retryWithReinit = true)
                } else {
                    queueUpcomingUtterances(tts)
                }
                return
            }
            moveToNextParagraph()
        }
        nextChapter()
    }

    @Synchronized
    private fun moveToNextParagraph(): Boolean {
        if (nowSpeak >= contentList.size) return false
        readAloudNumber += contentList[nowSpeak].length + 1 - paragraphStartPos
        paragraphStartPos = 0
        nowSpeak++
        return nowSpeak < contentList.size
    }

    private fun isActiveUtterance(utteranceId: String?): Boolean {
        val index = utteranceIndex(utteranceId) ?: return false
        return index in 0..queuedUntilIndex && index in contentList.indices
    }

    private fun utteranceId(index: Int): String {
        return "${AppConst.APP_TAG}${speakGeneration}_$index"
    }

    private fun utteranceIndex(utteranceId: String?): Int? {
        val prefix = "${AppConst.APP_TAG}${speakGeneration}_"
        return utteranceId
            ?.takeIf { it.startsWith(prefix) }
            ?.substring(prefix.length)
            ?.toIntOrNull()
    }

    @Synchronized
    private fun syncToUtteranceIndex(index: Int) {
        while (nowSpeak < index && nowSpeak in contentList.indices) {
            moveToNextParagraph()
        }
    }

    @Synchronized
    private fun queueUpcomingUtterances(tts: TextToSpeech) {
        if (pause || queuedUntilIndex < nowSpeak) return
        var index = queuedUntilIndex + 1
        var preloadLength = 0
        while (index < contentList.size && preloadLength < minReadAloudPreloadLength()) {
            val text = contentList[index]
            if (text.matches(AppPattern.notReadAloudRegex)) {
                return
            }
            val result = tts.runCatching {
                speak(text, TextToSpeech.QUEUE_ADD, null, utteranceId(index))
            }.getOrElse {
                AppLog.put("tts preload error\n${it.localizedMessage}", it)
                TextToSpeech.ERROR
            }
            if (result == TextToSpeech.ERROR) {
                return
            }
            queuedUntilIndex = index
            preloadLength += text.length
            index++
        }
    }

    @Synchronized
    private fun handleSpeakError(message: String, retryWithReinit: Boolean) {
        val paragraphKey = "$nowSpeak:$readAloudNumber:$paragraphStartPos"
        if (retryParagraphKey != paragraphKey) {
            AppLog.putDebug("$message, retry current paragraph")
            retryParagraphKey = paragraphKey
            activeUtteranceId = null
            queuedUntilIndex = -1
            speakGeneration++
            if (retryWithReinit) {
                retryingTtsInit = true
                clearTTS()
                initTts()
            } else {
                speakCurrentParagraph()
            }
            return
        }
        retryParagraphKey = null
        activeUtteranceId = null
        queuedUntilIndex = -1
        if (!moveToNextParagraph()) {
            nextChapter()
            return
        }
        speakCurrentParagraph()
    }

    override fun upSpeechRate(reset: Boolean) {
        if (AppConfig.ttsFlowSys) {
            if (reset) {
                clearTTS(forgetVoice = true)
                initTts()
            }
        } else {
            val speechRate = (AppConfig.ttsSpeechRate + 5) / 10f
            textToSpeech?.setSpeechRate(speechRate)
            if (reset && !pause && ttsInitFinish) {
                playStop()
                play()
            }
        }
    }

    override fun pauseReadAloud(abandonFocus: Boolean) {
        super.pauseReadAloud(abandonFocus)
        activeUtteranceId = null
        queuedUntilIndex = -1
        speakGeneration++
        cancelPageBreakPrediction()
        retryParagraphKey = null
        retryingTtsInit = false
        if (AppConfig.ttsWavMode) {
            // 暂停时保留播放器现场，恢复时从当前位置继续当前句
            wavPosJob?.cancel()
            runCatching { wavPlayer.pause() }
            wavPausedPlayback = true
        }
        // tts.stop() 会冲掉引擎队列里的在途合成，任务列表在恢复/推进时重建
        clearRealtimePending()
        textToSpeech?.runCatching {
            stop()
        }
    }

    override fun resumeReadAloud() {
        super.resumeReadAloud()
        play()
    }

    private inner class TTSUtteranceListener : UtteranceProgressListener() {

        private val TAG = "TTSUtteranceListener"

        override fun onStart(s: String) {
            runActiveUtteranceCallback(s) {
                utteranceIndex(s)?.let { syncToUtteranceIndex(it) }
                LogUtils.d(TAG, "onStart nowSpeak:$nowSpeak pageIndex:$pageIndex utteranceId:$s")
                textChapter?.let {
                    if (nowSpeak !in contentList.indices) return@runActiveUtteranceCallback
                    if (pageIndex + 1 < it.pageSize
                        && readAloudNumber + 1 > it.getReadLength(pageIndex + 1)
                    ) {
                        // 只推进引擎私有页光标，显示翻页由 UI 侧跟随规则处理
                        pageIndex++
                    }
                    upTtsProgress(readAloudNumber + 1)
                    // 本朗读单元开始：记录计时基准并调度页界预测
                    utteranceStartRealtime = SystemClock.elapsedRealtime()
                    lastRangeOffset = 0
                    schedulePageBreakPrediction(currentUtteranceTextLength())
                }
            }
        }

        override fun onDone(s: String) {
            if (AppConfig.ttsWavMode) {
                // Wav 模式：onDone 表示合成完成（synthesizeToFile），驱动播放
                onWavSynthesisDone(s)
                return
            }
            runActiveUtteranceCallback(s) {
                LogUtils.d(TAG, "onDone utteranceId:$s")
                nextParagraph(s)
            }
        }

        override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) {
            super.onRangeStart(utteranceId, start, end, frame)
            runActiveUtteranceCallback(utteranceId) {
                utteranceIndex(utteranceId)?.let { syncToUtteranceIndex(it) }
                val msg =
                    "onRangeStart nowSpeak:$nowSpeak pageIndex:$pageIndex utteranceId:$utteranceId start:$start end:$end frame:$frame"
                LogUtils.d(TAG, msg)
                // 引擎实时汇报朗读进度：用真实读速校准预测速率（指数平滑）
                val now = SystemClock.elapsedRealtime()
                val elapsed = now - utteranceStartRealtime
                if (start > 0 && elapsed > 500) {
                    val sample = start.toDouble() / elapsed
                    measuredCharRate = measuredCharRate * 0.7 + sample * 0.3
                }
                lastRangeOffset = start
                textChapter?.let {
                    if (pageIndex + 1 < it.pageSize
                        && readAloudNumber + start > it.getReadLength(pageIndex + 1)
                    ) {
                        // 按引擎实时进度过页界：只推进引擎私有页光标并发布位置，
                        // 显示翻页由 UI 侧跟随规则处理
                        pageIndex++
                        upTtsProgress(readAloudNumber + start)
                    }
                }
            }
        }

        override fun onError(utteranceId: String?, errorCode: Int) {
            if (AppConfig.ttsWavMode) {
                onWavSynthesisError(utteranceIndex(utteranceId))
                return
            }
            runActiveUtteranceCallback(utteranceId) {
                utteranceIndex(utteranceId)?.let { syncToUtteranceIndex(it) }
                LogUtils.d(
                    TAG,
                    "onError nowSpeak:$nowSpeak pageIndex:$pageIndex utteranceId:$utteranceId errorCode:$errorCode"
                )
                handleSpeakError("tts utterance error:$errorCode", retryWithReinit = true)
            }
        }

        private fun nextParagraph(utteranceId: String?) {
            val index = utteranceIndex(utteranceId) ?: return
            if (index < nowSpeak) return
            syncToUtteranceIndex(index)
            // 本句真实总时长已知：先按它校准预测速率，再作废旧单元的页界预测
            //（预加载队列中下一单元 onStart 时会按最新光标与速率重新调度）
            val len = currentUtteranceTextLength()
            val elapsed = SystemClock.elapsedRealtime() - utteranceStartRealtime
            if (len > 0 && elapsed > 500) {
                val sample = len.toDouble() / elapsed
                measuredCharRate = measuredCharRate * 0.7 + sample * 0.3
                AppLog.putDebug(
                    "[朗读] 预测速率校准 len:$len 耗时:${elapsed}ms → " +
                        "${(measuredCharRate * 60_000).toInt()}/min"
                )
            }
            cancelPageBreakPrediction()
            activeUtteranceId = null
            retryParagraphKey = null
            if (!moveToNextParagraph()) {
                nextChapter()
                return
            }
            textToSpeech?.let { tts ->
                if (queuedUntilIndex >= nowSpeak) {
                    queueUpcomingUtterances(tts)
                } else {
                    speakCurrentParagraph()
                }
            } ?: speakCurrentParagraph()
        }

        @Deprecated("Deprecated in Java")
        override fun onError(s: String) {
            runActiveUtteranceCallback(s) {
                utteranceIndex(s)?.let { syncToUtteranceIndex(it) }
                LogUtils.d(TAG, "onError nowSpeak:$nowSpeak pageIndex:$pageIndex s:$s")
                handleSpeakError("tts utterance error", retryWithReinit = true)
            }
        }

        private fun runActiveUtteranceCallback(utteranceId: String?, block: () -> Unit) {
            if (!isActiveUtterance(utteranceId)) return
            lifecycleScope.launch(Main) {
                if (isActiveUtterance(utteranceId)) {
                    block.invoke()
                }
            }
        }

    }

    override fun aloudServicePendingIntent(actionStr: String): PendingIntent? {
        return servicePendingIntent<TTSReadAloudService>(actionStr)
    }

}
