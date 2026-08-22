package io.legado.app.help.review

import io.legado.app.constant.AppLog
import io.legado.app.constant.AppPattern
import io.legado.app.constant.BookType
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.BookImgClick
import io.legado.app.help.book.isLocal
import io.legado.app.help.config.AppConfig
import io.legado.app.help.http.StrResponse
import io.legado.app.model.CacheBook
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.service.ReviewCacheService
import io.legado.app.ui.login.SourceLoginJsExtensions
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.mapAsyncIndexed
import io.legado.app.utils.startService
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.update
import splitties.init.appCtx
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * 评论快照抓取调度器（登记端）。
 *
 * 正文永远优先：批量缓存分两阶段——
 * - Body Phase：只下载正文，评论任务只登记（pendingForce），绝不开 WebView 抓评论；
 * - Review Phase：整批目标正文结束后（[endBodyPhase]）把登记任务放入队列，
 *   由独立前台 [io.legado.app.service.ReviewCacheService] 低优先级并发抓取，
 *   评论进度不算正文下载进度。
 * 阅读页单章下载（不走批量）正文完全结束后直接登记并启动评论服务。
 *
 * 快照刷新语义：
 * - 普通后台重复触发（force=false）：已有快照可跳过；
 * - 用户明确重新缓存/刷新该章节（force=true 或 [markUserRefresh] 持久标记）：
 *   重新抓取并覆盖旧快照，不依赖 has()。
 */
object ReviewSnapshotManager {

    /** 对外任务（key = bookUrl|chapterIndex；force 为消费时聚合值） */
    data class QueueTask internal constructor(
        val key: String,
        val force: Boolean
    )

    /**
     * 评论快照同步进度（供 ReviewCacheService 通知展示）。
     * 并发 worker 下不存在“当前章”概念，进度一律按实际完成量聚合：
     * - 进度条 = 评论任务完成章数/目标章数（completedChapters/totalChapters），只增不减；
     * - “评论快照 c/d” = 已完成快照数/已登记需处理快照总数（跨并发章累计），c 只增不减。
     */
    data class ReviewSyncState(
        val bookUrl: String = "",
        val bookName: String = "",
        /** 正文目标进度（本次批量目标章数），通知“正文 x/y”用 */
        val bodyDone: Int = 0,
        val bodyTotal: Int = 0,
        /** 本次会话入队的评论任务总数（目标章数），通知“评论 a/b”用 */
        val totalChapters: Int = 0,
        val completedChapters: Int = 0,
        /** 已登记需处理评论快照总数（章节开始解析按钮时累加），通知“评论快照 c/d”用 */
        val totalSnapshots: Int = 0,
        /** 已完成评论快照数（跨并发章原子累计，只增不减，按实际下载量） */
        val completedSnapshots: Int = 0
    )

    private val _syncState = MutableStateFlow(ReviewSyncState())
    val syncState: StateFlow<ReviewSyncState> = _syncState.asStateFlow()

    /**
     * 进程内存计数：bookUrl → 有评论快照的章 url 集合。
     * 用于通知/缓存页显示的“评论 x/y”，只统计真实存在至少一个有效快照的章。
     */
    private val cachedReviewChaptersMap = ConcurrentHashMap<String, MutableSet<String>>()

    /**
     * 该书有评论快照的章数（内存计数，缺失时惰性扫描文件补齐）。
     */
    fun cachedReviewChapterCount(book: Book): Int {
        return cachedReviewChaptersMap.computeIfAbsent(book.bookUrl) {
            ReviewSnapshotStore.listAll(book)
                .asSequence()
                .map { it.chapterUrl }
                .filter { it.isNotBlank() }
                .toCollection(mutableSetOf())
        }.size
    }

    private data class Task(val key: String)

    /** 评论按钮模型：click / 旧源 js 二选一 */
    data class ReviewButton(
        val src: String,
        val click: String? = null,
        val js: String? = null,
        val urlNoOption: String? = null
    ) {
        val hasAction: Boolean get() = !click.isNullOrBlank() || !js.isNullOrBlank()
    }

