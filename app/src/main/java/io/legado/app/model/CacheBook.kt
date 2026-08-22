package io.legado.app.model

import android.content.Context
import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.constant.AppConst
import io.legado.app.constant.EventBus
import io.legado.app.constant.IntentAction
import io.legado.app.constant.NotificationId
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.CacheManifestHelper
import io.legado.app.help.book.isLocal
import io.legado.app.help.config.AppConfig
import io.legado.app.help.coroutine.CompositeCoroutine
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.lib.permission.NotificationPermission
import io.legado.app.model.webBook.WebBook
import io.legado.app.service.CacheBookService
import io.legado.app.service.ReviewCacheService
import io.legado.app.utils.onEachParallel
import io.legado.app.utils.postEvent
import io.legado.app.utils.startService
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import androidx.core.app.NotificationCompat
import splitties.systemservices.notificationManager
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.CoroutineContext

object CacheBook {

    val cacheBookMap = ConcurrentHashMap<String, CacheBookModel>()

    private val workingState = MutableStateFlow(true)
    private val downloadPauseState = MutableStateFlow(true)
    private val mutex = Mutex()

    @Synchronized
    fun getOrCreate(bookUrl: String): CacheBookModel? {
        val book = appDb.bookDao.getBook(bookUrl) ?: return null
        val bookSource = appDb.bookSourceDao.getBookSource(book.origin) ?: return null
        updateBookSource(bookSource)
        var cacheBook = cacheBookMap[bookUrl]
        if (cacheBook != null) {
            //存在时更新,书源可能会变化,必须更新
            cacheBook.bookSource = bookSource
            cacheBook.book = book
            return cacheBook
        }
        cacheBook = CacheBookModel(bookSource, book)
        cacheBookMap[bookUrl] = cacheBook
        return cacheBook
    }

    @Synchronized
    fun getOrCreate(bookSource: BookSource, book: Book): CacheBookModel {
        updateBookSource(bookSource)
        var cacheBook = cacheBookMap[book.bookUrl]
        if (cacheBook != null) {
            //存在时更新,书源可能会变化,必须更新
            cacheBook.bookSource = bookSource
            cacheBook.book = book
            return cacheBook
        }
        cacheBook = CacheBookModel(bookSource, book)
        cacheBookMap[book.bookUrl] = cacheBook
        return cacheBook
    }

    private fun updateBookSource(newBookSource: BookSource) {
        cacheBookMap.forEach {
            val model = it.value
            if (model.bookSource.bookSourceUrl == newBookSource.bookSourceUrl) {
                model.bookSource = newBookSource
            }
        }
    }

    fun start(context: Context, book: Book, start: Int, end: Int) {
        AppLog.put("开始离线缓存 ${book.name}，章节范围 ${start + 1}-${end + 1}")
        if (book.isLocal) {
            val error = IllegalArgumentException("local book cannot be cached from network")
            AppLog.put("离线缓存拒绝：${book.name} 是本地书", error, true)
            notifyResult(context, R.string.cache_download_failed, "${book.name}: local book")
            return
        }
        NotificationPermission.ensure(
            context,
            onGranted = {
                runCatching {
                    context.startService<CacheBookService> {
                        action = IntentAction.start
                        putExtra("bookUrl", book.bookUrl)
                        putExtra("start", start)
                        putExtra("end", end)
                    }
                }.onFailure {
                    val message = "${book.name}: 启动正文缓存服务失败：${it.localizedMessage}"
                    AppLog.put(message, it, true)
                    notifyResult(context, R.string.cache_download_failed, message)
                }
            },
            onDenied = {
                AppLog.put("离线缓存拒绝：通知权限未授予，${book.name}")
                context.toastOnUi(R.string.notification_permission_required_for_download)
                notifyResult(context, R.string.cache_download_failed, context.getString(R.string.notification_permission_required_for_download))
            }
        )
    }

    private fun notifyResult(context: Context, titleRes: Int, message: String) {
        notificationManager.notify(
            NotificationId.CacheBookService,
            NotificationCompat.Builder(context, AppConst.channelIdDownload)
                .setSmallIcon(R.drawable.ic_status_bar_r)
                .setContentTitle(context.getString(titleRes))
                .setContentText(message)
                .setStyle(NotificationCompat.BigTextStyle().bigText(message))
                .setAutoCancel(false)
                .setOngoing(false)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .build()
        )
    }

