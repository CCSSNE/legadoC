package io.legado.app.plugin.doubao

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookTtsVoiceBinding
import io.legado.app.help.book.BookHelp
import io.legado.app.help.config.AppConfig
import io.legado.app.help.tts.AiTtsStoryboardHelper
import io.legado.app.help.tts.BookTtsAutomationConfig
import io.legado.app.help.tts.BookTtsCastingCoordinator
import io.legado.app.help.tts.ChapterStoryboard
import io.legado.app.help.tts.ReadAloudDebugLog
import io.legado.app.help.tts.StoryboardSegmentType
import io.legado.app.help.tts.TtsSpeedPolicy
import io.legado.app.model.ReadBook
import io.legado.app.utils.getPrefBoolean
import splitties.init.appCtx
import io.legado.app.plugin.TtsVoiceInfo
import io.legado.app.service.BaseReadAloudService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/**
 * 豆包语音（内置）朗读服务：逐段调用 [DoubaoTtsClient] 合成 WAV，落盘后交给 ExoPlayer 播放。
 * 语速统一由播放端变速（合成 speech_rate 固定 1.0），与项目的脚本引擎管线一致。
 */
class DoubaoReadAloudService : BaseReadAloudService(), Player.Listener {

    private val exoPlayer: ExoPlayer by lazy { ExoPlayer.Builder(this).build() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var synthJob: Job? = null
    private var playIndexJob: Job? = null
    private var prefetchJob: Job? = null

    /** 预取窗口：按字符数而不是段数，长段不会把窗口撑爆。 */
    private val prefetchChars = 900

    /** 磁盘缓存上限（文件数），超出按修改时间清理最旧的。 */
    private val speakCacheLimit = 240

    /** 当前章节分镜（AI 角色识别结果），按书+章节缓存一次，用于逐段换音色。 */
    private var storyboard: ChapterStoryboard? = null
    private var storyboardKey: String? = null

    private var storyboardPrefetchJob: Job? = null

    /** 分镜预取：读到本章时顺带把后几章的分析做掉，已缓存的跳过。 */
    private val storyboardPrefetchChapters = 3

    /**
     * 解析本段该用的音色：
     * 1. 加载本章分镜（AI 识别的角色 + 说话段落）；
     * 2. 用当前朗读文本去匹配对白/心声 segment；
     * 3. 命中就按 [BookTtsCastingCoordinator.resolveSpeaker] 取角色绑定音色；
     * 4. 未命中/未配置分镜 → 旁白音色（[DoubaoVoices.resolveVoice]）。
     */
    @Volatile
    private var missLoggedCount: Int = 0

    @Volatile
    private var lastSegmentEndedAt: Long = 0L

    @Volatile
    private var lastLoggedSpeaker: String? = null

    private val loggedCastKeys = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    /** 朗读链路探针：段切换/缓存命中/合成耗时/段间空档，统一带 ms 时间戳写朗读日志。 */
    private fun probe(message: String) {
        // 探针已关闭：复测时改回 ReadAloudDebugLog.add("探针", message)
    }

    /** 当前段落的播放片段（旁白/对白交替）与正在播放的下标。 */
    @Volatile
    private var currentChunks: List<VoiceChunk> = emptyList()

    @Volatile
    private var currentChunkIndex: Int = 0

    @Volatile
    private var lastRoleName: String? = null

    @Volatile
    private var lastVoiceId: String? = null

    override fun currentSpeakerLabel(): String? {
        val voiceId = lastVoiceId ?: return null
        val voiceName = DoubaoVoices.byId(voiceId)?.name
        val role = lastRoleName?.takeIf { it.isNotBlank() }
        return when {
            role != null && voiceName != null -> "$role · $voiceName"
            role != null -> role
            else -> voiceName
        }
    }

    override fun onSpeakerLabelClick() {
        runCatching {
            startActivity(
                android.content.Intent(
                    this,
                    io.legado.app.ui.book.read.ReadBookActivity::class.java
                ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    /** 一个朗读单元按分镜切出来的播放片段：旁白与对白交替。 */
    private data class VoiceChunk(val text: String, val voiceId: String, val roleLabel: String?)

    /**
     * 把当前段落切成片段：
     * 例如「今天天气很好，李雷说"我们出去玩吧"」→ [旁白:"今天天气很好，李雷说"]["对白(李雷):"我们出去玩吧"]。
     * 没有分镜、开关关闭、或分镜区间对不上时，退化为整段一个片段。
     */
    private suspend fun buildChunks(text: String): List<VoiceChunk> {
        val narrator = DoubaoVoices.resolveVoice()
        val single = listOf(VoiceChunk(text, narrator, null))
        if (!appCtx.getPrefBoolean(io.legado.app.constant.PreferKey.readAloudRoleMark, true)) return single
        val board = ensureStoryboard() ?: return single
        val probe = text.trim().replace(Regex("\\s+"), "")
        if (probe.length < 4) return single
        val paragraphIndex = findParagraphIndex(board, probe) ?: return single
        val segments = board.allSegments()
            .filter { it.paragraphIndex == paragraphIndex }
            .sortedBy { it.start }
        if (segments.isEmpty()) return single
        // 单条分镜就是整段对白时，整段都用该角色音色（之前这里直接返回旁白，是 bug）
        if (segments.size == 1) {
            val only = segments.first()
            if (only.type == StoryboardSegmentType.NARRATION) return single
            val voice = voiceForSegment(only) ?: return single
            return listOf(VoiceChunk(text, voice, only.speakerName))
        }

        val chunks = mutableListOf<VoiceChunk>()
        var cursor = 0
        for (segment in segments) {
            val start = segment.start.coerceIn(0, text.length)
            val end = segment.end.coerceIn(start, text.length)
            if (start > cursor) {
                chunks += VoiceChunk(text.substring(cursor, start), narrator, null)
            }
            if (end > start) {
                val slice = text.substring(start, end)
                if (segment.type == StoryboardSegmentType.NARRATION) {
                    chunks += VoiceChunk(slice, narrator, null)
                } else {
                    val voice = voiceForSegment(segment) ?: narrator
                    chunks += VoiceChunk(slice, voice, segment.speakerName)
                }
            }
            cursor = maxOf(cursor, end)
        }
        if (cursor < text.length) {
            chunks += VoiceChunk(text.substring(cursor), narrator, null)
        }
        val merged = chunks.filter { it.text.isNotBlank() }
        // 区间明显不可靠时（切出来不足原文一半）直接退回整段，避免读漏字
        if (merged.sumOf { it.text.length } < text.length / 2) return single
        return mergeShortChunks(merged)
    }

    /**
     * 把纯标点/极短片段并入相邻片段：服务端对「。」「？”」这类文本会返回 TTSInvalidText，
     * 与其合成失败再跳过，不如在切片阶段就并掉，保证每个片段都是可合成的完整句子。
     */
    private fun mergeShortChunks(chunks: List<VoiceChunk>): List<VoiceChunk> {
        if (chunks.size <= 1) return chunks
        fun VoiceChunk.onlyPunctuation(): Boolean {
            val trimmed = text.trim()
            return trimmed.isNotEmpty() && trimmed.all { !it.isLetterOrDigit() }
        }
        val out = mutableListOf<VoiceChunk>()
        for (chunk in chunks) {
            val prev = out.lastOrNull()
            val tooShort = chunk.text.trim().length <= 2
            if (prev != null && (chunk.onlyPunctuation() || tooShort)) {
                out[out.lastIndex] = prev.copy(text = prev.text + chunk.text)
            } else {
                out += chunk
            }
        }
        // 首个片段仍是纯标点时并入第二段
        if (out.size > 1 && out.first().onlyPunctuation()) {
            val head = out.removeAt(0)
            out[0] = out[0].copy(text = head.text + out[0].text)
        }
        return out
    }

    /** 取某个对白分镜对应的音色（走 AI 选角绑定）；取不到返回 null。 */
    private fun voiceForSegment(segment: io.legado.app.help.tts.StoryboardSegment): String? {
        val book = ReadBook.book ?: return null
        val workKey = BookTtsAutomationConfig.workKeyOf(book.name, book.author)
        val dao = appDb.bookRoleDao

        // 1) 分镜已带角色 id：直接按绑定取音色（不走 resolveSpeaker 的性别兜底，避免被旁白盖掉）
        val directBinding = when {
            segment.characterId > 0L -> runCatching {
                dao.getBinding(workKey, BookTtsVoiceBinding.TargetType.CHARACTER, segment.characterId)
            }.getOrNull()
            segment.castRoleId > 0L -> runCatching {
                dao.getBinding(workKey, BookTtsVoiceBinding.TargetType.CAST_ROLE, segment.castRoleId)
            }.getOrNull()
            else -> null
        }
        bindingVoice(directBinding)?.let { voice ->
            logCastPick("id", segment, voice)
            return voice
        }

        // 2) 按角色名反查绑定：分镜未 relink（characterId=0）时的主要路径
        val name = segment.speakerName?.trim().orEmpty()
        if (name.isNotEmpty()) {
            val role = runCatching { dao.getAllRoles(workKey) }.getOrNull().orEmpty()
                .firstOrNull { it.name.trim() == name }
            if (role != null) {
                val voice = bindingVoice(
                    runCatching {
                        dao.getBinding(workKey, BookTtsVoiceBinding.TargetType.CHARACTER, role.roleId)
                    }.getOrNull()
                )
                if (voice != null) {
                    logCastPick("角色名:" + name, segment, voice)
                    return voice
                }
            }
            val castRole = runCatching { dao.getCastRoles(workKey) }.getOrNull().orEmpty()
                .firstOrNull { !it.ignored && it.name.trim() == name }
            if (castRole != null) {
                val voice = bindingVoice(
                    runCatching {
                        dao.getBinding(workKey, BookTtsVoiceBinding.TargetType.CAST_ROLE, castRole.castRoleId)
                    }.getOrNull()
                )
                if (voice != null) {
                    logCastPick("临时角色:" + name, segment, voice)
                    return voice
                }
            }
        }

        // 3) 最后才走官方路由（含性别兜底 / 引擎默认）
        val fallbackInfo = TtsVoiceInfo(
            id = DoubaoVoices.globalVoiceId(DoubaoVoices.resolveVoice()),
            name = "",
            gender = "unknown",
            locale = "zh-CN"
        )
        val speaker = runCatching {
            BookTtsCastingCoordinator.resolveSpeaker(workKey, segment, fallbackInfo)
        }.getOrNull()
        if (speaker == null) {
            logCastMiss("resolveSpeaker 异常", segment)
            return null
        }
        val voiceId = DoubaoVoices.parseGlobal(speaker.id) ?: speaker.id
        val ok = DoubaoVoices.byId(voiceId) != null
        logCastMiss(
            "段 char=" + segment.characterId + " cast=" + segment.castRoleId +
                " 分镜名=" + segment.speakerName + " 兜底解析=" + speaker.id.take(40) + " 表命中=" + ok,
            segment
        )
        return voiceId.takeIf { ok }
    }

    /** 绑定 → 音色 id（兼容复合 id 与裸 id，且必须在内置音色表里）。 */
    private fun bindingVoice(binding: BookTtsVoiceBinding?): String? {
        val raw = binding?.speakerId?.takeIf { it.isNotBlank() } ?: return null
        val voiceId = DoubaoVoices.parseGlobal(raw) ?: raw
        return voiceId.takeIf { DoubaoVoices.byId(it) != null }
    }

    private fun logCastPick(source: String, segment: io.legado.app.help.tts.StoryboardSegment, voice: String) {
        val key = source + "|" + (segment.speakerName ?: "") + "|" + voice
        if (!loggedCastKeys.add(key)) return
        ReadAloudDebugLog.add(
            "选角",
            "命中(" + source + ") " + (segment.speakerName ?: "?") + " → " + DoubaoVoices.describe(voice)
        )
    }

    private var castLogCount = 0

    /** 选角诊断：只记前 10 条，避免刷屏；用于定位「角色名对但音色回退旁白」。 */
    private fun logCastMiss(message: String, segment: io.legado.app.help.tts.StoryboardSegment) {
        if (castLogCount >= 10) return
        castLogCount++
        ReadAloudDebugLog.add("选角", message)
    }

    private suspend fun resolveVoiceFor(text: String): String {
        val fallback = DoubaoVoices.resolveVoice()
        // 朗读设置里的「角色标注」开关：关闭时统一旁白，不做逐段角色切换。
        if (!appCtx.getPrefBoolean(io.legado.app.constant.PreferKey.readAloudRoleMark, true)) {
            lastRoleName = null
            lastVoiceId = fallback
            refreshFloatingSpeakerLabel()
            ReadAloudDebugLog.add("音色", "角色标注开关已关闭，整章用旁白音色")
            return fallback
        }
        val board = ensureStoryboard() ?: return fallback
        val probe = text.trim().replace(Regex("\\s+"), "")
        if (probe.length < 6) return fallback
        val spoken = board.allSegments().filter {
            it.type == StoryboardSegmentType.DIALOGUE || it.type == StoryboardSegmentType.THOUGHT
        }
        // 主匹配：把朗读单元对到「章节段落索引」，再取该段上的对白/心声分镜。
        // 比拿分镜片段文本做包含匹配稳得多（片段往往只是整段里的一句对白）。
        val paragraphIndex = findParagraphIndex(board, probe)
        val byParagraph = paragraphIndex?.let { index ->
            if (index in board.paragraphs.indices) {
                board.allSegments().firstOrNull {
                    it.paragraphIndex == index && it.type != StoryboardSegmentType.NARRATION
                }
            } else null
        }
        val hit = byParagraph ?: spoken.firstOrNull { segment ->
            val sample = segment.text.trim().replace(Regex("\\s+"), "")
            sample.length >= 4 &&
                (probe.contains(sample.take(10)) || sample.contains(probe.take(10)))
        } ?: run {
            if (missLoggedCount < 5) {
                missLoggedCount++
                ReadAloudDebugLog.add(
                    "分镜", "本段未匹配到对白角色（对白段共 ${spoken.size} 个）：${probe.take(16)}"
                )
            }
            return fallback
        }
        val book = ReadBook.book ?: return fallback
        val workKey = BookTtsAutomationConfig.workKeyOf(book.name, book.author)
        val fallbackInfo = TtsVoiceInfo(
            id = DoubaoVoices.globalVoiceId(fallback),
            name = DoubaoVoices.byId(fallback)?.name ?: fallback,
            gender = DoubaoVoices.byId(fallback)?.gender ?: "unknown",
            locale = "zh-CN"
        )
        val speaker = runCatching {
            BookTtsCastingCoordinator.resolveSpeaker(workKey, hit, fallbackInfo)
        }.getOrNull() ?: return fallback
        val voiceId = DoubaoVoices.parseGlobal(speaker.id) ?: speaker.id
        return if (DoubaoVoices.byId(voiceId) != null) {
            lastRoleName = hit.speakerName
            lastVoiceId = voiceId
            refreshFloatingSpeakerLabel()
            voiceId
        } else {
            fallback
        }
    }

    /**
     * 分镜预取：本章开始播放后，后台把后续 [storyboardPrefetchChapters] 章的分镜做好。
     * 已缓存（loadCached 命中）直接跳过；串行执行，避免并发打爆模型接口。
     */
    private fun scheduleStoryboardPrefetch() {
        storyboardPrefetchJob?.cancel()
        storyboardPrefetchJob = scope.launch {
            val book = ReadBook.book ?: return@launch
            val currentIndex = textChapter?.chapter?.index ?: return@launch
            for (offset in 1..storyboardPrefetchChapters) {
                if (!isActive) return@launch
                val targetIndex = currentIndex + offset
                val chapter = withContext(Dispatchers.IO) {
                    runCatching { appDb.bookChapterDao.getChapter(book.bookUrl, targetIndex) }.getOrNull()
                } ?: break
                // 与 AiStoryboardBatchAnalyzer.analyzeChapter 完全一致：离线构建 TextChapter，
                // 正文取 getNeedReadAloud(0,false,0)，否则 contentHash 对不上，缓存写进去也读不回来。
                val content = withContext(Dispatchers.IO) {
                    runCatching {
                        ReadBook.loadTextChapterForReadAloud(targetIndex, this@DoubaoReadAloudService.scope)
                            ?.getNeedReadAloud(0, false, 0)
                    }.getOrNull()
                }
                if (content.isNullOrBlank()) {
                    io.legado.app.help.tts.CastingDebugLog.add(
                        "预取", "第 $targetIndex 章内容为空，停止预取"
                    )
                    break
                }
                val cached = runCatching {
                    val raw = AiTtsStoryboardHelper.loadCached(book, chapter.index, chapter.title, content)
                        ?: return@runCatching null
                    // 关键：缓存里的分镜只有角色名，没有角色池 id。必须走 syncCastRoles
                    // 把名字 relink 成 characterId/castRoleId，否则 resolveSpeaker 查不到
                    // 角色绑定，只能回退旁白（表现为「角色名对、音色是旁白」）。
                    BookTtsCastingCoordinator.syncCastRoles(
                        BookTtsAutomationConfig.workKeyOf(book.name, book.author),
                        chapter.index,
                        raw
                    )
                }.getOrNull()
                if (cached != null) {
                    io.legado.app.help.tts.CastingDebugLog.add(
                        "预取", "第 $targetIndex 章已有分镜（${cached.allSegments().size} 段），跳过"
                    )
                    continue
                }
                io.legado.app.help.tts.CastingDebugLog.add("预取", "开始分析第 $targetIndex 章…")
                runCatching {
                    AiTtsStoryboardHelper.getOrGenerate(book, chapter.index, chapter.title, content)
                }.onSuccess { board ->
                    io.legado.app.help.tts.CastingDebugLog.add(
                        "预取", "第 $targetIndex 章完成：${board.allSegments().size} 段"
                    )
                }.onFailure { error ->
                    io.legado.app.help.tts.CastingDebugLog.add(
                        "预取", "第 $targetIndex 章失败：${error.localizedMessage}"
                    )
                }
            }
        }
    }

    /** 朗读单元 → 章节段落索引：先去空白全等，再前缀包含，最后退化到前 12 字包含。 */
    private fun findParagraphIndex(board: ChapterStoryboard, probe: String): Int? {
        if (probe.isEmpty()) return null
        val paragraphs = board.paragraphs
        if (paragraphs.isEmpty()) return null
        paragraphs.forEachIndexed { index, paragraph ->
            if (paragraph.replace(Regex("\\s+"), "") == probe) return index
        }
        val head = probe.take(12)
        paragraphs.forEachIndexed { index, paragraph ->
            val text = paragraph.replace(Regex("\\s+"), "")
            if (text.isEmpty()) return@forEachIndexed
            if (text.startsWith(head) || probe.startsWith(text.take(12))) return index
        }
        return paragraphs.indexOfFirst { it.replace(Regex("\\s+"), "").contains(probe.take(16)) }
            .takeIf { it >= 0 }
    }

    private suspend fun ensureStoryboard(): ChapterStoryboard? {
        val book = ReadBook.book ?: return null
        val chapter = textChapter?.chapter ?: return null
        val key = "${book.bookUrl}#${chapter.index}#${chapter.title}"
        if (storyboardKey == key) return storyboard
        storyboardKey = key
        storyboard = runCatching {
            // 必须与生成侧同源：AiStoryboardBatchAnalyzer/HttpReadAloudService 都用
            // getNeedReadAloud(0,false,0) 计算 contentHash，用别的文本拼出来的 hash 永远不匹配。
            // 与生成侧（AiStoryboardBatchAnalyzer.analyzeChapter）完全同源：
            // 离线构建 TextChapter 后取净化过的朗读文本；用服务里的分页 textChapter
            // 会因分页参数不同算出不同的 contentHash，导致缓存永远命中不了。
            val content = withContext(Dispatchers.IO) {
                runCatching {
                    ReadBook.loadTextChapterForReadAloud(chapter.index, scope)
                        ?.getNeedReadAloud(0, false, 0)
                }.getOrNull()
            }?.takeIf { it.isNotBlank() } ?: return@runCatching null
            AiTtsStoryboardHelper.loadCached(book, chapter.index, chapter.title, content)
        }.getOrNull()
        val message = storyboard?.let { "已加载 ${it.allSegments().size} 段（角色切换生效）" }
            ?: "本章无分镜缓存（统一旁白音色；请先做 AI 分镜分析）"
        ReadAloudDebugLog.add("分镜", message)
        io.legado.app.help.tts.CastingDebugLog.add("播放", message)
        return storyboard
    }

    private val speakDir: File
        get() = File(cacheDir, "doubaoSpeak").apply { mkdirs() }

    override fun onCreate() {
        super.onCreate()
        runCatching { exoPlayer.addListener(this) }
    }

    override fun onDestroy() {
        synthJob?.cancel()
        playIndexJob?.cancel()
        prefetchJob?.cancel()
        scope.cancel()
        runCatching { exoPlayer.removeListener(this) }
        runCatching { exoPlayer.release() }
        super.onDestroy()
    }

    override fun play() {
        exoPlayer.stop()
        applyPlaybackRate()
        if (!requestFocus()) return
        if (contentList.isEmpty()) {
            AppLog.putDebug("豆包 朗读列表为空")
            nextChapter()
            return
        }
        super.play()
        upReadAloudLoading(true)
        playCurrentParagraph()
        scheduleStoryboardPrefetch()
    }

    override fun playStop() {
        synthJob?.cancel()
        playIndexJob?.cancel()
        prefetchJob?.cancel()
        storyboardPrefetchJob?.cancel()
        exoPlayer.stop()
    }

    override fun pauseReadAloud(abandonFocus: Boolean) {
        synthJob?.cancel()
        exoPlayer.pause()
        super.pauseReadAloud(abandonFocus)
    }

    override fun resumeReadAloud() {
        super.resumeReadAloud()
        if (!exoPlayer.isPlaying) exoPlayer.play()
    }

    override fun upSpeechRate(reset: Boolean) {
        applyPlaybackRate()
        if (!BaseReadAloudService.pause) {
            contentList.getOrNull(nowSpeak)?.let { trackProgress(it) }
        }
    }

    override fun refreshTtsRoute() {
        DoubaoVoices.resolveVoice()
        playCurrentParagraph()
    }

    override fun aloudServicePendingIntent(actionStr: String) = null

    private fun applyPlaybackRate() {
        exoPlayer.setPlaybackSpeed(TtsSpeedPolicy.playbackRate(AppConfig.speechRatePlay))
    }

    /**
     * 播放当前段：命中磁盘缓存则立刻起播（段落间无缝），未命中才合成。
     * 起播后异步预取后续段落，把「下一段」的合成时间挪到本段播放期间。
     */
    private fun playCurrentParagraph() {
        synthJob?.cancel()
        val text = contentList.getOrNull(nowSpeak)
        if (text.isNullOrBlank()) {
            nextChapter()
            return
        }
        upReadAloudLoading(true)
        synthJob = scope.launch {
            val chunks = buildChunks(text).filter { it.text.isNotBlank() }
            val hits = chunks.count { cachedAudioFile(it.text, it.voiceId) != null }
            probe("段开始：nowSpeak=" + nowSpeak + " 片段=" + chunks.size + " 缓存命中=" + hits + " 需合成=" + (chunks.size - hits))
            val synthStart = System.currentTimeMillis()
            val files = mutableListOf<File>()
            var failure: Throwable? = null
            for (chunk in chunks) {
                val file = runCatching { synthesizeToFile(chunk.text, chunk.voiceId) }
                    .onFailure { failure = it }
                    .getOrNull()
                if (file == null) {
                    // 极短片段（如单独一个引号/句号）会被服务端判 TTSInvalidText，
                    // 跳过它继续读后面的，不要因为一个标点中断整段朗读。
                    if (chunk.text.trim().length <= 4) continue
                    break
                }
                files += file
            }
            if (files.isEmpty()) {
                val voiceId = chunks.firstOrNull()?.voiceId ?: DoubaoVoices.resolveVoice()
                AppLog.put(
                    "豆包合成失败：${DoubaoVoices.describe(voiceId)} ${failure?.localizedMessage ?: ""}",
                    failure,
                    true
                )
                withContext(Dispatchers.Main) {
                    upReadAloudLoading(false)
                    pauseReadAloud()
                }
                return@launch
            }
            probe("合成完成：耗时=" + (System.currentTimeMillis() - synthStart) + "ms 片段=" + files.size)
            val playChunks = chunks.take(files.size)
            withContext(Dispatchers.Main) {
                currentChunks = playChunks
                currentChunkIndex = 0
                exoPlayer.clearMediaItems()
                files.forEach { file ->
                    exoPlayer.addMediaItem(MediaItem.fromUri(android.net.Uri.fromFile(file)))
                }
                exoPlayer.prepare()
                exoPlayer.play()
                upReadAloudLoading(false)
                updateSpeakerLabelFromChunk()
                trackProgress(text)
            }
            schedulePrefetch(playChunks.firstOrNull()?.voiceId ?: DoubaoVoices.resolveVoice())
        }
    }

    private fun updateSpeakerLabelFromChunk() {
        val chunk = currentChunks.getOrNull(currentChunkIndex) ?: return
        lastRoleName = chunk.roleLabel
        lastVoiceId = chunk.voiceId
        refreshFloatingSpeakerLabel()
        val label = (chunk.roleLabel ?: "旁白") + " → " + DoubaoVoices.describe(chunk.voiceId)
        if (label != lastLoggedSpeaker) {
            lastLoggedSpeaker = label
            ReadAloudDebugLog.add("音色", label)
        }
    }

    private fun startPlayback(file: File, text: String) {
        exoPlayer.setMediaItem(MediaItem.fromUri(android.net.Uri.fromFile(file)))
        exoPlayer.prepare()
        exoPlayer.play()
        upReadAloudLoading(false)
        val gap = if (lastSegmentEndedAt > 0) System.currentTimeMillis() - lastSegmentEndedAt else -1L
        probe("起播：片段=" + currentChunks.size + " 段间空档=" + (if (gap >= 0) gap.toString() + "ms" else "首段"))
        trackProgress(text)
    }

    /**
     * 预取后续段落：串行合成（一条 WS 通道，避免同时开多路被限流），
     * 累计到 [prefetchChars] 字符或本章结束为止；已缓存段落直接跳过。
     */
    private fun schedulePrefetch(voiceId: String) {
        // 已有预取在跑就不打断它：频繁取消会让每段都从头再来，反而更慢
        if (prefetchJob?.isActive == true) return
        prefetchJob = scope.launch {
            var index = nowSpeak + 1
            var fetched = 0
            // 预取段数取「朗读设置 → 实时预取段数」
            val maxSegments = runCatching { AppConfig.ttsCachePrefetchCount }.getOrDefault(5)
            probe("预取启动：上限=" + maxSegments + " 段（取设置 ttsCachePrefetchCount）")
            var queued = 0
            while (index < contentList.size && queued < maxSegments) {
                val text = contentList[index]
                if (text.isNotBlank()) {
                    // 必须与播放侧同构：按分镜切片、逐片合成。
                    // 之前按「整段文本 + 单音色」写缓存，而播放按「片段文本 + 片段音色」查，
                    // key 永远对不上，预取等于白做（表现为每段之间仍要现合成、有停顿）。
                    val chunks = runCatching { buildChunks(text) }.getOrNull()
                        ?.filter { it.text.isNotBlank() }.orEmpty()
                    for (chunk in chunks) {
                        if (!isActive) return@launch
                        if (cachedAudioFile(chunk.text, chunk.voiceId) != null) continue
                        runCatching { synthesizeToFile(chunk.text, chunk.voiceId) }
                            .onSuccess { fetched++ }
                            .onFailure { error ->
                                if (error !is kotlinx.coroutines.CancellationException) {
                                    ReadAloudDebugLog.add(
                                        "预取", "失败（" + index + "）：" + error.localizedMessage
                                    )
                                }
                            }
                    }
                    queued++
                }
                index++
            }
            if (fetched > 0) {
                ReadAloudDebugLog.add("预取", "已预合成 " + fetched + " 个片段")
            }
            trimCache()
        }
    }
    private suspend fun synthesizeToFile(text: String, voiceId: String): File {
        // 关键：先查缓存。改成片段队列时漏了这一步，导致预取写下的音频从未被读，
        // 每次播放都重新走网络合成（表现为段间固定空档、预取形同虚设）。
        cachedAudioFile(text, voiceId)?.let { return it }
        // 单段合成超时取「朗读设置 → 单段合成超时」，与缓存体系保持同一口径
        val timeoutMs = runCatching { AppConfig.ttsCacheSegmentTimeoutSeconds * 1000L }
            .getOrDefault(90_000L)
        val audio = withContext(Dispatchers.IO) {
            DoubaoTtsClient.synthesize(text = text, voiceId = voiceId, timeoutMs = timeoutMs)
        }
        return writeAudioFile(text, voiceId, audio)
    }

    /** 缓存 key：音色是否参与由「朗读设置 → 命中维度：音色」决定。 */
    private fun cacheKey(text: String, voiceId: String): String {
        val voicePart = if (AppConfig.ttsCacheKeyVoice) voiceId else ""
        return md5("$voicePart|$text")
    }

    private fun cachedAudioFile(text: String, voiceId: String): File? {
        val file = File(speakDir, "${cacheKey(text, voiceId)}.wav")
        return file.takeIf { it.exists() && it.length() > 1024 }
    }

    /** 缓存超量时按修改时间清理最旧文件，避免长书把缓存吃满。 */
    private fun trimCache() {
        runCatching {
            val files = speakDir.listFiles()?.sortedBy { it.lastModified() } ?: return
            if (files.size <= speakCacheLimit) return
            files.take(files.size - speakCacheLimit).forEach { it.delete() }
        }
    }

    private fun writeAudioFile(text: String, voiceId: String, audio: DoubaoTtsClient.Result): File {
        val key = cacheKey(text, voiceId)
        val file = File(speakDir, "$key.wav")
        if (!file.exists() || file.length() != audio.wav.size.toLong()) {
            file.writeBytes(audio.wav)
        }
        return file
    }

    private fun trackProgress(text: String) {
        playIndexJob?.cancel()
        playIndexJob = scope.launch(Dispatchers.Main) {
            var lastCharDurationMs = 100L
            while (true) {
                val duration = exoPlayer.duration
                val position = exoPlayer.currentPosition
                val chunk = currentChunks.getOrNull(currentChunkIndex)
                val chunkLength = chunk?.text?.length?.coerceAtLeast(1) ?: text.length.coerceAtLeast(1)
                if (duration > 0) lastCharDurationMs = duration / chunkLength
                val chunkStart = currentChunks.take(currentChunkIndex).sumOf { it.text.length }
                val offset = if (duration > 0) {
                    (chunkLength.toLong() * position / duration).toInt()
                } else {
                    (position / lastCharDurationMs.coerceAtLeast(1L)).toInt()
                }
                upTtsProgress(readAloudNumber + chunkStart + offset + 1)
                if (exoPlayer.playbackState == Player.STATE_ENDED) break
                delay(lastCharDurationMs.coerceAtLeast(20L))
            }
        }
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        currentChunkIndex = exoPlayer.currentMediaItemIndex.coerceAtLeast(0)
        updateSpeakerLabelFromChunk()
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        when (playbackState) {
            Player.STATE_ENDED -> {
                lastSegmentEndedAt = System.currentTimeMillis()
                probe("段结束：nowSpeak=" + nowSpeak + " 片段=" + currentChunks.size)
                advanceParagraph()
            }
            Player.STATE_READY -> upReadAloudLoading(false)
            else -> Unit
        }
    }

    override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
        AppLog.put("豆包播放失败\n${error.localizedMessage}", error, true)
        pauseReadAloud()
    }

    private fun advanceParagraph() {
        val content = contentList.getOrNull(nowSpeak) ?: run {
            nextChapter()
            return
        }
        readAloudNumber += content.length + 1 - paragraphStartPos
        paragraphStartPos = 0
        if (nowSpeak < contentList.lastIndex) {
            nowSpeak++
            playCurrentParagraph()
        } else {
            nextChapter()
        }
    }

    private fun md5(value: String): String =
        MessageDigest.getInstance("MD5").digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }
}
