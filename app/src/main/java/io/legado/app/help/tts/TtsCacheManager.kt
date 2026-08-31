package io.legado.app.help.tts

import android.os.Build
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.script.ScriptException
import io.legado.app.constant.AppPattern
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.HttpTTS
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.cache.CacheOperationDiagnostics
import io.legado.app.help.cache.CacheWorkerLease
import io.legado.app.help.config.AppConfig
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.plugin.ReadAloudEngines
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import okhttp3.Response
import org.mozilla.javascript.WrappedException
import splitties.init.appCtx
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
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
            return when (val unitsResult = TtsChapterUnits.of(book, chapter, scope)) {
                is TtsChapterUnits.Result.ContentUnavailable -> {
                    TtsCacheLog.put("第${chapter.index + 1}章 正文不可用，无法合成")
                    trace.fail(IllegalStateException("body content unavailable"))
                    false
                }

                is TtsChapterUnits.Result.LayoutFailed -> {
                    TtsCacheLog.put("第${chapter.index + 1}章 排版失败")
                    trace.fail(IllegalStateException("text chapter layout failed"))
                    false
                }

                is TtsChapterUnits.Result.Ok -> {
                    // 2. 朗读单元序列（与 BaseReadAloudService.prepareReadAloudChapter 同款）
                    val contentList = unitsResult.units
                    // 3. 逐单元合成
                    val success = synthesizeChapter(task, contentList, trace)
                    if (success) {
                        trace.done(CacheOperationDiagnostics.Metrics(unitCount = contentList.size))
                    } else {
                        trace.fail(IllegalStateException("one or more tts units failed"))
                    }
                    success
                }
            }
        } catch (error: Throwable) {
            trace.fail(error)
            throw error
        }
    }

    /**
     * 引擎分流（与 [TtsCacheParams.kind] 同源）：在线类引擎（HTTP / V2 脚本）走
     * 单元级并发网络合成（并发数 = ttsCacheHttpThreadCount）；插件引擎（本地百度等）
     * 经插件合成能力离线串行合成；系统引擎走 TextToSpeech 串行合成。
     * 书源音频引擎不可能到达（提交端已拒绝媒体书），到达即失败暴露。
     */
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
        return when (TtsCacheParams.kind(book)) {
            TtsCacheParams.Kind.HTTP -> synthesizeChapterHttp(
                task, readableUnits, TtsCacheParams.engineSelection(book).orEmpty(), trace
            )
            TtsCacheParams.Kind.SCRIPT -> synthesizeChapterScript(task, readableUnits, trace)
            TtsCacheParams.Kind.PLUGIN -> synthesizeChapterPlugin(task, readableUnits, trace)
            TtsCacheParams.Kind.SOURCE_AUDIO -> {
                TtsCacheLog.put("第${chapter.index + 1}章 书源音频引擎不支持 TTS 批量缓存")
                trace.fail(IllegalStateException("source audio engine has no tts cache"))
                false
            }
            TtsCacheParams.Kind.SYSTEM -> synthesizeChapterSysTts(
                task, readableUnits, TtsCacheParams.engineValue(book), trace
            )
        }
    }

    /** 在线类引擎共用：单元级并发执行 + 进度归并。 */
    private suspend fun synthesizeUnitsConcurrently(
        task: QueueTask,
        readableUnits: List<String>,
        label: String,
        synthesizeUnit: suspend (index: Int, text: String) -> Boolean,
    ): Boolean {
        val total = readableUnits.size
        var processed = 0
        var failed = 0
        val progressLock = Any()
        val concurrency = AppConfig.ttsCacheHttpThreadCount
        val semaphore = Semaphore(concurrency)
        TtsCacheLog.put("第${task.chapter.index + 1}章 $label 单元:$total 并发:$concurrency")
        coroutineScope {
            readableUnits.forEachIndexed { index, text ->
                launch {
                    semaphore.withPermit {
                        currentCoroutineContext().ensureActive()
                        val ok = synthesizeUnit(index, text)
                        synchronized(progressLock) {
                            processed++
                            if (!ok) failed++
                            task.reportProgress(processed, total, failed)
                        }
                    }
                }
            }
        }
        return failed == 0
    }

    /** 在线(HTTP) TTS 引擎分支：单元级并发请求，语速换算与校验与朗读同源。 */
    private suspend fun synthesizeChapterHttp(
        task: QueueTask,
        readableUnits: List<String>,
        engineId: String,
        trace: CacheOperationDiagnostics.Operation,
    ): Boolean {
        val chapter = task.chapter
        val httpTTS = appDb.httpTTSDao.get(engineId.toLong())
        if (httpTTS == null) {
            TtsCacheLog.put("第${chapter.index + 1}章 HTTP TTS 配置不存在：$engineId")
            trace.fail(IllegalStateException("http tts config missing: $engineId"))
            return false
        }
        return synthesizeUnitsConcurrently(task, readableUnits, "在线合成") { index, text ->
            synthesizeUnitHttp(task, httpTTS, index, text)
        }
    }

    /**
     * V2 脚本引擎分支：与朗读脚本管线同源（[TtsScriptEngineClient.getSynthesisResponse]，
     * 语速取引擎生效速度），单元级并发与在线分支一致；当前启用音色参与缓存 key。
     */
    private suspend fun synthesizeChapterScript(
        task: QueueTask,
        readableUnits: List<String>,
        trace: CacheOperationDiagnostics.Operation,
    ): Boolean {
        val book = task.book
        val chapter = task.chapter
        val engine = TtsEngineStore.enabledScriptEngineForSelection(
            TtsCacheParams.engineSelection(book)
        )
        if (engine == null) {
            TtsCacheLog.put("第${chapter.index + 1}章 朗读脚本引擎不存在或已禁用")
            trace.fail(IllegalStateException("script tts engine missing or disabled"))
            return false
        }
        return synthesizeUnitsConcurrently(task, readableUnits, "脚本合成") { index, text ->
            synthesizeUnitScript(task, engine, index, text)
        }
    }

    /** 脚本引擎单单元：命中缓存直接成功，否则聚合响应字节落盘（产物后缀按引擎为 mp3）。 */
    private suspend fun synthesizeUnitScript(
        task: QueueTask,
        engine: TtsEngineSetting,
        index: Int,
        text: String,
    ): Boolean {
        val book = task.book
        val chapter = task.chapter
        val key = TtsCacheParams.playbackUnitKey(book, chapter, text)
        val target = TtsCacheStore.unitFile(book, key)
        if (TtsCacheStore.has(book, key)) {
            return true
        }
        val tempFile = File(tempDir, "${target.name}.$index.tmp")
        tempFile.delete()
        try {
            val bytes = TtsScriptEngineClient.getSynthesisResponse(
                engine = engine,
                text = text,
                speed = TtsSpeedPolicy.synthesisSpeed(engine),
            ).use { it.body.bytes() }
            if (bytes.isEmpty()) {
                TtsCacheLog.put("第${chapter.index + 1}章 单元${index + 1} 脚本合成返回空音频")
                return false
            }
            tempFile.writeBytes(bytes)
            val committed = task.commitIfLeaseActive {
                TtsCacheStore.commit(tempFile, target)
            }
            if (!committed) {
                tempFile.delete()
                throw CancellationException("tts lease is no longer active at cache commit")
            }
            return true
        } catch (error: CancellationException) {
            tempFile.delete()
            throw error
        } catch (error: Exception) {
            tempFile.delete()
            TtsCacheLog.put(
                "第${chapter.index + 1}章 单元${index + 1} 脚本合成失败\n${error.localizedMessage}",
                error,
            )
            return false
        }
    }

    /**
     * 插件引擎分支（本地百度等）：经插件注册的合成能力离线串行合成。
     * 离线引擎不允许并发，逐单元顺序执行；合成文本经插件侧收口（终止标点），
     * 缓存 key 文本保持朗读单元原文，与播放端命中同源。
     */
    private suspend fun synthesizeChapterPlugin(
        task: QueueTask,
        readableUnits: List<String>,
        trace: CacheOperationDiagnostics.Operation,
    ): Boolean {
        val book = task.book
        val chapter = task.chapter
        val selection = TtsCacheParams.engineSelection(book)
        val plugin = ReadAloudEngines.byId(selection)
        val synthesizer = plugin?.cacheSynthesizer
        if (synthesizer == null) {
            TtsCacheLog.put(
                "第${chapter.index + 1}章 朗读引擎「${plugin?.engineLabel ?: selection}」不支持批量缓存合成"
            )
            trace.fail(IllegalStateException("plugin engine has no tts cache synthesizer"))
            return false
        }
        val voiceKey = synthesizer.activeVoiceKey()
        if (voiceKey == null) {
            TtsCacheLog.put("第${chapter.index + 1}章 插件引擎音色未就绪（如未导入语音包）")
            trace.fail(IllegalStateException("plugin engine voice not ready"))
            return false
        }
        val total = readableUnits.size
        var processed = 0
        var failed = 0
        // 语速与朗读服务同公式（[TtsCacheParams.speechRateValue]）
        val speed = TtsCacheParams.speechRateValue()
        TtsCacheLog.put("第${chapter.index + 1}章 插件离线合成 单元:$total")
        readableUnits.forEachIndexed { index, text ->
            currentCoroutineContext().ensureActive()
            val ok = runCatching {
                withTimeout(AppConfig.ttsCacheSegmentTimeoutSeconds * 1000L) {
                    synthesizer.synthesize(text, speed)
                }
            }.map { bytes ->
                commitUnitBytes(task, book, chapter, text, voiceKey, index, bytes)
            }.getOrElse { error ->
                when (error) {
                    // 看门狗超时：本单元按失败记录，不取消整章任务
                    is TimeoutCancellationException -> {
                        TtsCacheLog.put(
                            "第${chapter.index + 1}章 单元${index + 1} 插件合成超时" +
                                "(${AppConfig.ttsCacheSegmentTimeoutSeconds}s)"
                        )
                        false
                    }
                    is CancellationException -> throw error
                    else -> {
                        TtsCacheLog.put(
                            "第${chapter.index + 1}章 单元${index + 1} 插件合成失败\n${error.localizedMessage}",
                            error,
                        )
                        false
                    }
                }
            }
            if (!ok) failed++
            processed++
            task.reportProgress(processed, total, failed)
        }
        return failed == 0
    }

    /** 单元字节落盘：命中跳过，临时文件经 lease 提交。 */
    private suspend fun commitUnitBytes(
        task: QueueTask,
        book: Book,
        chapter: BookChapter,
        text: String,
        voiceKey: String?,
        index: Int,
        bytes: ByteArray,
    ): Boolean {
        val key = TtsCacheStore.buildUnitKey(book, chapter, text, voiceKey)
        val target = TtsCacheStore.unitFile(book, key)
        if (TtsCacheStore.has(book, key)) {
            return true
        }
        if (bytes.isEmpty()) {
            TtsCacheLog.put("第${chapter.index + 1}章 单元${index + 1} 合成返回空音频")
            return false
        }
        val tempFile = File(tempDir, "${target.name}.$index.tmp")
        tempFile.delete()
        try {
            tempFile.writeBytes(bytes)
            val committed = task.commitIfLeaseActive {
                TtsCacheStore.commit(tempFile, target)
            }
            if (!committed) {
                tempFile.delete()
                throw CancellationException("tts lease is no longer active at cache commit")
            }
            return true
        } catch (error: CancellationException) {
            tempFile.delete()
            throw error
        } catch (error: Exception) {
            tempFile.delete()
            TtsCacheLog.put(
                "第${chapter.index + 1}章 单元${index + 1} 缓存提交失败\n${error.localizedMessage}",
                error,
            )
            return false
        }
    }

    /** 在线引擎单单元：命中缓存直接成功，否则请求音频落盘（产物后缀按引擎为 mp3）。 */
    private suspend fun synthesizeUnitHttp(
        task: QueueTask,
        httpTTS: HttpTTS,
        index: Int,
        text: String,
    ): Boolean {
        val book = task.book
        val chapter = task.chapter
        // 在线引擎无音色维度，voice 记 default（与 key 约定一致）
        val key = TtsCacheParams.playbackUnitKey(book, chapter, text)
        val target = TtsCacheStore.unitFile(book, key)
        if (TtsCacheStore.has(book, key)) {
            return true
        }
        val tempFile = File(tempDir, "${target.name}.$index.tmp")
        tempFile.delete()
        try {
            val bytes = requestHttpAudio(httpTTS, text)
            if (bytes == null || bytes.isEmpty()) {
                TtsCacheLog.put("第${chapter.index + 1}章 单元${index + 1} 在线合成失败")
                return false
            }
            tempFile.writeBytes(bytes)
            val committed = task.commitIfLeaseActive {
                TtsCacheStore.commit(tempFile, target)
            }
            if (!committed) {
                tempFile.delete()
                throw CancellationException("tts lease is no longer active at cache commit")
            }
            return true
        } catch (error: CancellationException) {
            tempFile.delete()
            throw error
        } catch (error: Exception) {
            tempFile.delete()
            TtsCacheLog.put(
                "第${chapter.index + 1}章 单元${index + 1} 在线请求失败\n${error.localizedMessage}",
                error,
            )
            return false
        }
    }

    /**
     * 在线 TTS 请求（与 HttpReadAloudService.getSpeakStream 同源：同一 URL 模板、
     * loginCheckJs、语速换算与 Content-Type 校验）。批量缓存不做静音兜底，
     * 超时/网络错误重试 3 次后失败返回 null。
     */
    private suspend fun requestHttpAudio(httpTTS: HttpTTS, speakText: String): ByteArray? {
        var retryCount = 0
        while (true) {
            try {
                val analyzeUrl = AnalyzeUrl(
                    httpTTS.url,
                    speakText = speakText,
                    speakSpeed = AppConfig.speechRatePlay + 5,
                    source = httpTTS,
                    readTimeout = 300 * 1000L,
                    coroutineContext = currentCoroutineContext(),
                )
                val checkJs = httpTTS.loginCheckJs
                var response = analyzeUrl.getResponseAwait()
                currentCoroutineContext().ensureActive()
                if (!checkJs.isNullOrBlank()) {
                    response = analyzeUrl.evalJS(checkJs, response) as Response
                }
                response.headers["Content-Type"]?.let { rawContentType ->
                    val contentType = rawContentType.substringBefore(";")
                    if (contentType == "application/json" || contentType.startsWith("text/")) {
                        throw NoStackTraceException(response.body.string())
                    }
                    httpTTS.contentType?.takeIf { it.isNotBlank() }?.let { expected ->
                        if (!contentType.matches(expected.toRegex())) {
                            throw NoStackTraceException("在线TTS返回错误：" + response.body.string())
                        }
                    }
                }
                return response.body.bytes()
            } catch (error: CancellationException) {
                throw error
            } catch (error: ScriptException) {
                TtsCacheLog.put("在线合成 js 错误\n${error.localizedMessage}", error)
                return null
            } catch (error: WrappedException) {
                TtsCacheLog.put("在线合成 js 错误\n${error.localizedMessage}", error)
                return null
            } catch (error: Exception) {
                retryCount++
                if (retryCount > 3) {
                    return null
                }
                TtsCacheLog.put("在线合成请求失败，重试 $retryCount/3\n${error.localizedMessage}")
                delay(1000L * retryCount)
            }
        }
    }

    /** 系统引擎分支：专用 TextToSpeech 实例顺序合成（引擎不保证并发安全）。 */
    private suspend fun synthesizeChapterSysTts(
        task: QueueTask,
        readableUnits: List<String>,
        engine: String?,
        trace: CacheOperationDiagnostics.Operation,
    ): Boolean {
        val book = task.book
        val chapter = task.chapter
        val tts = TtsCacheParams.createSystemTts(engine)
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
