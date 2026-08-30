package io.legado.app.model.localBook

import android.net.Uri
import android.util.Base64
import androidx.documentfile.provider.DocumentFile
import com.script.ScriptBindings
import com.script.rhino.RhinoScriptEngine
import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.constant.AppPattern
import io.legado.app.constant.BookType
import io.legado.app.data.appDb
import io.legado.app.data.entities.BaseSource
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookIllustration
import io.legado.app.data.entities.Bookmark
import io.legado.app.data.entities.ReplaceRule
import io.legado.app.exception.EmptyFileException
import io.legado.app.help.illustration.IllustrationHelp
import io.legado.app.help.illustration.imageSrcsToJson
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.exception.NoBooksDirException
import io.legado.app.exception.NoStackTraceException
import io.legado.app.exception.TocEmptyException
import io.legado.app.help.AppWebDav
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.AudioBookArchive
import io.legado.app.help.book.AudioBookArchiveManifest
import io.legado.app.help.book.BookArchive
import io.legado.app.help.book.BookArchiveManifest
import io.legado.app.help.book.ContentProcessor
import io.legado.app.help.book.addType
import io.legado.app.help.book.archiveName
import io.legado.app.help.book.getArchiveUri
import io.legado.app.help.book.getLocalUri
import io.legado.app.help.book.getRemoteUrl
import io.legado.app.help.book.isArchive
import io.legado.app.help.book.isAudio
import io.legado.app.help.book.isEpub
import io.legado.app.help.book.isLocal
import io.legado.app.help.book.isMobi
import io.legado.app.help.book.isPdf
import io.legado.app.help.book.isUmd
import io.legado.app.help.book.removeLocalUriCache
import io.legado.app.help.book.removeType
import io.legado.app.help.book.simulatedTotalChapterNum
import io.legado.app.help.config.AppConfig
import io.legado.app.help.tts.TtsCacheArchive
import io.legado.app.help.tts.TtsCacheStore
import io.legado.app.lib.webdav.WebDav
import io.legado.app.lib.webdav.WebDavException
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.utils.ArchiveUtils
import io.legado.app.utils.FileDoc
import io.legado.app.utils.FileUtils
import io.legado.app.utils.GSON
import io.legado.app.utils.delete
import io.legado.app.utils.exists
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.externalFiles
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.getFile
import io.legado.app.utils.inputStream
import io.legado.app.utils.isAbsUrl
import io.legado.app.utils.isContentScheme
import io.legado.app.utils.isDataUrl
import io.legado.app.utils.printOnDebug
import kotlinx.coroutines.runBlocking
import org.apache.commons.text.StringEscapeUtils
import splitties.init.appCtx
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.InputStream
import java.util.regex.Pattern
import androidx.core.net.toUri
import kotlinx.coroutines.currentCoroutineContext

/**
 * 书籍文件导入 目录正文解析
 * 支持在线文件(txt epub umd 压缩文件 本地文件
 */
private class ReviewArchiveIntegrityException(cause: Throwable) :
    IllegalStateException(cause.message, cause)

object LocalBook {

    private const val LARGE_EPUB_FAST_IMPORT_BYTES = 100L * 1024L * 1024L

    private data class AudioArchiveChapterIdentity(
        val index: Int,
        val title: String,
    )

    private data class AudioArchiveImportMapping(
        val bookUrl: String,
        val chaptersBySourceUrl: Map<String, BookChapter>?,
        val legacyChapters: Map<AudioArchiveChapterIdentity, BookChapter>?,
    )

    private data class ImportedArchiveBook(
        val entryName: String,
        val book: Book,
    )

    private val nameAuthorPatterns = arrayOf(
        Pattern.compile("(.*?)《([^《》]+)》.*?作者：(.*)"),
        Pattern.compile("(.*?)《([^《》]+)》(.*)"),
        Pattern.compile("(^)(.+) 作者：(.+)$"),
        Pattern.compile("(^)(.+) by (.+)$")
    )

    @Throws(FileNotFoundException::class, SecurityException::class)
    fun getBookInputStream(book: Book): InputStream {
        val uri = book.getLocalUri()
        val inputStream = uri.inputStream(appCtx).getOrNull()
            ?: let {
                book.removeLocalUriCache()
                val localArchiveUri = book.getArchiveUri()
                val webDavUrl = book.getRemoteUrl()
                if (localArchiveUri != null) {
                    // 重新导入对应的压缩包
                    importArchiveFile(localArchiveUri, book.originName) {
                        it.contains(book.originName)
                    }.firstOrNull()?.let {
                        getBookInputStream(it)
                    }
                } else if (webDavUrl != null && downloadRemoteBook(book)) {
                    // 下载远程链接
                    getBookInputStream(book)
                } else {
                    null
                }
            }
        if (inputStream != null) return inputStream
        book.removeLocalUriCache()
        throw FileNotFoundException("${uri.path} 文件不存在")
    }

    fun getLastModified(book: Book): Result<Long> {
        return kotlin.runCatching {
            val uri = book.bookUrl.toUri()
            if (uri.isContentScheme()) {
                return@runCatching DocumentFile.fromSingleUri(appCtx, uri)!!.lastModified()
            }
            val file = File(uri.path!!)
            if (file.exists()) {
                return@runCatching file.lastModified()
            }
            throw FileNotFoundException("${uri.path} 文件不存在")
        }
    }

    @Throws(TocEmptyException::class)
    fun getChapterList(book: Book): ArrayList<BookChapter> {
        if (book.isAudio && book.isArchive) {
            val archivedChapters = appDb.bookChapterDao.getChapterList(book.bookUrl)
            check(archivedChapters.isNotEmpty()) {
                "Audio TXT-ZIP chapter manifest is missing: ${book.bookUrl}"
            }
            return ArrayList(archivedChapters)
        }
        val chapters = when {
            book.isEpub -> {
                EpubFile.getChapterList(book)
            }

            book.isUmd -> {
                UmdFile.getChapterList(book)
            }

            book.isPdf -> {
                PdfFile.getChapterList(book)
            }

            book.isMobi -> {
                MobiFile.getChapterList(book)
            }

            else -> {
                TextFile.getChapterList(book)
            }
        }
        if (chapters.isEmpty()) {
            throw TocEmptyException(appCtx.getString(R.string.chapter_list_empty))
        }
        val list = ArrayList(LinkedHashSet(chapters))
        list.forEachIndexed { index, bookChapter ->
            bookChapter.index = index
            if (bookChapter.title.isEmpty()) {
                bookChapter.title = "无标题章节"
            }
        }
        val replaceRules = ContentProcessor.get(book).getTitleReplaceRules()
        val replaceBook = book.toReplaceBook()
        book.durChapterTitle = list.getOrElse(book.durChapterIndex) { list.last() }
            .getDisplayTitle(
                replaceRules,
                book.getUseReplaceRule(),
                replaceBook = replaceBook
            )
        book.latestChapterTitle =
            list.getOrElse(book.simulatedTotalChapterNum() - 1) { list.last() }
                .getDisplayTitle(
                    replaceRules,
                    book.getUseReplaceRule(),
                    replaceBook = replaceBook
                )
        book.totalChapterNum = list.size
        book.latestChapterTime = System.currentTimeMillis()
        return list
    }