    fun remove(context: Context, bookUrl: String) {
        context.startService<CacheBookService> {
            action = IntentAction.remove
            putExtra("bookUrl", bookUrl)
        }
    }

    fun stop(context: Context) {
        var requested = false
        if (ReviewCacheService.isRun) {
            ReviewCacheService.requestStop()
            requested = true
        } else if (CacheBookService.isRun) {
            context.startService<CacheBookService> {
                action = IntentAction.stop
            }
            requested = true
        }
        if (!requested) {
            AppLog.put("停止离线缓存：当前没有运行中的缓存服务")
        }
    }

    /** 清理正文任务状态；评论队列由 ReviewSnapshotManager 单独管理。 */
    fun stopAll() {
        cacheBookMap.forEach { it.value.stop(releaseReviewPhase = false) }
        cacheBookMap.clear()
        downloadPauseState.value = true
        workingState.value = true
        successDownloadSet.clear()
        errorDownloadMap.clear()
        postEvent(EventBus.UP_DOWNLOAD, "")
    }

    fun close() {
        stopAll()
        // 兜底清掉所有残留 Body Phase，异常退出也不留“只登记不执行”状态
        io.legado.app.help.review.ReviewSnapshotManager.cancelAllBodyPhases()
    }

    fun setWorkingState(value: Boolean) {
        workingState.value = value
    }

    val isDownloadPaused: Boolean
        get() = !downloadPauseState.value

    fun pauseDownload() {
        downloadPauseState.value = false
        AppLog.put("离线缓存已暂停")
        postEvent(EventBus.UP_DOWNLOAD_STATE, "")
    }

    fun resumeDownload() {
        downloadPauseState.value = true
        AppLog.put("离线缓存已继续")
        postEvent(EventBus.UP_DOWNLOAD_STATE, "")
    }

    suspend fun awaitDownloadResumed() {
        downloadPauseState.first { it }
    }

    suspend fun startProcessJob(context: CoroutineContext) = mutex.withLock {
        setWorkingState(true)
        flow {
            while (currentCoroutineContext().isActive && cacheBookMap.isNotEmpty()) {
                var emitted = false

                cacheBookMap.forEach { (_, model) ->
                    if (!model.isLoading()) {
                        emit(model)
                        emitted = true
                    }
                    workingState.first { it }
                    downloadPauseState.first { it }
                }

                if (!emitted) {
                    delay(1000)
                }
            }
        }.onStart {
            postEvent(EventBus.UP_DOWNLOAD_STATE, "")
        }.onEachParallel(AppConfig.threadCount) {
            coroutineScope {
                it.download(this, context)
            }
        }.onCompletion {
            postEvent(EventBus.UP_DOWNLOAD_STATE, "")
        }.collect()
    }


    val downloadSummary: String
        get() {
            return "正在下载:${onDownloadCount}|等待中:${waitCount}|失败:${errorDownloadMap.count()}|成功:${successDownloadSet.size}"
        }

    /** 当前正在缓存的书（多本时取第一本活跃的），通知展示“正文 x/y · 评论 a/b”用 */
    fun activeCachingBook(): Book? {
        cacheBookMap.values.firstOrNull { it.isRun() }?.book?.let { return it }
        return null
    }

    /** 当前活跃批量模型的目标正文进度（已缓存章, 目标章数）；空表示无活跃批量 */
    fun activeBodyProgress(): Pair<Int, Int>? {
        cacheBookMap.values.firstOrNull { it.isRun() }?.let { m ->
            if (m.bodyTarget > 0) {
                return m.bodyDone to m.bodyTarget
            }
        }
        return null
    }

    val isRun: Boolean
        get() {
            cacheBookMap.forEach {
                if (it.value.isRun()) {
                    return true
                }
            }
            return false
        }

    private val waitCount: Int
        get() {
            var count = 0
            cacheBookMap.forEach {
                count += it.value.waitCount
            }
            return count
        }

    val onDownloadCount: Int
        get() {
            var count = 0
            cacheBookMap.forEach {
                count += it.value.onDownloadCount
            }
            return count
        }

    val successDownloadSet = linkedSetOf<String>()
    val errorDownloadMap = hashMapOf<String, Int>()

