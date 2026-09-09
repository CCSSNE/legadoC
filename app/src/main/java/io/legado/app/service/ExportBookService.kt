package io.legado.app.service

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import io.legado.app.R
import io.legado.app.base.BaseService
import io.legado.app.constant.AppConst
import io.legado.app.constant.AppLog
import io.legado.app.constant.AppPattern
import io.legado.app.constant.EventBus
import io.legado.app.constant.IntentAction
import io.legado.app.constant.NotificationId
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookIllustration
import io.legado.app.data.entities.Bookmark
import io.legado.app.data.entities.HighlightRule
import io.legado.app.data.entities.ReplaceRule
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.AppWebDav
import io.legado.app.help.LifecycleHelp
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.AudioBookArchive
import io.legado.app.help.book.AudioBookArchiveChapter
import io.legado.app.help.book.AudioBookArchiveManifest
import io.legado.app.help.book.BookArchive
import io.legado.app.help.book.BookArchiveManifest
import io.legado.app.help.book.BookExportReport
import io.legado.app.help.book.AudioTextFusion
import io.legado.app.help.book.ContentProcessor
import io.legado.app.help.book.getExportFileName
import io.legado.app.help.book.getLiteralExportFileName
import io.legado.app.help.book.isAudio
import io.legado.app.help.book.isLocalModified
import io.legado.app.help.book.isLocal
import io.legado.app.help.book.isLocalTxt
import io.legado.app.help.illustration.IllustrationHelp
import io.legado.app.help.glide.ImageLoader
import io.legado.app.help.review.ReviewSnapshotStore
import io.legado.app.help.illustration.imageSrcsFromJson
import io.legado.app.help.illustration.imageSrcsToJson
import io.legado.app.help.config.AppConfig
import io.legado.app.help.exoplayer.ExoPlayerHelper
import io.legado.app.help.tts.TtsCacheArchive
import io.legado.app.help.tts.TtsCacheStore
import io.legado.app.model.ReadBook
import io.legado.app.model.localBook.EpubFile
import io.legado.app.model.localBook.LocalBook
import io.legado.app.ui.book.cache.CacheActivity
import io.legado.app.utils.FileDoc
import io.legado.app.utils.ExportFileWriter
import io.legado.app.utils.createFolderIfNotExist
import io.legado.app.utils.FileUtils
import io.legado.app.utils.ExportImageSanitizer
import io.legado.app.utils.ConvertUtils
import io.legado.app.utils.HtmlFormatter
import io.legado.app.utils.GSON
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.NetworkUtils
import io.legado.app.utils.activityPendingIntent
import io.legado.app.utils.cnCompare
import io.legado.app.utils.createFileIfNotExist
import io.legado.app.utils.createFileIfNotExistWithMime
import io.legado.app.utils.compress.ZipUtils
import io.legado.app.utils.delete
import io.legado.app.utils.exists
import io.legado.app.utils.find
import io.legado.app.utils.isAbsUrl
import io.legado.app.utils.list
import io.legado.app.utils.mapAsync
import io.legado.app.utils.mapAsyncIndexed
import io.legado.app.utils.normalizeFileName
import io.legado.app.utils.openInputStream
import io.legado.app.utils.openOutputStream
import io.legado.app.utils.postEvent
import io.legado.app.utils.servicePendingIntent
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.writeFile
import io.legado.app.utils.externalCache
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.zip.Deflater
import kotlin.math.ceil
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collectIndexed
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.runInterruptible
import me.ag2s.epublib.domain.Author
import me.ag2s.epublib.domain.Date
import me.ag2s.epublib.domain.EpubBook
import me.ag2s.epublib.domain.FileResourceProvider
import me.ag2s.epublib.domain.LazyResource
import me.ag2s.epublib.domain.LazyResourceProvider
import me.ag2s.epublib.domain.Metadata
import me.ag2s.epublib.domain.Resource
import me.ag2s.epublib.domain.TOCReference
import me.ag2s.epublib.epub.EpubWriter
import me.ag2s.epublib.epub.EpubWriterProcessor
import me.ag2s.epublib.util.ResourceUtil
import splitties.init.appCtx
import splitties.systemservices.notificationManager
import java.nio.charset.Charset
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.min

/**
 * 导出书籍服务
 */
class ExportBookService : BaseService() {

    companion object {
        val exportProgress = ConcurrentHashMap<String, Int>()
        val exportMsg = ConcurrentHashMap<String, String>()
        private const val EPUB_ASSET_BACKGROUND_PREFIX = "asset://bg/"
        @Volatile
        private var exportFinishedNotificationVisible = false

        fun clearFinishedNotification() {
            if (exportFinishedNotificationVisible && exportProgress.isEmpty()) {
                notificationManager.cancel(NotificationId.ExportBook)
                exportFinishedNotificationVisible = false
            }
        }
    }

    data class ExportConfig(
        val path: String,
        val type: String,
        val charset: String = AppConfig.exportCharset,
        val useReplace: Boolean = AppConfig.exportUseReplace,
        val toWebDav: Boolean = AppConfig.exportToWebDav,
        val noChapterName: Boolean = AppConfig.exportNoChapterName,
        val pictureFile: Boolean = AppConfig.exportPictureFile,
        val exportBookmarks: Boolean = AppConfig.exportBookmarks,
        val exportReviews: Boolean = AppConfig.exportReviews,
        val exportTtsCache: Boolean = AppConfig.exportTtsCache,
        val parallelExport: Boolean = AppConfig.parallelExportBook,
        val bookExportFileName: String? = AppConfig.bookExportFileName,
        val episodeExportFileName: String? = AppConfig.episodeExportFileName,
        val epubSize: Int = 1,
        val epubScope: String? = null,
        val epubTitleColor: String = AppConfig.epubExportTitleColor ?: "#3F83E8",
        val epubTextColor: String = AppConfig.epubExportTextColor ?: "#3E3D3B",
        val epubFontPath: String? = AppConfig.epubExportFontPath,
        val epubEmbedFont: Boolean = AppConfig.epubExportEmbedFont,
        val epubTextSize: Int = AppConfig.epubExportTextSize,
        val epubLineHeight: Int = AppConfig.epubExportLineHeight,
        val epubParagraphSpacing: Int = AppConfig.epubExportParagraphSpacing,
        val epubParagraphIndent: String = AppConfig.epubExportParagraphIndent,
        val epubBackgroundColor: String = AppConfig.epubExportBackgroundColor ?: "#FFFFFF",
        val epubBackgroundImagePath: String? = AppConfig.epubExportBackgroundImagePath,
        val epubUseBackgroundImage: Boolean = AppConfig.epubExportUseBackgroundImage,
        val epubUseExternalTemplate: Boolean = false
    )

    private val groupKey = "${appCtx.packageName}.exportBook"
    private val waitExportBooks = linkedMapOf<String, ExportConfig>()
    private var exportJob: Job? = null
    private var succeededExports = 0
    private var failedExports = 0
    private var notificationContentText = appCtx.getString(R.string.service_starting)
    @Volatile
    private var lastExportFileName = ""


    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            IntentAction.start -> kotlin.runCatching {
                val bookUrl = intent.getStringExtra("bookUrl")!!
                if (!exportProgress.contains(bookUrl)) {
                    require(intent.getStringExtra("exportType") in setOf("txt", "txt_zip", "epub", "pdf")) { "不支持的导出格式" }
                    val exportConfig = ExportConfig(
                        path = intent.getStringExtra("exportPath")!!,
                        type = intent.getStringExtra("exportType")!!,
                        charset = intent.getStringExtra("exportCharset") ?: AppConfig.exportCharset,
                        useReplace = intent.getBooleanExtra(
                            "exportUseReplace",
                            AppConfig.exportUseReplace
                        ),
                        toWebDav = intent.getBooleanExtra(
                            "exportToWebDav",
                            AppConfig.exportToWebDav
                        ),
                        noChapterName = intent.getBooleanExtra(
                            "exportNoChapterName",
                            AppConfig.exportNoChapterName
                        ),
                        pictureFile = intent.getBooleanExtra(
                            "exportPictureFile",
                            AppConfig.exportPictureFile
                        ),
                        exportBookmarks = intent.getBooleanExtra(
                            "exportBookmarks",
                            AppConfig.exportBookmarks
                        ),
                        exportReviews = intent.getBooleanExtra(
                            "exportReviews",
                            AppConfig.exportReviews
                        ),
                        exportTtsCache = intent.getBooleanExtra(
                            "exportTtsCache",
                            AppConfig.exportTtsCache
                        ),
                        parallelExport = intent.getBooleanExtra(
                            "parallelExportBook",
                            AppConfig.parallelExportBook
                        ),
                        bookExportFileName = intent.getStringExtra("bookExportFileName")
                            ?: AppConfig.bookExportFileName,
                        episodeExportFileName = intent.getStringExtra("episodeExportFileName")
                            ?: AppConfig.episodeExportFileName,
                        epubSize = 1,
                        epubScope = null,
                        epubTitleColor = intent.getStringExtra("epubTitleColor")
                            ?: AppConfig.epubExportTitleColor
                            ?: "#3F83E8",
                        epubTextColor = intent.getStringExtra("epubTextColor")
                            ?: AppConfig.epubExportTextColor
                            ?: "#3E3D3B",
                        epubFontPath = intent.getStringExtra("epubFontPath")
                            ?: AppConfig.epubExportFontPath,
                        epubEmbedFont = intent.getBooleanExtra(
                            "epubEmbedFont",
                            AppConfig.epubExportEmbedFont
                        ),
                        epubTextSize = intent.getIntExtra(
                            "epubTextSize",
                            AppConfig.epubExportTextSize
                        ),
                        epubLineHeight = intent.getIntExtra(
                            "epubLineHeight",
                            AppConfig.epubExportLineHeight
                        ),
                        epubParagraphSpacing = intent.getIntExtra(
                            "epubParagraphSpacing",
                            AppConfig.epubExportParagraphSpacing
                        ),
                        epubParagraphIndent = intent.getStringExtra("epubParagraphIndent")
                            ?: AppConfig.epubExportParagraphIndent,
                        epubBackgroundColor = intent.getStringExtra("epubBackgroundColor")
                            ?: AppConfig.epubExportBackgroundColor
                            ?: "#FFFFFF",
                        epubBackgroundImagePath = intent.getStringExtra("epubBackgroundImagePath")
                            ?: AppConfig.epubExportBackgroundImagePath,
                        epubUseBackgroundImage = intent.getBooleanExtra(
                            "epubUseBackgroundImage",
                            AppConfig.epubExportUseBackgroundImage
                        ),
                        epubUseExternalTemplate = intent.getBooleanExtra(
                            "epubUseExternalTemplate",
                            false
                        )
                    )
                    exportProgress[bookUrl] = 0
                    waitExportBooks[bookUrl] = exportConfig
                    exportMsg[bookUrl] = getString(R.string.export_wait)
                    postEvent(EventBus.EXPORT_BOOK, bookUrl)
                    export()
                }
            }.onFailure {
                toastOnUi(it.localizedMessage)
            }