    fun getContent(book: Book, chapter: BookChapter): String? {
        var content = try {
            when {
                book.isEpub -> {
                    EpubFile.getContent(book, chapter)
                }

                book.isUmd -> {
                    UmdFile.getContent(book, chapter)
                }

                book.isPdf -> {
                    PdfFile.getContent(book, chapter)
                }

                book.isMobi -> {
                    MobiFile.getContent(book, chapter)
                }

                else -> {
                    TextFile.getContent(book, chapter)
                }
            }
        } catch (e: Exception) {
            e.printOnDebug()
            AppLog.put("获取本地书籍内容失败\n${e.localizedMessage}", e)
            "获取本地书籍内容失败\n${e.localizedMessage}"
        }
        if (book.isEpub) {
            content ?: return null
            if (content.indexOf('&') > -1) {
                content = content.replace("&lt;img", "&lt; img", true)
                return StringEscapeUtils.unescapeHtml4(content)
            }
        }

        if (content.isNullOrEmpty() && !chapter.isVolume) {
            return null
        }

        return content
    }

    fun getCoverPath(book: Book): String {
        return getCoverPath(book.bookUrl, "jpg")
    }

    fun getCoverPath(book: Book, extension: String): String {
        return getCoverPath(book.bookUrl, extension)
    }

    fun findCoverPath(book: Book): String? {
        return listOf("png", "jpg", "webp")
            .asSequence()
            .map { getCoverPath(book.bookUrl, it) }
            .firstOrNull { File(it).exists() }
    }

    fun resolveCoverPath(book: Book, extension: String): String {
        val current = book.coverUrl
        if (!current.isNullOrBlank() && !isManagedCoverPath(book, current)) {
            return current
        }
        return getCoverPath(book.bookUrl, extension)
    }

    private fun isManagedCoverPath(book: Book, path: String): Boolean {
        return listOf("png", "jpg", "webp").any { path == getCoverPath(book.bookUrl, it) }
    }

    private fun getCoverPath(bookUrl: String, extension: String): String {
        val safeExtension = extension.substringAfterLast('.').ifBlank { "jpg" }.lowercase()
        return FileUtils.getPath(
            appCtx.externalFiles,
            "covers",
            "${MD5Utils.md5Encode16(bookUrl)}.$safeExtension"
        )
    }

    /**
     * 下载在线的文件并自动导入到阅读（txt umd epub)
     */
    suspend fun importFileOnLine(
        str: String,
        fileName: String,
        source: BaseSource? = null,
    ): Book {
        return importFile(saveBookFile(str, fileName, source))
    }

    /**
     * 导入本地文件
     */
    fun importFile(uri: Uri, onStage: ((String) -> Unit)? = null): Book {
        //updateTime变量不要修改,否则会导致读取不到缓存
        onStage?.invoke("读取文件信息")
        val fileDoc = FileDoc.fromUri(uri, false)
        if (fileDoc.size == 0L) throw EmptyFileException("Unexpected empty File")
        val fileName = fileDoc.name
        val updateTime = fileDoc.lastModified
        val bookUrl = fileDoc.toString()
        val fileSize = fileDoc.size
        var book = appDb.bookDao.getBook(bookUrl)
        if (book == null) {
            onStage?.invoke("解析书籍信息")
            val nameAuthor = analyzeNameAuthor(fileName)
            book = Book(
                type = BookType.text or BookType.local,
                bookUrl = bookUrl,
                name = nameAuthor.first,
                author = nameAuthor.second,
                originName = fileName,
                latestChapterTime = updateTime,
                order = appDb.bookDao.minOrder - 1
            )
            upBookInfoSafely(book, fileSize)
            onStage?.invoke("保存书籍信息")
            appDb.bookDao.insert(book)
        } else {
            onStage?.invoke("更新书籍信息")
            deleteBook(book, false)
            upBookInfoSafely(book, fileSize)
            // 触发 isLocalModified
            book.latestChapterTime = 0
            //已有书籍说明是更新,删除原有目录
            appDb.bookChapterDao.delByBook(bookUrl)
        }
        if (book.isEpub) {
            Coroutine.async {
                restoreIllustrationsFromEpub(book)
                restoreBookmarksFromEpub(book)
            }
        }
        return book
    }

    /** 识别本应用导出的 EPUB 书签侧车（legado_bookmarks.json）并还原书签 */
    private fun restoreBookmarksFromEpub(book: Book) {
        kotlin.runCatching {
            val bookmarksText = readEpubBookmarks(book) ?: return
            val bookmarks = GSON.fromJsonArray<Bookmark>(
                ByteArrayInputStream(bookmarksText.toByteArray(Charsets.UTF_8))
            ).getOrNull()
            if (!bookmarks.isNullOrEmpty()) {
                appDb.bookmarkDao.insert(*bookmarks.toTypedArray())
            }
        }.onFailure { e ->
            AppLog.put("还原EPUB书签失败\n${e.localizedMessage}", e)
        }
    }

    private fun readEpubBookmarks(book: Book): String? {
        return kotlin.runCatching {
            val zip = BookHelp.getEpubFile(book)
            zip.use { z ->
                val entry = z.entries().asSequence()
                    .firstOrNull { it.name.endsWith(IllustrationHelp.EPUB_BOOKMARKS_NAME) }
                    ?: return@use null
                z.getInputStream(entry).use { it.readBytes() }.toString(Charsets.UTF_8)
            }
        }.getOrNull()
    }

