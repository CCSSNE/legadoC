package io.legado.app.help.book

import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.help.book.CacheMaintenance.CacheMaintenanceRules.findOrphanSubjects
import io.legado.app.help.book.CacheMaintenance.CacheMaintenanceRules.parseStoredWordCount
import io.legado.app.model.CacheBook
import io.legado.app.model.ReadBook
import io.legado.app.utils.StringUtils
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File

/**
 * 缓存维护：孤立章节缓存清理 + 章节字数重算。
 * 适配自 HapeLee/legado-with-MD3 84dd0e36d「新增缓存维护，支持清理孤立章节缓存与重算字数」，
 * 按本仓目录布局与命名规则重写（主/legacy/stable 三种章节缓存命名均纳入合法主体）。
 */
object CacheMaintenance {

    /** 书名 + 孤立文件清单 */
    data class OrphanGroup(
        val bookName: String,
        val bookUrl: String,
        val orphanFiles: List<File>,
    )

    data class RecountResult(
        val bookName: String,
        val recountedChapters: Int,
        val unresolvedChapters: Int,
        val bookWordCountUpdated: Boolean,
    )

    /** 扫描期间不参与判定的书（正在下载/正在阅读），与 MD3 busyBookUrls 同口径 */
    private fun busyBookUrls(): Set<String> {
        val busy = HashSet<String>()
        ReadBook.book?.bookUrl?.let { busy.add(it) }
        return busy
    }

    private fun isBusy(book: Book): Boolean = busyBookUrls().contains(book.bookUrl)
        || CacheBook.hasActiveBook(book.bookUrl)

    /**
     * 扫描孤立章节缓存：按书比对章节列表与缓存目录文件。
     * 章节列表拉不到的书不产孤立（无法判定）；目录（images/audio_media 等媒体目录）
     * 与 cache_manifest.json 豁免；正在下载/阅读的书跳过。
     */
    suspend fun scanOrphanedGroups(): List<OrphanGroup> {
        val groups = ArrayList<OrphanGroup>()
        val books = appDb.bookDao.all
        for (book in books) {
            currentCoroutineContext().ensureActive()
            if (isBusy(book)) continue
            val chapters = runCatching {
                appDb.bookChapterDao.getChapterList(book.bookUrl)
            }.getOrDefault(emptyList())
            if (chapters.isEmpty()) continue
            val dir = BookHelp.getCacheDir(book)
            if (!dir.isDirectory) continue
            val legalSubjects = HashSet<String>()
            chapters.forEach { chapter ->
                legalSubjects.add(chapter.getFileName().substringBeforeLast('.'))
                legalSubjects.add(chapter.contentCacheFileName().substringBeforeLast('.'))
            }
            val entryNames = dir.listFiles { file -> file.isFile }?.map { it.name } ?: continue
            val orphans = findOrphanSubjects(
                entryNames,
                legalSubjects,
                CacheMaintenanceRules.MANIFEST_SUBJECT,
            )
            if (orphans.isNotEmpty()) {
                groups.add(
                    OrphanGroup(
                        bookName = book.name,
                        bookUrl = book.bookUrl,
                        orphanFiles = orphans.map { File(dir, it) },
                    )
                )
            }
        }
        return groups
    }

    /** 删除孤立文件，返回实际删除数量；删除前再次跳过忙碌的书 */
    suspend fun deleteOrphanedFiles(groups: List<OrphanGroup>): Int {
        var deleted = 0
        for (group in groups) {
            currentCoroutineContext().ensureActive()
            val book = appDb.bookDao.getBook(group.bookUrl) ?: continue
            if (isBusy(book)) continue
            group.orphanFiles.forEach { file ->
                if (file.exists() && FileUtils.delete(file)) {
                    deleted++
                }
            }
        }
        return deleted
    }

    /** 可重算的在线文本书：存在「已缓存但缺字数」的章节 */
    suspend fun scanRecountTargets(): List<Book> {
        val targets = ArrayList<Book>()
        val books = appDb.bookDao.all
        for (book in books) {
            currentCoroutineContext().ensureActive()
            if (book.isLocal || !book.isOnLineTxt) continue
            val chapters = runCatching {
                appDb.bookChapterDao.getChapterList(book.bookUrl)
            }.getOrDefault(emptyList())
            val missing = chapters.any {
                parseStoredWordCount(it.wordCount) == null && BookHelp.hasContent(book, it)
            }
            if (missing) targets.add(book)
        }
        return targets
    }

