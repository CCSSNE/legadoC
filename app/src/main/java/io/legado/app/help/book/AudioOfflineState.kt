package io.legado.app.help.book

import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.help.exoplayer.ExoPlayerHelper

/** Pure audio-domain completeness projection; it owns no task state. */
data class AudioOfflineState(
    val mediaAvailable: Boolean,
    val rawLyricAvailable: Boolean,
) {
    val isComplete: Boolean
        get() = mediaAvailable && rawLyricAvailable

    fun incompleteReason(): String =
        "audio artifact incomplete: mediaAvailable=$mediaAvailable " +
            "rawLyricAvailable=$rawLyricAvailable"

    companion object {
        fun inspect(book: Book, chapter: BookChapter): AudioOfflineState {
            require(book.isAudio) { "audio completeness requires an audio book" }
            val resourceUrl = chapter.resourceUrl
            return AudioOfflineState(
                mediaAvailable = ExoPlayerHelper.isLocalMediaAvailable(resourceUrl) ||
                    ExoPlayerHelper.isMediaCached(resourceUrl, book),
                rawLyricAvailable = chapter.getVariable("lyric").isNotBlank(),
            )
        }

        fun isComplete(book: Book, chapter: BookChapter): Boolean =
            inspect(book, chapter).isComplete
    }
}
