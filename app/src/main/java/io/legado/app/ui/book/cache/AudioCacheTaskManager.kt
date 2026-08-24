package io.legado.app.ui.book.cache

import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.help.book.AudioOfflineState
import io.legado.app.help.exoplayer.ExoPlayerHelper
import io.legado.app.help.book.isVideo
import io.legado.app.help.cache.ChapterDownloadPacer
import io.legado.app.help.cache.CacheOperationDiagnostics
import io.legado.app.help.config.AppConfig
import io.legado.app.utils.ConvertUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import splitties.init.appCtx
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.FutureTask
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

internal object AudioCacheTaskManager {

    private val executor: ExecutorService = Executors.newSingleThreadExecutor(
        ThreadFactory { runnable ->
            Thread(runnable, "audio-cache-worker").apply {
                isDaemon = true
                priority = Thread.NORM_PRIORITY - 1
            }
        }
    )
    private val cancelFlags = ConcurrentHashMap<String, AtomicBoolean>()
    private val futures = ConcurrentHashMap<String, Future<*>>()
    private val workerThreads = ConcurrentHashMap<String, Thread>()
    private val requests = ConcurrentHashMap<String, AudioCacheTaskRequest>()
    private val stopRequests = ConcurrentHashMap<String, StopRequest>()
    private val preparingResumeBookUrls = ConcurrentHashMap.newKeySet<String>()
    private val _states = MutableStateFlow<Map<String, AudioCacheTaskState>>(emptyMap())
    internal fun snapshot(bookUrl: String): AudioCacheTaskState? = _states.value[bookUrl]

    internal fun start(
        book: Book,
        chapters: List<BookChapter>,
        resolver: suspend (Book, BookChapter) -> ExoPlayerHelper.MediaRequest,
        onChapterResolved: ((BookChapter, ExoPlayerHelper.MediaRequest) -> Unit)? = null,
        onChapterStarted: ((BookChapter) -> Unit)? = null,
        onChapterProgress: ((BookChapter, Long, Long?) -> Unit)? = null,
        onChapterFinished: ((BookChapter, Boolean, String?) -> Unit)? = null,
        onFinished: (() -> Unit)? = null,
        diagnostics: CacheOperationDiagnostics.Context? = null,
    ): Boolean {
        if (chapters.isEmpty()) return false
        val existing = _states.value[book.bookUrl]
        if (existing?.active == true) return false
        if (existing?.status == CacheTaskStatus.PAUSED) return false
        if (futures.containsKey(book.bookUrl)) return false
        val request = AudioCacheTaskRequest(
            book = book,
            chapters = chapters,
            resolver = resolver,
            onChapterResolved = onChapterResolved,
            onChapterStarted = onChapterStarted,
            onChapterProgress = onChapterProgress,
            onChapterFinished = onChapterFinished,
            onFinished = onFinished,
            diagnostics = diagnostics,
            totalChapters = chapters.size
        )
        if (requests.putIfAbsent(book.bookUrl, request) != null) return false
        val started = startRequest(request, chapters, completedOffset = 0)
        if (!started) requests.remove(book.bookUrl, request)
        return started
    }

