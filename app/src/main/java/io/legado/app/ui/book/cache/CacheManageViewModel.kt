package io.legado.app.ui.book.cache

import android.app.Application
import android.os.Build
import android.net.Uri
import android.system.Os
import androidx.annotation.StringRes
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.bumptech.glide.Glide
import io.legado.app.R
import io.legado.app.base.BaseViewModel
import io.legado.app.constant.BookType
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.help.exoplayer.ExoPlayerHelper
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.CacheBookManifest
import io.legado.app.help.book.CacheManifestHelper
import io.legado.app.help.book.getBookSource
import io.legado.app.help.book.isAudio
import io.legado.app.help.book.isImage
import io.legado.app.help.book.isLocal
import io.legado.app.help.book.isNotShelf
import io.legado.app.help.book.isType
import io.legado.app.help.book.isVideo
import io.legado.app.help.book.removeType
import io.legado.app.model.CacheBook
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.model.analyzeRule.AnalyzeUrl.Companion.getMediaRequest
import io.legado.app.model.webBook.WebBook
import io.legado.app.utils.FileUtils
import io.legado.app.utils.GSON
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.externalCache
import io.legado.app.utils.externalFiles
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.normalizeFileName
import io.legado.app.utils.compress.ZipUtils
import io.legado.app.utils.isJsonArray
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import splitties.init.appCtx
import java.io.File
import java.util.Locale

class CacheManageViewModel(application: Application) : BaseViewModel(application) {

    val itemsLiveData = MutableLiveData<List<CacheBookItem>>()
    val summaryLiveData = MutableLiveData<CacheSummary>()
    val loadingLiveData = MutableLiveData<Boolean>()

    private var loadJob: Job? = null
    private val selectedSourceKeys = hashMapOf<String, String>()
    private var sizeJob: Job? = null
    var mode: CacheManageMode = CacheManageMode.BOOK
        private set

    fun isLoading(): Boolean = loadJob?.isActive == true