    val successDownloadCount: Int
        get() = successDownloadSet.size

    val failedDownloadCount: Int
        get() = errorDownloadMap.size

    class CacheBookModel(var bookSource: BookSource, var book: Book) {

        private val waitDownloadSet = linkedSetOf<Int>()
        private val onDownloadSet = linkedSetOf<Int>()
        private val tasks = CompositeCoroutine()
        private val successUrls = linkedSetOf<String>()
        private var isStopped = false
        private var waitingRetry = false
        private var isLoading = false

        /** 本次批量缓存的目标章数（addDownload 新增的章累计，停止后重置） */
        private var targetChapterCount = 0

        /** 已完成缓存的正文章数（按章 url 去重） */
        val bodyDone: Int get() = successUrls.size
        val bodyTarget: Int get() = targetChapterCount

        val waitCount get() = waitDownloadSet.size
        val onDownloadCount get() = onDownloadSet.size

        init {
            postEvent(EventBus.UP_DOWNLOAD, book.bookUrl)
        }

        @Synchronized
        fun isRun(): Boolean {
            return waitDownloadSet.isNotEmpty() || onDownloadSet.isNotEmpty() || isLoading
        }

        @Synchronized
        fun isStop(): Boolean {
            return isStopped || (!isRun() && !waitingRetry)
        }

        @Synchronized
        fun isLoading(): Boolean {
            return isLoading
        }

        @Synchronized
        fun setLoading() {
            isLoading = true
        }

        @Synchronized
        fun stop(releaseReviewPhase: Boolean = true) {
            waitDownloadSet.clear()
            tasks.clear()
            isStopped = true
            isLoading = false
            targetChapterCount = 0
            // 用户取消批量缓存：同样必须收掉 Body Phase，否则该书后续评论任务
            // 会一直“只登记不执行”；已登记任务（正文已完成）照常进入 Review Phase
            if (releaseReviewPhase) {
                io.legado.app.help.review.ReviewSnapshotManager.cancelBodyPhase(book.bookUrl)
            }
            postEvent(EventBus.UP_DOWNLOAD, book.bookUrl)
        }

        @Synchronized
        fun addDownload(start: Int, end: Int) {
            if (isStopped) targetChapterCount = 0
            isStopped = false
            for (i in start..end) {
                if (!onDownloadSet.contains(i)) {
                    // 新加入目标队列的章才算本次目标；重复 addDownload 不重复计数
                    if (waitDownloadSet.add(i)) {
                        targetChapterCount++
                    }
                }
            }
            cacheBookMap[book.bookUrl] = this
            isLoading = false
            // 批量缓存开始（Body Phase）：该批正文下载期间评论任务只登记不执行
            io.legado.app.help.review.ReviewSnapshotManager.beginBodyPhase(book.bookUrl)
            postEvent(EventBus.UP_DOWNLOAD, book.bookUrl)
        }

        @Synchronized
        private fun onSuccess(chapter: BookChapter) {
            onDownloadSet.remove(chapter.index)
            successDownloadSet.add(chapter.primaryStr())
            errorDownloadMap.remove(chapter.primaryStr())
            successUrls.add(chapter.url)
            AppLog.put("离线缓存成功 ${book.name}-${chapter.title} (index=${chapter.index})")
        }

        @Synchronized
        private fun onPreError(chapter: BookChapter, error: Throwable) {
            waitingRetry = true
            errorDownloadMap[chapter.primaryStr()] =
                (errorDownloadMap[chapter.primaryStr()] ?: 0) + 1
            AppLog.put("离线缓存尝试失败 ${book.name}-${chapter.title}：${error.localizedMessage}", error)
            onDownloadSet.remove(chapter.index)
        }

        @Synchronized
        private fun onPostError(chapter: BookChapter, error: Throwable) {
            //重试3次
            if ((errorDownloadMap[chapter.primaryStr()] ?: 0) < 3 && !isStopped) {
                waitDownloadSet.add(chapter.index)
            } else {
                AppLog.put(
                    "下载${book.name}-${chapter.title}失败\n${error.localizedMessage}",
                    error
                )
            }
            waitingRetry = false
        }

        @Synchronized
        private fun onError(chapter: BookChapter, error: Throwable) {
            onPreError(chapter, error)
            onPostError(chapter, error)
        }

