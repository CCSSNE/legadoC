package io.legado.app.help.agent.mcp

import io.legado.app.constant.EventBus
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.Bookmark
import io.legado.app.help.agent.AgentControl
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.ContentProcessor
import io.legado.app.model.ReadBook
import io.legado.app.model.webBook.WebBook
import io.legado.app.ui.book.read.page.entities.TextChapter
import io.legado.app.ui.book.read.page.provider.ChapterProvider
import io.legado.app.utils.GSON
import io.legado.app.utils.postEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

object AgentReading {
    @Volatile private var owner: Any? = null
    @Volatile private var selectionReader: (() -> JSONObject)? = null
    fun attach(owner: Any, selectionReader: () -> JSONObject) { this.owner = owner; this.selectionReader = selectionReader }
    fun detach(owner: Any) { if (this.owner === owner) { this.owner = null; selectionReader = null } }
    fun current(): JSONObject = runBlocking(Dispatchers.Main.immediate) {
        if (owner == null) return@runBlocking JSONObject().put("open", false).put("reason", "当前没有打开阅读页")
        val book = ReadBook.book ?: return@runBlocking JSONObject().put("open", false).put("reason", "阅读页书籍尚未加载")
        JSONObject().put("open", true).put("bookUrl", book.bookUrl).put("bookName", book.name)
            .put("author", book.author).put("chapterIndex", ReadBook.durChapterIndex)
            .put("chapterTitle", ReadBook.curTextChapter?.chapter?.title ?: JSONObject.NULL)
            .put("position", ReadBook.durChapterPos).put("coordinate", "display")
            .put("selection", selectionReader?.invoke() ?: JSONObject.NULL).put("capturedAt", System.currentTimeMillis())
    }

    fun book(arguments: JSONObject): Book {
        if (arguments.has("bookUrl")) return appDb.bookDao.getBook(arguments.getString("bookUrl")) ?: error("书籍不存在：${arguments.getString("bookUrl")}")
        if (arguments.has("name")) {
            val matches = appDb.bookDao.all.filter { it.name == arguments.getString("name") &&
                (!arguments.has("author") || it.author == arguments.getString("author")) }
            require(matches.size == 1) { "书籍名称匹配 ${matches.size} 本，请指定 bookUrl" }
            return matches.single()
        }
        check(current().getBoolean("open")) { "没有当前阅读书籍，请指定 bookUrl" }
        return ReadBook.book ?: error("当前书籍已关闭")
    }

    private fun chapter(book: Book, arguments: JSONObject): BookChapter {
        val index = when {
            arguments.has("chapterIndex") -> arguments.getInt("chapterIndex")
            arguments.has("index") -> arguments.getInt("index")
            arguments.has("chapterTitle") -> {
                val matches = appDb.bookChapterDao.getChapterList(book.bookUrl).filter { it.title == arguments.getString("chapterTitle") }
                require(matches.size == 1) { "章节名称不唯一或不存在，请指定 chapterIndex" }
                matches.single().index
            }
            else -> {
                require(current().getBoolean("open") && ReadBook.book?.bookUrl == book.bookUrl) { "请指定 chapterIndex" }
                ReadBook.durChapterIndex
            }
        }
        return appDb.bookChapterDao.getChapter(book.bookUrl, index) ?: error("章节不存在：${book.bookUrl}/$index")
    }

    suspend fun raw(book: Book, chapter: BookChapter): String {
        currentCoroutineContext().ensureActive()
        BookHelp.getContent(book, chapter)?.let { return it }
        val source = appDb.bookSourceDao.getBookSource(book.origin) ?: error("正文未缓存且书源不存在：${book.origin}")
        val content = WebBook.getContentAwait(source, book, chapter)
        currentCoroutineContext().ensureActive()
        return content
    }

