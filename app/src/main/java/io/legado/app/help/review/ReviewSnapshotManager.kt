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
import io.legado.app.help.book.isAudio
import io.legado.app.help.book.isVideo
import io.legado.app.help.cache.CacheOperationDiagnostics
import io.legado.app.help.cache.CacheWorkerLease
import io.legado.app.help.config.AppConfig
import io.legado.app.help.http.StrResponse
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.ui.login.SourceLoginJsExtensions
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.mapAsyncIndexed
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withTimeout
import splitties.init.appCtx
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * 评论快照抓取调度器（登记端）。
 *
 * 只负责评论任务去重、force 合并和 WebView 执行队列；正文与评论的依赖、
 * 生命周期和结果归属由 CacheCoordinator 管理。
 *
 * 快照刷新语义：
 * - 普通后台重复触发（force=false）：已有快照可跳过；
 * - 用户明确重新缓存/刷新该章节（force=true 或 [markUserRefresh] 持久标记）：
 *   重新抓取并覆盖旧快照，不依赖 has()。
 */
object ReviewSnapshotManager {

    /** 评论网络打开链路有本地快照时的网络加载上限。 */
    const val NETWORK_FALLBACK_LOAD_TIMEOUT_MS = 5_000L

    /** 预热当前 Capture 与下一条 Capture；不随用户资源下载设置改变。 */
    private const val CAPTURE_PIPELINE_CONCURRENCY = 2

    /** 全局页面流水线固定为两条；无论按钮数多少，活动 Capture 都不会超过该值。 */
    private val pipelineLock = Any()
    private var activePipelines = 0

    private suspend fun <T> withPipelinePermit(block: suspend () -> T): T {
        while (true) {
            val acquired = synchronized(pipelineLock) {
                if (activePipelines < CAPTURE_PIPELINE_CONCURRENCY) {
                    activePipelines++
                    true
                } else {
                    false
                }
            }
            if (acquired) {
                return try {
                    block()
                } finally {
                    synchronized(pipelineLock) {
                        activePipelines--
                    }
                }
            }
            delay(100)
        }
    }

    /** 对外任务（key = bookUrl|chapterIndex；force 为消费时聚合值） */
    internal data class QueueTask(
        val key: String,
        val force: Boolean,
        /** true = 已完整快照的按钮/tab 走增量下载而不是纯跳过 */
        val incremental: Boolean,
        /** Null = normal/force chapter capture; non-null = only these failed button src values. */
        val retryButtonSources: Set<String>?,
        internal val executionLease: CacheWorkerLease,
        internal val commitIfLeaseActive: ((() -> Unit) -> Boolean),
        internal val reportProgress: (
            processedSnapshots: Int,
            totalSnapshots: Int,
            failedSnapshots: Int,
        ) -> Unit,
    )

