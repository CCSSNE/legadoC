package io.legado.app.help.book

import com.google.gson.reflect.TypeToken
import io.legado.app.constant.AppLog
import io.legado.app.constant.AppPattern
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.utils.GSON
import io.legado.app.utils.StringUtils

/**
 * 音频书 × 文本书 评论融合。
 *
 * 目标关系：Audio = 主体（继续负责音频与字幕），Text = 评论元数据提供者。
 * 只处理双方已经缓存的章节，不做任何联网下载。
 *
 * 融合结果不写入原始 lyric：评论挂载以 overlay（章节 variable 的
 * [OVERLAY_KEY] 键）单独保存，显示时由 [effectiveLyric] 动态合并。
 * 因此音频书源刷新章节、重新下载副内容（`putLyric` 覆盖 lyric）不会冲掉
 * 融合数据；并天然支持“取消融合/重新融合”。
 *
 * 两层匹配：
 * - 第一层章节匹配：标题归一化相等优先；其次章节号相等（中文/阿拉伯数字
 *   统一解析，如“第2章”↔“第二章”）；最后做邻章一致性验证——按正文顺序
 *   Audio 章序号必须严格递增，违反者直接丢弃。不做同 index 兜底，宁可
 *   少融合也不能错融合。
 * - 第二层段落匹配：Text 段落文字 ↔ Audio 章节字幕行文字
 *   （块外正文行），锚点归一化对全角/半角、空白与标点不敏感，并按
 *   “字幕锚点 + 第几次出现”记录挂载位置；audio 源更新字幕文本后，
 *   不再匹配的锚点自动不挂载，匹配的锚点仍按原次序生效。
 *
 * 迁移对象不是 Text 正文，而是段落关联的评论入口：段落内 TEXT 样式
 * 评论小图（`<img src="…,{"style":"TEXT","click":…}">`，与排版层共用
 * [AppPattern.imgPattern] 解析口径）与紧随段落（按原始 offset 邻接）的
 * `<usehtml>…</usehtml>` 块。统一以 usehtml 块插入 Audio 章节字幕行之后，
 * 与原生有声书源添加评论按钮的形态一致，cue 时间轴不受影响。
 */
object AudioTextFusion {

    /** 融合 overlay 在章节 variable 中的键；原始 lyric 保持不变 */
    const val OVERLAY_KEY = "audioTextFusion"

    /** 融合汇总：配对章节数、实际写入 overlay 的音频章节数、挂载的评论入口数 */
    data class Result(
        val pairedChapters: Int,
        val fusedChapters: Int,
        val migratedEntries: Int,
    ) {
        val migratedAnything: Boolean get() = migratedEntries > 0
    }

    /**
     * 一条评论挂载：挂在 lyric 中第 [occurrence] 次出现（从 1 起）的
     * [anchor] 匹配正文行之后；[payload] 为完整 `<usehtml>…</usehtml>` 块。
     */
    data class OverlayInsertion(
        val anchor: String,
        val occurrence: Int,
        val payload: String,
    )

    /**
     * 遍历两本书配对的已缓存章节并执行融合，把挂载列表写入 Audio 章节的
     * overlay 变量（不触碰原始 lyric）。调用方需在 IO 线程执行；
     * 本函数只读缓存文件与数据库，不发起网络请求。重复融合以文字书当前
     * 内容重新生成并替换旧 overlay。
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
            val insertions = fuseOverlay(textContent, lyric) ?: return@forEach
            saveOverlay(audioChapter, insertions)
            fusedChapters++
            migratedEntries += insertions.size
        }
        return Result(pairs.size, fusedChapters, migratedEntries)
    }

    /** 取消融合：清除该书全部章节的 overlay，恢复为原始 lyric；返回清理章节数 */
    fun removeFusionOverlay(audioBook: Book): Int {
        var removed = 0
        appDb.bookChapterDao.getChapterList(audioBook.bookUrl).forEach { chapter ->
            if (chapter.getVariable(OVERLAY_KEY).isNotBlank()) {
                chapter.putVariable(OVERLAY_KEY, null)
                chapter.update()
                removed++
            }
        }
        return removed
    }

