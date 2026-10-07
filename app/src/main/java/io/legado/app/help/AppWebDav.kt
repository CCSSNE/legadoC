package io.legado.app.help

import android.net.Uri
import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.constant.BookMediaType
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookProgress
import io.legado.app.data.entities.BookProgressComparison
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.config.AppConfig
import io.legado.app.help.storage.Backup
import io.legado.app.help.storage.Restore
import io.legado.app.lib.webdav.Authorization
import io.legado.app.lib.webdav.ProgressListener
import io.legado.app.lib.webdav.WebDav
import io.legado.app.lib.webdav.WebDavException
import io.legado.app.lib.webdav.WebDavFile
import io.legado.app.model.remote.RemoteBookWebDav
import io.legado.app.utils.AlphanumComparator
import io.legado.app.utils.FileUtils
import io.legado.app.utils.GSON
import io.legado.app.utils.NetworkUtils
import io.legado.app.utils.UrlUtil
import io.legado.app.utils.compress.ZipUtils
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.getPrefString
import io.legado.app.utils.isJson
import io.legado.app.utils.normalizeFileName
import io.legado.app.utils.removePref
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import splitties.init.appCtx
import java.io.File

/**
 * webDav初始化会访问网络,不要放到主线程
 */
object AppWebDav {
    private const val defaultWebDavUrl = "https://dav.jianguoyun.com/dav/"
    private val bookProgressUrl get() = "${rootWebDavUrl}bookProgress/"
    private val exportsWebDavUrl get() = "${rootWebDavUrl}books/"
    private val bgWebDavUrl get() = "${rootWebDavUrl}background/"
    private val themesWebDavUrl get() = "${rootWebDavUrl}themes/"
    private val navigationBarsWebDavUrl get() = "${rootWebDavUrl}navigationBars/"

    var authorization: Authorization? = null
        private set

    var defaultBookWebDav: RemoteBookWebDav? = null

    val isOk get() = authorization != null

    val isJianGuoYun get() = rootWebDavUrl.startsWith(defaultWebDavUrl, true)

    init {
        runBlocking {
            upConfig()
        }
    }

    private val rootWebDavUrl: String
        get() {
            val configUrl = appCtx.getPrefString(PreferKey.webDavUrl)?.trim()
            var url = if (configUrl.isNullOrEmpty()) defaultWebDavUrl else configUrl
            if (!url.endsWith("/")) url = "${url}/"
            AppConfig.webDavDir?.trim()?.let {
                if (it.isNotEmpty()) {
                    url = "${url}${it}/"
                }
            }
            return url
        }

    suspend fun upConfig() {
        kotlin.runCatching {
            authorization = null
            defaultBookWebDav = null
            val account = appCtx.getPrefString(PreferKey.webDavAccount)
            val password = appCtx.getPrefString(PreferKey.webDavPassword)
            if (!account.isNullOrEmpty() && !password.isNullOrEmpty()) {
                val mAuthorization = Authorization(account, password)
                checkAuthorization(mAuthorization)
                WebDav(rootWebDavUrl, mAuthorization).makeAsDir()
                WebDav(bookProgressUrl, mAuthorization).makeAsDir()
                WebDav(exportsWebDavUrl, mAuthorization).makeAsDir()
                WebDav(bgWebDavUrl, mAuthorization).makeAsDir()
                WebDav(themesWebDavUrl, mAuthorization).makeAsDir()
                WebDav(navigationBarsWebDavUrl, mAuthorization).makeAsDir()
                val rootBooksUrl = "${rootWebDavUrl}books/"
                defaultBookWebDav = RemoteBookWebDav(rootBooksUrl, mAuthorization)
                authorization = mAuthorization
            }
        }
    }

    @Throws(WebDavException::class)
    private suspend fun checkAuthorization(authorization: Authorization) {
        if (!WebDav(rootWebDavUrl, authorization).check()) {
            appCtx.removePref(PreferKey.webDavPassword)
            appCtx.toastOnUi(R.string.webdav_application_authorization_error)
            throw WebDavException(appCtx.getString(R.string.webdav_application_authorization_error))
        }
    }