    internal enum class TaskResult {
        SUCCEEDED,
        FAILED,
        STOPPED,
    }

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
            ReviewSnapshotStore.chapterUrls(book).toMutableSet()
        }.size
    }

    /**
     * 增量下载的书评 tab 去重（key = bookUrl|sessionId/taskId）：
     * 书评整本一份，同一次下载任务的全部章节只增量一次。
     */
    private val bookTabIncrementalHandled = ConcurrentHashMap.newKeySet<String>()

    private data class Task(
        val key: String,
        val executionLease: CacheWorkerLease,
    )

    private data class PendingReview(
        val force: Boolean,
        val incremental: Boolean,
        val retryButtonSources: Set<String>?,
        val executionLease: CacheWorkerLease,
        val commitIfLeaseActive: ((() -> Unit) -> Boolean),
        val reportProgress: (
            processedSnapshots: Int,
            totalSnapshots: Int,
            failedSnapshots: Int,
        ) -> Unit,
    )

    private data class ExecutionGroup(
        val lease: CacheWorkerLease,
        val queued: MutableSet<String> = linkedSetOf(),
        val claimed: MutableSet<String> = linkedSetOf(),
        val activeJobs: MutableMap<String, Job> = linkedMapOf(),
        val stopCallbacks: MutableList<() -> Unit> = mutableListOf(),
        var stopRequested: Boolean = false,
    )

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

    /**
     * 待处理任务的 force 聚合表（key = bookUrl|chapterIndex）。
     * 负责两件事：
     * - 去重：已存在代表该章已在队列中，不重复入队；
     * - force 合并：后入队的 force=true 必须提升已排队任务的 force，
     *   绝不允许先 false 后 true 时把 true 丢掉。
     * 入队与消费的读取/删除都持有 [queueLock]，消除 putIfAbsent→replace 与
     * remove(key) 之间的并发窗口。
     */
    private val pendingForce = ConcurrentHashMap<String, PendingReview>()
    private val taskOutcomes = ConcurrentHashMap<String, Boolean>()
    private val executionGroups = linkedMapOf<String, ExecutionGroup>()
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
     * 已缓存正文的章节在重新缓存/刷新时也必须走“是否需要补评论”的判断，
     * 因此缓存流程在跳过正文下载前调用本方法直接入队。
     */
    internal fun enqueue(
        book: Book,
        chapter: BookChapter,
        force: Boolean,
        incremental: Boolean = false,
        retryButtonSources: Set<String>? = null,
        executionLease: CacheWorkerLease,
        commitIfLeaseActive: ((() -> Unit) -> Boolean),
        reportProgress: (
            processedSnapshots: Int,
            totalSnapshots: Int,
            failedSnapshots: Int,
        ) -> Unit,
    ) {
        synchronized(queueLock) {
            val key = keyOf(book, chapter)
            val group = executionGroups.getOrPut(leaseKey(executionLease)) {
                ExecutionGroup(executionLease)
            }
            check(group.lease == executionLease && !group.stopRequested) {
                "review execution lease is already stopping: ${leaseKey(executionLease)}"
            }
            require(retryButtonSources == null || retryButtonSources.all { it.isNotBlank() }) {
                "review retry contains blank button source"
            }
            val existed = pendingForce.putIfAbsent(
                key,
                PendingReview(
                    force,
                    incremental,
                    retryButtonSources,
                    executionLease,
                    commitIfLeaseActive,
                    reportProgress,
                ),
            )
            if (existed != null &&
                taskKey(existed.executionLease) != taskKey(executionLease)
            ) {
                error("review chapter already owned by another Coordinator task: $key")
            }
            if (existed == null) {
                check(group.queued.add(key)) { "review queue already contains chapter: $key" }
                channel.trySend(Task(key, executionLease))
            } else if (force && !existed.force) {
                // force 合并：已排队任务升级为强制重抓，不丢 true
                pendingForce.replace(
                    key,
                    existed,
                    existed.copy(
                        force = true,
                        executionLease = executionLease,
                        commitIfLeaseActive = commitIfLeaseActive,
                        reportProgress = reportProgress,
                    ),
                )
            }
        }
    }

    /** 评论服务消费：取一个任务（无任务返回 null）。force 取聚合值并原子移除 */
    internal fun tryTakeTask(): QueueTask? {
        val task = synchronized(queueLock) {
            val t = channel.tryReceive().getOrNull() ?: return null
            val pending = pendingForce.remove(t.key)
                ?: error("review queue entry has no ownership: ${t.key}")
            val group = requireNotNull(executionGroups[leaseKey(t.executionLease)]) {
                "review queue entry has no execution group: ${t.key}"
            }
            check(group.queued.remove(t.key)) {
                "review queue entry was not registered as queued: ${t.key}"
            }
            check(group.claimed.add(t.key)) {
                "review queue entry was already claimed: ${t.key}"
            }
            t to pending
        }
        return QueueTask(
            key = task.first.key,
            force = task.second.force,
            incremental = task.second.incremental,
            retryButtonSources = task.second.retryButtonSources,
            executionLease = task.second.executionLease,
            reportProgress = task.second.reportProgress,
            commitIfLeaseActive = task.second.commitIfLeaseActive,
        )
    }

    /** Stop one Coordinator task and acknowledge only after every claimed/active chapter exits. */
    internal fun stopTask(sessionId: String, taskId: String, onStopped: () -> Unit) {
        val ownerKey = "$sessionId/$taskId"
        val jobsToCancel = mutableListOf<Job>()
        var callbacks = emptyList<() -> Unit>()
        synchronized(queueLock) {
            val groups = executionGroups.values.filter { group -> taskKey(group.lease) == ownerKey }
            check(groups.size <= 1) { "multiple review generations are active for $ownerKey" }
            val group = groups.singleOrNull()
            if (group == null) {
                callbacks = listOf(onStopped)
                return@synchronized
            }
            group.stopRequested = true
            group.stopCallbacks += onStopped
            pendingForce.keys.toList()
                .filter { key ->
                    pendingForce[key]?.executionLease?.let { taskKey(it) == ownerKey } == true
                }
                .forEach { pendingForce.remove(it) }
            val retained = ArrayList<Task>()
            while (true) {
                val task = channel.tryReceive().getOrNull() ?: break
                if (taskKey(task.executionLease) != ownerKey) retained += task
            }
            retained.forEach { channel.trySend(it) }
            group.queued.clear()
            jobsToCancel += group.activeJobs.values
            callbacks = completeExecutionGroupIfIdleLocked(group)
        }
        jobsToCancel.forEach { job ->
            job.cancel(CancellationException("review Coordinator task stopped: $ownerKey"))
        }
        callbacks.forEach { it() }
        AppLog.put("review cache stop requested: $ownerKey")
    }

    /** 评论服务处理任务（suspend：内部解析 URL、WebView 抓取、落盘） */
    internal suspend fun processTask(task: QueueTask): TaskResult {
        val lease = task.executionLease
        val outcomeKey = "${task.key}|${lease.sessionId}/${lease.taskId}/${lease.generation}"
        val diagnostics = CacheOperationDiagnostics.Context(
            domain = CacheOperationDiagnostics.Domain.REVIEW,
            sessionId = lease.sessionId,
            taskId = lease.taskId,
            generation = lease.generation,
            chapterIndex = task.key.substringAfter('|').toIntOrNull(),
        )
        var group: ExecutionGroup? = null
        return try {
            coroutineScope {
                group = registerActiveExecution(
                    task,
                    currentCoroutineContext()[Job] ?: error("review task has no coroutine Job"),
                )
                if (group?.stopRequested == true) return@coroutineScope TaskResult.STOPPED
                processTask(
                    task.key,
                    task.force,
                    task.incremental,
                    task.retryButtonSources,
                    outcomeKey,
                    diagnostics,
                    task.commitIfLeaseActive,
                    task.reportProgress,
                )
                if (taskOutcomes.remove(outcomeKey) == true) {
                    TaskResult.SUCCEEDED
                } else {
                    TaskResult.FAILED
                }
            }
        } catch (error: CancellationException) {
            if (group?.let(::isStopRequested) == true) TaskResult.STOPPED else throw error
        } finally {
            group?.let { unregisterActiveExecution(task, it) }
        }
    }

    private fun keyOf(book: Book, chapter: BookChapter) = "${book.bookUrl}|${chapter.index}"

    private fun commitOrThrow(
        commitIfLeaseActive: ((() -> Unit) -> Boolean),
        boundary: String,
        action: () -> Unit,
    ) {
        if (!commitIfLeaseActive(action)) {
            throw CancellationException("review lease is no longer active at $boundary")
        }
    }

    private fun taskKey(lease: CacheWorkerLease): String = "${lease.sessionId}/${lease.taskId}"

    private fun leaseKey(lease: CacheWorkerLease): String =
        "${taskKey(lease)}/${lease.generation}"

    private fun registerActiveExecution(task: QueueTask, job: Job): ExecutionGroup {
        var callbacks = emptyList<() -> Unit>()
        val group = synchronized(queueLock) {
            val current = requireNotNull(executionGroups[leaseKey(task.executionLease)]) {
                "review claimed task has no execution group: ${task.key}"
            }
            check(current.claimed.remove(task.key)) {
                "review task was not claimed before execution: ${task.key}"
            }
            if (!current.stopRequested) {
                check(current.activeJobs.put(task.key, job) == null) {
                    "review task is already active: ${task.key}"
                }
            } else {
                callbacks = completeExecutionGroupIfIdleLocked(current)
            }
            current
        }
        callbacks.forEach { it() }
        return group
    }

    private fun unregisterActiveExecution(task: QueueTask, group: ExecutionGroup) {
        val callbacks = synchronized(queueLock) {
            group.activeJobs.remove(task.key)
            completeExecutionGroupIfIdleLocked(group)
        }
        callbacks.forEach { it() }
    }

    private fun completeExecutionGroupIfIdleLocked(group: ExecutionGroup): List<() -> Unit> {
        if (group.queued.isNotEmpty() || group.claimed.isNotEmpty() || group.activeJobs.isNotEmpty()) {
            return emptyList()
        }
        executionGroups.remove(leaseKey(group.lease), group)
        return if (group.stopRequested) group.stopCallbacks.toList() else emptyList()
    }

    private fun isStopRequested(group: ExecutionGroup): Boolean = synchronized(queueLock) {
        group.stopRequested
    }

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

    private suspend fun processTask(
        key: String,
        force: Boolean,
        incremental: Boolean,
        retryButtonSources: Set<String>?,
        outcomeKey: String,
        diagnostics: CacheOperationDiagnostics.Context,
        commitIfLeaseActive: ((() -> Unit) -> Boolean),
        reportProgress: (
            processedSnapshots: Int,
            totalSnapshots: Int,
            failedSnapshots: Int,
        ) -> Unit,
    ) {
        taskOutcomes[outcomeKey] = false
        // 一章一次评论缓存任务 = 日志一条（多行详情），进入 AppLog 日志页可展开查看
        val sb = StringBuilder()
        val unexpected = runCatching {
            processTaskWithLog(
                key,
                force,
                incremental,
                retryButtonSources,
                sb,
                outcomeKey,
                diagnostics,
                commitIfLeaseActive,
                reportProgress,
            )
        }.exceptionOrNull()
        if (unexpected is CancellationException) throw unexpected
        if (unexpected != null) {
            sb.append("\n异常：").append(unexpected.stackTraceToString())
        }
        if (sb.isNotBlank()) {
            AppLog.put(sb.toString())
        }
        taskOutcomes[outcomeKey] = unexpected == null && taskOutcomes[outcomeKey] == true
    }

    private suspend fun processTaskWithLog(
        key: String,
        force: Boolean,
        incremental: Boolean,
        retryButtonSources: Set<String>?,
        sb: StringBuilder,
        outcomeKey: String,
        diagnostics: CacheOperationDiagnostics.Context,
        commitIfLeaseActive: ((() -> Unit) -> Boolean),
        reportProgress: (
            processedSnapshots: Int,
            totalSnapshots: Int,
            failedSnapshots: Int,
        ) -> Unit,
    ) {
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
        sb.append("增量：").append(if (incremental) "true" else "false").append('\n')
        // 处理时再查一次开关：入队后关闭开关不应继续抓取
        if (!AppConfig.syncCacheReview) {
            sb.append("开关“缓存评论”已关闭，跳过")
            return
        }
        val content = reviewContent(book, chapter)
        if (content == null) {
            sb.append("1. 读取评论载体：失败（文本正文或音频原始字幕不存在）")
            return
        }
        sb.append("1. 读取评论载体：成功\n")
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
        val snapshotButtons = buttons.filter { it.hasAction }
        val requestedRetrySources = retryButtonSources?.map(String::trim)?.toSet()
        if (requestedRetrySources != null) {
            val persisted = ReviewSnapshotStore.chapterStatus(book, chapter)
                ?.failedButtonSourcesForRetry()
                ?.toSet()
                ?: error("review retry has no complete failed button identities: ${chapter.url}")
            require(persisted == requestedRetrySources) {
                "review retry no longer matches persisted failed button identities: ${chapter.url}"
            }
        }
        if (snapshotButtons.isNotEmpty()) {
            // A capture with no image resources still belongs to the one resource-library
            // format, so establish its empty index before any snapshot/status can persist.
            commitOrThrow(commitIfLeaseActive, "review resource database preparation") {
                ReviewSnapshotResourceStore.prepareForCapture(book)
            }
        }
        val processButtons = if (requestedRetrySources != null) {
            snapshotButtons.filter { it.src in requestedRetrySources }.also { selected ->
                require(selected.size == requestedRetrySources.size) {
                    "review retry button is no longer present in chapter content: ${chapter.url}"
                }
                require(selected.none { ReviewSnapshotStore.hasComplete(book, chapter, it.src) }) {
                    "review retry button already has a complete snapshot: ${chapter.url}"
                }
            }
        } else {
            snapshotButtons
        }
        val existingSnapshots = if (force) {
            0
        } else {
            // 只有完整快照才算“已处理”基线；部分成功快照会在本轮重新抓取，
            // 由其本轮结果计数，不得预计入 processed。
            snapshotButtons.count { ReviewSnapshotStore.hasComplete(book, chapter, it.src) }
        }
        // "已处理" and "成功" are different counters. Existing snapshots count as
        // already processed for an ordinary cache run, while a forced retry starts
        // a complete new attempt from zero. "失败" always counts failures observed in
        // this run (0 at start): the progress port requires failed <= processed, so a
        // retry must not pre-accumulate persisted failures into the counter.
        val successfulSnapshots = AtomicInteger(if (force) 0 else existingSnapshots)
        val processedSnapshots = AtomicInteger(if (force) 0 else existingSnapshots)
        val failedSnapshots = AtomicInteger(0)
        val progressLock = Any()
        fun reportChapterProgress() {
            synchronized(progressLock) {
                reportProgress(
                    processedSnapshots.get(),
                    snapshotButtons.size,
                    failedSnapshots.get(),
                )
            }
        }
        reportChapterProgress()
        // 单章只预热当前按钮与下一条；全局也由 [withPipelinePermit] 固定为两条 Capture。
        val buttonConcurrency = CAPTURE_PIPELINE_CONCURRENCY.coerceAtMost(
            processButtons.size.coerceAtLeast(1)
        )
        val resolvedPageRecorder = AtomicReference<ResolvedPageContext?>(null)
        val outcomes = processButtons
            .asFlow()
            .mapAsyncIndexed(buttonConcurrency) { index, button ->
                val outcome = processButton(
                    book,
                    bookSource,
                    chapter,
                    index,
                    button,
                    force,
                    incremental,
                    resolvedPageRecorder,
                    diagnostics,
                    commitIfLeaseActive,
                    onSnapshotSaved = {
                        synchronized(progressLock) {
                            successfulSnapshots.updateAndGet { value ->
                                (value + 1).coerceAtMost(snapshotButtons.size)
                            }
                            processedSnapshots.updateAndGet { value ->
                                (value + 1).coerceAtMost(snapshotButtons.size)
                            }
                            reportProgress(
                                processedSnapshots.get(),
                                snapshotButtons.size,
                                failedSnapshots.get(),
                            )
                        }
                    },
                )
                if (outcome.failed) {
                    synchronized(progressLock) {
                        failedSnapshots.incrementAndGet()
                        processedSnapshots.updateAndGet { value ->
                            (value + 1).coerceAtMost(snapshotButtons.size)
                        }
                        reportProgress(
                            processedSnapshots.get(),
                            snapshotButtons.size,
                            failedSnapshots.get(),
                        )
                    }
                }
                outcome
            }
            .toList()
            .sortedBy { it.index }
        outcomes.forEach { o ->
            sb.append(o.log)
        }
        val failedButtonSources = outcomes
            .asSequence()
            .filter { it.failed }
            .map { it.buttonSrc }
            .toList()
        val failedButtons = failedButtonSources.size
        val hasFailure = failedButtons > 0
        if (snapshotButtons.isNotEmpty()) {
            commitOrThrow(commitIfLeaseActive, "review chapter status commit") {
                ReviewSnapshotStore.putChapterStatus(
                    book,
                    ReviewChapterSnapshotStatus(
                        bookUrl = book.bookUrl,
                        chapterUrl = chapter.url,
                        chapterIndex = chapter.index,
                        chapterTitle = chapter.title,
                        totalSnapshots = snapshotButtons.size,
                        failedSnapshots = failedButtons,
                        failedButtonSources = failedButtonSources,
                        updatedAt = System.currentTimeMillis(),
                    )
                )
            }
        }
        // 该章所有需要刷新的评论按钮全部成功处理，才清除“评论待刷新”持久标记
        if (force && !hasFailure) {
            clearUserRefresh(bookUrl, chapterIndex)
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
        // 章评/书评 tab 补充快照：每章一份章评、整本一份书评；失败只记日志不影响任务结论
        captureSupplementTabs(
            book,
            bookSource,
            chapter,
            force,
            incremental,
            resolvedPageRecorder.get(),
            sb,
            diagnostics,
            commitIfLeaseActive,
        )
        taskOutcomes[outcomeKey] = !hasFailure
        sb.append("9. 最终结果：\n")
        sb.append("   成功快照 ").append(successfulSnapshots.get()).append("/")
            .append(snapshotButtons.size).append('\n')
        sb.append("   失败 ").append(failedButtons).append("/").append(processButtons.size).append('\n')
        sb.append("   本章是否计入“评论已缓存”：").append(if (chapterHasSnapshot) "是" else "否")
    }

    /** 单按钮处理结果：日志按原序号归位，成功/失败供整章统计 */
    private data class ButtonOutcome(
        val index: Int,
        val buttonSrc: String,
        val log: String,
        val success: Boolean = false,
        val failed: Boolean = false
    )

    /** 单章任务内第一个成功解析的评论页上下文，供章评/书评补充快照复用 */
    private class ResolvedPageContext(
        val url: String,
        val html: String?,
        val preloadJs: String?,
    )

    /**
     * 章评/书评 tab 补充快照：复用本章按钮解析出的评论页地址（不重新执行
     * click/js），加载后点击目标 tab 抓取。章评每章一份、书评整本一份
     * （书评挂在伪章节主键上跨章去重）；普通缓存跳过已完整快照，force 覆盖，
     * 增量下载对已完整快照只补新增评论（书评整本每次下载只增量一次）。
     *
     * 失败只记录日志，不影响段评快照的任务结论；下一次缓存运行会因
     * hasComplete=false 自动补抓。“缓存楼中楼”开关不影响补充快照本身——
     * 只由抓取端决定其内部是否包含回复层。
     */
    private suspend fun captureSupplementTabs(
        book: Book,
        bookSource: BookSource,
        chapter: BookChapter,
        force: Boolean,
        incremental: Boolean,
        resolved: ResolvedPageContext?,
        sb: StringBuilder,
        diagnostics: CacheOperationDiagnostics.Context,
        commitIfLeaseActive: ((() -> Unit) -> Boolean),
    ) {
        if (resolved == null) {
            sb.append("8. 章评/书评补充快照：跳过（本章没有评论按钮成功解析出评论页地址）\n")
            return
        }
        sb.append("8. 章评/书评补充快照：\n")
        val chapterTabComplete = !force && ReviewSnapshotStore.hasCompleteChapterTab(book, chapter)
        val chapterTabExisting = if (chapterTabComplete) {
            ReviewSnapshotStore.getChapterTab(book, chapter)
        } else {
            null
        }
        when {
            chapterTabComplete && chapterTabExisting != null && incremental -> {
                incrementalSupplementTab(
                    tabLabel = "章评",
                    book = book,
                    bookSource = bookSource,
                    chapter = chapter,
                    existing = chapterTabExisting,
                    sb = sb,
                    diagnostics = diagnostics,
                    commitIfLeaseActive = commitIfLeaseActive,
                    capture = { incrementalMode ->
                        ReviewSnapshotCapture.captureChapterTab(
                            bookSource,
                            book,
                            chapter,
                            resolved.url,
                            resolved.html,
                            resolved.preloadJs,
                            diagnostics.forChapter(chapter.index),
                            commitIfLeaseActive,
                            incrementalMode,
                        )
                    },
                )
            }
            chapterTabComplete -> {
                sb.append("   章评：已有完整快照，跳过\n")
            }
            else -> {
                val outcome = runCatching {
                    withPipelinePermit {
                        ReviewSnapshotCapture.captureChapterTab(
                            bookSource,
                            book,
                            chapter,
                            resolved.url,
                            resolved.html,
                            resolved.preloadJs,
                            diagnostics.forChapter(chapter.index),
                            commitIfLeaseActive,
                        )
                    }
                }
                outcome.fold(
                    onSuccess = { capture ->
                        val put = runCatching {
                            commitOrThrow(commitIfLeaseActive, "review chapter tab commit") {
                                ReviewSnapshotStore.put(book, capture.snapshot, diagnostics.forChapter(chapter.index))
                            }
                        }
                        put.exceptionOrNull()?.let { error ->
                            if (error is CancellationException) throw error
                        }
                        if (put.isSuccess) {
                            if (capture.snapshot.partial) {
                                sb.append("   章评：部分成功（缺失资源已占位，等待重试）\n")
                            } else {
                                sb.append("   章评：成功（")
                                    .append(capture.snapshot.html.length / 1024).append(" KB）\n")
                            }
                        } else {
                            sb.append("   章评：落盘失败\n   ")
                                .append(put.exceptionOrNull()?.stackTraceToString()).append('\n')
                        }
                    },
                    onFailure = { error ->
                        if (error is CancellationException) throw error
                        sb.append("   章评：失败（").append(error.localizedMessage ?: "未知错误").append("）\n")
                        sb.append("   ").append(error.stackTraceToString()).append('\n')
                    },
                )
            }
        }
        val bookTabComplete = !force && ReviewSnapshotStore.hasCompleteBookTab(book)
        val bookTabExisting = if (bookTabComplete) {
            ReviewSnapshotStore.getBookTab(book)
        } else {
            null
        }
        when {
            bookTabComplete && bookTabExisting != null && incremental -> {
                // 书评整本一份：同一次下载任务内只增量一次，其余章节直接跳过
                val guardKey = "${book.bookUrl}|${diagnostics.sessionId}/${diagnostics.taskId}"
                if (!bookTabIncrementalHandled.add(guardKey)) {
                    sb.append("   书评：本次下载已增量处理，跳过\n")
                } else {
                    incrementalSupplementTab(
                        tabLabel = "书评",
                        book = book,
                        bookSource = bookSource,
                        chapter = chapter,
                        existing = bookTabExisting,
                        sb = sb,
                        diagnostics = diagnostics,
                        commitIfLeaseActive = commitIfLeaseActive,
                        capture = { incrementalMode ->
                            ReviewSnapshotCapture.captureBookTab(
                                bookSource,
                                book,
                                resolved.url,
                                resolved.html,
                                resolved.preloadJs,
                                diagnostics.forChapter(chapter.index),
                                commitIfLeaseActive,
                                incrementalMode,
                            )
                        },
                    )
                }
            }
            bookTabComplete -> {
                sb.append("   书评：已有完整快照，跳过\n")
            }
            else -> {
                val outcome = runCatching {
                    withPipelinePermit {
                        ReviewSnapshotCapture.captureBookTab(
                            bookSource,
                            book,
                            resolved.url,
                            resolved.html,
                            resolved.preloadJs,
                            diagnostics.forChapter(chapter.index),
                            commitIfLeaseActive,
                        )
                    }
                }
                outcome.fold(
                    onSuccess = { capture ->
                        val put = runCatching {
                            commitOrThrow(commitIfLeaseActive, "review book tab commit") {
                                ReviewSnapshotStore.put(book, capture.snapshot, diagnostics.forChapter(chapter.index))
                            }
                        }
                        put.exceptionOrNull()?.let { error ->
                            if (error is CancellationException) throw error
                        }
                        if (put.isSuccess) {
                            if (capture.snapshot.partial) {
                                sb.append("   书评：部分成功（缺失资源已占位，等待重试）\n")
                            } else {
                                sb.append("   书评：成功（")
                                    .append(capture.snapshot.html.length / 1024).append(" KB）\n")
                            }
                        } else {
                            sb.append("   书评：落盘失败\n   ")
                                .append(put.exceptionOrNull()?.stackTraceToString()).append('\n')
                        }
                    },
                    onFailure = { error ->
                        if (error is CancellationException) throw error
                        sb.append("   书评：失败（").append(error.localizedMessage ?: "未知错误").append("）\n")
                        sb.append("   ").append(error.stackTraceToString()).append('\n')
                    },
                )
            }
        }
    }

    /**
     * 章评/书评 tab 增量下载：免翻页抓第一屏，与原快照做增量合并。
     * 任何失败都保留原快照，只记日志，不影响任务结论。
     *
     * @param capture (incremental) -> CaptureOutcome；incremental 恒为 true
     */
    private suspend fun incrementalSupplementTab(
        tabLabel: String,
        book: Book,
        bookSource: BookSource,
        chapter: BookChapter,
        existing: ReviewSnapshot,
        sb: StringBuilder,
        diagnostics: CacheOperationDiagnostics.Context,
        commitIfLeaseActive: ((() -> Unit) -> Boolean),
        capture: suspend (Boolean) -> ReviewSnapshotCapture.CaptureOutcome,
    ) {
        val outcome = runCatching {
            withPipelinePermit {
                capture(true)
            }
        }
        val captureResult = outcome.getOrNull()
        if (captureResult == null) {
            val error = outcome.exceptionOrNull()!!
            if (error is CancellationException) throw error
            sb.append("   ").append(tabLabel).append("：增量抓取失败（原快照保留，下次下载再试）")
                .append("（").append(error.localizedMessage ?: "未知错误").append("）\n")
            return
        }
        val mergeResult = runCatching {
            ReviewSnapshotMerger.merge(existing.html, captureResult.snapshot.html)
        }.getOrNull()
        if (mergeResult == null) {
            sb.append("   ").append(tabLabel).append("：增量合并失败，保留原快照\n")
            return
        }
        if (mergeResult.addedCount == 0) {
            sb.append("   ").append(tabLabel).append("：增量下载完成，无新增评论\n")
            return
        }
        val mergedSnapshot = captureResult.snapshot.copy(
            html = mergeResult.html,
            resourceKeys = (existing.resourceKeys.orEmpty() + captureResult.snapshot.resourceKeys.orEmpty())
                .distinct(),
            partial = captureResult.snapshot.partial,
            savedAt = System.currentTimeMillis(),
        )
        val put = runCatching {
            commitOrThrow(commitIfLeaseActive, "review supplement tab incremental commit") {
                ReviewSnapshotStore.put(book, mergedSnapshot, diagnostics.forChapter(chapter.index))
            }
        }
        put.exceptionOrNull()?.let { error ->
            if (error is CancellationException) throw error
        }
        if (put.isSuccess) {
            sb.append("   ").append(tabLabel).append("：增量下载成功，新增 ")
                .append(mergeResult.addedCount).append(" 条评论（")
                .append(mergedSnapshot.html.length / 1024).append(" KB）\n")
        } else {
            sb.append("   ").append(tabLabel).append("：增量落盘失败，原快照未被覆盖\n   ")
                .append(put.exceptionOrNull()?.stackTraceToString()).append('\n')
        }
    }

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
        force: Boolean,
        incremental: Boolean,
        resolvedPageRecorder: AtomicReference<ResolvedPageContext?>,
        diagnostics: CacheOperationDiagnostics.Context,
        commitIfLeaseActive: ((() -> Unit) -> Boolean),
        onSnapshotSaved: () -> Unit,
    ): ButtonOutcome = withPipelinePermit {
        processButtonInPipeline(
            book,
            bookSource,
            chapter,
            buttonIndex,
            button,
            force,
            incremental,
            resolvedPageRecorder,
            diagnostics,
            commitIfLeaseActive,
            onSnapshotSaved,
        )
    }

    private suspend fun processButtonInPipeline(
        book: Book,
        bookSource: BookSource,
        chapter: BookChapter,
        buttonIndex: Int,
        button: ReviewButton,
        force: Boolean,
        incremental: Boolean,
        resolvedPageRecorder: AtomicReference<ResolvedPageContext?>,
        diagnostics: CacheOperationDiagnostics.Context,
        commitIfLeaseActive: ((() -> Unit) -> Boolean),
        onSnapshotSaved: () -> Unit,
    ): ButtonOutcome {
        if (!button.hasAction) return ButtonOutcome(buttonIndex, button.src, "")
        val sb = StringBuilder()
        // 普通触发只跳过完整快照；部分成功快照不完整，必须重新抓取覆盖。
        // 增量下载（用户显式重复下载）：完整快照不跳过，改为“只补新增评论”的
        // 增量抓取——原快照为基底合入新增，绝不整页覆盖、绝不删除已缓存评论。
        var incrementalBase: ReviewSnapshot? = null
        val hasCompleteSnapshot = !force && ReviewSnapshotStore.hasComplete(book, chapter, button.src)
        if (hasCompleteSnapshot && incremental) {
            incrementalBase = runCatching {
                ReviewSnapshotStore.get(book, chapter, button.src)
            }.getOrNull()
        }
        sb.append("3. 按钮").append(buttonIndex + 1).append("：\n")
        sb.append("   src=").append(button.src).append('\n')
        sb.append("   click=").append(if (button.click.isNullOrBlank()) "无" else "有")
            .append("  js=").append(if (button.js.isNullOrBlank()) "无" else "有").append('\n')
        if (hasCompleteSnapshot && !incremental) {
            sb.append("   已有有效快照，跳过\n")
            return ButtonOutcome(buttonIndex, button.src, sb.toString())
        }
        if (hasCompleteSnapshot && incremental) {
            if (incrementalBase == null) {
                sb.append("   已有完整快照，但读取失败，无法增量，跳过\n")
                return ButtonOutcome(buttonIndex, button.src, sb.toString())
            }
            sb.append("   已有完整快照，执行增量下载（只补新增评论，不翻页重抓历史）\n")
        }
        // 计数拆分：抛异常记一次并 continue；无异常但 URL 为空才记 browser open 超时
        val resolveResult = runCatching {
            resolveReviewPageUrl(book, bookSource, chapter, button)
        }
        if (resolveResult.isFailure) {
            val error = resolveResult.exceptionOrNull()!!
            if (error is CancellationException) throw error
            val reason = when (error) {
                is kotlinx.coroutines.TimeoutCancellationException ->
                    "click/js 执行与 browser open/showBrowser 等待总超时(${RESOLVE_TIMEOUT_MS / 1000}s)"
                is kotlinx.coroutines.CancellationException -> "任务被取消"
                else -> "JS 执行异常"
            }
            sb.append("   解析真实评论页 URL：失败（").append(reason).append("）\n")
            sb.append("   ").append(error.stackTraceToString()).append('\n')
            if (incrementalBase != null) {
                sb.append("   增量下载失败（原快照保留，下次下载再试）\n")
                return ButtonOutcome(buttonIndex, button.src, sb.toString())
            }
            return ButtonOutcome(buttonIndex, button.src, sb.toString(), failed = true)
        }
        val page = resolveResult.getOrNull()!!
        val url = page.url
        if (url.isNullOrBlank()) {
            // resolveReviewPageUrl 无异常但没等到地址
            sb.append("   解析真实评论页 URL：失败（click/js 执行了，但没有触发 browser open/showBrowser，")
                .append(RESOLVE_TIMEOUT_MS / 1000).append("s 超时）\n")
            if (incrementalBase != null) {
                sb.append("   增量下载失败（原快照保留，下次下载再试）\n")
                return ButtonOutcome(buttonIndex, button.src, sb.toString())
            }
            return ButtonOutcome(buttonIndex, button.src, sb.toString(), failed = true)
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
        // 记录首个成功解析的评论页上下文：章评/书评补充快照复用同一地址，
        // 不再为补充快照单独执行 click/js 解析
        resolvedPageRecorder.compareAndSet(
            null,
            ResolvedPageContext(url, page.html, page.preloadJs),
        )
        val outcome = runCatching {
            ReviewSnapshotCapture.capture(
                bookSource,
                book,
                chapter,
                button.src,
                url,
                page.html,
                page.preloadJs,
                diagnostics.forChapter(chapter.index),
                commitIfLeaseActive,
                incremental = incrementalBase != null,
            )
        }
        if (outcome.isFailure) {
            val e = outcome.exceptionOrNull()!!
            if (e is CancellationException) throw e
            val reason = when {
                e is kotlinx.coroutines.TimeoutCancellationException -> "WebView 抓取超时(60s)"
                e is kotlinx.coroutines.CancellationException -> "任务被取消"
                e.localizedMessage?.contains("序列化为空") == true -> "页面 HTML 为空"
                else -> (e.localizedMessage ?: "未知错误")
            }
            sb.append("4. 打开评论页/抓取快照：失败（").append(reason).append("）\n")
            sb.append("   ").append(e.stackTraceToString()).append('\n')
            if (incrementalBase != null) {
                sb.append("   增量下载失败（原快照保留，下次下载再试）\n")
                return ButtonOutcome(buttonIndex, button.src, sb.toString())
            }
            return ButtonOutcome(buttonIndex, button.src, sb.toString(), failed = true)
        }
        val capture = outcome.getOrNull()!!
        sb.append("4. 打开评论页：成功\n")
        sb.append("5. 展开检测轮次：").append(capture.expandRounds)
            .append(" 次；实际点击“展开/加载更多”按钮：").append(capture.expandClickCount).append(" 次\n")
        sb.append("6. 最终 HTML：").append(capture.snapshot.html.length / 1024).append(" KB\n")
        val base = incrementalBase
        if (base != null) {
            // 增量合并：原快照为基底，只合入第一屏新增的评论；任何失败都保留原快照
            val mergeResult = ReviewSnapshotMerger.merge(base.html, capture.snapshot.html)
            if (mergeResult == null) {
                sb.append("7. 增量合并：无法可靠合并（评论页结构变化），保留原快照\n")
                return ButtonOutcome(buttonIndex, button.src, sb.toString())
            }
            if (mergeResult.addedCount == 0) {
                sb.append("7. 增量合并：无新增评论，原快照保持不变\n")
                return ButtonOutcome(buttonIndex, button.src, sb.toString())
            }
            val mergedSnapshot = capture.snapshot.copy(
                html = mergeResult.html,
                resourceKeys = (base.resourceKeys.orEmpty() + capture.snapshot.resourceKeys.orEmpty())
                    .distinct(),
                partial = capture.snapshot.partial,
                savedAt = System.currentTimeMillis(),
            )
            val putResult = runCatching {
                commitOrThrow(commitIfLeaseActive, "review snapshot incremental commit") {
                    ReviewSnapshotStore.put(book, mergedSnapshot, diagnostics.forChapter(chapter.index))
                }
            }
            putResult.exceptionOrNull()?.let { error ->
                if (error is CancellationException) throw error
            }
            if (putResult.isFailure) {
                sb.append("7. 增量合并落盘：失败，原快照未被覆盖\n")
                sb.append("   ").append(putResult.exceptionOrNull()!!.stackTraceToString()).append('\n')
                return ButtonOutcome(buttonIndex, button.src, sb.toString())
            }
            sb.append("7. 增量合并落盘：成功，新增 ").append(mergeResult.addedCount)
                .append(" 条评论\n")
            return ButtonOutcome(buttonIndex, button.src, sb.toString())
        }
        // 诊断日志：put 失败必须留下原因
        val putResult = runCatching {
            commitOrThrow(commitIfLeaseActive, "review snapshot commit") {
                ReviewSnapshotStore.put(book, capture.snapshot, diagnostics.forChapter(chapter.index))
            }
        }
        putResult.exceptionOrNull()?.let { error ->
            if (error is CancellationException) throw error
        }
        if (putResult.isSuccess) {
            if (capture.snapshot.partial) {
                // 部分成功：快照已落盘可离线渲染，但缺失资源，仍按失败计，
                // 进入失败身份列表等待重试；onSnapshotSaved 只归属完整成功。
                sb.append("7. SnapshotStore.put：部分成功（快照已保存可渲染，")
                    .append(capture.droppedResources)
                    .append(" 个资源缺失已剔除/占位，计入失败等待重试）\n")
                return ButtonOutcome(buttonIndex, button.src, sb.toString(), failed = true)
            }
            onSnapshotSaved()
            sb.append("7. SnapshotStore.put：成功\n")
            return ButtonOutcome(buttonIndex, button.src, sb.toString(), success = true)
        }
        sb.append("7. SnapshotStore.put：失败\n")
        sb.append("   ").append(putResult.exceptionOrNull()!!.stackTraceToString()).append('\n')
        return ButtonOutcome(buttonIndex, button.src, sb.toString(), failed = true)
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
     *
     * [RESOLVE_TIMEOUT_MS] 覆盖 click/js 的实际执行和 browser open 回调等待。
     * 不能先同步执行脚本、再给回调等待另起一段完整预算；否则卡住的脚本会无限占用
     * Review pipeline 配额。Rhino 在指令观察点感知该协程取消；不响应取消的宿主调用则
     * 必须由宿主调用自身暴露并修复，不能伪装成已中断。
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
        val resolvedPage = CompletableDeferred<ReviewPage>()
        fun record(url: String, html: String? = null, preloadJs: String? = null) {
            resolvedPage.complete(ReviewPage(url, html, preloadJs))
        }
        return withTimeout(RESOLVE_TIMEOUT_MS) {
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
            resolvedPage.await()
        }
    }

    /**
     * 提取正文里的评论按钮：img 标签选项 JSON 带 style=TEXT 的评论泡。
     * click 与旧源 js 原样带出，交给与用户点击共用的执行逻辑。
     * 附带诊断统计：img 总数、选项 JSON 解析失败数、style 非 TEXT 数、click/js 都为空数。
     */
    fun extractReviewButtons(content: String): List<ReviewButton> {
        return extractReviewButtonsWithStats(content).buttons
    }

    /**
     * 评论入口只读取其领域拥有的原始产物：文本读取 BODY，音频读取原始 lyric。
     * 不使用 effectiveLyric，避免把文字书 overlay 再以音频书身份重复抓取。
     */
    private fun reviewContent(book: Book, chapter: BookChapter): String? {
        require(!book.isVideo) { "video books do not own REVIEW artifacts" }
        return if (book.isAudio) {
            chapter.getVariable("lyric").takeIf { it.isNotBlank() }
        } else {
            BookHelp.getContent(book, chapter)?.takeIf { it.isNotBlank() }
        }
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