    /**
     * 识别本应用导出的 EPUB 配图标记（div[data-ill-version]），
     * 提取图片到配图目录、重写缓存的图片 src、还原配图记录。
     */
    private fun restoreIllustrationsFromEpub(book: Book) {
        kotlin.runCatching {
            val sidecarText = readEpubSidecar(book) ?: return
            val sidecar = kotlin.runCatching {
                GSON.fromJson(sidecarText, IllustrationHelp.EpubIllustrationJson::class.java)
            }.getOrNull() ?: return
            if (sidecar.records.isEmpty()) return
            val chapterList = appDb.bookChapterDao.getChapterList(book.bookUrl)
                .ifEmpty { EpubFile.getChapterList(book) }
            val records = arrayListOf<BookIllustration>()
            val recordsByChapter = sidecar.records.groupBy { it.chapterIndex }
            chapterList.forEach { chapter ->
                val items = recordsByChapter[chapter.index] ?: return@forEach
                val content = EpubFile.getContent(book, chapter) ?: return@forEach
                var newContent = content
                var contentChanged = false
                val chapterRecords = arrayListOf<BookIllustration>()
                items.sortedBy { it.sortOrder }.forEachIndexed { index, item ->
                    val srcs = arrayListOf<String>()
                    item.srcs.forEach { src ->
                        val bytes = extractEpubImageBytes(
                            book,
                            IllustrationHelp.epubImageHrefWithParent(src),
                            IllustrationHelp.epubImageHref(src)
                        )
                        if (bytes != null) {
                            IllustrationHelp.saveImage(book, src, bytes)
                            // 正文中 `<img>` 指向的可能是媒体原 href，也可能是视频首帧（同名 .jpg）。
                            // 导出的标记是 `<img src="../Images/xxx" />`（`/>` 前带空格），替换时都要兼容。
                            val candidates = listOf(
                                IllustrationHelp.epubImageHrefWithParent(src),
                                IllustrationHelp.epubImageHref(src),
                                IllustrationHelp.epubVideoFrameHrefWithParent(src),
                                IllustrationHelp.epubVideoFrameHref(src)
                            )
                            candidates.forEach { hrefWithParent ->
                                newContent = newContent.replace(
                                    """<img src="$hrefWithParent">""",
                                    """<img src="$src">"""
                                )
                                newContent = newContent.replace(
                                    """<img src="$hrefWithParent"/>""",
                                    """<img src="$src"/>"""
                                )
                                newContent = newContent.replace(
                                    """<img src="$hrefWithParent" />""",
                                    """<img src="$src" />"""
                                )
                            }
                            contentChanged = contentChanged || newContent != content
                            srcs.add(src)
                        }
                    }
                    if (srcs.isEmpty()) return@forEach
                    chapterRecords.add(
                        BookIllustration(
                            bookUrl = book.bookUrl,
                            chapterIndex = chapter.index,
                            chapterUrl = chapter.url,
                            chapterName = item.chapterName.ifBlank { chapter.title },
                            anchorType = item.anchorType,
                            anchorPos = item.anchorPos,
                            frontParagraphText = item.frontParagraphText,
                            backParagraphText = item.backParagraphText,
                            frontFingerprint = item.frontFingerprint,
                            backFingerprint = item.backFingerprint,
                            imageSrcs = imageSrcsToJson(srcs),
                            layoutType = item.layoutType,
                            displayHeight = item.displayHeight,
                            pageBreak = item.pageBreak,
                            sortOrder = index
                        )
                    )
                }
                if (chapterRecords.isNotEmpty()) {
                    records.addAll(chapterRecords)
                    if (contentChanged) {
                        BookHelp.saveText(book, chapter, newContent)
                    }
                }
            }
            if (records.isNotEmpty()) {
                appDb.bookIllustrationDao.deleteByBook(book.bookUrl)
                appDb.bookIllustrationDao.insert(*records.toTypedArray())
            }
        }.onFailure { e ->
            AppLog.put("还原EPUB配图失败\n${e.localizedMessage}", e)
        }
    }

    private fun readEpubSidecar(book: Book): String? {
        return kotlin.runCatching {
            val zip = BookHelp.getEpubFile(book)
            zip.use { z ->
                val entry = z.entries().asSequence()
                    .firstOrNull { it.name.endsWith(IllustrationHelp.EPUB_SIDECAR_NAME) }
                    ?: return@use null
                z.getInputStream(entry).use { it.readBytes() }.toString(Charsets.UTF_8)
            }
        }.getOrNull()
    }

    private fun extractEpubImageBytes(
        book: Book,
        htmlSrc: String,
        altSrc: String
    ): ByteArray? {
        val candidates = listOf(htmlSrc, altSrc)
        candidates.forEach { src ->
            val bytes = kotlin.runCatching {
                EpubFile.getImage(book, src)?.use { it.readBytes() }
            }.getOrNull()
            if (bytes != null && bytes.isNotEmpty()) return bytes
        }
        return null
    }

    fun upBookInfo(book: Book) {
        when {
            book.isEpub -> EpubFile.upBookInfo(book)
            book.isUmd -> UmdFile.upBookInfo(book)
            book.isPdf -> PdfFile.upBookInfo(book)
            book.isMobi -> MobiFile.upBookInfo(book)
        }
    }

    private fun upBookInfoSafely(book: Book, fileSize: Long) {
        if (book.isEpub && shouldDeferEpubBookInfo(fileSize)) {
            if (book.name.isBlank()) {
                book.name = book.originName.substringBeforeLast(".")
            }
            if (book.intro.isNullOrBlank()) {
                book.intro = "大体积 EPUB 已快速导入，封面和简介将在阅读时按需加载。"
            }
            return
        }
        if (!book.isEpub) {
            upBookInfo(book)
            return
        }
        kotlin.runCatching {
            upBookInfo(book)
        }.onFailure {
            AppLog.put("EPUB 元数据解析失败，已先导入书籍\n${it.localizedMessage}", it)
            if (book.name.isBlank()) {
                book.name = book.originName.substringBeforeLast(".")
            }
            if (book.intro.isNullOrBlank()) {
                book.intro = "EPUB 已导入，元数据将在阅读时按需加载。"
            }
        }
    }

    private fun shouldDeferEpubBookInfo(fileSize: Long): Boolean {
        return fileSize >= LARGE_EPUB_FAST_IMPORT_BYTES
    }

    /* 导入压缩包内的书籍 */
    fun importArchiveFile(
        archiveFileUri: Uri,
        saveFileName: String? = null,
        filter: ((String) -> Boolean)? = null
    ): List<Book> {
        val archiveFileDoc = FileDoc.fromUri(archiveFileUri, false)
        val bookManifest = readBookArchiveManifest(archiveFileDoc)
        val audioManifest = readAudioBookArchiveManifest(archiveFileDoc)
        val files = ArchiveUtils.deCompress(archiveFileDoc, filter = filter)
        if (files.isEmpty()) {
            throw NoStackTraceException(appCtx.getString(R.string.unsupport_archivefile_entry))
        }
        val importedArchiveBooks = files.map { extractedFile ->
            val book = saveBookFile(
                FileInputStream(extractedFile),
                saveFileName ?: extractedFile.name,
            ).let { uri ->
                importFile(uri).apply {
                    //附加压缩包名称 以便解压文件被删后再解压
                    // Keep both a stable display name and the original URI. The URI is
                    // required for delete-original-file to target the archive, not its
                    // extracted TXT child.
                    origin = "${BookType.localTag}::${archiveFileDoc.name}::${archiveFileDoc.uri}"
                    addType(BookType.archive)
                    save()
                }
            }
            ImportedArchiveBook(extractedFile.name, book)
        }
        val books = importedArchiveBooks.map { it.book }
        val audioChapterMapping = audioManifest?.let { manifest ->
            restoreAudioBookFromArchive(archiveFileDoc, importedArchiveBooks, manifest)
        }
        bookManifest?.let { manifest ->
            restoreBookCoverFromArchive(archiveFileDoc, importedArchiveBooks, manifest)
        }
        restoreIllustrationsFromArchive(archiveFileDoc, books, audioChapterMapping)
        restoreTtsCacheFromArchive(archiveFileDoc, importedArchiveBooks, audioChapterMapping)
        return books
    }