    private fun startRequest(
        request: AudioCacheTaskRequest,
        chapters: List<BookChapter>,
        completedOffset: Int
    ): Boolean {
        val book = request.book
        if (chapters.isEmpty()) {
            updateState(
                book.bookUrl,
                AudioCacheTaskState(
                    bookUrl = book.bookUrl,
                    bookName = book.name,
                    totalChapters = request.totalChapters,
                    completedChapters = request.totalChapters,
                    status = CacheTaskStatus.COMPLETED,
                    active = false,
                    message = appCtx.getString(R.string.cache_manage_task_done, request.totalChapters)
                )
            )
            requests.remove(book.bookUrl)
            request.onFinished?.invoke()
            return true
        }
        val cancelFlag = AtomicBoolean(false)
        cancelFlags[book.bookUrl] = cancelFlag
        updateState(
            book.bookUrl,
            AudioCacheTaskState(
                bookUrl = book.bookUrl,
                bookName = book.name,
                totalChapters = request.totalChapters,
                completedChapters = completedOffset,
                status = CacheTaskStatus.PENDING,
                message = appCtx.getString(R.string.data_loading)
            )
        )
        val future = FutureTask<Unit> {
            workerThreads[book.bookUrl] = Thread.currentThread()
            var finalStatus: CacheTaskStatus? = null
            var completed = completedOffset
            var activeChapter: BookChapter? = null
            var downloadedBytes = 0L
            var knownTotalBytes = 0L
            var speedBytes = 0L
            var speedWindowStart = System.currentTimeMillis()
            var activeTrace: CacheOperationDiagnostics.Operation? = null
            var failedChapters = 0
            var lastChapterFailure: String? = null
            try {
                chapters.forEach { chapter ->
                    var retries = 0
                    var chapterFinished = false
                    while (!chapterFinished) {
                        if (cancelFlag.get()) throw CancellationException("cancelled")
                        val mayStart = runBlocking {
                            ChapterDownloadPacer.awaitStartSlot { !cancelFlag.get() }
                        }
                        if (!mayStart || cancelFlag.get()) throw CancellationException("cancelled")
                        activeChapter = chapter
                        val displayIndex = (completed + 1).coerceAtMost(request.totalChapters)
                        updateState(
                            book.bookUrl
                        ) {
                            it.copy(
                                status = CacheTaskStatus.RESOLVING,
                                currentChapterTitle = chapter.title,
                                currentChapterIndex = displayIndex,
                                completedChapters = completed,
                                currentChapterBytes = 0L,
                                currentChapterTotalBytes = null,
                                active = true,
                                message = appCtx.getString(
                                    R.string.cache_manage_resolving_chapter,
                                    displayIndex,
                                    request.totalChapters
                                )
                            )
                        }
                        request.onChapterStarted?.invoke(chapter)
                        try {
                            val chapterDiagnostics = request.diagnostics?.forChapter(chapter.index)
                            activeTrace = chapterDiagnostics?.let {
                                CacheOperationDiagnostics.begin(it, "MEDIA_RESOLVE")
                            }
                            val mediaRequest = runBlocking {
                                request.resolver(book, chapter)
                            }
                            activeTrace?.done(
                                CacheOperationDiagnostics.Metrics(outputChars = mediaRequest.url.length),
                                "MEDIA_URL_READY",
                            )
                            activeTrace = null
                            request.onChapterResolved?.invoke(chapter, mediaRequest)
                            var chapterKnownLength = 0L
                            activeTrace = chapterDiagnostics?.let {
                                CacheOperationDiagnostics.begin(
                                    it,
                                    "MEDIA_CACHE",
                                    CacheOperationDiagnostics.Metrics(inputChars = mediaRequest.url.length),
                                )
                            }
                            ExoPlayerHelper.cacheMedia(
                                request = mediaRequest,
                                useVideoCache = book.isVideo,
                                book = book,
                                progress = progress@{ requestLength, bytesCached, newBytesCached ->
                                    if (cancelFlag.get()) throw CancellationException("cancelled")
                                    if (requestLength > 0 && bytesCached <= requestLength) {
                                        val previousKnown = chapterKnownLength
                                        chapterKnownLength = max(chapterKnownLength, requestLength)
                                        knownTotalBytes += (chapterKnownLength - previousKnown)
                                    }
                                    downloadedBytes += newBytesCached.coerceAtLeast(0L)
                                    speedBytes += newBytesCached.coerceAtLeast(0L)
                                    val now = System.currentTimeMillis()
                                    val delta = (now - speedWindowStart).coerceAtLeast(1L)
                                    if (delta < PROGRESS_STATE_INTERVAL_MS) return@progress
                                    val speed = speedBytes * 1000L / delta
                                    speedBytes = 0L
                                    speedWindowStart = now
                                    updateState(book.bookUrl) {
                                        it.copy(
                                            status = CacheTaskStatus.CACHING,
                                            currentChapterTitle = chapter.title,
                                            currentChapterIndex = displayIndex,
                                            completedChapters = completed,
                                            currentChapterBytes = bytesCached.coerceAtLeast(0L),
                                            currentChapterTotalBytes = chapterKnownLength
                                                .takeIf { value -> value > 0L },
                                            downloadedBytes = downloadedBytes,
                                            totalBytes = knownTotalBytes.takeIf { value -> value > 0L },
                                            speedBytesPerSecond = speed,
                                            active = true,
                                            message = buildProgressMessage(
                                                completed = completed,
                                                total = request.totalChapters,
                                                downloadedBytes = downloadedBytes,
                                                totalBytes = knownTotalBytes.takeIf { value -> value > 0L },
                                                speedBytes = speed
                                            )
                                        )
                                    }
                                    request.onChapterProgress?.invoke(
                                        chapter,
                                        bytesCached.coerceAtLeast(0L),
                                        chapterKnownLength.takeIf { value -> value > 0L },
                                    )
                                },
                                shouldCancel = { cancelFlag.get() }
                            )
                            activeTrace?.done(
                                CacheOperationDiagnostics.Metrics(outputBytes = chapterKnownLength),
                                "MEDIA_CACHE_WRITTEN",
                            )
                            activeTrace = null
                            if (!book.isVideo) {
                                val offlineState = AudioOfflineState.inspect(book, chapter)
                                require(offlineState.isComplete) {
                                    offlineState.incompleteReason()
                                }
                            }
                            request.onChapterProgress?.invoke(
                                chapter,
                                chapterKnownLength.coerceAtLeast(0L),
                                chapterKnownLength.takeIf { value -> value > 0L },
                            )
                            request.onChapterFinished?.invoke(chapter, true, null)
                            activeChapter = null
                            completed += 1
                            updateState(book.bookUrl) {
                                it.copy(
                                    status = CacheTaskStatus.CACHING,
                                    completedChapters = completed,
                                    currentChapterTitle = chapter.title,
                                    currentChapterIndex = displayIndex,
                                    currentChapterBytes = chapterKnownLength.coerceAtLeast(0L),
                                    currentChapterTotalBytes = chapterKnownLength
                                        .takeIf { value -> value > 0L },
                                    active = true,
                                    message = buildProgressMessage(
                                        completed = completed,
                                        total = request.totalChapters,
                                        downloadedBytes = downloadedBytes,
                                        totalBytes = knownTotalBytes.takeIf { value -> value > 0L },
                                        speedBytes = _states.value[book.bookUrl]?.speedBytesPerSecond ?: 0L
                                    )
                                )
                            }
                            chapterFinished = true
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Exception) {
                            if (cancelFlag.get()) {
                                activeTrace?.cancelled()
                                activeTrace = null
                                throw CancellationException("cancelled")
                            }
                            activeTrace?.fail(error)
                            activeTrace = null
                            AppLog.put(
                                "媒体缓存尝试失败 ${book.name}-${chapter.title}：${error.localizedMessage}",
                                error,
                            )
                            if (retries < AppConfig.downloadChapterRetryCount) {
                                retries += 1
                            } else {
                                request.onChapterFinished?.invoke(chapter, false, error.localizedMessage)
                                activeChapter = null
                                failedChapters += 1
                                lastChapterFailure = error.localizedMessage
                                chapterFinished = true
                            }
                        }
                    }
                }
                updateState(book.bookUrl) {
                    val status = if (failedChapters == 0) {
                        CacheTaskStatus.COMPLETED
                    } else {
                        CacheTaskStatus.FAILED
                    }
                    it.copy(
                        status = status,
                        completedChapters = completed,
                        active = false,
                        speedBytesPerSecond = 0L,
                        message = if (status == CacheTaskStatus.COMPLETED) {
                            appCtx.getString(R.string.cache_manage_task_done, completed)
                        } else {
                            lastChapterFailure ?: appCtx.getString(R.string.error)
                        }
                    )
                }
                finalStatus = if (failedChapters == 0) {
                    CacheTaskStatus.COMPLETED
                } else {
                    CacheTaskStatus.FAILED
                }
            } catch (e: CancellationException) {
                activeTrace?.cancelled()
                activeTrace = null
                finalStatus = if (stopRequests[book.bookUrl]?.mode == StopMode.PAUSE) {
                    CacheTaskStatus.PAUSED
                } else {
                    CacheTaskStatus.CANCELLED
                }
                updateState(book.bookUrl) {
                    it.copy(
                        status = finalStatus ?: CacheTaskStatus.CANCELLED,
                        active = false,
                        speedBytesPerSecond = 0L,
                        message = appCtx.getString(
                            if (finalStatus == CacheTaskStatus.PAUSED) {
                                R.string.cache_manage_task_paused
                            } else {
                                R.string.cache_manage_task_cancelled
                            }
                        )
                    )
                }
            } catch (e: Exception) {
                activeTrace?.fail(e)
                activeTrace = null
                activeChapter?.let { chapter ->
                    request.onChapterFinished?.invoke(chapter, false, e.localizedMessage)
                }
                finalStatus = if (
                    cancelFlag.get() && stopRequests[book.bookUrl]?.mode == StopMode.PAUSE
                ) {
                    CacheTaskStatus.PAUSED
                } else {
                    CacheTaskStatus.FAILED
                }
                updateState(book.bookUrl) {
                    it.copy(
                        status = finalStatus ?: CacheTaskStatus.FAILED,
                        active = false,
                        speedBytesPerSecond = 0L,
                        message = if (finalStatus == CacheTaskStatus.PAUSED) {
                            appCtx.getString(R.string.cache_manage_task_paused)
                        } else {
                            e.localizedMessage ?: appCtx.getString(R.string.error)
                        }
                    )
                }
            } finally {
                activeTrace?.cancelled()
                workerThreads.remove(book.bookUrl, Thread.currentThread())
                cancelFlags.remove(book.bookUrl)
                futures.remove(book.bookUrl)
                val stopRequest = stopRequests.remove(book.bookUrl)
                if (stopRequest != null) {
                    val stoppedStatus = when (stopRequest.mode) {
                        StopMode.PAUSE -> CacheTaskStatus.PAUSED
                        StopMode.CANCEL -> CacheTaskStatus.CANCELLED
                    }
                    updateStoppedState(book.bookUrl, stoppedStatus)
                    if (stopRequest.mode == StopMode.CANCEL) {
                        requests.remove(book.bookUrl)
                    }
                    stopRequest.onStopped()
                } else {
                    requests.remove(book.bookUrl)
                    if (finalStatus == CacheTaskStatus.COMPLETED ||
                        finalStatus == CacheTaskStatus.FAILED
                    ) {
                        request.onFinished?.invoke()
                    }
                }
            }
        }
        if (futures.putIfAbsent(book.bookUrl, future) != null) {
            cancelFlags.remove(book.bookUrl, cancelFlag)
            return false
        }
        executor.execute(future)
        return true
    }