    /** 显示用有效字幕：原始 lyric + overlay 动态合并；无 overlay 时原样返回 */
    fun effectiveLyric(chapter: BookChapter): String {
        val lyric = chapter.getVariable("lyric")
        val overlay = chapter.getVariable(OVERLAY_KEY)
        if (overlay.isBlank()) return lyric
        return applyOverlay(lyric, overlay)
    }

    fun saveOverlay(chapter: BookChapter, insertions: List<OverlayInsertion>) {
        chapter.putVariable(OVERLAY_KEY, buildOverlay(insertions))
        chapter.update()
    }

    internal fun buildOverlay(insertions: List<OverlayInsertion>): String = GSON.toJson(insertions)

    internal fun parseOverlay(overlayJson: String): List<OverlayInsertion> {
        if (overlayJson.isBlank()) return emptyList()
        return runCatching {
            GSON.fromJson<List<OverlayInsertion>>(
                overlayJson,
                object : TypeToken<List<OverlayInsertion>>() {}.type
            ) ?: emptyList()
        }.onFailure {
            AppLog.put("解析融合 overlay 失败", it)
        }.getOrDefault(emptyList())
    }

    /**
     * 第一层章节匹配：
     * 1. 标题归一化相等（保持 Text 顺序，重复标题按出现顺序消费）；
     * 2. 章节号相等（中文/阿拉伯数字统一解析，如“第2章”↔“第二章”）；
     * 3. 邻章一致性验证：按正文顺序，Audio 章序号必须严格递增，
     *    违反者丢弃——不做同 index 兜底，宁可少融合也不能错融合。
     */
    internal fun pairChapters(
        textChapters: List<BookChapter>,
        audioChapters: List<BookChapter>
    ): List<Pair<BookChapter, BookChapter>> {
        val result = ArrayList<Pair<BookChapter, BookChapter>>(textChapters.size)
        val usedAudio = hashSetOf<BookChapter>()
        val pendingText = ArrayList<BookChapter>()

        // 1. 标题归一化相等
        val audioByTitleKey = linkedMapOf<String, ArrayDeque<BookChapter>>()
        audioChapters.forEach { chapter ->
            val key = normalizeKey(chapter.title)
            if (key.isNotEmpty()) {
                audioByTitleKey.getOrPut(key) { ArrayDeque() }.addLast(chapter)
            }
        }
        textChapters.forEach { chapter ->
            val matched = audioByTitleKey[normalizeKey(chapter.title)]
                ?.removeFirstOrNull()
                ?.also(usedAudio::add)
            if (matched == null) {
                pendingText.add(chapter)
            } else {
                result.add(chapter to matched)
            }
        }

        // 2. 章节号相等（解析失败即跳过，不用同 index 兜底）
        val audioByNum = linkedMapOf<Int, ArrayDeque<BookChapter>>()
        audioChapters.filterNot { it in usedAudio }.forEach { chapter ->
            val num = ChapterTitle.num(chapter.title)
            if (num >= 0) {
                audioByNum.getOrPut(num) { ArrayDeque() }.addLast(chapter)
            }
        }
        pendingText.forEach { chapter ->
            val num = ChapterTitle.num(chapter.title)
            if (num < 0) return@forEach
            val matched = audioByNum[num]?.removeFirstOrNull()?.also(usedAudio::add) ?: return@forEach
            result.add(chapter to matched)
        }

        // 3. 邻章一致性：Audio 序号随正文顺序严格递增，违反者丢弃
        var lastAudioIndex = -1
        return result.sortedBy { it.first.index }.filter { (_, audio) ->
            if (audio.index > lastAudioIndex) {
                lastAudioIndex = audio.index
                true
            } else {
                false
            }
        }
    }

