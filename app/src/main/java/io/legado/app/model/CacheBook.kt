package io.legado.app.model

import android.content.Context
import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.constant.EventBus
import io.legado.app.constant.IntentAction
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.CacheManifestHelper
import io.legado.app.help.book.isLocal
import io.legado.app.help.cache.CacheOperationDiagnostics
import io.legado.app.help.config.AppConfig
import io.legado.app.help.cache.CacheWorkerLease
import io.legado.app.help.coroutine.CompositeCoroutine
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.lib.permission.NotificationPermission
import io.legado.app.model.webBook.WebBook
import io.legado.app.service.CacheBookService
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
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.CoroutineContext

object CacheBook {

    private val models = ConcurrentHashMap<String, CacheBookModel>()

    private val mutex = Mutex()

    @Synchronized
    fun getOrCreate(bookUrl: String): CacheBookModel? {
        val book = appDb.bookDao.getBook(bookUrl) ?: return null
        val bookSource = appDb.bookSourceDao.getBookSource(book.origin) ?: return null
        updateBookSource(bookSource)
        var cacheBook = models[bookUrl]
        if (cacheBook != null) {
            //存在时更新,书源可能会变化,必须更新
            cacheBook.bookSource = bookSource
            cacheBook.book = book
            return cacheBook
        }
        cacheBook = CacheBookModel(bookSource, book)
        models[bookUrl] = cacheBook
        return cacheBook
    }

    @Synchronized
    fun getOrCreate(bookSource: BookSource, book: Book): CacheBookModel {
        updateBookSource(bookSource)
        var cacheBook = models[book.bookUrl]
        if (cacheBook != null) {
            //存在时更新,书源可能会变化,必须更新
            cacheBook.bookSource = bookSource
            cacheBook.book = book
            return cacheBook
        }
        cacheBook = CacheBookModel(bookSource, book)
        models[book.bookUrl] = cacheBook
        return cacheBook
    }

    private fun updateBookSource(newBookSource: BookSource) {
        models.forEach {
            val model = it.value
            if (model.bookSource.bookSourceUrl == newBookSource.bookSourceUrl) {
                model.bookSource = newBookSource
            }
        }
    }

    internal fun start(
        context: Context,
        book: Book,
        start: Int,
        end: Int,
        coordinatorSessionId: String? = null,
        coordinatorTaskId: String? = null,
        coordinatorGeneration: Long? = null,
    ) {
        AppLog.put("开始离线缓存 ${book.name}，章节范围 ${start + 1}-${end + 1}")
        if (book.isLocal) {
            notifyCoordinatorStartFailure(
                coordinatorSessionId,
                coordinatorTaskId,
                coordinatorGeneration,
                "${book.name}: local book",
            )
            val error = IllegalArgumentException("local book cannot be cached from network")
            AppLog.put("离线缓存拒绝：${book.name} 是本地书", error, true)
            return
        }
        NotificationPermission.ensure(
            context,
            onGranted = {
                val leaseActive = if (
                    coordinatorSessionId == null ||
                    coordinatorTaskId == null ||
                    coordinatorGeneration == null
                ) {
                    true
                } else {
                    io.legado.app.help.cache.CacheBodyWorkerRegistry.isLeaseActive(
                        io.legado.app.help.cache.CacheWorkerLease(
                            coordinatorSessionId,
                            coordinatorTaskId,
                            coordinatorGeneration,
                        )
                    )
                }
                if (!leaseActive) {
                    AppLog.put("忽略已失效的正文缓存启动：${book.bookUrl}")
                } else {
                    runCatching {
                        context.startService<CacheBookService> {
                            action = IntentAction.start
                            putExtra("bookUrl", book.bookUrl)
                            putExtra("start", start)
                            putExtra("end", end)
                            putExtra("coordinatorSessionId", coordinatorSessionId)
                            putExtra("coordinatorTaskId", coordinatorTaskId)
                            coordinatorGeneration?.let { putExtra("coordinatorGeneration", it) }
                        }
                    }.onFailure {
                        val message = "${book.name}: 启动正文缓存服务失败：${it.localizedMessage}"
                        AppLog.put(message, it, true)
                        notifyCoordinatorStartFailure(
                            coordinatorSessionId,
                            coordinatorTaskId,
                            coordinatorGeneration,
                            message,
                        )
                    }
                }
            },
            onDenied = {
                AppLog.put("离线缓存拒绝：通知权限未授予，${book.name}")
                notifyCoordinatorStartFailure(
                    coordinatorSessionId,
                    coordinatorTaskId,
                    coordinatorGeneration,
                    context.getString(R.string.notification_permission_required_for_download),
                )
                context.toastOnUi(R.string.notification_permission_required_for_download)
            }
        )
    }

