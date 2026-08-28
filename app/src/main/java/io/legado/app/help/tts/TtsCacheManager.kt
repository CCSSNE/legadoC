package io.legado.app.help.tts

import android.os.Build
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import io.legado.app.constant.AppPattern
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.ContentProcessor
import io.legado.app.help.book.simulatedTotalChapterNum
import io.legado.app.help.cache.CacheOperationDiagnostics
import io.legado.app.help.cache.CacheWorkerLease
import io.legado.app.help.config.AppConfig
import io.legado.app.ui.book.read.page.provider.ChapterProvider
import io.legado.app.ui.book.read.page.entities.TextChapter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import splitties.init.appCtx
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume

/**
 * TTS 音频合成调度器（登记端）。只负责 TTS 缓存任务的队列管理与逐章合成执行；
 * 依赖、生命周期和结果归属由 CacheCoordinator 管理。
 *
 * 章节消费者固定 1（宿主 TtsCacheService）；章内逐单元顺序合成（系统 TTS 引擎
 * 不保证并发合成安全）。无 force/重试语义：重试 = 重新提交 TEXT+TTS 任务，
 * 已合成的单元由 [TtsCacheStore.has] 命中跳过，天然幂等。
 *
 * 与朗读引擎的参数同源约定：引擎（[TtsCacheParams.engineValue]）、语速
 * （[TtsCacheParams.speechRateValue]，跟随系统时不设置）、音色（引擎默认
 * voice，不主动 setVoice，key 取实例实际生效 voice name）、朗读单元序列
 * （与 BaseReadAloudService 同款：当前阅读配置排版 + getNeedReadAloud 切分）。
 */
internal object TtsCacheManager {

    enum class TaskResult { SUCCEEDED, FAILED, STOPPED }

    data class QueueTask(
        val key: String,
        val book: Book,
        val chapter: BookChapter,
        val executionLease: CacheWorkerLease,
        val commitIfLeaseActive: ((() -> Unit) -> Boolean),
        val reportProgress: (processedUnits: Int, totalUnits: Int, failedUnits: Int) -> Unit,
    )

    private class ExecutionGroup(
        val lease: CacheWorkerLease,
        val queued: MutableSet<String> = linkedSetOf(),
        val claimed: MutableSet<String> = linkedSetOf(),
        val activeJobs: MutableMap<String, Job> = linkedMapOf(),
        val stopCallbacks: MutableList<() -> Unit> = mutableListOf(),
        var stopRequested: Boolean = false,
    )

    private val channel = Channel<QueueTask>(Channel.UNLIMITED)
    private val executionGroups = linkedMapOf<String, ExecutionGroup>()
    private val queueLock = Any()
    private val tempDir: File by lazy {
        File(appCtx.cacheDir, "tts_cache_tmp").apply { mkdirs() }
    }

    internal fun enqueue(
        book: Book,
        chapter: BookChapter,
        executionLease: CacheWorkerLease,
        commitIfLeaseActive: ((() -> Unit) -> Boolean),
        reportProgress: (Int, Int, Int) -> Unit,
    ) {
        synchronized(queueLock) {
            val key = keyOf(book, chapter)
            val group = executionGroups.getOrPut(leaseKey(executionLease)) {
                ExecutionGroup(executionLease)
            }
            check(group.lease == executionLease && !group.stopRequested) {
                "tts execution lease is already stopping: ${leaseKey(executionLease)}"
            }
            check(group.queued.add(key)) { "tts queue already contains chapter: $key" }
            channel.trySend(
                QueueTask(
                    key = key,
                    book = book,
                    chapter = chapter,
                    executionLease = executionLease,
                    commitIfLeaseActive = commitIfLeaseActive,
                    reportProgress = reportProgress,
                )
            )
        }
    }

    /** TTS 宿主消费：取一个任务（无任务返回 null）。 */
    internal fun tryTakeTask(): QueueTask? {
        return synchronized(queueLock) {
            val task = channel.tryReceive().getOrNull() ?: return null
            val group = requireNotNull(executionGroups[leaseKey(task.executionLease)]) {
                "tts queue entry has no execution group: ${task.key}"
            }
            check(group.queued.remove(task.key)) {
                "tts queue entry was not registered as queued: ${task.key}"
            }
            check(group.claimed.add(task.key)) {
                "tts queue entry was already claimed: ${task.key}"
            }
            task
        }
    }