    private val channel = Channel<Task>(Channel.UNLIMITED)

    /** 批量缓存进行中的书（Body Phase）：该书已登记任务只入 pendingForce，暂不执行 */
    private val bodyPhaseBooks = ConcurrentHashMap.newKeySet<String>()

    /**
     * 待处理任务的 force 聚合表（key = bookUrl|chapterIndex）。
     * 负责两件事：
     * - 去重：已存在代表该章已在队列中，不重复入队；
     * - force 合并：后入队的 force=true 必须提升已排队任务的 force，
     *   绝不允许先 false 后 true 时把 true 丢掉。
     * 入队与消费的读取/删除都持有 [queueLock]，消除 putIfAbsent→replace 与
     * remove(key) 之间的并发窗口。
     */
    private val pendingForce = ConcurrentHashMap<String, Boolean>()
    /** 已被 worker 取出但尚未完成的任务，不能只看 pendingForce。 */
    private val activeTaskKeys = ConcurrentHashMap.newKeySet<String>()
    private val queueLock = Any()

    /**
     * “评论待刷新”持久标记（key = bookUrl|chapterIndex）。
     * 用户明确要求刷新的章节加入；不设时间 TTL，并且落盘保存——
     * App 重启后依然存在。只有该章所有需要刷新的评论按钮全部真正处理成功
     * （processTask 整体成功）后才清除；任何按钮失败都保留，等待重新
     * 缓存/刷新时继续重试。因此“刷新当前之后”隔很久才读到的章节，
     * force 依然有效。
     */
    private val refreshPending = ConcurrentHashMap.newKeySet<String>()

    /**
     * 持久化文件：filesDir/review_refresh_pending.json（应用私有目录，
     * 不受 book_cache 清理影响）；保存 bookUrl|chapterIndex 列表。
     */
    private val refreshPendingFile by lazy {
        File(appCtx.filesDir, "review_refresh_pending.json")
    }
    @Volatile
    private var refreshPendingLoaded = false

    /** 单个按钮解析评论页地址的超时 */
    private const val RESOLVE_TIMEOUT_MS = 20_000L

    /**
     * 正文保存/成功状态结束后调用。
     * @param force true = 用户明确重新缓存/刷新该章节，忽略旧快照直接重抓
     */
    fun enqueueIfEnabled(
        bookSource: BookSource?,
        book: Book,
        chapter: BookChapter,
        force: Boolean = false
    ) {
        if (!AppConfig.syncCacheReview) return
        if (book.isLocal) return
        if (bookSource == null) return
        enqueue(book, chapter, force)
    }

    /**
     * 已缓存正文的章节在重新缓存/刷新时也必须走“是否需要补评论”的判断，
     * 因此缓存流程在跳过正文下载前调用本方法直接入队。
     */
    fun enqueue(book: Book, chapter: BookChapter, force: Boolean = false) {
        var shouldStartService = false
        refreshBodyState(book)
        synchronized(queueLock) {
            val existed = pendingForce.putIfAbsent(keyOf(book, chapter), force)
            if (existed == null) {
                if (bodyPhaseBooks.contains(book.bookUrl)) {
                    // Body Phase：只登记待抓任务，绝不在批量正文结束前启动 WebView 抓取
                } else {
                    channel.trySend(Task(keyOf(book, chapter)))
                    _syncState.update { it.copy(totalChapters = it.totalChapters + 1) }
                    shouldStartService = true
                }
            } else if (force && existed != true) {
                // force 合并：已排队任务升级为强制重抓，不丢 true
                pendingForce.replace(keyOf(book, chapter), existed, true)
            }
        }
        if (shouldStartService) {
            ReviewCacheService.startSelf()
        }
    }

