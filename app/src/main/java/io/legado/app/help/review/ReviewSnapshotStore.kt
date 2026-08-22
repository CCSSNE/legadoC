package io.legado.app.help.review

import io.legado.app.data.entities.Book
import io.legado.app.help.book.BookHelp
import io.legado.app.utils.GSON
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.fromJsonObject
import java.io.File

/**
 * 评论页快照实体：一章的某个评论按钮对应一份“真实评论页”网页快照。
 * html 已在抓取时穷尽展开/回复/加载更多，并把样式与图片内联，可完全离线渲染。
 */
data class ReviewSnapshot(
    val version: Int = 1,
    val bookUrl: String = "",
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
 * 存储位置：<book_cache>/<book folder>/reviews/r_<md5(chapterIndex|buttonSrc)>.json
 * 快照主键 = 章节序号 + 评论按钮 src，与正文缓存相互独立：
 * 正文已缓存绝不代表评论快照已存在，“是否需要补评论”必须单独按键检查。
 */
object ReviewSnapshotStore {

    const val REVIEWS_DIR_NAME = "reviews"
    private const val FILE_PREFIX = "r_"
    private const val FILE_SUFFIX = ".json"

    fun reviewsDir(book: Book): File {
        return File(BookHelp.getCacheDir(book), REVIEWS_DIR_NAME)
    }

    fun snapshotFile(book: Book, chapterIndex: Int, buttonSrc: String): File {
        return File(reviewsDir(book), fileName(chapterIndex, buttonSrc))
    }

    fun fileName(chapterIndex: Int, buttonSrc: String): String {
        return "$FILE_PREFIX${MD5Utils.md5Encode16("${chapterIndex}|${buttonSrc}")}$FILE_SUFFIX"
    }

    fun put(book: Book, snapshot: ReviewSnapshot) {
        if (snapshot.html.isBlank()) return
        val dir = reviewsDir(book)
        if (!dir.exists()) dir.mkdirs()
        snapshotFile(book, snapshot.chapterIndex, snapshot.buttonSrc)
            .writeText(GSON.toJson(snapshot), Charsets.UTF_8)
    }

    fun get(book: Book, chapterIndex: Int, buttonSrc: String): ReviewSnapshot? {
        val file = snapshotFile(book, chapterIndex, buttonSrc)
        if (!file.isFile) return null
        return runCatching {
            GSON.fromJsonObject<ReviewSnapshot>(file.readText()).getOrNull()
        }.getOrNull()?.takeIf { it.html.isNotBlank() }
    }

    fun has(book: Book, chapterIndex: Int, buttonSrc: String): Boolean {
        return get(book, chapterIndex, buttonSrc) != null
    }

    fun delete(book: Book, chapterIndex: Int, buttonSrc: String) {
        snapshotFile(book, chapterIndex, buttonSrc).delete()
    }

    /** 统计该书全部快照（导出用） */
    fun listAll(book: Book): List<ReviewSnapshot> {
        val dir = reviewsDir(book)
        val files = dir.listFiles() ?: return emptyList()
        return files.asSequence()
            .filter { it.name.startsWith(FILE_PREFIX) && it.name.endsWith(FILE_SUFFIX) }
            .mapNotNull { file ->
                runCatching {
                    GSON.fromJsonObject<ReviewSnapshot>(file.readText()).getOrNull()
                }.getOrNull()
            }
            .filter { it.html.isNotBlank() }
            .toList()
    }
}
