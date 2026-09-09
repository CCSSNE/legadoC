package io.legado.app.help.review

import android.util.AtomicFile
import com.google.gson.stream.JsonReader
import io.legado.app.data.entities.Book
import io.legado.app.utils.GSON
import io.legado.app.utils.MD5Utils
import splitties.init.appCtx
import java.io.File

/**
 * 管理页的派生目录索引，不参与快照完整性判定，也不修改原始缓存。
 * 文件名、长度和修改时间用于增量更新；已有快照只读 chapterUrl 所在的 JSON 头，
 * 取得身份立即关闭流，绝不 skipValue 穿过 HTML 或检查资源 blob。
 * 索引放在应用临时缓存中，即使被系统清理也能从原文件重新建立。
 */
internal object ReviewSnapshotInventory {
    data class Entry(
        val size: Long,
        val modified: Long,
        val chapterUrl: String,
        val status: ReviewChapterSnapshotStatus? = null,
    )

    data class Inventory(val files: Map<String, Entry> = emptyMap())

    @Synchronized
    fun read(book: Book, checkActive: () -> Unit = {}): Inventory {
        checkActive()
        val dir = ReviewSnapshotStore.reviewsDir(book)
        if (!dir.exists()) return Inventory()
        val files = checkNotNull(dir.listFiles { file ->
            ReviewSnapshotStore.isSnapshotFile(file) || ReviewSnapshotStore.isChapterStatusFile(file)
        }) { "无法读取评论缓存目录: ${dir.absolutePath}" }
        val indexDir = File(appCtx.cacheDir, "review_inventory")
        check(indexDir.isDirectory || indexDir.mkdirs()) {
            "无法创建评论目录索引: ${indexDir.absolutePath}"
        }
        val index = AtomicFile(File(indexDir, MD5Utils.md5Encode(dir.absolutePath) + ".json"))
        val previous = if (index.baseFile.exists()) {
            index.openRead().bufferedReader(Charsets.UTF_8).use {
                requireNotNull(GSON.fromJson(it, Inventory::class.java)) {
                    "评论目录索引为空: ${index.baseFile.absolutePath}"
                }
            }
        } else Inventory()
        val entries = files.associate { file ->
            checkActive()
            val size = file.length()
            val modified = file.lastModified()
            val old = previous.files[file.name]
            val entry = if (old != null && old.size == size && old.modified == modified) {
                old
            } else if (ReviewSnapshotStore.isChapterStatusFile(file)) {
                val status = requireNotNull(ReviewSnapshotStore.readChapterStatus(file))
                require(status.bookUrl == book.bookUrl) { "评论状态书籍不匹配: ${file.absolutePath}" }
                Entry(size, modified, status.chapterUrl.trim(), status)
            } else {
                Entry(size, modified, readChapterUrl(file, book))
            }
            file.name to entry
        }
        val result = Inventory(entries)
        checkActive()
        if (result != previous) {
            val stream = index.startWrite()
            try {
                val writer = stream.writer(Charsets.UTF_8)
                GSON.toJson(result, writer)
                writer.flush()
                index.finishWrite(stream)
            } catch (error: Throwable) {
                index.failWrite(stream)
                throw error
            }
        }
        return result
    }

    private fun readChapterUrl(file: File, book: Book): String {
        JsonReader(file.bufferedReader(Charsets.UTF_8)).use { reader ->
            var bookUrl: String? = null
            var chapterUrl: String? = null
            reader.beginObject()
            while (reader.hasNext()) {
                when (reader.nextName()) {
                    "bookUrl" -> bookUrl = reader.nextString()
                    "chapterUrl" -> chapterUrl = reader.nextString().trim()
                    "html" -> error("评论快照头缺少书籍或章节标识: ${file.absolutePath}")
                    else -> reader.skipValue()
                }
                if (bookUrl != null && chapterUrl != null) {
                    require(bookUrl == book.bookUrl && chapterUrl.isNotBlank()) {
                        "评论快照身份错误: ${file.absolutePath}"
                    }
                    return chapterUrl
                }
            }
        }
        error("评论快照缺少章节标识: ${file.absolutePath}")
    }
}
