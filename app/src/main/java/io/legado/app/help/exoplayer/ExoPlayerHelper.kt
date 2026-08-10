package io.legado.app.help.exoplayer

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.FileDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSink
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.offline.DefaultDownloaderFactory
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.source.ConcatenatingMediaSource2
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.extractor.DefaultExtractorsFactory
import com.google.gson.reflect.TypeToken
import io.legado.app.data.entities.Book
import io.legado.app.help.book.BookHelp
import io.legado.app.help.http.okHttpClient
import io.legado.app.utils.FileUtils
import io.legado.app.utils.GSON
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.externalCache
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.isJsonArray
import okhttp3.CacheControl
import splitties.init.appCtx
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit


@Suppress("unused")
@SuppressLint("UnsafeOptInUsageError")
object ExoPlayerHelper {

    private const val SPLIT_TAG = "\uD83D\uDEA7"

    private val mapType by lazy {
        object : TypeToken<Map<String, String>>() {}.type
    }

    fun createMediaItem(url: String, headers: Map<String, String>): MediaItem {
        val formatUrl = url + SPLIT_TAG + GSON.toJson(headers, mapType)
        val mediaItemBuilder = MediaItem.Builder().setUri(formatUrl)
        return mediaItemBuilder.build()
    }

    fun createMediaRequest(url: String, headers: Map<String, String>): MediaRequest {
        return MediaRequest(url, headers.toMap())
    }

    fun createHttpExoPlayer(context: Context): ExoPlayer {
        return ExoPlayer.Builder(context).setLoadControl(
            DefaultLoadControl.Builder().setBufferDurationsMs(
                DefaultLoadControl.DEFAULT_MIN_BUFFER_MS,
                DefaultLoadControl.DEFAULT_MAX_BUFFER_MS,
                DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_MS / 10,
                DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS / 10
            ).build()
        ).setMediaSourceFactory(
            DefaultMediaSourceFactory(
                context,
                DefaultExtractorsFactory().setConstantBitrateSeekingEnabled(true)
            ).setDataSourceFactory(resolvingDataSource)
                .setLiveTargetOffsetMs(5000)
        ).build()
    }


    private val resolvingDataSource: ResolvingDataSource.Factory by lazy {
        ResolvingDataSource.Factory(audioReadDataSourceFactory()) {
            var res = it

            if (it.uri.toString().contains(SPLIT_TAG)) {
                val urls = it.uri.toString().split(SPLIT_TAG)
                val url = urls[0]
                res = res.withUri(Uri.parse(url))
                try {
                    val headers: Map<String, String> = GSON.fromJson(urls[1], mapType)
                    okhttpDataFactory.setDefaultRequestProperties(headers)
                } catch (_: Exception) {
                }
            }

            res

        }
    }


    /**
     * 支持缓存的DataSource.Factory
     */
    val cacheDataSourceFactory by lazy {
        //使用自定义的CacheDataSource以支持设置UA
        CacheDataSource.Factory()
            .setCache(videoCache())
            .setUpstreamDataSourceFactory(okhttpDataFactory)
            .setCacheReadDataSourceFactory(FileDataSource.Factory())
            .setCacheWriteDataSinkFactory(
                CacheDataSink.Factory()
                    .setCache(videoCache())
                    .setFragmentSize(CacheDataSink.DEFAULT_FRAGMENT_SIZE)
            )
    }

    fun createOfflineMediaSource(
        context: Context,
        url: String,
        headers: Map<String, String>,
        book: Book? = null
    ): MediaSource {
        return DefaultMediaSourceFactory(offlineMediaDataSourceFactory(headers, book))
            .setLiveTargetOffsetMs(5000)
            .createMediaSource(
                MediaItem.Builder()
                    .setUri(url)
                    .setMimeType(guessMediaMimeType(url))
                    .build()
            )
    }