    internal fun cancel(bookUrl: String, onStopped: () -> Unit) {
        requestStop(bookUrl, StopRequest(StopMode.CANCEL, onStopped))
    }

    internal fun pause(bookUrl: String, onStopped: () -> Unit) {
        requestStop(bookUrl, StopRequest(StopMode.PAUSE, onStopped))
    }

    internal fun resume(
        bookUrl: String,
        onChapterStarted: ((BookChapter) -> Unit)? = null,
        onChapterProgress: ((BookChapter, Long, Long?) -> Unit)? = null,
        onChapterFinished: ((BookChapter, Boolean, String?) -> Unit)? = null,
        onFinished: (() -> Unit)? = null,
        diagnostics: CacheOperationDiagnostics.Context? = null,
    ): Boolean {
        val state = _states.value[bookUrl] ?: return false
        if (state.status != CacheTaskStatus.PAUSED) return false
        check(!futures.containsKey(bookUrl)) {
            "media worker resumed before pause completed: $bookUrl"
        }
        if (!preparingResumeBookUrls.add(bookUrl)) return true
        executor.execute {
            try {
                if (stopRequests.containsKey(bookUrl)) return@execute
                var request = requests[bookUrl] ?: return@execute
                if (onChapterStarted != null || onChapterProgress != null ||
                    onChapterFinished != null || onFinished != null || diagnostics != null
                ) {
                    request = request.copy(
                        onChapterStarted = onChapterStarted ?: request.onChapterStarted,
                        onChapterProgress = onChapterProgress ?: request.onChapterProgress,
                        onChapterFinished = onChapterFinished ?: request.onChapterFinished,
                        onFinished = onFinished ?: request.onFinished,
                        diagnostics = diagnostics ?: request.diagnostics,
                    )
                    requests[bookUrl] = request
                }
                val latestState = _states.value[bookUrl] ?: return@execute
                if (latestState.status != CacheTaskStatus.PAUSED || futures.containsKey(bookUrl)) {
                    return@execute
                }
                val remainingChapters = request.chapters
                    .filterNot { isChapterCached(request.book, it) }
                val completedOffset = (request.totalChapters - remainingChapters.size)
                    .coerceAtLeast(latestState.completedChapters)
                    .coerceIn(0, request.totalChapters)
                startRequest(request, remainingChapters, completedOffset)
            } finally {
                preparingResumeBookUrls.remove(bookUrl)
                completeStopIfIdle(bookUrl)
            }
        }
        return true
    }

