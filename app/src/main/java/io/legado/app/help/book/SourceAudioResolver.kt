package io.legado.app.help.book

import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.help.exoplayer.ExoPlayerHelper
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.model.analyzeRule.AnalyzeUrl.Companion.getMediaRequest
import io.legado.app.model.webBook.WebBook
import io.legado.app.utils.isJsonArray

object SourceAudioResolver {

    suspend fun resolve(
        book: Book,
        bookSource: BookSource?,
        chapter: BookChapter,
    ): ResolvedSourceAudio {
        require(book.isAudio) { "Source audio requires an audio book: ${book.bookUrl}" }

        // Audio text belongs to the chapter lyric field. A previous media path saved the
        // source response as ordinary book content, which made an audio download look like
        // a separately downloaded text book. Enforce the ownership boundary before every
        // audio resolution so both old pollution and future requests stay out of book_cache.
        BookHelp.delContent(book, chapter)

        chapter.resourceUrl
            ?.takeIf { book.isLocal && ExoPlayerHelper.isLocalMediaContent(it) }
            ?.let { localUrl ->
                return ResolvedSourceAudio(
                    request = ExoPlayerHelper.createMediaRequest(localUrl, emptyMap()),
                    mapping = AudioTextMapping.parse(AudioTextFusion.effectiveLyric(chapter)),
                )
            }

        chapter.resourceUrl
            ?.takeIf {
                ExoPlayerHelper.isMediaCached(it, book) &&
                    AudioTextFusion.effectiveLyric(chapter).isNotBlank()
            }
            ?.let { cachedUrl ->
                return ResolvedSourceAudio(
                    request = ExoPlayerHelper.createMediaRequest(cachedUrl, emptyMap()),
                    mapping = AudioTextMapping.parse(AudioTextFusion.effectiveLyric(chapter)),
                )
            }

        val source = requireNotNull(bookSource) {
            "Audio source is missing: book=${book.bookUrl}, chapter=${chapter.index}"
        }
        val content = WebBook.getContentAwait(
            bookSource = source,
            book = book,
            bookChapter = chapter,
            needSave = false,
        ).trim()
        require(content.isNotEmpty()) {
            "Audio source returned an empty media address: chapter=${chapter.index}"
        }

        val request = if (content.isJsonArray()) {
            ExoPlayerHelper.createMediaRequest(content, emptyMap())
        } else {
            AnalyzeUrl(
                content,
                source = source,
                ruleData = book,
                chapter = chapter,
            ).getMediaRequest()
        }
        require(request.url.isNotBlank()) {
            "Resolved audio media address is empty: chapter=${chapter.index}"
        }
        if (chapter.resourceUrl != request.url) {
            chapter.resourceUrl = request.url
            chapter.update()
        }
        return ResolvedSourceAudio(
            request = request,
            mapping = AudioTextMapping.parse(AudioTextFusion.effectiveLyric(chapter)),
        )
    }

    data class ResolvedSourceAudio(
        val request: ExoPlayerHelper.MediaRequest,
        val mapping: AudioTextMapping,
    )
}