    /** 同步正文目标进度到通知状态（正文通知“正文 x/y”用） */
    private fun refreshBodyState(book: Book) {
        val model = CacheBook.cacheBookMap[book.bookUrl]
        val done = model?.bodyDone
            ?: BookHelp.getChapterFiles(book).count { it.endsWith(".nb") }
        val total = model?.bodyTarget ?: book.totalChapterNum
        _syncState.update { it.copy(bodyDone = done, bodyTotal = total) }
    }

    /** 批量缓存开始（Body Phase）：该书评论任务只登记不执行 */
    fun beginBodyPhase(bookUrl: String) {
        var newSession = false
        synchronized(queueLock) {
            if (bodyPhaseBooks.isEmpty() && pendingForce.isEmpty() && activeTaskKeys.isEmpty()) {
                _syncState.value = ReviewSyncState()
                newSession = true
            }
            bodyPhaseBooks.add(bookUrl)
        }
        if (newSession) {
            AppLog.put("评论缓存会话开始：$bookUrl")
        }
    }

    /** 整批目标正文结束后（Review Phase 开始）：该书登记的任务正式入队执行 */
    fun endBodyPhase(bookUrl: String) {
        if (!bodyPhaseBooks.remove(bookUrl)) return
        var sentAny = false
        synchronized(queueLock) {
            val book = appDb.bookDao.getBook(bookUrl)
            if (book != null) refreshBodyState(book)
            pendingForce.keys
                .filter { it.substringBefore('|') == bookUrl }
                .forEach { key ->
                    channel.trySend(Task(key))
                    _syncState.update { it.copy(totalChapters = it.totalChapters + 1) }
                    sentAny = true
                }
        }
        if (sentAny) {
            ReviewCacheService.startSelf()
        }
    }

    /**
     * 批量取消/异常等非正常结束：同样必须收掉 Body Phase，否则该书后续
     * 评论任务会一直“只登记不执行”。已登记任务（正文已完成）照常执行。
     * 幂等：与 [endBodyPhase] 同一收尾路径，重复调用无副作用。
     */
    fun cancelBodyPhase(bookUrl: String) {
        endBodyPhase(bookUrl)
    }

    /** 兜底收尾（CacheBook.close 等整体清理场景）：清掉所有残留 Body Phase */
    fun cancelAllBodyPhases() {
        bodyPhaseBooks.toList().forEach { endBodyPhase(it) }
    }

    /**
     * 批量循环发现队列空时的收尾：只有该书确实处于 Body Phase（活跃批量）才
     * 收——阅读页单章下载的 model 也会被批量流程窥见，不能误收/重复入队。
     */
    fun endBodyPhaseIfActive(bookUrl: String) {
        if (bodyPhaseBooks.contains(bookUrl)) {
            endBodyPhase(bookUrl)
        }
    }

    /** 评论服务消费：取一个任务（无任务返回 null）。force 取聚合值并原子移除 */
    fun tryTakeTask(): QueueTask? {
        val task = synchronized(queueLock) {
            val t = channel.tryReceive().getOrNull() ?: return null
            val force = pendingForce.remove(t.key) ?: false
            activeTaskKeys.add(t.key)
            t to force
        }
        return QueueTask(task.first.key, task.second)
    }

    fun hasPendingTasks(): Boolean = synchronized(queueLock) {
        pendingForce.isNotEmpty() || activeTaskKeys.isNotEmpty()
    }

    /** 用户明确停止时丢弃队列；正常正文收尾绝不能调用此方法。 */
    fun clearAllTasks() {
        var cleared = 0
        synchronized(queueLock) {
            cleared = pendingForce.size + activeTaskKeys.size
            pendingForce.clear()
            activeTaskKeys.clear()
            bodyPhaseBooks.clear()
            while (channel.tryReceive().isSuccess) {
                // Drain queued tasks before resetting the session counters.
            }
            _syncState.value = ReviewSyncState()
        }
        AppLog.put("评论缓存队列已清理：$cleared 个任务")
    }

    /** 评论服务处理任务（suspend：内部解析 URL、WebView 抓取、落盘） */
    suspend fun processTask(task: QueueTask) {
        try {
            processTask(task.key, task.force)
        } finally {
            activeTaskKeys.remove(task.key)
        }
    }

