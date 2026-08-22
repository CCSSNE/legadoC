package io.legado.app.help.book

import com.google.gson.reflect.TypeToken
import io.legado.app.constant.AppLog
import io.legado.app.constant.AppPattern
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.utils.GSON
import io.legado.app.utils.StringUtils
import io.legado.app.utils.fromJsonObject

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
 * 重新融合按整本书 reconcile：先只读计算整本书的期望 overlay 并生成写入
 * 计划，再在一个 Room 事务里一次性提交；本次不应再有 overlay 的章节会
 * 清除旧数据，计算阶段任何失败都不会把书改成半新半旧状态。
 *
 * 两层匹配：
 * - 第一层章节匹配：标题归一化相等做高置信度锚点；未命中章节的章节号
 *   fallback（中文/阿拉伯数字统一解析）只在“相邻锚点划分的局部区间内、
 *   卷信息一致”时生效，最后做邻章一致性验证——按正文顺序 Audio 章序号
 *   必须严格递增，违反者直接丢弃。低置信度直接跳过，宁可少融合也不串卷。
 * - 第二层段落匹配：Text 全部有效正文段落都参与“第几次出现”计数，没有
 *   评论的段落只占位置不生成挂载；挂载按“字幕锚点 + 第几次出现”记录，
 *   音频源更新字幕文本后，不再匹配的锚点自动不挂载。
 *
 * 迁移对象不是 Text 正文，而是段落关联的评论入口：段落内 TEXT 样式
 * 评论小图（`<img src="…,{…}">`，与排版层共用 [AppPattern.imgPattern]
 * 解析口径；URL 选项 JSON 分隔共用 [AppPattern.urlOptionPattern]）与
 * 紧随段落（按原始 offset 邻接，空行即断开）的 `<usehtml>…</usehtml>` 块。
 */
object AudioTextFusion {

    /** 融合 overlay 在章节 variable 中的键；原始 lyric 保持不变 */
    const val OVERLAY_KEY = "audioTextFusion"

    /** 融合汇总：配对章节数、本次写入 overlay 的音频章节数、挂载的评论入口数 */
    data class Result(
        val pairedChapters: Int,
        val fusedChapters: Int,
        val migratedEntries: Int,
        /** 一次融合的多行诊断日志（章节配对、每章匹配成功/失败、统计）；空串表示无 */
        val detail: String = "",
    ) {
        val migratedAnything: Boolean get() = migratedEntries > 0
    }

    /**
     * 一条评论挂载：挂在 lyric 中第 [occurrence] 次出现（从 1 起）的
     * [anchor] 匹配正文行之后；[payload] 为完整 `<usehtml>…</usehtml>` 块。
     *
     * [textBookUrl]/[textChapterUrl] 记录评论按钮的来源（文字书章节）：
     * 点击与评论快照都按文字书上下文执行，不使用有声书上下文。旧数据
     * 无该字段时为 null，点击回退当前阅读上下文。
     */
    data class OverlayInsertion(
        val anchor: String,
        val occurrence: Int,
        val payload: String,
        val textBookUrl: String? = null,
        val textChapterUrl: String? = null,
    )

    /** 对单个音频章节的写入动作；[insertions] 为 null 表示清除 overlay */
    data class ChapterOverlayWrite(
        val chapter: BookChapter,
        val insertions: List<OverlayInsertion>?,
    )

    /** 整本书融合计划：配对章数、写入动作列表与逐章诊断行（先计算后写入） */
    data class FusionPlan(
        val pairedChapters: Int,
        val writes: List<ChapterOverlayWrite>,
        /** 逐章诊断行：配对 → 每章匹配成功/失败原因；未配对只记总数，不逐条 */
        val details: List<String> = emptyList(),
    )

