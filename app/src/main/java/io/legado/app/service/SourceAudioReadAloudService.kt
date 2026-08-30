package io.legado.app.service

import android.app.PendingIntent
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.help.book.AudioTextMapping
import io.legado.app.help.book.SourceAudioResolver
import io.legado.app.help.config.AppConfig
import io.legado.app.help.exoplayer.ExoPlayerHelper
import io.legado.app.model.ReadBook
import io.legado.app.utils.isJsonArray
import io.legado.app.utils.servicePendingIntent
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SourceAudioReadAloudService : BaseReadAloudService(), Player.Listener {

    companion object {
        @Volatile
        var currentMediaUrl: String? = null
            private set
    }

    private val player: ExoPlayer by lazy {
        ExoPlayerHelper.createHttpExoPlayer(this).also { it.addListener(this) }
    }
    private var resolveJob: Job? = null
    private var progressJob: Job? = null
    private var mapping = AudioTextMapping(emptyList(), emptyList())
    private var layoutBinding: AudioTextMapping.LayoutBinding? = null
    private var playbackGeneration = 0L
    private var lastMappedParagraph = -1
    private var lastPersistedAt = 0L

    /**
     * 书源音频自己记住的当前章节。章节对象来自书源目录（BookChapter），
     * 章节身份只认统一朗读会话的 currentChapterIndex（BookChapter.index），
     * 正文 TextChapter 只负责字幕映射、不再决定音频当前正在播放哪一章。
     */
    private var currentChapter: BookChapter? = null

    // 书源音频的章节身份建立在 BookChapter.index 上，正文 TextChapter 未就绪也能启动
    override val sessionChapterCanStartWithoutText: Boolean get() = true

    override fun play() {
        super.play()
        if (!requestFocus()) {
            pauseReadAloud()
            return
        }
        val book = ReadBook.book ?: return failPlayback("书源音频启动失败：当前书籍为空")
        val chapter = resolveCurrentChapter(book)
            ?: return failPlayback("书源音频启动失败：当前章节为空")
        currentChapter = chapter
        val chapterIndex = chapter.index
        val generation = ++playbackGeneration
        resolveJob?.cancel()
        progressJob?.cancel()
        player.stop()
        layoutBinding = null
        upReadAloudLoading(true)
        resolveJob = lifecycleScope.launch {
            val result = runCatching {
                withContext(IO) {
                    SourceAudioResolver.resolve(book, ReadBook.bookSource, chapter)
                }
            }
            val resolved = result.getOrElse {
                failPlayback(it)
                return@launch
            }
            if (generation != playbackGeneration || chapterIndex != currentChapterIndex) {
                return@launch
            }
            runCatching {
                val resolvedMapping = resolved.mapping
                mapping = resolvedMapping
                layoutBinding = null
                lastMappedParagraph = -1
                // 字幕/LRC 绑定失败只影响正文同步与高亮，不阻断已解析成功的音频播放
                layoutBinding = runCatching {
                    bindMappingToLayout(resolvedMapping)
                }.onFailure {
                    AppLog.put(
                        "书源音频字幕绑定失败，仅影响正文同步/高亮：${it.localizedMessage}",
                        it
                    )
                }.getOrNull()
                currentMediaUrl = resolved.request.url
                val mediaSource = if (resolved.request.url.isJsonArray()) {
                    requireNotNull(
                        ExoPlayerHelper.getMediaSource(
                            this@SourceAudioReadAloudService,
                            resolved.request.url,
                            book,
                        )
                    ) { "书源音频地址数组格式错误" }
                } else {
                    ExoPlayerHelper.createOfflineMediaSource(
                        this@SourceAudioReadAloudService,
                        resolved.request.url,
                        resolved.request.headers,
                        book,
                    )
                }
                player.setMediaSource(mediaSource)
                player.setPlaybackSpeed(book.getPlaySpeed().coerceIn(0.5f, 3.0f))
                player.playWhenReady = true
                player.prepare()
                player.seekTo(resolveStartPosition(book, chapter.index).toLong())
            }.onFailure(::failPlayback)
        }
    }

    /**
     * 从统一朗读会话的 currentChapterIndex 取当前 BookChapter。
     * 章节对象只来自书源目录；TextChapter 未加载、无正文或正在切页都不会让音频丢失当前章节。
     */
    private fun resolveCurrentChapter(book: Book): BookChapter? {
        val sessionChapterIndex = currentChapterIndex
        if (sessionChapterIndex < 0) return null
        currentChapter?.takeIf { it.index == sessionChapterIndex }?.let { return it }
        return appDb.bookChapterDao.getChapter(book.bookUrl, sessionChapterIndex)?.also {
            currentChapter = it
        }
    }

    private fun bindMappingToLayout(
        mapping: AudioTextMapping,
    ): AudioTextMapping.LayoutBinding? {
        if (!mapping.hasTimeMapping) return null
        val chapter = textChapter?.takeIf { it.chapter.index == currentChapterIndex } ?: run {
            AppLog.putDebug(
                "Source audio mapping skipped: text chapter unavailable for index=$currentChapterIndex"
            )
            return null
        }
        val layoutParagraphs = chapter.getParagraphs(false)
        val binding = chapter.bindAudioTextMapping(mapping)
        AppLog.putDebug(
            "Source audio mapping bound: chapter=${chapter.chapter.index}, " +
                "subtitle=${mapping.paragraphs.size}, layout=${layoutParagraphs.size}, " +
                "content=${binding.paragraphCount}"
        )
        return binding
    }

    private fun resolveStartPosition(book: Book, chapterIndex: Int): Int {
        val mappedStart = layoutBinding?.timeForLayoutParagraph(nowSpeak)
        if (mappedStart != null) {
            return if (mappedStart == 0) {
                (book.getOpenCredits() * 1000).coerceAtLeast(0)
            } else {
                mappedStart
            }
        }
        if (nowSpeak > 0) {
            // 字幕无时间映射时无法从段落定位音频：只影响“指哪读哪”，
            // 回退到已存进度/章节起始，不能阻断正常播放
            AppLog.putDebug(
                "书源音频当前字幕无时间映射，无法从第 ${nowSpeak + 1} 段定位，回退到已存进度"
            )
        }
        return if (book.getSourceAudioChapterIndex() == chapterIndex) {
            book.getSourceAudioPosition()
        } else {
            (book.getOpenCredits() * 1000).coerceAtLeast(0)
        }
    }

    override fun playStop() {
        playbackGeneration++
        resolveJob?.cancel()
        progressJob?.cancel()
        player.stop()
    }

    override fun pauseReadAloud(abandonFocus: Boolean) {
        if (player.duration > 0) {
            persistProgress(player.currentPosition.toInt())
        }
        player.pause()
        progressJob?.cancel()
        super.pauseReadAloud(abandonFocus)
    }

    override fun resumeReadAloud() {
        if (player.mediaItemCount == 0) {
            play()
            return
        }
        if (!requestFocus()) return
        super.resumeReadAloud()
        player.play()
        startProgressUpdates()
    }

    override fun upSpeechRate(reset: Boolean) {
        val speed = ReadBook.book?.getPlaySpeed() ?: return
        setPlaybackSpeed(speed)
    }

    override fun setPlaybackSpeed(speed: Float) {
        require(speed.isFinite() && speed in 0.5f..3.0f) {
            "书源音频速度超出范围：$speed"
        }
        val book = requireNotNull(ReadBook.book) { "设置书源音频速度时当前书籍为空" }
        book.setPlaySpeed(speed)
        lifecycleScope.launch(IO) { book.save() }
        player.setPlaybackSpeed(speed)
    }

    override fun seekToReadAloudProgress(
        chapterIndex: Int,
        position: Int,
        syncView: Boolean,
    ) {
        if (chapterIndex != currentChapterIndex) {
            AppLog.putDebug(
                "Ignore stale source audio seek: requestedChapter=$chapterIndex, currentChapter=$currentChapterIndex"
            )
            publishTimeProgress()
            return
        }
        val duration = player.duration
        if (duration <= 0 || duration == C.TIME_UNSET) {
            return stopReadAloudOnInvalidPosition("书源音频跳转失败：音频时长尚未就绪")
        }
        val max = duration.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        if (position !in 0..max) {
            return stopReadAloudOnInvalidPosition(
                "书源音频跳转位置越界：position=$position, duration=$duration"
            )
        }
        player.seekTo(position.toLong())
        persistProgress(position)
        publishTimeProgress(position, duration)
        syncTextPosition(position, syncView)
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        when (playbackState) {
            Player.STATE_BUFFERING -> upReadAloudLoading(true)
            Player.STATE_READY -> {
                val duration = player.duration
                if (duration <= 0 || duration > Int.MAX_VALUE) {
                    failPlayback("书源音频时长无效：$duration")
                    return
                }
                upReadAloudLoading(false)
                currentChapter?.takeIf { it.index == currentChapterIndex }?.let { chapter ->
                    if (chapter.end != duration) {
                        chapter.end = duration
                        lifecycleScope.launch(IO) { chapter.update() }
                    }
                }
                publishTimeProgress()
                startProgressUpdates()
            }
            Player.STATE_ENDED -> {
                progressJob?.cancel()
                persistProgress(player.duration.coerceAtLeast(0).toInt())
                nextChapter()
            }
            Player.STATE_IDLE -> Unit
        }
    }

    override fun onPlayerError(error: PlaybackException) {
        failPlayback("书源音频播放失败：${error.errorCodeName}", error)
    }

    private fun startProgressUpdates() {
        progressJob?.cancel()
        progressJob = lifecycleScope.launch {
            val closeCreditsMs = (ReadBook.book?.getCloseCredits() ?: 0) * 1000L
            while (isActive) {
                val duration = player.duration
                val position = player.currentPosition.coerceAtLeast(0)
                if (duration > 0 && duration <= Int.MAX_VALUE) {
                    publishTimeProgress(position.toInt(), duration)
                    syncTextPosition(position.toInt())
                    val now = System.currentTimeMillis()
                    if (now - lastPersistedAt >= 5_000L) {
                        persistProgress(position.toInt())
                        lastPersistedAt = now
                    }
                    if (closeCreditsMs > 0 && position >= duration - closeCreditsMs) {
                        nextChapter()
                        return@launch
                    }
                }
                delay(AppConfig.readAloudProgressPollInterval.toLong())
            }
        }
    }

    private fun publishTimeProgress(
        position: Int = player.currentPosition.coerceAtLeast(0).toInt(),
        duration: Long = player.duration,
    ) {
        if (duration <= 0 || duration > Int.MAX_VALUE) return
        val chapterIndex = currentChapterIndex
        if (chapterIndex < 0) return
        publishReadAloudProgress(
            ReadAloudProgress(
                chapterIndex = chapterIndex,
                position = position.coerceIn(0, duration.toInt()),
                total = duration.toInt(),
                kind = ReadAloudProgress.Kind.TIME,
            )
        )
    }

    private fun syncTextPosition(position: Int, syncView: Boolean = false) {
        val chapter = textChapter?.takeIf { it.chapter.index == currentChapterIndex } ?: return
        val paragraphIndex = layoutBinding?.layoutParagraphAt(position) ?: return
        val paragraphUnchanged = paragraphIndex == lastMappedParagraph
        // 用户显式传送（拖动进度条）：映射段落未变也要发布一次位置事件，
        // 阅读页“回原进度”对齐才有目标；常规轮询保持段落变化才发布。
        if (paragraphUnchanged && !syncView) return
        val paragraphs = chapter.getParagraphs(false)
        val paragraph = paragraphs.getOrNull(paragraphIndex) ?: run {
            // 字幕映射异常只影响正文同步与高亮，不能停止正常播放的音频
            AppLog.putDebug(
                "书源音频字幕映射越界，跳过正文同步：paragraph=$paragraphIndex, layout=${paragraphs.size}"
            )
            return
        }
        if (!paragraphUnchanged) {
            lastMappedParagraph = paragraphIndex
            nowSpeak = paragraphIndex
            readAloudNumber = paragraph.chapterPosition
            pageIndex = chapter.getPageIndexByCharIndex(paragraph.chapterPosition)
        }
        postReadAloudTextPosition(paragraph.chapterPosition + 1, syncView)
    }

    private fun persistProgress(position: Int) {
        val book = ReadBook.book ?: return
        val chapterIndex = currentChapterIndex
        if (chapterIndex < 0) return
        book.setSourceAudioProgress(chapterIndex, position.coerceAtLeast(0))
        lifecycleScope.launch(IO) { book.save() }
    }

    private fun failPlayback(message: String) {
        failPlayback(message, null)
    }

    private fun failPlayback(error: Throwable) {
        failPlayback("书源音频播放失败：${error.localizedMessage}", error)
    }

    private fun failPlayback(message: String, error: Throwable?) {
        if (error == null) {
            AppLog.put(message)
        } else {
            AppLog.put(message, error)
        }
        toastOnUi(message)
        upReadAloudLoading(false)
        pauseReadAloud()
    }

    override fun onDestroy() {
        playbackGeneration++
        resolveJob?.cancel()
        progressJob?.cancel()
        if (player.duration > 0) persistProgress(player.currentPosition.toInt())
        player.removeListener(this)
        player.release()
        currentMediaUrl = null
        super.onDestroy()
    }

    override fun aloudServicePendingIntent(actionStr: String): PendingIntent? {
        return servicePendingIntent<SourceAudioReadAloudService>(actionStr)
    }
}
