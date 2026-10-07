package io.legado.app.help.storage

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.room.withTransaction
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.legado.app.R
import io.legado.app.constant.AppConst.androidId
import io.legado.app.constant.EventBus
import io.legado.app.constant.AppLog
import io.legado.app.constant.BookMediaType
import io.legado.app.constant.BookType
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookIllustration
import io.legado.app.data.entities.BookGroup
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.Bookmark
import io.legado.app.data.entities.DictRule
import io.legado.app.data.entities.HttpTTS
import io.legado.app.data.entities.KeyboardAssist
import io.legado.app.data.entities.ReadRecord
import io.legado.app.data.entities.ReadRecordDaily
import io.legado.app.data.entities.ReplaceRule
import io.legado.app.data.entities.RssSource
import io.legado.app.data.entities.RssStar
import io.legado.app.data.entities.RuleSub
import io.legado.app.data.entities.SearchKeyword
import io.legado.app.data.entities.Server
import io.legado.app.data.entities.TxtTocRule
import io.legado.app.help.DirectLinkUpload
import io.legado.app.help.LauncherIconHelp
import io.legado.app.help.book.isArchive
import io.legado.app.help.book.isLocal
import io.legado.app.help.book.isType
import io.legado.app.help.book.upType
import io.legado.app.help.config.LocalConfig
import io.legado.app.help.config.NavigationBarIconConfig
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.help.config.ThemeConfig
import io.legado.app.help.config.ThemePackageManager
import io.legado.app.model.VideoPlay.VIDEO_PREF_NAME
import io.legado.app.model.BookCover
import io.legado.app.model.localBook.LocalBook
import io.legado.app.utils.ACache
import io.legado.app.utils.FileUtils
import io.legado.app.utils.GSON
import io.legado.app.utils.LogUtils
import io.legado.app.utils.compress.ZipUtils
import io.legado.app.utils.defaultSharedPreferences
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.externalFiles
import io.legado.app.utils.getFile
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.getPrefInt
import io.legado.app.utils.getPrefString
import io.legado.app.utils.getSharedPreferences
import io.legado.app.utils.isContentScheme
import io.legado.app.utils.isJsonArray
import io.legado.app.utils.openInputStream
import io.legado.app.utils.postEvent
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import splitties.init.appCtx
import java.io.File
import java.io.FileInputStream

/**
 * 恢复
 */
object Restore {

    private val mutex = Mutex()

    private const val TAG = "Restore"

    internal val backgroundAssetDirNames = arrayOf(
        "bg",
        "font",
        "covers",
        "readRecordGoalAvatar",
        PreferKey.bgImage,
        PreferKey.bgImageN,
        PreferKey.bookInfoBgImage,
        PreferKey.bookInfoBgImageN
    )

    suspend fun restore(context: Context, uri: Uri) {
        LogUtils.d(TAG, "开始恢复备份 uri:$uri")
        kotlin.runCatching {
            FileUtils.delete(Backup.backupPath)
            if (uri.isContentScheme()) {
                DocumentFile.fromSingleUri(context, uri)!!.openInputStream()!!.use {
                    ZipUtils.unZipToPath(it, Backup.backupPath)
                }
            } else {
                ZipUtils.unZipToPath(File(uri.path!!), Backup.backupPath)
            }
        }.onFailure {
            AppLog.put("复制解压文件出错\n${it.localizedMessage}", it)
            return
        }
        kotlin.runCatching {
            restoreLocked(Backup.backupPath)
            LocalConfig.lastBackup = System.currentTimeMillis()
        }.onFailure {
            appCtx.toastOnUi("恢复备份出错\n${it.localizedMessage}")
            AppLog.put("恢复备份出错\n${it.localizedMessage}", it)
        }
    }

    suspend fun restoreLocked(path: String) {
        mutex.withLock {
            RestoreJournal.begin(RestoreJournal.buildSnapshotTargets(path))
            try {
                restore(path)
                RestoreJournal.markPendingValidation()
            } catch (e: Throwable) {
                RestoreJournal.rollbackNow("恢复过程异常: ${e.localizedMessage}")
                throw e
            }
        }
    }

