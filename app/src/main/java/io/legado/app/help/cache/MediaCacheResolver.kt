package io.legado.app.help.cache

import android.net.Uri
import io.legado.app.R
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.SourceAudioResolver
import io.legado.app.help.book.getBookSource
import io.legado.app.help.book.isAudio
import io.legado.app.help.book.isVideo
import io.legado.app.help.exoplayer.ExoPlayerHelper
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.model.analyzeRule.AnalyzeUrl.Companion.getMediaRequest
import io.legado.app.model.webBook.WebBook
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.isJsonArray
import io.legado.app.utils.MD5Utils
import kotlinx.coroutines.currentCoroutineContext
import splitties.init.appCtx
import java.io.File

/** Shared media URL resolution used by the media execution adapter. */
internal object MediaCacheResolver {
    suspend fun resolve(book: Book, chapter: BookChapter): ExoPlayerHelper.MediaRequest {
        // Audio resolution also extracts the chapter lyric. Each media domain keeps its
        // resolver artifact out of the ordinary text-body cache.
        if (book.isAudio) {
            return SourceAudioResolver.resolve(book, book.getBookSource(), chapter).request
        }
        require(book.isVideo) {
            "generic media resolver received a non-media book: ${book.bookUrl}"
        }
        chapter.resourceUrl
            ?.takeIf { it.isNotBlank() }
            ?.takeIf { isDownloadableMediaContent(book, it) }
            ?.let { return ExoPlayerHelper.MediaRequest(it) }
        val source = book.getBookSource()
            ?: error(appCtx.getString(R.string.book_source_not_found))
        val content = WebBook.getContentAwait(source, book, chapter, needSave = false)
            .trim()
        require(content.isNotBlank()) {
            "Video source returned an empty media address: chapter=${chapter.index}"
        }
        val normalized = normalizeMediaContent(book, content)
        if (normalized.isJsonArray()) return ExoPlayerHelper.MediaRequest(normalized)
        return AnalyzeUrl(
            normalized,
            source = source,
            ruleData = book,
            chapter = chapter,
            coroutineContext = currentCoroutineContext(),
        ).getMediaRequest()
    }

    private fun normalizeMediaContent(book: Book, content: String): String {
        if (!book.isVideo) return content
        if (content.startsWith("#EXTM3U")) return writeVideoManifest(book, content, "m3u8")
        if (!content.startsWith("<")) return content
        return writeVideoManifest(book, content, "mpd")
    }

    private fun writeVideoManifest(book: Book, content: String, suffix: String): String {
        val dir = File(BookHelp.getCacheDir(book), VIDEO_MANIFEST_DIR_NAME).apply { mkdirs() }
        val file = File(dir, "${MD5Utils.md5Encode(content)}.$suffix")
        if (!file.isFile || file.readText() != content) file.writeText(content)
        return Uri.fromFile(file).toString()
    }

    private fun isDownloadableMediaContent(book: Book, content: String): Boolean {
        val urls = if (content.isJsonArray()) {
            GSON.fromJsonArray<String>(content).getOrNull().orEmpty()
        } else {
            listOf(content)
        }
        return urls.isNotEmpty() && urls.all {
            val scheme = Uri.parse(it).scheme
            scheme.equals("http", true) ||
                scheme.equals("https", true) ||
                (scheme.equals("file", true) && isPersistentVideoManifest(book, it))
        }
    }

    private fun isPersistentVideoManifest(book: Book, url: String): Boolean {
        if (!isVideoManifestUrl(url)) return false
        val path = Uri.parse(url).path ?: return false
        val manifestDir = File(BookHelp.getCacheDir(book), VIDEO_MANIFEST_DIR_NAME)
        val file = File(path)
        return file.isFile && file.length() > 0L && runCatching {
            val dirPath = manifestDir.canonicalPath.trimEnd(File.separatorChar) + File.separator
            file.canonicalPath.startsWith(dirPath)
        }.getOrDefault(false)
    }

    private fun isVideoManifestUrl(url: String): Boolean {
        val lower = url.substringBefore('?').lowercase()
        return lower.endsWith(".m3u8") || lower.endsWith(".mpd") || lower.endsWith(".ism")
    }

    private const val VIDEO_MANIFEST_DIR_NAME = "video_media_manifests"
}