    @Throws(Exception::class)
    suspend fun getBackupNames(): ArrayList<String> {
        val names = arrayListOf<String>()
        authorization?.let {
            var files = WebDav(rootWebDavUrl, it).listFiles()
            files = files.sortedWith { o1, o2 ->
                AlphanumComparator.compare(o1.displayName, o2.displayName)
            }.reversed()
            files.forEach { webDav ->
                val name = webDav.displayName
                if (name.startsWith("backup")) {
                    names.add(name)
                }
            }
        } ?: throw NoStackTraceException("webDav没有配置")
        return names
    }

    @Throws(WebDavException::class)
    suspend fun restoreWebDav(
        name: String,
        onProgress: ProgressListener? = null,
        onDownloadFinish: (() -> Unit)? = null
    ) {
        authorization?.let {
            downloadBackupToLocal(name, onProgress, onDownloadFinish)
            Restore.restoreLocked(Backup.backupPath)
        }
    }

    @Throws(WebDavException::class)
    suspend fun downloadBackupToLocal(
        name: String,
        onProgress: ProgressListener? = null,
        onDownloadFinish: (() -> Unit)? = null
    ) {
        authorization?.let {
            val webDav = WebDav(rootWebDavUrl + name, it)
            webDav.downloadTo(Backup.zipFilePath, true, onProgress)
            onDownloadFinish?.invoke()
            FileUtils.delete(Backup.backupPath)
            ZipUtils.unZipToPath(File(Backup.zipFilePath), Backup.backupPath)
        }
    }

    suspend fun hasBackUp(backUpName: String): Boolean {
        authorization?.let {
            val url = "$rootWebDavUrl${backUpName}"
            return WebDav(url, it).exists()
        }
        return false
    }

    suspend fun lastBackUp(): Result<WebDavFile?> {
        return kotlin.runCatching {
            authorization?.let {
                var lastBackupFile: WebDavFile? = null
                WebDav(rootWebDavUrl, it).listFiles().reversed().forEach { webDavFile ->
                    if (webDavFile.displayName.startsWith("backup")) {
                        if (lastBackupFile == null
                            || webDavFile.lastModify > lastBackupFile.lastModify
                        ) {
                            lastBackupFile = webDavFile
                        }
                    }
                }
                lastBackupFile
            }
        }
    }

    /**
     * webDav备份
     * @param fileName 备份文件名
     */
    @Throws(Exception::class)
    suspend fun backUpWebDav(fileName: String, onProgress: ProgressListener? = null) {
        if (!NetworkUtils.isAvailable()) return
        authorization?.let {
            val putUrl = "$rootWebDavUrl$fileName"
            WebDav(putUrl, it).upload(Backup.zipFilePath, onProgress = onProgress)
        }
    }

    suspend fun listThemePackages(isNightTheme: Boolean): List<WebDavFile> {
        val authorization = authorization ?: return emptyList()
        if (!NetworkUtils.isAvailable()) return emptyList()
        val dirUrl = getThemeTypeUrl(isNightTheme)
        WebDav(dirUrl, authorization).makeAsDir()
        return WebDav(dirUrl, authorization).listFiles()
            .filter { !it.isDir && it.displayName.endsWith(".zip", ignoreCase = true) }
    }

    suspend fun uploadThemePackage(isNightTheme: Boolean, remoteDirName: String, zipFile: File) {
        val authorization = authorization ?: throw NoStackTraceException("webDav未配置")
        if (!NetworkUtils.isAvailable()) throw NoStackTraceException("网络未连接")
        val fileName = "${remoteDirName.trimEnd('/').removeSuffix(".zip")}.zip"
        val typeUrl = getThemeTypeUrl(isNightTheme)
        WebDav(typeUrl, authorization).makeAsDir()
        WebDav(typeUrl + fileName, authorization).upload(zipFile)
    }

    suspend fun uploadCachePackage(fileName: String, zipFile: File) {
        val authorization = authorization ?: throw NoStackTraceException("webDav未配置")
        if (!NetworkUtils.isAvailable()) throw NoStackTraceException("网络未连接")
        val safeFileName = UrlUtil.replaceReservedChar(
            fileName.trimEnd('/').removeSuffix(".zip").normalizeFileName()
        ).ifBlank { "cache_${System.currentTimeMillis()}" }
        WebDav(exportsWebDavUrl, authorization).makeAsDir()
        WebDav(exportsWebDavUrl + safeFileName + ".zip", authorization)
            .upload(zipFile, "application/zip")
    }