    private fun audioReadDataSourceFactory(book: Book? = null): CacheDataSource.Factory {
        val audioCache = audioCache(book)
        return CacheDataSource.Factory()
            .setCache(audioCache)
            .setUpstreamDataSourceFactory(okhttpDataFactory)
            .setCacheReadDataSourceFactory(FileDataSource.Factory())
            .setCacheWriteDataSinkFactory(null)
    }

    private fun offlineMediaDataSourceFactory(
        headers: Map<String, String>,
        book: Book? = null
    ): CacheDataSource.Factory {
        val audioCache = audioCache(book)
        return CacheDataSource.Factory()
            .setCache(audioCache)
            .setUpstreamDataSourceFactory(okhttpDataFactory(headers))
            .setCacheReadDataSourceFactory(FileDataSource.Factory())
            .setCacheWriteDataSinkFactory(
                CacheDataSink.Factory()
                    .setCache(audioCache)
                    .setFragmentSize(CacheDataSink.DEFAULT_FRAGMENT_SIZE)
            )
    }

    fun createVideoMediaSource(
        context: Context,
        url: String,
        headers: Map<String, String>,
        cacheDir: File? = null
    ): MediaSource {
        return DefaultMediaSourceFactory(videoMediaDataSourceFactory(headers, cacheDir))
            .setLiveTargetOffsetMs(5000)
            .createMediaSource(
                MediaItem.Builder()
                    .setUri(url)
                    .setMimeType(guessMediaMimeType(url))
                    .build()
            )
    }

    private fun videoPreloadDataSourceFactory(
        headers: Map<String, String>,
        cacheDir: File? = null
    ): CacheDataSource.Factory {
        return videoMediaDataSourceFactory(headers, cacheDir)
    }

    private fun videoMediaDataSourceFactory(
        headers: Map<String, String>,
        cacheDir: File? = null
    ): CacheDataSource.Factory {
        val videoCache = videoCache(cacheDir)
        return CacheDataSource.Factory()
            .setCache(videoCache)
            .setUpstreamDataSourceFactory(okhttpDataFactory(headers))
            .setCacheReadDataSourceFactory(FileDataSource.Factory())
            .setCacheWriteDataSinkFactory(
                CacheDataSink.Factory()
                    .setCache(videoCache)
                    .setFragmentSize(CacheDataSink.DEFAULT_FRAGMENT_SIZE)
            )
    }

    /**
     * Okhttp DataSource.Factory
     */
    private val okhttpDataFactory by lazy {
        val client = okHttpClient.newBuilder()
            .callTimeout(0, TimeUnit.SECONDS)
            .build()
        OkHttpDataSource.Factory(client)
            .setCacheControl(CacheControl.Builder().maxAge(1, TimeUnit.DAYS).build())
    }

    private fun okhttpDataFactory(headers: Map<String, String>): OkHttpDataSource.Factory {
        val client = okHttpClient.newBuilder()
            .callTimeout(0, TimeUnit.SECONDS)
            .build()
        return OkHttpDataSource.Factory(client)
            .setCacheControl(CacheControl.Builder().maxAge(1, TimeUnit.DAYS).build())
            .setDefaultRequestProperties(headers)
    }

    private val databaseProvider: StandaloneDatabaseProvider by lazy {
        StandaloneDatabaseProvider(appCtx)
    }
    private val cacheMap = ConcurrentHashMap<String, Cache>()
    private val cacheLock = Any()

    private fun simpleCache(dir: File, maxBytes: Long): Cache {
        val path = dir.absolutePath
        cacheMap[path]?.let { return it }
        return synchronized(cacheLock) {
            cacheMap[path] ?: SimpleCache(
                dir.apply { mkdirs() },
                LeastRecentlyUsedCacheEvictor(maxBytes),
                databaseProvider
            ).also { cacheMap[path] = it }
        }
    }

    fun audioBookCacheDir(book: Book): File {
        return File(BookHelp.getCacheDir(book), AUDIO_BOOK_CACHE_DIR)
    }

    fun videoBookCacheDir(book: Book): File {
        return File(BookHelp.getCacheDir(book), VIDEO_BOOK_CACHE_DIR)
    }

