package io.legado.app.service

import android.annotation.SuppressLint
import android.content.Intent
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
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.AppWebDav
import io.legado.app.help.LifecycleHelp
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.ContentProcessor
import io.legado.app.help.book.getExportFileName
import io.legado.app.help.book.getLiteralExportFileName
import io.legado.app.help.book.isLocalModified
import io.legado.app.help.config.AppConfig
import io.legado.app.model.ReadBook
import io.legado.app.model.localBook.EpubFile
import io.legado.app.model.localBook.LocalBook
import io.legado.app.ui.book.cache.CacheActivity
import io.legado.app.utils.FileDoc
import io.legado.app.utils.FileUtils
import io.legado.app.utils.ExportImageSanitizer
import io.legado.app.utils.HtmlFormatter
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.NetworkUtils
import io.legado.app.utils.activityPendingIntent
import io.legado.app.utils.cnCompare
import io.legado.app.utils.createFileIfNotExist
import io.legado.app.utils.createFileIfNotExistWithMime
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
                    refreshChapterList(book)
                    notificationContentText = getString(
                        R.string.export_book_notification_content,
                        book.name,
                        waitExportBooks.size
                    )
                    upExportNotification()
                    if (exportConfig.type == "epub") {
                        exportEpub(exportConfig.path, book, exportConfig)
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
        notificationContentText = "导出完成"
        if (LifecycleHelp.isAppVisible()) {
            exportFinishedNotificationVisible = false
            notificationManager.cancel(NotificationId.ExportBook)
        } else {
            upExportNotification(true)
        }
    }

    private fun refreshChapterList(book: Book) {
        if (!book.isLocalModified()) {
            return
        }
        kotlin.runCatching {
            LocalBook.getChapterList(book)
        }.onSuccess {
            appDb.bookChapterDao.delByBook(book.bookUrl)
            appDb.bookChapterDao.insert(*it.toTypedArray())
            appDb.bookDao.update(book)
            ReadBook.onChapterListUpdated(book)
        }
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
    }

    private suspend fun getAllContents(
        book: Book,
        config: ExportConfig,
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
            appDb.bookChapterDao.getChapterList(book.bookUrl).forEach { chapter ->
                emit(chapter)
            }
        }.mapAsync(threads) { chapter ->
            getExportData(book, chapter, contentProcessor, useReplace, config)
        }.collectIndexed { index, result ->
            postEvent(EventBus.EXPORT_BOOK, book.bookUrl)
            exportProgress[book.bookUrl] = index
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
        val content = BookHelp.getContent(book, chapter).withoutReadableContentVersionFlag()
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
            .let(ExportImageSanitizer::cleanSvgUrlOptionImages)
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
        val backgroundColor = config.epubBackgroundColor.normalizeCssColor("#FFFFFF")
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
        if (color.length == 9) "#${color.takeLast(6)}" else color.uppercase()
    } else {
        default
    }
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
