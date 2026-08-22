package io.legado.app.ui.book.cache

import androidx.core.app.NotificationCompat
import io.legado.app.R
import io.legado.app.constant.AppConst
import io.legado.app.constant.NotificationId
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.help.exoplayer.ExoPlayerHelper
import io.legado.app.help.book.isVideo
import io.legado.app.utils.ConvertUtils
import io.legado.app.utils.activityPendingIntent
import io.legado.app.utils.broadcastPendingIntent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import splitties.init.appCtx
import splitties.systemservices.notificationManager
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

object AudioCacheTaskManager {

    const val EXTRA_BOOK_URL = "bookUrl"

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
    private val lastNotifyTimes = ConcurrentHashMap<String, Long>()
    private val lastNotifyStatuses = ConcurrentHashMap<String, CacheTaskStatus>()
    private val requests = ConcurrentHashMap<String, AudioCacheTaskRequest>()
    private val pausingBookUrls = ConcurrentHashMap.newKeySet<String>()
    private val pendingResumeBookUrls = ConcurrentHashMap.newKeySet<String>()
    private val preparingResumeBookUrls = ConcurrentHashMap.newKeySet<String>()
    private val _states = MutableStateFlow<Map<String, AudioCacheTaskState>>(emptyMap())
    val states: StateFlow<Map<String, AudioCacheTaskState>> = _states.asStateFlow()

    fun hasTask(bookUrl: String): Boolean = _states.value[bookUrl]?.active == true

    fun snapshot(bookUrl: String): AudioCacheTaskState? = _states.value[bookUrl]

