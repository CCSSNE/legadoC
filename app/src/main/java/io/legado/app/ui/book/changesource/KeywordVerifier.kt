package io.legado.app.ui.book.changesource

import io.legado.app.constant.AppLog
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookSource
import io.legado.app.model.webBook.WebBook
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 关键词校验：对候选书源抓「指定章节」的正文，判断是否含全部必含词、且不含任何禁词。
 *
 * 与 Z·阅读 同名功能的语义一致：
 * - 章节名按「包含」匹配（去掉空白后比较），命中第一本即用；
 * - 多个关键词用 | 分隔（同正则习惯），空表示不限制；
 * - 结果不落库，抓正文时 needSave=false，不会污染本地缓存。
 */
object KeywordVerifier {

    data class SourceResult(
        val sourceName: String,
        val sourceUrl: String,
        val passed: Boolean,
        val reason: String,
        val chapterTitle: String? = null
    )

    fun parseKeywords(raw: String): List<String> = raw
        .split('|')
        .map { it.trim() }
        .filter { it.isNotEmpty() }

    suspend fun verify(
        bookSource: BookSource,
        book: Book,
        chapterName: String,
        must: List<String>,
        ban: List<String>,
        semaphore: Semaphore,
        timeoutMs: Long = 45_000
    ): SourceResult = semaphore.withPermit {
        val sourceName = bookSource.bookSourceName.ifBlank { bookSource.bookSourceUrl }
        val result = withTimeoutOrNull(timeoutMs) {
            runCatching {
                val chapters = WebBook.getChapterListAwait(bookSource, book, isFromBookInfo = true)
                    .getOrThrow()
                val target = findChapter(chapters, chapterName)
                    ?: return@runCatching SourceResult(
                        sourceName, bookSource.bookSourceUrl, false, "未找到章节：$chapterName"
                    )
                val content = WebBook.getContentAwait(
                    bookSource = bookSource,
                    book = book,
                    bookChapter = target,
                    needSave = false
                )
                val text = content.replace(Regex("\\s+"), "")
                val missing = must.filterNot { text.contains(it.replace(Regex("\\s+"), "")) }
                if (missing.isNotEmpty()) {
                    return@runCatching SourceResult(
                        sourceName, bookSource.bookSourceUrl, false,
                        "缺必含词：${missing.joinToString("、")}", target.title
                    )
                }
                val hit = ban.firstOrNull { text.contains(it.replace(Regex("\\s+"), "")) }
                if (hit != null) {
                    return@runCatching SourceResult(
                        sourceName, bookSource.bookSourceUrl, false,
                        "命中禁词：$hit", target.title
                    )
                }
                SourceResult(
                    sourceName, bookSource.bookSourceUrl, true,
                    "通过（${text.length} 字）", target.title
                )
            }.getOrElse { error ->
                AppLog.put("关键词校验失败：$sourceName\n${error.localizedMessage}")
                SourceResult(
                    sourceName, bookSource.bookSourceUrl, false,
                    "请求失败：${error.localizedMessage ?: "未知错误"}"
                )
            }
        }
        result ?: SourceResult(sourceName, bookSource.bookSourceUrl, false, "超时未返回")
    }

    private fun findChapter(
        chapters: List<io.legado.app.data.entities.BookChapter>,
        chapterName: String
    ): io.legado.app.data.entities.BookChapter? {
        val key = chapterName.replace(Regex("\\s+"), "")
        if (key.isEmpty()) return chapters.firstOrNull()
        return chapters.firstOrNull { it.title.replace(Regex("\\s+"), "").contains(key) }
            ?: chapters.firstOrNull { it.title.contains(chapterName.trim()) }
    }
}