    private fun readBookArchiveManifest(
        archiveFileDoc: FileDoc,
    ): BookArchiveManifest? {
        val manifestEntries = ArchiveUtils.getArchiveFilesName(archiveFileDoc) { entryName ->
            entryName.replace('\\', '/').removePrefix("./") == BookArchive.MANIFEST_FILE_NAME
        }
        if (manifestEntries.isEmpty()) return null
        require(manifestEntries.size == 1) {
            "TXT-ZIP import failed: expected one ${BookArchive.MANIFEST_FILE_NAME}"
        }
        val manifestFiles = ArchiveUtils.deCompress(archiveFileDoc) { entryName ->
            entryName.replace('\\', '/').removePrefix("./") == BookArchive.MANIFEST_FILE_NAME
        }
        val manifestFile = manifestFiles.singleOrNull {
            it.name == BookArchive.MANIFEST_FILE_NAME
        } ?: error("TXT-ZIP import failed: book manifest was not extracted")
        val manifest = GSON.fromJsonObject<BookArchiveManifest>(manifestFile.readText())
            .getOrThrow()
        require(manifest.version in BookArchive.MIN_SUPPORTED_VERSION..BookArchive.VERSION) {
            "TXT-ZIP import failed: unsupported book manifest version ${manifest.version}"
        }
        validateArchiveTextFile(manifest.textFile, "TXT-ZIP import failed")
        manifest.coverFile?.let { coverFile ->
            require(coverFile == BookArchive.COVER_FILE_NAME) {
                "TXT-ZIP import failed: invalid cover path $coverFile"
            }
        }
        return manifest
    }

    private fun restoreBookCoverFromArchive(
        archiveFileDoc: FileDoc,
        importedBooks: List<ImportedArchiveBook>,
        manifest: BookArchiveManifest,
    ) {
        val book = importedBooks.singleOrNull { it.entryName == manifest.textFile }?.book
            ?: error(
                "TXT-ZIP import failed: expected exactly one imported text file " +
                    manifest.textFile
            )
        val coverPath = manifest.coverFile ?: return
        val coverEntries = ArchiveUtils.getArchiveFilesName(archiveFileDoc) { entryName ->
            entryName.replace('\\', '/').removePrefix("./") == coverPath
        }
        require(coverEntries.size == 1) {
            "TXT-ZIP import failed: expected one cover file $coverPath"
        }
        val extractedFiles = ArchiveUtils.deCompress(archiveFileDoc) { entryName ->
            entryName.replace('\\', '/').removePrefix("./") == coverPath
        }
        val source = extractedFiles.singleOrNull { it.isFile && it.name == coverPath }
            ?: error("TXT-ZIP import failed: cover file $coverPath was not extracted")
        require(source.length() > 0L) {
            "TXT-ZIP import failed: cover file $coverPath is empty"
        }
        val target = BookArchive.persistentCoverFile(book)
        val staging = File(
            target.parentFile,
            ".${BookArchive.COVER_FILE_NAME}.${System.currentTimeMillis()}.tmp",
        )
        target.parentFile?.mkdirs()
        try {
            source.copyTo(staging, overwrite = true)
            require(staging.isFile && staging.length() == source.length()) {
                "TXT-ZIP import failed: cover copy is incomplete"
            }
            FileUtils.delete(target)
            require(staging.renameTo(target)) {
                "TXT-ZIP import failed: cannot install cover"
            }
            book.coverUrl = target.absolutePath
            book.customCoverUrl = null
            appDb.bookDao.update(book)
        } finally {
            if (staging.exists()) FileUtils.delete(staging)
        }
    }

    private fun readAudioBookArchiveManifest(
        archiveFileDoc: FileDoc,
    ): AudioBookArchiveManifest? {
        val manifestEntries = ArchiveUtils.getArchiveFilesName(archiveFileDoc) { entryName ->
            entryName.replace('\\', '/').removePrefix("./") == AudioBookArchive.MANIFEST_FILE_NAME
        }
        if (manifestEntries.isEmpty()) return null
        require(manifestEntries.size == 1) {
            "Audio TXT-ZIP import failed: expected one ${AudioBookArchive.MANIFEST_FILE_NAME}"
        }
        val manifestFiles = ArchiveUtils.deCompress(archiveFileDoc) { entryName ->
            entryName.replace('\\', '/').removePrefix("./") == AudioBookArchive.MANIFEST_FILE_NAME
        }
        val manifestFile = manifestFiles.singleOrNull { it.name == AudioBookArchive.MANIFEST_FILE_NAME }
            ?: error("Audio TXT-ZIP import failed: manifest was not extracted")
        val manifest = GSON.fromJsonObject<AudioBookArchiveManifest>(manifestFile.readText())
            .getOrThrow()
        require(manifest.version in AudioBookArchive.MIN_SUPPORTED_VERSION..AudioBookArchive.VERSION) {
            "Audio TXT-ZIP import failed: unsupported manifest version ${manifest.version}"
        }
        validateArchiveTextFile(manifest.textFile, "Audio TXT-ZIP import failed")
        require(manifest.name.isNotBlank()) {
            "Audio TXT-ZIP import failed: book name is empty"
        }
        require(manifest.chapters.isNotEmpty()) {
            "Audio TXT-ZIP import failed: chapter mapping is empty"
        }
        require(manifest.chapters.map { it.index }.distinct().size == manifest.chapters.size) {
            "Audio TXT-ZIP import failed: duplicate chapter index"
        }
        val sourceChapterUrls = manifest.chapters.mapNotNull { it.sourceChapterUrl }
        if (manifest.version >= 2) {
            require(sourceChapterUrls.size == manifest.chapters.size) {
                "Audio TXT-ZIP import failed: incomplete source chapter identities"
            }
            require(sourceChapterUrls.none { it.isBlank() }) {
                "Audio TXT-ZIP import failed: blank source chapter identity"
            }
            require(sourceChapterUrls.distinct().size == sourceChapterUrls.size) {
                "Audio TXT-ZIP import failed: duplicate source chapter identity"
            }
        } else {
            require(sourceChapterUrls.isEmpty()) {
                "Audio TXT-ZIP import failed: version 1 contains version 2 chapter identities"
            }
        }
        return manifest
    }