    /**
     * 遍历两本书配对的已缓存章节并执行整本书 reconcile。调用方需在 IO
     * 线程执行；本函数只读缓存文件与数据库，不发起网络请求。
     * 计算阶段失败时直接抛出，数据库保持原状；写入阶段在一个 Room 事务
     * 内一次性提交。
     */
    fun fuseBooks(textBook: Book, audioBook: Book): Result {
        val textChapters = appDb.bookChapterDao.getChapterList(textBook.bookUrl)
            .filterNot { it.isVolume }
        val audioChapters = appDb.bookChapterDao.getChapterList(audioBook.bookUrl)
            .filterNot { it.isVolume }
        val plan = planFusionWrites(
            textChapters = textChapters,
            audioChapters = audioChapters,
            textBookUrl = textBook.bookUrl,
            hasTextContent = { BookHelp.hasContent(textBook, it) },
            getTextContent = { BookHelp.getContent(textBook, it) },
            hasAudioContent = { BookHelp.hasContent(audioBook, it) },
            getLyric = { it.getVariable("lyric") },
            getCurrentOverlay = { it.getVariable(OVERLAY_KEY) },
        )
        if (plan.writes.isNotEmpty()) {
            appDb.runInTransaction {
                plan.writes.forEach { write ->
                    if (write.insertions == null) {
                        write.chapter.putVariable(OVERLAY_KEY, null)
                        write.chapter.update()
                    } else {
                        saveOverlay(write.chapter, write.insertions)
                    }
                }
            }
        }
        val fusedChapters = plan.writes.count { it.insertions != null }
        val migratedEntries = plan.writes.sumOf { it.insertions?.size ?: 0 }
        // 一次融合只产出一条多行诊断：章节配对、每章匹配成功/失败、最终统计
        val detail = buildString {
            appendLine("融合 ${textBook.name} ↔ ${audioBook.name}")
            appendLine("章节配对 ${plan.pairedChapters} 章，挂载 $fusedChapters 章，迁移 $migratedEntries 个评论入口")
            if (plan.details.isEmpty()) {
                appendLine("  （无已配对章节）")
            }
            plan.details.forEach { appendLine(it) }
        }
        return Result(
            pairedChapters = plan.pairedChapters,
            fusedChapters = fusedChapters,
            migratedEntries = migratedEntries,
            detail = detail,
        )
    }