    suspend fun downloadThemePackage(isNightTheme: Boolean, remoteDirName: String, zipFile: File) {
        val authorization = authorization ?: throw NoStackTraceException("webDav未配置")
        if (!NetworkUtils.isAvailable()) throw NoStackTraceException("网络未连接")
        val fileName = "${remoteDirName.trimEnd('/').removeSuffix(".zip")}.zip"
        zipFile.parentFile?.mkdirs()
        WebDav(getThemeTypeUrl(isNightTheme) + fileName, authorization)
            .downloadTo(zipFile.absolutePath, true)
    }

    suspend fun deleteThemePackage(isNightTheme: Boolean, remoteDirName: String) {
        val authorization = authorization ?: throw NoStackTraceException("webDav未配置")
        if (!NetworkUtils.isAvailable()) throw NoStackTraceException("网络未连接")
        val fileName = "${remoteDirName.trimEnd('/').removeSuffix(".zip")}.zip"
        WebDav(getThemeTypeUrl(isNightTheme) + fileName, authorization).delete()
    }

    suspend fun listNavigationBarPackages(isNightTheme: Boolean): List<WebDavFile> {
        val authorization = authorization ?: return emptyList()
        if (!NetworkUtils.isAvailable()) return emptyList()
        val dirUrl = getNavigationBarTypeUrl(isNightTheme)
        WebDav(dirUrl, authorization).makeAsDir()
        return WebDav(dirUrl, authorization).listFiles()
            .filter { !it.isDir && it.displayName.endsWith(".zip", ignoreCase = true) }
    }

    suspend fun uploadNavigationBarPackage(isNightTheme: Boolean, remoteDirName: String, zipFile: File) {
        val authorization = authorization ?: throw NoStackTraceException("webDav未配置")
        if (!NetworkUtils.isAvailable()) throw NoStackTraceException("网络未连接")
        val fileName = "${remoteDirName.trimEnd('/').removeSuffix(".zip")}.zip"
        val typeUrl = getNavigationBarTypeUrl(isNightTheme)
        WebDav(typeUrl, authorization).makeAsDir()
        WebDav(typeUrl + fileName, authorization).upload(zipFile)
    }

    suspend fun downloadNavigationBarPackage(isNightTheme: Boolean, remoteDirName: String, zipFile: File) {
        val authorization = authorization ?: throw NoStackTraceException("webDav未配置")
        if (!NetworkUtils.isAvailable()) throw NoStackTraceException("网络未连接")
        val fileName = "${remoteDirName.trimEnd('/').removeSuffix(".zip")}.zip"
        zipFile.parentFile?.mkdirs()
        WebDav(getNavigationBarTypeUrl(isNightTheme) + fileName, authorization)
            .downloadTo(zipFile.absolutePath, true)
    }

    suspend fun deleteNavigationBarPackage(isNightTheme: Boolean, remoteDirName: String) {
        val authorization = authorization ?: throw NoStackTraceException("webDav未配置")
        if (!NetworkUtils.isAvailable()) throw NoStackTraceException("网络未连接")
        val fileName = "${remoteDirName.trimEnd('/').removeSuffix(".zip")}.zip"
        WebDav(getNavigationBarTypeUrl(isNightTheme) + fileName, authorization).delete()
    }

    private fun getThemeTypeUrl(isNightTheme: Boolean): String {
        return themesWebDavUrl + if (isNightTheme) "night/" else "day/"
    }

    private fun getNavigationBarTypeUrl(isNightTheme: Boolean): String {
        return navigationBarsWebDavUrl + if (isNightTheme) "night/" else "day/"
    }

    /**
     * 获取云端所有背景名称
     */
    private suspend fun getAllBgWebDavFiles(): Result<List<WebDavFile>> {
        return kotlin.runCatching {
            if (!NetworkUtils.isAvailable())
                throw NoStackTraceException("网络未连接")
            authorization.let {
                it ?: throw NoStackTraceException("webDav未配置")
                WebDav(bgWebDavUrl, it).listFiles()
            }
        }
    }

    /**
     * 上传背景图片
     */
    suspend fun upBgs(files: Array<File>) {
        val authorization = authorization ?: return
        if (!NetworkUtils.isAvailable()) return
        val bgWebDavFiles = getAllBgWebDavFiles().getOrThrow()
            .map { it.displayName }
            .toSet()
        files.forEach {
            if (!bgWebDavFiles.contains(it.name) && it.exists()) {
                WebDav("$bgWebDavUrl${it.name}", authorization)
                    .upload(it)
            }
        }
    }