    private fun notifyCoordinatorStartFailure(
        sessionId: String?,
        taskId: String?,
        generation: Long?,
        message: String,
    ) {
        if (sessionId == null || taskId == null || generation == null) return
        io.legado.app.help.cache.CacheBodyWorkerRegistry.onStartRejected(
            io.legado.app.help.cache.CacheWorkerLease(sessionId, taskId, generation),
            message,
        )
    }

    /** Stop one coordinator-owned book without touching other cache books. */
    internal fun stop(bookUrl: String) {
        models[bookUrl]?.stop()
        models.remove(bookUrl)
    }

    internal suspend fun runProcessJob(context: CoroutineContext) = mutex.withLock {
        flow {
            while (currentCoroutineContext().isActive && models.isNotEmpty()) {
                var emitted = false

                models.forEach { (_, model) ->
                    if (!model.isLoading()) {
                        emit(model)
                        emitted = true
                    }
                }

                if (!emitted) {
                    delay(1000)
                }
            }
        }.onEachParallel(AppConfig.threadCount) {
            coroutineScope {
                it.download(this, context)
            }
        }.collect()
    }
    internal fun hasPendingWork(): Boolean = models.values.any { it.hasWork() }

    internal fun hasActiveBook(bookUrl: String): Boolean = models[bookUrl]?.hasWork() == true

    /** Hand the book from direct reader loads to the Coordinator without keeping dead retries. */
    @Synchronized
    internal fun prepareForCoordinator(bookUrl: String): Boolean {
        val model = models[bookUrl] ?: return true
        if (!model.canDetachReaderQueue()) return false
        model.stop()
        models.remove(bookUrl, model)
        return true
    }

    class CacheBookModel(var bookSource: BookSource, var book: Book) {

        private val waitDownloadSet = linkedSetOf<Int>()
        private val onDownloadSet = linkedSetOf<Int>()
        private val tasks = CompositeCoroutine()
        private var isStopped = false
        private var isLoading = false
        private var coordinatorLease: CacheWorkerLease? = null

        private val errorDownloadMap = hashMapOf<String, Int>()

        @Synchronized
        internal fun hasWork(): Boolean {
            return waitDownloadSet.isNotEmpty() || onDownloadSet.isNotEmpty() || isLoading
        }

        @Synchronized
        internal fun isLoading(): Boolean {
            return isLoading
        }

        @Synchronized
        internal fun canDetachReaderQueue(): Boolean {
            return !isLoading && onDownloadSet.isEmpty()
        }

        @Synchronized
        internal fun setLoading() {
            isLoading = true
        }

        @Synchronized
        internal fun stop() {
            waitDownloadSet.clear()
            tasks.clear()
            coordinatorLease = null
            isStopped = true
            isLoading = false
        }

        @Synchronized
        internal fun addDownload(
            start: Int,
            end: Int,
            executionLease: CacheWorkerLease? = null,
        ) {
            coordinatorLease = executionLease
            isStopped = false
            for (i in start..end) {
                if (!onDownloadSet.contains(i)) {
                    waitDownloadSet.add(i)
                }
            }
            models[book.bookUrl] = this
            isLoading = false
        }