    /**
     * 重算一本书：仅统计「字数为空但已有缓存」的章节；全部非卷章节都得到可信字数时才回写整书字数。
     * 与 MD3 原版的差异：本仓章节字数可能来自书源的任意文案（如「约321.5万字」），已有值一律
     * 不动（不覆盖源数据）；存在不可解析的既有值时放弃整书求和（仅补空白章节）。
     * 章节字数逐条 upWordCount 回写，整书用 BookDao.upWordCount 只写字数列
     * （不能用 @Update 全字段写入，会覆盖阅读页并发写回的进度字段）。
     */
    suspend fun recountBook(book: Book): RecountResult {
        val chapters = appDb.bookChapterDao.getChapterList(book.bookUrl).filterNot { it.isVolume }
        val resolved = HashMap<String, Int>()
        var recounted = 0
        var unresolved = 0
        var hasUnparseable = false
        for (chapter in chapters) {
            currentCoroutineContext().ensureActive()
            val raw = chapter.wordCount
            if (!raw.isNullOrBlank()) {
                val stored = parseStoredWordCount(raw)
                if (stored != null) {
                    resolved[chapter.url] = stored
                } else {
                    hasUnparseable = true
                }
                continue
            }
            if (!BookHelp.hasContent(book, chapter)) {
                unresolved++
                continue
            }
            val content = BookHelp.getContent(book, chapter)
            if (content.isNullOrEmpty()) {
                unresolved++
                continue
            }
            val length = content.length
            if (length <= 0) {
                unresolved++
                continue
            }
            val wordCount = StringUtils.wordCountFormat(length)
            appDb.bookChapterDao.upWordCount(book.bookUrl, chapter.url, wordCount)
            resolved[chapter.url] = length
            recounted++
        }
        var bookUpdated = false
        if (!hasUnparseable && resolved.size == chapters.size && chapters.isNotEmpty()) {
            val total = resolved.values.sum()
            if (total > 0) {
                appDb.bookDao.upWordCount(book.bookUrl, StringUtils.wordCountFormat(total))
                bookUpdated = true
            }
        }
        return RecountResult(book.name, recounted, unresolved, bookUpdated)
    }

    /**
     * 缓存维护纯规则（自 MD3 CacheMaintenanceRules 移植）：
     * 孤立主体判定、已存字数解析（只认自家格式，认不出返回 null 不猜数）。
     */
    object CacheMaintenanceRules {

        const val MANIFEST_SUBJECT = "cache_manifest"

        /** cachedEntryNames 中不在 legalSubjects 且不在豁免名单的条目（按主名比对，后缀无关） */
        fun findOrphanSubjects(
            cachedEntryNames: List<String>,
            legalSubjects: Set<String>,
            exemptSubject: String,
        ): List<String> {
            if (legalSubjects.isEmpty()) return emptyList()
            val orphans = ArrayList<String>()
            cachedEntryNames.forEach { name ->
                val subject = name.substringBeforeLast('.')
                if (subject == exemptSubject) return@forEach
                if (!legalSubjects.contains(subject)) {
                    orphans.add(name)
                }
            }
            return orphans
        }

        /** 解析已存字数：只认「N字」「N万字」与纯数字；认不出返回 null，不猜数 */
        fun parseStoredWordCount(raw: String?): Int? {
            if (raw.isNullOrBlank()) return null
            val text = raw.trim()
            if (text.isNumericLike()) {
                return text.toIntOrNull()?.takeIf { it > 0 }
            }
            if (text.endsWith("万字")) {
                val value = text.removeSuffix("万字").toDoubleOrNull() ?: return null
                return (value * 10000).toInt().takeIf { it > 0 }
            }
            if (text.endsWith("字")) {
                return text.removeSuffix("字").toIntOrNull()?.takeIf { it > 0 }
            }
            return null
        }

        private fun String.isNumericLike(): Boolean {
            return isNotEmpty() && all { it in '0'..'9' }
        }
    }
}