    private suspend fun restore(path: String) {
        val aes = BackupAES()
        // DB 段（bookshelf→servers.json）整体事务化，任一步失败整段回滚，杜绝半恢复态
        restoreDbData(path, aes)
        File(path, DirectLinkUpload.ruleFileName).takeIf {
            it.exists()
        }?.runCatching {
            val json = readText()
            ACache.get(cacheDir = false).put(DirectLinkUpload.ruleFileName, json)
        }?.onFailure {
            AppLog.put("恢复直链上传出错\n${it.localizedMessage}", it)
        }
        //恢复主题配置
        File(path, ThemeConfig.configFileName).takeIf {
            it.exists()
        }?.runCatching {
            FileUtils.delete(ThemeConfig.configFilePath)
            copyTo(File(ThemeConfig.configFilePath))
            ThemeConfig.upConfig()
        }?.onFailure {
            AppLog.put("恢复主题出错\n${it.localizedMessage}", it)
        }
        File(path, BookCover.configFileName).takeIf {
            it.exists()
        }?.runCatching {
            val json = readText()
            BookCover.saveCoverRule(json)
        }?.onFailure {
            AppLog.put("恢复封面规则出错\n${it.localizedMessage}", it)
        }
        var restoredReadConfig = false
        if (!BackupConfig.ignoreReadConfig) {
            //恢复阅读界面配置
            File(path, ReadBookConfig.configFileName).takeIf {
                it.exists()
            }?.runCatching {
                FileUtils.delete(ReadBookConfig.configFilePath)
                copyTo(File(ReadBookConfig.configFilePath))
                restoredReadConfig = true
            }?.onFailure {
                AppLog.put("恢复阅读界面出错\n${it.localizedMessage}", it)
            }
            File(path, ReadBookConfig.shareConfigFileName).takeIf {
                it.exists()
            }?.runCatching {
                FileUtils.delete(ReadBookConfig.shareConfigFilePath)
                copyTo(File(ReadBookConfig.shareConfigFilePath))
                restoredReadConfig = true
            }?.onFailure {
                AppLog.put("恢复阅读界面出错\n${it.localizedMessage}", it)
            }
        }
        restoreBackgroundAssets(path)
        repairLocalCoverPaths()
        normalizeReadRecordGoalAvatar()
        if (restoredReadConfig) {
            ReadBookConfig.initConfigs()
            ReadBookConfig.initShareConfig()
        }
        ReadBookConfig.repairConfigIfNeeded()
        restoreThemePackages(path)
        restoreNavigationIcons(path)
        //AppWebDav.downBgs()
        appCtx.getSharedPreferences(path, "config")?.all?.let { map ->
            val edit = appCtx.defaultSharedPreferences.edit()

            map.forEach { (key, value) ->
                if (BackupConfig.keyIsNotIgnore(key)) {
                    when (key) {
                        PreferKey.webDavPassword -> {
                            kotlin.runCatching {
                                aes.decryptStr(value.toString())
                            }.getOrNull()?.let {
                                edit.putString(key, it)
                            } ?: let {
                                if (appCtx.getPrefString(PreferKey.webDavPassword)
                                        .isNullOrBlank()
                                ) {
                                    edit.putString(key, value.toString())
                                }
                            }
                        }

                        else -> when (value) {
                            is Int -> edit.putInt(key, value)
                            is Boolean -> edit.putBoolean(key, value)
                            is Long -> edit.putLong(key, value)
                            is Float -> edit.putFloat(key, value)
                            is String -> edit.putString(key, value)
                        }
                    }
                }
            }
            edit.commit()
        }
        normalizeBackgroundPrefs()
        normalizeStringPrefs()
        kotlin.runCatching {
            ThemePackageManager.ensureLocalAppliedTheme(appCtx, false)
            ThemePackageManager.ensureLocalAppliedTheme(appCtx, true)
            ThemePackageManager.reapplyRestoredAppliedThemes(appCtx)
        }.onFailure {
            AppLog.put("恢复默认主题包出错\n${it.localizedMessage}", it)
        }
        appCtx.getSharedPreferences(path, "videoConfig")?.all?.let { map ->
            appCtx.getSharedPreferences(VIDEO_PREF_NAME, Context.MODE_PRIVATE).edit().apply {
                map.forEach { (key, value) ->
                    when (value) {
                        is Int -> putInt(key, value)
                        is Boolean -> putBoolean(key, value)
                        is Long -> putLong(key, value)
                        is Float -> putFloat(key, value)
                        is String -> putString(key, value)
                    }
                }
                apply()
            }
        }
        ReadBookConfig.apply {
            comicStyleSelect = appCtx.getPrefInt(PreferKey.comicStyleSelect)
            readStyleSelect = appCtx.getPrefInt(PreferKey.readStyleSelect)
            shareLayout = appCtx.getPrefBoolean(PreferKey.shareLayout)
            hideStatusBar = appCtx.getPrefBoolean(PreferKey.hideStatusBar)
            hideNavigationBar = appCtx.getPrefBoolean(PreferKey.hideNavigationBar)
            autoReadSpeed = appCtx.getPrefInt(PreferKey.autoReadSpeed, 46)
            resetActiveConfig()
        }
        val agentBackup = File(path, io.legado.app.help.agent.AgentBackup.FILE_NAME)
        if (agentBackup.exists()) {
            io.legado.app.help.agent.AgentBackup.restore(agentBackup)
        }
        postEvent(EventBus.UP_CONFIG, arrayListOf(1, 2, 5))
        appCtx.toastOnUi(R.string.restore_success)
        withContext(Main) {
            delay(100)
            LauncherIconHelp.changeIcon(appCtx.getPrefString(PreferKey.launcherIcon))
            ThemeConfig.applyDayNight(appCtx)
        }
    }