    fun revision(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    fun <T> page(items: List<T>, arguments: JSONObject, encode: (T) -> Any): JSONObject {
        val start = arguments.optInt("cursor", 0)
        val count = if (arguments.has("limit")) arguments.getInt("limit") else items.size - start
        require(start in 0..items.size && (count > 0 || start == items.size)) { "无效游标或无法前进的读取范围" }
        val end = (start.toLong() + count).coerceAtMost(items.size.toLong()).toInt()
        return JSONObject().put("total", items.size).put("items", JSONArray(items.subList(start, end).map(encode)))
            .put("nextCursor", if (end < items.size) end else JSONObject.NULL).put("complete", end == items.size)
    }

    suspend fun listChapters(arguments: JSONObject): JSONObject {
        val book = book(arguments)
        val all = appDb.bookChapterDao.getChapterList(book.bookUrl)
        val selected = all.filter { !arguments.has("keyword") || it.title.contains(arguments.getString("keyword")) }
        return page(selected, arguments) { chapter -> JSONObject().put("index", chapter.index).put("title", chapter.title)
            .put("url", chapter.url).put("cached", BookHelp.hasContent(book, chapter)) }
            .put("ok", true).put("bookUrl", book.bookUrl).put("chapterCount", all.size).apply { put("chapters", getJSONArray("items")) }
    }

    suspend fun readChapter(arguments: JSONObject): JSONObject {
        val book = book(arguments)
        val chapter = chapter(book, arguments)
        val original = raw(book, chapter)
        val coordinate = arguments.optString("coordinate", "raw")
        val text = when (coordinate) {
            "raw" -> original
            "display" -> display(book, chapter, original).getContent()
            else -> error("未知坐标类型：$coordinate")
        }
        val offset = arguments.optInt("offset", 0)
        val count = if (arguments.has("maxChars")) arguments.getInt("maxChars") else text.length - offset
        require(offset in 0..text.length && (count > 0 || offset == text.length)) { "正文范围无效或无法前进" }
        val end = (offset.toLong() + count).coerceAtMost(text.length.toLong()).toInt()
        return JSONObject().put("ok", true).put("bookUrl", book.bookUrl).put("chapterIndex", chapter.index).put("title", chapter.title)
            .put("coordinate", coordinate).put("revision", revision(text)).put("content", text.substring(offset, end))
            .put("offset", offset).put("total", text.length).put("nextOffset", if (end < text.length) end else JSONObject.NULL)
            .put("complete", end == text.length).put("paragraphs", paragraphs(text, offset, end))
    }

    private fun paragraphs(text: String, start: Int, end: Int) = JSONArray().apply {
        var offset = 0
        text.split('\n').forEachIndexed { index, paragraph ->
            val finish = offset + paragraph.length
            if (finish >= start && offset <= end) put(JSONObject().put("index", index).put("start", offset).put("end", finish))
            offset = finish + 1
        }
    }

    private suspend fun display(book: Book, chapter: BookChapter, raw: String): TextChapter = coroutineScope {
        require(current().getBoolean("open") && ChapterProvider.viewWidth > 0) { "显示坐标需要已打开的阅读页与有效排版尺寸" }
        val processor = ContentProcessor.get(book.name, book.origin)
        val contents = processor.getContent(book, chapter, raw, includeTitle = false)
        val result = ChapterProvider.getTextChapterAsync(this, book, chapter,
            chapter.getDisplayTitle(processor.getTitleReplaceRules(), book.getUseReplaceRule(), replaceBook = book.toReplaceBook()),
            contents, appDb.bookChapterDao.getChapterCount(book.bookUrl))
        result.layoutChannel.receiveAsFlow().collect { currentCoroutineContext().ensureActive() }
        result
    }

    private suspend fun position(book: Book, chapter: BookChapter, arguments: JSONObject): Pair<Int, String> {
        val original = raw(book, chapter)
        val rendered = display(book, chapter, original).getContent()
        val offset = arguments.getInt("position")
        val coordinate = arguments.getString("coordinate")
        val source = when (coordinate) { "raw" -> original; "display" -> rendered; else -> error("未知坐标类型：$coordinate") }
        require(arguments.getString("revision") == revision(source)) { "正文/替换规则已变化，请重新读取坐标" }
        require(offset in 0..source.length) { "位置超出正文范围" }
        if (coordinate == "display") return offset to rendered
        val quote = arguments.getString("quote")
        require(quote.isNotEmpty() && original.startsWith(quote, offset)) { "原文位置与 quote 不一致" }
        val mapped = rendered.indexOf(quote)
        require(mapped >= 0 && rendered.indexOf(quote, mapped + 1) < 0) { "原文片段经替换/排版后无法唯一定位；请读取 display 坐标，不猜测偏移" }
        return mapped to rendered
    }

    private suspend fun search(arguments: JSONObject, control: AgentControl): JSONObject {
        val book = book(arguments)
        val query = arguments.getString("query")
        require(query.isNotEmpty()) { "搜索词不能为空" }
        val chapters = appDb.bookChapterDao.getChapterList(book.bookUrl)
        val start = arguments.optInt("startChapter", 0)
        val end = arguments.optInt("endChapter", chapters.lastIndex)
        require(start >= 0 && end >= start && end < chapters.size) { "搜索章节范围无效" }
        val matches = JSONArray()
        val failures = JSONArray()
        val uncovered = JSONArray()
        for (index in start..end) {
            control.check()
            val chapter = chapters[index]
            if (arguments.optBoolean("cachedOnly") && !BookHelp.hasContent(book, chapter)) { uncovered.put(index); continue }
            try {
                val content = raw(book, chapter)
                val contentRevision = revision(content)
                var offset = content.indexOf(query)
                while (offset >= 0) {
                    control.check()
                    matches.put(JSONObject().put("chapterIndex", chapter.index).put("title", chapter.title).put("position", offset)
                        .put("end", offset + query.length).put("quote", query).put("coordinate", "raw").put("revision", contentRevision))
                    offset = content.indexOf(query, offset + 1)
                }
            } catch (error: CancellationException) { throw error
            } catch (error: Exception) { failures.put(JSONObject().put("chapterIndex", index).put("error", error.stackTraceToString())) }
        }
        return JSONObject().put("ok", failures.length() == 0).put("matches", matches).put("requestedStart", start).put("requestedEnd", end)
            .put("totalChapters", chapters.size).put("uncached", uncovered).put("failures", failures)
            .put("complete", failures.length() == 0 && uncovered.length() == 0)
    }

    private suspend fun bookmark(arguments: JSONObject, update: Boolean): JSONObject {
        val book = book(arguments)
        val previous = if (update) appDb.bookmarkDao.getByBook(book.name, book.author).singleOrNull { it.time == arguments.getLong("id") }
            ?: error("书签不存在") else null
        val targetChapter = if (arguments.has("position") || previous == null) chapter(book, arguments) else null
        val mapped = targetChapter?.let { position(book, it, arguments) }
        val record = previous ?: Bookmark(bookName = book.name, bookAuthor = book.author)
        if (targetChapter != null && mapped != null) {
            record.chapterIndex = targetChapter.index
            record.chapterName = targetChapter.title
            record.chapterPos = mapped.first
            record.bookText = arguments.optString("quote", "")
        }
        if (arguments.has("content")) record.content = arguments.getString("content")
        if (previous == null) appDb.bookmarkDao.insertNew(record) else appDb.bookmarkDao.update(record)
        postEvent(EventBus.BOOKMARK_CHANGED, true)
        return JSONObject().put("ok", true).put("bookmark", JSONObject(GSON.toJson(record)))
    }

    private suspend fun jump(arguments: JSONObject, control: AgentControl): JSONObject {
        val book = book(arguments)
        require(current().getBoolean("open") && ReadBook.book?.bookUrl == book.bookUrl) { "请先在阅读页打开目标书籍" }
        val chapter = chapter(book, arguments)
        val mapped = position(book, chapter, arguments)
        withContext(Dispatchers.Main) {
            control.check()
            check(ReadBook.openChapter(chapter.index, mapped.first)) { "章节跳转失败" }
        }
        withTimeout(io.legado.app.help.ai.AiRequestTimeoutConfig.generationTimeoutSeconds * 1000L) {
            while (ReadBook.curTextChapter?.chapter?.index != chapter.index || ReadBook.curTextChapter?.isCompleted != true) {
                control.check()
                ReadBook.msg?.let { error("阅读定位失败：$it") }
                delay(40)
            }
        }
        return JSONObject().put("ok", true).put("state", current())
    }

    fun tools(): List<AgentTool> {
        fun props(vararg entries: Pair<String, String>) = JSONObject().apply { entries.forEach { put(it.first, AgentCapabilities.property(it.second)) } }
        val coordinates = props("bookUrl" to "string", "chapterIndex" to "integer", "position" to "integer", "coordinate" to "string",
            "revision" to "string", "quote" to "string", "content" to "string", "id" to "integer")
        return listOf(
            AgentCapabilities.tool("reading", "get_reading_state", "当前实际阅读状态和选区；没有打开阅读页时明确返回 open=false", JSONObject()) { _, _ -> current() },
            AgentCapabilities.tool("reading", "read_display_chapter", "读取替换排版后的完整正文与可用于书签/跳转的 display 坐标", props("bookUrl" to "string", "chapterIndex" to "integer", "offset" to "integer", "maxChars" to "integer")) { args, _ -> readChapter(args.put("coordinate", "display")) },
            AgentCapabilities.tool("reading", "read_adjacent_chapters", "读取指定章节及相邻章节，边界处明确返回可用范围", props("bookUrl" to "string", "chapterIndex" to "integer")) { args, _ ->
                val book = book(args); val chapter = chapter(book, args); val count = appDb.bookChapterDao.getChapterCount(book.bookUrl)
                JSONObject().put("ok", true).put("chapters", JSONArray().apply {
                    for (index in (chapter.index - 1).coerceAtLeast(0)..(chapter.index + 1).coerceAtMost(count - 1)) {
                        put(readChapter(JSONObject().put("bookUrl", book.bookUrl).put("chapterIndex", index)))
                    }
                }).put("totalChapters", count)
            },
            AgentCapabilities.tool("reading", "search_book_content", "全书/章节范围原文检索；默认获取完整范围，明确报告未缓存和失败章节", props("bookUrl" to "string", "query" to "string", "startChapter" to "integer", "endChapter" to "integer", "cachedOnly" to "boolean"), listOf("query"), ::search),
            AgentCapabilities.tool("reading", "list_bookmarks", "查询既有书签与笔记，可用 cursor/limit 遍历全部", props("bookUrl" to "string", "cursor" to "integer", "limit" to "integer")) { args, _ ->
                val book = book(args); page(appDb.bookmarkDao.getByBook(book.name, book.author), args) { JSONObject(GSON.toJson(it)) }.put("ok", true)
            },
            AgentCapabilities.tool("reading", "create_bookmark", "新增阅读页可见书签/笔记；坐标须带正文 revision，raw 坐标还需唯一 quote", coordinates, listOf("position", "coordinate", "revision")) { args, _ -> bookmark(args, false) },
            AgentCapabilities.tool("reading", "update_bookmark", "修改既有书签内容或位置；位置变化时必须提供坐标与修订", coordinates, listOf("id")) { args, _ -> bookmark(args, true) },
            AgentCapabilities.tool("reading", "delete_bookmark", "删除既有书签或笔记", props("bookUrl" to "string", "id" to "integer"), listOf("id")) { args, _ ->
                val book = book(args)
                val record = appDb.bookmarkDao.getByBook(book.name, book.author).singleOrNull { it.time == args.getLong("id") } ?: error("书签不存在")
                appDb.bookmarkDao.delete(record); postEvent(EventBus.BOOKMARK_CHANGED, true); JSONObject().put("ok", true)
            },
            AgentCapabilities.tool("reading", "jump_to_reading_position", "跳转当前阅读书籍的章节与位置；必须使用有效 display 坐标或可唯一映射的原文引用", coordinates, listOf("position", "coordinate", "revision"), ::jump)
        )
    }
}
