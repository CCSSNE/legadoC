package io.legado.app.model

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import io.legado.app.constant.AppLog
import io.legado.app.constant.EventBus
import io.legado.app.constant.IntentAction
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.data.entities.HttpTTS
import io.legado.app.help.book.isAudio
import io.legado.app.help.config.AppConfig
import io.legado.app.help.tts.TtsEngineSetting
import io.legado.app.lib.dialogs.SelectItem
import io.legado.app.plugin.ReadAloudEngines
import io.legado.app.service.BaseReadAloudService
import io.legado.app.service.HttpReadAloudService
import io.legado.app.service.ReadAloudEngineType
import io.legado.app.service.ReadAloudProgress
import io.legado.app.service.SourceAudioReadAloudService
import io.legado.app.service.TTSReadAloudService
import io.legado.app.ui.book.audio.AudioPlayActivity
import io.legado.app.ui.book.read.ReadBookActivity
import io.legado.app.utils.GSON
import io.legado.app.utils.LogUtils
import io.legado.app.utils.StringUtils
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.postEvent
import io.legado.app.utils.startForegroundServiceCompat
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.getPrefBoolean
import splitties.init.appCtx

/** Absolute text position shared by every read-aloud engine and the reader UI. */
data class ReadAloudPosition(
    val chapterIndex: Int,
    val chapterPosition: Int,
)

/** A position confirmed by the read-aloud engine, plus the position it replaces. */
data class ReadAloudPositionUpdate(
    val position: ReadAloudPosition,
    val previousPosition: ReadAloudPosition?,
    val switchConfirmed: Boolean,
    val generation: Long,
    /**
     * 用户显式传送标记：本次位置发布来自“上一章/下一章、拖动朗读进度条”
     * 这类用户操作（命令层 syncView=true）。显示侧收到后直接走原语B
     * （backToAloudProgress）对齐，不走跟随规则判定。事件元数据，
     * 不是存储的跟随/脱钩状态。
     */
    val syncView: Boolean,
)

object ReadAloud {
    const val SOURCE_AUDIO_ENGINE_ID = "sourceAudio"

    @Volatile
    var aloudPosition: ReadAloudPosition? = null
        private set

    private var pendingSwitchPosition: ReadAloudPosition? = null
    private var positionGeneration = 0L

    @Synchronized
    fun beginPositionSwitch(position: ReadAloudPosition) {
        pendingSwitchPosition = position
    }

    @Synchronized
    fun cancelPositionSwitch() {
        if (pendingSwitchPosition != null) {
            AppLog.putDebug("[朗读] 切换取消 (pending=${pendingSwitchPosition})")
        }
        pendingSwitchPosition = null
    }

    /** The engine is the only authority allowed to update and publish this position. */
    @Synchronized
    fun publishAloudPosition(
        position: ReadAloudPosition,
        syncView: Boolean = false,
    ): ReadAloudPositionUpdate {
        val previousPosition = aloudPosition
        aloudPosition = position
        val generation = ++positionGeneration
        val switchConfirmed = pendingSwitchPosition == position
        if (switchConfirmed) {
            pendingSwitchPosition = null
        }
        AppLog.putDebug(
            "[朗读] 位置发布 ch:${position.chapterIndex} pos:${position.chapterPosition} " +
                "gen:$generation confirmed:$switchConfirmed syncView:$syncView " +
                "prev:${previousPosition?.let { "ch${it.chapterIndex}:${it.chapterPosition}" } ?: "null"}"
        )
        return ReadAloudPositionUpdate(
            position,
            previousPosition,
            switchConfirmed,
            generation,
            syncView,
        ).also {
            postEvent(EventBus.READ_ALOUD_POSITION, it)
        }
    }

    @Synchronized
    fun isCurrentPosition(update: ReadAloudPositionUpdate): Boolean {
        return update.generation == positionGeneration && update.position == aloudPosition
    }

    @Synchronized
    fun clearAloudPosition() {
        AppLog.putDebug(
            "[朗读] 位置清空 (原=${aloudPosition?.let { "ch${it.chapterIndex}:${it.chapterPosition}" } ?: "null"})"
        )
        aloudPosition = null
        positionGeneration++
        pendingSwitchPosition = null
    }

    val ttsEngine: String?
        get() = ReadBook.book?.let { book ->
            book.getTtsEngine() ?: if (book.isAudio) {
                SOURCE_AUDIO_ENGINE_ID
            } else {
                AppConfig.ttsEngine
            }
        } ?: AppConfig.ttsEngine

    var httpTTS: HttpTTS? = null
        private set