    fun start(
        book: Book,
        chapters: List<BookChapter>,
        resolver: suspend (Book, BookChapter) -> ExoPlayerHelper.MediaRequest,
        onChapterResolved: ((BookChapter, ExoPlayerHelper.MediaRequest) -> Unit)? = null,
        onChapterFinished: ((BookChapter, Boolean, String?) -> Unit)? = null,
        onFinished: (() -> Unit)? = null,
        coordinatorManaged: Boolean = false,
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
            onChapterFinished = onChapterFinished,
            onFinished = onFinished,
            coordinatorManaged = coordinatorManaged,
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
        if (futures.containsKey(book.bookUrl)) return false
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
        val future = executor.submit {
            var finalStatus: CacheTaskStatus? = null
            var completed = completedOffset
            var activeChapter: BookChapter? = null
            var downloadedBytes = 0L
            var knownTotalBytes = 0L
            var speedBytes = 0L
            var speedWindowStart = System.currentTimeMillis()
            try {
                chapters.forEach { chapter ->
                    if (cancelFlag.get()) throw CancellationException("cancelled")
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
                    val mediaRequest = runBlocking {
                        request.resolver(book, chapter)
                    }
                    request.onChapterResolved?.invoke(chapter, mediaRequest)
                    var chapterKnownLength = 0L
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
                        },
                        shouldCancel = { cancelFlag.get() }
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
                }
                updateState(book.bookUrl) {
                    it.copy(
                        status = CacheTaskStatus.COMPLETED,
                        completedChapters = completed,
                        active = false,
                        speedBytesPerSecond = 0L,
                        message = appCtx.getString(R.string.cache_manage_task_done, completed)
                    )
                }
                finalStatus = CacheTaskStatus.COMPLETED
            } catch (e: CancellationException) {
                finalStatus = if (pausingBookUrls.contains(book.bookUrl)) {
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
                activeChapter?.let { chapter ->
                    request.onChapterFinished?.invoke(chapter, false, e.localizedMessage)
                }
                finalStatus = if (cancelFlag.get() && pausingBookUrls.contains(book.bookUrl)) {
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
                val shouldResume = finalStatus == CacheTaskStatus.PAUSED &&
                    pendingResumeBookUrls.remove(book.bookUrl)
                val remainingChapters = if (shouldResume) {
                    request.chapters.filterNot { isChapterCached(book, it) }
                } else {
                    emptyList()
                }
                val resumeCompletedOffset = if (shouldResume) {
                    (request.totalChapters - remainingChapters.size)
                        .coerceAtLeast(completed)
                        .coerceIn(0, request.totalChapters)
                } else {
                    completed
                }
                cancelFlags.remove(book.bookUrl)
                futures.remove(book.bookUrl)
                pausingBookUrls.remove(book.bookUrl)
                if (shouldResume) {
                    startRequest(request, remainingChapters, resumeCompletedOffset)
                } else {
                    if (finalStatus != CacheTaskStatus.PAUSED) {
                        requests.remove(book.bookUrl)
                    }
                    if (finalStatus == CacheTaskStatus.COMPLETED || request.coordinatorManaged) {
                        request.onFinished?.invoke()
                    }
                }
                lastNotifyTimes.remove(book.bookUrl)
                lastNotifyStatuses.remove(book.bookUrl)
            }
        }
        futures[book.bookUrl] = future
        return true
    }

    fun cancel(bookUrl: String) {
        requests.remove(bookUrl)
        pausingBookUrls.remove(bookUrl)
        pendingResumeBookUrls.remove(bookUrl)
        preparingResumeBookUrls.remove(bookUrl)
        cancelFlags[bookUrl]?.set(true)
        futures[bookUrl]?.cancel(true)
    }

    fun pause(bookUrl: String) {
        val state = _states.value[bookUrl] ?: return
        if (!state.active) return
        pausingBookUrls.add(bookUrl)
        cancelFlags[bookUrl]?.set(true)
        futures[bookUrl]?.cancel(true)
        updateState(bookUrl) {
            it.copy(
                status = CacheTaskStatus.PAUSED,
                active = false,
                speedBytesPerSecond = 0L,
                message = appCtx.getString(R.string.cache_manage_task_paused)
            )
        }
    }

    fun resume(
        bookUrl: String,
        onChapterFinished: ((BookChapter, Boolean, String?) -> Unit)? = null,
        onFinished: (() -> Unit)? = null,
    ): Boolean {
        val state = _states.value[bookUrl] ?: return false
        if (state.status != CacheTaskStatus.PAUSED) return false
        if (futures.containsKey(bookUrl)) {
            pendingResumeBookUrls.add(bookUrl)
            return true
        }
        if (!preparingResumeBookUrls.add(bookUrl)) return true
        executor.execute {
            try {
                var request = requests[bookUrl] ?: return@execute
                if (onChapterFinished != null || onFinished != null) {
                    request = request.copy(
                        onChapterFinished = onChapterFinished ?: request.onChapterFinished,
                        onFinished = onFinished ?: request.onFinished,
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
            }
        }
        return true
    }

    fun togglePause(bookUrl: String) {
        val state = _states.value[bookUrl] ?: return
        if (state.status == CacheTaskStatus.PAUSED) {
            resume(bookUrl)
        } else if (state.active) {
            pause(bookUrl)
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
            ExoPlayerHelper.isMediaCached(chapter.resourceUrl, book)
        }
    }

    private fun updateState(bookUrl: String, transform: (AudioCacheTaskState) -> AudioCacheTaskState) {
        var updatedState: AudioCacheTaskState? = null
        _states.update { states ->
            val current = states[bookUrl] ?: return@update states
            val updated = transform(current)
            // Resume replaces the state through startRequest; in-flight worker snapshots cannot do it.
            if (current.status == CacheTaskStatus.PAUSED && updated.active) {
                return@update states
            }
            updatedState = updated
            states.toMutableMap().apply {
                put(bookUrl, updated)
            }
        }
        updatedState?.let(::notifyState)
    }

    private fun updateState(bookUrl: String, state: AudioCacheTaskState) {
        _states.update { states ->
            states.toMutableMap().apply {
                put(bookUrl, state)
            }
        }
        notifyState(state)
    }

    private fun notifyState(state: AudioCacheTaskState) {
        if (requests[state.bookUrl]?.coordinatorManaged == true) return
        val terminal = !state.active && state.status.isTerminalNotificationStatus()
        val now = System.currentTimeMillis()
        val last = lastNotifyTimes[state.bookUrl] ?: 0L
        val statusChanged = lastNotifyStatuses[state.bookUrl] != state.status
        if (!terminal && !statusChanged && now - last < NOTIFICATION_INTERVAL_MS) return
        lastNotifyTimes[state.bookUrl] = now
        lastNotifyStatuses[state.bookUrl] = state.status
        val chapterPercent = state.currentChapterPercent()
        val totalPosition = when {
            state.status == CacheTaskStatus.COMPLETED -> state.totalChapters
            state.currentChapterIndex > 0 -> state.currentChapterIndex
            else -> state.completedChapters
        }.coerceIn(0, state.totalChapters.coerceAtLeast(0))
        val contentText = if (state.status == CacheTaskStatus.RESOLVING) {
            state.message
        } else {
            appCtx.getString(
                R.string.audio_cache_notification_progress,
                state.bookName,
                chapterPercent?.toString() ?: "--",
                totalPosition,
                state.totalChapters,
            )
        }
        val builder = NotificationCompat.Builder(appCtx, AppConst.channelIdDownload)
            .setSmallIcon(R.drawable.ic_status_bar_r)
            .setContentTitle(appCtx.getString(R.string.offline_cache))
            .setContentText(contentText)
            .setOngoing(state.active)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(
                appCtx.activityPendingIntent<CacheManageActivity>("audioCacheManage") {
                    putExtra(CacheManageActivity.EXTRA_MODE, CacheManageActivity.MODE_AUDIO)
                }
            )
        if (state.active || state.status == CacheTaskStatus.PAUSED) {
            val paused = state.status == CacheTaskStatus.PAUSED
            builder.addAction(
                if (paused) R.drawable.ic_play_24dp else R.drawable.ic_pause_24dp,
                appCtx.getString(if (paused) R.string.resume else R.string.pause),
                appCtx.broadcastPendingIntent<AudioCacheActionReceiver>(
                    "$ACTION_AUDIO_CACHE_TOGGLE:${state.bookUrl.hashCode()}:${state.status}"
                ) {
                    putExtra(EXTRA_BOOK_URL, state.bookUrl)
                }
            )
        }
        state.notificationProgress()?.let { progress ->
            builder.setProgress(progress.max, progress.value, progress.indeterminate)
        } ?: builder.setProgress(0, 0, false)
        notificationManager.notify(NotificationId.AudioCache, builder.build())
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
    val onChapterFinished: ((BookChapter, Boolean, String?) -> Unit)?,
    val onFinished: (() -> Unit)?,
    val coordinatorManaged: Boolean,
    val totalChapters: Int
)

private const val PROGRESS_STATE_INTERVAL_MS = 750L
private const val NOTIFICATION_INTERVAL_MS = 1000L
private const val ACTION_AUDIO_CACHE_TOGGLE = "audioCacheToggle"

private data class CacheNotificationProgress(
    val max: Int,
    val value: Int,
    val indeterminate: Boolean
)

private fun CacheTaskStatus.isTerminalNotificationStatus(): Boolean {
    return this == CacheTaskStatus.COMPLETED ||
        this == CacheTaskStatus.PAUSED ||
        this == CacheTaskStatus.CANCELLED ||
        this == CacheTaskStatus.FAILED
}

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
    val active: Boolean = true
)

private fun AudioCacheTaskState.currentChapterPercent(): Int? {
    val total = currentChapterTotalBytes?.takeIf { it > 0L } ?: return null
    return ((currentChapterBytes.coerceIn(0L, total) * 100L) / total)
        .toInt()
        .coerceIn(0, 100)
}

private fun AudioCacheTaskState.notificationProgress(): CacheNotificationProgress? {
    val chapterPercent = currentChapterPercent()
    if (active) {
        return CacheNotificationProgress(
            max = 100,
            value = chapterPercent ?: 0,
            indeterminate = chapterPercent == null
        )
    }
    if (status != CacheTaskStatus.PAUSED) return null
    if (chapterPercent != null) {
        return CacheNotificationProgress(
            max = 100,
            value = chapterPercent,
            indeterminate = false
        )
    }
    return CacheNotificationProgress(
        max = 100,
        value = 0,
        indeterminate = false
    )
}