        @Synchronized
        private fun onCancel(index: Int) {
            onDownloadSet.remove(index)
            if (!isStopped) waitDownloadSet.add(index)
            AppLog.put("离线缓存取消 ${book.name} index=$index")
        }

        @Synchronized
        private fun onFinally() {
            if (waitDownloadSet.isEmpty() && onDownloadSet.isEmpty()) {
                kotlin.runCatching {
                    CacheManifestHelper.refresh(book)
                }
                cacheBookMap.remove(book.bookUrl)
                // 整批目标正文结束（Review Phase 开始）：登记过的评论任务正式执行，
                // 评论进度不参与正文下载状态
                io.legado.app.help.review.ReviewSnapshotManager.endBodyPhase(book.bookUrl)
            }
            postEvent(EventBus.UP_DOWNLOAD, book.bookUrl)
        }

        /**
         * 从待下载列表内取第一条下载
         */
        @Synchronized
        fun download(scope: CoroutineScope, context: CoroutineContext) {
            val chapterIndex = waitDownloadSet.firstOrNull()
            if (chapterIndex == null) {
                if (!isLoading && onDownloadSet.isEmpty()) {
                    cacheBookMap.remove(book.bookUrl)
                    // 整批结束（可能是“全部已缓存命中，无任何正文任务”）：同样必须收掉
                    // Body Phase，否则后补评论场景（正文已缓存，重新缓存即补评论）会卡死；
                    // 仅在活跃批量下收尾，阅读页单章 model 不被误收
                    io.legado.app.help.review.ReviewSnapshotManager
                        .endBodyPhaseIfActive(book.bookUrl)
                }
                return
            }
            if (onDownloadSet.contains(chapterIndex)) {
                waitDownloadSet.remove(chapterIndex)
                return
            }
            val chapter = appDb.bookChapterDao.getChapter(book.bookUrl, chapterIndex) ?: let {
                waitDownloadSet.remove(chapterIndex)
                val error = IllegalStateException("chapter index $chapterIndex is missing")
                errorDownloadMap["${book.bookUrl}#$chapterIndex"] = 1
                AppLog.put("离线缓存失败 ${book.name}：找不到章节索引 $chapterIndex", error)
                onFinally()
                return
            }
            if (chapter.isVolume) {
                /** 修正下载计数 */
                onSuccess(chapter)
                postEvent(EventBus.SAVE_CONTENT, Pair(book, chapter))
                waitDownloadSet.remove(chapterIndex)
                onFinally()
                return
            }
            if (BookHelp.hasImageContent(book, chapter)) {
                // 正文已缓存（图片也齐），无任何正文工作在进行：
                // 用户重新缓存该章，直接补/覆盖评论（force）
                onSuccess(chapter)
                waitDownloadSet.remove(chapterIndex)
                reviewEnqueue(bookSource, book, chapter, force = true)
                onFinally()
                return
            }
            waitDownloadSet.remove(chapterIndex)
            onDownloadSet.add(chapterIndex)
            if (BookHelp.hasContent(book, chapter)) {
                // 正文已缓存但缺图片：先补完图片、成功状态结束，再入队补评论（force）
                Coroutine.async(scope, context, executeContext = context) {
                    BookHelp.getContent(book, chapter)?.let {
                        BookHelp.saveImages(bookSource, book, chapter, it, 1)
                    }
                }.onSuccess {
                    onSuccess(chapter)
                    reviewEnqueue(bookSource, book, chapter, force = true)
                }.onError {
                    onPreError(chapter, it)
                    //出现错误等待一秒后重新加入待下载列表
                    delay(1000)
                    onPostError(chapter, it)
                }.onCancel {
                    onCancel(chapterIndex)
                }.onFinally {
                    onFinally()
                }.let {
                    tasks.add(it)
                }
                return
            }
            WebBook.getContent(
                scope,
                bookSource,
                book,
                chapter,
                context = context,
                start = CoroutineStart.LAZY,
                executeContext = context
            ).onSuccess { content ->
                onSuccess(chapter)
                downloadFinish(chapter, content)
            }.onError {
                onPreError(chapter, it)
                //出现错误等待一秒后重新加入待下载列表
                delay(1000)
                onPostError(chapter, it)
                downloadFinish(chapter, "获取正文失败\n${it.localizedMessage}", success = false)
            }.onCancel {
                onCancel(chapterIndex)
            }.onFinally {
                onFinally()
            }.apply {
                tasks.add(this)
            }.start()
        }

