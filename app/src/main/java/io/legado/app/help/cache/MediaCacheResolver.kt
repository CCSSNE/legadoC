package io.legado.app.help.cache

import android.net.Uri
import io.legado.app.R
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.getBookSource
import io.legado.app.help.book.isVideo
import io.legado.app.help.exoplayer.ExoPlayerHelper
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.model.analyzeRule.AnalyzeUrl.Companion.getMediaRequest
import io.legado.app.model.webBook.WebBook
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.isJsonArray
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.externalCache
import kotlinx.coroutines.currentCoroutineContext
import splitties.init.appCtx
import java.io.File

/** Shared media URL resolution used by the media execution adapter. */
internal object MediaCacheResolver {
    suspend fun resolve(book: Book, chapter: BookChapter): ExoPlayerHelper.MediaRequest {
        chapter.resourceUrl
            ?.takeIf { it.isNotBlank() }
            ?.takeIf(::isDownloadableMediaContent)
            ?.let { return ExoPlayerHelper.MediaRequest(it) }
        val source = book.getBookSource()
            ?: error(appCtx.getString(R.string.book_source_not_found))
        val candidates = linkedSetOf<String>()
        BookHelp.getContent(book, chapter)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { normalizeMediaContent(book, it) }
            ?.let(candidates::add)
        WebBook.getContentAwait(source, book, chapter, needSave = true)
            .trim()
            .takeIf { it.isNotBlank() }
            ?.let { normalizeMediaContent(book, it) }
            ?.let(candidates::add)
        var lastError: Throwable? = null
        for (content in candidates) {
            try {
                if (content.isJsonArray()) return ExoPlayerHelper.MediaRequest(content)
                return AnalyzeUrl(
                    content,
                    source = source,
                    ruleData = book,
                    chapter = chapter,
                    coroutineContext = currentCoroutineContext(),
                ).getMediaRequest()
            } catch (error: Exception) {
                lastError = error
            }
        }
        throw IllegalStateException(
            lastError?.localizedMessage ?: appCtx.getString(R.string.cache_manage_audio_url_empty)
        )
    }

    private fun normalizeMediaContent(book: Book, content: String): String {
        if (!book.isVideo) return content
        if (content.startsWith("#EXTM3U")) return writeVideoTempManifest(content, "m3u8")
        if (!content.startsWith("<")) return content
        return writeVideoTempManifest(content, "mpd")
    }

    private fun writeVideoTempManifest(content: String, suffix: String): String {
        val dir = File(appCtx.externalCache, "video_temp_cache").apply { mkdirs() }
        val file = File(dir, "${MD5Utils.md5Encode(content)}.$suffix")
        if (!file.isFile || file.readText() != content) file.writeText(content)
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
}