    /**
     * 第二层匹配：从文字书章节正文提取带评论入口的段落，计算与 lyric
     * 块外正文行的锚点挂载列表。无任何挂载时返回 null，lyric 保持不变。
     */
    internal fun fuseOverlay(textContent: String, lyric: String): List<OverlayInsertion>? {
        val entries = parseCommentParagraphs(textContent)
        if (entries.isEmpty()) return null
        val pendingByKey = linkedMapOf<String, ArrayDeque<FusionEntry>>()
        entries.forEach { entry ->
            pendingByKey.getOrPut(entry.key) { ArrayDeque() }.addLast(entry)
        }
        val counts = hashMapOf<String, Int>()
        val insertions = ArrayList<OverlayInsertion>()
        // 与 applyOverlay 共用同一份“块外正文行”序列，锚点计数语义一致
        lyricBodyLines(lyric).forEach { (lineText, _) ->
            val key = subtitleKey(lineText)
            if (key.isEmpty()) return@forEach
            val queue = pendingByKey[key] ?: return@forEach
            val count = (counts[key] ?: 0) + 1
            counts[key] = count
            val entry = queue.removeFirstOrNull() ?: return@forEach
            insertions.add(
                OverlayInsertion(anchor = key, occurrence = count, payload = entry.payload)
            )
        }
        return insertions.takeIf { it.isNotEmpty() }
    }

    /**
     * 把 overlay 动态合并到 lyric：按“锚点 + 第几次出现”在对应字幕行后
     * 插入 payload 块；已有 usehtml 块原样保留。纯函数、幂等。
     */
    internal fun applyOverlay(lyric: String, overlayJson: String): String {
        val insertions = parseOverlay(overlayJson)
        if (insertions.isEmpty()) return lyric
        val pendingByKey = linkedMapOf<String, ArrayDeque<OverlayInsertion>>()
        insertions.sortedBy { it.occurrence }.forEach { insertion ->
            pendingByKey.getOrPut(insertion.anchor) { ArrayDeque() }.addLast(insertion)
        }
        val counts = hashMapOf<String, Int>()
        val builder = StringBuilder(lyric.length + 256)
        fun rebuildSegment(segment: String) {
            var lineStart = 0
            while (lineStart <= segment.length) {
                val newLineIndex = segment.indexOf('\n', lineStart)
                val (lineText, hasFollowingNewLine) = if (newLineIndex < 0 || newLineIndex >= segment.length) {
                    segment.substring(lineStart) to false
                } else {
                    segment.substring(lineStart, newLineIndex) to true
                }
                builder.append(lineText)
                var inserted = false
                val key = subtitleKey(lineText)
                val queue = if (key.isEmpty()) null else pendingByKey[key]
                if (queue != null) {
                    val count = (counts[key] ?: 0) + 1
                    counts[key] = count
                    val head = queue.firstOrNull()
                    if (head != null && head.occurrence == count) {
                        queue.removeFirst()
                        builder.append('\n').append(head.payload).append('\n')
                        inserted = true
                    }
                }
                if (!inserted && hasFollowingNewLine) {
                    builder.append('\n')
                }
                if (!hasFollowingNewLine) break
                lineStart = newLineIndex + 1
                if (lineStart == segment.length && !segment.endsWith('\n')) break
            }
        }
        var lastEnd = 0
        // 已有 usehtml 结构块原样保留；只在其外的字幕行后插入新块
        AppPattern.useHtmlRegex.findAll(lyric).forEach { blockMatch ->
            rebuildSegment(lyric.substring(lastEnd, blockMatch.range.first))
            builder.append(blockMatch.value)
            lastEnd = blockMatch.range.last + 1
        }
        rebuildSegment(lyric.substring(lastEnd))
        return builder.toString()
    }

    /**
     * 块外正文行序列：以 usehtml 块为界切分 lyric，块内行不展开。
     * 融合与 overlay 应用共用，保证“第几次出现”计数口径一致。
     */
    private fun lyricBodyLines(lyric: String): List<Pair<String, Boolean>> {
        val lines = ArrayList<Pair<String, Boolean>>()
        var lastEnd = 0
        AppPattern.useHtmlRegex.findAll(lyric).forEach { blockMatch ->
            collectSegmentLines(lines, lyric.substring(lastEnd, blockMatch.range.first))
            lastEnd = blockMatch.range.last + 1
        }
        collectSegmentLines(lines, lyric.substring(lastEnd))
        return lines
    }

    private fun collectSegmentLines(out: MutableList<Pair<String, Boolean>>, segment: String) {
        var lineStart = 0
        while (lineStart <= segment.length) {
            val newLineIndex = segment.indexOf('\n', lineStart)
            if (newLineIndex < 0 || newLineIndex >= segment.length) {
                out.add(segment.substring(lineStart) to false)
                break
            }
            out.add(segment.substring(lineStart, newLineIndex) to true)
            lineStart = newLineIndex + 1
            if (lineStart == segment.length && !segment.endsWith('\n')) break
        }
    }