    /**
     * 整本书 reconcile 的纯计算：先只读算出每章期望 overlay，再生成
     * “覆盖保存 / 清除”写入计划。
     *
     * 对每个配对章节：
     * - 文字/音频缓存或 lyric、正文无法读取 → 无法确认，保持旧 overlay
     *   （不产生任何写入动作），绝不当作“评论已删除”清除；
     * - 缓存齐全且 fus 后确认无评论入口 → 生成清除动作。
     * 本次不应再有 overlay 但旧数据存在的章节才会被清。
     *
     * 纯函数，可在 JVM 单元测试中直接验证。
     */
    internal fun planFusionWrites(
        textChapters: List<BookChapter>,
        audioChapters: List<BookChapter>,
        textBookUrl: String,
        hasTextContent: (BookChapter) -> Boolean,
        getTextContent: (BookChapter) -> String?,
        hasAudioContent: (BookChapter) -> Boolean,
        getLyric: (BookChapter) -> String,
        getCurrentOverlay: (BookChapter) -> String,
    ): FusionPlan {
        val pairs = pairChapters(textChapters, audioChapters)
        // identity -> 期望 overlay；null 表示确认无评论（清除），key 缺失表示保持
        val desired = linkedMapOf<String, List<OverlayInsertion>?>()
        val details = ArrayList<String>()
        pairs.forEach { (textChapter, audioChapter) ->
            val pairLabel = "${textChapter.title.take(28)} ↔ ${audioChapter.title.take(28)}"
            // 无法确认的章节一律保持旧 overlay，不清除；原因逐行记录
            if (!hasTextContent(textChapter)) {
                details.add("  $pairLabel → 跳过：文字缓存缺失")
                return@forEach
            }
            if (!hasAudioContent(audioChapter)) {
                details.add("  $pairLabel → 跳过：音频缓存缺失")
                return@forEach
            }
            val lyric = getLyric(audioChapter)
            if (lyric.isBlank()) {
                details.add("  $pairLabel → 跳过：字幕(lyric)为空")
                return@forEach
            }
            val textContent = getTextContent(textChapter)
            if (textContent == null) {
                details.add("  $pairLabel → 跳过：正文读取失败")
                return@forEach
            }
            // 为 null 即“确认当前无评论入口”，生成清除
            val (insertions, matches) = fuseOverlayDetailed(textContent, lyric)
            when {
                matches.isEmpty() ->
                    details.add("  $pairLabel → 本章无评论")

                insertions == null -> {
                    details.add("  $pairLabel → 有 ${matches.size} 个评论但匹配 0 个")
                    matches.forEach { details.add("      × ${it.text.take(40)}") }
                }

                else -> {
                    val matched = matches.count { it.matched }
                    val unmatched = matches.size - matched
                    details.add(
                        "  $pairLabel → 评论段落 ${matches.size} 段，匹配 $matched 个，未匹配 $unmatched 段"
                    )
                    matches.forEach { match ->
                        details.add(
                            "      ${if (match.matched) "✓" else "×"} ${match.text.take(40)}"
                        )
                    }
                }
            }
            desired[audioChapter.primaryStr()] = insertions?.map {
                it.copy(textBookUrl = textBookUrl, textChapterUrl = textChapter.url)
            }
        }
        val unmatchedText = (textChapters.size - pairs.size).coerceAtLeast(0)
        val unmatchedAudio = (audioChapters.size - pairs.size).coerceAtLeast(0)
        if (unmatchedText > 0 || unmatchedAudio > 0) {
            details.add(
                "  未配对：文字 $unmatchedText 章、音频 $unmatchedAudio 章（标题/章节号无匹配，不参与段落匹配）"
            )
        }
        val writes = buildList {
            audioChapters.forEach { audioChapter ->
                val identity = audioChapter.primaryStr()
                val want = desired[identity]
                val hasOld = getCurrentOverlay(audioChapter).isNotBlank()
                when {
                    want != null -> add(ChapterOverlayWrite(audioChapter, want))
                    desired.containsKey(identity) && hasOld ->
                        add(ChapterOverlayWrite(audioChapter, null))
                    // key 缺失（无法确认）且有旧数据：保持，不产生写入
                }
            }
        }
        return FusionPlan(
            pairedChapters = pairs.size,
            writes = writes,
            details = details,
        )
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
     * 1. 标题归一化相等（高置信度锚点，重复标题按出现顺序消费）；
     * 2. 章节号相等（中文/阿拉伯数字统一解析）fallback：只取相邻锚点划分
     *    的局部区间内的未配对音频章节，且卷信息一致（两侧都能解析时）；
     *    没有相邻锚点约束时退化为“同卷 + 邻章一致性”约束；
     * 3. 邻章一致性验证：按正文顺序 Audio 章序号严格递增，违反者丢弃。
     * 不做同 index 兜底，宁可少融合也不能错融合。
     */
    internal fun pairChapters(
        textChapters: List<BookChapter>,
        audioChapters: List<BookChapter>
    ): List<Pair<BookChapter, BookChapter>> {
        val anchors = ArrayList<Pair<BookChapter, BookChapter>>()
        val usedAudio = hashSetOf<BookChapter>()
        val pendingText = ArrayList<BookChapter>()

        // 1. 标题归一化相等（高置信度锚点）
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
                anchors.add(chapter to matched)
            }
        }

        // 2. 章节号 fallback：只允许在相邻锚点划定的局部区间内、卷一致时配对
        val fallbackPairs = ArrayList<Pair<BookChapter, BookChapter>>()
        if (pendingText.isNotEmpty()) {
            val orderedAnchors = anchors.sortedBy { it.first.index }
            val unmatchedAudio = audioChapters
                .filterNot { it in usedAudio }
                .toMutableList()
            for (textChapter in pendingText.sortedBy { it.index }) {
                val num = ChapterTitle.num(textChapter.title)
                if (num < 0) continue
                val volume = ChapterTitle.volume(textChapter.title)
                val previousAnchor = orderedAnchors.asReversed()
                    .firstOrNull { it.first.index < textChapter.index }
                val nextAnchor = orderedAnchors
                    .firstOrNull { it.first.index > textChapter.index }
                val audioLo = previousAnchor?.second?.index ?: Int.MIN_VALUE
                val audioHi = nextAnchor?.second?.index ?: Int.MAX_VALUE
                val candidate = unmatchedAudio.firstOrNull { audio ->
                    audio.index > audioLo && audio.index < audioHi &&
                        ChapterTitle.num(audio.title) == num &&
                        volumeCompatible(volume, ChapterTitle.volume(audio.title))
                }
                if (candidate != null) {
                    unmatchedAudio.remove(candidate)
                    usedAudio.add(candidate)
                    fallbackPairs.add(textChapter to candidate)
                }
            }
        }

