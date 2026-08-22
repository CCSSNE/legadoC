package io.legado.app.help.book

import io.legado.app.constant.AppPattern
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.utils.StringUtils

/**
 * 音频书 × 文本书 评论融合。
 *
 * 目标关系：Audio = 主体（继续负责音频与字幕），Text = 评论元数据提供者。
 * 只处理双方已经缓存的章节，不做任何联网下载：
 * - 第一层章节匹配：标题归一化相等优先，其次同序号（第 N 章 ↔ 第 N 章）配对；
 * - 第二层段落匹配：Text 段落文字 ↔ Audio 章节字幕行文字，
 *   归一化对全角/半角、空白与标点不敏感；
 * - 迁移对象不是 Text 正文，而是段落关联的评论入口：段落内 TEXT 样式
 *   评论小图（`<img src="…,{"style":"TEXT","click":…}">`）与紧随段落的
 *   `<usehtml>…</usehtml>` 块。统一以 usehtml 块写回 Audio 章节 lyric 变量中
 *   对应字幕行之后——与原生有声书源添加评论按钮的形态完全一致，
 *   渲染走既有 usehtml 结构块路径，cue 时间轴不受影响。
 */
object AudioTextFusion {

    /** 融合汇总：配对章节数、实际写入的音频章节数、迁移的评论入口数 */
    data class Result(
        val pairedChapters: Int,
        val fusedChapters: Int,
        val migratedEntries: Int,
    ) {
        val migratedAnything: Boolean get() = migratedEntries > 0
    }

    data class LyricFusion(
        val newLyric: String,
        val migratedEntries: Int,
    )

    /**
     * 遍历两本书配对的已缓存章节并执行融合，返回汇总。
     * 调用方需在 IO 线程执行；本函数只读缓存文件与数据库，不发起网络请求。
     */
    fun fuseBooks(textBook: Book, audioBook: Book): Result {
        val textChapters = appDb.bookChapterDao.getChapterList(textBook.bookUrl)
            .filterNot { it.isVolume }
        val audioChapters = appDb.bookChapterDao.getChapterList(audioBook.bookUrl)
            .filterNot { it.isVolume }
        val pairs = pairChapters(textChapters, audioChapters)
        var fusedChapters = 0
        var migratedEntries = 0
        pairs.forEach { (textChapter, audioChapter) ->
            if (!BookHelp.hasContent(textBook, textChapter)) return@forEach
            if (!BookHelp.hasContent(audioBook, audioChapter)) return@forEach
            val lyric = audioChapter.getVariable("lyric")
            if (lyric.isBlank()) return@forEach
            val textContent = BookHelp.getContent(textBook, textChapter) ?: return@forEach
            val fusion = fuseLyric(textContent, lyric) ?: return@forEach
            audioChapter.putLyric(fusion.newLyric)
            fusedChapters++
            migratedEntries += fusion.migratedEntries
        }
        return Result(pairs.size, fusedChapters, migratedEntries)
    }

    /**
     * 第一层章节匹配：
     * 1. 标题归一化相等（保持 Text 顺序，重复标题按出现顺序消费）；
     * 2. 剩余未配对章节按相同序号配对（仅当双方该序号都未被占用）。
     */
    internal fun pairChapters(
        textChapters: List<BookChapter>,
        audioChapters: List<BookChapter>
    ): List<Pair<BookChapter, BookChapter>> {
        val audioByKey = linkedMapOf<String, ArrayDeque<BookChapter>>()
        audioChapters.forEach { chapter ->
            val key = normalizeKey(chapter.title)
            if (key.isNotEmpty()) {
                audioByKey.getOrPut(key) { ArrayDeque() }.addLast(chapter)
            }
        }
        val pairedAudio = hashSetOf<BookChapter>()
        val result = ArrayList<Pair<BookChapter, BookChapter>>(textChapters.size)
        val pendingText = ArrayList<BookChapter>()
        textChapters.forEach { chapter ->
            val matched = audioByKey[normalizeKey(chapter.title)]
                ?.removeFirstOrNull()
                ?.also(pairedAudio::add)
            if (matched == null) {
                pendingText.add(chapter)
            } else {
                result.add(chapter to matched)
            }
        }
        val unusedAudioByIdx = audioChapters
            .filterNot { it in pairedAudio }
            .associateBy { it.index }
        pendingText.forEach { chapter ->
            val audio = unusedAudioByIdx[chapter.index] ?: return@forEach
            if (pairedAudio.add(audio)) {
                result.add(chapter to audio)
            }
        }
        return result
    }