    private fun keyOf(book: Book, chapter: BookChapter) = "${book.bookUrl}|${chapter.index}"

    /** 用户明确刷新某章：登记“评论待刷新”并落盘，状态保持到该章评论真正处理成功后清除 */
    fun markUserRefresh(bookUrl: String, chapterIndex: Int) {
        ensureRefreshPendingLoaded()
        if (refreshPending.add("$bookUrl|$chapterIndex")) {
            persistRefreshPending()
        }
    }

    /** 该章是否处于“评论待刷新”状态（无 TTL，重启仍有效，直到真正处理成功） */
    fun isUserRefreshActive(bookUrl: String, chapterIndex: Int): Boolean {
        ensureRefreshPendingLoaded()
        return "$bookUrl|$chapterIndex" in refreshPending
    }

    /** 该章所有需要刷新的评论按钮已全部处理成功后，清除待刷新标记并落盘 */
    fun clearUserRefresh(bookUrl: String, chapterIndex: Int) {
        ensureRefreshPendingLoaded()
        if (refreshPending.remove("$bookUrl|$chapterIndex")) {
            persistRefreshPending()
        }
    }

    private fun ensureRefreshPendingLoaded() {
        if (refreshPendingLoaded) return
        synchronized(this) {
            if (refreshPendingLoaded) return
            runCatching {
                if (refreshPendingFile.isFile) {
                    val saved = GSON.fromJson(
                        refreshPendingFile.readText(),
                        Array<String>::class.java
                    ) ?: return@runCatching
                    refreshPending.addAll(saved)
                }
            }.onFailure {
                AppLog.put("读取评论待刷新标记失败\n${it.localizedMessage}", it)
            }
            refreshPendingLoaded = true
        }
    }

    private fun persistRefreshPending() {
        runCatching {
            refreshPendingFile.parentFile?.mkdirs()
            refreshPendingFile.writeText(GSON.toJson(refreshPending.toList()))
        }.onFailure {
            AppLog.put("保存评论待刷新标记失败\n${it.localizedMessage}", it)
        }
    }

    private suspend fun processTask(key: String, force: Boolean) {
        // 一章一次评论缓存任务 = 日志一条（多行详情），进入 AppLog 日志页可展开查看
        val sb = StringBuilder()
        val unexpected = runCatching {
            processTaskWithLog(key, force, sb)
        }.exceptionOrNull()
        if (unexpected != null) {
            sb.append("\n异常：").append(unexpected.stackTraceToString())
        }
        if (sb.isNotBlank()) {
            AppLog.put(sb.toString())
        }
    }

