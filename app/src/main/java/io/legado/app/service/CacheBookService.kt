package io.legado.app.service

import android.content.Intent
import androidx.lifecycle.lifecycleScope
import io.legado.app.base.BaseService
import io.legado.app.constant.AppConst
import io.legado.app.constant.AppLog
import io.legado.app.constant.IntentAction
import io.legado.app.constant.NotificationId
import io.legado.app.data.appDb
import io.legado.app.help.book.update
import io.legado.app.help.cache.CacheBodyWorkerRegistry
import io.legado.app.help.cache.CacheCoordinator
import io.legado.app.help.cache.CacheLifecycle
import io.legado.app.help.cache.CacheNotificationBridge
import io.legado.app.help.cache.CacheWorkerLease
import io.legado.app.help.config.AppConfig
import io.legado.app.model.CacheBook
import io.legado.app.model.webBook.WebBook
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.Executors
import kotlin.math.min

/** Android execution host for Coordinator-owned text BODY workers. */
class CacheBookService : BaseService() {

    private val threadCount = AppConfig.threadCount
    private val cachePool = Executors
        .newFixedThreadPool(min(threadCount, AppConst.MAX_THREAD))
        .asCoroutineDispatcher()
    private var downloadJob: Job? = null
    private val coordinatorLeases = linkedMapOf<String, CacheWorkerLease>()
    private val mutex = Mutex()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == IntentAction.start) {
            val bookUrl = intent.getStringExtra("bookUrl")
            val sessionId = intent.getStringExtra("coordinatorSessionId")
            val taskId = intent.getStringExtra("coordinatorTaskId")
            val generation = intent.getLongExtra("coordinatorGeneration", Long.MIN_VALUE)
            if (bookUrl != null && sessionId != null && taskId != null && generation != Long.MIN_VALUE) {
                synchronized(coordinatorLeases) {
                    coordinatorLeases[bookUrl] = CacheWorkerLease(sessionId, taskId, generation)
                }
            }
            addDownloadData(
                bookUrl,
                intent.getIntExtra("start", 0),
                intent.getIntExtra("end", 0),
            )
        }
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onDestroy() {
        val outstanding = outstandingCoordinatorLeases()
        if (outstanding.isNotEmpty()) {
            AppLog.put("正文缓存宿主异常结束：收敛 ${outstanding.size} 个 Coordinator 任务")
            outstanding.forEach { (bookUrl, lease) ->
                CacheBodyWorkerRegistry.onExecutionFailed(lease, "正文缓存宿主异常结束")
                releaseLease(bookUrl)
            }
        }
        stopForeground(false)
        downloadJob?.cancel()
        cachePool.close()
        synchronized(coordinatorLeases) { coordinatorLeases.clear() }
        super.onDestroy()
    }

    private fun addDownloadData(bookUrl: String?, start: Int, end: Int) {
        if (bookUrl.isNullOrBlank()) {
            failAll("正文缓存缺少 bookUrl")
            stopSelf()
            return
        }
        execute {
            val cacheBook = CacheBook.getOrCreate(bookUrl) ?: run {
                fail(bookUrl, "book not found: $bookUrl")
                stopSelf()
                return@execute
            }
            val chapterCount = appDb.bookChapterDao.getChapterCount(bookUrl)
            val book = cacheBook.book
            if (chapterCount == 0) {
                cacheBook.setLoading()
                mutex.withLock {
                    if (book.tocUrl.isEmpty()) {
                        WebBook.getBookInfoAwait(cacheBook.bookSource, book).onFailure {
                            val message = "${book.name} 目录为空且加载详情失败：${it.localizedMessage}"
                            fail(bookUrl, message, it)
                            removeDownload(bookUrl)
                            return@execute
                        }
                    }
                    WebBook.getChapterListAwait(cacheBook.bookSource, book).onFailure {
                        if (book.totalChapterNum > 0) {
                            book.totalChapterNum = 0
                            book.update()
                        }
                        val message = "${book.name} 目录为空且加载目录失败：${it.localizedMessage}"
                        fail(bookUrl, message, it)
                        removeDownload(bookUrl)
                        return@execute
                    }.getOrNull()?.let { toc ->
                        appDb.bookChapterDao.insert(*toc.toTypedArray())
                    }
                    book.update()
                }
            }
            val lastChapterIndex = appDb.bookChapterDao.getChapterList(bookUrl).maxOfOrNull { it.index }
            if (lastChapterIndex == null) {
                fail(bookUrl, "${book.name} has no chapters")
                removeDownload(bookUrl)
                return@execute
            }
            val end2 = if (end < 0) lastChapterIndex else min(end, lastChapterIndex)
            if (start < 0 || start > end2) {
                fail(bookUrl, "${book.name} invalid chapter range ${start + 1}-${end + 1}, max index $lastChapterIndex")
                removeDownload(bookUrl)
                return@execute
            }
            AppLog.put("提交正文执行 ${book.name}，实际章节范围 ${start + 1}-${end2 + 1}")
            val lease = synchronized(coordinatorLeases) { coordinatorLeases[bookUrl] }
            cacheBook.addDownload(start, end2, lease)
        }.onError { error ->
            failAll("正文缓存宿初始化失败：${error.localizedMessage}", error)
            stopSelf()
        }.onFinally {
            if (downloadJob == null && CacheBook.hasPendingWork()) {
                startDownload()
            }
        }
    }

    private fun startDownload() {
        downloadJob?.cancel()
        downloadJob = lifecycleScope.launch(cachePool) {
            try {
                CacheBook.runProcessJob(cachePool)
            } catch (error: Throwable) {
                failAll("正文执行器异常：${error.localizedMessage}", error)
            } finally {
                downloadJob = null
                synchronized(coordinatorLeases) { coordinatorLeases.clear() }
                stopSelf()
            }
        }
    }

    private fun fail(bookUrl: String, message: String, error: Throwable? = null) {
        synchronized(coordinatorLeases) { coordinatorLeases[bookUrl] }?.let { lease ->
            CacheBodyWorkerRegistry.onExecutionFailed(lease, message)
        }
        releaseLease(bookUrl)
        AppLog.put("正文缓存失败：$message", error)
    }

    private fun failAll(message: String, error: Throwable? = null) {
        outstandingCoordinatorLeases().forEach { (bookUrl, lease) ->
            CacheBodyWorkerRegistry.onExecutionFailed(lease, message)
            releaseLease(bookUrl)
        }
        AppLog.put("正文缓存失败：$message", error)
    }

    private fun outstandingCoordinatorLeases(): List<Pair<String, CacheWorkerLease>> {
        return synchronized(coordinatorLeases) {
            coordinatorLeases.entries.mapNotNull { (bookUrl, lease) ->
                val task = CacheCoordinator.currentTask(
                    io.legado.app.help.cache.CacheSubmission(lease.sessionId, lease.taskId)
                )
                if (task?.status == CacheLifecycle.RUNNING && task.generation == lease.generation) {
                    bookUrl to lease
                } else {
                    null
                }
            }
        }
    }

    private fun releaseLease(bookUrl: String) {
        synchronized(coordinatorLeases) { coordinatorLeases.remove(bookUrl) }
    }

    private fun removeDownload(bookUrl: String) {
        CacheBook.stop(bookUrl)
        if (downloadJob == null && CacheBook.hasPendingWork()) {
            startDownload()
        } else if (!CacheBook.hasPendingWork()) {
            stopSelf()
        }
    }

    override fun startForegroundNotification() {
        startForeground(
            NotificationId.CacheCoordinator,
            CacheNotificationBridge.foregroundNotification(),
        )
    }
}