    private fun requestStop(bookUrl: String, stopRequest: StopRequest) {
        stopRequests.compute(bookUrl) { _, current ->
            when {
                current == null -> stopRequest
                current.mode == StopMode.PAUSE && stopRequest.mode == StopMode.CANCEL -> stopRequest
                else -> current
            }
        }
        cancelFlags[bookUrl]?.set(true)
        workerThreads[bookUrl]?.interrupt()
        completeStopIfIdle(bookUrl)
    }

    private fun completeStopIfIdle(bookUrl: String) {
        if (futures.containsKey(bookUrl) || preparingResumeBookUrls.contains(bookUrl)) return
        val completedStop = stopRequests.remove(bookUrl) ?: return
        val status = when (completedStop.mode) {
            StopMode.PAUSE -> CacheTaskStatus.PAUSED
            StopMode.CANCEL -> CacheTaskStatus.CANCELLED
        }
        updateStoppedState(bookUrl, status)
        if (completedStop.mode == StopMode.CANCEL) {
            requests.remove(bookUrl)
        }
        completedStop.onStopped()
    }

    private fun updateStoppedState(bookUrl: String, status: CacheTaskStatus) {
        updateState(bookUrl) {
            it.copy(
                status = status,
                active = false,
                speedBytesPerSecond = 0L,
                message = appCtx.getString(
                    if (status == CacheTaskStatus.PAUSED) {
                        R.string.cache_manage_task_paused
                    } else {
                        R.string.cache_manage_task_cancelled
                    }
                )
            )
        }
    }