    private suspend fun processTaskWithLog(key: String, force: Boolean, sb: StringBuilder) {
        val bookUrl = key.substringBefore('|')
        val chapterIndex = key.substringAfter('|').toIntOrNull()
        val book = appDb.bookDao.getBook(bookUrl)
        if (chapterIndex == null || book == null) {
            sb.append("[评论缓存] 任务无法处理 bookUrl=$bookUrl chapterIndex=$chapterIndex\n找不到书籍")
            return
        }
        val bookSource = appDb.bookSourceDao.getBookSource(book.origin)
        if (bookSource == null) {
            sb.append("[评论缓存] 第").append(chapterIndex + 1).append("章 ").append(book.name)
                .append("\n书籍：").append(book.name).append("\n找不到书源 origin=").append(book.origin)
            return
        }
        val chapter = appDb.bookChapterDao.getChapter(bookUrl, chapterIndex)
        if (chapter == null) {
            sb.append("[评论缓存] ").append(book.name).append(" chapterIndex=").append(chapterIndex)
                .append("\n章节不存在")
            return
        }
        sb.append("[评论缓存] 第").append(chapter.index + 1).append("章 ").append(chapter.title).append('\n')
        sb.append("书籍：").append(book.name).append('\n')
        sb.append("章节URL：").append(chapter.url).append('\n')
        sb.append("force：").append(if (force) "true" else "false").append('\n')
        // 处理时再查一次开关：入队后关闭开关不应继续抓取
        if (!AppConfig.syncCacheReview) {
            sb.append("开关“缓存评论”已关闭，跳过")
            return
        }
        val content = BookHelp.getContent(book, chapter)
        if (content == null) {
            sb.append("1. 读取正文：失败（未找到缓存正文，可能正文未下载或已删除）")
            return
        }
        sb.append("1. 读取正文：成功\n")
        val extraction = extractReviewButtonsWithStats(content)
        sb.append("2. 找到 style=TEXT 评论按钮：").append(extraction.buttons.size).append(" 个")
        when {
            extraction.imgCount == 0 ->
                sb.append("（正文没有找到任何 <img> 标签：不是评论样式、或正文本身不含评论按钮）")
            extraction.optionParseFailed > 0 || extraction.nonTextStyle > 0 || extraction.noAction > 0 -> {
                sb.append("（共 ").append(extraction.imgCount).append(" 个 img；")
                if (extraction.optionParseFailed > 0) {
                    sb.append("src 选项 JSON 解析失败 ").append(extraction.optionParseFailed).append("，")
                }
                if (extraction.nonTextStyle > 0) {
                    sb.append("找到了 img 但 style 不是 TEXT ").append(extraction.nonTextStyle).append("，")
                }
                if (extraction.noAction > 0) {
                    sb.append("click/js 都为空 ").append(extraction.noAction).append("，")
                }
                sb.setLength(sb.length - 1)
                sb.append("）")
            }
        }
        sb.append('\n')
        val buttons = extraction.buttons
        val needProcess = buttons.filter {
            it.hasAction && (force || !ReviewSnapshotStore.has(book, chapter, it.src))
        }
        // 并发下无“当前章”概念：登记本章需处理的快照数，累加进“评论快照 c/d”的总数
        _syncState.update {
            it.copy(
                bookUrl = book.bookUrl,
                bookName = book.name,
                totalSnapshots = it.totalSnapshots + needProcess.size
            )
        }
        var completedButtons = 0
        var failedButtons = 0
        // force=true（用户明确刷新）时，任何一个需要刷新的按钮失败都保留待刷新标记，
        // 不能“一个成功就算整章成功”
        var hasFailure = false
        // 按钮级并发：本章提取到的全部评论按钮都进入统一队列，不人为截断数量。
        // 空章不进入 mapAsyncIndexed；这里保持正数以满足并发 API 的契约。
        val buttonConcurrency = AppConfig.reviewCacheConcurrency
            .coerceIn(1, buttons.size.coerceAtLeast(1))
        val outcomes = buttons
            .asFlow()
            .mapAsyncIndexed(buttonConcurrency) { index, button ->
                processButton(book, bookSource, chapter, index, button, force)
            }
            .toList()
            .sortedBy { it.index }
        outcomes.forEach { o ->
            sb.append(o.log)
            if (o.success) completedButtons++
            if (o.failed) {
                hasFailure = true
                failedButtons++
            }
        }
        // 该章所有需要刷新的评论按钮全部成功处理，才清除“评论待刷新”持久标记
        if (force && !hasFailure) {
            clearUserRefresh(bookUrl, chapterIndex)
        }
        // 本章结束：完成章数原子 +1（并发下不能读-改-写共享值，否则丢失完成计数）
        _syncState.update {
            it.copy(
                bookUrl = book.bookUrl,
                bookName = book.name,
                completedChapters = it.completedChapters + 1
            )
        }
        // 只有本章真实存在至少一个有效评论快照时，才计入“有评论快照的章数”
        // 并广播事件，避免抓失败的章虚增“评论 x/y”（事件载荷带 hasSnapshot）
        val chapterHasSnapshot = buttons.any { ReviewSnapshotStore.has(book, chapter, it.src) }
        if (chapterHasSnapshot) {
            cachedReviewChaptersMap.computeIfAbsent(bookUrl) { mutableSetOf() }.add(chapter.url)
            io.legado.app.utils.postEvent(
                io.legado.app.constant.EventBus.REVIEW_CACHE_SAVED,
                Triple(book.bookUrl, chapter.url, true)
            )
        }
        sb.append("8. 最终结果：\n")
        sb.append("   成功快照 ").append(completedButtons).append("/").append(needProcess.size).append('\n')
        sb.append("   失败 ").append(failedButtons).append("/").append(needProcess.size).append('\n')
        sb.append("   本章是否计入“评论已缓存”：").append(if (chapterHasSnapshot) "是" else "否")
    }