    /**
     * DB 段（bookshelf → servers.json）整体事务化：任一步失败整库回滚，杜绝半恢复态。
     * 用 Room [appDb.withTransaction]：keyboardAssistsDao.deleteAll() 是 suspend DAO，
     * 原生 beginTransaction 无法让它落在同一事务线程，withTransaction 在事务上下文中
     * 协调 suspend 与同步 DAO。
     */
    private suspend fun restoreDbData(path: String, aes: BackupAES) {
        appDb.withTransaction {
            // 备份书 URL → 最终落库去向；配图段据此重映射并过滤，保证外键恒有父行。
            val restoredBooks = restoreShelfBooks(path)
            fileToListT<Bookmark>(path, "bookmark.json")?.let {
                appDb.bookmarkDao.insert(*it.toTypedArray())
            }
            restoreIllustrations(path, restoredBooks)
            fileToListT<BookGroup>(path, "bookGroup.json")?.let {
                appDb.bookGroupDao.insert(*it.toTypedArray())
            }
            fileToListT<BookSource>(path, "bookSource.json")?.let {
                appDb.bookSourceDao.insert(*it.toTypedArray())
            } ?: run {
                val bookSourceFile = File(path, "bookSource.json")
                if (bookSourceFile.exists()) {
                    val json = bookSourceFile.readText()
                    ImportOldData.importOldSource(json)
                }
            }
            fileToListT<RssSource>(path, "rssSources.json")?.let {
                appDb.rssSourceDao.insert(*it.toTypedArray())
            }
            fileToListT<RssStar>(path, "rssStar.json")?.let {
                appDb.rssStarDao.insert(*it.toTypedArray())
            }
            fileToListT<ReplaceRule>(path, "replaceRule.json")?.let {
                appDb.replaceRuleDao.insert(*it.toTypedArray())
            }
            fileToListT<SearchKeyword>(path, "searchHistory.json")?.let {
                appDb.searchKeywordDao.insert(*it.toTypedArray())
            }
            fileToListT<RuleSub>(path, "sourceSub.json")?.let {
                appDb.ruleSubDao.insert(*it.toTypedArray())
            }
            fileToListT<TxtTocRule>(path, "txtTocRule.json")?.let {
                appDb.txtTocRuleDao.insert(*it.toTypedArray())
            }
            fileToListT<HttpTTS>(path, "httpTTS.json")?.let {
                appDb.httpTTSDao.insert(*it.toTypedArray())
            }
            fileToListT<DictRule>(path, "dictRule.json")?.let {
                appDb.dictRuleDao.insert(*it.toTypedArray())
            }
            fileToListT<KeyboardAssist>(path, "keyboardAssists.json")?.let {
                appDb.keyboardAssistsDao.deleteAll() //先删除所有,保证和备份数据一样
                appDb.keyboardAssistsDao.insert(*it.toTypedArray())
            }
            fileToListT<ReadRecord>(path, "readRecord.json")?.let {
                it.forEach { readRecord ->
                    mergeReadRecord(readRecord)
                }
            }
            fileToListT<ReadRecordDaily>(path, "readRecordDaily.json")?.let {
                it.forEach { record ->
                    mergeReadRecordDaily(record)
                }
            }
            File(path, "servers.json").takeIf {
                it.exists()
            }?.let { file ->
                var json = file.readText()
                if (!json.isJsonArray()) {
                    json = aes.decryptStr(json)
                }
                val servers = GSON.fromJsonArray<Server>(json).getOrThrow()
                appDb.serverDao.insert(*servers.toTypedArray())
            }
        }
    }