    private fun restoreAudioBookFromArchive(
        archiveFileDoc: FileDoc,
        importedBooks: List<ImportedArchiveBook>,
        manifest: AudioBookArchiveManifest,
    ): AudioArchiveImportMapping {
        val book = importedBooks.singleOrNull { it.entryName == manifest.textFile }?.book
            ?: error(
                "Audio TXT-ZIP import failed: expected exactly one imported text file " +
                    manifest.textFile
            )
        val mediaPaths = manifest.chapters.flatMap { chapter ->
            require(chapter.title.isNotBlank()) {
                "Audio TXT-ZIP import failed: chapter ${chapter.index + 1} has no title"
            }
            require(chapter.mediaFiles.isNotEmpty()) {
                "Audio TXT-ZIP import failed: chapter ${chapter.index + 1} has no audio"
            }
            chapter.mediaFiles
        }
        require(mediaPaths.distinct().size == mediaPaths.size) {
            "Audio TXT-ZIP import failed: duplicate audio file mapping"
        }
        mediaPaths.forEach { path ->
            val normalized = path.replace('\\', '/').removePrefix("./")
            require(
                normalized == path &&
                    normalized.startsWith("${AudioBookArchive.MEDIA_DIR_NAME}/") &&
                    normalized.count { it == '/' } == 1 &&
                    !normalized.contains("../") &&
                    normalized.substringAfterLast('/').isNotBlank()
            ) {
                "Audio TXT-ZIP import failed: invalid audio path $path"
            }
        }
        val expectedPaths = mediaPaths.toSet()
        val extractedMedia = ArchiveUtils.deCompress(archiveFileDoc) { entryName ->
            entryName.replace('\\', '/').removePrefix("./") in expectedPaths
        }
        val extractedByName = extractedMedia
            .filter { it.isFile }
            .associateBy { it.name }
        require(extractedByName.size == expectedPaths.size) {
            "Audio TXT-ZIP import failed: expected ${expectedPaths.size} audio files, found ${extractedByName.size}"
        }
        expectedPaths.forEach { path ->
            val file = extractedByName[path.substringAfterLast('/')]
                ?: error("Audio TXT-ZIP import failed: missing $path")
            require(file.length() > 0L) { "Audio TXT-ZIP import failed: empty $path" }
        }

        val chapterAssignments = manifest.chapters.mapIndexed { order, archivedChapter ->
            val localChapter = BookChapter(
                url = AudioBookArchive.importedChapterUrl(book.bookUrl, order),
                title = archivedChapter.title,
                baseUrl = book.bookUrl,
                bookUrl = book.bookUrl,
                index = order,
                variable = archivedChapter.variable,
                start = archivedChapter.start,
                end = archivedChapter.end,
            )
            require(localChapter.getVariable("lyric").isNotBlank()) {
                "Audio TXT-ZIP import failed: chapter ${archivedChapter.index + 1} has no lyric"
            }
            archivedChapter to localChapter
        }

        book.name = manifest.name
        book.author = manifest.author
        book.intro = manifest.intro
        book.removeType(BookType.text)
        book.addType(BookType.audio, BookType.local, BookType.archive)
        book.syncMediaType()
        book.canUpdate = false
        val targetDir = AudioBookArchive.persistentMediaDir(book)
        val stagingDir = File(
            targetDir.parentFile,
            ".audio_archive_import_${System.currentTimeMillis()}"
        )
        FileUtils.delete(stagingDir, deleteRootDir = true)
        stagingDir.mkdirs()
        try {
            expectedPaths.forEach { path ->
                val source = requireNotNull(extractedByName[path.substringAfterLast('/')])
                source.copyTo(File(stagingDir, source.name), overwrite = true)
            }
            FileUtils.delete(targetDir, deleteRootDir = true)
            require(stagingDir.renameTo(targetDir)) {
                "Audio TXT-ZIP import failed: cannot install extracted audio"
            }
            chapterAssignments.forEach { (archivedChapter, localChapter) ->
                val localMediaUrls = archivedChapter.mediaFiles.map { path ->
                    Uri.fromFile(File(targetDir, path.substringAfterLast('/'))).toString()
                }
                localChapter.resourceUrl = if (localMediaUrls.size == 1) {
                    localMediaUrls.first()
                } else {
                    GSON.toJson(localMediaUrls)
                }
            }
            val localChapters = chapterAssignments.map { it.second }
            book.totalChapterNum = localChapters.size
            book.durChapterIndex = book.durChapterIndex.coerceIn(0, localChapters.lastIndex)
            book.durChapterTitle = localChapters[book.durChapterIndex].title
            book.latestChapterTitle = localChapters.last().title
            appDb.runInTransaction {
                appDb.bookChapterDao.delByBook(book.bookUrl)
                appDb.bookChapterDao.insert(*localChapters.toTypedArray())
                appDb.bookDao.update(book)
            }
            return AudioArchiveImportMapping(
                bookUrl = book.bookUrl,
                chaptersBySourceUrl = manifest.chapters.first().sourceChapterUrl?.let {
                    chapterAssignments.associate { (archivedChapter, localChapter) ->
                        requireNotNull(archivedChapter.sourceChapterUrl) to localChapter
                    }
                },
                legacyChapters = manifest.chapters.first().sourceChapterUrl?.let { null }
                    ?: chapterAssignments.associate { (archivedChapter, localChapter) ->
                        AudioArchiveChapterIdentity(
                            archivedChapter.index,
                            archivedChapter.title,
                        ) to localChapter
                    },
            )
        } finally {
            if (stagingDir.exists()) FileUtils.delete(stagingDir, deleteRootDir = true)
        }
    }