    /** 单按钮处理结果：日志按原序号归位，成功/失败供整章统计 */
    private data class ButtonOutcome(
        val index: Int,
        val log: String,
        val success: Boolean = false,
        val failed: Boolean = false
    )

    /**
     * 处理单个评论按钮：解析真实评论页 URL → 抓取快照 → 落盘。
     * 与旧的串行循环体逐步骤等价，仅去掉按钮间强制等待；
     * 多个按钮可跨 [processButton] 并发执行（WebView 池按需扩容）。
     */
    private suspend fun processButton(
        book: Book,
        bookSource: BookSource,
        chapter: BookChapter,
        buttonIndex: Int,
        button: ReviewButton,
        force: Boolean
    ): ButtonOutcome {
        if (!button.hasAction) return ButtonOutcome(buttonIndex, "")
        val sb = StringBuilder()
        // 普通触发有快照可跳过；force（明确重新缓存/刷新）必须重抓覆盖
        if (!force && ReviewSnapshotStore.has(book, chapter, button.src)) {
            sb.append("3. 按钮").append(buttonIndex + 1).append("：src=").append(button.src)
                .append("\n   已有有效快照，跳过\n")
            return ButtonOutcome(buttonIndex, sb.toString())
        }
        sb.append("3. 按钮").append(buttonIndex + 1).append("：\n")
        sb.append("   src=").append(button.src).append('\n')
        sb.append("   click=").append(if (button.click.isNullOrBlank()) "无" else "有")
            .append("  js=").append(if (button.js.isNullOrBlank()) "无" else "有").append('\n')
        // 计数拆分：抛异常记一次并 continue；无异常但 URL 为空才记 browser open 超时
        val resolveResult = runCatching {
            resolveReviewPageUrl(book, bookSource, chapter, button)
        }
        if (resolveResult.isFailure) {
            sb.append("   解析真实评论页 URL：失败（JS 执行异常）\n")
            sb.append("   ").append(resolveResult.exceptionOrNull()!!.stackTraceToString()).append('\n')
            return ButtonOutcome(buttonIndex, sb.toString(), failed = true)
        }
        val page = resolveResult.getOrNull()!!
        val url = page.url
        if (url.isNullOrBlank()) {
            // resolveReviewPageUrl 无异常但没等到地址
            sb.append("   解析真实评论页 URL：失败（click/js 执行了，但没有触发 browser open/showBrowser，")
                .append(RESOLVE_TIMEOUT_MS / 1000).append("s 超时）\n")
            return ButtonOutcome(buttonIndex, sb.toString(), failed = true)
        }
        sb.append("   解析真实评论页 URL：成功")
        if (!page.html.isNullOrBlank()) {
            sb.append("（showBrowser 已带回渲染 HTML ")
                .append(page.html.length / 1024).append(" KB")
                .append(if (page.preloadJs.isNullOrBlank()) "" else " + preloadJs ${page.preloadJs.length} 字符")
                .append("，作为初始页面）")
        }
        sb.append('\n')
        sb.append("   URL=").append(url).append('\n')
        val outcome = runCatching {
            ReviewSnapshotCapture.capture(
                bookSource, book, chapter, button.src, url, page.html, page.preloadJs
            )
        }
        if (outcome.isFailure) {
            val e = outcome.exceptionOrNull()!!
            val reason = when {
                e is kotlinx.coroutines.TimeoutCancellationException -> "WebView 抓取超时(60s)"
                e is kotlinx.coroutines.CancellationException -> "任务被取消"
                e.localizedMessage?.contains("序列化为空") == true -> "页面 HTML 为空"
                else -> (e.localizedMessage ?: "未知错误")
            }
            sb.append("4. 打开评论页/抓取快照：失败（").append(reason).append("）\n")
            sb.append("   ").append(e.stackTraceToString()).append('\n')
            return ButtonOutcome(buttonIndex, sb.toString(), failed = true)
        }
        val capture = outcome.getOrNull()!!
        sb.append("4. 打开评论页：成功\n")
        sb.append("5. 展开检测轮次：").append(capture.expandRounds)
            .append(" 次；实际点击“展开/加载更多”按钮：").append(capture.expandClickCount).append(" 次\n")
        sb.append("6. 最终 HTML：").append(capture.snapshot.html.length / 1024).append(" KB\n")
        // 诊断日志：put 失败必须留下原因
        val putResult = runCatching { ReviewSnapshotStore.put(book, capture.snapshot) }
        if (putResult.isSuccess) {
            sb.append("7. SnapshotStore.put：成功\n")
            // 实际完成一个快照：跨并发章/按钮原子累计，只增不减（不可读-改-写共享值）
            _syncState.update {
                it.copy(completedSnapshots = it.completedSnapshots + 1)
            }
            return ButtonOutcome(buttonIndex, sb.toString(), success = true)
        }
        sb.append("7. SnapshotStore.put：失败\n")
        sb.append("   ").append(putResult.exceptionOrNull()!!.stackTraceToString()).append('\n')
        return ButtonOutcome(buttonIndex, sb.toString(), failed = true)
    }