        @Synchronized
        private fun onSuccess(chapter: BookChapter, executionLease: CacheWorkerLease? = null) {
            onDownloadSet.remove(chapter.index)
            errorDownloadMap.remove(chapter.primaryStr())
            executionLease?.let {
                io.legado.app.help.cache.CacheBodyWorkerRegistry.onChapterSuccess(it, chapter.index)
            }
            AppLog.put("离线缓存成功 ${book.name}-${chapter.title} (index=${chapter.index})")
        }

        @Synchronized
        private fun onPreError(chapter: BookChapter, error: Throwable) {
            errorDownloadMap[chapter.primaryStr()] =
                (errorDownloadMap[chapter.primaryStr()] ?: 0) + 1
            AppLog.put("离线缓存尝试失败 ${book.name}-${chapter.title}：${error.localizedMessage}", error)
            onDownloadSet.remove(chapter.index)
        }

        @Synchronized
        private fun onPostError(
            chapter: BookChapter,
            error: Throwable,
            executionLease: CacheWorkerLease? = null,
        ) {
            //重试3次
            if ((errorDownloadMap[chapter.primaryStr()] ?: 0) < 3 && !isStopped) {
                waitDownloadSet.add(chapter.index)
            } else {
                executionLease?.let {
                    io.legado.app.help.cache.CacheBodyWorkerRegistry.onChapterFailed(
                        it,
                        chapter.index,
                        error.localizedMessage,
                    )
                }
                AppLog.put(
                    "下载${book.name}-${chapter.title}失败\n${error.localizedMessage}",
                    error
                )
            }
        }

        @Synchronized
        private fun onError(
            chapter: BookChapter,
            error: Throwable,
            executionLease: CacheWorkerLease? = null,
        ) {
            onPreError(chapter, error)
            onPostError(chapter, error, executionLease)
        }

        @Synchronized
        private fun onCancel(index: Int) {
            onDownloadSet.remove(index)
            if (!isStopped) waitDownloadSet.add(index)
            AppLog.put("离线缓存取消 ${book.name} index=$index")
        }

        @Synchronized
        private fun onFinally(executionLease: CacheWorkerLease? = null) {
            if (waitDownloadSet.isEmpty() && onDownloadSet.isEmpty()) {
                kotlin.runCatching {
                    CacheManifestHelper.refresh(book)
                }
                models.remove(book.bookUrl)
                executionLease?.let {
                    io.legado.app.help.cache.CacheBodyWorkerRegistry.onWorkerFinished(it)
                }
            }
        }