    /**
     * 识别配图压缩包（book.txt + images/ + illustrations.json），还原配图记录。
     */
    private fun restoreIllustrationsFromArchive(
        archiveFileDoc: FileDoc,
        importedBooks: List<Book>,
        audioChapterMapping: AudioArchiveImportMapping? = null,
    ) {
        kotlin.runCatching {
            val files = ArchiveUtils.deCompress(
                archiveFileDoc,
                filter = { name ->
                    name == IllustrationHelp.EXPORT_JSON_NAME ||
                        name == IllustrationHelp.EXPORT_BOOKMARKS_NAME ||
                        name == IllustrationHelp.EXPORT_REPLACE_RULES_NAME ||
                        name.startsWith("${IllustrationHelp.EXPORT_IMAGES_DIR}/") ||
                        name.startsWith("reviews/") ||
                        name.matches(AppPattern.bookFileRegex)
                }
            )
            // 压缩包内含书签文件时同步导入书签（bookName/bookAuthor 关联到书）
            files.firstOrNull { it.name == IllustrationHelp.EXPORT_BOOKMARKS_NAME }?.let { bookmarkFile ->
                kotlin.runCatching {
                    val bookmarks = GSON.fromJsonArray<Bookmark>(bookmarkFile.inputStream()).getOrNull()
                    if (!bookmarks.isNullOrEmpty()) {
                        appDb.bookmarkDao.insert(*bookmarks.toTypedArray())
                    }
                }.onFailure { e ->
                    AppLog.put("导入书签失败\n${e.localizedMessage}", e)
                }
            }
            // 压缩包内含替换规则时同步导入规则，并把该书的净化开关打开：
            // 原文 + 规则同步还原，规则不作用于导出的 txt 本身
            files.firstOrNull { it.name == IllustrationHelp.EXPORT_REPLACE_RULES_NAME }?.let { rulesFile ->
                kotlin.runCatching {
                    val rules = GSON.fromJsonArray<ReplaceRule>(rulesFile.inputStream()).getOrNull()
                    if (!rules.isNullOrEmpty()) {
                        appDb.replaceRuleDao.insert(*rules.toTypedArray())
                        importedBooks.firstOrNull()?.let { book ->
                            if (book.config.useReplaceRule != true) {
                                book.config.useReplaceRule = true
                                book.save()
                            }
                        }
                        ContentProcessor.upReplaceRules()
                    }
                }.onFailure { e ->
                    AppLog.put("导入替换规则失败\n${e.localizedMessage}", e)
                }
            }
            // 评论页快照：reviews/r_*.json 还原进该书缓存目录，之后点击评论按钮即可离线打开。
            // 音频归档使用 Manifest 建立的确定性章节映射；普通 TXT 仍按解析后的本地目录
            // 匹配。两者最终都把在线 chapterUrl remap 为本地章节 URL。
            // 注意放在 illustrations.json 存在性判断之前：无配图的书可能只有评论快照
            val importedBook = audioChapterMapping?.let { mapping ->
                importedBooks.singleOrNull { it.bookUrl == mapping.bookUrl }
                    ?: error("Audio TXT-ZIP import failed: imported audio book is missing")
            } ?: importedBooks.firstOrNull()
            if (importedBook != null) {
                // 刚导入的普通 TXT 只创建了 Book，目录（BookChapter）尚未解析入库；
                // 音频归档章节已由 Manifest 直接写入，不再解析 TXT 猜测章节身份。
                if (audioChapterMapping == null) {
                    ensureChapterListForImport(importedBook)
                }
                val localChapters = appDb.bookChapterDao.getChapterList(importedBook.bookUrl)
                // Validate and restore the resource library before accepting any
                // snapshot payload. A reviews/ archive without resources.json is
                // the retired non-resource format and must not be partially imported.
                try {
                    io.legado.app.help.review.ReviewSnapshotResourceStore.importFrom(
                        importedBook,
                        files
                    )
                    files.filter(io.legado.app.help.review.ReviewSnapshotStore::isSnapshotFile)
                        .forEach { snapshotFile ->
                            io.legado.app.help.review.ReviewSnapshotStore
                                .validateImportedSnapshot(importedBook, snapshotFile)
                        }
                } catch (error: Throwable) {
                    throw ReviewArchiveIntegrityException(error)
                }
                files.filter(io.legado.app.help.review.ReviewSnapshotStore::isSnapshotFile)
                    .forEach { snapshotFile ->
                        try {
                            val snapshot =
                                GSON.fromJsonObject<io.legado.app.help.review.ReviewSnapshot>(
                                    snapshotFile.readText()
                            ).getOrNull() ?: error("invalid review snapshot JSON: ${snapshotFile.name}")
                            matchImportedChapter(
                                importedBook,
                                localChapters,
                                snapshot.chapterUrl,
                                snapshot.chapterIndex,
                                snapshot.chapterTitle,
                                audioChapterMapping,
                            )?.let { localChapter ->
                                val remapped = snapshot.copy(
                                    bookUrl = importedBook.bookUrl,
                                    chapterUrl = localChapter.url,
                                    chapterIndex = localChapter.index,
                                    chapterTitle = localChapter.title
                                )
                                io.legado.app.help.review.ReviewSnapshotStore.put(
                                    importedBook, remapped
                                )
                            } ?: error("review snapshot chapter mapping is ambiguous: ${snapshotFile.name}")
                        } catch (error: Throwable) {
                            throw ReviewArchiveIntegrityException(error)
                        }
                    }
                files.filter(io.legado.app.help.review.ReviewSnapshotStore::isChapterStatusFile)
                    .forEach { statusFile ->
                        try {
                            val status = io.legado.app.help.review.ReviewSnapshotStore
                                .readChapterStatus(statusFile)
                                ?: error("invalid review chapter status: ${statusFile.name}")
                            matchImportedChapter(
                                importedBook,
                                localChapters,
                                status.chapterUrl,
                                status.chapterIndex,
                                status.chapterTitle,
                                audioChapterMapping,
                            )?.let { localChapter ->
                                io.legado.app.help.review.ReviewSnapshotStore.putChapterStatus(
                                    importedBook,
                                    status.copy(
                                        bookUrl = importedBook.bookUrl,
                                        chapterUrl = localChapter.url,
                                        chapterIndex = localChapter.index,
                                        chapterTitle = localChapter.title
                                    )
                                )
                            } ?: error("review status chapter mapping is ambiguous: ${statusFile.name}")
                        } catch (error: Throwable) {
                            throw ReviewArchiveIntegrityException(error)
                        }
                    }
            }
            val jsonFile = files.firstOrNull { it.name == IllustrationHelp.EXPORT_JSON_NAME }
                ?: return
            val json = kotlin.runCatching {
                GSON.fromJson(
                    jsonFile.readText(),
                    IllustrationHelp.IllustrationJson::class.java
                )
            }.getOrNull() ?: return
            val book = importedBooks.firstOrNull { it.originName == json.bookFile }
                ?: importedBooks.firstOrNull()
            if (book != null) {
                val jsonText = jsonFile.readText()
                val restored = IllustrationHelp.restoreFromExport(book, jsonText, files)
                if (!restored && book.isPdf) {
                    IllustrationHelp.restoreFromPdfExport(book, book, jsonText, files)
                }
            }
        }.onFailure { e ->
            if (e is ReviewArchiveIntegrityException) throw e.cause ?: e
            AppLog.put("还原配图数据失败\n${e.localizedMessage}", e)
        }
    }

    /**
     * 识别 TTS 音频缓存归档（tts_cache/ + tts_cache_manifest.json），按清单重算
     * 缓存 key 落位到导入书的缓存目录。章节身份用与评论快照一致的本地章节匹配
     * 策略（宁可漏迁也不绑错章）；单元落位全程 key 寻址，映射偏差只会 miss 后
     * 重新合成，不会错播。
     */
    private fun restoreTtsCacheFromArchive(
        archiveFileDoc: FileDoc,
        importedBooks: List<ImportedArchiveBook>,
        audioChapterMapping: AudioArchiveImportMapping?,
    ) {
        kotlin.runCatching {
            val manifestEntryName = TtsCacheArchive.MANIFEST_FILE_NAME
            val cacheDirName = "${TtsCacheStore.DIR_NAME}/"
            fun isArchiveEntry(entryName: String): Boolean {
                val name = entryName.replace('\\', '/').removePrefix("./")
                return name == manifestEntryName || name.startsWith(cacheDirName)
            }
            if (ArchiveUtils.getArchiveFilesName(archiveFileDoc) { isArchiveEntry(it) }
                    .isEmpty()
            ) {
                return
            }
            val extracted = ArchiveUtils.deCompress(archiveFileDoc) { isArchiveEntry(it) }
            val manifestFile = extracted.firstOrNull { it.name == manifestEntryName } ?: return
            val manifest = GSON.fromJsonObject<TtsCacheArchive.Manifest>(manifestFile.readText())
                .getOrNull() ?: return
            val importedBook = audioChapterMapping?.let { mapping ->
                importedBooks.singleOrNull { it.book.bookUrl == mapping.bookUrl }
            } ?: importedBooks.firstOrNull() ?: return
            val archiveRootDir = manifestFile.parentFile ?: return
            val localChapters = appDb.bookChapterDao.getChapterList(importedBook.book.bookUrl)
            val report = TtsCacheArchive.restore(
                importedBook.book,
                manifest,
                archiveRootDir,
            ) { index, title ->
                matchLocalChapter(localChapters, index, title)
            }
            AppLog.put("导入 TTS 音频缓存（${importedBook.book.name}）：$report")
        }.onFailure { e ->
            AppLog.put("导入 TTS 音频缓存失败\n${e.localizedMessage}", e)
        }
    }

