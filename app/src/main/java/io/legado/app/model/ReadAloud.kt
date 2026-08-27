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
import io.legado.app.service.BaseReadAloudService
import io.legado.app.service.HttpReadAloudService
import io.legado.app.service.ReadAloudEngineType
import io.legado.app.service.ReadAloudProgress
import io.legado.app.service.SourceAudioReadAloudService
import io.legado.app.service.TTSReadAloudService
import io.legado.app.ui.book.audio.AudioPlayActivity
import io.legado.app.ui.book.read.ReadBookActivity
import io.legado.app.utils.LogUtils
import io.legado.app.utils.StringUtils
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
    fun publishAloudPosition(position: ReadAloudPosition): ReadAloudPositionUpdate {
        val previousPosition = aloudPosition
        aloudPosition = position
        val generation = ++positionGeneration
        val switchConfirmed = pendingSwitchPosition == position
        if (switchConfirmed) {
            pendingSwitchPosition = null
        }
        AppLog.putDebug(
            "[朗读] 位置发布 ch:${position.chapterIndex} pos:${position.chapterPosition} " +
                "gen:$generation confirmed:$switchConfirmed " +
                "prev:${previousPosition?.let { "ch${it.chapterIndex}:${it.chapterPosition}" } ?: "null"}"
        )
        return ReadAloudPositionUpdate(position, previousPosition, switchConfirmed, generation).also {
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
        get() = when (BaseReadAloudService.runningClass) {
            SourceAudioReadAloudService::class.java -> ReadAloudEngineType.SOURCE_AUDIO
            HttpReadAloudService::class.java -> ReadAloudEngineType.HTTP_TTS
            TTSReadAloudService::class.java -> ReadAloudEngineType.SYSTEM_TTS
            else -> selectedEngineType
        }

    val selectedEngineType: ReadAloudEngineType
        get() {
            val selected = ttsEngine
            return when {
                selected == SOURCE_AUDIO_ENGINE_ID -> ReadAloudEngineType.SOURCE_AUDIO
                selected != null && StringUtils.isNumeric(selected) -> ReadAloudEngineType.HTTP_TTS
                else -> ReadAloudEngineType.SYSTEM_TTS
            }
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
        httpTTS = null
        return TTSReadAloudService::class.java
    }

    fun upReadAloudClass() {
        postEvent(EventBus.READ_ALOUD_ENGINE_CHANGED, selectedEngineType)
        stop(appCtx)
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
                appCtx.getPrefBoolean(PreferKey.readAloudByPage, false)
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

    fun seekToProgress(context: Context, chapterIndex: Int, position: Int) {
        if (BaseReadAloudService.isRun) {
            val intent = Intent(context, commandClass() ?: return)
            intent.action = IntentAction.seekReadAloudProgress
            intent.putExtra("chapterIndex", chapterIndex)
            intent.putExtra("position", position)
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

    fun prevChapter(context: Context) {
        if (BaseReadAloudService.isRun) {
            val intent = Intent(context, commandClass() ?: return)
            intent.action = IntentAction.prev
            context.startForegroundServiceCompat(intent)
        }
    }

    fun nextChapter(context: Context) {
        if (BaseReadAloudService.isRun) {
            val intent = Intent(context, commandClass() ?: return)
            intent.action = IntentAction.next
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
