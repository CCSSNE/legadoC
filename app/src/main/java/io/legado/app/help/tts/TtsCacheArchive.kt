package io.legado.app.help.tts

import android.os.Build
import io.legado.app.constant.AppPattern
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.help.config.AppConfig
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File

/**
 * TTS 音频缓存随书归档契约（TXT-ZIP 附属数据）。
 *
 * 导出：逐章用与朗读/批量缓存同源的单元序列推导（[TtsChapterUnits]）枚举朗读单元
 * 文本，以 key 维度候选（语速全量、音色全量）枚举命中 [TtsCacheStore] 缓存文件，
 * 产出 tts_cache/<旧章节stem>/<单元文件> + tts_cache_manifest.json。清单记录每个
 * 单元的完整缓存 key 输入（引擎/章节stem/文本/语速/音色）。
 *
 * 导入：回导书的 bookUrl 已变，缓存目录名与单元文件名都必须按新章节身份重建，
 * 清单是唯一可靠的映射来源。落位全程 key 寻址：引擎/语速/音色与生成时一致才会被
 * 播放命中，任何映射偏差只会 miss 后重新合成，不会错播。
 */
object TtsCacheArchive {

    const val MANIFEST_FILE_NAME = "tts_cache_manifest.json"
    const val MIN_SUPPORTED_VERSION = 1
    const val VERSION = 1

    /** 单元条目：记录重算缓存 key 所需的全部输入与归档内旧文件名。 */
    data class ManifestUnit(
        val file: String,
        val engineKey: String,
        val text: String,
        val speedKey: String? = null,
        val voiceKey: String? = null,
    )

    data class ManifestChapter(
        val index: Int,
        val title: String,
        val stem: String,
        val units: List<ManifestUnit> = emptyList(),
    )

    data class Manifest(
        val version: Int = VERSION,
        val chapters: List<ManifestChapter> = emptyList(),
    )

    data class RestoreReport(
        val chapterCount: Int,
        val unitCount: Int,
        val skippedChapterCount: Int,
        val skippedUnitCount: Int,
    ) {
        override fun toString(): String {
            val base = "章节 $chapterCount、单元 $unitCount"
            val skipped = skippedChapterCount + skippedUnitCount
            return if (skipped > 0) {
                "$base（跳过：章节 $skippedChapterCount、单元 $skippedUnitCount）"
            } else {
                base
            }
        }
    }

    /**
     * 收集可导出的 TTS 缓存清单。正文缺失/排版失败的章节无法推导单元文本，其缓存
     * 不导出；无可导出条目返回 null。key 候选枚举使收集结果与生成缓存时的引擎参数
     * 漂移（语速调整、音色切换）解耦：凡能按任一候选命中的缓存文件都会被收录。
     */
    suspend fun collectManifest(
        book: Book,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): Manifest? = coroutineScope {
        val cacheDir = TtsCacheStore.ttsCacheDir(book)
        if (!cacheDir.isDirectory) return@coroutineScope null
        val chapters = appDb.bookChapterDao.getChapterList(book.bookUrl).filterNot { it.isVolume }
        if (chapters.isEmpty()) return@coroutineScope null
        val engineKey = TtsCacheParams.engineKey(book)
        val speedCandidates = buildSpeedCandidates()
        val voiceCandidates = buildVoiceCandidates(book)
        val archivedChapters = mutableListOf<ManifestChapter>()
        chapters.forEachIndexed { position, chapter ->
            currentCoroutineContext().ensureActive()
            onProgress(position + 1, chapters.size)
            val stem = TtsCacheStore.chapterStem(chapter)
            if (!File(cacheDir, stem).isDirectory) return@forEachIndexed
            val units = when (val result = TtsChapterUnits.of(book, chapter, this)) {
                is TtsChapterUnits.Result.Ok ->
                    result.units.filterNot { it.matches(AppPattern.notReadAloudRegex) }
                else -> emptyList()
            }
            val explained = hashSetOf<String>()
            val unitRecords = mutableListOf<ManifestUnit>()
            units.forEach { text ->
                speedCandidates.forEach { speedKey ->
                    voiceCandidates.forEach { voiceKey ->
                        val key = TtsCacheStore.UnitKey(engineKey, stem, text, speedKey, voiceKey)
                        val file = TtsCacheStore.unitFile(book, key)
                        if (file.isFile && file.length() > 0L && explained.add(file.name)) {
                            unitRecords += ManifestUnit(file.name, engineKey, text, speedKey, voiceKey)
                        }
                    }
                }
            }
            if (unitRecords.isNotEmpty()) {
                archivedChapters += ManifestChapter(chapter.index, chapter.title, stem, unitRecords)
            }
        }
        if (archivedChapters.isEmpty()) null else Manifest(chapters = archivedChapters)
    }

