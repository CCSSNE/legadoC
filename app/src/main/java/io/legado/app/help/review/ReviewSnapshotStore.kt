package io.legado.app.help.review

import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.help.book.BookHelp
import io.legado.app.help.cache.CacheOperationDiagnostics
import io.legado.app.utils.GSON
import io.legado.app.utils.MD5Utils
import com.google.gson.stream.JsonReader
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
 * Durable result for the latest review-cache attempt of a chapter.
 *
 * Snapshot files only exist after a successful capture, so they cannot describe
 * buttons that failed to capture. This sidecar keeps that result explicit for
 * cache management and retry actions.
 */
data class ReviewChapterSnapshotStatus(
    val version: Int = 1,
    val bookUrl: String = "",
    val chapterUrl: String = "",
    val chapterIndex: Int = 0,
    val chapterTitle: String = "",
    val totalSnapshots: Int = 0,
    val failedSnapshots: Int = 0,
    val updatedAt: Long = 0L,
)

data class ReviewSnapshotCounts(
    private val byChapterUrl: Map<String, Int>,
    private val byChapterIndex: Map<Int, Int>,
) {
    fun forChapter(chapter: BookChapter): Int {
        return byChapterUrl[chapter.url.trim()]
            ?: byChapterIndex[chapter.index]
            ?: 0
    }
}

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
    private const val STATUS_FILE_PREFIX = "s_"
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

    private fun statusFileName(chapterUrl: String): String {
        return "$STATUS_FILE_PREFIX${MD5Utils.md5Encode16(chapterUrl.trim())}$FILE_SUFFIX"
    }

    private fun reviewFiles(book: Book): Array<File> {
        return reviewsDir(book).listFiles()
            ?.filter { it.name.startsWith(FILE_PREFIX) && it.name.endsWith(FILE_SUFFIX) }
            ?.toTypedArray()
            ?: emptyArray()
    }

    private fun statusFiles(book: Book): Array<File> {
        return reviewsDir(book).listFiles()
            ?.filter { it.name.startsWith(STATUS_FILE_PREFIX) && it.name.endsWith(FILE_SUFFIX) }
            ?.toTypedArray()
            ?: emptyArray()
    }

    internal fun put(
        book: Book,
        snapshot: ReviewSnapshot,
        diagnostics: CacheOperationDiagnostics.Context? = null,
    ) {
        if (snapshot.html.isBlank()) return
        val trace = CacheOperationDiagnostics.begin(
            diagnostics?.forChapter(snapshot.chapterIndex)
                ?: CacheOperationDiagnostics.Context(
                    domain = CacheOperationDiagnostics.Domain.REVIEW,
                    chapterIndex = snapshot.chapterIndex,
                ),
            "SNAPSHOT_WRITE",
            CacheOperationDiagnostics.Metrics(inputChars = snapshot.html.length),
            startAlways = true,
        )
        val dir = reviewsDir(book)
        try {
            if (!dir.exists()) dir.mkdirs()
            val name = if (snapshot.chapterUrl.isNotBlank()) {
                fileName(snapshot.chapterUrl, snapshot.buttonSrc)
            } else {
                legacyFileName(snapshot.chapterIndex, snapshot.buttonSrc)
            }
            val target = File(dir, name)
            // 快照 HTML 可能很大。Gson 直接写入 Writer，避免先构造整份 JSON String 和 UTF-8
            // ByteArray；它们会在原 HTML 仍存活时额外复制完整快照，放大 Java heap 峰值。
            target.bufferedWriter(Charsets.UTF_8).use { writer ->
                GSON.toJson(snapshot, writer)
            }
            // 主键变化后清理旧 index 键文件，避免重复占用
            val legacy = File(dir, legacyFileName(snapshot.chapterIndex, snapshot.buttonSrc))
            if (legacy.exists() && legacy.name != name) legacy.delete()
            trace.done(CacheOperationDiagnostics.Metrics(outputBytes = target.length()))
        } catch (error: Throwable) {
            trace.fail(error)
            throw error
        }
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
            file.bufferedReader(Charsets.UTF_8).use { reader ->
                GSON.fromJson(reader, ReviewSnapshot::class.java)
            }
        }.getOrNull()?.takeIf { it.html.isNotBlank() }
    }

    fun has(book: Book, chapter: BookChapter, buttonSrc: String): Boolean {
        // 文件名已经是章节 URL + 按钮 src 的确定性主键；不能为了判断“是否存在”而把
        // 整份内联 HTML 读入内存再解析一次。写入失败会向调用方抛出，正常落盘的文件
        // 即是可复用快照；真正打开时仍由 [get] 完整解析并直接暴露损坏文件。
        val dir = reviewsDir(book)
        return File(dir, fileName(chapter.url, buttonSrc)).isFile ||
            File(dir, legacyFileName(chapter.index, buttonSrc)).isFile
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
        reviewFiles(book).forEach { file ->
            readMetadata(file)?.let { snapshot ->
                val matches = when {
                    snapshot.chapterUrl.isNotBlank() ->
                        snapshot.chapterUrl.trim() == chapter.url.trim()
                    else -> snapshot.chapterIndex == chapter.index
                }
                if (matches) file.delete()
            }
        }
        File(reviewsDir(book), statusFileName(chapter.url)).delete()
    }

    /**
     * 只读取统计所需的章节字段。JsonReader.skipValue 会流式越过超大的 html 字段，
     * 缓存页统计不会把整书所有快照同时留在 heap 中。
     */
    fun chapterUrls(book: Book): Set<String> {
        return reviewFiles(book)
            .asSequence()
            .mapNotNull(::readMetadata)
            .map { it.chapterUrl }
            .filter { it.isNotBlank() }
            .toSet()
    }

    /** Counts persisted snapshots without reading their potentially huge HTML fields. */
    fun snapshotCounts(book: Book): ReviewSnapshotCounts {
        val byChapterUrl = hashMapOf<String, Int>()
        val byChapterIndex = hashMapOf<Int, Int>()
        reviewFiles(book).forEach { file ->
            readMetadata(file)?.let { snapshot ->
                if (snapshot.chapterUrl.isNotBlank()) {
                    val key = snapshot.chapterUrl.trim()
                    byChapterUrl[key] = (byChapterUrl[key] ?: 0) + 1
                } else {
                    byChapterIndex[snapshot.chapterIndex] =
                        (byChapterIndex[snapshot.chapterIndex] ?: 0) + 1
                }
            }
        }
        return ReviewSnapshotCounts(byChapterUrl, byChapterIndex)
    }

    /** Latest completed capture result for every chapter that has been attempted. */
    fun chapterStatuses(book: Book): List<ReviewChapterSnapshotStatus> {
        return statusFiles(book).mapNotNull(::readChapterStatus)
    }

    fun isChapterStatusFile(file: File): Boolean {
        return file.name.startsWith(STATUS_FILE_PREFIX) && file.name.endsWith(FILE_SUFFIX)
    }

    fun readChapterStatus(file: File): ReviewChapterSnapshotStatus? {
        if (!isChapterStatusFile(file)) return null
        return runCatching {
            file.bufferedReader(Charsets.UTF_8).use { reader ->
                GSON.fromJson(reader, ReviewChapterSnapshotStatus::class.java)
            }
        }.getOrNull()?.takeIf { it.chapterUrl.isNotBlank() && it.totalSnapshots > 0 }
    }

    /** Persists status independently of the successful snapshot payloads. */
    fun putChapterStatus(book: Book, status: ReviewChapterSnapshotStatus) {
        require(status.chapterUrl.isNotBlank()) { "review status requires chapterUrl" }
        require(status.totalSnapshots > 0) { "review status requires totalSnapshots" }
        val dir = reviewsDir(book)
        if (!dir.exists()) check(dir.mkdirs()) { "cannot create review status directory: ${dir.absolutePath}" }
        File(dir, statusFileName(status.chapterUrl)).bufferedWriter(Charsets.UTF_8).use { writer ->
            GSON.toJson(status, writer)
        }
    }

    /** 按原文件字节流导出，避免“读取所有快照 -> 重新序列化所有快照”的全量内存占用。 */
    fun copyAllTo(book: Book, targetDir: File) {
        check(targetDir.isDirectory || targetDir.mkdirs()) {
            "无法创建评论快照导出目录: ${targetDir.absolutePath}"
        }
        (reviewFiles(book).asList() + statusFiles(book).asList()).forEach { source ->
            File(targetDir, source.name).outputStream().buffered().use { output ->
                source.inputStream().buffered().use { input ->
                    input.copyTo(output)
                }
            }
        }
        ReviewSnapshotResourceStore.copyAllTo(book, targetDir)
    }

    private data class SnapshotMetadata(
        val chapterUrl: String,
        val chapterIndex: Int,
    )

    private fun readMetadata(file: File): SnapshotMetadata? {
        return runCatching {
            file.bufferedReader(Charsets.UTF_8).use { input ->
                JsonReader(input).use { reader ->
                    var chapterUrl = ""
                    var chapterIndex = 0
                    reader.beginObject()
                    while (reader.hasNext()) {
                        when (reader.nextName()) {
                            "chapterUrl" -> chapterUrl = reader.nextString()
                            "chapterIndex" -> chapterIndex = reader.nextInt()
                            // html 可能数十 MB；必须流式跳过，不能 nextString()。
                            else -> reader.skipValue()
                        }
                    }
                    reader.endObject()
                    SnapshotMetadata(chapterUrl, chapterIndex)
                }
            }
        }.getOrNull()
    }
}