    /**
     * 恢复书架书籍，返回「备份书 URL → 最终落库去向」映射，供配图段重映射外键。
     *
     * 身份收敛：`Book` 表主键是 `bookUrl`（详情页地址），同一本书在不同书源下 `bookUrl` 不同，
     * 只按 `bookUrl` 合并会让两设备选了不同书源的同一本书在书架出现两条。
     * 按「书名+作者+媒体类型」命中本机已有行时，把备份并入**本机行**（只 UPDATE 不 INSERT），
     * 绝不删除本机数据；身份与进度一律保持本机——备份的章节索引锚定在备份源目录上，
     * 与本机目录不可比，直接搬会跳错章节。
     */
    private fun restoreShelfBooks(path: String): Map<String, RestoredBookRef> {
        val books = fileToBookList(path) ?: return emptyMap()
        books.forEach { book ->
            book.upType()
            // upType() 只改写 type，不同步 mediaType 列；mediaType 有独立读取点（如朗读语速），
            // 恢复落库时补一次同步。
            book.syncMediaType()
        }
        books.filter { book -> book.isLocal }
            .forEach { book ->
                book.coverUrl = LocalBook.getCoverPath(book)
            }
        val refs = hashMapOf<String, RestoredBookRef>()
        val ignoreLocalBook = BackupConfig.ignoreLocalBook
        books.forEach { book ->
            if (ignoreLocalBook && book.isLocal) {
                return@forEach
            }
            // 身份命中且 bookUrl 不同：并入本机行，不再插入备份主键，书架不产生同书重复条目。
            val localByIdentity = appDb.bookDao.getBook(book.bookUrl)
                ?: findLocalBookByIdentity(book)
            if (localByIdentity != null && localByIdentity.bookUrl != book.bookUrl) {
                appDb.bookDao.update(mergeBackupIntoLocal(localByIdentity, book))
                refs[book.bookUrl] = RestoredBookRef(
                    bookUrl = localByIdentity.bookUrl,
                    sameOrigin = localByIdentity.origin == book.origin,
                )
                return@forEach
            }
            if (appDb.bookDao.has(book.bookUrl)) {
                appDb.bookDao.update(book)
            } else {
                // 立即插入，使同一备份后续条目也能按身份命中；外层事务保证整体回滚。
                appDb.bookDao.insert(book)
            }
            refs[book.bookUrl] = RestoredBookRef(book.bookUrl, sameOrigin = true)
        }
        return refs
    }

    /** 恢复落库去向：`bookUrl` 为最终写入 books 表的主键，`sameOrigin` 表示与备份是否同书源。 */
    private data class RestoredBookRef(val bookUrl: String, val sameOrigin: Boolean)

    /**
     * 按「书名+作者+媒体类型」在本机找同书；不参与身份收敛时返回 null。
     * 媒体类型两侧都按 `type` 现算（[BookMediaType.fromBookType]），不依赖可能陈旧的 mediaType 列；
     * 本地书/归档书/未入架临时书/无书名不参与——本地书牵连文件资源，试读临时书不该被记进正式书架。
     */
    private fun findLocalBookByIdentity(book: Book): Book? {
        if (!identityEligible(book)) return null
        val mediaType = BookMediaType.fromBookType(book.type)
        return appDb.bookDao.getBooks(book.name, book.author).firstOrNull { local ->
            identityEligible(local) && BookMediaType.fromBookType(local.type) == mediaType
        }
    }

    private fun identityEligible(book: Book): Boolean {
        if (book.isLocal || book.isArchive) return false
        if (book.isType(BookType.notShelf)) return false
        return book.name.isNotBlank()
    }