    /**
     * 把归档缓存落位到导入书。章节身份由 [chapterMatcher] 决定（调用方注入本地
     * 章节匹配策略，宁可漏迁也不绑错章）；单元文件按清单记录的 key 输入以新章节
     * stem 重算后复制。单个文件缺失/损坏只计数跳过，不中断整体恢复。
     */
    fun restore(
        book: Book,
        manifest: Manifest,
        archiveRootDir: File,
        chapterMatcher: (index: Int, title: String) -> BookChapter?,
    ): RestoreReport {
        require(manifest.version in MIN_SUPPORTED_VERSION..VERSION) {
            "TTS cache manifest version is not supported: ${manifest.version}"
        }
        var chapterCount = 0
        var unitCount = 0
        var skippedChapterCount = 0
        var skippedUnitCount = 0
        manifest.chapters.forEach { archivedChapter ->
            val localChapter = chapterMatcher(archivedChapter.index, archivedChapter.title)
            if (localChapter == null || !validEntryName(archivedChapter.stem)) {
                skippedChapterCount += 1
                skippedUnitCount += archivedChapter.units.size
                return@forEach
            }
            var restoredInChapter = 0
            archivedChapter.units.forEach { unit ->
                if (!validEntryName(unit.file) || unit.text.isBlank()) {
                    skippedUnitCount += 1
                    return@forEach
                }
                val source = File(
                    File(archiveRootDir, TtsCacheStore.DIR_NAME),
                    "${archivedChapter.stem}/${unit.file}",
                )
                if (!source.isFile || source.length() == 0L) {
                    skippedUnitCount += 1
                    return@forEach
                }
                val key = TtsCacheStore.UnitKey(
                    unit.engineKey,
                    TtsCacheStore.chapterStem(localChapter),
                    unit.text,
                    unit.speedKey,
                    unit.voiceKey,
                )
                val target = TtsCacheStore.unitFile(book, key)
                target.parentFile?.mkdirs()
                source.copyTo(target, overwrite = true)
                if (target.isFile && target.length() == source.length()) {
                    restoredInChapter += 1
                } else {
                    skippedUnitCount += 1
                }
            }
            if (restoredInChapter > 0) {
                chapterCount += 1
                unitCount += restoredInChapter
            } else {
                skippedChapterCount += 1
            }
        }
        return RestoreReport(chapterCount, unitCount, skippedChapterCount, skippedUnitCount)
    }

    /** 归档内相对名只允许出现在路径末段，拒绝目录穿越与子目录结构。 */
    private fun validEntryName(name: String): Boolean {
        return name.isNotBlank() &&
            name != "." && name != ".." &&
            !name.contains('/') && !name.contains('\\')
    }

    /** 语速 key 候选：语速参与 key 时覆盖全部取值（缓存生成时刻的语速可能已漂移）。 */
    private fun buildSpeedCandidates(): List<String?> {
        return if (AppConfig.ttsCacheKeySpeed) {
            (0..50).map(Int::toString) + TtsCacheStore.FOLLOW_SYS_SPEED_KEY
        } else {
            listOf(null)
        }
    }

    /**
     * 音色 key 候选：脚本/插件引擎取当前生效音色 key；在线(HTTP)引擎恒为 default；
     * 系统引擎枚举当前引擎实例的全部音色名。
     */
    private suspend fun buildVoiceCandidates(book: Book): List<String?> {
        if (!AppConfig.ttsCacheKeyVoice) return listOf(null)
        return when (TtsCacheParams.kind(book)) {
            TtsCacheParams.Kind.SCRIPT,
            TtsCacheParams.Kind.PLUGIN,
            TtsCacheParams.Kind.HTTP,
            -> listOf(
                TtsCacheParams.cacheVoiceKey(book) ?: TtsCacheStore.DEFAULT_VOICE_KEY
            )
            else -> {
                val candidates = linkedSetOf(TtsCacheStore.DEFAULT_VOICE_KEY)
                val tts = TtsCacheParams.createSystemTts(TtsCacheParams.engineValue(book))
                    ?: return candidates.toList()
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        tts.voice?.name?.let(candidates::add)
                        tts.voices?.forEach { voice -> voice.name?.let(candidates::add) }
                    }
                } finally {
                    runCatching { tts.stop() }
                    runCatching { tts.shutdown() }
                }
                candidates.toList()
            }
        }
    }
}