    /**
     * 解析评论按钮对应的真实评论页。
     *
     * 与用户点击共用 [BookImgClick.executeClick]/[executeJs]：
     * click 分支替换 java 拦截宿主，旧源 js 分支挂 AnalyzeRule 钩子，
     * 执行环境与真实点击完全一致。
     *
     * 宿主基于 [SourceLoginJsExtensions]：书源评论实际走
     * `java.showBrowser(url, html, preloadJs, config)`（改版 app 弹窗路径），
     * 或 qread 的 `java.startBrowserDp(url, title)`，或老式 startBrowser 路径；
     * showBrowser 时书源已用 ajax 取回渲染 HTML，一并记录，抓取阶段可直接
     * 作为初始页面，不用再开网络请求。
     */
    data class ReviewPage(
        val url: String?,
        /** showBrowser 路径书源已取到的渲染 HTML（可为 null） */
        val html: String?,
        /** showBrowser 路径书源传入的 preloadJs（页面 JS bridge 需要） */
        val preloadJs: String? = null
    )

    suspend fun resolveReviewPageUrl(
        book: Book,
        bookSource: BookSource,
        chapter: BookChapter,
        button: ReviewButton
    ): ReviewPage {
        val resolvedUrl = AtomicReference<String>()
        val resolvedHtml = AtomicReference<String?>()
        val resolvedPreloadJs = AtomicReference<String?>()
        val latch = CountDownLatch(1)
        fun record(url: String, html: String? = null, preloadJs: String? = null) {
            if (resolvedUrl.compareAndSet(null, url)) {
                resolvedHtml.compareAndSet(null, html)
                resolvedPreloadJs.compareAndSet(null, preloadJs)
                latch.countDown()
            }
        }
        if (!button.click.isNullOrBlank()) {
            val host = object : SourceLoginJsExtensions(null, bookSource, BookType.text) {
                override fun startBrowser(url: String, title: String) {
                    record(url)
                }

                override fun startBrowser(url: String, title: String, html: String?) {
                    record(url, html)
                }

                override fun startBrowserAwait(url: String, title: String): StrResponse {
                    record(url)
                    return StrResponse(url, "")
                }

                override fun startBrowserAwait(
                    url: String,
                    title: String,
                    refetchAfterSuccess: Boolean
                ): StrResponse {
                    record(url)
                    return StrResponse(url, "")
                }

                override fun startBrowserAwait(
                    url: String,
                    title: String,
                    refetchAfterSuccess: Boolean,
                    html: String?
                ): StrResponse {
                    record(url, html)
                    return StrResponse(url, "")
                }

                /** 改版 app 弹窗路径：书源评论实际走这里 */
                override fun showBrowser(
                    url: String,
                    html: String?,
                    preloadJs: String?,
                    config: String?
                ) {
                    record(url, html, preloadJs)
                }

                /** qread 弹窗路径兼容 */
                fun startBrowserDp(url: String, title: String) {
                    record(url)
                }
            }
            BookImgClick.executeClick(book, bookSource, chapter, button.click, button.src) { host }
        } else {
            val js = button.js.orEmpty()
            val urlNoOption = button.urlNoOption.orEmpty()
            BookImgClick.executeJs(book, bookSource, chapter, js, urlNoOption) {
                onBrowserOpenRequestedHook = { url, _, _ ->
                    record(url)
                    true
                }
                onBrowserAwaitRequestedHook = { url, _, _ ->
                    record(url)
                    StrResponse(url, "")
                }
            }
        }
        latch.await(RESOLVE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        return ReviewPage(resolvedUrl.get(), resolvedHtml.get())
    }

    /**
     * 提取正文里的评论按钮：img 标签选项 JSON 带 style=TEXT 的评论泡。
     * click 与旧源 js 原样带出，交给与用户点击共用的执行逻辑。
     * 附带诊断统计：img 总数、选项 JSON 解析失败数、style 非 TEXT 数、click/js 都为空数。
     */
    fun extractReviewButtons(content: String): List<ReviewButton> {
        return extractReviewButtonsWithStats(content).buttons
    }

    data class ButtonExtraction(
        val buttons: List<ReviewButton>,
        val imgCount: Int,
        val optionParseFailed: Int,
        val nonTextStyle: Int,
        val noAction: Int
    )

    fun extractReviewButtonsWithStats(content: String): ButtonExtraction {
        if (!content.contains("<img", ignoreCase = true)) {
            return ButtonExtraction(emptyList(), 0, 0, 0, 0)
        }
        var imgCount = 0
        var optionParseFailed = 0
        var nonTextStyle = 0
        var noAction = 0
        val result = linkedMapOf<String, ReviewButton>()
        val matcher = AppPattern.imgPattern.matcher(content)
        while (matcher.find()) {
            val src = matcher.group(1) ?: continue
            imgCount++
            val options = extractUrlOptions(src)
            if (options == null) {
                optionParseFailed++
                continue
            }
            val style = options["style"]
            if (style == null || !style.equals("TEXT", ignoreCase = true)) {
                nonTextStyle++
                continue
            }
            val key = src.trim()
            if (result.containsKey(key)) continue
            val urlNoOption = AnalyzeUrl.paramPattern.matcher(src).let { m ->
                if (m.find()) src.take(m.start()) else null
            }
            val button = ReviewButton(
                src = key,
                click = options["click"]?.takeIf { it.isNotBlank() },
                js = options["js"]?.takeIf { it.isNotBlank() },
                urlNoOption = urlNoOption
            )
            if (!button.hasAction) noAction++
            result[key] = button
        }
        return ButtonExtraction(result.values.toList(), imgCount, optionParseFailed, nonTextStyle, noAction)
    }

    /** 与 AnalyzeUrl.paramPattern 一致：URL 后第一个 `,` 到 `{` 的选项 JSON */
    private fun extractUrlOptions(src: String): Map<String, String>? {
        val matcher = AnalyzeUrl.paramPattern.matcher(src)
        if (!matcher.find()) return null
        val optionJson = src.substring(matcher.end())
        return GSON.fromJsonObject<Map<String, String>>(optionJson).getOrNull()
    }
}