    /**
     * 恢复专用保守合并：把备份书 [backup] 的用户状态并入本机行 [local]，返回应 UPDATE 的对象。
     * - 身份与进度（bookUrl/origin/originName/tocUrl/variable/order/totalChapterNum/durChapter*）
     *   一律保持 [local]；特别是 `variable`——恢复不动 origin，本机源脚本变量必须随本机，
     *   否则书源脚本状态与 origin 错配。
     * - 用户自定义字段（标签/简介/自定义封面/阅读配置）本机为空才用备份补，不覆盖本机填写。
     * - 分组取并集；本机更新开关和 syncTime 保持原值，未同步的备份不能推进同步水位。
     * - 绝不删除本机数据。
     */
    private fun mergeBackupIntoLocal(local: Book, backup: Book): Book {
        val merged = local.copy()
        if (merged.customTag == null) merged.customTag = backup.customTag
        if (merged.customIntro == null) merged.customIntro = backup.customIntro
        if (merged.customCoverUrl == null) merged.customCoverUrl = backup.customCoverUrl
        if (merged.readConfig == null) merged.readConfig = backup.readConfig
        merged.group = local.group or backup.group
        return merged
    }

    /**
     * 恢复配图。硬约束（`book_illustrations` 对 books.bookUrl 有外键，且整段在 withTransaction 内，
     * 违反任一条都会导致整个 DB 段回滚）：
     * ① 每行必须重映射到最终落库的书 URL（ignoreLocalBook 跳过的本地书、身份合并的书，其原 URL 不在库）；
     * ② 找不到父行的行跳过并记日志（暴露而非静默）；
     * ③ 身份合并且跨源时丢弃备份配图——chapterIndex 锚定备份源目录，与本机目录不可比；
     * ④ 按配图内容去重，不能因章节已有一张配图就丢弃该章其他配图。
     * 不删除任何本机配图：备份不含章节表，本机配图清掉即永久丢失。
     */
    private fun restoreIllustrations(path: String, restoredBooks: Map<String, RestoredBookRef>) {
        val illustrations = fileToListT<BookIllustration>(path, "bookIllustration.json") ?: return
        val pending = arrayListOf<BookIllustration>()
        val known = hashMapOf<Pair<String, Int>, MutableSet<BookIllustration>>()
        var skipped = 0
        illustrations.forEach { illustration ->
            val ref = restoredBooks[illustration.bookUrl]
                ?: appDb.bookDao.getBook(illustration.bookUrl)?.let {
                    RestoredBookRef(it.bookUrl, sameOrigin = true)
                }
            if (ref == null) {
                skipped++
                return@forEach
            }
            if (ref.bookUrl != illustration.bookUrl && !ref.sameOrigin) {
                skipped++
                return@forEach
            }
            val restored = illustration.copy(id = 0, bookUrl = ref.bookUrl)
            val existing = known.getOrPut(ref.bookUrl to illustration.chapterIndex) {
                appDb.bookIllustrationDao.getByBookAndChapter(ref.bookUrl, illustration.chapterIndex)
                    .map { it.copy(id = 0) }.toMutableSet()
            }
            if (existing.add(restored)) pending.add(restored)
        }
        if (pending.isNotEmpty()) {
            appDb.bookIllustrationDao.insert(*pending.toTypedArray())
        }
        if (skipped > 0) {
            LogUtils.d(TAG, "恢复配图跳过 $skipped 条（父书不存在或跨源锚点不可比）")
        }
    }

    private inline fun <reified T> fileToListT(path: String, fileName: String): List<T>? {
        try {
            val file = File(path, fileName)
            if (file.exists()) {
                LogUtils.d(TAG, "阅读恢复备份 $fileName 文件大小 ${file.length()}")
                FileInputStream(file).use {
                    return GSON.fromJsonArray<T>(it).getOrThrow().also { list ->
                        LogUtils.d(TAG, "阅读恢复备份 $fileName 列表大小 ${list.size}")
                    }
                }
            } else {
                LogUtils.d(TAG, "阅读恢复备份 $fileName 文件不存在")
            }
        } catch (e: Exception) {
            AppLog.put("$fileName\n读取解析出错\n${e.localizedMessage}", e)
            throw IllegalStateException("$fileName 读取失败，恢复已中止", e)
        }
        return null
    }

    private fun mergeReadRecord(readRecord: ReadRecord) {
        val normalized = readRecord.copy(
            deviceId = readRecord.deviceId.ifBlank { androidId }
        )
        val current = appDb.readRecordDao.getRecord(normalized.deviceId, normalized.bookName)
        if (current == null) {
            appDb.readRecordDao.insert(normalized)
            return
        }
        appDb.readRecordDao.insert(
            current.copy(
                readTime = maxOf(current.readTime, normalized.readTime),
                lastRead = maxOf(current.lastRead, normalized.lastRead)
            )
        )
    }