        suspend fun downloadAwait(chapter: BookChapter): String {
            synchronized(this) {
                onDownloadSet.add(chapter.index)
                waitDownloadSet.remove(chapter.index)
            }
            try {
                val content = WebBook.getContentAwait(bookSource, book, chapter)
                onSuccess(chapter)
                ReadBook.downloadedChapters.add(chapter.index)
                ReadBook.downloadFailChapters.remove(chapter.index)
                // 注意：这里不提前入队评论——downloadAwait 调用方拿到正文后
                // 还要做正文排版/刷新状态收尾，由 ReadBook 在真正完成后入队
                return content
            } catch (e: Exception) {
                if (e is CancellationException) {
                    onCancel(chapter.index)
                }
                onError(chapter, e)
                ReadBook.downloadFailChapters[chapter.index] =
                    (ReadBook.downloadFailChapters[chapter.index] ?: 0) + 1
                return "获取正文失败\n${e.localizedMessage}"
            } finally {
                postEvent(EventBus.UP_DOWNLOAD, book.bookUrl)
            }
        }

        @Synchronized
        fun download(
            scope: CoroutineScope,
            chapter: BookChapter,
            semaphore: Semaphore?,
            resetPageOffset: Boolean = false
        ) {
            if (onDownloadSet.contains(chapter.index)) {
                return
            }
            onDownloadSet.add(chapter.index)
            waitDownloadSet.remove(chapter.index)
            WebBook.getContent(
                scope,
                bookSource,
                book,
                chapter,
                start = CoroutineStart.LAZY,
                executeContext = IO,
                semaphore = semaphore
            ).onSuccess { content ->
                onSuccess(chapter)
                ReadBook.downloadedChapters.add(chapter.index)
                ReadBook.downloadFailChapters.remove(chapter.index)
                downloadFinish(chapter, content, resetPageOffset)
            }.onError {
                onError(chapter, it)
                ReadBook.downloadFailChapters[chapter.index] =
                    (ReadBook.downloadFailChapters[chapter.index] ?: 0) + 1
                downloadFinish(chapter, "获取正文失败\n${it.localizedMessage}", resetPageOffset, success = false)
            }.onCancel {
                onCancel(chapter.index)
                downloadFinish(chapter, "download canceled", resetPageOffset, true, success = false)
            }.onFinally {
                postEvent(EventBus.UP_DOWNLOAD, book.bookUrl)
            }.start()
        }

        /**
         * 该章正文全部完成（文本/图片/成功状态/正文刷新状态）之后的统一评论入队出口。
         * 只允许在各下载路径的成功收尾调用；评论任务本身低优先级、失败不影响正文。
         */
        private fun reviewEnqueue(
            bookSource: BookSource,
            book: Book,
            chapter: BookChapter,
            force: Boolean
        ) {
            io.legado.app.help.review.ReviewSnapshotManager.enqueueIfEnabled(
                bookSource, book, chapter, force = force
            )
        }

        /**
         * 下载收尾的统一出口：
         * - 当前阅读中的书：正文刷新（异步排版 job 完成、callBack 通知后）真正结束，
         *   contentLoadFinish 的 success 回调才入队评论；
         * - 非当前书（批量缓存等）：无排版流程，正文下载完成即入队；
         * - 失败/取消不传 success，绝不入队评论。
         */
        private fun downloadFinish(
            chapter: BookChapter,
            content: String,
            resetPageOffset: Boolean = false,
            canceled: Boolean = false,
            success: Boolean = true
        ) {
            val enqueueAfterFinish = {
                reviewEnqueue(
                    bookSource, book, chapter,
                    force = io.legado.app.help.review.ReviewSnapshotManager
                        .isUserRefreshActive(book.bookUrl, chapter.index)
                )
            }
            if (ReadBook.book?.bookUrl == book.bookUrl) {
                ReadBook.contentLoadFinish(
                    book, chapter, content,
                    resetPageOffset = resetPageOffset,
                    canceled = canceled,
                    success = {
                        if (success && !canceled) enqueueAfterFinish()
                    }
                )
            } else if (success && !canceled) {
                enqueueAfterFinish()
            }
        }

    }

}
