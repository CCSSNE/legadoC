package io.legado.app.service

import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.BaseService
import io.legado.app.constant.AppConst
import io.legado.app.constant.AppLog
import io.legado.app.constant.EventBus
import io.legado.app.constant.IntentAction
import io.legado.app.constant.NotificationId
import io.legado.app.data.appDb
import io.legado.app.help.book.update
import io.legado.app.help.config.AppConfig
import io.legado.app.model.CacheBook
import io.legado.app.model.webBook.WebBook
import io.legado.app.ui.book.cache.CacheActivity
import io.legado.app.utils.activityPendingIntent
import io.legado.app.utils.postEvent
import io.legado.app.utils.servicePendingIntent
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import splitties.init.appCtx
import splitties.systemservices.notificationManager
import java.util.concurrent.Executors
import kotlin.math.min

/**
 * 缓存书籍服务
 */
class CacheBookService : BaseService() {

    companion object {
        var isRun = false
            private set
    }

    private val threadCount = AppConfig.threadCount
    private var cachePool =
        Executors.newFixedThreadPool(min(threadCount, AppConst.MAX_THREAD)).asCoroutineDispatcher()
    private var downloadJob: Job? = null
    private var notificationContent = appCtx.getString(R.string.service_starting)
    private var terminalFailure: String? = null
    private var resultNotified = false
    private var mutex = Mutex()
    private val notificationBuilder by lazy {
        val builder = NotificationCompat.Builder(this, AppConst.channelIdDownload)
            .setSmallIcon(R.drawable.ic_status_bar_r)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentTitle(getString(R.string.offline_cache))
            .setContentIntent(activityPendingIntent<CacheActivity>("cacheActivity"))
        builder.addAction(
            R.drawable.ic_stop_black_24dp,
            getString(R.string.cancel),
            servicePendingIntent<CacheBookService>(IntentAction.stop)
        )
        builder.setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
    }