        /**
         * 从待下载列表内取第一条下载
         */
        @Synchronized
        internal fun download(scope: CoroutineScope, context: CoroutineContext) {
            val executionLease = coordinatorLease
            val chapterIndex = waitDownloadSet.firstOrNull()
            if (chapterIndex == null) {
                if (!isLoading && onDownloadSet.isEmpty()) {
                    models.remove(book.bookUrl)
                    if (executionLease != null) {
                        onFinally(executionLease)
                    }
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
                executionLease?.let {
                    io.legado.app.help.cache.CacheBodyWorkerRegistry.onChapterFailed(
                        it,
                        chapterIndex,
                        error.localizedMessage,
                    )
                }
                AppLog.put("离线缓存失败 ${book.name}：找不到章节索引 $chapterIndex", error)
                onFinally(executionLease)
                return
            }
            if (chapter.isVolume) {
                /** 修正下载计数 */
                onSuccess(chapter, executionLease)
                postEvent(EventBus.SAVE_CONTENT, Pair(book, chapter))
                waitDownloadSet.remove(chapterIndex)
                onFinally(executionLease)
                return
            }
            if (BookHelp.hasImageContent(book, chapter)) {
                // 正文与图片均已完整，本章 BODY 直接成功。
                onSuccess(chapter, executionLease)
                waitDownloadSet.remove(chapterIndex)
                onFinally(executionLease)
                return
            }
            waitDownloadSet.remove(chapterIndex)
            onDownloadSet.add(chapterIndex)
            if (BookHelp.hasContent(book, chapter)) {
                val imageTrace = executionLease?.let { lease ->
                    CacheOperationDiagnostics.begin(
                        CacheOperationDiagnostics.Context(
                            domain = CacheOperationDiagnostics.Domain.BODY,
                            sessionId = lease.sessionId,
                            taskId = lease.taskId,
                            generation = lease.generation,
                            chapterIndex = chapter.index,
                        ),
                        "BODY_IMAGE_REPAIR",
                    )
                }
                // 正文已缓存但缺图片：先补完图片，再结束本章 BODY。
                Coroutine.async(scope, context, executeContext = context) {
                    BookHelp.getContent(book, chapter)?.let { content ->
                        BookHelp.saveImages(bookSource, book, chapter, content, 1)
                        content.length
                    } ?: 0
                }.onSuccess { contentChars ->
                    imageTrace?.done(
                        CacheOperationDiagnostics.Metrics(outputChars = contentChars),
                        "BODY_IMAGES_SAVED",
                    )
                    onSuccess(chapter, executionLease)
                }.onError {
                    imageTrace?.fail(it)
                    onPreError(chapter, it)
                    //出现错误等待一秒后重新加入待下载列表
                    delay(1000)
                    onPostError(chapter, it, executionLease)
                }.onCancel {
                    imageTrace?.cancelled()
                    onCancel(chapterIndex)
                }.onFinally {
                    onFinally(executionLease)
                }.let {
                    tasks.add(it)
                }
                return
            }
            val bodyTrace = executionLease?.let { lease ->
                CacheOperationDiagnostics.begin(
                    CacheOperationDiagnostics.Context(
                        domain = CacheOperationDiagnostics.Domain.BODY,
                        sessionId = lease.sessionId,
                        taskId = lease.taskId,
                        generation = lease.generation,
                        chapterIndex = chapter.index,
                    ),
                    "BODY_FETCH",
                )
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
                // WebBook only returns after its parser and BookHelp.saveContent() have completed.
                bodyTrace?.done(
                    CacheOperationDiagnostics.Metrics(outputChars = content.length),
                    "BODY_CONTENT_SAVED",
                )
                onSuccess(chapter, executionLease)
                downloadFinish(chapter, content)
            }.onError {
                bodyTrace?.fail(it)
                onPreError(chapter, it)
                //出现错误等待一秒后重新加入待下载列表
                delay(1000)
                onPostError(chapter, it, executionLease)
                downloadFinish(
                    chapter,
                    "获取正文失败\n${it.localizedMessage}",
                )
            }.onCancel {
                bodyTrace?.cancelled()
                onCancel(chapterIndex)
            }.onFinally {
                onFinally(executionLease)
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
                // downloadAwait 只负责正文；评论由完整的 Coordinator BODY→REVIEW 会话处理。
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
                downloadFinish(chapter, "获取正文失败\n${it.localizedMessage}", resetPageOffset)
            }.onCancel {
                onCancel(chapter.index)
                downloadFinish(chapter, "download canceled", resetPageOffset, canceled = true)
            }.onFinally {
            }.start()
        }

        /**
         * 把正文下载结果交回当前阅读会话；评论只由 Coordinator 的后续阶段处理。
         */
        private fun downloadFinish(
            chapter: BookChapter,
            content: String,
            resetPageOffset: Boolean = false,
            canceled: Boolean = false,
        ) {
            if (ReadBook.book?.bookUrl == book.bookUrl) {
                ReadBook.contentLoadFinish(
                    book, chapter, content,
                    resetPageOffset = resetPageOffset,
                    canceled = canceled,
                )
            }
        }

    }

}