    /**
     * 解析 Text 章节正文：按 usehtml 块切分原文，每个非空正文行收集为
     * 候选段落（归一化 key + 评论入口载荷 + 原始行尾 offset）；
     * 只有按原始 offset 紧随段落（其间只允许段落行换行，空行即断开）的
     * usehtml 块才归属该段落，避免章节级装饰块被误挂；无归属的块丢弃。
     * 只返回真正携带评论入口、且文字可参与匹配的段落。
     */
    internal fun parseCommentParagraphs(content: String): List<FusionEntry> {
        val paragraphs = ArrayList<FusionEntry>()
        fun appendBodyLines(part: String, partStart: Int) {
            var lineStart = 0
            splitRawLines(part).forEach { rawLine ->
                val lineEndAbs = partStart + lineStart + rawLine.length
                lineStart += rawLine.length + 1
                val line = rawLine.trim { it <= ' ' || it == '\u00A0' }
                if (line.isEmpty()) return@forEach
                val (textWithoutButtons, buttons) = splitInlineCommentButtons(line)
                val key = normalizeKey(textWithoutButtons)
                if (key.isEmpty() && buttons.isEmpty()) return@forEach
                paragraphs.add(FusionEntry(key, joinButtonPayload(buttons), lineEndAbs))
            }
        }
        var lastEnd = 0
        AppPattern.useHtmlRegex.findAll(content).forEach { blockMatch ->
            appendBodyLines(content.substring(lastEnd, blockMatch.range.first), lastEnd)
            // usehtml 块仅归属按原始 offset 紧随其前的正文段落
            paragraphs.lastOrNull()?.let { previous ->
                if (isDirectlyAfter(content, previous.endOffset, blockMatch.range.first)) {
                    val block = blockMatch.value.trim()
                    previous.payload += if (previous.payload.isEmpty()) block else "\n$block"
                }
            }
            lastEnd = blockMatch.range.last + 1
        }
        appendBodyLines(content.substring(lastEnd), lastEnd)
        return paragraphs.filter { it.key.isNotEmpty() && it.payload.isNotEmpty() }
    }

    /** 只能与段落行尾之间隔它的换行（允许行尾空白/CRLF），出现空行即断开 */
    private fun isDirectlyAfter(content: String, from: Int, to: Int): Boolean {
        if (from > to) return false
        val between = content.substring(from, to)
        if (between.isBlank()) {
            // 空串（同行结尾）或纯空白：仍需确认没有第二个换行符
            return between.count { it == '\n' } <= 1
        }
        return false
    }

    /**
     * 把一行正文拆成“纯文字”和“评论小图”：与排版层共用 [AppPattern.imgPattern]
     * 解析口径（含内嵌引号选项 JSON 的特殊分组）；TEXT 样式的行内图片是段落
     * 评论泡，其余 img（插图等）既不迁移也从匹配键中剔除，避免 URL 字母干扰。
     */
    internal fun splitInlineCommentButtons(line: String): Pair<String, List<String>> {
        if (!line.contains("<img", ignoreCase = true)) {
            return line to emptyList()
        }
        val buttons = ArrayList<String>(1)
        val text = StringBuilder(line.length)
        var lastEnd = 0
        val matcher = AppPattern.imgPattern.matcher(line)
        while (matcher.find()) {
            text.append(line, lastEnd, matcher.start())
            lastEnd = matcher.end()
            if (isReviewButton(matcher.group(1))) {
                buttons.add(matcher.group())
            }
        }
        text.append(line, lastEnd, line.length)
        return text.toString() to buttons
    }

    /** 评论泡判定：src 携带的选项 JSON 里 style 为 TEXT（与排版层同一约定） */
    private fun isReviewButton(src: String?): Boolean {
        if (src.isNullOrEmpty()) return false
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

    /** 一个待迁移段落：归一化文字 key、usehtml 载荷（解析期可追加）、原始行尾 offset */
    internal data class FusionEntry(
        val key: String,
        var payload: String,
        val endOffset: Int,
    )
}