    private fun mergeReadRecordDaily(record: ReadRecordDaily) {
        val current = appDb.readRecordDailyDao.get(record.date)
        if (current == null) {
            appDb.readRecordDailyDao.insert(record)
            return
        }
        appDb.readRecordDailyDao.insert(
            current.copy(
                readTime = maxOf(current.readTime, record.readTime),
                updatedAt = maxOf(current.updatedAt, record.updatedAt)
            )
        )
    }

    private fun fileToBookList(path: String): List<Book>? {
        val fileName = "bookshelf.json"
        try {
            val file = File(path, fileName)
            if (file.exists()) {
                LogUtils.d(TAG, "阅读恢复备份 $fileName 文件大小 ${file.length()}")
                val list = arrayListOf<Book>()
                file.reader().use { reader ->
                    val jsonArray = JsonParser.parseReader(reader).asJsonArray
                    jsonArray.forEachIndexed { index, element ->
                        val bookJson = element.deepCopy()
                        sanitizeBookJson(bookJson)
                        runCatching {
                            GSON.fromJson(bookJson, Book::class.java)
                        }.onSuccess { book ->
                            list.add(requireNotNull(book) { "书籍条目为 null" })
                        }.onFailure {
                            throw IllegalStateException("$fileName 第${index + 1}项读取失败", it)
                        }
                    }
                }
                LogUtils.d(TAG, "阅读恢复备份 $fileName 列表大小 ${list.size}")
                return list
            } else {
                LogUtils.d(TAG, "阅读恢复备份 $fileName 文件不存在")
            }
        } catch (e: Exception) {
            AppLog.put("$fileName\n读取解析出错\n${e.localizedMessage}", e)
            throw IllegalStateException("$fileName 读取失败，恢复已中止", e)
        }
        return null
    }

    private fun sanitizeBookJson(element: JsonElement) {
        if (!element.isJsonObject) {
            return
        }
        val bookJson = element.asJsonObject
        val readConfig = bookJson.get("readConfig") ?: return
        if (!readConfig.isJsonObject) {
            bookJson.remove("readConfig")
            return
        }
        sanitizeReadConfigJson(readConfig.asJsonObject)
    }

    private fun sanitizeReadConfigJson(readConfig: JsonObject) {
        val startDate = readConfig.get("startDate") ?: return
        if (startDate.isJsonPrimitive && startDate.asJsonPrimitive.isString) {
            val legacyStartDate = runCatching {
                JsonParser.parseString(startDate.asString)
            }.getOrNull()
            if (legacyStartDate?.isJsonObject == true && legacyStartDate.asJsonObject.isValidLocalDateJson()) {
                readConfig.add("startDate", legacyStartDate)
            } else {
                readConfig.remove("startDate")
            }
        } else if (startDate.isJsonObject && !startDate.asJsonObject.isValidLocalDateJson()) {
            readConfig.remove("startDate")
        }
    }

    private fun JsonObject.isValidLocalDateJson(): Boolean {
        val year = getIntOrNull("year") ?: return true
        val month = getIntOrNull("month") ?: return true
        val day = getIntOrNull("day") ?: return true
        return year > 0 && month in 1..12 && day in 1..31
    }

    private fun JsonObject.getIntOrNull(name: String): Int? {
        val value = get(name)?.takeIf { it.isJsonPrimitive } ?: return null
        return runCatching { value.asInt }.getOrNull()
    }

    private fun restoreBackgroundAssets(path: String) {
        backgroundAssetDirNames.forEach { dirName ->
            val sourceDir = File(path, dirName)
            if (!sourceDir.exists() || !sourceDir.isDirectory) return@forEach
            val targetDir = appCtx.externalFiles.getFile(dirName)
            kotlin.runCatching {
                FileUtils.delete(targetDir, deleteRootDir = true)
                copyDir(sourceDir, targetDir)
            }.onFailure {
                AppLog.put("恢复背景图片出错 $dirName\n${it.localizedMessage}", it)
            }
        }
    }