    fun releaseBookCaches(book: Book) {
        releaseCache(audioBookCacheDir(book))
        releaseCache(videoBookCacheDir(book))
    }

    private fun releaseCache(dir: File) {
        val path = dir.absolutePath
        synchronized(cacheLock) {
            cacheMap.remove(path)?.release()
        }
    }

    private fun audioCache(book: Book? = null): Cache {
        return simpleCache(book?.let(::audioBookCacheDir) ?: legacyAudioCacheDir, AUDIO_OFFLINE_CACHE_MAX_BYTES)
    }

    private fun videoCache(cacheDir: File? = null): Cache {
        return simpleCache(cacheDir ?: legacyVideoCacheDir, VIDEO_CACHE_MAX_BYTES)
    }

    private val legacyAudioCacheDir: File
        get() = File(appCtx.externalCache, "audio_exoplayer")

    private val legacyVideoCacheDir: File
        get() = File(appCtx.externalCache, "exoplayer")

    private val legacyAudioCompleteMarkerDir: File
        get() = File(appCtx.externalCache, "audio_exoplayer_complete")

    private val legacyVideoCompleteMarkerDir: File
        get() = File(appCtx.externalCache, "exoplayer_complete")

    /**
     * 通过kotlin扩展函数+反射实现CacheDataSource.Factory设置默认请求头
     * 需要添加混淆规则 -keepclassmembers class com.google.android.exoplayer2.upstream.cache.CacheDataSource$Factory{upstreamDataSourceFactory;}
     * @param headers
     * @return
     */
//    private fun CacheDataSource.Factory.setDefaultRequestProperties(headers: Map<String, String> = mapOf()): CacheDataSource.Factory {
//        val declaredField = this.javaClass.getDeclaredField("upstreamDataSourceFactory")
//        declaredField.isAccessible = true
//        val df = declaredField[this] as DataSource.Factory
//        if (df is OkHttpDataSource.Factory) {
//            df.setDefaultRequestProperties(headers)
//        }
//        return this
//    }


    fun getMediaSource(context: Context, url: String, book: Book? = null): MediaSource? {
        val uris = GSON.fromJsonArray<String>(url).getOrNull() ?: return null
        if (uris.isEmpty()) return null
        val mediaSourceBuilder = ConcatenatingMediaSource2.Builder()
        for (uri in uris) {
            mediaSourceBuilder.add(
                ProgressiveMediaSource.Factory(audioReadDataSourceFactory(book))
                    .createMediaSource(MediaItem.fromUri(uri)), 3000
            )
        }
        return mediaSourceBuilder.build()
    }

    fun cacheMedia(
        request: MediaRequest,
        useVideoCache: Boolean = false,
        book: Book? = null,
        progress: ((requestLength: Long, bytesCached: Long, newBytesCached: Long) -> Unit)? = null,
        shouldCancel: (() -> Boolean)? = null
    ): Long {
        var totalCached = 0L
        val urls = getMediaUrls(request.url)
        require(urls.isNotEmpty()) { "media url is empty" }
        urls.forEach { url ->
            require(isDownloadableMediaUrl(url)) { "media url is not downloadable" }
            if (shouldCancel?.invoke() == true) {
                throw kotlinx.coroutines.CancellationException("audio cache cancelled")
            }
            var cached = 0L
            val dataSourceFactory = if (useVideoCache) {
                videoMediaDataSourceFactory(request.headers, book?.let(::videoBookCacheDir))
            } else {
                offlineMediaDataSourceFactory(request.headers, book)
            }
            val downloader = DefaultDownloaderFactory(
                dataSourceFactory,
                Runnable::run
            ).createDownloader(
                DownloadRequest.Builder(MD5Utils.md5Encode(url), Uri.parse(url))
                    .setMimeType(guessMediaMimeType(url))
                    .build()
            )
            downloader.download { requestLength, bytesCached, _ ->
                if (shouldCancel?.invoke() == true) {
                    downloader.cancel()
                    throw kotlinx.coroutines.CancellationException("audio cache cancelled")
                }
                val newBytesCached = (bytesCached - cached).coerceAtLeast(0L)
                cached = bytesCached
                progress?.invoke(requestLength, bytesCached, newBytesCached)
            }
            markMediaUrlComplete(url, useVideoCache, book)
            totalCached += cached
        }
        return totalCached
    }