    /** Stop one Coordinator task and acknowledge only after every claimed/active chapter exits. */
    internal fun stopTask(sessionId: String, taskId: String, onStopped: () -> Unit) {
        val ownerKey = "$sessionId/$taskId"
        val jobsToCancel = mutableListOf<Job>()
        var callbacks = emptyList<() -> Unit>()
        synchronized(queueLock) {
            val groups = executionGroups.values.filter { group -> taskKey(group.lease) == ownerKey }
            check(groups.size <= 1) { "multiple tts generations are active for $ownerKey" }
            val group = groups.singleOrNull()
            if (group == null) {
                callbacks = listOf(onStopped)
                return@synchronized
            }
            group.stopRequested = true
            group.stopCallbacks += onStopped
            val retained = ArrayList<QueueTask>()
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
            job.cancel(CancellationException("tts Coordinator task stopped: $ownerKey"))
        }
        callbacks.forEach { it() }
        TtsCacheLog.put("tts cache stop requested: $ownerKey")
    }

    /** TTS 宿主处理任务（suspend：内部排版、逐单元合成、落盘） */
    internal suspend fun processTask(task: QueueTask): TaskResult {
        val lease = task.executionLease
        val diagnostics = CacheOperationDiagnostics.Context(
            domain = CacheOperationDiagnostics.Domain.TTS,
            sessionId = lease.sessionId,
            taskId = lease.taskId,
            generation = lease.generation,
            chapterIndex = task.chapter.index,
        )
        var group: ExecutionGroup? = null
        return try {
            coroutineScope {
                group = registerActiveExecution(
                    task,
                    currentCoroutineContext()[Job] ?: error("tts task has no coroutine Job"),
                )
                if (group?.stopRequested == true) return@coroutineScope TaskResult.STOPPED
                val success = runCatching {
                    processChapter(task, diagnostics, this)
                }.onFailure {
                    if (it !is CancellationException) {
                        TtsCacheLog.put("章节 ${task.key} 处理失败\n${it.localizedMessage}", it)
                    }
                }.getOrElse { false }
                if (success) TaskResult.SUCCEEDED else TaskResult.FAILED
            }
        } catch (error: CancellationException) {
            if (group?.let(::isStopRequested) == true) TaskResult.STOPPED else throw error
        } finally {
            group?.let { unregisterActiveExecution(task, it) }
        }
    }

    private suspend fun processChapter(
        task: QueueTask,
        diagnostics: CacheOperationDiagnostics.Context,
        scope: CoroutineScope,
    ): Boolean {
        val book = task.book
        val chapter = task.chapter
        val trace = CacheOperationDiagnostics.begin(diagnostics, "TTS_CHAPTER")
        try {
            // 1. 正文（TEXT+TTS 只读本领域产物；正文缺失直接失败，不静默兜底）
            val rawContent = BookHelp.getContent(book, chapter)
            if (rawContent.isNullOrBlank()) {
                TtsCacheLog.put("第${chapter.index + 1}章 正文不可用，无法合成")
                trace.fail(IllegalStateException("body content unavailable"))
                return false
            }
            // 2. 与朗读引擎同参排版：替换规则 + 标题处理 + TextChapter 布局
            val contentProcessor = ContentProcessor.get(book.name, book.origin)
            val displayTitle = chapter.getDisplayTitle(
                contentProcessor.getTitleReplaceRules(),
                book.getUseReplaceRule(),
                replaceBook = book.toReplaceBook(),
            )
            val contents = contentProcessor.getContent(
                book,
                chapter,
                rawContent,
                includeTitle = false,
            )
            val textChapter = ChapterProvider.getTextChapterAsync(
                scope,
                book,
                chapter,
                displayTitle,
                contents,
                book.simulatedTotalChapterNum(),
            )
            textChapter.layoutChannel.receiveAsFlow().collect()
            if (!textChapter.isCompleted || textChapter.pages.isEmpty()) {
                TtsCacheLog.put("第${chapter.index + 1}章 排版失败")
                trace.fail(IllegalStateException("text chapter layout failed"))
                return false
            }
            // 3. 朗读单元序列（与 BaseReadAloudService.prepareReadAloudChapter 同款）
            val contentList = textChapter.getNeedReadAloud(0, AppConfig.pageSplit, 0)
                .split("\n")
                .filter { it.isNotEmpty() }
            // 4. 逐单元合成
            val success = synthesizeChapter(task, contentList, trace)
            if (success) {
                trace.done(CacheOperationDiagnostics.Metrics(unitCount = contentList.size))
            } else {
                trace.fail(IllegalStateException("one or more tts units failed"))
            }
            return success
        } catch (error: Throwable) {
            trace.fail(error)
            throw error
        }
    }