    private fun restoreThemePackages(path: String) {
        val sourceDir = File(path, "themePackages")
        if (!sourceDir.exists() || !sourceDir.isDirectory) return
        val targetDir = ThemePackageManager.rootDir
        kotlin.runCatching {
            BackupThemePackageDedupe.restoreThemePackageFonts(File(path))
            FileUtils.delete(targetDir, deleteRootDir = true)
            copyDir(sourceDir, targetDir)
        }.onFailure {
            AppLog.put("恢复主题包出错\n${it.localizedMessage}", it)
        }
    }

    private fun restoreNavigationIcons(path: String) {
        val sourceDir = File(path, "navigationBarPackages")
        if (!sourceDir.exists() || !sourceDir.isDirectory) return
        val targetDir = NavigationBarIconConfig.rootDir
        kotlin.runCatching {
            FileUtils.delete(targetDir, deleteRootDir = true)
            copyDir(sourceDir, targetDir)
        }.onFailure {
            AppLog.put("恢复导航栏图标出错\n${it.localizedMessage}", it)
        }
    }

    private fun normalizeBackgroundPrefs() {
        val edit = appCtx.defaultSharedPreferences.edit()
        var changed = false
        backgroundAssetDirNames.forEach { key ->
            val current = appCtx.getPrefString(key) ?: return@forEach
            if (current.isBlank() || current.startsWith("http")) return@forEach
            if (File(current).exists()) return@forEach
            val fileName = File(current).name.takeIf { it.isNotBlank() } ?: return@forEach
            val restoredFile = appCtx.externalFiles.getFile(key, fileName)
            if (restoredFile.exists()) {
                edit.putString(key, restoredFile.absolutePath)
                changed = true
            }
        }
        if (changed) {
            edit.commit()
        }
    }

    fun repairLocalCoverPaths() {
        val changedBooks = appDb.bookDao.all.mapNotNull { book ->
            var changed = false
            val coverUrl = normalizeLocalCoverPath(book.coverUrl)
            val customCoverUrl = normalizeLocalCoverPath(book.customCoverUrl)
            if (coverUrl != book.coverUrl) {
                book.coverUrl = coverUrl
                changed = true
            }
            if (customCoverUrl != book.customCoverUrl) {
                book.customCoverUrl = customCoverUrl
                changed = true
            }
            book.takeIf { changed }
        }
        if (changedBooks.isNotEmpty()) {
            appDb.bookDao.update(*changedBooks.toTypedArray())
        }

        val changedGroups = appDb.bookGroupDao.all.mapNotNull { group ->
            val cover = normalizeLocalCoverPath(group.cover)
            if (cover != group.cover) {
                group.cover = cover
                group
            } else {
                null
            }
        }
        if (changedGroups.isNotEmpty()) {
            appDb.bookGroupDao.update(*changedGroups.toTypedArray())
        }
    }

    fun normalizeLocalCoverPath(path: String?): String? {
        if (path.isNullOrBlank() ||
            path.startsWith("http", ignoreCase = true) ||
            path.isContentScheme()
        ) {
            return path
        }
        if (!path.contains(File.separator)) {
            return path
        }
        if (path.startsWith(appCtx.externalFiles.absolutePath) && File(path).exists()) {
            return path
        }
        val fileName = File(path).name.takeIf { it.isNotBlank() } ?: return path
        val restoredFile = appCtx.externalFiles.getFile("covers", fileName)
        if (restoredFile.exists()) {
            return restoredFile.absolutePath
        }
        return path
    }

    /**
     * 恢复后把阅读记录头像配置里的绝对路径修正到当前应用目录，
     * 找不到对应文件时保留原路径，不置空
     */
    private fun normalizeReadRecordGoalAvatar() {
        val raw = appCtx.getPrefString(PreferKey.readRecordGoalConfig) ?: return
        if (raw.isBlank()) return
        val json = runCatching { JsonParser.parseString(raw) }
            .getOrNull()
            ?.takeIf { it.isJsonObject }
            ?: return
        val avatarElement = json.asJsonObject.get("avatar") ?: return
        if (!avatarElement.isJsonPrimitive) return
        val avatar = avatarElement.asString
        if (avatar.isNullOrBlank() ||
            avatar.startsWith("http", ignoreCase = true) ||
            avatar.isContentScheme()
        ) {
            return
        }
        if (!avatar.contains(File.separator)) return
        val fileName = File(avatar).name.takeIf { it.isNotBlank() } ?: return
        val restoredFile = appCtx.externalFiles.getFile("readRecordGoalAvatar", fileName)
        if (!restoredFile.exists()) return
        json.asJsonObject.addProperty("avatar", restoredFile.absolutePath)
        appCtx.defaultSharedPreferences.edit()
            .putString(PreferKey.readRecordGoalConfig, json.toString())
            .commit()
    }