    fun preloadVideoWindow(
        request: MediaRequest,
        cacheDir: File? = null,
        durationMs: Long = VIDEO_PRELOAD_DURATION_MS,
        shouldCancel: (() -> Boolean)? = null
    ): Long {
        val url = getMediaUrls(request.url).firstOrNull() ?: return 0L
        if (!isDownloadableMediaUrl(url)) return 0L
        if (shouldCancel?.invoke() == true) {
            throw kotlinx.coroutines.CancellationException("video preload cancelled")
        }
        val preloadBytes = estimatePreloadBytes(durationMs)
        val dataSource = videoPreloadDataSourceFactory(request.headers, cacheDir).createDataSource()
        var writer: CacheWriter? = null
        val progressListener = object : CacheWriter.ProgressListener {
            override fun onProgress(
                requestLength: Long,
                bytesCached: Long,
                newBytesCached: Long
            ) {
                if (shouldCancel?.invoke() == true) {
                    writer?.cancel()
                }
            }
        }
        writer = CacheWriter(
            dataSource,
            DataSpec.Builder()
                .setUri(Uri.parse(url))
                .setLength(preloadBytes)
                .build(),
            null,
            progressListener
        )
        var cancelled = false
        try {
            writer.cache()
        } catch (e: java.io.InterruptedIOException) {
            cancelled = true
        } finally {
            if (shouldCancel?.invoke() == true) {
                writer.cancel()
                cancelled = true
            }
        }
        if (cancelled) {
            throw kotlinx.coroutines.CancellationException("video preload cancelled")
        }
        return videoCache(cacheDir).getCachedBytes(url, 0, preloadBytes)
    }

    private fun estimatePreloadBytes(durationMs: Long): Long {
        val seconds = (durationMs / 1000L).coerceAtLeast(1L)
        return seconds * VIDEO_PRELOAD_BYTES_PER_SECOND
    }

    fun isMediaCached(url: String?, book: Book? = null): Boolean {
        if (url.isNullOrBlank()) return false
        val urls = getMediaUrls(url)
        if (urls.isEmpty()) return false
        if (urls.any { !isDownloadableMediaUrl(it) }) return false
        return urls.all { isMediaUrlCached(it, book) }
    }

    fun isVideoCached(url: String?, book: Book? = null): Boolean {
        return isVideoCachedInCacheDir(url, book?.let(::videoBookCacheDir))
    }

    private fun isVideoCachedInCacheDir(url: String?, cacheDir: File? = null): Boolean {
        if (url.isNullOrBlank()) return false
        val urls = getMediaUrls(url)
        if (urls.isEmpty()) return false
        if (urls.any { !isDownloadableMediaUrl(it) }) return false
        return urls.all { isVideoUrlCached(it, cacheDir) }
    }

    fun removeMediaCache(url: String?, book: Book? = null) {
        if (url.isNullOrBlank()) return
        val audioCache = audioCache(book)
        getMediaUrls(url).forEach {
            audioCache.removeResource(it)
            mediaCompleteMarker(it, book = book).delete()
        }
    }

    fun removeVideoCache(url: String?, book: Book? = null) {
        removeVideoCacheInCacheDir(url, book?.let(::videoBookCacheDir))
    }

    private fun removeVideoCacheInCacheDir(url: String?, cacheDir: File? = null) {
        if (url.isNullOrBlank()) return
        val videoCache = videoCache(cacheDir)
        getMediaUrls(url).forEach {
            videoCache.removeResource(it)
            mediaCompleteMarker(it, useVideoCache = true, cacheDir = cacheDir).delete()
        }
    }