    private fun matchImportedChapter(
        importedBook: Book,
        localChapters: List<BookChapter>,
        sourceChapterUrl: String,
        chapterIndex: Int,
        chapterTitle: String,
        audioChapterMapping: AudioArchiveImportMapping?,
    ): BookChapter? {
        if (audioChapterMapping?.bookUrl == importedBook.bookUrl) {
            return audioChapterMapping.chaptersBySourceUrl?.get(sourceChapterUrl)
                ?: audioChapterMapping.legacyChapters?.get(
                    AudioArchiveChapterIdentity(chapterIndex, chapterTitle)
                )
        }
        return matchLocalChapter(localChapters, chapterIndex, chapterTitle)
    }

    /**
     * 匹配评论快照对应的本地章节。
     *
     * 不能直接 index 优先：TXT 导出再导入后可能因书名/作者/简介生成“前言”，
     * 导致本地章节整体 index 后移，index 盲绑会把评论绑到错误章节。
     *
     * 匹配顺序：
     * 1. index 相同 且 title 相同（最可靠）；
     * 2. title 精确匹配：唯一 → 采用；
     * 3. title 同名多个 → 结合原 index 取距离最近的本地章节，且距离必须唯一；
     * 4. 仍无法唯一确认 → 返回 null（跳过并记日志，宁可漏恢复也不乱绑定）。
     */
    private fun matchLocalChapter(
        localChapters: List<BookChapter>,
        chapterIndex: Int,
        chapterTitle: String
    ): BookChapter? {
        if (chapterTitle.isBlank()) return null
        // 1. index + title 双匹配
        localChapters.firstOrNull {
            it.index == chapterIndex && it.title == chapterTitle
        }?.let { return it }
        // 2/3. title 匹配候选
        val candidates = localChapters.filter { it.title == chapterTitle }
        if (candidates.isEmpty()) return null
        if (candidates.size == 1) return candidates.first()
        // 同名多个：取与原 index 距离最近且唯一
        val nearest = candidates.minByOrNull {
            kotlin.math.abs(it.index - chapterIndex)
        } ?: return null
        val hasTie = candidates.any {
            it !== nearest &&
                kotlin.math.abs(it.index - chapterIndex) ==
                kotlin.math.abs(nearest.index - chapterIndex)
        }
        return if (hasTie) null else nearest
    }

    /**
     * 导入评论快照前确保本地书目录已解析入库：
     * 普通 TXT 导入后章节要由 [getChapterList]（TextFile 分章）生成，
     * 快照 remap 依赖本地章节列表，因此先补齐数据库章节。
     */
    private fun ensureChapterListForImport(book: Book) {
        if (!book.isLocal) return
        if (appDb.bookChapterDao.getChapterCount(book.bookUrl) > 0) return
        val chapters = runCatching { getChapterList(book) }.getOrNull() ?: return
        if (chapters.isEmpty()) return
        appDb.bookChapterDao.delByBook(book.bookUrl)
        appDb.bookChapterDao.insert(*chapters.toTypedArray())
        appDb.bookDao.update(book)
    }

    /* 批量导入 支持自动导入压缩包的支持书籍 */
    fun importFiles(uri: Uri, onStage: ((String) -> Unit)? = null): List<Book> {
        val books = mutableListOf<Book>()
        onStage?.invoke("读取文件信息")
        val fileDoc = FileDoc.fromUri(uri, false)
        if (ArchiveUtils.isArchive(fileDoc.name)) {
            onStage?.invoke("解压压缩包")
            books.addAll(
                importArchiveFile(uri) {
                    it.matches(AppPattern.bookFileRegex)
                }
            )
        } else {
            books.add(importFile(uri, onStage))
        }
        return books
    }

    fun importFiles(uris: List<Uri>) {
        var errorCount = 0
        uris.forEach { uri ->
            val fileDoc = FileDoc.fromUri(uri, false)
            kotlin.runCatching {
                if (ArchiveUtils.isArchive(fileDoc.name)) {
                    importArchiveFile(uri) {
                        it.matches(AppPattern.bookFileRegex)
                    }
                } else {
                    importFile(uri)
                }
            }.onFailure {
                AppLog.put("ImportFile Error:\nFile $fileDoc\n${it.localizedMessage}", it)
                errorCount += 1
            }
        }
        if (errorCount == uris.size) {
            throw NoStackTraceException("ImportFiles Error:\nAll input files occur error")
        }
    }

    fun prepareImportedBookCache(
        book: Book,
        onProgress: (stage: String, processed: Int, total: Int, title: String) -> Unit = { _, _, _, _ -> }
    ) {
        if (!book.isEpub) return
        onProgress("toc", 0, 1, book.name)
        val chapterList = getChapterList(book)
        if (chapterList.isEmpty()) return
        appDb.bookChapterDao.delByBook(book.bookUrl)
        appDb.bookChapterDao.insert(*chapterList.toTypedArray())
        appDb.bookDao.update(book)
        onProgress("toc", 1, 1, book.name)
    }

    /**
     * 从文件分析书籍必要信息（书名 作者等）
     */
    private fun analyzeNameAuthor(fileName: String): Pair<String, String> {
        val tempFileName = fileName.substringBeforeLast(".")
        var name = ""
        var author = ""
        if (!AppConfig.bookImportFileName.isNullOrBlank()) {
            try {
                //在用户脚本后添加捕获author、name的代码，只要脚本中author、name有值就会被捕获
                val js =
                    AppConfig.bookImportFileName + "\nJSON.stringify({author:author,name:name})"
                //在脚本中定义如何分解文件名成书名、作者名
                val jsonStr = RhinoScriptEngine.run {
                    val bindings = ScriptBindings()
                    bindings["src"] = tempFileName
                    eval(js, bindings)
                }.toString()
                val bookMess = GSON.fromJsonObject<HashMap<String, String>>(jsonStr)
                    .getOrThrow()
                name = bookMess["name"] ?: ""
                author = bookMess["author"]?.takeIf { it.length != tempFileName.length } ?: ""
            } catch (e: Exception) {
                AppLog.put("执行导入文件名规则出错\n${e.localizedMessage}", e)
            }
        }
        if (name.isBlank()) {
            for (pattern in nameAuthorPatterns) {
                pattern.matcher(tempFileName).takeIf { it.find() }?.run {
                    name = group(2)!!
                    val group1 = group(1) ?: ""
                    val group3 = group(3) ?: ""
                    author = BookHelp.formatBookAuthor(group1 + group3)
                    return Pair(name, author)
                }
            }
            name = BookHelp.formatBookName(tempFileName)
            author = BookHelp.formatBookAuthor(tempFileName.replace(name, ""))
                .takeIf { it.length != tempFileName.length } ?: ""
        }
        return Pair(name, author)
    }