            IntentAction.stop -> {
                notificationManager.cancel(NotificationId.ExportBook)
                exportFinishedNotificationVisible = false
                stopSelf()
            }
        }
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onDestroy() {
        super.onDestroy()
        val unfinished = exportProgress.keys.toList()
        exportProgress.clear()
        unfinished.forEach {
            exportMsg[it] = "导出已取消"
            postEvent(EventBus.EXPORT_BOOK, it)
        }
        waitExportBooks.clear()
    }

    @SuppressLint("MissingPermission")
    override fun startForegroundNotification() {
        val notification = NotificationCompat.Builder(this, AppConst.channelIdDownload)
            .setSmallIcon(R.drawable.ic_status_bar_r)
            .setSubText(getString(R.string.export_book))
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setGroup(groupKey)
            .setGroupSummary(true)
        startForeground(NotificationId.ExportBookService, notification.build())
    }

    private fun upExportNotification(finish: Boolean = false) {
        exportFinishedNotificationVisible = finish
        val notification = NotificationCompat.Builder(this, AppConst.channelIdDownload)
            .setSmallIcon(R.drawable.ic_status_bar_r)
            .setSubText(getString(R.string.export_book))
            .setContentIntent(activityPendingIntent<CacheActivity>("cacheActivity"))
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentText(notificationContentText)
            .setDeleteIntent(servicePendingIntent<ExportBookService>(IntentAction.stop))
            .setGroup(groupKey)
            .setOnlyAlertOnce(true)
        if (!finish) {
            notification.setOngoing(true)
            notification.addAction(
                R.drawable.ic_stop_black_24dp,
                getString(R.string.cancel),
                servicePendingIntent<ExportBookService>(IntentAction.stop)
            )
        } else {
            notification.setAutoCancel(true)
        }
        notificationManager.notify(NotificationId.ExportBook, notification.build())
    }

    private fun export() {
        if (exportJob?.isActive == true) {
            return
        }
        exportJob = lifecycleScope.launch {
            while (isActive) {
                val (bookUrl, exportConfig) = waitExportBooks.entries.firstOrNull() ?: let {
                    finishExportNotification()
                    stopSelf()
                    return@launch
                }
                exportProgress[bookUrl] = 0
                waitExportBooks.remove(bookUrl)
                val pendingExportCount = waitExportBooks.size
                var book: Book? = null
                try {
                    withContext(IO) {
                        val loadedBook = appDb.bookDao.getBook(bookUrl)
                        book = loadedBook
                        val book = loadedBook
                        book ?: throw NoStackTraceException("获取${bookUrl}书籍出错")
                        require(!book.isAudio || exportConfig.type in setOf("txt", "txt_zip")) {
                            "Audio books can only be exported as TXT or TXT-ZIP"
                        }
                        val chapterRefreshError = if (exportConfig.type in setOf("txt", "txt_zip")) {
                            try {
                                refreshChapterList(book)
                                null
                            } catch (error: Throwable) {
                                currentCoroutineContext().ensureActive()
                                error
                            }
                        } else {
                            refreshChapterList(book)
                            null
                        }
                        if (exportConfig.type == "epub" &&
                            appDb.bookChapterDao.getChapterCount(book.bookUrl) == 0
                        ) {
                            throw NoStackTraceException("EPUB 导出失败：书籍没有解析出章节目录")
                        }
                        notificationContentText = getString(
                            R.string.export_book_notification_content,
                            book.name,
                            pendingExportCount
                        )
                        upExportNotification()
                        exportMsg[book.bookUrl] = when (exportConfig.type) {
                            "pdf" -> {
                                exportPdf(exportConfig.path, book, exportConfig)
                                getString(R.string.export_success)
                            }
                            "epub" -> {
                                exportEpub(exportConfig.path, book, exportConfig)
                                getString(R.string.export_success)
                            }
                            "txt_zip" -> exportTxtZip(
                                exportConfig.path,
                                book,
                                exportConfig,
                                chapterRefreshError,
                            )
                            else -> exportTxt(
                                exportConfig.path,
                                book,
                                exportConfig,
                                chapterRefreshError,
                            )
                        }
                    }
                    succeededExports++
                } catch (e: Throwable) {
                    ensureActive()
                    failedExports++
                    exportMsg[bookUrl] = e.localizedMessage ?: "ERROR"
                    AppLog.put("导出书籍<${book?.name ?: bookUrl}>出错", e)
                } finally {
                    exportProgress.remove(bookUrl)
                    postEvent(EventBus.EXPORT_BOOK, bookUrl)
                }
            }
        }
    }

    private fun finishExportNotification() {
        notificationContentText = "导出结束：成功 $succeededExports，失败 $failedExports" +
            lastExportFileName.takeIf { it.isNotBlank() }?.let { "；已保存：$it" }.orEmpty()
        if (LifecycleHelp.isAppVisible()) {
            exportFinishedNotificationVisible = false
            notificationManager.cancel(NotificationId.ExportBook)
        } else {
            upExportNotification(true)
        }
    }

    private fun refreshChapterList(book: Book) {
        if (!book.isLocal) {
            return
        }
        val existingChapterCount = appDb.bookChapterDao.getChapterCount(book.bookUrl)
        if (!book.isLocalModified() && existingChapterCount > 0) {
            return
        }
        val chapters = LocalBook.getChapterList(book)
        if (chapters.isEmpty()) {
            throw NoStackTraceException("书籍<${book.name}>没有解析出章节目录")
        }
        appDb.runInTransaction {
            appDb.bookChapterDao.delByBook(book.bookUrl)
            appDb.bookChapterDao.insert(*chapters.toTypedArray())
            appDb.bookDao.update(book)
        }
        ReadBook.onChapterListUpdated(book)
    }

    private data class SrcData(
        val chapterTitle: String,
        val index: Int,
        val src: String
    )

    private suspend fun exportTxt(
        path: String,
        book: Book,
        config: ExportConfig,
        chapterRefreshError: Throwable?,
    ): String {
        exportMsg.remove(book.bookUrl)
        postEvent(EventBus.EXPORT_BOOK, book.bookUrl)
        val fileDoc = FileDoc.fromDir(path)
        return exportTxt(fileDoc, book, config, chapterRefreshError)
    }

    private suspend fun exportTxt(
        fileDoc: FileDoc,
        book: Book,
        config: ExportConfig,
        chapterRefreshError: Throwable?,
    ): String {
        val filename = book.getLiteralExportFileName("txt", config.bookExportFileName)
        ExportFileWriter.validateName(filename)
        val report = BookExportReport(book, filename)
        chapterRefreshError?.let { report.issue("章节目录刷新", book.name, it) }
        val exportCharset = try {
            Charset.forName(config.charset)
        } catch (error: Throwable) {
            report.issue("字符编码", config.charset, error)
            Charsets.UTF_8
        }
        val source = File.createTempFile("export_txt_", ".txt", cacheDir)
        try {
            report.expect("正文文件")
            var textChunk = 0
            report.capture("正文文件", filename) {
                source.bufferedWriter(exportCharset).use { writer ->
                    getAllContents(book, config, report = report) { text, srcList ->
                        textChunk++
                        if (!exportCharset.newEncoder().canEncode(text)) {
                            report.issue(
                                "字符编码",
                                "$filename 第 $textChunk 个文本片段",
                                "${exportCharset.name()} 无法无损表达其中字符，文件中已写入替代字符",
                            )
                        }
                        writer.write(text)
                        srcList?.forEach { image ->
                            report.expect("正文图片")
                            report.capture("正文图片", "${image.chapterTitle} ${image.src}") {
                                val imageFile = BookHelp.getImage(book, image.src)
                                require(imageFile.isFile && imageFile.length() > 0L) {
                                    "缓存文件缺失或为空"
                                }
                                val imageParent = fileDoc.createFolderIfNotExist(
                                    "${book.name}_${book.author}".toExportImageDirName("book"), "images",
                                    image.chapterTitle.toExportImageDirName("chapter_${image.index}"),
                                )
                                ExportFileWriter.save(
                                    imageParent,
                                    "${image.index}-${MD5Utils.md5Encode16(image.src)}.jpg",
                                    imageFile,
                                    "image/jpeg",
                                )
                            }
                        }
                    }
                }
            }
            if (!source.isFile || source.length() == 0L) {
                report.issue("正文文件", filename, "正文文件未生成或为空，已写入说明文本")
                source.writeText("${book.name}\n作者：${book.author}\n\n正文未能写入，详情见下方导出报告。", exportCharset)
            }
            FileOutputStream(source, true).bufferedWriter(exportCharset).use { writer ->
                report.writeAppendix(writer)
            }
            val bookDoc = ExportFileWriter.save(fileDoc, filename, source, "text/plain")
            lastExportFileName = filename
            val webDavIssue = if (config.toWebDav) uploadExportToWebDav(bookDoc.uri, filename) else null
            return listOfNotNull(report.summary(), webDavIssue).joinToString("；")
        } finally {
            source.delete()
        }
    }

    /**
     * 带配图的 TXT 导出（独立选项，type = "txt_zip"）：
     * 压缩包 = book.txt + images/ + illustrations.json + bookmarks.json。
     * 正文 txt 对本地 TXT 书直接复制原始文件，保持与源文件字节一致，
     * 配图/媒体/书签走 sidecar，重新导入时不会章节错乱。
     */
    private suspend fun exportTxtZip(
        path: String,
        book: Book,
        config: ExportConfig,
        chapterRefreshError: Throwable?,
    ): String {
        exportMsg.remove(book.bookUrl)
        postEvent(EventBus.EXPORT_BOOK, book.bookUrl)
        val fileDoc = FileDoc.fromDir(path)
        return exportTxtZip(fileDoc, book, config, chapterRefreshError)
    }

    private suspend fun exportTxtZip(
        fileDoc: FileDoc,
        book: Book,
        config: ExportConfig,
        chapterRefreshError: Throwable?,
    ): String {
        val literalTxtName = book.getLiteralExportFileName("txt", config.bookExportFileName)
        val txtName = if (book.isAudio) {
            AudioBookArchive.audioFileName(literalTxtName)
        } else {
            literalTxtName
        }
        ExportFileWriter.validateName(txtName)
        val zipName = txtName.removeSuffix(".txt") + ".zip"
        val report = BookExportReport(book, zipName)
        chapterRefreshError?.let { report.issue("章节目录刷新", book.name, it) }
        val tmpRoot = FileUtils.createFolderIfNotExist(
            appCtx.externalCache,
            "ExportTxtZip_${UUID.randomUUID()}"
        )
        try {
            val tmpTxt = File(tmpRoot, txtName)
            val tmpImagesDir = File(tmpRoot, IllustrationHelp.EXPORT_IMAGES_DIR)
            FileUtils.createFolderIfNotExist(tmpImagesDir.absolutePath)
            val tmpJson = File(tmpRoot, IllustrationHelp.EXPORT_JSON_NAME)
            val audioChapters = if (book.isAudio) {
                updateAudioExportStatus(book, "正在收集现有音频与字幕")
                audioArchiveChapters(book, report)
            } else {
                null
            }
            val audioSelection = audioChapters
                ?.takeIf { it.isNotEmpty() }
                ?.let(::AudioExportSelection)
            report.expect("配图记录")
            val illustrations = report.capture("配图记录", book.name) {
                appDb.bookIllustrationDao.getByBook(book.bookUrl).let { records ->
                    audioSelection?.remapIllustrations(records, report) ?: records
                }
            }.orEmpty()
            val tmpBookmarks = if (config.exportBookmarks) {
                report.expect("书签")
                report.capture("书签", IllustrationHelp.EXPORT_BOOKMARKS_NAME) {
                    File(tmpRoot, IllustrationHelp.EXPORT_BOOKMARKS_NAME).also { file ->
                        val bookmarks = appDb.bookmarkDao.getByBook(book.name, book.author).let { records ->
                            audioSelection?.remapBookmarks(records, report) ?: records
                        }
                        if (bookmarks.isNotEmpty()) {
                            file.writeText(GSON.toJson(bookmarks), Charsets.UTF_8)
                        }
                    }
                }
            } else {
                null
            }
            // 替换净化开启时，把该书当前生效的替换规则作为外挂数据写入 zip，
            // 正文 txt 保持原文，规则在导入时同步还原（原文+规则，规则不作用于导出文本）
            val tmpReplaceRules = if (config.useReplace && book.getUseReplaceRule()) {
                report.expect("替换规则")
                report.capture("替换规则", IllustrationHelp.EXPORT_REPLACE_RULES_NAME) {
                    File(tmpRoot, IllustrationHelp.EXPORT_REPLACE_RULES_NAME).also { file ->
                        val rules = arrayListOf<ReplaceRule>()
                        rules.addAll(appDb.replaceRuleDao.findEnabledByContentScope(book.name, book.origin))
                        rules.addAll(appDb.replaceRuleDao.findEnabledByTitleScope(book.name, book.origin))
                        if (rules.isNotEmpty()) {
                            file.writeText(GSON.toJson(rules), Charsets.UTF_8)
                        }
                    }
                }
            } else {
                null
            }
            // 高亮规则同样作为外挂数据写入 zip：按该书 scope 命中的启用规则收集，
            // 导入时还原规则数据，显示效果在排版时重新计算，不影响导出正文
            report.expect("高亮规则")
            val tmpHighlightRules = report.capture("高亮规则", IllustrationHelp.EXPORT_HIGHLIGHT_RULES_NAME) {
                File(tmpRoot, IllustrationHelp.EXPORT_HIGHLIGHT_RULES_NAME).also { file ->
                    val rules: List<HighlightRule> =
                        appDb.highlightRuleDao.findEnabledByScope(book.name, book.origin)
                    if (rules.isNotEmpty()) {
                        file.writeText(GSON.toJson(rules), Charsets.UTF_8)
                    }
                }
            }
            // 评论页快照：按存储原样导出 reviews/*.json，导入时原样还原
            val tmpReviewsDir = if (config.exportReviews) {
                report.expect("评论缓存")
                report.capture("评论缓存", ReviewSnapshotStore.REVIEWS_DIR_NAME) {
                    val dir = File(tmpRoot, ReviewSnapshotStore.REVIEWS_DIR_NAME)
                    val warning = ReviewSnapshotStore.copyAllTo(book, dir) { item, error ->
                        report.issue("评论缓存", item, error)
                    }
                    warning?.let { report.issue("评论缓存", book.name, it) }
                    dir.takeIf { it.walkTopDown().any(File::isFile) }
                }
            } else {
                null
            }
            // TTS 音频缓存归档：按清单收集已缓存的朗读单元音频，
            // 导入端以清单重算缓存 key 落位，回导后可直接命中
            val tmpTtsCacheDir = if (config.exportTtsCache) {
                report.expect("TTS 缓存")
                report.capture("TTS 缓存", TtsCacheStore.DIR_NAME) {
                    exportTtsCacheArchive(book, tmpRoot, report)
                }
            } else {
                null
            }
            val bookArchiveEntries = exportBookArchiveMetadata(book, tmpRoot, txtName, report)
            val tmpAudioManifest = if (book.isAudio) {
                exportAudioBookMedia(book, tmpRoot, txtName, audioChapters.orEmpty(), report)
            } else {
                null
            }
            if (book.isLocalTxt) {
                // 本地 TXT 书直接复制原始文件，保持与用户源文件字节一致：
                // 不经过 getAllContents（那会加书名头、全角缩进并重排版），
                // 否则重新导入时章节解析会错乱、书签位置对不上。
                report.expect("正文")
                report.capture("正文", txtName) {
                    LocalBook.getBookInputStream(book).use { input ->
                        tmpTxt.outputStream().use { out ->
                            input.copyTo(out)
                        }
                    }.also { copiedBytes ->
                        require(copiedBytes > 0L) { "正文源文件为空" }
                    }
                }
                if (!tmpTxt.isFile || tmpTxt.length() == 0L) {
                    tmpTxt.writeText("${book.name}\n作者：${book.author}\n\n正文源文件读取失败，详情见${BookExportReport.FILE_NAME}。", Charsets.UTF_8)
                }
            } else {
                report.expect("正文文件")
                report.capture("正文文件", txtName) {
                    tmpTxt.bufferedWriter(Charsets.UTF_8).use { bw ->
                        if (book.isAudio) {
                            updateAudioExportStatus(book, "正在生成字幕文本")
                        }
                        getAllContents(
                            book,
                            config.copy(useReplace = false, noChapterName = false, pictureFile = false),
                            reportProgress = !book.isAudio,
                            chapters = audioChapters,
                            report = report,
                        ) { text, _ ->
                            bw.write(text)
                        }
                    }
                }
                if (!tmpTxt.isFile || tmpTxt.length() == 0L) {
                    report.issue("正文文件", txtName, "正文文件未生成或为空，已写入说明文本")
                    tmpTxt.writeText("${book.name}\n作者：${book.author}\n\n正文未能写入，详情见${BookExportReport.FILE_NAME}。", Charsets.UTF_8)
                }
            }
            val exportedIllustrations = illustrations.map { illustration ->
                val sources = runCatching {
                    GSON.fromJson(illustration.imageSrcs, Array<String>::class.java).toList()
                }.getOrElse { error ->
                    report.issue(
                        "配图记录",
                        "第${illustration.chapterIndex + 1}章 ${illustration.chapterName} " +
                            "原始引用=${illustration.imageSrcs}",
                        error,
                    )
                    emptyList()
                }
                report.expect("配图", sources.size)
                val exportedSources = sources.mapNotNull { src ->
                    report.capture(
                        "配图",
                        "第${illustration.chapterIndex + 1}章 ${illustration.chapterName} $src",
                    ) {
                        require(src.startsWith(IllustrationHelp.SRC_PREFIX)) { "配图引用格式无效" }
                        val imageName = src.substringAfter(IllustrationHelp.SRC_PREFIX)
                        ExportFileWriter.validateName(imageName)
                        val srcFile = IllustrationHelp.getImageFile(book, src)
                        require(srcFile.isFile && srcFile.length() > 0L) { "缓存文件缺失或为空" }
                        val targetFile = File(tmpImagesDir, imageName)
                        srcFile.copyTo(targetFile, overwrite = true)
                        require(targetFile.length() == srcFile.length()) { "复制后长度不一致" }
                        src
                    }
                }
                illustration.copy(imageSrcs = imageSrcsToJson(exportedSources))
            }
            if (exportedIllustrations.isNotEmpty()) {
                report.expect("配图清单")
                report.capture("配图清单", IllustrationHelp.EXPORT_JSON_NAME) {
                    val jsonText = requireNotNull(
                        IllustrationHelp.buildExportJson(txtName, exportedIllustrations)
                    ) { "配图记录非空但未生成配图清单" }
                    tmpJson.writeText(jsonText, Charsets.UTF_8)
                    tmpJson
                }
            }
            val reportFile = report.writeTo(tmpRoot)
            val tmpZip = File(tmpRoot, zipName)
            val zipEntries = arrayListOf(tmpTxt, reportFile)
            tmpImagesDir.takeIf(File::isDirectory)?.let(zipEntries::add)
            zipEntries.addAll(bookArchiveEntries)
            tmpJson.takeIf { it.exists() }?.let { zipEntries.add(it) }
            tmpBookmarks?.takeIf { it.exists() }?.let { zipEntries.add(it) }
            tmpReplaceRules?.takeIf { it.exists() }?.let { zipEntries.add(it) }
            tmpHighlightRules?.takeIf { it.exists() }?.let { zipEntries.add(it) }
            tmpAudioManifest?.let(zipEntries::add)
            File(tmpRoot, AudioBookArchive.MEDIA_DIR_NAME)
                .takeIf(File::isDirectory)
                ?.let(zipEntries::add)
            tmpReviewsDir?.takeIf { dir ->
                dir.exists() && (dir.listFiles()?.isNotEmpty() == true)
            }?.let { zipEntries.add(it) }
            tmpTtsCacheDir?.takeIf { it.isDirectory }?.let { zipEntries.add(it) }
            zipTxtArchive(book, zipEntries, tmpZip)
            if (config.toWebDav) {
                try {
                    AppWebDav.exportWebDav(Uri.fromFile(tmpZip), zipName, localAlreadySaved = false)
                } catch (error: Throwable) {
                    currentCoroutineContext().ensureActive()
                    report.issue("WebDAV 投递", zipName, error)
                    // 投递失败也属于本次导出事实。更新报告后重新打包，再保存本地最终版。
                    report.writeTo(tmpRoot)
                    zipTxtArchive(book, zipEntries, tmpZip)
                }
            }
            updateAudioExportStatus(book, "正在保存并校验压缩包")
            ExportFileWriter.save(fileDoc, zipName, tmpZip, "application/zip") { done, total, verifying ->
                val stage = if (verifying) "正在校验压缩包" else "正在保存压缩包"
                updateAudioExportStatus(book,
                    "$stage ${ConvertUtils.formatFileSize(done)}/${ConvertUtils.formatFileSize(total)}")
            }
            lastExportFileName = zipName
            return report.summary()
        } finally {
            FileUtils.delete(tmpRoot)
        }
    }

    /**
     * 收集 TTS 音频缓存归档：逐章排版推导朗读单元，按 key 维度候选枚举命中的
     * 缓存文件，产出 tts_cache/ 目录（单元文件 + manifest 清单）。每个单元独立复制；
     * 无法映射到清单的原始文件进入 _unmapped，原因写进导出报告。
     */
    private suspend fun exportTtsCacheArchive(
        book: Book,
        tmpRoot: File,
        report: BookExportReport,
    ): File? {
        exportMsg[book.bookUrl] = "正在收集 TTS 音频缓存"
        postEvent(EventBus.EXPORT_BOOK, book.bookUrl)
        val sourceRoot = TtsCacheStore.ttsCacheDir(book)
        val manifest = try {
            TtsCacheArchive.collectManifest(
                book = book,
                onProgress = { done, total ->
                    exportMsg[book.bookUrl] = "正在收集 TTS 音频缓存 $done/$total"
                    postEvent(EventBus.EXPORT_BOOK, book.bookUrl)
                },
                onIssue = { chapter, error ->
                    report.issue("TTS 缓存", "第${chapter.index + 1}章 ${chapter.title}", error)
                },
            )
        } catch (error: Throwable) {
            currentCoroutineContext().ensureActive()
            report.issue("TTS 清单", book.name, error)
            null
        }
        val exportDir = File(tmpRoot, TtsCacheStore.DIR_NAME)
        val selectedSourcePaths = manifest?.chapters.orEmpty().flatMapTo(linkedSetOf()) { chapter ->
            chapter.units.map { unit -> "${chapter.stem}/${unit.file}" }
        }
        report.expect("TTS 单元", selectedSourcePaths.size)
        val copiedSourcePaths = linkedSetOf<String>()
        val keptChapters = manifest?.chapters.orEmpty().mapNotNull { chapter ->
            val chapterDir = File(exportDir, chapter.stem)
            FileUtils.createFolderIfNotExist(chapterDir.absolutePath)
            val keptUnits = chapter.units.mapNotNull { unit ->
                currentCoroutineContext().ensureActive()
                report.capture("TTS 单元", "${chapter.title}/${unit.file}") {
                    val source = File(
                        TtsCacheStore.ttsCacheDir(book),
                        "${chapter.stem}/${unit.file}",
                    )
                    require(source.isFile && source.length() > 0L) { "缓存文件缺失或为空" }
                    val target = File(chapterDir, unit.file)
                    source.copyTo(target, overwrite = true)
                    require(target.isFile && target.length() == source.length()) { "复制后长度不一致" }
                    copiedSourcePaths += "${chapter.stem}/${unit.file}"
                    unit
                }
            }
            if (keptUnits.isEmpty()) {
                FileUtils.delete(chapterDir)
                null
            } else {
                chapter.copy(units = keptUnits)
            }
        }
        if (keptChapters.isNotEmpty()) {
            report.expect("TTS 清单")
            report.capture("TTS 清单", TtsCacheArchive.MANIFEST_FILE_NAME) {
                File(exportDir, TtsCacheArchive.MANIFEST_FILE_NAME).also { file ->
                    file.writeText(
                        GSON.toJson(requireNotNull(manifest).copy(chapters = keptChapters)),
                        Charsets.UTF_8,
                    )
                }
            }
        } else {
            report.issue("TTS 清单", book.name, "没有建立可自动还原的 TTS 缓存清单")
        }

        // 无法由当前章节/引擎参数解释的缓存仍是用户已有数据。原样放进清单目录下的
        // _unmapped 子目录；导入器会忽略它，但导出包不会静默丢弃。
        val sourceFiles = try {
            sourceRoot.walkTopDown().onFail { file, error ->
                report.issue("TTS 未映射缓存", file.relativeToOrSelf(sourceRoot).path, error)
            }.filter(File::isFile).toList()
        } catch (error: Throwable) {
            report.issue("TTS 未映射缓存", sourceRoot.path, error)
            emptyList()
        }
        val unmappedFiles = sourceFiles.filter { source ->
            source.relativeTo(sourceRoot).invariantSeparatorsPath !in copiedSourcePaths
        }
        report.expect("TTS 未映射缓存", unmappedFiles.size)
        unmappedFiles.forEach { source ->
            currentCoroutineContext().ensureActive()
            val relative = source.relativeTo(sourceRoot).invariantSeparatorsPath
            val copied = report.capture("TTS 未映射缓存", relative) {
                val target = File(exportDir, "_unmapped/$relative")
                target.parentFile?.mkdirs()
                val expected = source.length()
                source.copyTo(target, overwrite = true)
                require(expected == source.length() && target.length() == expected) {
                    "缓存文件在复制期间变化或复制不完整"
                }
                target
            }
            if (copied != null) {
                report.issue(
                    "TTS 未映射缓存",
                    relative,
                    "无法映射到自动还原清单，已原样保存在 ${TtsCacheStore.DIR_NAME}/_unmapped/",
                )
            }
        }

        if (!exportDir.walkTopDown().any(File::isFile)) {
            FileUtils.delete(exportDir)
            return null
        }
        val unitCount = keptChapters.sumOf { it.units.size }
        AppLog.put(
            "TTS 音频缓存导出：${book.name} ${keptChapters.size} 章 $unitCount 个可还原单元，" +
                "${unmappedFiles.size} 个未映射文件",
        )
        return exportDir
    }

    private suspend fun exportBookArchiveMetadata(
        book: Book,
        tmpRoot: File,
        txtName: String,
        report: BookExportReport,
    ): List<File> {
        val coverFile = book.getDisplayCover()?.takeIf { it.isNotBlank() }?.let { coverPath ->
            report.expect("封面")
            report.capture("封面", coverPath) {
                val request = ImageLoader.loadFile(this, coverPath, sourceOrigin = book.origin)
                    .onlyRetrieveFromCache(coverPath.isAbsUrl())
                    .submit()
                try {
                    val source = runInterruptible(IO) { request.get() }
                    require(source.isFile && source.length() > 0L) { "封面源文件缺失或为空" }
                    File(tmpRoot, BookArchive.COVER_FILE_NAME).also { target ->
                        source.copyTo(target, overwrite = true)
                        require(target.isFile && target.length() == source.length()) {
                            "封面复制后长度不一致"
                        }
                    }
                } finally {
                    Glide.with(this).clear(request)
                }
            }
        }
        report.expect("书籍清单")
        val manifestFile = report.capture("书籍清单", BookArchive.MANIFEST_FILE_NAME) {
            File(tmpRoot, BookArchive.MANIFEST_FILE_NAME).also { file ->
                file.writeText(
                    GSON.toJson(
                        BookArchiveManifest(
                            textFile = txtName,
                            coverFile = coverFile?.name,
                        )
                    ),
                    Charsets.UTF_8,
                )
            }
        }
        return listOfNotNull(manifestFile, coverFile)
    }

    private suspend fun exportAudioBookMedia(
        book: Book,
        tmpRoot: File,
        txtName: String,
        chapters: List<BookChapter>,
        report: BookExportReport,
    ): File? {
        if (chapters.isEmpty()) {
            report.issue("音频章节", book.name, "章节目录为空，未生成音频书清单")
            return null
        }
        val audioDir = File(tmpRoot, AudioBookArchive.MEDIA_DIR_NAME)
        if (!audioDir.mkdirs() && !audioDir.isDirectory) {
            report.issue("音频", audioDir.path, "无法创建音频暂存目录")
        }
        val manifestChapters = chapters.mapIndexed { index, chapter ->
            currentCoroutineContext().ensureActive()
            report.expect("音频章节")
            val prefix = "chapter_${index.toString().padStart(6, '0')}"
            val item = "第${chapter.index + 1}章 ${chapter.title}"
            if (chapter.title.isBlank()) {
                report.issue("音频章节", "源索引 ${chapter.index}", "章节标题为空，已按原值写入清单")
            }
            if (chapter.url.isBlank()) {
                report.issue("音频章节", item, "章节稳定标识为空，已按原值写入清单")
            }
            val mediaFiles = try {
                val resourceUrl = requireNotNull(chapter.resourceUrl?.takeIf { it.isNotBlank() }) {
                    "没有媒体地址"
                }
                ExoPlayerHelper.exportAudioFiles(
                    resourceUrl = resourceUrl,
                    book = book,
                    targetDir = audioDir,
                    filePrefix = prefix,
                )
            } catch (error: Throwable) {
                currentCoroutineContext().ensureActive()
                report.issue("音频章节", item, error)
                val rawCacheDir = File(audioDir, "_unmapped/$prefix")
                val rawSpanCount = try {
                    ExoPlayerHelper.copyMediaCache(
                        chapter.resourceUrl,
                        rawCacheDir,
                        book,
                    ) { span, spanError ->
                        report.issue("音频未映射缓存", "$item $span", spanError)
                    }
                } catch (rawError: Throwable) {
                    currentCoroutineContext().ensureActive()
                    report.issue("音频未映射缓存", item, rawError)
                    0
                }
                if (rawSpanCount > 0) {
                    report.expect("音频未映射缓存", rawSpanCount)
                    report.exported("音频未映射缓存", rawSpanCount)
                    report.issue(
                        "音频未映射缓存",
                        item,
                        "已原样保存 $rawSpanCount 个缓存片段至 audio/_unmapped/，这些片段不能自动还原",
                    )
                } else {
                    FileUtils.delete(rawCacheDir, deleteRootDir = true)
                }
                // 多段媒体中此前已经完整复制的部分仍写入 v3 清单。
                audioDir.listFiles()
                    ?.filter { it.isFile && it.length() > 0L && it.name.startsWith("${prefix}_part_") }
                    ?.sortedBy(File::getName)
                    .orEmpty()
            }
            if (mediaFiles.isNotEmpty()) {
                report.exported("音频章节")
            }
            exportProgress[book.bookUrl] = index + 1
            updateAudioExportStatus(book, "正在整理音频 ${index + 1}/${chapters.size}")
            AudioBookArchiveChapter(
                index = index,
                title = chapter.title,
                sourceChapterUrl = chapter.url.takeIf { it.isNotBlank() },
                mediaFiles = mediaFiles.map { file ->
                    "${AudioBookArchive.MEDIA_DIR_NAME}/${file.name}"
                },
                variable = chapter.variable,
                start = chapter.start,
                end = chapter.end,
            )
        }
        report.expect("音频书清单")
        return report.capture("音频书清单", AudioBookArchive.MANIFEST_FILE_NAME) {
            File(tmpRoot, AudioBookArchive.MANIFEST_FILE_NAME).also { manifestFile ->
                manifestFile.writeText(
                    GSON.toJson(
                        AudioBookArchiveManifest(
                            textFile = txtName,
                            name = book.name,
                            author = book.author,
                            intro = book.intro,
                            chapters = manifestChapters,
                        )
                    ),
                    Charsets.UTF_8,
                )
            }
        }
    }

    /** 音频归档按目录逐章尝试，完整与否只影响该章报告，不再成为整书门槛。 */
    private fun audioArchiveChapters(book: Book, report: BookExportReport): List<BookChapter> {
        report.expect("章节目录")
        val chapters = runCatching {
            appDb.bookChapterDao.getChapterList(book.bookUrl).filterNot { it.isVolume }
        }.getOrElse { error ->
            report.issue("章节目录", book.name, error)
            emptyList()
        }
        if (chapters.isNotEmpty()) report.exported("章节目录")
        if (chapters.isEmpty()) {
            report.issue("章节目录", book.name, "没有读到音频章节目录")
        }
        chapters.groupBy(BookChapter::index).filterValues { it.size > 1 }.forEach { (index, matches) ->
            report.issue("章节目录", "源索引 $index", "存在 ${matches.size} 个重复章节，全部保留")
        }
        chapters.filter { it.url.isNotBlank() }
            .groupBy(BookChapter::url)
            .filterValues { it.size > 1 }
            .forEach { (url, matches) ->
                report.issue("章节目录", url, "存在 ${matches.size} 个重复章节标识，全部保留")
            }
        return chapters
    }

    /**
     * One immutable chapter selection drives every chapter-owned audio archive artifact.
     * Sidecars use dense archive-local indices because imported Manifest chapters are rebuilt in
     * selection order rather than retaining sparse source-book indices.
     */
    private class AudioExportSelection(
        val chapters: List<BookChapter>,
    ) {
        private data class SelectedChapter(
            val archiveIndex: Int,
            val chapter: BookChapter,
        )

        private val selected = chapters.mapIndexed { archiveIndex, chapter ->
            SelectedChapter(archiveIndex, chapter)
        }
        private val bySourceIndex = selected.groupBy { it.chapter.index }
            .filterValues { it.size == 1 }
            .mapValues { it.value.single() }
        private val bySourceUrl = selected.mapNotNull { selected ->
            val chapter = selected.chapter
            chapter.url.takeIf { it.isNotBlank() }
                ?.let { it to selected }
        }.groupBy({ it.first }, { it.second })
            .filterValues { it.size == 1 }
            .mapValues { it.value.single() }

        fun remapIllustrations(
            records: List<BookIllustration>,
            report: BookExportReport,
        ): List<BookIllustration> {
            return records.map { record ->
                val selected = bySourceUrl[record.chapterUrl] ?: bySourceIndex[record.chapterIndex]
                if (selected == null) {
                    report.issue(
                        "配图记录",
                        "${record.chapterName}#${record.chapterIndex}",
                        "无法匹配音频章节，保留原始章节索引",
                    )
                    return@map record
                }
                record.copy(
                    chapterIndex = selected.archiveIndex,
                    chapterUrl = selected.chapter.url,
                    chapterName = selected.chapter.title,
                )
            }
        }

        fun remapBookmarks(records: List<Bookmark>, report: BookExportReport): List<Bookmark> {
            return records.map { record ->
                val selected = bySourceIndex[record.chapterIndex]
                if (selected == null) {
                    report.issue(
                        "书签",
                        "${record.chapterName}#${record.chapterIndex}",
                        "无法匹配音频章节，保留原始章节索引",
                    )
                    return@map record
                }
                record.copy(
                    chapterIndex = selected.archiveIndex,
                    chapterName = selected.chapter.title,
                )
            }
        }
    }

    private suspend fun zipAudioBookArchive(
        book: Book,
        entries: Collection<File>,
        target: File,
    ) {
        val exportContext = currentCoroutineContext()
        var lastUpdateAt = 0L
        updateAudioExportStatus(book, "正在打包音频")
        require(
            ZipUtils.zipFiles(
                srcFiles = entries,
                zipFile = target,
                compressionLevel = Deflater.NO_COMPRESSION,
            ) { processedBytes, totalBytes ->
                exportContext.ensureActive()
                val now = System.currentTimeMillis()
                if (now - lastUpdateAt >= 350L || processedBytes == totalBytes) {
                    lastUpdateAt = now
                    updateAudioExportStatus(
                        book,
                        "正在打包音频 ${ConvertUtils.formatFileSize(processedBytes)}/" +
                            ConvertUtils.formatFileSize(totalBytes),
                    )
                }
            }
        ) {
            "Audio TXT-ZIP export failed: unable to create archive"
        }
    }

    private suspend fun zipTxtArchive(
        book: Book,
        entries: Collection<File>,
        target: File,
    ) {
        if (book.isAudio) {
            zipAudioBookArchive(book, entries, target)
            return
        }
        val context = currentCoroutineContext()
        require(ZipUtils.zipFiles(entries, target, onProgress = { _, _ -> context.ensureActive() })) {
            "TXT-ZIP export failed: unable to create archive"
        }
    }

    private fun updateAudioExportStatus(book: Book, status: String) {
        exportMsg[book.bookUrl] = status
        postEvent(EventBus.EXPORT_BOOK, book.bookUrl)
    }

    /** WebDAV 是本地导出完成后的独立投递；投递失败必须显式返回，但不能抹掉本地成功。 */
    private suspend fun uploadExportToWebDav(uri: Uri, fileName: String): String? {
        return try {
            AppWebDav.exportWebDav(uri, fileName)
            null
        } catch (error: Throwable) {
            currentCoroutineContext().ensureActive()
            error.localizedMessage?.trim().orEmpty().ifBlank {
                "本地导出已保存，WebDAV 上传失败：${error::class.java.simpleName}"
            }
        }
    }

    /**
     * 导出 PDF：纯文本排版，不包含配图/书签等附加数据。
     */
    private suspend fun exportPdf(path: String, book: Book, config: ExportConfig) {
        exportMsg.remove(book.bookUrl)
        postEvent(EventBus.EXPORT_BOOK, book.bookUrl)
        val fileDoc = FileDoc.fromDir(path)
        exportPdf(fileDoc, book, config)
    }

    private suspend fun exportPdf(fileDoc: FileDoc, book: Book, config: ExportConfig) {
        val pdfName = book.getLiteralExportFileName("pdf", config.bookExportFileName)
        fileDoc.find(pdfName)?.delete()
        val tmpRoot = FileUtils.createFolderIfNotExist(appCtx.externalCache, "ExportPdf")
        FileUtils.delete(tmpRoot)
        FileUtils.createFolderIfNotExist(tmpRoot.absolutePath)
        val tmpPdf = File(tmpRoot, pdfName)
        try {
            renderPdf(book, config, tmpPdf)
            val doc = fileDoc.createFileIfNotExistWithMime(pdfName, "application/pdf")
            doc.openOutputStream(truncate = true).getOrThrow().use { out ->
                tmpPdf.inputStream().use { it.copyTo(out) }
            }
            if (config.toWebDav) {
                AppWebDav.exportWebDav(doc.uri, pdfName)
            }
            lastExportFileName = pdfName
        } finally {
            FileUtils.delete(tmpRoot)
        }
    }

    /**
     * 把书籍排版成 PDF 页：A4 页、逐段折行，仅输出正文文本。
     */
    private fun renderPdf(
        book: Book,
        config: ExportConfig,
        outFile: File
    ) {
        val document = PdfDocument()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = 12f
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        }
        val pageWidth = 595f
        val pageHeight = 842f
        val margin = 40f
        val contentWidth = pageWidth - margin * 2
        val lineHeight = 15f
        val paragraphGap = 7f
        var page: PdfDocument.Page? = null
        var y = margin

        fun ensurePage() {
            if (page == null || y > pageHeight - margin) {
                if (page != null) {
                    document.finishPage(page)
                }
                val info = PdfDocument.PageInfo.Builder(
                    pageWidth.toInt(),
                    pageHeight.toInt(),
                    document.pages.size
                ).create()
                page = document.startPage(info)
                y = margin
            }
        }

        fun newPage() {
            if (page != null) {
                document.finishPage(page)
                page = null
            }
        }

        fun drawParagraph(text: String) {
            if (text.isBlank()) {
                y += lineHeight * 0.5f
                return
            }
            val lines = wrapPdfText(paint, text, contentWidth)
            for (line in lines) {
                if (y + lineHeight > pageHeight - margin) {
                    newPage()
                    ensurePage()
                }
                page?.canvas?.drawText(line, margin, y + paint.textSize, paint)
                y += lineHeight
            }
            y += paragraphGap
        }

        val useReplace = config.useReplace && book.getUseReplaceRule()
        val contentProcessor = ContentProcessor.get(book.name, book.origin)
        val chapterList = appDb.bookChapterDao.getChapterList(book.bookUrl)
        chapterList.forEach { chapter ->
            val content = BookHelp.getContent(book, chapter).withoutReadableContentVersionFlag()
            val paragraphs = contentProcessor
                .getContent(
                    book,
                    chapter,
                    content ?: if (chapter.isVolume) "" else "null",
                    includeTitle = false,
                    useReplace = useReplace,
                    chineseConvert = false,
                    reSegment = false
                ).toString().split("\n")
            paragraphs.forEach { drawParagraph(it) }
        }
        newPage()
        FileOutputStream(outFile).use { document.writeTo(it) }
        document.close()
    }

    private fun wrapPdfText(paint: Paint, text: String, width: Float): List<String> {
        val lines = arrayListOf<String>()
        text.split("\n").forEach { segment ->
            if (segment.isEmpty()) {
                lines.add("")
                return@forEach
            }
            val sb = StringBuilder()
            for (ch in segment) {
                val candidate = sb.toString() + ch
                if (sb.isNotEmpty() && paint.measureText(candidate) > width) {
                    lines.add(sb.toString())
                    sb.setLength(0)
                }
                sb.append(ch)
            }
            if (sb.isNotEmpty()) lines.add(sb.toString())
        }
        return lines
    }

    private suspend fun getAllContents(
        book: Book,
        config: ExportConfig,
        reportProgress: Boolean = true,
        chapters: List<BookChapter>? = null,
        report: BookExportReport? = null,
        append: suspend (text: String, srcList: ArrayList<SrcData>?) -> Unit
    ) = coroutineScope {
        val useReplace = config.useReplace && book.getUseReplaceRule()
        val contentProcessor = ContentProcessor.get(book.name, book.origin)
        val qy = "${book.name}\n${
            getString(R.string.author_show, book.getRealAuthor())
        }\n${
            getString(
                R.string.intro_show,
                "\n" + HtmlFormatter.format(book.getDisplayIntro())
            )
        }"
        append(qy, null)
        val threads = if (config.parallelExport) {
            AppConst.MAX_THREAD
        } else {
            1
        }
        val selectedChapters = chapters ?: if (report == null) {
            appDb.bookChapterDao.getChapterList(book.bookUrl)
        } else {
            report.expect("章节目录")
            report.capture("章节目录", book.name) {
                appDb.bookChapterDao.getChapterList(book.bookUrl)
            }.orEmpty()
        }
        report?.expect("正文", selectedChapters.size)
        if (selectedChapters.isEmpty()) {
            report?.issue("正文", book.name, "章节目录为空")
        }
        flow {
            selectedChapters.forEach { chapter ->
                emit(chapter)
            }
        }.mapAsync(threads) { chapter ->
            try {
                getExportData(book, chapter, contentProcessor, useReplace, config).also {
                    report?.exported("正文")
                }
            } catch (error: Throwable) {
                currentCoroutineContext().ensureActive()
                val item = "第${chapter.index + 1}章 ${chapter.title}"
                report?.issue("正文", item, error)
                val reason = error.localizedMessage?.trim().orEmpty().ifBlank { error::class.java.simpleName }
                Pair(
                    buildString {
                        append("\n\n")
                        if (!config.noChapterName) appendLine(chapter.title)
                        append("【本章未能导出：$reason】")
                    },
                    null,
                )
            }
        }.collectIndexed { index, result ->
            if (reportProgress) {
                exportProgress[book.bookUrl] = index
                postEvent(EventBus.EXPORT_BOOK, book.bookUrl)
            }
            append.invoke(result.first, result.second)
        }

    }

    private fun getExportData(
        book: Book,
        chapter: BookChapter,
        contentProcessor: ContentProcessor,
        useReplace: Boolean,
        config: ExportConfig
    ): Pair<String, ArrayList<SrcData>?> {
        val content = if (book.isAudio) {
            AudioTextFusion.effectiveLyric(chapter)
        } else {
            BookHelp.getContent(book, chapter).withoutReadableContentVersionFlag()
        }
        require(content != null || chapter.isVolume) {
            "正文或字幕缓存不存在"
        }
        val content1 = contentProcessor
            .getContent(
                book,
                // 不导出vip标识
                chapter.copy(isVip = false),
                content.orEmpty(),
                includeTitle = !config.noChapterName,
                useReplace = useReplace,
                chineseConvert = false,
                reSegment = false
            ).toString()
            .let { ExportImageSanitizer.cleanSvgUrlOptionImages(it, keepReviewButtons = true) }
        if (config.pictureFile) {
            //txt导出图片文件
            val srcList = arrayListOf<SrcData>()
            content?.split("\n")?.forEachIndexed { index, text ->
                val matcher = AppPattern.imgPattern.matcher(text)
                while (matcher.find()) {
                    matcher.group(1)?.let {
                        val imageSrc = ExportImageSanitizer.normalizeSrc(it)
                        if (imageSrc.removeTag) {
                            return@let
                        }
                        val src = NetworkUtils.getAbsoluteURL(chapter.url, imageSrc.src)
                        srcList.add(SrcData(chapter.title, index, src))
                    }
                }
            }
            return Pair("\n\n$content1", srcList)
        } else {
            return Pair("\n\n$content1", null)
        }
    }

    private fun String?.withoutReadableContentVersionFlag(): String? {
        return this?.replace(EpubFile.READABLE_CONTENT_VERSION_FLAG, "")
    }

    private fun String.toExportImageDirName(defaultName: String): String {
        val name = trim()
            .normalizeFileName()
            .trim()
            .ifBlank { defaultName }
        return name
    }

    /**
     * 导出Epub
     */
    private suspend fun exportEpub(path: String, book: Book, config: ExportConfig) {
        exportMsg.remove(book.bookUrl)
        postEvent(EventBus.EXPORT_BOOK, book.bookUrl)
        val fileDoc = FileDoc.fromDir(path)
        exportEpub(fileDoc, book, config)
    }

    private suspend fun exportEpub(fileDoc: FileDoc, book: Book, config: ExportConfig) {
        if (appDb.bookChapterDao.getChapterCount(book.bookUrl) == 0) {
            throw NoStackTraceException("EPUB 导出失败：书籍没有章节目录")
        }
        val filename = book.getLiteralExportFileName("epub", config.bookExportFileName)

        val epubBook = EpubBook()
        epubBook.version = "2.0"
        //set metadata
        setEpubMetadata(book, epubBook)
        //set cover
        setCover(book, epubBook)
        //set css
        val applyExportStyle = !config.epubUseExternalTemplate
        if (applyExportStyle) {
            addExportStyleAssets(epubBook, config)
        }
        val contentModel = setAssets(
            fileDoc,
            book,
            epubBook,
            config.epubUseExternalTemplate,
            applyExportStyle
        )

        //设置正文
        setEpubContent(contentModel, book, epubBook, config)

        val bookDoc = saveEpubBook(fileDoc, filename, epubBook)

        if (config.toWebDav) {
            // 导出到webdav
            AppWebDav.exportWebDav(bookDoc.uri, filename)
        }
        lastExportFileName = filename
    }

    private fun saveEpubBook(
        fileDoc: FileDoc,
        filename: String,
        epubBook: EpubBook,
        onProgressing: ((total: Int, progress: Int) -> Unit)? = null
    ): FileDoc {
        fileDoc.find(filename)?.delete()
        val bookDoc = fileDoc.createFileIfNotExistWithMime(filename, "application/epub+zip")
        bookDoc.openOutputStream(truncate = true).getOrThrow().buffered().use { bookOs ->
            val writer = EpubWriter()
            onProgressing?.let { callback ->
                writer.setCallback(object : EpubWriterProcessor.Callback {
                    override fun onProgressing(total: Int, progress: Int) {
                        callback(total, progress)
                    }
                })
            }
            writer.write(epubBook, bookOs)
        }
        val savedDoc = fileDoc.find(filename) ?: bookDoc
        if (!savedDoc.exists() || !savedDoc.hasContent()) {
            throw NoStackTraceException("EPUB export failed: empty output file $filename")
        }
        return savedDoc
    }

    private fun FileDoc.hasContent(): Boolean {
        if (size > 0L) {
            return true
        }
        return openInputStream().getOrNull()?.use { it.read() != -1 } == true
    }

    private fun setAssets(
        doc: FileDoc,
        book: Book,
        epubBook: EpubBook,
        useExternalTemplate: Boolean,
        applyExportStyle: Boolean
    ): String {
        val customPath = doc.find("Asset")
        val contentModel = if (useExternalTemplate && customPath != null) {//外部模板
            setAssetsExternal(customPath, book, epubBook)
        } else {//使用内置模板
            setAssets(book, epubBook, applyExportStyle)
        }

        return contentModel
    }

    private fun setAssetsExternal(doc: FileDoc, book: Book, epubBook: EpubBook): String {
        var contentModel = ""
        doc.list()!!.forEach { folder ->
            if (folder.isDir && folder.name == "Text") {
                folder.list()!!.sortedWith { o1, o2 ->
                    o1.name.cnCompare(o2.name)
                }.forEach loop@{ file ->
                    if (file.isDir) {
                        return@loop
                    }
                    when {
                        //正文模板
                        file.name.equals("chapter.html", true)
                                || file.name.equals("chapter.xhtml", true) -> {
                            contentModel = file.readText()
                        }
                        //封面等其他模板
                        file.name.endsWith("html", true) -> {
                            epubBook.addSection(
                                FileUtils.getNameExcludeExtension(file.name),
                                ResourceUtil.createPublicResource(
                                    book.name,
                                    book.getRealAuthor(),
                                    book.getDisplayIntro(),
                                    book.kind,
                                    book.wordCount,
                                    file.readText(),
                                    "${folder.name}/${file.name}"
                                )
                            )
                        }
                        //其他格式文件当做资源文件
                        else -> {
                            epubBook.resources.add(
                                Resource(
                                    file.readBytes(),
                                    "${folder.name}/${file.name}"
                                )
                            )
                        }
                    }
                }
            } else if (folder.isDir) {
                //资源文件
                folder.list()!!.forEach loop2@{
                    if (it.isDir) {
                        return@loop2
                    }
                    epubBook.resources.add(
                        Resource(
                            it.readBytes(),
                            "${folder.name}/${it.name}"
                        )
                    )
                }
            } else {//Asset下面的资源文件
                epubBook.resources.add(
                    Resource(
                        folder.readBytes(),
                        folder.name
                    )
                )
            }
        }
        return contentModel
    }

    private fun setAssets(book: Book, epubBook: EpubBook, applyExportStyle: Boolean): String {
        epubBook.resources.add(
            Resource(
                appCtx.assets.open("epub/main.css").use { it.readBytes() },
                "Styles/main.css"
            )
        )
        epubBook.addSection(
            getString(R.string.img_cover),
            ResourceUtil.createPublicResource(
                book.name,
                book.getRealAuthor(),
                book.getDisplayIntro(),
                book.kind,
                book.wordCount,
                readEpubAssetText("epub/cover.html"),
                "Text/cover.html"
            )
        )
        val introModel = readEpubAssetText("epub/intro.html").let {
            if (applyExportStyle) it.withExportCssLink() else it
        }
        epubBook.addSection(
            getString(R.string.book_intro),
            ResourceUtil.createPublicResource(
                book.name,
                book.getRealAuthor(),
                book.getDisplayIntro(),
                book.kind,
                book.wordCount,
                introModel,
                "Text/intro.html"
            )
        )
        return readEpubAssetText("epub/chapter.html").let {
            if (applyExportStyle) it.withExportCssLink() else it
        }
    }

    private fun addExportStyleAssets(
        epubBook: EpubBook,
        config: ExportConfig
    ) {
        val embeddedFontHref = addExportFont(epubBook, config)
        val embeddedBackgroundHref = addExportBackgroundImage(epubBook, config)
        epubBook.resources.add(
            Resource(
                buildExportCss(
                    config,
                    embeddedFontHref,
                    embeddedBackgroundHref
                ).toByteArray(Charsets.UTF_8),
                "Styles/export.css"
            )
        )
    }

    private fun readEpubAssetText(path: String): String {
        return appCtx.assets.open(path).use { String(it.readBytes(), Charsets.UTF_8) }
    }

    private fun addExportFont(epubBook: EpubBook, config: ExportConfig): String? {
        val fontPath = config.epubFontPath?.takeIf { it.isNotBlank() } ?: return null
        if (!config.epubEmbedFont) {
            return null
        }
        return kotlin.runCatching {
            val fontDoc = FileDoc.fromFile(fontPath)
            val suffix = fontDoc.name.substringAfterLast('.', "ttf")
                .lowercase()
                .takeIf { it == "ttf" || it == "otf" }
                ?: "ttf"
            val href = "Fonts/export_font.$suffix"
            epubBook.resources.add(Resource(fontDoc.readBytes(), href))
            "../$href"
        }.onFailure {
            AppLog.put("EPUB export font embed failed\n${it.localizedMessage}", it)
        }.getOrNull()
    }

    private fun addExportBackgroundImage(epubBook: EpubBook, config: ExportConfig): String? {
        if (!config.epubUseBackgroundImage) {
            return null
        }
        val backgroundPath = config.epubBackgroundImagePath?.takeIf { it.isNotBlank() } ?: return null
        return kotlin.runCatching {
            val (name, bytes) = if (backgroundPath.startsWith(EPUB_ASSET_BACKGROUND_PREFIX)) {
                val assetName = backgroundPath.removePrefix(EPUB_ASSET_BACKGROUND_PREFIX)
                assetName to appCtx.assets.open("bg/$assetName").use { it.readBytes() }
            } else {
                val backgroundDoc = FileDoc.fromFile(backgroundPath)
                backgroundDoc.name to backgroundDoc.readBytes()
            }
            val suffix = name.substringBefore('?')
                .substringAfterLast('.', "jpg")
                .lowercase()
                .takeIf { it in setOf("jpg", "jpeg", "png", "bmp", "webp") }
                ?: "jpg"
            val href = "Images/export_bg.$suffix"
            epubBook.resources.add(Resource(bytes, href))
            "../$href"
        }.onFailure {
            AppLog.put("EPUB export background image embed failed\n${it.localizedMessage}", it)
        }.getOrNull()
    }

    private fun buildExportCss(
        config: ExportConfig,
        embeddedFontHref: String?,
        embeddedBackgroundHref: String?
    ): String {
        val textSize = config.epubTextSize.coerceIn(8, 72)
        val lineHeight = ((textSize + config.epubLineHeight.coerceIn(0, 120))
            .coerceAtLeast(textSize) * 100 / textSize).coerceAtLeast(100)
        val paragraphSpacing = config.epubParagraphSpacing.coerceIn(0, 120)
        val textColor = config.epubTextColor.normalizeCssColor("#3E3D3B")
        val titleColor = config.epubTitleColor.normalizeCssColor("#3F83E8")
        val backgroundColor = config.epubBackgroundColor.normalizeCssColorWithAlpha("#FFFFFF")
        val paragraphIndent = config.epubParagraphIndent.normalizeCssLength()
        val fontFamily = when {
            embeddedFontHref != null -> "\"LegadoExportFont\", "
            !config.epubFontPath.isNullOrBlank() -> "\"${config.epubFontPath.toFontFamilyName().escapeCssString()}\", "
            else -> ""
        }
        val fontFace = embeddedFontHref?.let {
            """
            @font-face {
                font-family: "LegadoExportFont";
                src: url("$it");
            }

            """.trimIndent()
        }.orEmpty()
        val backgroundImage = embeddedBackgroundHref?.let {
            """
                background-image: url("$it");
                background-size: cover;
                background-repeat: no-repeat;
                background-attachment: fixed;
            """.trimIndent()
        }.orEmpty()
        return """
            @charset "utf-8";
            $fontFace
            body {
                background-color: $backgroundColor;
                $backgroundImage
            }

            html, body {
                color: $textColor;
            }

            body, div {
                color: $textColor;
                font-family: ${fontFamily}"Songti SC", "Songti TC", "宋体", serif;
                font-size: ${textSize}px;
                line-height: $lineHeight%;
            }

            p {
                color: $textColor;
                font-family: ${fontFamily}"Songti SC", "Songti TC", "宋体", serif;
                font-size: ${textSize}px;
                line-height: $lineHeight%;
                margin-top: 0;
                margin-bottom: ${paragraphSpacing}px;
                text-indent: $paragraphIndent;
                duokan-text-indent: $paragraphIndent;
            }

            h1, h2, h3, h4, h1.head, h2.head {
                color: $titleColor;
                font-family: ${fontFamily}"Heiti SC", "Heiti TC", "黑体", sans-serif;
                background: transparent;
                border: 0;
                text-indent: 0;
                duokan-text-indent: 0;
            }

            h1.head, h2.head {
                color: $titleColor;
                text-align: center;
                margin: 1em 0 1em 0;
            }

            h2.head span {
                color: inherit;
                background: transparent;
                border-radius: 0;
                padding: 0;
            }
        """.trimIndent()
    }

    private fun String.withExportCssLink(): String {
        val link = """    <link href="../Styles/export.css" type="text/css" rel="stylesheet"/>"""
        if (contains("Styles/export.css", ignoreCase = true)) {
            return this
        }
        if (contains("</head>", ignoreCase = true)) {
            return replace("</head>", "$link\n</head>", ignoreCase = true)
        }
        return "$link\n$this"
    }

    private fun setCover(book: Book, epubBook: EpubBook) {
        kotlin.runCatching {
            val file = Glide.with(this)
                .asFile()
                .load(book.getDisplayCover())
                .submit()
                .get()
            val provider = LazyResourceProvider { _ ->
                file.inputStream()
            }
            epubBook.coverImage = LazyResource(provider, "Images/cover.jpg")
        }.onFailure {
            AppLog.put("获取书籍封面出错\n${it.localizedMessage}", it)
        }
    }

    private suspend fun setEpubContent(
        contentModel: String,
        book: Book,
        epubBook: EpubBook,
        config: ExportConfig
    ) = coroutineScope {
        //正文
        val useReplace = config.useReplace && book.getUseReplaceRule()
        val contentProcessor = ContentProcessor.get(book.name, book.origin)
        val replaceBook = book.toReplaceBook()
        val threads = if (config.parallelExport) {
            AppConst.MAX_THREAD
        } else {
            1
        }
        var parentSection: TOCReference? = null
        flow {
            appDb.bookChapterDao.getChapterList(book.bookUrl).forEach { chapter ->
                emit(chapter)
            }
        }.mapAsyncIndexed(threads) { index, chapter ->
            val content = BookHelp.getContent(book, chapter).withoutReadableContentVersionFlag()
            val (contentFix, resources) = fixPic(
                book,
                content ?: if (chapter.isVolume) "" else "null",
                chapter
            )
            // 不导出vip标识
            chapter.isVip = false
            val content1 = contentProcessor
                .getContent(
                    book,
                    chapter,
                    contentFix,
                    includeTitle = false,
                    useReplace = useReplace,
                    chineseConvert = false,
                    reSegment = false
                ).toString()
            val title = chapter.run {
                // 不导出vip标识
                isVip = false
                getDisplayTitle(
                    contentProcessor.getTitleReplaceRules(),
                    useReplace = useReplace,
                    replaceBook = replaceBook
                )
            }
            val chapterResource = ResourceUtil.createChapterResource(
                title.replace("\uD83D\uDD12", ""),
                content1,
                contentModel,
                "Text/chapter_${index}.html"
            )
            ExportChapter(title, chapterResource, resources, chapter)
        }.collectIndexed { index, exportChapter ->
            postEvent(EventBus.EXPORT_BOOK, book.bookUrl)
            exportProgress[book.bookUrl] = index
            val (title, chapterResource, resources, chapter) = exportChapter
            epubBook.resources.addAll(resources)
            if (chapter.isVolume) {
                parentSection = epubBook.addSection(title, chapterResource)
            } else if (parentSection == null) {
                epubBook.addSection(title, chapterResource)
            } else {
                epubBook.addSection(parentSection, title, chapterResource)
            }
        }
    }

    data class ExportChapter(
        val title: String,
        val chapterResource: Resource,
        val resources: ArrayList<Resource>,
        val chapter: BookChapter
    )

    private fun fixPic(
        book: Book,
        content: String,
        chapter: BookChapter
    ): Pair<String, ArrayList<Resource>> {
        val data = StringBuilder("")
        val resources = arrayListOf<Resource>()
        ExportImageSanitizer.cleanSvgUrlOptionImages(content).split("\n").forEach { text ->
            var text1 = text
            val matcher = AppPattern.imgPattern.matcher(text)
            while (matcher.find()) {
                matcher.group(1)?.let {
                    val imageSrc = ExportImageSanitizer.normalizeSrc(it)
                    if (imageSrc.removeTag) {
                        return@let
                    }
                    val src = NetworkUtils.getAbsoluteURL(chapter.url, imageSrc.src)
                    val originalHref =
                        "${MD5Utils.md5Encode16(src)}.${BookHelp.getImageSuffix(src)}"
                    val href =
                        "Images/${MD5Utils.md5Encode16(src)}.${BookHelp.getImageSuffix(src)}"
                    val vFile = BookHelp.getImage(book, src)
                    val fp = FileResourceProvider(vFile.parent)
                    if (vFile.exists()) {
                        val img = LazyResource(fp, href, originalHref)
                        resources.add(img)
                        text1 = text1.replace(it, "../${href}")
                    } else if (imageSrc.hasUrlOption) {
                        text1 = text1.replace(it, imageSrc.src)
                    }
                }
            }
            data.append(text1).append("\n")
        }
        return data.toString() to resources
    }

    private fun setEpubMetadata(book: Book, epubBook: EpubBook) {
        val metadata = Metadata()
        metadata.titles.add(book.name)//书籍的名称
        metadata.authors.add(Author(book.getRealAuthor()))//书籍的作者
        metadata.language = "zh"//数据的语言
        metadata.dates.add(Date())//数据的创建日期
        metadata.publishers.add("Legado")//数据的创建者
        metadata.descriptions.add(book.getDisplayIntro())//书籍的简介
        //metadata.subjects.add("")//书籍的主题，在静读天下里面有使用这个分类书籍
        epubBook.metadata = metadata
    }

    //////end of EPUB

    //////start of custom exporter
    /**
     * 自定义Exporter
     * @param scope 导出范围
     * @param size epub 文件包含最大章节数
     */
    inner class CustomExporter(
        scopeStr: String,
        private val size: Int,
        private val config: ExportConfig
    ) {

        private var scope = parseScope(scopeStr)

        /**
         * 导出Epub
         * @param path 导出的路径
         * @param book 书籍
         */
        suspend fun export(
            path: String,
            book: Book
        ) {
            exportProgress[book.bookUrl] = 0
            exportMsg.remove(book.bookUrl)
            postEvent(EventBus.EXPORT_BOOK, book.bookUrl)
            val currentTimeMillis = System.currentTimeMillis()
            val count = appDb.bookChapterDao.getChapterCount(book.bookUrl)
            scope = scope.filter { it < count }.toHashSet()

            val fileDoc = FileDoc.fromDir(path)

            val (contentModel, epubList) = createEpubs(book, fileDoc)
            var progressBar = 0.0
            epubList.forEachIndexed { index, ep ->
                val (filename, epubBook) = ep
                //设置正文
                setEpubContent(
                    contentModel,
                    book,
                    epubBook,
                    index
                ) { _, _ ->
                    // 将章节写入内存时更新进度条
                    postEvent(EventBus.EXPORT_BOOK, book.bookUrl)
                    progressBar += book.totalChapterNum.toDouble() / scope.size / 2
                    exportProgress[book.bookUrl] = progressBar.toInt()
                }
                save2Drive(filename, epubBook, fileDoc) { total, _ ->
                    //写入硬盘时更新进度条
                    progressBar += book.totalChapterNum.toDouble() / epubList.size / total / 2
                    postEvent(EventBus.EXPORT_BOOK, book.bookUrl)
                    exportProgress[book.bookUrl] = progressBar.toInt()
                }
            }

            val elapsed = System.currentTimeMillis() - currentTimeMillis
            AppLog.put("分割导出书籍 ${book.name} 一共耗时 $elapsed")
        }


        /**
         * 设置epub正文
         *
         * @param contentModel 正文模板
         * @param book 书籍
         * @param epubBook 分割后的epub
         * @param epubBookIndex 分割后的epub序号
         */
        private suspend fun setEpubContent(
            contentModel: String,
            book: Book,
            epubBook: EpubBook,
            epubBookIndex: Int,
            updateProgress: (chapterList: MutableList<BookChapter>, index: Int) -> Unit
        ) {
            //正文
            val useReplace = config.useReplace && book.getUseReplaceRule()
            val contentProcessor = ContentProcessor.get(book.name, book.origin)
            val replaceBook = book.toReplaceBook()
            var chapterList: MutableList<BookChapter> = ArrayList()
            appDb.bookChapterDao.getChapterList(book.bookUrl).forEachIndexed { index, chapter ->
                if (scope.contains(index)) {
                    chapterList.add(chapter)
                }
                if (scope.size == chapterList.size) {
                    return@forEachIndexed
                }
            }
            // val totalChapterNum = book.totalChapterNum / scope.size
            if (chapterList.isEmpty()) {
                throw RuntimeException("书籍<${book.name}>(${epubBookIndex + 1})未找到章节信息")
            }
            chapterList = chapterList.subList(
                epubBookIndex * size,
                min(scope.size, (epubBookIndex + 1) * size)
            )
            chapterList.forEachIndexed { index, chapter ->
                currentCoroutineContext().ensureActive()
                updateProgress(chapterList, index)
                BookHelp.getContent(book, chapter).withoutReadableContentVersionFlag().let { content ->
                    val (contentFix, resources) = fixPic(
                        book,
                        content ?: if (chapter.isVolume) "" else "null",
                        chapter
                    )
                    epubBook.resources.addAll(resources)
                    val content1 = contentProcessor
                        .getContent(
                            book,
                            chapter,
                            contentFix,
                            includeTitle = false,
                            useReplace = useReplace,
                            chineseConvert = false,
                            reSegment = false
                        ).toString()
                    val title = chapter.run {
                        // 不导出vip标识
                        isVip = false
                        getDisplayTitle(
                            contentProcessor.getTitleReplaceRules(),
                            useReplace = useReplace,
                            replaceBook = replaceBook
                        )
                    }
                    epubBook.addSection(
                        title,
                        ResourceUtil.createChapterResource(
                            title.replace("\uD83D\uDD12", ""),
                            content1,
                            contentModel,
                            "Text/chapter_${index}.html"
                        )
                    )
                }
            }
        }

        /**
         * 创建多个epub 对象
         *
         * 分割epub时，一个书籍需要创建多个epub对象
         * @param book 书籍
         * @param fileDoc 导出文件夹文档
         *
         * @return <内容模板字符串, <epub文件名, epub对象>>
         */
        private fun createEpubs(
            book: Book,
            fileDoc: FileDoc
        ): Pair<String, List<Pair<String, EpubBook>>> {
            val paresNumOfEpub = paresNumOfEpub(scope.size, size)
            val result: MutableList<Pair<String, EpubBook>> = ArrayList(paresNumOfEpub)
            var contentModel = ""
            for (i in 1..paresNumOfEpub) {
                val filename = book.getExportFileName("epub", i, config.episodeExportFileName)

                val epubBook = EpubBook()
                epubBook.version = "2.0"
                //set metadata
                setEpubMetadata(book, epubBook)
                //set cover
                setCover(book, epubBook)
                //set css
                val applyExportStyle = !config.epubUseExternalTemplate
                if (applyExportStyle) {
                    addExportStyleAssets(epubBook, config)
                }
                contentModel = setAssets(
                    fileDoc,
                    book,
                    epubBook,
                    config.epubUseExternalTemplate,
                    applyExportStyle
                )

                // add epubBook
                result.add(Pair(filename, epubBook))
            }
            return Pair(contentModel, result)
        }

        /**
         * 保存文件到 设备
         */
        private suspend fun save2Drive(
            filename: String,
            epubBook: EpubBook,
            fileDoc: FileDoc,
            callback: (total: Int, progress: Int) -> Unit
        ) {
            val bookDoc = saveEpubBook(fileDoc, filename, epubBook, callback)

            if (config.toWebDav) {
                // 导出到webdav
                AppWebDav.exportWebDav(bookDoc.uri, filename)
            }
        }

        /**
         * 解析 分割epub后的数量
         *
         * @param total 章节总数
         * @param size 每个epub文件包含多少章节
         */
        private fun paresNumOfEpub(total: Int, size: Int): Int {
            val i = total % size
            var result = total / size
            if (i > 0) {
                result++
            }
            return result
        }

        /**
         * 解析范围字符串
         *
         * @param scope 范围字符串
         * @return 范围
         *
         * @since 2023/5/22
         * @author Discut
         */
        private fun parseScope(scope: String): Set<Int> {
            val split = scope.split(",")

            val result = linkedSetOf<Int>()
            for (s in split) {
                val v = s.split("-")
                if (v.size != 2) {
                    result.add(s.toInt() - 1)
                    continue
                }
                val left = v[0].toInt()
                val right = v[1].toInt()
                if (left > right) {
                    AppLog.put("Error expression : $s; left > right")
                    continue
                }
                for (i in left..right)
                    result.add(i - 1)
            }
            return result
        }
    }
}