    /**
     * 下载背景图片
     */
    suspend fun downBgs() {
        val authorization = authorization ?: return
        if (!NetworkUtils.isAvailable()) return
        val bgWebDavFiles = getAllBgWebDavFiles().getOrThrow()
            .map { it.displayName }
            .toSet()
    }

    @Suppress("unused")
    suspend fun exportWebDav(byteArray: ByteArray, fileName: String) {
        if (!NetworkUtils.isAvailable()) return
        try {
            authorization?.let {
                // 如果导出的本地文件存在,开始上传
                val putUrl = exportsWebDavUrl + fileName
                WebDav(putUrl, it).upload(byteArray, "text/plain")
            }
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            AppLog.put("WebDav导出失败\n${e.localizedMessage}", e, true)
        }
    }

    suspend fun exportWebDav(
        uri: Uri,
        fileName: String,
        localAlreadySaved: Boolean = true,
    ) {
        val failurePrefix = if (localAlreadySaved) {
            "本地导出已保存，WebDAV 上传失败"
        } else {
            "WebDAV 上传失败"
        }
        check(NetworkUtils.isAvailable()) { "$failurePrefix：网络不可用" }
        val credentials = requireNotNull(authorization) {
            "$failurePrefix：未配置认证信息"
        }
        try {
            val mime = if (fileName.endsWith(".zip", ignoreCase = true)) "application/zip" else "text/plain"
            WebDav(exportsWebDavUrl + Uri.encode(fileName), credentials).upload(uri, mime)
        } catch (error: Exception) {
            currentCoroutineContext().ensureActive()
            throw IllegalStateException("$failurePrefix：${error.localizedMessage}", error)
        }
    }

    suspend fun uploadBookProgress(
        book: Book,
        toast: Boolean = false,
        onSuccess: (() -> Unit)? = null
    ) {
        val authorization = authorization ?: return
        if (!AppConfig.syncBookProgress) return
        if (!NetworkUtils.isAvailable()) return
        try {
            val bookProgress = BookProgress(book)
            val json = GSON.toJson(bookProgress)
            val url = getProgressUrl(book.name, book.author, bookProgress.mediaType)
            WebDav(url, authorization).upload(json.toByteArray(), "application/json")
            book.syncTime = System.currentTimeMillis()
            onSuccess?.invoke()
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            AppLog.put("上传进度失败\n${e.localizedMessage}", e, toast)
        }
    }

    suspend fun uploadBookProgress(bookProgress: BookProgress, onSuccess: (() -> Unit)? = null) {
        try {
            val authorization = authorization ?: return
            if (!AppConfig.syncBookProgress) return
            if (!NetworkUtils.isAvailable()) return
            val json = GSON.toJson(bookProgress)
            val url = getProgressUrl(
                bookProgress.name,
                bookProgress.author,
                bookProgress.mediaType
            )
            WebDav(url, authorization).upload(json.toByteArray(), "application/json")
            onSuccess?.invoke()
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            AppLog.put("上传进度失败\n${e.localizedMessage}", e)
        }
    }

    private fun getProgressUrl(
        name: String,
        author: String,
        @BookMediaType.Type mediaType: Int
    ): String {
        return bookProgressUrl + getProgressFileName(name, author, mediaType)
    }

    private fun getProgressFileName(
        name: String,
        author: String,
        @BookMediaType.Type mediaType: Int
    ): String {
        val mediaSuffix = if (mediaType == BookMediaType.text) "" else "_media_$mediaType"
        return UrlUtil.replaceReservedChar(
            "${name}_${author}$mediaSuffix".normalizeFileName()
        ) + ".json"
    }