    fun deleteBook(book: Book, deleteOriginal: Boolean) {
        clearBookShelfCache(book)
        deletePersistentBookResources(book)
        if (deleteOriginal) {
            if (book.isArchive) {
                val archiveFile = book.getArchiveUri()?.let { FileDoc.fromUri(it, false) }
                    ?: error("删除原文件失败：找不到压缩包 ${book.archiveName}")
                archiveFile.delete()
                check(!archiveFile.exists()) {
                    "删除原文件失败：压缩包仍存在 ${book.archiveName}"
                }
            }
            if (book.bookUrl.isContentScheme()) {
                val uri = book.bookUrl.toUri()
                DocumentFile.fromSingleUri(appCtx, uri)?.delete()
            } else {
                FileUtils.delete(book.bookUrl)
            }
        }
    }

    fun clearBookShelfCache(book: Book) {
        kotlin.runCatching {
            BookHelp.deleteCache(book)
            clearManagedCoverCache(book)
            if (book.isEpub) {
                EpubFile.clearCache(book)
                clearCopiedEpubCache(book)
            }
            book.removeLocalUriCache()
        }
    }

    /** Imported archive resources are entity-owned storage, never cache-cleanup targets. */
    fun deletePersistentBookResources(book: Book) {
        BookArchive.deletePersistentResources(book)
        AudioBookArchive.deletePersistentMedia(book)
    }

    private fun validateArchiveTextFile(textFile: String, failurePrefix: String) {
        val normalized = textFile.replace('\\', '/').removePrefix("./")
        require(
            textFile.isNotBlank() &&
                normalized == textFile &&
                !normalized.contains('/') &&
                normalized.endsWith(".txt", true)
        ) {
            "$failurePrefix: invalid text file $textFile"
        }
    }

    private fun clearManagedCoverCache(book: Book) {
        listOf("png", "jpg", "webp").forEach { extension ->
            FileUtils.delete(getCoverPath(book.bookUrl, extension))
        }
    }

    private fun clearCopiedEpubCache(book: Book) {
        if (!book.bookUrl.isContentScheme()) return
        val hasOtherSameEpub = appDb.bookDao.all.any {
            it.bookUrl != book.bookUrl && it.isEpub && it.originName == book.originName
        }
        if (hasOtherSameEpub) return
        FileUtils.delete(FileUtils.getPath(appCtx.externalFiles, "epub", book.originName))
    }

    /**
     * 下载在线的文件
     */
    suspend fun saveBookFile(
        str: String,
        fileName: String,
        source: BaseSource? = null,
    ): Uri {
        AppConfig.defaultBookTreeUri
            ?: throw NoBooksDirException()
        val inputStream = when {
            str.isAbsUrl() -> AnalyzeUrl(
                str, source = source, callTimeout = 0,
                coroutineContext = currentCoroutineContext()
            ).getInputStreamAwait()

            str.isDataUrl() -> ByteArrayInputStream(
                Base64.decode(
                    str.substringAfter("base64,"),
                    Base64.DEFAULT
                )
            )

            else -> throw NoStackTraceException("在线导入书籍支持http/https/DataURL")
        }
        return saveBookFile(inputStream, fileName)
    }

    @Throws(SecurityException::class)
    fun saveBookFile(
        inputStream: InputStream,
        fileName: String
    ): Uri {
        inputStream.use {
            val defaultBookTreeUri = AppConfig.defaultBookTreeUri
            return if (!defaultBookTreeUri.isNullOrBlank()) {
                val treeUri = defaultBookTreeUri.toUri()
                if (treeUri.isContentScheme()) {
                    val treeDoc = DocumentFile.fromTreeUri(appCtx, treeUri)
                    var doc = treeDoc!!.findFile(fileName)
                    if (doc == null) {
                        doc = treeDoc.createFile(FileUtils.getMimeType(fileName), fileName)
                            ?: throw SecurityException("请重新设置书籍保存位置\nPermission Denial")
                    }
                    appCtx.contentResolver.openOutputStream(doc.uri)!!.use { oStream ->
                        it.copyTo(oStream)
                    }
                    doc.uri
                } else {
                    try {
                        val treeFile = File(treeUri.path!!)
                        val file = treeFile.getFile(fileName)
                        FileOutputStream(file).use { oStream ->
                            it.copyTo(oStream)
                        }
                        Uri.fromFile(file)
                    } catch (e: FileNotFoundException) {
                        throw SecurityException("请重新设置书籍保存位置\nPermission Denial\n$e").apply {
                            addSuppressed(e)
                        }
                    }
                }
            } else {
                // 未设置书籍保存目录时，兜底保存到应用外部文件目录 Books/，避免导入失败
                val fallbackDir = appCtx.externalFiles.getFile("Books").apply { mkdirs() }
                val file = File(fallbackDir, fileName)
                FileOutputStream(file).use { oStream ->
                    it.copyTo(oStream)
                }
                Uri.fromFile(file)
            }
        }
    }

    fun isOnBookShelf(
        fileName: String
    ): Boolean {
        return appDb.bookDao.hasFile(fileName)
    }

    //文件类书源 合并在线书籍信息 在线 > 本地
    fun mergeBook(localBook: Book, onLineBook: Book?): Book {
        onLineBook ?: return localBook
        localBook.name = onLineBook.name.ifBlank { localBook.name }
        localBook.author = onLineBook.author.ifBlank { localBook.author }
        localBook.coverUrl = onLineBook.coverUrl
        localBook.intro =
            if (onLineBook.intro.isNullOrBlank()) localBook.intro else onLineBook.intro
        localBook.save()
        return localBook
    }

    //下载book对应的远程文件 并更新Book
    private fun downloadRemoteBook(localBook: Book): Boolean {
        val webDavUrl = localBook.getRemoteUrl()
        if (webDavUrl.isNullOrBlank()) throw NoStackTraceException("Book file is not webDav File")
        try {
            AppConfig.defaultBookTreeUri
                ?: throw NoBooksDirException()
            // 兼容旧版链接
            val webdav: WebDav = kotlin.runCatching {
                WebDav.fromPath(webDavUrl)
            }.getOrElse {
                AppWebDav.authorization?.let { WebDav(webDavUrl, it) }
                    ?: throw WebDavException("Unexpected defaultBookWebDav")
            }
            val inputStream = runBlocking {
                webdav.downloadInputStream()
            }
            inputStream.use {
                if (localBook.isArchive) {
                    // 压缩包
                    val archiveUri = saveBookFile(it, localBook.archiveName)
                    val newBook = importArchiveFile(archiveUri, localBook.originName) { name ->
                        name.contains(localBook.originName)
                    }.first()
                    localBook.origin = newBook.origin
                    localBook.bookUrl = newBook.bookUrl
                } else {
                    // txt epub pdf umd
                    val fileUri = saveBookFile(it, localBook.originName)
                    localBook.bookUrl = FileDoc.fromUri(fileUri, false).toString()
                    localBook.save()
                }
            }
            return true
        } catch (e: Exception) {
            e.printOnDebug()
            AppLog.put("自动下载webDav书籍失败", e)
            return false
        }
    }

}