    private suspend fun synthesizeChapter(
        task: QueueTask,
        contentList: List<String>,
        trace: CacheOperationDiagnostics.Operation,
    ): Boolean {
        val book = task.book
        val chapter = task.chapter
        val readableUnits = contentList
            .filterNot { it.matches(AppPattern.notReadAloudRegex) }
        if (readableUnits.isEmpty()) {
            // 卷标记/空章：无朗读单元，按成功收敛
            task.reportProgress(0, 0, 0)
            trace.done(CacheOperationDiagnostics.Metrics())
            return true
        }
        val tts = createTextToSpeech(TtsCacheParams.engineValue(book))
        if (tts == null) {
            TtsCacheLog.put("第${chapter.index + 1}章 TTS 引擎初始化失败")
            return false
        }
        var failed = 0
        var processed = 0
        try {
            // 语速：与朗读引擎同规则；跟随系统时交给引擎自身设置
            if (!AppConfig.ttsFlowSys) {
                runCatching { tts.setSpeechRate(TtsCacheParams.speechRateValue()) }
            }
            val voiceName = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                runCatching { tts.voice?.name }.getOrNull()
            } else {
                null
            }
            val total = readableUnits.size
            readableUnits.forEachIndexed { index, text ->
                currentCoroutineContext().ensureActive()
                val key = TtsCacheStore.buildUnitKey(book, chapter, text, voiceName)
                val target = TtsCacheStore.unitFile(book, key)
                if (TtsCacheStore.has(book, key)) {
                    processed++
                    task.reportProgress(processed, total, failed)
                    return@forEachIndexed
                }
                val tempFile = File(tempDir, "${target.name}.$index.tmp")
                tempFile.delete()
                val synthesized = synthesizeUnit(tts, text, tempFile, "ttsCache_$index")
                if (synthesized && tempFile.isFile && tempFile.length() > 0L) {
                    val committed = task.commitIfLeaseActive {
                        TtsCacheStore.commit(tempFile, target)
                    }
                    if (!committed) {
                        tempFile.delete()
                        throw CancellationException("tts lease is no longer active at cache commit")
                    }
                } else {
                    tempFile.delete()
                    failed++
                    TtsCacheLog.put("第${chapter.index + 1}章 单元${index + 1} 合成失败")
                }
                processed++
                task.reportProgress(processed, total, failed)
            }
            return failed == 0
        } finally {
            runCatching { tts.stop() }
            runCatching { tts.shutdown() }
        }
    }

    private suspend fun createTextToSpeech(engine: String?): TextToSpeech? =
        suspendCancellableCoroutine { cont ->
            val resumed = AtomicBoolean(false)
            fun finish(instance: TextToSpeech?) {
                if (resumed.compareAndSet(false, true)) cont.resume(instance)
            }
            // init 回调经主线程异步投递，先建实例再登记；holder 兜住极端早到回调
            val holder = AtomicReference<TextToSpeech?>()
            val callback: (Int) -> Unit = { status ->
                val instance = holder.get()
                when {
                    instance == null -> Unit
                    status == TextToSpeech.SUCCESS -> finish(instance)
                    else -> {
                        runCatching { instance.shutdown() }
                        finish(null)
                    }
                }
            }
            val instance = if (engine.isNullOrBlank()) {
                TextToSpeech(appCtx, callback)
            } else {
                TextToSpeech(appCtx, callback, engine)
            }
            holder.set(instance)
            cont.invokeOnCancellation { runCatching { instance.shutdown() } }
        }

    private suspend fun synthesizeUnit(
        tts: TextToSpeech,
        text: String,
        tempFile: File,
        utteranceId: String,
    ): Boolean = suspendCancellableCoroutine { cont ->
        val resumed = AtomicBoolean(false)
        fun finish(ok: Boolean) {
            if (resumed.compareAndSet(false, true)) cont.resume(ok)
        }
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onDone(id: String?) {
                if (id == utteranceId) finish(true)
            }

            @Deprecated("Deprecated in Java")
            override fun onError(id: String?) {
                if (id == utteranceId) finish(false)
            }
        })
        val requested = runCatching {
            tts.synthesizeToFile(text, Bundle(), tempFile, utteranceId)
        }.getOrElse {
            TtsCacheLog.put("合成请求出错\n${it.localizedMessage}", it)
            TextToSpeech.ERROR
        }
        if (requested == TextToSpeech.ERROR) finish(false)
        cont.invokeOnCancellation { runCatching { tts.stop() } }
    }

    private fun keyOf(book: Book, chapter: BookChapter) = "${book.bookUrl}|${chapter.index}"

    private fun taskKey(lease: CacheWorkerLease): String = "${lease.sessionId}/${lease.taskId}"

    private fun leaseKey(lease: CacheWorkerLease): String =
        "${taskKey(lease)}/${lease.generation}"

    private fun registerActiveExecution(task: QueueTask, job: Job): ExecutionGroup {
        var callbacks = emptyList<() -> Unit>()
        val group = synchronized(queueLock) {
            val lease = task.executionLease
            val current = requireNotNull(executionGroups[leaseKey(lease)]) {
                "tts claimed task has no execution group: ${task.key}"
            }
            check(current.claimed.remove(task.key)) {
                "tts task was not claimed before execution: ${task.key}"
            }
            if (!current.stopRequested) {
                check(current.activeJobs.put(task.key, job) == null) {
                    "tts task is already active: ${task.key}"
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
}
