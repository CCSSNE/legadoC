package io.legado.app.help.tts

import android.speech.tts.TextToSpeech
import io.legado.app.R
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.isAudio
import io.legado.app.help.book.isVideo
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.dialogs.SelectItem
import io.legado.app.model.ReadAloud
import io.legado.app.plugin.ReadAloudEngines
import io.legado.app.utils.FileUtils
import io.legado.app.utils.GSON
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.StringUtils
import io.legado.app.utils.fromJsonObject
import kotlinx.coroutines.suspendCancellableCoroutine
import splitties.init.appCtx
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume

/**
 * TTS 音频缓存唯一数据所有者。
 *
 * 存储位置：<book_cache>/<书目录>/tts_cache/<章节正文文件主名>/<单元hash>.wav，
 * 与评论快照（reviews/）同为书缓存目录内的附属产物：按书删除/全局清除由整目录
 * 删除天然覆盖；按章删除由 [deleteChapter] 挂在 BookHelp.delChapterCache。
 *
 * 实时朗读与批量缓存共用本 Store，同 key 原子写入 + 先查 [has]，重叠只浪费合成、
 * 不产生状态冲突。缓存内容不进 cache_manifest.json，文件即事实源。
 */
object TtsCacheStore {

    const val DIR_NAME = "tts_cache"

    /** key 方案版本：key 组成变化时递增，旧文件自然失配等待重合成。 */
    private const val KEY_VERSION = "ttsv1"

    const val DEFAULT_ENGINE_KEY = "default"
    const val DEFAULT_VOICE_KEY = "default"

    /** 跟随系统语速时应用感知不到系统 TTS 内部语速，语速维度退化为常量标记。 */
    const val FOLLOW_SYS_SPEED_KEY = "followSys"

    /**
     * 朗读单元缓存 key。
     * 必选维度：引擎 + 章节正文文件主名 + 送入引擎的最终文本；
     * 可选维度：语速 / 音色（[AppConfig.ttsCacheKeySpeed] / [AppConfig.ttsCacheKeyVoice]）。
     * 维度值必须与朗读引擎实际生效参数同源（[TtsCacheParams]），否则批量写入与
     * 朗读播放无法互相命中。
     */
    data class UnitKey(
        val engineKey: String,
        val chapterStem: String,
        val text: String,
        val speedKey: String?,
        val voiceKey: String?,
    ) {
        fun hashInput(): String = listOf(
            KEY_VERSION,
            engineKey,
            chapterStem,
            text,
            speedKey.orEmpty(),
            voiceKey.orEmpty(),
        ).joinToString("\n")
    }

    fun ttsCacheDir(book: Book): File = File(BookHelp.getCacheDir(book), DIR_NAME)

    /** 章节子目录名与正文缓存文件主名（c-<md5-16>）同源，保证与正文缓存同章节身份。 */
    fun chapterStem(chapter: BookChapter): String =
        chapter.contentCacheFileName("nb").removeSuffix(".nb")

    fun chapterCacheDir(book: Book, chapter: BookChapter): File =
        File(ttsCacheDir(book), chapterStem(chapter))

    fun buildUnitKey(
        book: Book,
        chapter: BookChapter,
        text: String,
        voiceName: String?,
    ): UnitKey = UnitKey(
        engineKey = TtsCacheParams.engineKey(book),
        chapterStem = chapterStem(chapter),
        text = text,
        speedKey = if (AppConfig.ttsCacheKeySpeed) TtsCacheParams.speedKey() else null,
        voiceKey = if (AppConfig.ttsCacheKeyVoice) TtsCacheParams.voiceKey(voiceName) else null,
    )

    fun unitFile(book: Book, key: UnitKey): File =
        File(File(ttsCacheDir(book), key.chapterStem), MD5Utils.md5Encode16(key.hashInput()) + audioSuffix(key))

    /**
     * 产物后缀按引擎类型区分：在线类引擎（HTTP / V2 脚本）返回的多为 mp3，
     * 系统引擎 synthesizeToFile 产物与内置插件引擎（百度）PCM 封装产物为 wav。
     * 播放端按内容嗅探不受后缀影响，这里只为文件管理器中的可读性诚实。
     */
    private fun audioSuffix(key: UnitKey): String = when {
        StringUtils.isNumeric(key.engineKey) -> ".mp3"
        key.engineKey.startsWith("script:") -> ".mp3"
        else -> ".wav"
    }

    fun has(book: Book, key: UnitKey): Boolean {
        val file = unitFile(book, key)
        return file.isFile && file.length() > 0L
    }

    /**
     * 原子提交：合成先写临时文件，再落为正式缓存名。调用方负责临时文件清理。
     * 目标已存在时先删后换（同 key 并发合成后写者胜，产物等价）。
     */
    fun commit(tempFile: File, targetFile: File) {
        targetFile.parentFile?.mkdirs()
        if (targetFile.exists()) targetFile.delete()
        if (!tempFile.renameTo(targetFile)) {
            tempFile.copyTo(targetFile, overwrite = true)
            tempFile.delete()
        }
        check(targetFile.isFile && targetFile.length() > 0L) {
            "tts cache commit failed: ${targetFile.absolutePath}"
        }
    }