    private fun normalizeStringPrefs() {
        val stringKeys = setOf(
            PreferKey.language,
            PreferKey.themeMode,
            PreferKey.userAgent,
            PreferKey.customHosts,
            PreferKey.bookGroupStyle,
            PreferKey.bookshelfHiddenTags,
            PreferKey.bookshelfGroupTags,
            PreferKey.ttsEngine,
            PreferKey.prevKeys,
            PreferKey.nextKeys,
            PreferKey.mergedDiscoveryRssTarget,
            PreferKey.modernDiscoverySourceUrl,
            PreferKey.modernRssSourceUrl,
            PreferKey.aiProviderList,
            PreferKey.aiCurrentProviderId,
            PreferKey.aiModelConfigList,
            PreferKey.aiCurrentModelId,
            PreferKey.themePackageSyncTasks,
            PreferKey.aiTavilySearchDepth,
            PreferKey.aiTavilyTopic,
            PreferKey.aiBaseUrl,
            PreferKey.aiApiKey,
            PreferKey.aiCurrentModel,
            PreferKey.aiModelList,
            PreferKey.bookshelfLayout,
            PreferKey.bookshelfSort,
            PreferKey.bookExportFileName,
            PreferKey.bookImportFileName,
            PreferKey.episodeExportFileName,
            PreferKey.fontFolder,
            PreferKey.backupPath,
            PreferKey.webDavUrl,
            PreferKey.webDavAccount,
            PreferKey.webDavPassword,
            PreferKey.webDavDir,
            PreferKey.exportType,
            PreferKey.chineseConverterType,
            PreferKey.launcherIcon,
            PreferKey.systemTypefaces,
            PreferKey.uiFontPath,
            PreferKey.titleFontPath,
            PreferKey.bottomBarEffectMode,
            PreferKey.bottomBarLayoutMode,
            PreferKey.bottomBarSidebarGravity,
            PreferKey.uiCornerScale,
            PreferKey.uiCornerEffectMode,
            PreferKey.defaultCover,
            PreferKey.defaultCoverDark,
            PreferKey.screenOrientation,
            PreferKey.exportCharset,
            PreferKey.mangaFooterConfig,
            PreferKey.mangaColorFilter,
            PreferKey.contentSelectMenuConfig,
            PreferKey.contentSelectActions,
            PreferKey.contentSelectActionsOrder,
            PreferKey.contentSelectDefaultOpen,
            PreferKey.advancedTitleConfig,
            PreferKey.advancedTitleLottieJson,
            PreferKey.advancedTitleLottiePath,
            PreferKey.doublePageHorizontal,
            PreferKey.defaultBookTreeUri,
            PreferKey.readRecordComponents,
            PreferKey.readRecordRecentSnapshots,
            PreferKey.readRecordGoalConfig,
            PreferKey.localBookImportSort,
            PreferKey.progressBarBehavior,
            PreferKey.webDavDeviceName,
            PreferKey.defaultHomePage,
            PreferKey.clickImgWay,
            PreferKey.bottomWebViewDialogHeight,
            PreferKey.dThemeName,
            PreferKey.dNThemeName,
            PreferKey.bgImage,
            PreferKey.bookInfoBgImage,
            PreferKey.bgImageN,
            PreferKey.bookInfoBgImageN,
            "navigationBarPackageDay",
            "navigationBarPackageNight"
        )
        val all = appCtx.defaultSharedPreferences.all
        val edit = appCtx.defaultSharedPreferences.edit()
        var changed = false
        stringKeys.forEach { key ->
            val value = all[key] ?: return@forEach
            if (value !is String) {
                edit.putString(key, value.toString())
                changed = true
            }
        }
        if (changed) {
            edit.commit()
        }
    }

    private fun copyDir(source: File, target: File) {
        if (!target.exists()) {
            target.mkdirs()
        }
        source.listFiles()?.forEach { file ->
            val targetFile = File(target, file.name)
            if (file.isDirectory) {
                copyDir(file, targetFile)
            } else {
                targetFile.parentFile?.mkdirs()
                file.copyTo(targetFile, overwrite = true)
            }
        }
    }

}