    override fun onCreate() {
        super.onCreate()
        isRun = true
        lifecycleScope.launch {
            while (isActive) {
                delay(1000)
                notificationContent = upNotificationContent()
                upCacheBookNotification()
                postEvent(EventBus.UP_DOWNLOAD, "")
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.action?.let { action ->
            when (action) {
                IntentAction.start -> addDownloadData(
                    intent.getStringExtra("bookUrl"),
                    intent.getIntExtra("start", 0),
                    intent.getIntExtra("end", 0)
                )

                IntentAction.remove -> removeDownload(intent.getStringExtra("bookUrl"))
                IntentAction.stop -> {
                    AppLog.put("用户停止离线缓存")
                    finishNotification()
                    stopSelf()
                }
            }
        }
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onDestroy() {
        finishNotification()
        isRun = false
        cachePool.close()
        CacheBook.close()
        super.onDestroy()
        postEvent(EventBus.UP_DOWNLOAD, "")
    }

    private fun addDownloadData(bookUrl: String?, start: Int, end: Int) {
        resultNotified = false
        terminalFailure = null
        if (bookUrl.isNullOrBlank()) {
            reportTerminalFailure("missing book url")
            finishNotification()
            stopSelf()
            return
        }
        execute {
            val cacheBook = CacheBook.getOrCreate(bookUrl) ?: run {
                reportTerminalFailure("book not found: $bookUrl")
                finishNotification()
                stopSelf()
                return@execute
            }
            val chapterCount = appDb.bookChapterDao.getChapterCount(bookUrl)
            val book = cacheBook.book
            if (chapterCount == 0) {
                cacheBook.setLoading()
                mutex.withLock {
                    val name = book.name
                    if (book.tocUrl.isEmpty()) {
                        kotlin.runCatching {
                            WebBook.getBookInfoAwait(cacheBook.bookSource, book)
                        }.onFailure {
                            removeDownload(bookUrl)
                            val msg = "《$name》目录为空且加载详情页失败\n${it.localizedMessage}"
                            reportTerminalFailure(msg, it)
                            return@execute
                        }
                    }
                    WebBook.getChapterListAwait(cacheBook.bookSource, book).onFailure {
                        if (book.totalChapterNum > 0) {
                            book.totalChapterNum = 0
                            book.update()
                        }
                        removeDownload(bookUrl)
                        val msg = "《$name》目录为空且加载目录失败\n${it.localizedMessage}"
                        reportTerminalFailure(msg, it)
                        return@execute
                    }.getOrNull()?.let { toc ->
                        appDb.bookChapterDao.insert(*toc.toTypedArray())
                    }
                    book.update()
                }
            }
            val lastChapterIndex = appDb.bookChapterDao.getChapterList(bookUrl).maxOfOrNull { it.index }
            if (lastChapterIndex == null) {
                reportTerminalFailure("${book.name} has no chapters")
                removeDownload(bookUrl)
                return@execute
            }
            val end2 = if (end < 0) lastChapterIndex else min(end, lastChapterIndex)
            if (start < 0 || start > end2) {
                reportTerminalFailure("${book.name} invalid chapter range ${start + 1}-${end + 1}, max index $lastChapterIndex")
                removeDownload(bookUrl)
                return@execute
            }
            AppLog.put("提交离线缓存 ${book.name}，实际章节范围 ${start + 1}-${end2 + 1}")
            cacheBook.addDownload(start, end2)
            notificationContent = CacheBook.downloadSummary
            upCacheBookNotification()
        }.onError { error ->
            reportTerminalFailure("初始化离线缓存失败：${error.localizedMessage}", error)
            finishNotification()
            stopSelf()
        }.onFinally {
            if (downloadJob == null && terminalFailure == null && CacheBook.isRun) {
                download()
            }
        }
    }

    private fun reportTerminalFailure(message: String, throwable: Throwable? = null) {
        terminalFailure = message
        AppLog.put("离线缓存失败：$message", throwable)
        notificationContent = message
        upCacheBookNotification()
    }

    private fun removeDownload(bookUrl: String?) {
        CacheBook.cacheBookMap[bookUrl]?.stop()
        CacheBook.cacheBookMap.remove(bookUrl)
        postEvent(EventBus.UP_DOWNLOAD, "")
        if (downloadJob == null && CacheBook.isRun) {
            download()
            return
        }
        if (CacheBook.cacheBookMap.isEmpty()) {
            stopSelf()
        }
    }

    private fun download() {
        downloadJob?.cancel()
        downloadJob = lifecycleScope.launch(cachePool) {
            try {
                CacheBook.startProcessJob(cachePool)
            } catch (e: Throwable) {
                reportTerminalFailure("下载调度异常：${e.localizedMessage}", e)
            } finally {
                finishNotification()
                stopSelf()
            }
        }
    }

    private fun finishNotification() {
        if (resultNotified) return
        resultNotified = true
        val failure = terminalFailure
        val content = failure ?: getString(
            R.string.cache_download_finished,
            CacheBook.successDownloadCount,
            CacheBook.failedDownloadCount
        )
        val title = if (failure == null && CacheBook.failedDownloadCount == 0) {
            getString(R.string.cache_download_success)
        } else {
            getString(R.string.cache_download_failed)
        }
        AppLog.put("离线缓存通知：$title，$content")
        val notification = NotificationCompat.Builder(this, AppConst.channelIdDownload)
            .setSmallIcon(R.drawable.ic_status_bar_r)
            .setContentTitle(title)
            .setContentText(content)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content))
            .setOnlyAlertOnce(false)
            .setAutoCancel(false)
            .setOngoing(false)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(activityPendingIntent<CacheActivity>("cacheActivity"))
            .build()
        notificationManager.notify(NotificationId.CacheBookResult, notification)
    }

    /** 通知文字：正文 x/y · 评论 a/b（y = 本次缓存目标章数，不是全书总章数）；无活跃书时回退下载摘要 */
    private fun upNotificationContent(): String {
        val book = CacheBook.activeCachingBook() ?: return CacheBook.downloadSummary
        val progress = CacheBook.activeBodyProgress()
        val target = progress?.second ?: book.totalChapterNum
        val bodyDone = progress?.first
            ?: io.legado.app.help.book.BookHelp.getChapterFiles(book).count { it.endsWith(".nb") }
        val cachedReview = (io.legado.app.help.review.ReviewSnapshotManager
            .cachedReviewChapterCount(book)).coerceAtMost(target)
        return getString(
            R.string.download_count_review,
            bodyDone,
            target,
            cachedReview,
            target
        )
    }

    private fun upCacheBookNotification() {
        // 进度条 = 当前正文单章节下载进度：正文整章一次性抓取，无字节级进度，
        // 以不确定进度条表示“当前章正在缓存”（总进度只体现在文字里）
        notificationBuilder.setProgress(0, 0, true)
        notificationBuilder.setContentText(notificationContent)
        val notification = notificationBuilder.build()
        notificationManager.notify(NotificationId.CacheBookService, notification)
    }

    /**
     * 更新通知
     */
    override fun startForegroundNotification() {
        notificationBuilder.setContentText(notificationContent)
        val notification = notificationBuilder.build()
        startForeground(NotificationId.CacheBookService, notification)
    }

}