    fun clearAudioCache() {
        val audioCache = audioCache()
        audioCache.keys.toList().forEach { audioCache.removeResource(it) }
        releaseCache(legacyAudioCacheDir)
        FileUtils.delete(legacyAudioCacheDir, deleteRootDir = true)
        FileUtils.delete(legacyAudioCompleteMarkerDir, deleteRootDir = true)
    }

    fun clearVideoCache() {
        val videoCache = videoCache()
        videoCache.keys.toList().forEach { videoCache.removeResource(it) }
        releaseCache(legacyVideoCacheDir)
        FileUtils.delete(legacyVideoCacheDir, deleteRootDir = true)
        FileUtils.delete(legacyVideoCompleteMarkerDir, deleteRootDir = true)
    }

    fun getMediaCacheSize(url: String?, book: Book? = null): Long {
        if (url.isNullOrBlank()) return 0L
        val audioCache = audioCache(book)
        return getMediaUrls(url).sumOf { mediaUrl ->
            audioCache.getCachedSpans(mediaUrl)
                .asSequence()
                .filter { it.isCached }
                .sumOf { it.length.coerceAtLeast(0L) }
        }
    }

    fun getVideoCacheSize(url: String?, book: Book? = null): Long {
        return getVideoCacheSizeInCacheDir(url, book?.let(::videoBookCacheDir))
    }

    private fun getVideoCacheSizeInCacheDir(url: String?, cacheDir: File? = null): Long {
        if (url.isNullOrBlank()) return 0L
        val videoCache = videoCache(cacheDir)
        return getMediaUrls(url).sumOf { mediaUrl ->
            videoCache.getCachedSpans(mediaUrl)
                .asSequence()
                .filter { it.isCached }
                .sumOf { it.length.coerceAtLeast(0L) }
        }
    }

    fun copyMediaCache(url: String?, targetDir: File, book: Book? = null): Int {
        return copyCache(audioCache(book), url, targetDir)
    }

    fun copyVideoCache(url: String?, targetDir: File, book: Book? = null): Int {
        return copyCache(videoCache(book?.let(::videoBookCacheDir)), url, targetDir)
    }

    private fun copyCache(cache: Cache, url: String?, targetDir: File): Int {
        if (url.isNullOrBlank()) return 0
        if (!targetDir.exists()) targetDir.mkdirs()
        var count = 0
        getMediaUrls(url).forEachIndexed { urlIndex, mediaUrl ->
            for (span in cache.getCachedSpans(mediaUrl)) {
                if (!span.isCached) continue
                val source = span.file ?: continue
                if (!source.exists() || !source.isFile) continue
                val name = "${urlIndex}_${span.position}_${span.length}_${source.name}"
                source.copyTo(File(targetDir, name), overwrite = true)
                count++
            }
        }
        return count
    }

    private fun isMediaUrlCached(url: String, book: Book? = null): Boolean {
        val audioCache = audioCache(book)
        if (isAdaptiveMediaUrl(url)) {
            return hasMediaCompleteMarker(url, book = book)
        }
        val contentLength = ContentMetadata.getContentLength(audioCache.getContentMetadata(url))
        return if (contentLength > 0) {
            audioCache.isCached(url, 0, contentLength)
        } else {
            hasMediaCompleteMarker(url, book = book) &&
                audioCache.getCachedBytes(url, 0, Long.MAX_VALUE) > 0
        }
    }

    private fun isVideoUrlCached(url: String, cacheDir: File? = null): Boolean {
        val videoCache = videoCache(cacheDir)
        if (isAdaptiveMediaUrl(url)) {
            return hasMediaCompleteMarker(url, useVideoCache = true, cacheDir = cacheDir)
        }
        val contentLength = ContentMetadata.getContentLength(videoCache.getContentMetadata(url))
        return if (contentLength > 0) {
            videoCache.isCached(url, 0, contentLength)
        } else {
            hasMediaCompleteMarker(url, useVideoCache = true, cacheDir = cacheDir) &&
                videoCache.getCachedBytes(url, 0, Long.MAX_VALUE) > 0
        }
    }