    val engineType: ReadAloudEngineType
        get() = when (val running = BaseReadAloudService.runningClass) {
            SourceAudioReadAloudService::class.java -> ReadAloudEngineType.SOURCE_AUDIO
            HttpReadAloudService::class.java -> ReadAloudEngineType.HTTP_TTS
            TTSReadAloudService::class.java -> ReadAloudEngineType.SYSTEM_TTS
            else -> running?.let { ReadAloudEngines.byServiceClass(it) }?.engineType ?: selectedEngineType
        }

    val selectedEngineType: ReadAloudEngineType
        get() {
            val selected = ttsEngine
            ReadAloudEngines.byId(selected)?.let { return it.engineType }
            return when {
                selected == SOURCE_AUDIO_ENGINE_ID -> ReadAloudEngineType.SOURCE_AUDIO
                selected != null && StringUtils.isNumeric(selected) -> ReadAloudEngineType.HTTP_TTS
                else -> ReadAloudEngineType.SYSTEM_TTS
            }
        }

    /**
     * 引擎选择值的界面显示名：内置插件引擎返回插件标签（运行依赖未就绪时附原因），
     * 系统引擎 JSON 选择值返回其标题；无法解析返回 null，由调用方回退系统 TTS 文案。
     */
    fun selectedEngineLabel(): String? {
        val selected = ttsEngine ?: return null
        ReadAloudEngines.byId(selected)?.let { plugin ->
            val unavailableReason = plugin.unavailableReason
            return if (unavailableReason != null) {
                "${plugin.engineLabel}（$unavailableReason）"
            } else {
                plugin.engineLabel
            }
        }
        return GSON.fromJsonObject<SelectItem<String>>(selected).getOrNull()?.title
    }

    private fun getReadAloudClass(): Class<out BaseReadAloudService>? {
        val book = ReadBook.book
        if (ttsEngine == SOURCE_AUDIO_ENGINE_ID) {
            httpTTS = null
            if (book?.isAudio != true) {
                reportEngineError("书源音频引擎只能用于有声书")
                return null
            }
            return SourceAudioReadAloudService::class.java
        }

        val selected = ttsEngine
        if (selected.isNullOrBlank()) {
            httpTTS = null
            return TTSReadAloudService::class.java
        }
        if (StringUtils.isNumeric(selected)) {
            httpTTS = appDb.httpTTSDao.get(selected.toLong())
            if (httpTTS == null) {
                reportEngineError("HTTP TTS 配置不存在：$selected")
                return null
            }
            return HttpReadAloudService::class.java
        }
        ReadAloudEngines.byId(selected)?.let { plugin ->
            httpTTS = null
            val unavailableReason = plugin.unavailableReason
            if (unavailableReason != null) {
                // 内置引擎运行依赖未就绪（如百度引擎未导入语音包）：明示后回退系统 TTS，不静默失效。
                reportUnavailableEngine(
                    selected,
                    "朗读引擎「${plugin.engineLabel}」不可用：$unavailableReason，已回退系统 TTS"
                )
                return TTSReadAloudService::class.java
            }
            return plugin.serviceClass
        }
        httpTTS = null
        // 非 SelectItem JSON 的裸引擎 id 且未被任何插件注册：本构建未内置的引擎
        // （如从别的版本备份恢复的 bdtts 配置），明示后回退系统 TTS，不静默失效。
        if (GSON.fromJsonObject<SelectItem<String>>(selected).getOrNull() == null) {
            reportUnavailableEngine(selected, "朗读引擎「$selected」未内置在本版本，已回退系统 TTS")
        }
        return TTSReadAloudService::class.java
    }

    fun upReadAloudClass() {
        postEvent(EventBus.READ_ALOUD_ENGINE_CHANGED, selectedEngineType)
        stop(appCtx)
    }

    // ===== TTS 引擎 V2（数据驱动引擎层，移植自 legado_NG）集成面 =====

    /** 当前生效的 SCRIPT 类 V2 引擎快照：SCRIPT 引擎经 HttpReadAloudService 合成时与服务侧共享的引擎状态。 */
    @Volatile
    var httpTtsEngineV2: TtsEngineSetting? = null

    /** 已为播放准备好的 V2 引擎快照；仅同 id 的更新会被接受，避免过期快照覆盖新选择的引擎。 */
    @Volatile
    private var preparedActiveEngine: TtsEngineSetting? = null

    fun updatePreparedTtsEngine(engine: TtsEngineSetting) {
        if (preparedActiveEngine?.id != engine.id) return
        preparedActiveEngine = engine
        if (httpTtsEngineV2?.id == engine.id) {
            httpTtsEngineV2 = engine
        }
    }

    /**
     * 重算引擎路由但不打断朗读。NG 中此函数重算存储的 aloudClass 字段；
     * 我们的引擎路由是每次播放时的派生值（无存储字段），等效动作是广播引擎变化
     * 让依赖引擎状态的界面同步刷新。与 [upReadAloudClass] 的区别是不停止当前朗读。
     */
    @Synchronized
    fun refreshReadAloudClass() {
        postEvent(EventBus.READ_ALOUD_ENGINE_CHANGED, selectedEngineType)
    }