        // 3. 邻章一致性：Audio 序号随正文顺序严格递增，违反者丢弃
        val allPairs = anchors + fallbackPairs
        var lastAudioIndex = -1
        return allPairs.sortedBy { it.first.index }.filter { (_, audio) ->
            if (audio.index > lastAudioIndex) {
                lastAudioIndex = audio.index
                true
            } else {
                false
            }
        }
    }

    /**
     * 卷兼容：两边都无卷号时允许 fallback；只有一边有卷号时跳过；
     * 两边都有卷号时要求一致（宁可少融合）。
     */
    private fun volumeCompatible(textVolume: Int?, audioVolume: Int?): Boolean {
        return textVolume == audioVolume
    }

    /**
     * 从某章节的融合 overlay 反查评论按钮的文字书来源。
     * 点击/评论快照按文字书上下文执行时使用；返回 null 表示该按钮
     * 不是融合挂载（或旧数据无来源字段），调用方回退当前阅读上下文。
     */
    internal fun findFusionTextContext(
        overlayJson: String,
        src: String
    ): Pair<String, String>? {
        if (overlayJson.isBlank()) return null
        return parseOverlay(overlayJson).firstNotNullOfOrNull { insertion ->
            if (insertion.textBookUrl != null &&
                insertion.textChapterUrl != null &&
                insertion.payload.contains(src)
            ) {
                insertion.textBookUrl to insertion.textChapterUrl
            } else {
                null
            }
        }
    }

    /**
     * 第二层匹配：从文字书章节正文提取全部有效正文段落（无评论段落只占
     * “第几次出现”的位置），计算与 lyric 块外正文行的锚点挂载列表。
     * 无任何挂载时返回 null。lyric 保持不变。
     */
    internal fun fuseOverlay(textContent: String, lyric: String): List<OverlayInsertion>? {
        return fuseOverlayDetailed(textContent, lyric).first
    }

    /** 诊断用：一条评论段落原文及其是否在音频字幕中匹配到锚点（只读统计，不参与匹配） */
    internal data class ParagraphMatch(
        val text: String,
        val matched: Boolean,
    )

    /**
     * 与 [fuseOverlay] 同一匹配实现（单一来源），额外返回逐条评论段落的
     * 匹配明细供诊断日志使用：返回 (挂载列表, 按原文顺序的段落匹配列表)。
     * matches 为空表示本章没有评论段落；matches 非空但挂载列表为 null
     * 表示“有评论但 0 匹配”，由调用方在日志中区分。
     */
    internal fun fuseOverlayDetailed(
        textContent: String,
        lyric: String
    ): Pair<List<OverlayInsertion>?, List<ParagraphMatch>> {
        val entries = parseCommentParagraphs(textContent)
        if (entries.isEmpty()) return null to emptyList()
        val pendingByKey = linkedMapOf<String, ArrayDeque<FusionEntry>>()
        entries.forEach { entry ->
            pendingByKey.getOrPut(entry.key) { ArrayDeque() }.addLast(entry)
        }
        // 按原文顺序的所有评论段落（有载荷）
        val commentEntries = entries.filter { it.payload.isNotEmpty() }
        val matchedEntries = hashSetOf<FusionEntry>()
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
            // 无评论段落只占位置，不生成挂载
            if (entry.payload.isNotEmpty()) {
                matchedEntries.add(entry)
                insertions.add(
                    OverlayInsertion(anchor = key, occurrence = count, payload = entry.payload)
                )
            }
        }
        val matches = commentEntries.map { ParagraphMatch(it.text, it in matchedEntries) }
        return insertions.takeIf { it.isNotEmpty() } to matches
    }

    /**
     * 把 overlay 动态合并到 lyric：按“锚点 + 第几次出现”在对应字幕行后
     * 插入 payload 块；已有 usehtml 块原样保留。纯函数。
     *
     * 输入约定为原始存储的 lyric。同时实现同位置幂等：若原始 lyric 中
     * 该字幕行之后已经是同一 payload（例如旧版本直写或重复应用），则
     * 跳过插入并消费该挂载，不会产生二份副本。
     */
    internal fun applyOverlay(rawLyric: String, overlayJson: String): String {
        val insertions = parseOverlay(overlayJson)
        if (insertions.isEmpty()) return rawLyric
        val pendingByKey = linkedMapOf<String, ArrayDeque<OverlayInsertion>>()
        insertions.sortedBy { it.occurrence }.forEach { insertion ->
            pendingByKey.getOrPut(insertion.anchor) { ArrayDeque() }.addLast(insertion)
        }
        val counts = hashMapOf<String, Int>()
        val builder = StringBuilder(rawLyric.length + 256)
        fun rebuildSegment(segment: String, segmentStartInLyric: Int) {
            var lineStart = 0
            while (lineStart <= segment.length) {
                val newLineIndex = segment.indexOf('\n', lineStart)
                val atEnd = newLineIndex < 0 || newLineIndex >= segment.length
                val lineEnd = if (atEnd) segment.length else newLineIndex
                val lineText = segment.substring(lineStart, lineEnd)
                val hasTrailingNewLine = !atEnd
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
                        val lineAbsEnd = segmentStartInLyric + lineEnd
                        // 同位置幂等：该行之后（紧接换行 + payload）已是同一挂载则跳过
                        if (!rawLyric.startsWith("\n" + head.payload, lineAbsEnd)) {
                            builder.append('\n').append(head.payload).append('\n')
                            inserted = true
                        }
                    }
                }
                if (!inserted && hasTrailingNewLine) {
                    builder.append('\n')
                }
                if (atEnd) break
                lineStart = newLineIndex + 1
                if (lineStart == segment.length && !segment.endsWith('\n')) break
            }
        }
        var lastEnd = 0
        // 已有 usehtml 结构块原样保留；只在其外的字幕行后插入新块
        AppPattern.useHtmlRegex.findAll(rawLyric).forEach { blockMatch ->
            rebuildSegment(rawLyric.substring(lastEnd, blockMatch.range.first), lastEnd)
            builder.append(blockMatch.value)
            lastEnd = blockMatch.range.last + 1
        }
        rebuildSegment(rawLyric.substring(lastEnd), lastEnd)
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
     * 候选段落（归一化 key + 评论入口载荷 + 原始行尾 offset）。所有有效
     * 正文段落都会被收集（无载荷段落参与后续 occurrence 占位）；只有按
     * 原始 offset 紧随段落（其间只允许段落行换行，空行即断开）的 usehtml
     * 块才归属该段落，避免章节级装饰块被误挂；无归属的块丢弃。
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
                paragraphs.add(
                    FusionEntry(key, textWithoutButtons, joinButtonPayload(buttons), lineEndAbs)
                )
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
        return paragraphs.filter { it.key.isNotEmpty() }
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

    /**
     * 评论泡判定：src 携带的选项 JSON 里 style 为 TEXT（与排版层同一约定）。
     * 选项 JSON 用 [AppPattern.urlOptionPattern] 定位起点后交给 [GSON]
     * lenient 解析（书源常用单引号 JSON，如 `{'click':'…','style':'TEXT'}`，
     * 与阅读页渲染、BookImgClick.parseSrcOptions 完全同一口径）。
     */
    private fun isReviewButton(src: String?): Boolean {
        if (src.isNullOrEmpty()) return false
        val optionMatcher = AppPattern.urlOptionPattern.matcher(src)
        if (!optionMatcher.find()) return false
        val optionJson = src.substring(optionMatcher.end())
        val options = GSON.fromJsonObject<Map<String, String>>(optionJson).getOrNull() ?: return false
        return options["style"].equals("TEXT", ignoreCase = true)
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

    /** 一个待迁移段落：归一化文字 key、原始段落文字（去评论图后，供诊断日志）、
     *  usehtml 载荷（解析期可追加）、原始行尾 offset */
    internal data class FusionEntry(
        val key: String,
        val text: String,
        var payload: String,
        val endOffset: Int,
    )
}