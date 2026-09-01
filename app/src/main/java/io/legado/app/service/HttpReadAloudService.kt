package io.legado.app.service

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.net.Uri
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.FileDataSource
import androidx.media3.datasource.cache.CacheDataSink
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.offline.DefaultDownloaderFactory
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.Downloader
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import com.script.ScriptException
import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.constant.AppPattern
import io.legado.app.constant.EventBus
import io.legado.app.constant.LogModule
import io.legado.app.constant.Status
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.HttpTTS
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.config.AppConfig
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.exoplayer.InputStreamDataSource
import io.legado.app.help.http.okHttpClient
import io.legado.app.help.tts.AiMultiVoiceConfig
import io.legado.app.help.tts.AiTtsStoryboardHelper
import io.legado.app.help.tts.BookTtsAutomationConfig
import io.legado.app.help.tts.BookTtsCastingCoordinator
import io.legado.app.help.tts.ChapterStoryboard
import io.legado.app.help.tts.ReadAloudTtsRouter
import io.legado.app.help.tts.TtsEngineSetting
import io.legado.app.help.tts.TtsEngineStore
import io.legado.app.help.tts.TtsCacheParams
import io.legado.app.help.tts.TtsCacheStore
import io.legado.app.help.tts.TtsScriptEngineClient
import io.legado.app.help.tts.TtsSpeedPolicy
import io.legado.app.help.tts.normalizeStoryboardSynthesisText
import io.legado.app.model.ReadAloud
import io.legado.app.model.ReadBook
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.ui.book.read.page.entities.TextChapter
import io.legado.app.utils.FileUtils
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.postEvent
import io.legado.app.utils.servicePendingIntent
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Response
import org.mozilla.javascript.WrappedException
import splitties.init.appCtx
import java.io.File
import java.io.InputStream
import java.net.ConnectException
import java.net.SocketTimeoutException

/**
 * 在线朗读
 */