    private fun markMediaUrlComplete(
        url: String,
        useVideoCache: Boolean = false,
        book: Book? = null
    ) {
        runCatching {
            mediaCompleteMarker(url, useVideoCache, book = book).writeText(COMPLETE_MARKER_VERSION)
        }
    }

    private fun hasMediaCompleteMarker(
        url: String,
        useVideoCache: Boolean = false,
        book: Book? = null,
        cacheDir: File? = null
    ): Boolean {
        return runCatching {
            mediaCompleteMarker(url, useVideoCache, book, cacheDir).readText() == COMPLETE_MARKER_VERSION
        }.getOrDefault(false)
    }

    private fun mediaCompleteMarker(
        url: String,
        useVideoCache: Boolean = false,
        book: Book? = null,
        cacheDir: File? = null
    ): File {
        return File(
            mediaCompleteMarkerDir(useVideoCache, book, cacheDir),
            MD5Utils.md5Encode(url)
        )
    }

    private fun mediaCompleteMarkerDir(
        useVideoCache: Boolean,
        book: Book? = null,
        cacheDir: File? = null
    ): File {
        val dir = when {
            useVideoCache && cacheDir != null -> File(cacheDir.parentFile, VIDEO_BOOK_COMPLETE_DIR)
            useVideoCache && book != null -> File(BookHelp.getCacheDir(book), VIDEO_BOOK_COMPLETE_DIR)
            useVideoCache -> legacyVideoCompleteMarkerDir
            book != null -> File(BookHelp.getCacheDir(book), AUDIO_BOOK_COMPLETE_DIR)
            else -> legacyAudioCompleteMarkerDir
        }
        return dir.apply { mkdirs() }
    }

    private fun getMediaUrls(url: String): List<String> {
        if (url.isJsonArray()) {
            GSON.fromJsonArray<String>(url).getOrNull()?.filter { it.isNotBlank() }?.let {
                return it
            }
        }
        return listOf(url)
    }

    private fun isDownloadableMediaUrl(url: String): Boolean {
        val scheme = Uri.parse(url).scheme ?: return false
        return scheme.equals("http", true) ||
            scheme.equals("https", true) ||
            (scheme.equals("file", true) && isAdaptiveMediaUrl(url))
    }

    private fun isAdaptiveMediaUrl(url: String): Boolean {
        val lower = url.substringBefore('?').lowercase()
        return lower.endsWith(".m3u8") || lower.endsWith(".mpd") || lower.endsWith(".ism")
    }

    private fun guessMediaMimeType(url: String): String? {
        val lower = url.substringBefore('?').lowercase()
        return when {
            lower.endsWith(".m3u8") -> MimeTypes.APPLICATION_M3U8
            lower.endsWith(".mpd") -> MimeTypes.APPLICATION_MPD
            lower.endsWith(".ism") || lower.endsWith(".isml") -> MimeTypes.APPLICATION_SS
            else -> null
        }
    }

    data class MediaRequest(
        val url: String,
        val headers: Map<String, String> = emptyMap()
    )

    private const val AUDIO_OFFLINE_CACHE_MAX_BYTES = 4L * 1024 * 1024 * 1024
    private const val VIDEO_CACHE_MAX_BYTES = 4L * 1024 * 1024 * 1024
    private const val AUDIO_BOOK_CACHE_DIR = "audio_media"
    private const val VIDEO_BOOK_CACHE_DIR = "video_media"
    private const val AUDIO_BOOK_COMPLETE_DIR = "audio_media_complete"
    private const val VIDEO_BOOK_COMPLETE_DIR = "video_media_complete"
    private const val VIDEO_PRELOAD_DURATION_MS = 5L * 60 * 1000
    private const val VIDEO_PRELOAD_BYTES_PER_SECOND = 256L * 1024
    private const val COMPLETE_MARKER_VERSION = "media_downloader_v2"
}
