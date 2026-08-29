package io.legado.app.help.tts

import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.help.book.BookHelp
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.dialogs.SelectItem
import io.legado.app.utils.FileUtils
import io.legado.app.utils.GSON
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.StringUtils
import io.legado.app.utils.fromJsonObject
import java.io.File

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
     * 产物后缀按引擎类型区分：在线(HTTP) TTS 引擎返回的多为 mp3，
     * 系统引擎 synthesizeToFile 产物为 wav。播放端按内容嗅探不受后缀影响，
     * 这里只为文件管理器中的可读性诚实。
     */
    private fun audioSuffix(key: UnitKey): String =
        if (StringUtils.isNumeric(key.engineKey)) ".mp3" else ".wav"

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
}

/**
 * 朗读引擎参数解析：批量缓存执行器与朗读引擎必须共用同一份解析，
 * 保证缓存 key 与实际合成参数同源。
 */
object TtsCacheParams {

    /** 引擎标识：书级 ttsEngine 优先，回落全局设置；未指定（系统默认引擎）记 default。 */
    fun engineKey(book: Book): String =
        engineValue(book)?.takeIf { it.isNotBlank() } ?: TtsCacheStore.DEFAULT_ENGINE_KEY

    /** 引擎包名原值：空表示系统默认引擎（TextToSpeech 不指定 engine）。 */
    fun engineValue(book: Book): String? {
        val raw = book.getTtsEngine() ?: AppConfig.ttsEngine
        if (raw.isNullOrBlank()) return null
        return GSON.fromJsonObject<SelectItem<String>>(raw).getOrNull()?.value
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
}