    /** 删除一个章节的全部 TTS 缓存（按章节子目录整体删除）。 */
    fun deleteChapter(book: Book, chapter: BookChapter) {
        val dir = chapterCacheDir(book, chapter)
        if (dir.isDirectory) {
            FileUtils.delete(dir.absolutePath)
        }
    }

    /** 单声道 PCM16 wav 的解析结果：采样率 + 位于 PCM 数据起点的流（调用方负责关闭）。 */
    class MonoPcm16Wav(val sampleRate: Int, val pcmStream: InputStream)

    /**
     * 打开缓存 wav 的 PCM 数据流（仅接受 RIFF / PCM16 / 单声道）。
     * 缓存产物格式由合成端保证：系统 TTS synthesizeToFile 与内置插件引擎合成器
     * 均产出 PCM16 wav。格式不符直接抛错，由调用方明示原因并走实时合成恢复，
     * 不做转码静默兜底。
     */
    fun openMonoPcm16Stream(file: File): MonoPcm16Wav {
        val input = DataInputStream(BufferedInputStream(FileInputStream(file)))
        try {
            val riff = ByteArray(12)
            input.readFully(riff)
            check(
                String(riff, 0, 4, Charsets.US_ASCII) == "RIFF" &&
                        String(riff, 8, 4, Charsets.US_ASCII) == "WAVE"
            ) { "not a RIFF/WAVE file: ${file.name}" }
            var format = -1
            var channels = -1
            var sampleRate = -1
            var bitsPerSample = -1
            while (true) {
                val header = ByteArray(8)
                input.readFully(header)
                val chunkId = String(header, 0, 4, Charsets.US_ASCII)
                val chunkSize = readLittleInt(header, 4)
                when (chunkId) {
                    "fmt " -> {
                        val fmt = ByteArray(chunkSize)
                        input.readFully(fmt)
                        format = readLittleShort(fmt, 0)
                        channels = readLittleShort(fmt, 2)
                        sampleRate = readLittleInt(fmt, 4)
                        bitsPerSample = readLittleShort(fmt, 14)
                    }
                    "data" -> {
                        check(format == 1) { "unsupported wav audio format: $format" }
                        check(bitsPerSample == 16) { "unsupported wav bits: $bitsPerSample" }
                        check(channels == 1) { "unsupported wav channels: $channels" }
                        check(sampleRate > 0) { "invalid wav sample rate: $sampleRate" }
                        return MonoPcm16Wav(sampleRate, input)
                    }
                    else -> {
                        var skipped = 0L
                        while (skipped < chunkSize) {
                            val step = input.skip(chunkSize - skipped)
                            if (step <= 0) throw IllegalStateException("wav chunk truncated: $chunkId")
                            skipped += step
                        }
                        if (chunkSize % 2 == 1) input.readFully(ByteArray(1))
                    }
                }
            }
        } catch (error: Throwable) {
            runCatching { input.close() }
            throw error
        }
    }

    private fun readLittleShort(data: ByteArray, offset: Int): Int =
        (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)

    private fun readLittleInt(data: ByteArray, offset: Int): Int =
        (data[offset].toInt() and 0xFF) or
                ((data[offset + 1].toInt() and 0xFF) shl 8) or
                ((data[offset + 2].toInt() and 0xFF) shl 16) or
                ((data[offset + 3].toInt() and 0xFF) shl 24)
}

/**
 * 朗读引擎参数解析：批量缓存执行器与朗读引擎必须共用同一份解析，
 * 保证缓存 key 与实际合成参数同源。
 */
object TtsCacheParams {

    /**
     * 引擎种类：与 [io.legado.app.model.ReadAloud.selectedEngineType] 同序解析
     * （插件注册表 → 书源音频 → 数字 HTTP → V2 脚本 → 系统兜底）。
     * 两处消费（朗读路由、缓存合成/命中）共享同一判定顺序，禁止漂移。
     */
    enum class Kind { SYSTEM, HTTP, SCRIPT, PLUGIN, SOURCE_AUDIO }

    /** 经典引擎选择值原串（书级优先，回落全局）：空/未配置返回 null。 */
    fun engineSelection(book: Book): String? {
        val raw = book.getTtsEngine() ?: AppConfig.ttsEngine
        return raw?.takeIf { it.isNotBlank() }
    }

    fun kind(book: Book): Kind {
        val selected = engineSelection(book) ?: return Kind.SYSTEM
        ReadAloudEngines.byId(selected)?.let { return Kind.PLUGIN }
        if (selected == ReadAloud.SOURCE_AUDIO_ENGINE_ID) return Kind.SOURCE_AUDIO
        if (StringUtils.isNumeric(selected)) return Kind.HTTP
        if (TtsEngineStore.scriptEngineForSelection(selected) != null) return Kind.SCRIPT
        return Kind.SYSTEM
    }

    /** 引擎包名原值：空表示系统默认引擎（TextToSpeech 不指定 engine）。仅系统引擎语义。 */
    fun engineValue(book: Book): String? {
        val raw = engineSelection(book) ?: return null
        return GSON.fromJsonObject<SelectItem<String>>(raw).getOrNull()?.value
    }

