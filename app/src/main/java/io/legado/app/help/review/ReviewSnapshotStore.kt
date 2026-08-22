package io.legado.app.help.review

import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.help.book.BookHelp
import io.legado.app.utils.GSON
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.fromJsonObject
import java.io.File

/**
 * 评论页快照实体：一章的某个评论按钮对应一份“真实评论页”网页快照。
 * html 已在抓取时穷尽展开/回复/加载更多，并把样式与图片内联，可完全离线渲染。
 *
 * 快照主键 = chapter.url + buttonSrc：目录前插章节、重新排序后 index 会变化，
 * url 才是稳定标识；chapterIndex 只作为兼容/展示字段。
 */
data class ReviewSnapshot(
    val version: Int = 1,
    val bookUrl: String = "",
    /** 章节稳定标识：主键一部分 */
    val chapterUrl: String = "",
    /** 章节序号，仅兼容/展示用，不作为主键 */
    val chapterIndex: Int = 0,
    val chapterTitle: String = "",
    /** 抓取时的评论按钮 src（含选项 JSON），作为快照主键的一部分 */
    val buttonSrc: String = "",
    /** 真实评论页地址（click JS 执行后由 startBrowser/showBrowser 拦截得到） */
    val url: String = "",
    val title: String = "",
    val html: String = "",
    val savedAt: Long = 0L
)

/**
 * 评论页快照存储。
 *
 * 存储位置：<book_cache>/<book folder>/reviews/r_<md5(chapterUrl|buttonSrc)>.json
 * 快照主键 = 章节 URL + 评论按钮 src，与正文缓存相互独立：
 * 正文已缓存绝不代表评论快照已存在，“是否需要补评论”必须单独按键检查。
 */
object ReviewSnapshotStore {

    const val REVIEWS_DIR_NAME = "reviews"
    private const val FILE_PREFIX = "r_"
    private const val FILE_SUFFIX = ".json"

    fun reviewsDir(book: Book): File {
        return File(BookHelp.getCacheDir(book), REVIEWS_DIR_NAME)
    }

    /** 新版文件名：以章节 URL 为主键 */
    fun fileName(chapterUrl: String, buttonSrc: String): String {
        return "$FILE_PREFIX${MD5Utils.md5Encode16("${chapterUrl.trim()}|${buttonSrc.trim()}")}$FILE_SUFFIX"
    }

    /** 旧版（v1）文件名：以章节 index 为主键，仅兼容读取/导出旧快照 */
    fun legacyFileNameForExport(chapterIndex: Int, buttonSrc: String): String {
        return legacyFileName(chapterIndex, buttonSrc)
    }

    private fun legacyFileName(chapterIndex: Int, buttonSrc: String): String {
        return "$FILE_PREFIX${MD5Utils.md5Encode16("${chapterIndex}|${buttonSrc.trim()}")}$FILE_SUFFIX"
    }

    fun put(book: Book, snapshot: ReviewSnapshot) {
        if (snapshot.html.isBlank()) return
        val dir = reviewsDir(book)
        if (!dir.exists()) dir.mkdirs()
        val name = if (snapshot.chapterUrl.isNotBlank()) {
            fileName(snapshot.chapterUrl, snapshot.buttonSrc)
        } else {
            legacyFileName(snapshot.chapterIndex, snapshot.buttonSrc)
        }
        File(dir, name).writeText(GSON.toJson(snapshot), Charsets.UTF_8)
        // 主键变化后清理旧 index 键文件，避免重复占用
        val legacy = File(dir, legacyFileName(snapshot.chapterIndex, snapshot.buttonSrc))
        if (legacy.exists() && legacy.name != name) legacy.delete()
    }

    fun get(book: Book, chapter: BookChapter, buttonSrc: String): ReviewSnapshot? {
        val dir = reviewsDir(book)
        val file = File(dir, fileName(chapter.url, buttonSrc))
        if (!file.isFile) {
            // 兼容旧版 index 键快照
            val legacy = File(dir, legacyFileName(chapter.index, buttonSrc))
            return readSnapshot(legacy)
        }
        return readSnapshot(file)
    }

    private fun readSnapshot(file: File): ReviewSnapshot? {
        if (!file.isFile) return null
        return runCatching {
            GSON.fromJsonObject<ReviewSnapshot>(file.readText()).getOrNull()
        }.getOrNull()?.takeIf { it.html.isNotBlank() }
    }

    fun has(book: Book, chapter: BookChapter, buttonSrc: String): Boolean {
        return get(book, chapter, buttonSrc) != null
    }

    fun delete(book: Book, chapter: BookChapter, buttonSrc: String) {
        File(reviewsDir(book), fileName(chapter.url, buttonSrc)).delete()
        File(reviewsDir(book), legacyFileName(chapter.index, buttonSrc)).delete()
    }

    /**
     * 删除该章节（含章内全部评论按钮）的快照。
     * 主键是整体 md5，无法按文件名前缀过滤，因此逐条解析后按 chapterUrl/index 匹配。
     */
    fun deleteChapter(book: Book, chapter: BookChapter) {
        val dir = reviewsDir(book)
        val files = dir.listFiles() ?: return
        files.forEach { file ->
            if (!file.name.startsWith(FILE_PREFIX) || !file.name.endsWith(FILE_SUFFIX)) return@forEach
            runCatching {
                GSON.fromJsonObject<ReviewSnapshot>(file.readText()).getOrNull()
            }.getOrNull()?.let { snapshot ->
                val matches = when {
                    snapshot.chapterUrl.isNotBlank() ->
                        snapshot.chapterUrl.trim() == chapter.url.trim()
                    else -> snapshot.chapterIndex == chapter.index
                }
                if (matches) file.delete()
            }
        }
    }

    /** 统计该书全部快照（导出用） */
    fun listAll(book: Book): List<ReviewSnapshot> {
        val dir = reviewsDir(book)
        val files = dir.listFiles() ?: return emptyList()
        return files.asSequence()
            .filter { it.name.startsWith(FILE_PREFIX) && it.name.endsWith(FILE_SUFFIX) }
            .mapNotNull { readSnapshot(it) }
            .toList()
    }
}