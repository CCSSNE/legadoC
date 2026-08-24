package io.legado.app.service

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.system.Os
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
import io.legado.app.data.entities.ReplaceRule
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.AppWebDav
import io.legado.app.help.LifecycleHelp
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.AudioBookArchive
import io.legado.app.help.book.AudioBookArchiveChapter
import io.legado.app.help.book.AudioBookArchiveManifest
import io.legado.app.help.book.AudioOfflineState
import io.legado.app.help.book.AudioTextFusion
import io.legado.app.help.book.ContentProcessor
import io.legado.app.help.book.getExportFileName
import io.legado.app.help.book.getLiteralExportFileName
import io.legado.app.help.book.isAudio
import io.legado.app.help.book.isLocalModified
import io.legado.app.help.book.isLocal
import io.legado.app.help.book.isLocalTxt
import io.legado.app.help.illustration.IllustrationHelp
import io.legado.app.help.review.ReviewSnapshotStore
import io.legado.app.help.illustration.imageSrcsFromJson
import io.legado.app.help.config.AppConfig
import io.legado.app.help.exoplayer.ExoPlayerHelper
import io.legado.app.model.ReadBook
import io.legado.app.model.localBook.EpubFile
import io.legado.app.model.localBook.LocalBook
import io.legado.app.ui.book.cache.CacheActivity
import io.legado.app.utils.FileDoc
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
import java.io.OutputStream
import java.util.zip.Deflater
import kotlin.coroutines.CoroutineContext
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
        private const val EXPORT_IMAGE_DIR_NAME_MAX_LENGTH = 80
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
    private var notificationContentText = appCtx.getString(R.string.service_starting)
    @Volatile
    private var lastExportFileName = ""


    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            IntentAction.start -> kotlin.runCatching {
                val bookUrl = intent.getStringExtra("bookUrl")!!
                if (!exportProgress.contains(bookUrl)) {
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
        exportProgress.clear()
        exportMsg.clear()
        waitExportBooks.keys.forEach {
            postEvent(EventBus.EXPORT_BOOK, it)
        }
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
        exportJob = lifecycleScope.launch(IO) {
            while (isActive) {
                val (bookUrl, exportConfig) = waitExportBooks.entries.firstOrNull() ?: let {
                    finishExportNotification()
                    stopSelf()
                    return@launch
                }
                exportProgress[bookUrl] = 0
                waitExportBooks.remove(bookUrl)
                val book = appDb.bookDao.getBook(bookUrl)
                try {
                    book ?: throw NoStackTraceException("获取${bookUrl}书籍出错")
                    require(!book.isAudio || exportConfig.type == "txt_zip") {
                        "Audio books can only be exported as complete TXT-ZIP archives"
                    }
                    refreshChapterList(book)
                    if (exportConfig.type == "epub" &&
                        appDb.bookChapterDao.getChapterCount(book.bookUrl) == 0
                    ) {
                        throw NoStackTraceException("EPUB 导出失败：书籍没有解析出章节目录")
                    }
                    notificationContentText = getString(
                        R.string.export_book_notification_content,
                        book.name,
                        waitExportBooks.size
                    )
                    upExportNotification()
                    if (exportConfig.type == "pdf") {
                        exportPdf(exportConfig.path, book, exportConfig)
                    } else if (exportConfig.type == "epub") {
                        exportEpub(exportConfig.path, book, exportConfig)
                    } else if (exportConfig.type == "txt_zip") {
                        exportTxtZip(exportConfig.path, book, exportConfig)
                    } else {
                        exportTxt(exportConfig.path, book, exportConfig)
                    }
                    exportMsg[book.bookUrl] = getString(R.string.export_success)
                } catch (e: Throwable) {
                    ensureActive()
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
        notificationContentText = "导出完成：$lastExportFileName"
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
        appDb.bookChapterDao.delByBook(book.bookUrl)
        appDb.bookChapterDao.insert(*chapters.toTypedArray())
        appDb.bookDao.update(book)
        ReadBook.onChapterListUpdated(book)
    }

    private data class SrcData(
        val chapterTitle: String,
        val index: Int,
        val src: String
    )

    private suspend fun exportTxt(path: String, book: Book, config: ExportConfig) {
        exportMsg.remove(book.bookUrl)
        postEvent(EventBus.EXPORT_BOOK, book.bookUrl)
        val fileDoc = FileDoc.fromDir(path)
        exportTxt(fileDoc, book, config)
    }

    private suspend fun exportTxt(fileDoc: FileDoc, book: Book, config: ExportConfig) {
        val filename = book.getLiteralExportFileName("txt", config.bookExportFileName)
        fileDoc.find(filename)?.delete()

        val bookDoc = fileDoc.createFileIfNotExist(filename)
        val charset = Charset.forName(config.charset)
        bookDoc.openOutputStream().getOrThrow().bufferedWriter(charset).use { bw ->
            getAllContents(book, config) { text, srcList ->
                bw.write(text)
                srcList?.forEach {
                    val vFile = BookHelp.getImage(book, it.src)
                    if (vFile.exists()) {
                        kotlin.runCatching {
                            fileDoc.createFileIfNotExist(
                                "${it.index}-${MD5Utils.md5Encode16(it.src)}.jpg",
                                subDirs = arrayOf(
                                    "${book.name}_${book.author}".toExportImageDirName("book"),
                                    "images",
                                    it.chapterTitle.toExportImageDirName("chapter_${it.index}")
                                )
                            ).writeFile(vFile)
                        }.onFailure { e ->
                            AppLog.put("导出图片文件失败: ${book.name} ${it.chapterTitle}", e)
                        }
                    }
                }
            }
        }
        if (config.toWebDav) {
            // 导出到webdav
            AppWebDav.exportWebDav(bookDoc.uri, filename)
        }
        lastExportFileName = filename
    }

    /**
     * 带配图的 TXT 导出（独立选项，type = "txt_zip"）：
     * 压缩包 = book.txt + images/ + illustrations.json + bookmarks.json。
     * 正文 txt 对本地 TXT 书直接复制原始文件，保持与源文件字节一致，
     * 配图/媒体/书签走 sidecar，重新导入时不会章节错乱。
     */
    private suspend fun exportTxtZip(path: String, book: Book, config: ExportConfig) {
        exportMsg.remove(book.bookUrl)
        postEvent(EventBus.EXPORT_BOOK, book.bookUrl)
        val fileDoc = FileDoc.fromDir(path)
        exportTxtZip(fileDoc, book, config)
    }

    private suspend fun exportTxtZip(
        fileDoc: FileDoc,
        book: Book,
        config: ExportConfig
    ) {
        val literalTxtName = book.getLiteralExportFileName("txt", config.bookExportFileName)
        val txtName = if (book.isAudio) {
            AudioBookArchive.audioFileName(literalTxtName)
        } else {
            literalTxtName
        }
        val zipName = txtName.removeSuffix(".txt") + ".zip"
        val tmpRoot = FileUtils.createFolderIfNotExist(
            appCtx.externalCache,
            "ExportTxtZip"
        )
        FileUtils.delete(tmpRoot)
        FileUtils.createFolderIfNotExist(tmpRoot.absolutePath)
        val tmpTxt = File(tmpRoot, txtName)
        val tmpImagesDir = File(tmpRoot, IllustrationHelp.EXPORT_IMAGES_DIR)
        FileUtils.createFolderIfNotExist(tmpImagesDir.absolutePath)
        val tmpJson = File(tmpRoot, IllustrationHelp.EXPORT_JSON_NAME)
        val audioSelection = if (book.isAudio) {
            updateAudioExportStatus(book, "正在筛选已完成的音频与字幕")
            AudioExportSelection(availableAudioExportChapters(book))
        } else {
            null
        }
        val audioChapters = audioSelection?.chapters
        val illustrations = appDb.bookIllustrationDao.getByBook(book.bookUrl).let { records ->
            audioSelection?.remapIllustrations(records) ?: records
        }
        val tmpBookmarks = if (config.exportBookmarks) {
            File(tmpRoot, IllustrationHelp.EXPORT_BOOKMARKS_NAME).also { f ->
                val bookmarks = appDb.bookmarkDao.getByBook(book.name, book.author).let { records ->
                    audioSelection?.remapBookmarks(records) ?: records
                }
                if (bookmarks.isNotEmpty()) {
                    f.writeText(GSON.toJson(bookmarks), Charsets.UTF_8)
                }
            }
        } else {
            null
        }
        // 替换净化开启时，把该书当前生效的替换规则作为外挂数据写入 zip，
        // 正文 txt 保持原文，规则在导入时同步还原（原文+规则，规则不作用于导出文本）
        val tmpReplaceRules = if (config.useReplace && book.getUseReplaceRule()) {
            File(tmpRoot, IllustrationHelp.EXPORT_REPLACE_RULES_NAME).also { f ->
                val rules = arrayListOf<ReplaceRule>()
                rules.addAll(appDb.replaceRuleDao.findEnabledByContentScope(book.name, book.origin))
                rules.addAll(appDb.replaceRuleDao.findEnabledByTitleScope(book.name, book.origin))
                if (rules.isNotEmpty()) {
                    f.writeText(GSON.toJson(rules), Charsets.UTF_8)
                }
            }
        } else {
            null
        }
        // 评论页快照：按存储原样导出 reviews/*.json，导入时原样还原
        val tmpReviewsDir = if (config.exportReviews) {
            File(tmpRoot, ReviewSnapshotStore.REVIEWS_DIR_NAME).also { dir ->
                if (book.isAudio) {
                    ReviewSnapshotStore.copyChaptersTo(
                        book,
                        dir,
                        requireNotNull(audioChapters),
                    )
                } else {
                    ReviewSnapshotStore.copyAllTo(book, dir)
                }
            }
        } else {
            null
        }
        try {
            val tmpAudioManifest = if (book.isAudio) {
                exportAudioBookMedia(book, tmpRoot, txtName, requireNotNull(audioChapters))
            } else {
                null
            }
            if (book.isLocalTxt) {
                // 本地 TXT 书直接复制原始文件，保持与用户源文件字节一致：
                // 不经过 getAllContents（那会加书名头、全角缩进并重排版），
                // 否则重新导入时章节解析会错乱、书签位置对不上。
                LocalBook.getBookInputStream(book).use { input ->
                    tmpTxt.outputStream().use { out ->
                        input.copyTo(out)
                    }
                }
            } else {
                val charset = Charset.forName(config.charset)
                tmpTxt.bufferedWriter(charset).use { bw ->
                    if (book.isAudio) {
                        updateAudioExportStatus(book, "正在生成字幕文本")
                    }
                    getAllContents(
                        book,
                        config,
                        reportProgress = !book.isAudio,
                        chapters = audioChapters,
                    ) { text, _ ->
                        bw.write(text)
                    }
                }
            }
            illustrations.forEach { illustration ->
                illustration.imageSrcsFromJson().forEach { src ->
                    val srcFile = IllustrationHelp.getImageFile(book, src)
                    require(srcFile.isFile && srcFile.length() > 0L) {
                        "TXT-ZIP export failed: illustration is missing " +
                            "chapter=${illustration.chapterIndex + 1} ${illustration.chapterName} src=$src"
                    }
                    val imageName = src.substringAfter(IllustrationHelp.SRC_PREFIX)
                    val targetFile = File(tmpImagesDir, imageName)
                    srcFile.copyTo(targetFile, overwrite = true)
                    require(targetFile.length() == srcFile.length()) {
                        "TXT-ZIP export failed: illustration copy is incomplete src=$src"
                    }
                }
            }
            val jsonText = IllustrationHelp.buildExportJson(txtName, illustrations)
            if (jsonText != null) {
                tmpJson.writeText(jsonText, Charsets.UTF_8)
            }
            val tmpZip = File(tmpRoot, zipName)
            val zipEntries = arrayListOf<File>(tmpTxt, tmpImagesDir)
            tmpJson.takeIf { it.exists() }?.let { zipEntries.add(it) }
            tmpBookmarks?.takeIf { it.exists() }?.let { zipEntries.add(it) }
            tmpReplaceRules?.takeIf { it.exists() }?.let { zipEntries.add(it) }
            tmpAudioManifest?.let { manifest ->
                zipEntries.add(manifest)
                zipEntries.add(File(tmpRoot, AudioBookArchive.MEDIA_DIR_NAME))
            }
            tmpReviewsDir?.takeIf { dir ->
                dir.exists() && (dir.listFiles()?.isNotEmpty() == true)
            }?.let { zipEntries.add(it) }
            if (book.isAudio) {
                zipAudioBookArchive(book, zipEntries, tmpZip)
            } else {
                require(ZipUtils.zipFiles(zipEntries, tmpZip, null)) {
                    "TXT-ZIP export failed: unable to create archive"
                }
            }
            val zipDoc = saveCompletedTxtZip(fileDoc, zipName, tmpZip, book)
            if (config.toWebDav) {
                AppWebDav.exportWebDav(zipDoc.uri, zipName)
            }
            lastExportFileName = zipName
        } finally {
            FileUtils.delete(tmpRoot)
        }
    }

    private suspend fun exportAudioBookMedia(
        book: Book,
        tmpRoot: File,
        txtName: String,
        chapters: List<BookChapter>,
    ): File {
        val audioDir = File(tmpRoot, AudioBookArchive.MEDIA_DIR_NAME).apply { mkdirs() }
        val manifestChapters = chapters.mapIndexed { index, chapter ->
            currentCoroutineContext().ensureActive()
            val resourceUrl = requireNotNull(chapter.resourceUrl) {
                "Audio TXT-ZIP export failed: media address is missing " +
                    "chapter=${chapter.index + 1} ${chapter.title}"
            }
            val prefix = "chapter_${chapter.index.toString().padStart(6, '0')}"
            val mediaFiles = ExoPlayerHelper.exportAudioFiles(
                resourceUrl = resourceUrl,
                book = book,
                targetDir = audioDir,
                filePrefix = prefix,
            )
            require(mediaFiles.isNotEmpty()) {
                "Audio TXT-ZIP export failed: no audio for chapter ${chapter.index + 1} ${chapter.title}"
            }
            exportProgress[book.bookUrl] = index + 1
            updateAudioExportStatus(book, "正在整理音频 ${index + 1}/${chapters.size}")
            AudioBookArchiveChapter(
                index = chapter.index,
                title = chapter.title,
                sourceChapterUrl = chapter.url,
                mediaFiles = mediaFiles.map { file ->
                    "${AudioBookArchive.MEDIA_DIR_NAME}/${file.name}"
                },
                variable = chapter.variable,
                start = chapter.start,
                end = chapter.end,
            )
        }
        return File(tmpRoot, AudioBookArchive.MANIFEST_FILE_NAME).also { manifestFile ->
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

    /**
     * An audio TXT-ZIP is the coherent set of already-complete chapter artifacts. Incomplete
     * chapters are deliberately absent from both its media manifest and TXT, rather than causing
     * source resolution or a background download during export.
     */
    private fun availableAudioExportChapters(book: Book): List<BookChapter> {
        val chapters = appDb.bookChapterDao.getChapterList(book.bookUrl)
            .filterNot { it.isVolume }
        require(chapters.isNotEmpty()) { "Audio TXT-ZIP export failed: chapter list is empty" }
        val completed = chapters.filter { chapter ->
            AudioOfflineState.inspect(book, chapter).isComplete
        }
        require(completed.isNotEmpty()) {
            "Audio TXT-ZIP export failed: no chapter has both completed media and lyric"
        }
        return completed
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

        private val bySourceIndex = chapters.mapIndexed { archiveIndex, chapter ->
            chapter.index to SelectedChapter(archiveIndex, chapter)
        }.toMap()
        private val bySourceUrl = chapters.mapIndexed { archiveIndex, chapter ->
            chapter.url to SelectedChapter(archiveIndex, chapter)
        }.toMap()

        init {
            require(chapters.isNotEmpty()) { "Audio TXT-ZIP export selection is empty" }
            require(bySourceIndex.size == chapters.size) {
                "Audio TXT-ZIP export selection contains duplicate chapter indices"
            }
            require(chapters.none { it.url.isBlank() } && bySourceUrl.size == chapters.size) {
                "Audio TXT-ZIP export selection contains invalid chapter identities"
            }
        }

        fun remapIllustrations(records: List<BookIllustration>): List<BookIllustration> {
            return records.mapNotNull { record ->
                val selected = if (record.chapterUrl.isBlank()) {
                    bySourceIndex[record.chapterIndex]
                } else {
                    bySourceUrl[record.chapterUrl]
                } ?: return@mapNotNull null
                record.copy(
                    chapterIndex = selected.archiveIndex,
                    chapterUrl = selected.chapter.url,
                    chapterName = selected.chapter.title,
                )
            }
        }

        fun remapBookmarks(records: List<Bookmark>): List<Bookmark> {
            return records.mapNotNull { record ->
                val selected = bySourceIndex[record.chapterIndex] ?: return@mapNotNull null
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

    private fun copyAudioArchiveToDestination(
        book: Book,
        source: File,
        output: OutputStream,
        exportContext: CoroutineContext,
    ) {
        val totalBytes = source.length().coerceAtLeast(0L)
        var copiedBytes = 0L
        var lastUpdateAt = 0L
        updateAudioExportStatus(book, "正在保存音频压缩包")
        source.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                exportContext.ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                output.write(buffer, 0, count)
                copiedBytes += count
                val now = System.currentTimeMillis()
                if (now - lastUpdateAt >= 350L || copiedBytes == totalBytes) {
                    lastUpdateAt = now
                    updateAudioExportStatus(
                        book,
                        "正在保存音频压缩包 ${ConvertUtils.formatFileSize(copiedBytes)}/" +
                            ConvertUtils.formatFileSize(totalBytes),
                    )
                }
            }
        }
        require(copiedBytes == totalBytes) {
            "Audio TXT-ZIP export failed: archive changed while writing destination"
        }
    }

    /**
     * Writes beside the current archive and promotes only a fully persisted staging ZIP.
     * Direct-file destinations use the platform rename operation, which replaces atomically on
     * Android's Linux filesystems. SAF destinations keep the old document under a temporary backup
     * name until the staged document has been promoted successfully.
     */
    private suspend fun saveCompletedTxtZip(
        parent: FileDoc,
        zipName: String,
        source: File,
        book: Book,
    ): FileDoc {
        require(source.isFile && source.length() > 0L) {
            "TXT-ZIP export failed: generated archive is empty"
        }
        val transactionId = "${System.currentTimeMillis()}_${System.nanoTime()}"
        val stagingName = ".$zipName.$transactionId.tmp.zip"
        val backupName = ".$zipName.$transactionId.old.zip"
        require(parent.find(stagingName) == null && parent.find(backupName) == null) {
            "TXT-ZIP export failed: destination transaction names already exist"
        }

        val oldArchive = parent.find(zipName)
        val staging = parent.createFileIfNotExistWithMime(stagingName, "application/zip")
        var oldRenamed = false
        var promoted = false
        val backupDocument = oldArchive?.asDocumentFile()
        try {
            staging.openOutputStream(truncate = true).getOrThrow().use { out ->
                if (book.isAudio) {
                    copyAudioArchiveToDestination(
                        book,
                        source,
                        out,
                        currentCoroutineContext(),
                    )
                } else {
                    source.inputStream().use { it.copyTo(out) }
                }
            }
            val persistedStaging = FileDoc.fromUri(staging.uri, false)
            require(persistedStaging.size == source.length()) {
                "TXT-ZIP export failed: staged archive size mismatch " +
                    "expected=${source.length()} actual=${persistedStaging.size}"
            }

            if (!parent.isContentScheme) {
                val stagingFile = requireNotNull(persistedStaging.asFile()) {
                    "TXT-ZIP export failed: staging file path is unavailable"
                }
                val parentFile = requireNotNull(parent.asFile()) {
                    "TXT-ZIP export failed: destination directory path is unavailable"
                }
                val targetFile = File(parentFile, zipName)
                Os.rename(stagingFile.absolutePath, targetFile.absolutePath)
                promoted = true
                return FileDoc.fromFile(targetFile)
            }

            val stagingDocument = requireNotNull(staging.asDocumentFile()) {
                "TXT-ZIP export failed: staging document is unavailable"
            }
            if (backupDocument != null) {
                require(backupDocument!!.renameTo(backupName)) {
                    "TXT-ZIP export failed: unable to preserve existing $zipName"
                }
                oldRenamed = true
            }
            require(stagingDocument.renameTo(zipName)) {
                "TXT-ZIP export failed: unable to promote staged archive"
            }
            promoted = true
            val committed = FileDoc.fromDocumentFile(stagingDocument)
            backupDocument?.let { backup ->
                require(backup.delete() && !backup.exists()) {
                    "TXT-ZIP export failed: unable to remove replaced archive backup"
                }
            }
            return committed
        } catch (cause: Throwable) {
            if (!promoted && oldRenamed) {
                runCatching {
                    val backup = requireNotNull(backupDocument)
                    require(backup.renameTo(zipName)) {
                        "TXT-ZIP export rollback failed: unable to restore existing $zipName"
                    }
                }.onFailure(cause::addSuppressed)
            }
            if (!promoted) {
                runCatching {
                    if (staging.exists()) {
                        staging.delete()
                        require(!staging.exists()) {
                            "TXT-ZIP export rollback failed: unable to remove staging archive"
                        }
                    }
                }.onFailure(cause::addSuppressed)
            }
            throw cause
        }
    }

    private fun updateAudioExportStatus(book: Book, status: String) {
        exportMsg[book.bookUrl] = status
        postEvent(EventBus.EXPORT_BOOK, book.bookUrl)
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
        append: (text: String, srcList: ArrayList<SrcData>?) -> Unit
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
        flow {
            (chapters ?: appDb.bookChapterDao.getChapterList(book.bookUrl)).forEach { chapter ->
                emit(chapter)
            }
        }.mapAsync(threads) { chapter ->
            getExportData(book, chapter, contentProcessor, useReplace, config)
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
        // 未缓存章节直接跳过，不写 "null" 占位：占位章会让重新导入时分章规则
        // 误判（标题间距<100字被计为误识别），导致规则整体被拒、章节标题丢失、
        // 评论快照 remap 失败。卷章节（isVolume）无正文属正常结构，仍保留标题。
        if (content == null && !chapter.isVolume) {
            return Pair("", null)
        }
        val content1 = contentProcessor
            .getContent(
                book,
                // 不导出vip标识
                chapter.apply { isVip = false },
                content ?: if (chapter.isVolume) "" else "null",
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
        return name.take(EXPORT_IMAGE_DIR_NAME_MAX_LENGTH).ifBlank { defaultName }
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