    fun load(mode: CacheManageMode = this.mode) {
        this.mode = mode
        loadJob?.cancel()
        sizeJob?.cancel()
        lateinit var job: Job
        job = viewModelScope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
            loadingLiveData.postValue(true)
            try {
                val currentBooks = getBooks(mode)
                val currentBookUrls = currentBooks.mapTo(hashSetOf()) { it.bookUrl }
                val cacheDirs = CacheManifestHelper.listCacheDirs()
                val manifests = CacheManifestHelper.listManifests(cacheDirs)
                val manifestByBookUrl = manifests.associateBy { it.bookUrl }
                val currentItems = currentBooks
                    .asSequence()
                    .mapNotNull { book ->
                        buildCacheBookItem(
                            book = book,
                            mode = mode,
                            knownManifest = manifestByBookUrl[book.bookUrl]
                        )
                    }
                    .toList()
                val manifestItems = manifests
                    .asSequence()
                    .filter { it.matches(mode) }
                    .filterNot { currentBookUrls.contains(it.bookUrl) }
                    .mapNotNull { manifest -> buildCacheBookItem(manifest, mode) }
                    .toList()
                val items = groupByBook(currentItems + manifestItems)
                ensureActive()
                itemsLiveData.postValue(items)
                startSizeUpdateJob(items, mode)
                summaryLiveData.postValue(
                    CacheSummary(
                        bookCount = items.size,
                        cachedChapterCount = items.sumOf { it.cachedCount },
                        currentModeSize = 0L,
                        totalCacheSize = getAppStorageSize(),
                        storageDetails = emptyList(),
                        mode = mode
                    )
                )
            } catch (e: CancellationException) {
                throw e
            } finally {
                if (loadJob === job) {
                    loadingLiveData.postValue(false)
                }
            }
        }
        loadJob = job
        job.start()
    }

    private fun startSizeUpdateJob(items: List<CacheBookItem>, mode: CacheManageMode) {
        sizeJob?.cancel()
        sizeJob = viewModelScope.launch(Dispatchers.IO) {
            var updatedItems = items
            items.forEach { item ->
                ensureActive()
                val updated = item.withStorageCalculated()
                updatedItems = updatedItems.replaceGroupItem(updated)
                itemsLiveData.postValue(updatedItems)
            }
            val storageBreakdown = buildStorageBreakdown()
            summaryLiveData.postValue(
                CacheSummary(
                    bookCount = updatedItems.size,
                    cachedChapterCount = updatedItems.sumOf { it.cachedCount },
                    currentModeSize = updatedItems.sumOf { it.storageSizeBytes },
                    totalCacheSize = getAppStorageSize(),
                    storageDetails = storageBreakdown,
                    mode = mode
                )
            )
        }
    }

    fun loadStats() {
        viewModelScope.launch(Dispatchers.IO) {
            val storageBreakdown = buildStorageBreakdown()
            summaryLiveData.postValue(
                CacheSummary(
                    bookCount = 0,
                    cachedChapterCount = 0,
                    currentModeSize = 0L,
                    totalCacheSize = getAppStorageSize(),
                    storageDetails = storageBreakdown,
                    mode = mode
                )
            )
        }
    }

    fun deleteStorageDetail(target: CacheStorageDeleteTarget, onDone: () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            deleteStorageTarget(target)
            val storageBreakdown = buildStorageBreakdown()
            summaryLiveData.postValue(
                CacheSummary(
                    bookCount = 0,
                    cachedChapterCount = 0,
                    currentModeSize = 0L,
                    totalCacheSize = getAppStorageSize(),
                    storageDetails = storageBreakdown,
                    mode = mode
                )
            )
            withContext(Dispatchers.Main) {
                onDone()
            }
        }
    }

    fun selectSource(groupKey: String, sourceKey: String) {
        selectedSourceKeys[groupKey] = sourceKey
        load()
    }

    fun deleteBookCache(book: Book, onDone: () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            deleteMediaCache(book)
            ExoPlayerHelper.releaseBookCaches(book)
            BookHelp.clearCache(book)
            CacheManifestHelper.delete(book)
            withContext(Dispatchers.Main) {
                onDone()
            }
            load(mode)
        }
    }

    fun deleteBookCaches(books: List<Book>, onDone: () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            books.forEach {
                deleteMediaCache(it)
                ExoPlayerHelper.releaseBookCaches(it)
                BookHelp.clearCache(it)
                CacheManifestHelper.delete(it)
            }
            withContext(Dispatchers.Main) {
                onDone()
            }
            load(mode)
        }
    }

    suspend fun getChapterItems(book: Book, key: String? = null): List<CacheChapterItem> {
        return getChapterItems(book, key, CacheChapterFilter.ALL)
    }

    suspend fun getChapterItems(
        book: Book,
        key: String? = null,
        filter: CacheChapterFilter = CacheChapterFilter.ALL
    ): List<CacheChapterItem> {
        return withContext(Dispatchers.IO) {
            val cacheNames = if (book.isMedia) emptySet() else getCacheFileNames(book)
            val manifest = CacheManifestHelper.read(book)
            val dbChapters = if (key.isNullOrBlank()) {
                appDb.bookChapterDao.getChapterList(book.bookUrl)
            } else {
                appDb.bookChapterDao.search(book.bookUrl, key)
            }
            if (book.isMedia && CacheManifestHelper.mergeResourceUrls(dbChapters, manifest)) {
                appDb.bookChapterDao.update(*dbChapters.toTypedArray())
            }
            val chapters = dbChapters.takeIf { it.isNotEmpty() }
                ?: CacheManifestHelper.toChapters(manifest ?: return@withContext emptyList())
                    .filterByKey(key)
            chapters
                .asSequence()
                .filterNot { it.isVolume }
                .mapNotNull { chapter ->
                    val cached = isChapterCached(
                        book,
                        chapter,
                        cacheNames,
                        validateImageContent = false
                    )
                    when (filter) {
                        CacheChapterFilter.CACHED -> if (!cached) return@mapNotNull null
                        CacheChapterFilter.UNCACHED -> if (cached) return@mapNotNull null
                        CacheChapterFilter.ALL -> Unit
                    }
                    CacheChapterItem(chapter = chapter, cached = cached)
                }
                .toList()
        }
    }

    suspend fun deleteChapterCache(book: Book, chapter: BookChapter) {
        deleteChapterCaches(book, listOf(chapter))
    }

    suspend fun deleteChapterCaches(book: Book, chapters: List<BookChapter>) {
        withContext(Dispatchers.IO) {
            if (chapters.isEmpty()) return@withContext
            chapters.forEach { chapter ->
                if (book.isMedia) {
                if (book.isVideo) {
                    ExoPlayerHelper.removeVideoCache(chapter.resourceUrl, book)
                } else {
                    ExoPlayerHelper.removeMediaCache(chapter.resourceUrl, book)
                }
                }
                BookHelp.delChapterCache(book, chapter)
            }
            refreshManifest(book)
        }
    }

    fun cacheBookChapters(book: Book, chapters: List<BookChapter>): Int {
        if (book.isMedia || book.isLocal) return 0
        val indexes = chapters
            .asSequence()
            .filterNot { it.isVolume }
            .map { it.index }
            .distinct()
            .sorted()
            .toList()
        if (indexes.isEmpty()) return 0
        indexes.toRanges().forEach { (start, end) ->
            CacheBook.start(appCtx, book, start, end)
        }
        return indexes.size
    }

    suspend fun cacheAudioChapters(
        book: Book,
        chapters: List<BookChapter>,
        reloadOnFinished: Boolean = true
    ): Int {
        return cacheMediaChapters(book, chapters, reloadOnFinished)
    }

    suspend fun cacheMediaChapters(
        book: Book,
        chapters: List<BookChapter>,
        reloadOnFinished: Boolean = true
    ): Int {
        if (!book.isMedia) return 0
        val targets = withContext(Dispatchers.IO) {
            val realChapters = chapters
                .asSequence()
                .filterNot { it.isVolume }
                .toList()
            if (CacheManifestHelper.mergeResourceUrls(realChapters, CacheManifestHelper.read(book))) {
                appDb.bookChapterDao.update(*realChapters.toTypedArray())
            }
            realChapters
                .asSequence()
                .filterNot { isChapterCached(book, it) }
                .toList()
        }
        if (targets.isEmpty()) return 0
        val started = AudioCacheTaskManager.start(
            book = book,
            chapters = targets,
            resolver = ::resolveMediaRequest,
            onChapterResolved = { chapter, request ->
                if (chapter.resourceUrl != request.url) {
                    chapter.resourceUrl = request.url
                    appDb.bookChapterDao.update(chapter)
                }
            },
            onFinished = {
                refreshManifest(book)
                if (reloadOnFinished && mode == book.cacheManageMode) {
                    load(mode)
                }
            }
        )
        if (started && mode == book.cacheManageMode) {
            load(mode)
        }
        return if (started) targets.size else 0
    }

    suspend fun restoreCacheToBookshelf(item: CacheBookItem): Boolean {
        return withContext(Dispatchers.IO) {
            val manifest = item.manifest ?: CacheManifestHelper.read(item.book) ?: return@withContext false
            val sameUrlBook = appDb.bookDao.getBook(manifest.bookUrl)
            val sameNameBook = appDb.bookDao.getBook(manifest.name, manifest.author)
            val cacheBook = CacheManifestHelper.toBook(manifest).apply {
                removeType(BookType.notShelf)
                sameUrlBook?.let {
                    group = it.group
                    order = it.order
                    durChapterIndex = it.durChapterIndex
                    durChapterTitle = it.durChapterTitle
                    durChapterPos = it.durChapterPos
                    readConfig = it.readConfig
                } ?: sameNameBook?.let {
                    group = it.group
                    order = it.order
                    durChapterIndex = it.durChapterIndex
                    durChapterTitle = it.durChapterTitle
                    durChapterPos = it.durChapterPos
                    readConfig = it.readConfig
                }
            }
            when {
                sameUrlBook != null -> appDb.bookDao.update(cacheBook)
                sameNameBook != null -> appDb.bookDao.replace(sameNameBook, cacheBook)
                else -> appDb.bookDao.insert(cacheBook)
            }
            val chapters = CacheManifestHelper.toChapters(manifest, cacheBook.bookUrl)
            if (chapters.isNotEmpty()) {
                appDb.bookChapterDao.delByBook(cacheBook.bookUrl)
                appDb.bookChapterDao.insert(*chapters.toTypedArray())
            }
            true
        }
    }

    suspend fun createCachePackage(book: Book): File {
        return withContext(Dispatchers.IO) {
            val cacheDir = BookHelp.getCacheDir(book)
            val outDir = File(appCtx.externalCache, "cache_package").apply {
                if (!exists()) mkdirs()
            }
            val fileName = "${book.name}_${book.author}_${System.currentTimeMillis()}"
                .normalizeFileName()
                .ifBlank { "cache_${System.currentTimeMillis()}" }
            val zipFile = File(outDir, "$fileName.zip").apply {
                if (exists()) delete()
            }
            if (book.isMedia) {
                return@withContext createMediaCachePackage(book, cacheDir, outDir, fileName, zipFile)
            }
            if (!cacheDir.exists() || cacheDir.listFiles().isNullOrEmpty()) {
                throw IllegalStateException(context.getString(R.string.cache_manage_no_cache))
            }
            if (!ZipUtils.zipFile(cacheDir, zipFile) || !zipFile.exists() || zipFile.length() <= 0L) {
                throw IllegalStateException(context.getString(R.string.cache_manage_pack_failed))
            }
            zipFile
        }
    }

    private fun createMediaCachePackage(
        book: Book,
        cacheDir: File,
        outDir: File,
        fileName: String,
        zipFile: File
    ): File {
        val packageDir = File(outDir, "${fileName}_media").apply {
            if (exists()) deleteRecursively()
            mkdirs()
        }
        var hasCache = false
        if (cacheDir.exists() && !cacheDir.listFiles().isNullOrEmpty()) {
            cacheDir.copyRecursively(File(packageDir, "chapter_cache"), overwrite = true)
            hasCache = true
        }
        val mediaDir = File(packageDir, "media_cache").apply { mkdirs() }
        val chapters = (appDb.bookChapterDao.getChapterList(book.bookUrl)
            .takeIf { it.isNotEmpty() }
            ?: CacheManifestHelper.read(book)?.let(CacheManifestHelper::toChapters).orEmpty())
            .filterNot { it.isVolume }
            .mapNotNull { chapter ->
                val chapterDir = File(mediaDir, chapter.index.toString())
                if (!isChapterCached(book, chapter)) {
                    chapterDir.deleteRecursively()
                    return@mapNotNull null
                }
                val fileCount = if (book.isVideo) {
                    ExoPlayerHelper.copyVideoCache(chapter.resourceUrl, chapterDir, book)
                } else {
                    ExoPlayerHelper.copyMediaCache(chapter.resourceUrl, chapterDir, book)
                }
                if (fileCount <= 0) {
                    chapterDir.deleteRecursively()
                    return@mapNotNull null
                }
                hasCache = true
                MediaCacheManifest.Chapter(
                    index = chapter.index,
                    title = chapter.title,
                    url = chapter.url,
                    resourceUrl = chapter.resourceUrl,
                    fileCount = fileCount
                )
            }
        if (!hasCache) {
            packageDir.deleteRecursively()
            throw IllegalStateException(context.getString(R.string.cache_manage_no_cache))
        }
        File(packageDir, "manifest.json").writeText(
            GSON.toJson(
                MediaCacheManifest(
                    bookName = book.name,
                    author = book.author,
                    bookUrl = book.bookUrl,
                    chapters = chapters
                )
            )
        )
        val success = ZipUtils.zipFile(packageDir, zipFile)
        packageDir.deleteRecursively()
        if (!success || !zipFile.exists() || zipFile.length() <= 0L) {
            throw IllegalStateException(context.getString(R.string.cache_manage_pack_failed))
        }
        return zipFile
    }

    private fun groupByBook(items: List<CacheBookItem>): List<CacheBookItem> {
        return items
            .groupBy { it.groupKey }
            .values
            .mapNotNull { group ->
                val variants = group
                    .sortedWith(
                        compareByDescending<CacheBookItem> { if (it.taskState.isVisibleAudioTask()) 1 else 0 }
                            .thenByDescending { it.cachedCount }
                            .thenByDescending { it.storageSizeBytes }
                            .thenBy { it.sourceName }
                    )
                    .map { it.toSourceVariant() }
                val groupKey = group.firstOrNull()?.groupKey ?: return@mapNotNull null
                val selectedKey = selectedSourceKeys[groupKey]
                val selected = group.firstOrNull { it.sourceKey == selectedKey }
                    ?: group.firstOrNull { it.taskState.isVisibleAudioTask() }
                    ?: group.maxWithOrNull(
                        compareBy<CacheBookItem> { it.cachedCount }
                            .thenBy { it.storageSizeBytes }
                            .thenBy { it.totalChapterCount }
                    )
                    ?: group.first()
                selected.copy(sourceVariants = variants)
            }
            .sortedWith(
                compareByDescending<CacheBookItem> { it.cachedCount }
                    .thenByDescending { it.storageSizeBytes }
                    .thenBy { it.book.name }
                    .thenBy { it.sourceName }
            )
    }

    private fun buildCacheBookItem(
        book: Book,
        mode: CacheManageMode,
        knownManifest: CacheBookManifest? = null
    ): CacheBookItem? {
        val taskState = AudioCacheTaskManager.snapshot(book.bookUrl)
        if (mode.isMedia) {
            return buildMediaCacheBookItem(book, mode, knownManifest, taskState)
        }
        if (knownManifest == null) return null
        val cacheNames = getCacheFileNames(book)
        val needsChapterList = book.totalChapterNum <= 0 || book.isNotShelf
        val manifest = knownManifest
        val dbChapters = if (needsChapterList) {
            appDb.bookChapterDao.getChapterList(book.bookUrl)
        } else {
            emptyList()
        }
        val chapters = dbChapters.takeIf { it.isNotEmpty() }
            ?: CacheManifestHelper.toChapters(manifest)
        val rawCachedCount = getFastCachedCount(cacheNames)
        val totalChapterCount = book.totalChapterNum.takeIf { it > 0 }
            ?: chapters.size.takeIf { it > 0 }
            ?: rawCachedCount
        val cachedCount = rawCachedCount.coerceAtMost(totalChapterCount)
        return CacheBookItem(
            book = book,
            mode = mode,
            groupKey = book.cacheGroupKey(mode),
            sourceKey = book.cacheSourceKey(),
            sourceName = book.cacheSourceName(),
            cachedCount = cachedCount,
            totalChapterCount = totalChapterCount,
            taskState = taskState,
            manifest = manifest,
            inBookshelf = !book.isNotShelf,
            sourceAvailable = book.isLocal || book.getBookSource() != null
        )
    }

    private fun buildMediaCacheBookItem(
        book: Book,
        mode: CacheManageMode,
        initialManifest: CacheBookManifest?,
        taskState: AudioCacheTaskState?
    ): CacheBookItem? {
        val manifest = initialManifest
        if (manifest == null) return null
        val candidateCachedIndexes = manifest.cachedIndexes()
        val chapters = CacheManifestHelper.toChapters(manifest)
        val realCachedCount = getMediaCachedCount(book, chapters, candidateCachedIndexes)
        val taskCompletedCount = taskState?.completedChapters ?: 0
        val rawCachedCount = maxOf(realCachedCount, taskCompletedCount)
        val totalChapterCount = book.totalChapterNum.takeIf { it > 0 }
            ?: manifest.totalChapterNum.takeIf { it > 0 }
            ?: chapters.size.takeIf { it > 0 }
            ?: taskState?.totalChapters?.takeIf { it > 0 }
            ?: rawCachedCount.coerceAtLeast(1)
        val cachedCount = rawCachedCount.coerceAtMost(totalChapterCount)
        return CacheBookItem(
            book = book,
            mode = mode,
            groupKey = book.cacheGroupKey(mode),
            sourceKey = book.cacheSourceKey(),
            sourceName = book.cacheSourceName(),
            cachedCount = cachedCount,
            totalChapterCount = totalChapterCount,
            taskState = taskState,
            manifest = manifest,
            inBookshelf = !book.isNotShelf,
            sourceAvailable = book.isLocal || book.getBookSource() != null
        )
    }

    private fun buildCacheBookItem(
        manifest: CacheBookManifest,
        mode: CacheManageMode
    ): CacheBookItem? {
        val book = CacheManifestHelper.toBook(manifest)
        val chapters = CacheManifestHelper.toChapters(manifest)
        val cacheNames = getCacheFileNames(book)
        val rawCachedCount = if (mode.isMedia) {
            getMediaCachedCount(book, chapters, manifest.cachedIndexes())
        } else {
            chapters.count {
                isChapterCached(book, it, cacheNames, validateImageContent = false)
            }
        }
        val totalChapterCount = manifest.totalChapterNum.takeIf { it > 0 }
            ?: chapters.size.takeIf { it > 0 }
            ?: rawCachedCount.coerceAtLeast(1)
        return CacheBookItem(
            book = book,
            mode = mode,
            groupKey = book.cacheGroupKey(mode),
            sourceKey = book.cacheSourceKey(),
            sourceName = book.cacheSourceName(),
            cachedCount = rawCachedCount.coerceAtMost(totalChapterCount),
            totalChapterCount = totalChapterCount,
            manifest = manifest,
            inBookshelf = false,
            sourceAvailable = book.isLocal || book.getBookSource() != null
        )
    }

    private fun getFastCachedCount(cacheNames: Set<String>): Int {
        return cacheNames.count { it.endsWith(".nb") }
    }

    private fun getMediaCachedCount(book: Book, chapters: List<BookChapter>): Int {
        return getMediaCachedCount(book, chapters, cachedIndexes = null)
    }

    private fun getMediaCachedCount(
        book: Book,
        chapters: List<BookChapter>,
        cachedIndexes: Set<Int>? = null
    ): Int {
        return chapters
            .asSequence()
            .filterNot { it.isVolume }
            .filter { cachedIndexes == null || it.index in cachedIndexes }
            .count { isChapterCached(book, it) }
    }

    private fun getBookStorage(
        book: Book,
        chapters: List<BookChapter> = emptyList()
    ): CacheBookStorage {
        val cacheDir = BookHelp.getCacheDir(book)
        return CacheBookStorage(
            totalBytes = cacheDir.directorySize(),
            displayText = ""
        )
    }

    private fun CacheBookItem.withStorageCalculated(): CacheBookItem {
        val updatedVariants = sourceVariants.map { variant ->
            val storage = getBookStorage(variant.book, variant.manifest?.let(CacheManifestHelper::toChapters).orEmpty())
            variant.copy(
                storageSizeBytes = storage.totalBytes,
                storageSummary = storage.displayText,
                storageCalculated = true
            )
        }
        val selfStorage = if (sourceVariants.isEmpty()) {
            getBookStorage(book, manifest?.let(CacheManifestHelper::toChapters).orEmpty())
        } else {
            updatedVariants.firstOrNull { it.sourceKey == sourceKey }?.let {
                CacheBookStorage(it.storageSizeBytes, it.storageSummary)
            } ?: getBookStorage(book, manifest?.let(CacheManifestHelper::toChapters).orEmpty())
        }
        return copy(
            storageSizeBytes = selfStorage.totalBytes,
            storageSummary = selfStorage.displayText,
            storageCalculated = true,
            sourceVariants = updatedVariants
        )
    }

    private fun List<CacheBookItem>.replaceGroupItem(item: CacheBookItem): List<CacheBookItem> {
        return map { current ->
            if (current.groupKey == item.groupKey) item else current
        }
    }

    private fun buildStorageBreakdown(): List<CacheStorageDetail> {
        val internalCache = appCtx.cacheDir
        val externalCache = appCtx.externalCache
        val codeCache = appCtx.codeCacheDir
        val dataDir = File(appCtx.applicationInfo.dataDir)
        val filesDir = appCtx.filesDir
        val externalFiles = appCtx.externalFiles
        val knownInternal = listOf(
            CacheStorageGroup(
                context.getString(R.string.cache_manage_storage_cover_thumbs),
                listOf(File(internalCache, "cover_thumbs_v2")),
                CacheStorageDeleteTarget.COVER_THUMBS
            ),
            CacheStorageGroup(
                context.getString(R.string.cache_manage_storage_discover_rss),
                listOf(File(internalCache, "ACache"), File(filesDir, "ACache")),
                CacheStorageDeleteTarget.DISCOVER_RSS
            ),
            CacheStorageGroup(
                context.getString(R.string.cache_manage_storage_glide),
                listOf(File(internalCache, "image_manager_disk_cache")),
                CacheStorageDeleteTarget.GLIDE
            ),
            CacheStorageGroup(
                context.getString(R.string.cache_manage_storage_webview),
                listOf(
                    File(dataDir, "app_webview"),
                    File(internalCache, "WebView"),
                    File(codeCache, "com.android.webview"),
                    File(codeCache, "WebView")
                ),
                CacheStorageDeleteTarget.WEBVIEW
            ),
            CacheStorageGroup(
                context.getString(R.string.cache_manage_storage_share_js),
                listOf(File(internalCache, "shareJs")),
                CacheStorageDeleteTarget.SHARE_JS
            ),
            CacheStorageGroup(
                context.getString(R.string.cache_manage_storage_tts),
                listOf(File(internalCache, "httpTTS"), File(internalCache, "httpTTS_cache")),
                CacheStorageDeleteTarget.TTS
            ),
            CacheStorageGroup(
                context.getString(R.string.cache_manage_storage_epub_temp),
                listOf(File(internalCache, "epub-fonts"), File(internalCache, "epub-debug")),
                CacheStorageDeleteTarget.EPUB_TEMP
            ),
            CacheStorageGroup(
                context.getString(R.string.cache_manage_storage_image_temp),
                listOf(
                    File(internalCache, "tmp"),
                    File(internalCache, "image_crop_source"),
                    File(externalCache, "qr.png")
                ),
                CacheStorageDeleteTarget.IMAGE_TEMP
            ),
            CacheStorageGroup(
                context.getString(R.string.cache_manage_storage_so_download),
                listOf(File(internalCache, "so_download")),
                CacheStorageDeleteTarget.SO_DOWNLOAD
            )
        )
        val knownExternal = listOf(
            CacheStorageGroup(
                context.getString(R.string.cache_manage_storage_audio_offline),
                listOf(File(externalCache, "audio_exoplayer"), File(externalCache, "audio_exoplayer_complete")),
                CacheStorageDeleteTarget.AUDIO
            ),
            CacheStorageGroup(
                context.getString(R.string.cache_manage_storage_video_preload),
                listOf(File(externalCache, "exoplayer"), File(externalCache, "exoplayer_complete")),
                CacheStorageDeleteTarget.VIDEO
            ),
            CacheStorageGroup(
                context.getString(R.string.cache_manage_storage_player_temp),
                listOf(File(externalCache, "video_temp"), File(externalCache, "video_temp_cache")),
                CacheStorageDeleteTarget.PLAYER_TEMP
            ),
            CacheStorageGroup(
                context.getString(R.string.cache_manage_storage_cache_package),
                listOf(File(externalCache, "cache_package")),
                CacheStorageDeleteTarget.CACHE_PACKAGE
            ),
            CacheStorageGroup(
                context.getString(R.string.cache_manage_storage_read_config),
                listOf(File(externalCache, "readConfig"), File(externalCache, "readConfig.zip")),
                CacheStorageDeleteTarget.READ_CONFIG
            ),
            CacheStorageGroup(
                context.getString(R.string.cache_manage_storage_upload_temp),
                listOf(File(externalCache, "upload")),
                CacheStorageDeleteTarget.UPLOAD
            ),
            CacheStorageGroup(
                context.getString(R.string.cache_manage_storage_import_temp),
                listOf(File(externalCache, "download")),
                CacheStorageDeleteTarget.IMPORT_TEMP
            ),
            CacheStorageGroup(
                context.getString(R.string.cache_manage_storage_logs),
                listOf(
                    File(externalCache, "logs"),
                    File(externalCache, "crash"),
                    File(externalCache, "logcat.txt"),
                    File(externalCache, "logs.zip"),
                    File(externalCache, "heapDump")
                ),
                CacheStorageDeleteTarget.LOGS
            ),
            CacheStorageGroup(
                context.getString(R.string.cache_manage_storage_archive_temp),
                listOf(File(externalCache, "ArchiveTemp")),
                CacheStorageDeleteTarget.ARCHIVE_TEMP
            ),
            CacheStorageGroup(
                context.getString(R.string.cache_manage_storage_cronet_cache),
                listOf(File(externalCache, "app_cronet")),
                CacheStorageDeleteTarget.CRONET_CACHE
            )
        )
        val knownExternalFiles = listOf(
            CacheStorageGroup(
                context.getString(R.string.cache_manage_storage_book_source_data),
                listOf(File(externalFiles, "ruleData/book"))
            ),
            CacheStorageGroup(
                context.getString(R.string.cache_manage_storage_rss_source_data),
                listOf(File(externalFiles, "ruleData/rss"))
            ),
            CacheStorageGroup(
                context.getString(R.string.cache_manage_storage_theme_packages),
                listOf(File(externalFiles, "themePackages"), File(externalFiles, "themePackageImports"))
            ),
            CacheStorageGroup(
                context.getString(R.string.cache_manage_storage_read_style_assets),
                listOf(
                    File(externalFiles, "font"),
                    File(externalFiles, "bg"),
                    File(externalFiles, PreferKey.bgImage),
                    File(externalFiles, PreferKey.bgImageN),
                    File(externalFiles, PreferKey.bookInfoBgImage),
                    File(externalFiles, PreferKey.bookInfoBgImageN)
                )
            ),
            CacheStorageGroup(
                context.getString(R.string.cache_manage_storage_navigation_assets),
                listOf(
                    File(externalFiles, "navigationBarPackages"),
                    File(externalFiles, "navigationIcons"),
                    File(externalFiles, "navigationBarTemp"),
                    File(externalFiles, "navigationBarImports")
                )
            ),
            CacheStorageGroup(
                context.getString(R.string.cache_manage_storage_cover_files),
                listOf(File(externalFiles, "covers"))
            )
        )
        val internalDetails = knownInternal.map { it.toDetail() }
        val externalDetails = knownExternal.map { it.toDetail() }
        val externalFileDetails = knownExternalFiles.map { it.toDetail() }
        val bookCacheRoot = File(BookHelp.cachePath)
        val epubRoot = File(externalFiles, "epub")
        val userDataDetails = listOf(
            CacheStorageDetail(
                context.getString(R.string.cache_manage_storage_databases),
                File(dataDir, "databases").directorySize()
            ),
            CacheStorageDetail(
                context.getString(R.string.cache_manage_storage_preferences),
                File(dataDir, "shared_prefs").directorySize() + File(dataDir, "datastore").directorySize()
            ),
            CacheStorageDetail(
                context.getString(R.string.cache_manage_storage_internal_files),
                filesDir.childrenSize(excludes = setOf("ACache"))
            ),
            CacheStorageDetail(
                context.getString(R.string.cache_manage_storage_cronet_component),
                File(dataDir, "app_cronet").directorySize()
            )
        )
        val explicitDetails = buildList {
            addAll(getBookCacheStorageDetails(bookCacheRoot))
            add(CacheStorageDetail(context.getString(R.string.cache_manage_storage_local_epub), epubRoot.directorySize()))
            addAll(externalFileDetails)
            addAll(userDataDetails)
            addAll(internalDetails)
            addAll(externalDetails)
            add(
                CacheStorageDetail(
                    context.getString(R.string.cache_manage_storage_other_temp),
                    otherExternalTempFiles().sumOf { it.directorySize() },
                    CacheStorageDeleteTarget.OTHER_TEMP
                )
            )
        }
        val explicitSize = explicitDetails.sumOf { it.bytes }
        val otherSize = (getAppStorageSize() - explicitSize).coerceAtLeast(0L)
        return explicitDetails + CacheStorageDetail(
            context.getString(R.string.cache_manage_storage_other),
            otherSize
        )
    }

    private fun getBookCacheStorageDetails(bookCacheRoot: File): List<CacheStorageDetail> {
        var textSize = 0L
        var audioSize = 0L
        var videoSize = 0L
        var mangaSize = 0L
        var otherSize = 0L
        bookCacheRoot.listFiles()
            ?.asSequence()
            ?.filter { it.isDirectory }
            ?.forEach { cacheDir ->
                val size = cacheDir.directorySize()
                val manifest = CacheManifestHelper.read(File(cacheDir, CacheManifestHelper.MANIFEST_FILE_NAME))
                when {
                    manifest?.matches(CacheManageMode.MANGA) == true -> mangaSize += size
                    manifest?.matches(CacheManageMode.VIDEO) == true -> videoSize += size
                    manifest?.matches(CacheManageMode.AUDIO) == true -> audioSize += size
                    manifest?.matches(CacheManageMode.BOOK) == true -> textSize += size
                    else -> otherSize += size
                }
            }
        return listOf(
            CacheStorageDetail(context.getString(R.string.cache_manage_storage_text_books), textSize),
            CacheStorageDetail(context.getString(R.string.cache_manage_storage_audio_books), audioSize),
            CacheStorageDetail(context.getString(R.string.cache_manage_storage_video_books), videoSize),
            CacheStorageDetail(context.getString(R.string.cache_manage_storage_manga_books), mangaSize),
            CacheStorageDetail(
                context.getString(R.string.cache_manage_storage_other_books),
                otherSize,
                CacheStorageDeleteTarget.TEMP_BOOK_CACHE
            )
        )
    }

    private fun deleteStorageTarget(target: CacheStorageDeleteTarget) {
        val internalCache = appCtx.cacheDir
        val externalCache = appCtx.externalCache
        val filesDir = appCtx.filesDir
        val paths = when (target) {
            CacheStorageDeleteTarget.COVER_THUMBS -> listOf(File(internalCache, "cover_thumbs_v2"))
            CacheStorageDeleteTarget.DISCOVER_RSS -> listOf(File(internalCache, "ACache"), File(filesDir, "ACache"))
            CacheStorageDeleteTarget.GLIDE -> {
                Glide.get(appCtx).clearDiskCache()
                emptyList()
            }
            CacheStorageDeleteTarget.WEBVIEW -> listOf(
                File(File(appCtx.applicationInfo.dataDir), "app_webview"),
                File(internalCache, "WebView"),
                File(appCtx.codeCacheDir, "com.android.webview"),
                File(appCtx.codeCacheDir, "WebView")
            )
            CacheStorageDeleteTarget.SHARE_JS -> listOf(File(internalCache, "shareJs"))
            CacheStorageDeleteTarget.TTS -> listOf(File(internalCache, "httpTTS"), File(internalCache, "httpTTS_cache"))
            CacheStorageDeleteTarget.EPUB_TEMP -> listOf(File(internalCache, "epub-fonts"), File(internalCache, "epub-debug"))
            CacheStorageDeleteTarget.IMAGE_TEMP -> listOf(
                File(internalCache, "tmp"),
                File(internalCache, "image_crop_source"),
                File(externalCache, "qr.png")
            )
            CacheStorageDeleteTarget.SO_DOWNLOAD -> listOf(File(internalCache, "so_download"))
            CacheStorageDeleteTarget.TEMP_BOOK_CACHE -> File(BookHelp.cachePath)
                .listFiles()
                ?.filter { it.isDirectory && !CacheManifestHelper.hasManifest(it) }
                .orEmpty()
            CacheStorageDeleteTarget.AUDIO -> {
                ExoPlayerHelper.clearAudioCache()
                emptyList()
            }
            CacheStorageDeleteTarget.VIDEO -> {
                ExoPlayerHelper.clearVideoCache()
                emptyList()
            }
            CacheStorageDeleteTarget.PLAYER_TEMP -> listOf(File(externalCache, "video_temp"), File(externalCache, "video_temp_cache"))
            CacheStorageDeleteTarget.CACHE_PACKAGE -> listOf(File(externalCache, "cache_package"))
            CacheStorageDeleteTarget.READ_CONFIG -> listOf(File(externalCache, "readConfig"), File(externalCache, "readConfig.zip"))
            CacheStorageDeleteTarget.UPLOAD -> listOf(File(externalCache, "upload"))
            CacheStorageDeleteTarget.IMPORT_TEMP -> listOf(File(externalCache, "download"))
            CacheStorageDeleteTarget.LOGS -> listOf(
                File(externalCache, "logs"),
                File(externalCache, "crash"),
                File(externalCache, "logcat.txt"),
                File(externalCache, "logs.zip"),
                File(externalCache, "heapDump")
            )
            CacheStorageDeleteTarget.ARCHIVE_TEMP -> listOf(File(externalCache, "ArchiveTemp"))
            CacheStorageDeleteTarget.CRONET_CACHE -> listOf(File(externalCache, "app_cronet"))
            CacheStorageDeleteTarget.OTHER_TEMP -> otherExternalTempFiles()
        }
        paths.forEach { file ->
            if (file.exists()) FileUtils.delete(file, deleteRootDir = true)
        }
    }

    private fun otherExternalTempFiles(): List<File> {
        val externalCache = appCtx.externalCache
        val knownRoots = knownExternalCacheRoots(externalCache)
            .mapTo(hashSetOf()) { it.absolutePath }
        val roots = buildList {
            add(externalCache)
            addAll(appCtx.externalCacheDirs.filterNotNull())
        }.filter { it.exists() && it.isDirectory }
            .distinctBy { it.absolutePath }
        return roots.flatMap { root ->
            root.listFiles()
                ?.filterNot { it.absolutePath in knownRoots }
                .orEmpty()
        }
    }

    private fun knownExternalCacheRoots(externalCache: File): List<File> {
        return listOf(
            File(externalCache, "qr.png"),
            File(externalCache, "audio_exoplayer"),
            File(externalCache, "audio_exoplayer_complete"),
            File(externalCache, "exoplayer"),
            File(externalCache, "exoplayer_complete"),
            File(externalCache, "video_temp"),
            File(externalCache, "video_temp_cache"),
            File(externalCache, "cache_package"),
            File(externalCache, "readConfig"),
            File(externalCache, "readConfig.zip"),
            File(externalCache, "upload"),
            File(externalCache, "download"),
            File(externalCache, "logs"),
            File(externalCache, "crash"),
            File(externalCache, "logcat.txt"),
            File(externalCache, "logs.zip"),
            File(externalCache, "heapDump"),
            File(externalCache, "ArchiveTemp"),
            File(externalCache, "app_cronet")
        )
    }

    private fun getAppStorageSize(): Long {
        return appStorageRoots().sumOf { it.directorySize() }
    }

    private fun appStorageRoots(): List<File> {
        val roots = buildList {
            add(File(appCtx.applicationInfo.dataDir))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                add(appCtx.createDeviceProtectedStorageContext().dataDir)
            }
            addAll(appCtx.getExternalFilesDirs(null).filterNotNull())
            addAll(appCtx.externalCacheDirs.filterNotNull())
            addAll(appCtx.externalMediaDirs.filterNotNull())
            add(appCtx.obbDir)
        }
        return roots
            .filter { it.exists() }
            .distinctBy { it.absolutePath }
    }

    private fun CacheBookManifest?.cachedIndexes(): Set<Int>? {
        return this
            ?.chapters
            ?.asSequence()
            ?.filter { it.cached }
            ?.mapTo(hashSetOf()) { it.index }
    }

    private fun getCacheFileNames(book: Book): Set<String> {
        val cacheDir = BookHelp.getCacheDir(book)
        if (!cacheDir.exists() || !cacheDir.isDirectory) return emptySet()
        return cacheDir.list()?.toSet().orEmpty()
    }

    private fun isChapterCached(
        book: Book,
        chapter: BookChapter,
        cacheNames: Set<String> = getCacheFileNames(book),
        validateImageContent: Boolean = true
    ): Boolean {
        if (book.isLocal) return false
        if (book.isVideo) return ExoPlayerHelper.isVideoCached(chapter.resourceUrl, book)
        if (book.isAudio) return ExoPlayerHelper.isMediaCached(chapter.resourceUrl, book)
        val hasContent = BookHelp.getChapterCacheFileNames(book, chapter).any(cacheNames::contains)
        return if (validateImageContent && book.isImage && hasContent) {
            BookHelp.hasImageContent(book, chapter)
        } else {
            hasContent
        }
    }

    private fun getBooks(mode: CacheManageMode): List<Book> {
        return when (mode) {
            CacheManageMode.BOOK -> appDb.bookDao.getByTypeOnLine(BookType.text)
            CacheManageMode.AUDIO -> getBooksByTypeWithNotShelf(BookType.audio)
            CacheManageMode.VIDEO -> getBooksByTypeWithNotShelf(BookType.video)
            CacheManageMode.MANGA -> appDb.bookDao.getByTypeOnLine(BookType.image)
        }
    }

    private fun getBooksByTypeWithNotShelf(@BookType.Type type: Int): List<Book> {
        return (appDb.bookDao.getByTypeOnLine(type) +
            appDb.bookDao.notShelfBooks.filter { it.isType(type) })
            .distinctBy { it.bookUrl }
    }

    private fun deleteMediaCache(book: Book) {
        if (!book.isMedia) return
        val chapters = appDb.bookChapterDao.getChapterList(book.bookUrl)
            .takeIf { it.isNotEmpty() }
            ?: CacheManifestHelper.read(book)?.let(CacheManifestHelper::toChapters).orEmpty()
        chapters
            .forEach {
                if (book.isVideo) {
                    ExoPlayerHelper.removeVideoCache(it.resourceUrl, book)
                } else {
                    ExoPlayerHelper.removeMediaCache(it.resourceUrl, book)
                }
            }
    }

    private fun refreshManifest(book: Book) {
        CacheManifestHelper.refresh(book)
    }

    private suspend fun resolveMediaRequest(
        book: Book,
        chapter: BookChapter
    ): ExoPlayerHelper.MediaRequest {
        chapter.resourceUrl
            ?.takeIf { it.isNotBlank() }
            ?.takeIf(::isDownloadableMediaContent)
            ?.let { return ExoPlayerHelper.MediaRequest(it) }
        val source = book.getBookSource()
            ?: throw IllegalStateException(context.getString(R.string.book_source_not_found))
        val candidates = linkedSetOf<String>()
        BookHelp.getContent(book, chapter)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { content -> normalizeMediaContent(book, content) }
            ?.let(candidates::add)
        WebBook.getContentAwait(source, book, chapter, needSave = true)
            .trim()
            .takeIf { it.isNotBlank() }
            ?.let { content -> normalizeMediaContent(book, content) }
            ?.let(candidates::add)
        var lastError: Throwable? = null
        for (content in candidates) {
            try {
                if (content.isJsonArray()) {
                    return ExoPlayerHelper.MediaRequest(content)
                }
                return AnalyzeUrl(
                    content,
                    source = source,
                    ruleData = book,
                    chapter = chapter,
                    coroutineContext = currentCoroutineContext()
                ).getMediaRequest()
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw IllegalStateException(
            lastError?.localizedMessage ?: context.getString(R.string.cache_manage_audio_url_empty)
        )
    }

    private fun normalizeMediaContent(book: Book, content: String): String {
        if (!book.isVideo) return content
        if (content.startsWith("#EXTM3U")) {
            return writeVideoTempManifest(content, "m3u8")
        }
        if (!content.startsWith("<")) return content
        return writeVideoTempManifest(content, "mpd")
    }

    private fun writeVideoTempManifest(content: String, suffix: String): String {
        val dir = File(appCtx.externalCache, "video_temp_cache").apply { mkdirs() }
        val file = File(dir, "${MD5Utils.md5Encode(content)}.$suffix")
        if (!file.isFile || file.readText() != content) {
            file.writeText(content)
        }
        return Uri.fromFile(file).toString()
    }

    private fun isDownloadableMediaContent(content: String): Boolean {
        val urls = if (content.isJsonArray()) {
            GSON.fromJsonArray<String>(content).getOrNull().orEmpty()
        } else {
            listOf(content)
        }
        return urls.isNotEmpty() && urls.all {
            val scheme = Uri.parse(it).scheme
            scheme.equals("http", true) ||
                scheme.equals("https", true) ||
                (scheme.equals("file", true) && isVideoManifestUrl(it))
        }
    }

    private fun isVideoManifestUrl(url: String): Boolean {
        val lower = url.substringBefore('?').lowercase()
        return lower.endsWith(".m3u8") || lower.endsWith(".mpd") || lower.endsWith(".ism")
    }

    private fun Book.cacheGroupKey(mode: CacheManageMode): String {
        return listOf(
            mode.name,
            name.trim(),
            getRealAuthor().trim()
        ).joinToString(separator = "\u001F")
    }

    private fun Book.cacheSourceKey(): String {
        return listOf(
            origin.ifBlank { originName },
            bookUrl
        ).joinToString(separator = "\u001F")
    }

    private fun Book.cacheSourceName(): String {
        return when {
            isLocal -> context.getString(R.string.local)
            originName.isNotBlank() -> originName
            origin.isNotBlank() -> origin
            else -> context.getString(R.string.unknown)
        }
    }

    private fun CacheBookItem.toSourceVariant(): CacheBookSourceVariant {
        return CacheBookSourceVariant(
            sourceKey = sourceKey,
            sourceName = sourceName,
            book = book,
            cachedCount = cachedCount,
            totalChapterCount = totalChapterCount,
            storageSizeBytes = storageSizeBytes,
            storageSummary = storageSummary,
            storageCalculated = storageCalculated,
            taskState = taskState,
            manifest = manifest,
            inBookshelf = inBookshelf,
            sourceAvailable = sourceAvailable
        )
    }
}

private data class CacheBookStorage(
    val totalBytes: Long,
    val displayText: String
)

enum class CacheManageMode(@StringRes val titleRes: Int, val bookType: Int) {
    BOOK(R.string.cache_manage_books, BookType.text),
    AUDIO(R.string.cache_manage_audio, BookType.audio),
    VIDEO(R.string.cache_manage_video, BookType.video),
    MANGA(R.string.cache_manage_manga, BookType.image)
}

enum class CacheChapterFilter {
    ALL,
    CACHED,
    UNCACHED
}

data class CacheBookItem(
    val book: Book,
    val mode: CacheManageMode,
    val groupKey: String,
    val sourceKey: String,
    val sourceName: String,
    val cachedCount: Int,
    val totalChapterCount: Int,
    val storageSizeBytes: Long = 0L,
    val storageSummary: String = "",
    val storageCalculated: Boolean = false,
    val taskState: AudioCacheTaskState? = null,
    val manifest: CacheBookManifest? = null,
    val inBookshelf: Boolean = true,
    val sourceAvailable: Boolean = true,
    val sourceVariants: List<CacheBookSourceVariant> = emptyList()
)

data class CacheBookSourceVariant(
    val sourceKey: String,
    val sourceName: String,
    val book: Book,
    val cachedCount: Int,
    val totalChapterCount: Int,
    val storageSizeBytes: Long = 0L,
    val storageSummary: String = "",
    val storageCalculated: Boolean = false,
    val taskState: AudioCacheTaskState? = null,
    val manifest: CacheBookManifest? = null,
    val inBookshelf: Boolean = true,
    val sourceAvailable: Boolean = true
)

data class CacheChapterItem(
    val chapter: BookChapter,
    val cached: Boolean
)

data class CacheSummary(
    val bookCount: Int,
    val cachedChapterCount: Int,
    val currentModeSize: Long,
    val totalCacheSize: Long,
    val storageDetails: List<CacheStorageDetail>,
    val mode: CacheManageMode
)

data class CacheStorageDetail(
    val name: String,
    val bytes: Long,
    val deleteTarget: CacheStorageDeleteTarget? = null
)

private data class CacheStorageGroup(
    val name: String,
    val files: List<File>,
    val deleteTarget: CacheStorageDeleteTarget? = null
) {
    fun toDetail(): CacheStorageDetail {
        return CacheStorageDetail(name, files.sumOf { it.directorySize() }, deleteTarget)
    }
}

enum class CacheStorageDeleteTarget {
    COVER_THUMBS,
    DISCOVER_RSS,
    GLIDE,
    WEBVIEW,
    SHARE_JS,
    TTS,
    EPUB_TEMP,
    IMAGE_TEMP,
    SO_DOWNLOAD,
    TEMP_BOOK_CACHE,
    AUDIO,
    VIDEO,
    PLAYER_TEMP,
    CACHE_PACKAGE,
    READ_CONFIG,
    UPLOAD,
    IMPORT_TEMP,
    LOGS,
    ARCHIVE_TEMP,
    CRONET_CACHE,
    OTHER_TEMP
}

private data class MediaCacheManifest(
    val bookName: String,
    val author: String,
    val bookUrl: String,
    val chapters: List<Chapter>
) {
    data class Chapter(
        val index: Int,
        val title: String,
        val url: String,
        val resourceUrl: String?,
        val fileCount: Int
    )
}

private fun CacheBookManifest.matches(mode: CacheManageMode): Boolean {
    return type and mode.bookType > 0
}

private val CacheManageMode.isMedia: Boolean
    get() = this == CacheManageMode.AUDIO || this == CacheManageMode.VIDEO

private val Book.isMedia: Boolean
    get() = isAudio || isVideo

private val Book.cacheManageMode: CacheManageMode
    get() = if (isVideo) CacheManageMode.VIDEO else CacheManageMode.AUDIO

private fun AudioCacheTaskState?.isVisibleAudioTask(): Boolean {
    return this?.active == true || this?.status == CacheTaskStatus.PAUSED
}

private fun List<BookChapter>.filterByKey(key: String?): List<BookChapter> {
    if (key.isNullOrBlank()) return this
    return filter { it.title.contains(key, ignoreCase = true) }
}

private fun List<Int>.toRanges(): List<Pair<Int, Int>> {
    if (isEmpty()) return emptyList()
    val ranges = arrayListOf<Pair<Int, Int>>()
    var start = first()
    var previous = first()
    drop(1).forEach { value ->
        if (value == previous + 1) {
            previous = value
        } else {
            ranges.add(start to previous)
            start = value
            previous = value
        }
    }
    ranges.add(start to previous)
    return ranges
}

private fun File.fileSize(): Long {
    return if (isFile) allocatedSize() else 0L
}

private fun File.directorySize(): Long {
    if (!exists()) return 0L
    if (isFile) return allocatedSize()
    return allocatedSize() + (listFiles()?.sumOf { it.directorySize() } ?: 0L)
}

private fun File.childrenSize(excludes: Set<String> = emptySet()): Long {
    if (!isDirectory) return 0L
    return listFiles()
        ?.asSequence()
        ?.filterNot { it.name in excludes }
        ?.sumOf { it.directorySize() }
        ?: 0L
}

private fun File.allocatedSize(): Long {
    if (!exists()) return 0L
    return runCatching {
        Os.stat(absolutePath).st_blocks * 512L
    }.getOrElse {
        length()
    }.coerceAtLeast(0L)
}

private fun formatBytes(bytes: Long): String {
    val kb = 1024.0
    val mb = kb * 1024.0
    val gb = mb * 1024.0
    return when {
        bytes >= gb -> String.format(Locale.getDefault(), "%.2f GB", bytes / gb)
        bytes >= mb -> String.format(Locale.getDefault(), "%.2f MB", bytes / mb)
        bytes >= kb -> String.format(Locale.getDefault(), "%.1f KB", bytes / kb)
        else -> "$bytes B"
    }
}