    /**
     * 第二层段落匹配 + 评论入口迁移：把 [textContent] 中带评论入口的段落
     * 挂到 [lyric] 对应字幕行之后。无可迁移内容时返回 null，lyric 保持不变。
     * 已存在的同内容评论块（重复融合）不再插入，保证幂等。
     */
    internal fun fuseLyric(textContent: String, lyric: String): LyricFusion? {
        val entries = parseCommentParagraphs(textContent)
        if (entries.isEmpty()) return null
        val pendingByKey = linkedMapOf<String, ArrayDeque<FusionEntry>>()
        entries.forEach { entry ->
            pendingByKey.getOrPut(entry.key) { ArrayDeque() }.addLast(entry)
        }
        val existingBlockKeys = AppPattern.useHtmlRegex.findAll(lyric)
            .mapTo(hashSetOf()) { normalizeKey(it.value) }

        val builder = StringBuilder(lyric.length + 256)
        var migrated = 0
        var lastEnd = 0
        // 已有的 usehtml 结构块原样保留；只在其外的字幕行后插入新块
        AppPattern.useHtmlRegex.findAll(lyric).forEach { blockMatch ->
            migrated += scanLyricSegment(
                builder,
                lyric.substring(lastEnd, blockMatch.range.first),
                pendingByKey,
                existingBlockKeys
            )
            builder.append(blockMatch.value)
            lastEnd = blockMatch.range.last + 1
        }
        migrated += scanLyricSegment(
            builder,
            lyric.substring(lastEnd),
            pendingByKey,
            existingBlockKeys
        )

        if (migrated == 0) return null
        return LyricFusion(builder.toString(), migrated)
    }

    /** 逐行扫描一段不含 usehtml 块的字幕文本，在命中的字幕行后写入评论块 */
    private fun scanLyricSegment(
        builder: StringBuilder,
        segment: String,
        pendingByKey: Map<String, ArrayDeque<FusionEntry>>,
        existingBlockKeys: Set<String>
    ): Int {
        var migrated = 0
        // 行文本 + 该行在原文中是否以换行符结尾（末尾换行不再产生空元素）
        val rawLines = buildList {
            var start = 0
            while (start <= segment.length) {
                val newLineIndex = segment.indexOf('\n', start)
                if (newLineIndex < 0) {
                    add(segment.substring(start) to false)
                    break
                }
                add(segment.substring(start, newLineIndex) to true)
                start = newLineIndex + 1
                if (start == segment.length) break
            }
        }
        for ((lineText, hasTrailingNewLine) in rawLines) {
            builder.append(lineText)
            val key = subtitleKey(lineText)
            val entry = if (key.isEmpty()) {
                null
            } else {
                pendingByKey[key]?.removeFirstOrNull()
            }
            // 按块判重：只补缺失的评论块，保证重复融合不产生副本
            val newBlocks = entry?.payload?.let { payload ->
                AppPattern.useHtmlRegex.findAll(payload)
                    .map { it.value }
                    .filter { normalizeKey(it) !in existingBlockKeys }
                    .toList()
            }.orEmpty()
            if (newBlocks.isNotEmpty()) {
                // 评论块独立成行插入；多出的空行不影响 AudioTextMapping 解析
                builder.append('\n').append(newBlocks.joinToString("\n")).append('\n')
                migrated++
            } else if (hasTrailingNewLine) {
                builder.append('\n')
            }
        }
        return migrated
    }

    /**
     * 解析 Text 章节正文：按 usehtml 块切分原文，
     * 每个非空正文行收集为一个候选段落（归一化 key + 评论入口载荷），
     * 紧随其后的 usehtml 块并入该段落的载荷；无归属的块视为章节级装饰丢弃。
     * 只返回真正携带评论入口、且文字可参与匹配的段落。
     */
    internal fun parseCommentParagraphs(content: String): List<FusionEntry> {
        val paragraphs = ArrayList<FusionEntry>()
        fun appendBodyLines(part: String) {
            splitRawLines(part).forEach { rawLine ->
                val line = rawLine.trim { it <= ' ' || it == ' ' || it == '\u00A0' }
                if (line.isEmpty()) return@forEach
                val (textWithoutButtons, buttons) = splitInlineCommentButtons(line)
                val key = normalizeKey(textWithoutButtons)
                if (key.isEmpty() && buttons.isEmpty()) return@forEach
                paragraphs.add(FusionEntry(key, joinButtonPayload(buttons)))
            }
        }
        var lastEnd = 0
        AppPattern.useHtmlRegex.findAll(content).forEach { blockMatch ->
            appendBodyLines(content.substring(lastEnd, blockMatch.range.first))
            // usehtml 块归属于它前面的最近一个正文段落
            paragraphs.lastOrNull()?.let { previous ->
                val block = blockMatch.value.trim()
                previous.payload += if (previous.payload.isEmpty()) block else "\n$block"
            }
            lastEnd = blockMatch.range.last + 1
        }
        appendBodyLines(content.substring(lastEnd))
        return paragraphs.filter { it.key.isNotEmpty() && it.payload.isNotEmpty() }
    }