    /**
     * 引擎 key（缓存 key 的引擎维度）：
     * 系统引擎取 SelectItem.value（引擎包名，未指定 = default，与历史 key 兼容）；
     * HTTP 为数字 id 原串；V2 脚本引擎为 `script:<id>` 原串；插件引擎为引擎 id 原串。
     */
    fun engineKey(book: Book): String {
        val selected = engineSelection(book) ?: return TtsCacheStore.DEFAULT_ENGINE_KEY
        if (kind(book) != Kind.SYSTEM) return selected
        return engineValue(book)?.takeIf { it.isNotBlank() } ?: TtsCacheStore.DEFAULT_ENGINE_KEY
    }

    /**
     * 缓存音色维度（引擎实际生效音色的标识）：脚本引擎取当前启用引擎的 activeVoiceId，
     * 插件引擎取插件合成能力上报的音色 key；系统引擎由调用方传 TextToSpeech 实例的
     * voice name，在线(HTTP)引擎无音色维度。两侧（合成/命中）必须同源取值。
     */
    fun cacheVoiceKey(book: Book): String? = when (kind(book)) {
        Kind.SCRIPT ->
            TtsEngineStore.enabledScriptEngineForSelection(engineSelection(book))?.activeVoiceId
        Kind.PLUGIN ->
            ReadAloudEngines.byId(engineSelection(book))?.cacheSynthesizer?.activeVoiceKey()
        else -> null
    }

    /** 播放端消费入口：与批量缓存同源的单元 key（音色维度按引擎种类统一解析）。 */
    fun playbackUnitKey(book: Book, chapter: BookChapter, text: String): TtsCacheStore.UnitKey =
        TtsCacheStore.buildUnitKey(book, chapter, text, cacheVoiceKey(book))

    /**
     * TTS 缓存入口不可用原因（朗读面板缓存按钮、沉浸页下载按钮等 UI 统一引用的唯一门控）；
     * null = 可用。规则域与 [requireCacheSupported] 同源：
     * 媒体书（含书源音频引擎选择）走各自下载；系统引擎依赖 TTS-Wav 播放管线，
     * 其他引擎（HTTP/脚本/插件）播放天然按缓存文件命中，无限制。
     */
    fun unavailableReasonRes(book: Book): Int? = when {
        book.isAudio || book.isVideo || kind(book) == Kind.SOURCE_AUDIO ->
            R.string.tts_cache_media_book_download
        kind(book) == Kind.SYSTEM && !AppConfig.ttsWavMode ->
            R.string.tts_cache_requires_wav
        else -> null
    }

    /**
     * 批量缓存前置校验（提交与执行两端共用）：与 [unavailableReasonRes] 同一规则，
     * 校验不过直接抛本地化原因，由提交端弹给用户。
     */
    fun requireCacheSupported(book: Book) {
        val reason = unavailableReasonRes(book)
        require(reason == null) { appCtx.getString(reason!!) }
    }

    /** 语速 key：跟随系统时无法感知系统内部语速，退化为常量标记。 */
    fun speedKey(): String =
        if (AppConfig.ttsFlowSys) {
            TtsCacheStore.FOLLOW_SYS_SPEED_KEY
        } else {
            AppConfig.ttsSpeechRate.toString()
        }

    /** 与 TTSReadAloudService.upSpeechRate 同公式，供合成实例设置语速。 */
    fun speechRateValue(): Float = (AppConfig.ttsSpeechRate + 5) / 10f

    /** 音色 key：引擎在合成实例上实际生效的 voice name；拿不到时记 default。 */
    fun voiceKey(voiceName: String?): String =
        voiceName?.takeIf { it.isNotBlank() } ?: TtsCacheStore.DEFAULT_VOICE_KEY

    /**
     * 创建系统 TextToSpeech 实例（engine 为空表示系统默认引擎），初始化完成后返回；
     * 引擎不可用返回 null。批量缓存执行器与缓存归档的音色候选枚举共用此入口。
     */
    suspend fun createSystemTts(engine: String?): TextToSpeech? =
        suspendCancellableCoroutine { cont ->
            val resumed = AtomicBoolean(false)
            fun finish(instance: TextToSpeech?) {
                if (resumed.compareAndSet(false, true)) cont.resume(instance)
            }

            // init 回调经主线程异步投递，先建实例再登记；holder 兜住极端早到回调
            val holder = AtomicReference<TextToSpeech?>()
            val callback: (Int) -> Unit = { status ->
                val instance = holder.get()
                when {
                    instance == null -> Unit
                    status == TextToSpeech.SUCCESS -> finish(instance)
                    else -> {
                        runCatching { instance.shutdown() }
                        finish(null)
                    }
                }
            }
            val instance = if (engine.isNullOrBlank()) {
                TextToSpeech(appCtx, callback)
            } else {
                TextToSpeech(appCtx, callback, engine)
            }
            holder.set(instance)
            cont.invokeOnCancellation { runCatching { instance.shutdown() } }
        }
}