    /** 通知正在运行的朗读服务刷新引擎路由参数（语速/音量/音调等运行时参数变更后调用）。 */
    fun refreshTtsRoute(context: Context) {
        if (BaseReadAloudService.isRun) {
            val intent = Intent(context, commandClass() ?: return)
            intent.action = IntentAction.refreshTtsRoute
            context.startForegroundServiceCompat(intent)
        }
    }

    /**
     * Returns a progress snapshot that matches the engine selected in settings.
     * A running service remains authoritative; when it has just been stopped for
     * an engine switch, use the shared text position before falling back to persisted data.
     */
    fun progressForSelectedEngine(): ReadAloudProgress? {
        val current = BaseReadAloudService.readAloudProgress
        val expectedKind = selectedProgressKind()
        val chapter = ReadBook.curTextChapter ?: return null
        val chapterIndex = chapter.chapter.index
        if (current?.kind == expectedKind && current.chapterIndex == chapterIndex) return current

        return if (expectedKind == ReadAloudProgress.Kind.TIME) {
            val total = chapter.chapter.end
                ?.takeIf { it > 0L && it <= Int.MAX_VALUE }
                ?.toInt()
                ?: return null
            val position = ReadBook.book?.getSourceAudioPosition()
                ?.coerceIn(0, total)
                ?: 0
            ReadAloudProgress(
                chapterIndex = chapterIndex,
                position = position,
                total = total,
                kind = expectedKind,
            )
        } else {
            val paragraphs = chapter.getParagraphs(
                appCtx.getPrefBoolean(PreferKey.pageSplit, false)
            )
            if (paragraphs.isEmpty()) return null
            val chapterPosition = aloudPosition
                ?.takeIf { it.chapterIndex == chapterIndex }
                ?.chapterPosition
                ?: ReadBook.book?.durChapterPos
                ?: 0
            val position = paragraphs.indexOfLast {
                chapterPosition >= it.chapterPosition
            }.coerceAtLeast(0)
            ReadAloudProgress(
                chapterIndex = chapterIndex,
                position = position.coerceIn(0, paragraphs.lastIndex),
                total = paragraphs.size,
                kind = expectedKind,
            )
        }
    }

    fun isProgressForSelectedEngine(progress: ReadAloudProgress): Boolean {
        return progress.kind == selectedProgressKind()
    }

    private fun selectedProgressKind(): ReadAloudProgress.Kind {
        return if (selectedEngineType == ReadAloudEngineType.SOURCE_AUDIO) {
            ReadAloudProgress.Kind.TIME
        } else {
            ReadAloudProgress.Kind.PARAGRAPH
        }
    }

    private fun commandClass(): Class<out BaseReadAloudService>? {
        @Suppress("UNCHECKED_CAST")
        return BaseReadAloudService.runningClass as? Class<out BaseReadAloudService>
            ?: getReadAloudClass()
    }

    private fun reportEngineError(message: String) {
        AppLog.put(message)
        appCtx.toastOnUi(message)
    }

    /** 已提示过的不可用引擎 id：同一 id 只提示一次，避免逐段朗读刷 toast。 */
    @Volatile
    private var lastUnavailableEngineReported: String? = null

    private fun reportUnavailableEngine(engineId: String, message: String) {
        AppLog.put(message)
        if (lastUnavailableEngineReported != engineId) {
            lastUnavailableEngineReported = engineId
            appCtx.toastOnUi(message)
        }
    }

    fun play(
        context: Context,
        play: Boolean = true,
        pageIndex: Int = ReadBook.durPageIndex,
        startPos: Int = 0
    ) {
        val serviceClass = commandClass() ?: run {
            cancelPositionSwitch()
            return
        }
        val intent = Intent(context, serviceClass)
        intent.action = IntentAction.play
        intent.putExtra("play", play)
        intent.putExtra("pageIndex", pageIndex)
        intent.putExtra("startPos", startPos)
        LogUtils.d("ReadAloud", intent.toString())
        try {
            context.startForegroundServiceCompat(intent)
        } catch (e: Exception) {
            cancelPositionSwitch()
            val msg = "启动朗读服务出错\n${e.localizedMessage}"
            AppLog.put(msg, e)
            context.toastOnUi(msg)
        }
    }

    fun playByEventBus(
        play: Boolean = true,
        pageIndex: Int = ReadBook.durPageIndex,
        startPos: Int = 0
    ) {
        val bundle = Bundle().apply {
            putBoolean("play", play)
            putInt("pageIndex", pageIndex)
            putInt("startPos", startPos)
        }
        postEvent(EventBus.READ_ALOUD_PLAY, bundle)
    }

