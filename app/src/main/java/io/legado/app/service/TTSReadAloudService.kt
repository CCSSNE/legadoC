package io.legado.app.service

import android.app.PendingIntent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.constant.AppConst
import io.legado.app.constant.AppLog
import io.legado.app.constant.AppPattern
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.MediaHelp
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.dialogs.SelectItem
import io.legado.app.model.ReadAloud
import io.legado.app.utils.GSON
import io.legado.app.utils.LogUtils
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.servicePendingIntent
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.launch

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

    // 实测朗读速率（字/毫秒），初值按常见中文 TTS 默认语速约 250 字/分钟
    @Volatile
    private var measuredCharRate = 250.0 / 60_000.0

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
                "页界偏移:$breakOffset 延时:${delayMs}ms"
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

    @Synchronized
    private fun clearTTS(forgetVoice: Boolean = false) {
        activeUtteranceId = null
        queuedUntilIndex = -1
        speakGeneration++
        cancelPageBreakPrediction()
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
            // 朗读单元切换，旧单元的页界预测作废；预加载队列中下一单元
            // onStart 时会按最新光标重新调度
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