@SuppressLint("UnsafeOptInUsageError")
class HttpReadAloudService : BaseReadAloudService(),
    Player.Listener {
    private val exoPlayer: ExoPlayer by lazy {
        ExoPlayer.Builder(this).build()
    }
    private val ttsFolderPath: String by lazy {
        cacheDir.absolutePath + File.separator + "httpTTS" + File.separator
    }
    private val cache by lazy {
        SimpleCache(
            File(cacheDir, "httpTTS_cache"),
            LeastRecentlyUsedCacheEvictor(128 * 1024 * 1024),
            StandaloneDatabaseProvider(appCtx)
        )
    }
    private val cacheDataSinkFactory by lazy {
        CacheDataSink.Factory()
            .setCache(cache)
    }
    private val loadErrorHandlingPolicy by lazy {
        CustomLoadErrorHandlingPolicy()
    }
    private var speechRate: Int = AppConfig.speechRatePlay + 5
    private var downloadTask: Coroutine<*>? = null
    private var playIndexJob: Job? = null
    private var httpTtsSnapshot: HttpTTS? = null
    private var httpRequestJob: Job? = null
    private var playErrorNo = 0

    // 上一句实测的单字符音频时长（毫秒）：流式播放拿不到总时长时，
    // 作为句内进度轮询的步长估计（页间分段 OFF 时的被动预测兜底）
    @Volatile
    private var lastCharDurationMs = 100L
    private val downloadTaskActiveLock = Mutex()

    private data class PreparedMediaItem(
        val textLength: Int,
        val mediaItem: MediaItem,
        /** 多角色子段：本子段在朗读单元内的起始字符偏移（整段合成时为 0）。 */
        val charStartInUnit: Int = 0,
        /** 多角色子段：是否为当前朗读单元最后一个媒体项（驱动段落推进）。 */
        val paragraphEnd: Boolean = true
    )

    /**
     * 多角色脚本管线的播放队列对齐条目：与 ExoPlayer 媒体项严格同序（主线程维护），
     * 仅当消费到段尾条目时才推进段落光标，保证一个朗读单元可由多个子段媒体项构成。
     */
    private data class ScriptQueueEntry(
        val textLength: Int,
        val charStartInUnit: Int,
        val paragraphEnd: Boolean
    )

    private val scriptQueue = ArrayDeque<ScriptQueueEntry>()

    // AI 分镜缓存：本章复用，失败章不再逐段重复发起 AI 请求（与百度宿主同构）。
    private var storyboard: ChapterStoryboard? = null
    private var storyboardChapterIndex = -1
    private var failedStoryboardChapterIndex = -1

    private data class PreparedMediaSource(
        val textLength: Int,
        val mediaSource: MediaSource,
        val downloader: Downloader
    )

    override fun onCreate() {
        super.onCreate()
        exoPlayer.addListener(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        cancelHttpWork()
        exoPlayer.release()
        cache.release()
        Coroutine.async {
            removeCacheFile()
        }
    }

    override fun play() {
        pageChanged = false
        exoPlayer.stop()
        applyPlaybackRate()
        if (!requestFocus()) return
        httpTtsSnapshot = ReadAloud.httpTTS
        if (ReadAloud.currentScriptTtsEngine() != null) {
            // 脚本引擎合成走文件缓存 + 顺序播放管线。
            if (contentList.isEmpty()) {
                AppLog.putDebug("朗读列表为空")
                nextChapter()
            } else {
                super.play()
                upReadAloudLoading(true)
                downloadAndPlayScriptAudios()
            }
            return
        }
        if (httpTtsSnapshot == null) {
            AppLog.putDebug("http tts is null")
            pauseReadAloud()
            return
        }
        if (contentList.isEmpty()) {
            AppLog.putDebug("朗读列表为空")
            nextChapter()
        } else {
            super.play()
            upReadAloudLoading(true)
            if (AppConfig.streamReadAloudAudio) {
                downloadAndPlayAudiosStream()
            } else {
                downloadAndPlayAudios()
            }
        }
    }

    /**
     * 播放端变速（对齐 legado_NG）：V2 脚本引擎的合成速度只取引擎自身配置，
     * 用户调节的朗读语速经 ExoPlayer 播放变速生效；经典 HTTP TTS 的语速已
     * 编码进合成 URL，播放速度必须保持 1.0，否则双重加速。
     */
    private fun applyPlaybackRate() {
        val rate = if (ReadAloud.currentScriptTtsEngine() != null) {
            TtsSpeedPolicy.playbackRate(AppConfig.speechRatePlay)
        } else {
            1f
        }
        exoPlayer.setPlaybackSpeed(rate)
    }

    override fun playStop() {
        cancelHttpWork()
        exoPlayer.stop()
        playIndexJob?.cancel()
    }

    private fun renewHttpRequestJob() {
        httpRequestJob?.cancel()
        httpRequestJob = SupervisorJob(lifecycleScope.coroutineContext[Job])
    }

    private fun cancelHttpWork() {
        downloadTask?.cancel()
        downloadTask = null
        httpRequestJob?.cancel()
        httpRequestJob = null
        scriptQueue.clear()
    }

    /**
     * 播放队列推进：多角色管线一个朗读单元可含多个子段媒体项，
     * 只在消费到段尾条目时推进段落光标；队列空（旧 HTTP/整段管线）直接走原推进。
     */
    private fun advanceScriptQueue(): Boolean {
        if (scriptQueue.isEmpty()) return updateNextPos()
        val entry = scriptQueue.removeFirst()
        if (!entry.paragraphEnd) return true
        return updateNextPos()
    }

    private fun updateNextPos(): Boolean {
        if (nowSpeak !in contentList.indices) {
            nextChapter()
            return false
        }
        readAloudNumber += contentList[nowSpeak].length + 1 - paragraphStartPos
        paragraphStartPos = 0
        if (nowSpeak < contentList.lastIndex) {
            nowSpeak++
            return true
        } else {
            nextChapter()
            return false
        }
    }

    private fun downloadAndPlayAudios() {
        exoPlayer.clearMediaItems()
        downloadTask?.cancel()
        renewHttpRequestJob()
        scriptQueue.clear()
        downloadTask = execute {
            downloadTaskActiveLock.withLock {
                ensureActive()
                val httpTts = httpTtsSnapshot ?: throw NoStackTraceException("tts is null")
                val firstMediaItems = arrayListOf<MediaItem>()
                var firstMediaLength = 0
                var firstMediaItemsAdded = false
                contentList.forEachIndexed { index, content ->
                    ensureActive()
                    if (index < nowSpeak) return@forEachIndexed
                    val prepared = runCatching {
                        prepareMediaItem(httpTts, index, content)
                    }.onFailure {
                        if (it !is CancellationException) pauseReadAloud()
                        return@execute
                    }.getOrThrow()
                    if (!firstMediaItemsAdded) {
                        firstMediaItems.add(prepared.mediaItem)
                        firstMediaLength += prepared.textLength
                        if (firstMediaLength >= httpStartPreloadLength()
                            || index == contentList.lastIndex
                        ) {
                            firstMediaItemsAdded = true
                            launch(Main) {
                                exoPlayer.addMediaItems(firstMediaItems)
                                upReadAloudLoading(false)
                            }
                        }
                    } else {
                        launch(Main) {
                            exoPlayer.addMediaItem(prepared.mediaItem)
                        }
                    }
                }
                if (!firstMediaItemsAdded && firstMediaItems.isNotEmpty()) {
                    launch(Main) {
                        exoPlayer.addMediaItems(firstMediaItems)
                        upReadAloudLoading(false)
                    }
                }
                preDownloadAudios(httpTts)
            }
        }.onError {
            AppLog.put("朗读下载出错\n${it.localizedMessage}", it, true)
        }
    }

    /**
     * 脚本引擎合成管线：与旧 HttpTTS 路径同构的文件缓存 +
     * 顺序播放，逐段经 [TtsScriptEngineClient.getSynthesisStream] 获取音频流。
     * AI 多角色开启且分镜就绪时，朗读单元按分镜子段拆分：每段经选角路由
     * （[ReadAloudTtsRouter]）解析（引擎, 音色）后独立合成；绑定到内置语音包
     * 引擎外观（无脚本可合成）的子段明示日志后按当前引擎兜底，不静默、不丢段。
     * 音色/语速/音量/音调取路由引擎当前运行时状态；参数变更经 refreshTtsRoute 热刷新。
     */
    private fun downloadAndPlayScriptAudios() {
        exoPlayer.clearMediaItems()
        downloadTask?.cancel()
        renewHttpRequestJob()
        scriptQueue.clear()
        downloadTask = execute {
            downloadTaskActiveLock.withLock {
                ensureActive()
                val scriptEngine = ReadAloud.currentScriptTtsEngine()
                    ?: throw NoStackTraceException("朗读脚本引擎为空")
                val storyboard = prepareScriptStoryboard()
                val firstMediaItems = arrayListOf<MediaItem>()
                val firstQueueEntries = arrayListOf<ScriptQueueEntry>()
                var firstMediaLength = 0
                var firstMediaItemsAdded = false
                contentList.forEachIndexed { index, content ->
                    ensureActive()
                    if (index < nowSpeak) return@forEachIndexed
                    val items = runCatching {
                        prepareScriptMediaItems(scriptEngine, storyboard, index, content)
                    }.onFailure {
                        if (it !is CancellationException) pauseReadAloud()
                        return@execute
                    }.getOrThrow()
                    if (!firstMediaItemsAdded) {
                        items.forEach { prepared ->
                            firstMediaItems.add(prepared.mediaItem)
                            firstQueueEntries.add(
                                ScriptQueueEntry(prepared.textLength, prepared.charStartInUnit, prepared.paragraphEnd)
                            )
                            firstMediaLength += prepared.textLength
                        }
                        if (firstMediaLength >= httpStartPreloadLength()
                            || index == contentList.lastIndex
                        ) {
                            firstMediaItemsAdded = true
                            launch(Main) {
                                exoPlayer.addMediaItems(firstMediaItems)
                                scriptQueue.addAll(firstQueueEntries)
                                upReadAloudLoading(false)
                            }
                        }
                    } else {
                        launch(Main) {
                            items.forEach { prepared ->
                                exoPlayer.addMediaItem(prepared.mediaItem)
                                scriptQueue.add(
                                    ScriptQueueEntry(prepared.textLength, prepared.charStartInUnit, prepared.paragraphEnd)
                                )
                            }
                        }
                    }
                }
                if (!firstMediaItemsAdded && firstMediaItems.isNotEmpty()) {
                    launch(Main) {
                        exoPlayer.addMediaItems(firstMediaItems)
                        scriptQueue.addAll(firstQueueEntries)
                        upReadAloudLoading(false)
                    }
                }
            }
        }.onError {
            AppLog.put("朗读下载出错\n${it.localizedMessage}", it, true)
        }
    }

    /**
     * 确保本章分镜就绪（缓存优先，未命中现场调 1号AI），收编角色并按需自动选音。
     * 返回 null = 本段回退整段合成（多角色未开、缺书章信息或本章已失败过）。
     */
    private suspend fun prepareScriptStoryboard(): ChapterStoryboard? {
        if (!AiMultiVoiceConfig.enabled) return null
        val book = ReadBook.book ?: return null
        val chapter = textChapter ?: return null
        val chapterIndex = chapter.chapter.index
        if (storyboardChapterIndex == chapterIndex && storyboard != null) return storyboard
        if (failedStoryboardChapterIndex == chapterIndex) return null
        return try {
            val chapterContent = if (chapter.isCompleted) {
                chapter.getNeedReadAloud(0, false, 0).takeIf { it.isNotBlank() }
            } else {
                null
            } ?: return null
            val workKey = BookTtsAutomationConfig.workKeyOf(book.name, book.author)
            val generated = AiTtsStoryboardHelper.getOrGenerate(
                book, chapterIndex, chapter.title ?: "", chapterContent
            )
            val synced = BookTtsCastingCoordinator.syncCastRoles(workKey, chapterIndex, generated)
            storyboard = synced
            storyboardChapterIndex = chapterIndex
            failedStoryboardChapterIndex = -1
            if (BookTtsAutomationConfig.get(workKey).autoAssignVoices) {
                runCatching { BookTtsCastingCoordinator.assignMissingVoices(workKey) }
                    .onFailure { error ->
                        AppLog.put(
                            "[朗读][AI选音] 失败\n${error.localizedMessage}",
                            module = LogModule.AI_CAST
                        )
                    }
            }
            synced
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            failedStoryboardChapterIndex = chapterIndex
            AppLog.put(
                "[朗读][AI分镜] 分镜生成失败，本章按当前引擎整段合成\n${error.localizedMessage}",
                module = LogModule.AI_CAST
            )
            null
        }
    }

    /** 朗读单元 → 播放媒体项列表：多角色分镜命中时按子段拆分，否则整段一项。 */
    private suspend fun prepareScriptMediaItems(
        engine: TtsEngineSetting,
        storyboard: ChapterStoryboard?,
        index: Int,
        content: String
    ): List<PreparedMediaItem> {
        val currentStoryboard = storyboard ?: return listOf(prepareScriptMediaItem(engine, index, content))
        val text = getSpeakContent(index, content)
        val paragraphIndex = locateStoryboardParagraph(currentStoryboard, text)
            ?: return listOf(prepareScriptMediaItem(engine, index, content))
        val router = ReadBook.book?.let { ReadAloudTtsRouter.create(it) }
            ?: return listOf(prepareScriptMediaItem(engine, index, content))
        val segments = currentStoryboard.segmentsForParagraph(paragraphIndex)
        if (segments.isEmpty()) return listOf(prepareScriptMediaItem(engine, index, content))
        val paragraphText = currentStoryboard.paragraphs.getOrNull(paragraphIndex).orEmpty()
        val unitStart = locateStoryboardUnitOffset(paragraphText, text)
        val unitEnd = (unitStart + text.length).coerceAtMost(paragraphText.length)
        val items = arrayListOf<PreparedMediaItem>()
        segments.forEach { segment ->
            val overlapStart = maxOf(segment.start, unitStart)
            val overlapEnd = minOf(segment.end, unitEnd)
            if (overlapEnd <= overlapStart) return@forEach
            val overlapText = paragraphText.substring(
                overlapStart.coerceIn(0, paragraphText.length),
                overlapEnd.coerceIn(0, paragraphText.length)
            )
            val synthText = normalizeStoryboardSynthesisText(overlapText, segment.type)
            if (synthText.isBlank()) return@forEach
            var route = router.route(segment, engine)
            if (route.engine.id == TtsEngineStore.VOICE_DIRECTORY_ID || route.engine.script.isBlank()) {
                // 选角绑定到内置语音包外观：脚本宿主无法合成，明示后按当前引擎兜底
                AppLog.put(
                    "[朗读][AI分镜] 子段绑定到内置语音包引擎「${route.engine.name}」，" +
                        "脚本引擎宿主不支持该引擎合成，按当前引擎兜底",
                    module = LogModule.AI_CAST
                )
                route = route.copy(engine = engine, voiceId = engine.activeVoiceId)
            }
            items += prepareScriptSegmentItem(
                engine = route.engine,
                voiceId = route.voiceId,
                synthText = synthText,
                textLength = overlapEnd - overlapStart,
                charStartInUnit = overlapStart - unitStart,
                paragraphEnd = false
            )
        }
        if (items.isEmpty()) return listOf(prepareScriptMediaItem(engine, index, content))
        items[items.lastIndex] = items[items.lastIndex].copy(paragraphEnd = true)
        return items
    }

    /** 定位朗读单元在分镜段落内的字符偏移：精确（含续读起点）→ 后缀 → 0。 */
    private fun locateStoryboardUnitOffset(paragraphText: String, speakText: String): Int {
        if (speakText.isEmpty()) return 0
        val exact = paragraphText.indexOf(speakText)
        if (exact >= 0) return exact
        if (paragraphText.endsWith(speakText)) {
            return paragraphText.length - speakText.length
        }
        return 0
    }

    /**
     * 定位朗读段在分镜中的段落下标：分镜段落与 contentList 同源，
     * 归一空白后先精确匹配，再包含匹配（段内偏移续读场景）。
     */
    private fun locateStoryboardParagraph(storyboard: ChapterStoryboard, speakText: String): Int? {
        val normalize: (String) -> String = { value -> value.filterNot { it.isWhitespace() } }
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

    /** 按路由（引擎, 音色）合成单个分镜子段，缓存键含路由引擎与音色。 */
    private suspend fun prepareScriptSegmentItem(
        engine: TtsEngineSetting,
        voiceId: String?,
        synthText: String,
        textLength: Int,
        charStartInUnit: Int,
        paragraphEnd: Boolean
    ): PreparedMediaItem {
        val speed = TtsSpeedPolicy.synthesisSpeed(engine)
        val fileName = MD5Utils.md5Encode16(textChapter?.title ?: "") + "_" +
                MD5Utils.md5Encode16(
                    TtsScriptEngineClient.audioCacheKey(
                        engine = engine,
                        text = synthText,
                        voiceId = voiceId,
                        speed = speed
                    )
                )
        if (!hasSpeakFile(fileName)) {
            val stream = TtsScriptEngineClient.getSynthesisStream(
                engine = engine,
                text = synthText,
                voiceId = voiceId,
                speed = speed,
                coroutineContext = currentCoroutineContext()
            )
            currentCoroutineContext().ensureActive()
            createSpeakFile(fileName, stream)
        }
        val file = getSpeakFileAsMd5(fileName)
        return PreparedMediaItem(textLength, MediaItem.fromUri(Uri.fromFile(file)), charStartInUnit, paragraphEnd)
    }

    private suspend fun prepareScriptMediaItem(
        engine: TtsEngineSetting,
        index: Int,
        content: String
    ): PreparedMediaItem {
        val text = getSpeakContent(index, content)
        // 批量 TTS 缓存命中（整段单元）：直接播缓存文件，不再走脚本合成请求
        ttsCacheUnitFile(text)?.let { file ->
            return PreparedMediaItem(text.length, MediaItem.fromUri(Uri.fromFile(file)))
        }
        val fileName = md5SpeakFileNameForScript(engine, text)
        val speakText = text.replace(AppPattern.notReadAloudRegex, "")
        if (speakText.isEmpty()) {
            AppLog.put("阅读段落内容为空，使用无声音频代替。\n朗读文本：$text")
            createSilentSound(fileName)
        } else if (!hasSpeakFile(fileName)) {
            val stream = TtsScriptEngineClient.getSynthesisStream(
                engine = engine,
                text = speakText,
                voiceId = engine.activeVoiceId,
                speed = TtsSpeedPolicy.synthesisSpeed(engine),
                coroutineContext = currentCoroutineContext()
            )
            currentCoroutineContext().ensureActive()
            createSpeakFile(fileName, stream)
        }
        val file = getSpeakFileAsMd5(fileName)
        return PreparedMediaItem(text.length, MediaItem.fromUri(Uri.fromFile(file)))
    }

    private fun md5SpeakFileNameForScript(
        engine: TtsEngineSetting,
        content: String,
        textChapter: TextChapter? = this.textChapter
    ): String {
        val cacheKey = TtsScriptEngineClient.audioCacheKey(engine, content)
        return MD5Utils.md5Encode16(textChapter?.title ?: "") + "_" +
                MD5Utils.md5Encode16(cacheKey)
    }

    override fun refreshTtsRoute() {
        if (ReadAloud.currentScriptTtsEngine() == null) return
        // 运行时参数或选角路由变更：中断当前下载/播放，从当前段落按新参数重新合成
        playIndexJob?.cancel()
        downloadTask?.cancel()
        exoPlayer.stop()
        scriptQueue.clear()
        if (!pause) {
            postEvent(EventBus.ALOUD_STATE, Status.LOADING)
        }
        downloadAndPlayScriptAudios()
    }

    private suspend fun preDownloadAudios(httpTts: HttpTTS) {
        val book = ReadBook.book ?: return
        val textChapter = ReadBook.nextTextChapter ?: return
        val contentList = textChapter.getNeedReadAloud(0, pageSplit, 0, 1)
            .splitToSequence("\n")
            .filter { it.isNotEmpty() }
            .takePreloadContentList(maxLength = httpPreloadAheadLength())
        contentList.forEach { content ->
            currentCoroutineContext().ensureActive()
            val fileName = md5SpeakFileName(content, textChapter)
            val speakText = content.replace(AppPattern.notReadAloudRegex, "")
            if (speakText.isEmpty()) {
                createSilentSound(fileName)
            } else if (
                !hasSpeakFile(fileName) &&
                // 批量缓存已覆盖的单元不再重复请求网络，播放时直接命中缓存
                ttsCacheUnitFile(book, textChapter.chapter, content) == null
            ) {
                runCatching {
                    val inputStream = getSpeakStream(
                        httpTts,
                        speakText,
                        pauseOnFailure = false
                    )
                    if (inputStream != null) {
                        createSpeakFile(fileName, inputStream)
                    } else {
                        createSilentSound(fileName)
                    }
                }
            }
        }
    }

    private fun downloadAndPlayAudiosStream() {
        exoPlayer.clearMediaItems()
        downloadTask?.cancel()
        renewHttpRequestJob()
        scriptQueue.clear()
        downloadTask = execute {
            downloadTaskActiveLock.withLock {
                ensureActive()
                val httpTts = httpTtsSnapshot ?: throw NoStackTraceException("tts is null")
                val downloaderChannel = Channel<Downloader>()
                launch {
                    for (downloader in downloaderChannel) {
                        runCatching {
                            downloader.download(null)
                        }.onFailure {
                            if (it is CancellationException) throw it
                            AppLog.putDebug("http tts pre download error:${it.localizedMessage}")
                        }
                    }
                }
                try {
                    val firstMediaSources = arrayListOf<MediaSource>()
                    var firstMediaLength = 0
                    var firstMediaSourcesAdded = false
                    contentList.forEachIndexed { index, content ->
                        ensureActive()
                        if (index < nowSpeak) return@forEachIndexed
                        val prepared = prepareMediaSource(httpTts, index, content)
                        if (!firstMediaSourcesAdded) {
                            runCatching {
                                prepared.downloader.download(null)
                            }.onFailure {
                                if (it is CancellationException) throw it
                                pauseReadAloud()
                                return@execute
                            }
                            firstMediaSources.add(prepared.mediaSource)
                            firstMediaLength += prepared.textLength
                            if (firstMediaLength >= httpStartPreloadLength()
                                || index == contentList.lastIndex
                            ) {
                                firstMediaSourcesAdded = true
                                launch(Main) {
                                    exoPlayer.addMediaSources(firstMediaSources)
                                    upReadAloudLoading(false)
                                }
                            }
                        } else {
                            downloaderChannel.send(prepared.downloader)
                            launch(Main) {
                                exoPlayer.addMediaSource(prepared.mediaSource)
                            }
                        }
                    }
                    if (!firstMediaSourcesAdded && firstMediaSources.isNotEmpty()) {
                        launch(Main) {
                            exoPlayer.addMediaSources(firstMediaSources)
                            upReadAloudLoading(false)
                        }
                    }
                    preDownloadAudiosStream(httpTts, downloaderChannel)
                } finally {
                    downloaderChannel.close()
                }
            }
        }.onError {
            AppLog.put("朗读下载出错\n${it.localizedMessage}", it, true)
        }
    }

    private suspend fun preDownloadAudiosStream(
        httpTts: HttpTTS,
        downloaderChannel: Channel<Downloader>
    ) {
        val book = ReadBook.book ?: return
        val textChapter = ReadBook.nextTextChapter ?: return
        val contentList = textChapter.getNeedReadAloud(0, pageSplit, 0, 1)
            .splitToSequence("\n")
            .filter { it.isNotEmpty() }
            .takePreloadContentList(maxLength = httpPreloadAheadLength())
        contentList.forEach { content ->
            currentCoroutineContext().ensureActive()
            // 批量缓存已覆盖的单元不再发起网络预取，播放时直接命中缓存文件
            if (ttsCacheUnitFile(book, textChapter.chapter, content) != null) {
                return@forEach
            }
            val fileName = md5SpeakFileName(content, textChapter)
            val speakText = content.replace(AppPattern.notReadAloudRegex, "")
            val dataSourceFactory = createDataSourceFactory(
                httpTts,
                speakText,
                pauseOnFailure = false
            )
            val downloader = createDownloader(dataSourceFactory, fileName)
            downloaderChannel.send(downloader)
        }
    }

    private fun createDataSourceFactory(
        httpTts: HttpTTS,
        speakText: String,
        pauseOnFailure: Boolean = true
    ): CacheDataSource.Factory {
        val upstreamFactory = DataSource.Factory {
            InputStreamDataSource {
                if (speakText.isEmpty()) {
                    null
                } else {
                    kotlin.runCatching {
                        val requestJob = httpRequestJob ?: lifecycleScope.coroutineContext[Job]!!
                        runBlocking(requestJob) {
                            getSpeakStream(httpTts, speakText, pauseOnFailure)
                        }
                    }.onFailure {
                        when (it) {
                            is InterruptedException,
                            is CancellationException -> Unit

                            else -> if (pauseOnFailure) pauseReadAloud()
                        }
                    }.getOrThrow()
                } ?: resources.openRawResource(R.raw.silent_sound)
            }
        }
        val factory = CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(upstreamFactory)
            .setCacheWriteDataSinkFactory(cacheDataSinkFactory)
        return factory
    }

    private suspend fun prepareMediaItem(
        httpTts: HttpTTS,
        index: Int,
        content: String
    ): PreparedMediaItem {
        val text = getSpeakContent(index, content)
        // 批量 TTS 缓存命中（整段单元）：直接播缓存文件，不再请求网络
        ttsCacheUnitFile(text)?.let { file ->
            return PreparedMediaItem(text.length, MediaItem.fromUri(Uri.fromFile(file)))
        }
        val fileName = md5SpeakFileName(text)
        val speakText = text.replace(AppPattern.notReadAloudRegex, "")
        if (speakText.isEmpty()) {
            AppLog.put("阅读段落内容为空，使用无声音频代替。\n朗读文本：$text")
            createSilentSound(fileName)
        } else if (!hasSpeakFile(fileName)) {
            val inputStream = getSpeakStream(httpTts, speakText)
            if (inputStream != null) {
                createSpeakFile(fileName, inputStream)
            } else {
                createSilentSound(fileName)
            }
        }
        val file = getSpeakFileAsMd5(fileName)
        return PreparedMediaItem(text.length, MediaItem.fromUri(Uri.fromFile(file)))
    }

    private fun prepareMediaSource(
        httpTts: HttpTTS,
        index: Int,
        content: String
    ): PreparedMediaSource {
        val text = getSpeakContent(index, content)
        // 批量 TTS 缓存命中（整段单元）：本地文件数据源 + 空下载器，不再请求网络
        ttsCacheUnitFile(text)?.let { file ->
            val factory = DataSource.Factory { FileDataSource() }
            return PreparedMediaSource(
                text.length,
                createMediaSource(factory, file.absolutePath),
                noOpDownloader
            )
        }
        val speakText = text.replace(AppPattern.notReadAloudRegex, "")
        if (speakText.isEmpty()) {
            AppLog.put("阅读段落内容为空，使用无声音频代替。\n朗读文本：$text")
        }
        val fileName = md5SpeakFileName(text)
        val dataSourceFactory = createDataSourceFactory(httpTts, speakText)
        return PreparedMediaSource(
            text.length,
            createMediaSource(dataSourceFactory, fileName),
            createDownloader(dataSourceFactory, fileName)
        )
    }

    private fun getSpeakContent(index: Int, content: String): String {
        if (paragraphStartPos > 0 && index == nowSpeak) {
            return content.substring(paragraphStartPos.coerceAtMost(content.length))
        }
        return content
    }

    /**
     * 批量 TTS 缓存命中（整段单元）：缓存文件直接作为播放数据源，不再请求网络。
     * key 与批量缓存/实时缓存同源（[TtsCacheParams.playbackUnitKey]，音色维度按
     * 引擎种类解析）。多角色分镜子段与段内续读（paragraphStartPos 偏移后的文本）
     * 与缓存单元文本不同，自然不命中，仍走现场合成。
     */
    private fun ttsCacheUnitFile(text: String): File? {
        val book = ReadBook.book ?: return null
        val chapter = textChapter?.chapter ?: return null
        return ttsCacheUnitFile(book, chapter, text)
    }

    private fun ttsCacheUnitFile(book: Book, chapter: BookChapter, text: String): File? {
        if (text.isEmpty()) return null
        val key = TtsCacheParams.playbackUnitKey(book, chapter, text)
        if (!TtsCacheStore.has(book, key)) return null
        return TtsCacheStore.unitFile(book, key)
    }

    private fun httpPreloadAheadLength(): Int {
        return minReadAloudPreloadLength()
    }

    private fun httpStartPreloadLength(): Int {
        return 60
    }

    private fun Sequence<String>.takePreloadContentList(
        maxCount: Int = 30,
        maxLength: Int = minReadAloudPreloadLength()
    ): List<String> {
        val list = arrayListOf<String>()
        var length = 0
        for (content in this) {
            list.add(content)
            length += content.length
            if (list.size >= maxCount || length >= maxLength) {
                break
            }
        }
        return list
    }

    private fun createDownloader(factory: CacheDataSource.Factory, fileName: String): Downloader {
        val uri = fileName.toUri()
        val request = DownloadRequest.Builder(fileName, uri).build()
        return DefaultDownloaderFactory(factory, okHttpClient.dispatcher.executorService)
            .createDownloader(request)
    }

    /** 批量缓存命中单元的占位下载器：文件已在本地，无网络下载动作。 */
    private val noOpDownloader = object : Downloader {
        override fun download(progressListener: Downloader.ProgressListener?) = Unit
        override fun cancel() = Unit
        override fun remove() = Unit
    }

    private fun createMediaSource(factory: DataSource.Factory, fileName: String): MediaSource {
        return DefaultMediaSourceFactory(this)
            .setDataSourceFactory(factory)
            .setLoadErrorHandlingPolicy(loadErrorHandlingPolicy)
            .createMediaSource(MediaItem.fromUri(fileName))
    }

    private suspend fun getSpeakStream(
        httpTts: HttpTTS,
        speakText: String,
        pauseOnFailure: Boolean = true
    ): InputStream? {
        var downloadErrorNo = 0
        while (true) {
            try {
                val analyzeUrl = AnalyzeUrl(
                    httpTts.url,
                    speakText = speakText,
                    speakSpeed = speechRate,
                    source = httpTts,
                    readTimeout = 300 * 1000L,
                    coroutineContext = currentCoroutineContext()
                )
                val checkJs = httpTts.loginCheckJs
                val response = kotlin.runCatching {
                    analyzeUrl.getResponseAwait().let {
                        currentCoroutineContext().ensureActive()
                        if (!checkJs.isNullOrBlank()) {
                            analyzeUrl.evalJS(checkJs, it) as Response
                        } else {
                            it
                        }
                    }
                }.getOrElse { throwable ->
                    currentCoroutineContext().ensureActive()
                    if (!checkJs.isNullOrBlank()) {
                        val errResponse = analyzeUrl.getErrResponse(throwable)
                        try {
                            (analyzeUrl.evalJS(checkJs, errResponse) as Response).also {
                                if (it.code == 500) {
                                    throw throwable
                                }
                            }
                        } catch (_: Throwable) {
                            throw throwable
                        }
                    } else {
                        throw throwable
                    }
                }
                response.headers["Content-Type"]?.let { contentType ->
                    val contentType = contentType.substringBefore(";")
                    val ct = httpTts.contentType
                    if (contentType == "application/json" || contentType.startsWith("text/")) {
                        throw NoStackTraceException(response.body.string())
                    } else if (ct?.isNotBlank() == true) {
                        if (!contentType.matches(ct.toRegex())) {
                            throw NoStackTraceException(
                                "TTS服务器返回错误：" + response.body.string()
                            )
                        }
                    }
                }
                currentCoroutineContext().ensureActive()
                response.body.byteStream().let { stream ->
                    return stream
                }
            } catch (e: Exception) {
                when (e) {
                    is CancellationException -> throw e
                    is ScriptException, is WrappedException -> {
                        AppLog.put("js错误\n${e.localizedMessage}", e, true)
                        throw e
                    }

                    is SocketTimeoutException, is ConnectException -> {
                        downloadErrorNo++
                        if (downloadErrorNo > 5 || !pauseOnFailure) {
                            val msg = "tts超时或连接错误超过5次\n${e.localizedMessage}"
                            AppLog.put(msg, e, true)
                            throw e
                        }
                    }

                    else -> {
                        downloadErrorNo++
                        val msg = "tts下载错误\n${e.localizedMessage}"
                        AppLog.put(msg, e)
                        if (downloadErrorNo > 5 || !pauseOnFailure) {
                            val msg1 = "TTS服务器连续5次错误，已暂停阅读。"
                            AppLog.put(msg1, e, true)
                            throw e
                        } else {
                            AppLog.put("TTS下载音频出错，使用无声音频代替。\n朗读文本：$speakText")
                            break
                        }
                    }
                }
            }
        }
        return null
    }

    private fun md5SpeakFileName(content: String, textChapter: TextChapter? = this.textChapter): String {
        return MD5Utils.md5Encode16(textChapter?.title ?: "") + "_" +
                MD5Utils.md5Encode16("${httpTtsSnapshot?.url}-|-$speechRate-|-$content")
    }

    private fun createSilentSound(fileName: String) {
        val file = createSpeakFile(fileName)
        file.writeBytes(resources.openRawResource(R.raw.silent_sound).readBytes())
    }

    private fun hasSpeakFile(name: String): Boolean {
        return FileUtils.exist("${ttsFolderPath}$name.mp3")
    }

    private fun getSpeakFileAsMd5(name: String): File {
        return File("${ttsFolderPath}$name.mp3")
    }

    private fun createSpeakFile(name: String): File {
        return FileUtils.createFileIfNotExist("${ttsFolderPath}$name.mp3")
    }

    private fun createSpeakFile(name: String, inputStream: InputStream) {
        FileUtils.createFileIfNotExist("${ttsFolderPath}$name.mp3").outputStream().use { out ->
            inputStream.use {
                it.copyTo(out)
            }
        }
    }

    /**
     * 移除缓存文件
     */
    private fun removeCacheFile() {
        val titleMd5 = MD5Utils.md5Encode16(textChapter?.title ?: "")
        FileUtils.listDirsAndFiles(ttsFolderPath)?.forEach {
            val isSilentSound = it.length() == 2160L
            if ((!it.name.startsWith(titleMd5)
                        && System.currentTimeMillis() - it.lastModified() > 600000)
                || isSilentSound
            ) {
                FileUtils.delete(it.absolutePath)
            }
        }
    }


    override fun pauseReadAloud(abandonFocus: Boolean) {
        super.pauseReadAloud(abandonFocus)
        kotlin.runCatching {
            playIndexJob?.cancel()
            exoPlayer.pause()
        }
    }

    override fun resumeReadAloud() {
        super.resumeReadAloud()
        kotlin.runCatching {
            if (pageChanged) {
                play()
            } else {
                exoPlayer.play()
                upPlayPos()
            }
        }
    }

    /**
     * 句内进度发布（页间分段 OFF 时的被动预测机制）：
     * 网络引擎每句音频时长由 ExoPlayer 精确提供，按“时长/字符数”为步长轮询
     * 播放进度，扫过页界即发布一次前进位置事件——真实音频信号，不是估算。
     * 流式播放（streamReadAloudAudio）拿不到总时长时，退化为上一句实测的
     * 单字符时长步长估计。显示是否翻页仍由 UI 侧跟随规则判定。
     */
    private fun upPlayPos() {
        playIndexJob?.cancel()
        val textChapter = textChapter ?: return
        playIndexJob = lifecycleScope.launch {
            val queueEntry = scriptQueue.firstOrNull()
            val charBase = queueEntry?.charStartInUnit ?: 0
            upTtsProgress(readAloudNumber + charBase + 1)
            val content = contentList.getOrNull(nowSpeak) ?: return@launch
            val speakTextLength = queueEntry?.textLength ?: content.length
            if (speakTextLength <= 0) {
                return@launch
            }
            // 页间分段 ON：朗读单元已在页边界裂开，句内不存在页界，无需句内预测
            if (pageSplit) {
                return@launch
            }
            val duration = exoPlayer.duration
            // 步长按媒体时长除以当前播放速度换算成墙钟时间（对齐 legado_NG），
            // V2 脚本引擎语速经播放变速生效，轮询必须随速度加快
            val playbackRate = exoPlayer.playbackParameters.speed.coerceAtLeast(0.1f)
            val charDurationMs = if (duration > 0) {
                (duration / speakTextLength).also {
                    lastCharDurationMs = it
                }
            } else {
                lastCharDurationMs
            }
            val sleep = (charDurationMs / playbackRate).toLong().coerceAtLeast(1L)
            val start = if (duration > 0) {
                (speakTextLength.toLong() * exoPlayer.currentPosition / duration).toInt()
            } else {
                (exoPlayer.currentPosition / sleep).toInt()
            }
            for (i in start..speakTextLength) {
                if (pageIndex + 1 < textChapter.pageSize
                    && readAloudNumber + charBase + i > textChapter.getReadLength(pageIndex + 1)
                ) {
                    // 扫过页界：只推进引擎私有页光标并发布位置，
                    // 显示翻页由 UI 侧跟随规则处理
                    pageIndex++
                    AppLog.putDebug("[朗读] HTTP过界发布 pos:${readAloudNumber + charBase + i}")
                    upTtsProgress(readAloudNumber + charBase + i)
                }
                delay(sleep)
            }
        }
    }

    /**
     * 更新朗读速度
     */
    override fun upSpeechRate(reset: Boolean) {
        if (ReadAloud.currentScriptTtsEngine() != null) {
            // V2 脚本引擎：语速经播放端变速即时生效，不重新合成
            applyPlaybackRate()
            if (!pause) {
                upPlayPos()
            }
            return
        }
        if (!isRun || contentList.isEmpty() || httpTtsSnapshot == null) {
            return
        }
        cancelHttpWork()
        exoPlayer.stop()
        speechRate = AppConfig.speechRatePlay + 5
        if (AppConfig.streamReadAloudAudio) {
            downloadAndPlayAudiosStream()
        } else {
            downloadAndPlayAudios()
        }
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        super.onPlaybackStateChanged(playbackState)
        when (playbackState) {
            Player.STATE_IDLE -> {
                // 空闲
            }

            Player.STATE_BUFFERING -> {
                // 缓冲中
            }

            Player.STATE_READY -> {
                // 准备好
                if (pause) return
                exoPlayer.play()
                upPlayPos()
            }

            Player.STATE_ENDED -> {
                // 结束
                playErrorNo = 0
                if (!advanceScriptQueue()) return
                exoPlayer.stop()
                exoPlayer.clearMediaItems()
            }
        }
    }

    override fun onTimelineChanged(timeline: Timeline, reason: Int) {
        when (reason) {
            Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED -> {
                if (!timeline.isEmpty && exoPlayer.playbackState == Player.STATE_IDLE) {
                    exoPlayer.prepare()
                }
            }

            else -> {}
        }
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) return
        if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
            playErrorNo = 0
        }
        if (!advanceScriptQueue()) return
        upPlayPos()
    }

    override fun onPlayerError(error: PlaybackException) {
        super.onPlayerError(error)
        AppLog.put("朗读错误\n${contentList.getOrNull(nowSpeak).orEmpty()}", error)
        deleteCurrentSpeakFile()
        playErrorNo++
        if (playErrorNo >= 5) {
            toastOnUi("朗读连续5次错误, 最后一次错误代码(${error.localizedMessage})")
            AppLog.put("朗读连续5次错误, 最后一次错误代码(${error.localizedMessage})", error)
            pauseReadAloud()
        } else {
            if (exoPlayer.hasNextMediaItem()) {
                exoPlayer.seekToNextMediaItem()
                exoPlayer.prepare()
            } else {
                exoPlayer.clearMediaItems()
                scriptQueue.clear()
                if (!updateNextPos()) return
            }
        }
    }

    private fun deleteCurrentSpeakFile() {
        if (AppConfig.streamReadAloudAudio) {
            return
        }
        val mediaItem = exoPlayer.currentMediaItem ?: return
        val filePath = mediaItem.localConfiguration!!.uri.path!!
        File(filePath).delete()
    }

    override fun aloudServicePendingIntent(actionStr: String): PendingIntent? {
        return servicePendingIntent<HttpReadAloudService>(actionStr)
    }

    class CustomLoadErrorHandlingPolicy : DefaultLoadErrorHandlingPolicy(0) {
        override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
            return C.TIME_UNSET
        }
    }

}