    fun pause(context: Context) {
        if (BaseReadAloudService.isRun) {
            val intent = Intent(context, commandClass() ?: return)
            intent.action = IntentAction.pause
            context.startForegroundServiceCompat(intent)
        }
    }

    fun resume(context: Context) {
        if (BaseReadAloudService.isRun) {
            val intent = Intent(context, commandClass() ?: return)
            intent.action = IntentAction.resume
            context.startForegroundServiceCompat(intent)
        }
    }

    fun stop(context: Context) {
        if (BaseReadAloudService.isRun) {
            val intent = Intent(context, commandClass() ?: return)
            intent.action = IntentAction.stop
            context.startForegroundServiceCompat(intent)
        }
    }

    fun openAudioPlayActivity(context: Context) {
        val book = ReadBook.book ?: return
        val returnToReader = ReadBookActivity.activeActivity() != null
        ReadBook.saveRead()
        context.startActivity(
            Intent(context, AudioPlayActivity::class.java).apply {
                if (context !is Activity) {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                putExtra("bookUrl", book.bookUrl)
                putExtra("readAloudSession", true)
                putExtra("returnToReader", returnToReader)
            }
        )
    }

    fun prevParagraph(context: Context) {
        if (BaseReadAloudService.isRun) {
            val intent = Intent(context, commandClass() ?: return)
            intent.action = IntentAction.prevParagraph
            context.startForegroundServiceCompat(intent)
        }
    }

    fun nextParagraph(context: Context) {
        if (BaseReadAloudService.isRun) {
            val intent = Intent(context, commandClass() ?: return)
            intent.action = IntentAction.nextParagraph
            context.startForegroundServiceCompat(intent)
        }
    }

    /**
     * 拖动朗读进度条跳转。syncView=true（朗读面板进度条）表示用户显式传送：
     * 新位置发布带 syncView 标记，显示侧等同再点一次“回原进度”，
     * 语义与 [prevChapter]/[nextChapter] 一致；沉浸听书页等自身跟随
     * 进度事件的调用方保持默认 false。
     */
    fun seekToProgress(
        context: Context,
        chapterIndex: Int,
        position: Int,
        syncView: Boolean = false,
    ) {
        if (BaseReadAloudService.isRun) {
            val intent = Intent(context, commandClass() ?: return)
            intent.action = IntentAction.seekReadAloudProgress
            intent.putExtra("chapterIndex", chapterIndex)
            intent.putExtra("position", position)
            intent.putExtra("syncView", syncView)
            context.startForegroundServiceCompat(intent)
        }
    }

    fun seekToTextPosition(context: Context, chapterIndex: Int, chapterPosition: Int) {
        if (BaseReadAloudService.isRun) {
            val intent = Intent(context, commandClass() ?: return)
            intent.action = IntentAction.seekReadAloudTextPosition
            intent.putExtra("chapterIndex", chapterIndex)
            intent.putExtra("chapterPosition", chapterPosition)
            context.startForegroundServiceCompat(intent)
        }
    }

    /**
     * 上一章/下一章是用户显式传送：Intent 携带 syncView=true，
     * 引擎跳章后会把显示视角同步到目标章（等效自动触发“回原进度”）。
     * 引擎自然跨章不带该标记，视角是否跟随由跟随规则判定。
     */
    fun prevChapter(context: Context) {
        if (BaseReadAloudService.isRun) {
            val intent = Intent(context, commandClass() ?: return)
            intent.action = IntentAction.prev
            intent.putExtra("syncView", true)
            context.startForegroundServiceCompat(intent)
        }
    }

    fun nextChapter(context: Context) {
        if (BaseReadAloudService.isRun) {
            val intent = Intent(context, commandClass() ?: return)
            intent.action = IntentAction.next
            intent.putExtra("syncView", true)
            context.startForegroundServiceCompat(intent)
        }
    }

    fun upTtsSpeechRate(context: Context) {
        if (BaseReadAloudService.isRun) {
            val intent = Intent(context, commandClass() ?: return)
            intent.action = IntentAction.upTtsSpeechRate
            context.startForegroundServiceCompat(intent)
        }
    }

    fun setTimer(context: Context, minute: Int) {
        if (BaseReadAloudService.isRun) {
            val intent = Intent(context, commandClass() ?: return)
            intent.action = IntentAction.setTimer
            intent.putExtra("minute", minute)
            context.startForegroundServiceCompat(intent)
        }
    }

    fun setSpeed(context: Context, speed: Float) {
        if (BaseReadAloudService.isRun) {
            val intent = Intent(context, commandClass() ?: return)
            intent.action = IntentAction.setSpeed
            intent.putExtra("speed", speed)
            context.startForegroundServiceCompat(intent)
        }
    }

}