    private fun buildProgressMessage(
        completed: Int,
        total: Int,
        downloadedBytes: Long,
        totalBytes: Long?,
        speedBytes: Long
    ): String {
        val downloadedText = ConvertUtils.formatFileSize(downloadedBytes)
        val totalText = totalBytes?.let(ConvertUtils::formatFileSize) ?: "?"
        val speedText = if (speedBytes > 0L) {
            ConvertUtils.formatFileSize(speedBytes) + "/s"
        } else {
            "--"
        }
        return appCtx.getString(
            R.string.cache_manage_task_progress,
            completed,
            total,
            downloadedText,
            totalText,
            speedText
        )
    }

    private fun isChapterCached(book: Book, chapter: BookChapter): Boolean {
        return if (book.isVideo) {
            ExoPlayerHelper.isVideoCached(chapter.resourceUrl, book)
        } else {
            AudioOfflineState.isComplete(book, chapter)
        }
    }

    private fun updateState(bookUrl: String, transform: (AudioCacheTaskState) -> AudioCacheTaskState) {
        _states.update { states ->
            val current = states[bookUrl] ?: return@update states
            val updated = transform(current)
            // Resume replaces the state through startRequest; in-flight worker snapshots cannot do it.
            if (current.status == CacheTaskStatus.PAUSED && updated.active) {
                return@update states
            }
            states.toMutableMap().apply {
                put(bookUrl, updated)
            }
        }
    }

    private fun updateState(bookUrl: String, state: AudioCacheTaskState) {
        _states.update { states ->
            states.toMutableMap().apply {
                put(bookUrl, state)
            }
        }
    }
}

enum class CacheTaskStatus {
    PENDING,
    RESOLVING,
    CACHING,
    PAUSED,
    COMPLETED,
    CANCELLED,
    FAILED
}

private data class AudioCacheTaskRequest(
    val book: Book,
    val chapters: List<BookChapter>,
    val resolver: suspend (Book, BookChapter) -> ExoPlayerHelper.MediaRequest,
    val onChapterResolved: ((BookChapter, ExoPlayerHelper.MediaRequest) -> Unit)?,
    val onChapterStarted: ((BookChapter) -> Unit)?,
    val onChapterProgress: ((BookChapter, Long, Long?) -> Unit)?,
    val onChapterFinished: ((BookChapter, Boolean, String?) -> Unit)?,
    val onFinished: (() -> Unit)?,
    val diagnostics: CacheOperationDiagnostics.Context?,
    val totalChapters: Int
)

private enum class StopMode {
    PAUSE,
    CANCEL,
}

private data class StopRequest(
    val mode: StopMode,
    val onStopped: () -> Unit,
)

private const val PROGRESS_STATE_INTERVAL_MS = 750L

data class AudioCacheTaskState(
    val bookUrl: String,
    val bookName: String,
    val totalChapters: Int,
    val completedChapters: Int = 0,
    val currentChapterIndex: Int = 0,
    val currentChapterTitle: String? = null,
    val currentChapterBytes: Long = 0L,
    val currentChapterTotalBytes: Long? = null,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long? = null,
    val speedBytesPerSecond: Long = 0L,
    val status: CacheTaskStatus = CacheTaskStatus.PENDING,
    val message: String = "",
    val active: Boolean = true,
)