private fun String?.normalizeCssColor(default: String): String {
    val text = this?.trim()?.takeIf { it.isNotBlank() } ?: return default
    val color = if (text.startsWith("#")) text else "#$text"
    return if (Regex("^#([0-9a-fA-F]{6}|[0-9a-fA-F]{8})$").matches(color)) {
        if (color.length == 9) {
            // #RRGGBBAA：去掉透明度，保留 #RRGGBB
            "#${color.substring(1, 7)}".uppercase()
        } else {
            color.uppercase()
        }
    } else {
        default
    }
}

/** 背景色专用：保留透明度，输出 rgba(r,g,b,a)（兼容性最好的 CSS 写法，所有阅读器支持） */
private fun String?.normalizeCssColorWithAlpha(default: String): String {
    val text = this?.trim()?.takeIf { it.isNotBlank() } ?: return default
    val color = if (text.startsWith("#")) text else "#$text"
    if (!Regex("^#([0-9a-fA-F]{6}|[0-9a-fA-F]{8})$").matches(color)) {
        return default
    }
    val hex = color.substring(1)
    val r = hex.substring(0, 2).toInt(16)
    val g = hex.substring(2, 4).toInt(16)
    val b = hex.substring(4, 6).toInt(16)
    val a = if (hex.length == 8) hex.substring(6, 8).toInt(16) / 255f else 1f
    return "rgba($r, $g, $b, $a)"
}

private fun String?.normalizeCssLength(): String {
    val text = this?.trim()?.takeIf { it.isNotBlank() } ?: return "2em"
    if (Regex("""^\d+(\.\d+)?(em|rem|px|%)$""").matches(text)) {
        return text
    }
    return text.toFloatOrNull()?.let { "${it}em" } ?: "2em"
}

private fun String.toFontFamilyName(): String {
    return kotlin.runCatching { FileDoc.fromFile(this).name }.getOrNull()
        ?.substringBeforeLast('.')
        ?.takeIf { it.isNotBlank() }
        ?: substringAfterLast('/')
            .substringBeforeLast('.')
            .takeIf { it.isNotBlank() }
        ?: "LegadoExportFont"
}

private fun String.escapeCssString(): String {
    return replace("\\", "\\\\").replace("\"", "\\\"")
}