    private val imgTagRegex = Regex("<img\\b[^>]*>", RegexOption.IGNORE_CASE)
    private val imgSrcAttrRegex = Regex("\\bsrc\\s*=\\s*", RegexOption.IGNORE_CASE)

    /**
     * 取 img 标签的 src 值。缓存正文的形态是 `<img src="URL,{…}">`：
     * 选项 JSON 与属性定界同为双引号（与 [AppPattern.imgPattern] 的特殊分组一致），
     * 因此取“起始引号之后到标签内最后一个同类引号”之间的内容。
     */
    private fun extractImgSrc(imgTag: String): String? {
        val attr = imgSrcAttrRegex.find(imgTag) ?: return null
        val valueStart = attr.range.last + 1
        if (valueStart >= imgTag.length) return null
        val quote = imgTag[valueStart]
        return if (quote == '"' || quote == '\'') {
            val valueEnd = imgTag.lastIndexOf(quote)
            if (valueEnd <= valueStart) null else imgTag.substring(valueStart + 1, valueEnd)
        } else {
            val unquotedEnd = imgTag.indexOfFirst {
                it.isWhitespace()
            }.takeIf { it > valueStart } ?: imgTag.length
            imgTag.substring(valueStart, unquotedEnd)
        }
    }

    /**
     * 把一行正文拆成“纯文字”和“评论小图”：TEXT 样式的行内图片是段落评论泡，
     * 其余 img（插图等）既不迁移也从匹配键中剔除，避免 URL 字母干扰段落匹配。
     */
    internal fun splitInlineCommentButtons(line: String): Pair<String, List<String>> {
        if (!line.contains("<img", ignoreCase = true)) {
            return line to emptyList()
        }
        val buttons = ArrayList<String>(1)
        val text = StringBuilder(line.length)
        var lastEnd = 0
        imgTagRegex.findAll(line).forEach { match ->
            text.append(line, lastEnd, match.range.first)
            lastEnd = match.range.last + 1
            if (isReviewButton(match.value)) {
                buttons.add(match.value)
            }
        }
        text.append(line, lastEnd, line.length)
        return text.toString() to buttons
    }

    /** 评论泡判定：src 携带的选项 JSON 里 style 为 TEXT（与排版层同一约定） */
    private fun isReviewButton(imgTag: String): Boolean {
        val src = extractImgSrc(imgTag) ?: return false
        val jsonStart = findUrlOptionJsonStart(src) ?: return false
        val optionJson = src.substring(jsonStart)
        val style = Regex("\"style\"\\s*:\\s*\"([^\"]*)\"", RegexOption.IGNORE_CASE)
            .find(optionJson)?.groupValues?.get(1)
        return style.equals("TEXT", ignoreCase = true)
    }

    /** 与 AnalyzeUrl.paramPattern 一致：URL 后第一个 `,` 到 `{` 之间只允许空白 */
    private fun findUrlOptionJsonStart(src: String): Int? {
        val comma = src.indexOf(',')
        if (comma < 0) return null
        var index = comma + 1
        while (index < src.length && src[index].isWhitespace()) index++
        if (index >= src.length || src[index] != '{') return null
        return index
    }

    /** 组装要写入 Audio 字幕行之后的载荷：行内评论图包进一个 usehtml 块 */
    private fun joinButtonPayload(buttons: List<String>): String {
        if (buttons.isEmpty()) return ""
        return "<usehtml>" + buttons.joinToString("") + "</usehtml>"
    }

    /**
     * 字幕行匹配键：剥离时间轴标记后，对全角/半角、空白、标点不敏感；
     * 返回空串表示该行不是可匹配的正文行（空行/元数据/纯时间标签）。
     */
    internal fun subtitleKey(rawLine: String): String {
        if (AudioTextMapping.isMetadataLine(rawLine)) return ""
        val stripped = AudioTextMapping.stripTimelineMarks(rawLine)
        return normalizeKey(stripped)
    }

    private fun normalizeKey(text: String): String {
        val builder = StringBuilder(text.length)
        StringUtils.fullToHalf(text).lowercase().forEach { char ->
            if (char.isLetterOrDigit()) builder.append(char)
        }
        return builder.toString()
    }

    /** 统一按 \n 切分并保留结尾空串，避免不同 stdlib 行序词语义差异 */
    private fun splitRawLines(text: String): List<String> = text.split('\n')

    /** 一个待迁移段落：归一化文字 key 与组装好的 usehtml 载荷（解析期可追加） */
    internal data class FusionEntry(
        val key: String,
        var payload: String,
    )
}