    /**
     * 获取书籍进度
     *
     * 必须区分「云端无文件」与「拉取失败」两种 null 情形。
     * 调用方（ReadBook/ReadManga/VideoPlay 的 syncProgress）把 null 与 LOCAL_NEWER
     * 合并进同一分支并上传本地进度；若网络抖动导致的拉取失败也返回 null，就会用
     * 本地旧进度覆盖云端新进度（不可逆数据丢失）。
     * 因此：文件存在却拉取失败 → 抛出异常，由调用方 onError 中止，绝不上传；
     *       云端确无该文件（首同步）→ 返回 null，允许调用方上传；
     *       拉取失败后连「文件是否存在」都无法判定（网络故障）→ 同样抛出异常中止。
     * 注：判定存在性用 existsChecked() 而非 exists()——后者吞掉网络异常返回 false，
     *     弱网下会把「无法判定」误判为「云端无文件」导致反向覆盖。
     */
    suspend fun getBookProgress(book: Book): BookProgress? {
        val url = getProgressUrl(
            book.name,
            book.author,
            BookMediaType.fromBookType(book.type)
        )
        var fetchError: Throwable? = null
        kotlin.runCatching {
            val authorization = authorization ?: return null
            WebDav(url, authorization).download().let { byteArray ->
                val json = String(byteArray)
                if (json.isJson()) {
                    return GSON.fromJsonObject<BookProgress>(json).getOrNull()
                }
                // HTTP 下载成功但内容非合法 JSON（文件被写坏、半途上传残留、BOM/多余
                // 字符），绝不能当成"云端无进度"而上传本地进度覆盖。走下方 existsChecked()
                // 判定：文件确实存在则抛异常中止上传；仅空文件（byteArray 为空）保持
                // 原路径视为无有效内容。
                if (byteArray.isNotEmpty()) {
                    error("进度文件内容不是合法 JSON: ${json.take(80)}")
                }
            }
        }.onFailure {
            currentCoroutineContext().ensureActive()
            AppLog.put("获取书籍进度失败\n${it.localizedMessage}", it)
            fetchError = it
        }
        val error = fetchError
        if (error != null) {
            val authorization = authorization ?: return null
            // 必须用不吞异常的 existsChecked 严格判定：确定存在 → throw 中止；
            // 确定不存在 → 返回 null 允许首同步上传；无法判定（网络故障）→ throw 中止，
            // 宁可不更新，绝不上传覆盖。
            val exists = try {
                WebDav(url, authorization).existsChecked()
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                AppLog.put("确认进度文件是否存在失败，中止本次同步\n${e.localizedMessage}", e)
                throw error
            }
            if (exists) {
                // 文件存在却拉取失败：网络/鉴权/解析问题，
                // 绝不能让调用方当成"云端无进度"而上传本地进度
                throw error
            }
        }
        // 云端确无该进度文件（首同步），返回 null 由调用方上传本地进度
        return null
    }

    suspend fun downloadAllBookProgress() {
        val authorization = authorization ?: return
        if (!NetworkUtils.isAvailable()) return
        val bookProgressFiles = try {
            WebDav(bookProgressUrl, authorization).listFiles()
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            AppLog.put("拉取进度文件列表失败\n${e.localizedMessage}", e)
            return
        }
        val map = hashMapOf<String, WebDavFile>()
        bookProgressFiles.forEach {
            map[it.displayName] = it
            // displayName 来自 URLDecoder.decodeForPath（已解码），
            // 而 progressFileName 产出的是 UrlUtil.replaceReservedChar 百分号编码名
            //（空格→%20、#→%23…）。只按一种编码态建索引，会让含这些字符的书名
            // 永远匹配不到进度文件并被静默跳过，故两种形态都登记。
            map[UrlUtil.replaceReservedChar(it.displayName)] = it
        }
        appDb.bookDao.all.forEach { book ->
            val progressFileName = getProgressFileName(
                book.name,
                book.author,
                BookMediaType.fromBookType(book.type)
            )
            val webDavFile = map[progressFileName]
            // 必须 return@forEach 跳过当前书，裸 return 会中断整批进度拉取
            webDavFile ?: return@forEach
            if (webDavFile.lastModify <= book.syncTime) {
                //本地同步时间大于上传时间不用同步
                return@forEach
            }
            val bookProgress = try {
                getBookProgress(book)
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                AppLog.put("获取书籍进度失败(批量)\n${e.localizedMessage}", e)
                null
            } ?: return@forEach
            if (bookProgress.compareWith(book) == BookProgressComparison.REMOTE_NEWER) {
                book.durChapterIndex = bookProgress.durChapterIndex
                book.durChapterPos = bookProgress.durChapterPos
                book.durChapterTitle = bookProgress.durChapterTitle
                book.durChapterTime = bookProgress.durChapterTime
                book.syncTime = System.currentTimeMillis()
                appDb.bookDao.update(book)
            }
        }
    }

}
