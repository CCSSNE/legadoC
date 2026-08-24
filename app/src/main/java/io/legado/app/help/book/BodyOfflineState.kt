package io.legado.app.help.book

import android.graphics.BitmapFactory
import io.legado.app.constant.AppPattern
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.utils.NetworkUtils
import io.legado.app.utils.SvgUtils
import java.io.File

/** Pure BODY-domain completeness projection; it owns no task or manifest state. */
data class BodyOfflineState(
    val contentAvailable: Boolean,
    val requiredImageCount: Int,
    val missingImageCount: Int,
    val invalidImageCount: Int,
    val firstProblemImage: String? = null,
) {
    val isComplete: Boolean
        get() = contentAvailable && missingImageCount == 0 && invalidImageCount == 0

    fun incompleteReason(): String =
        "body artifact incomplete: contentAvailable=$contentAvailable " +
            "requiredImages=$requiredImageCount missingImages=$missingImageCount " +
            "invalidImages=$invalidImageCount" +
            firstProblemImage?.let { " firstProblem=$it" }.orEmpty()

    companion object {
        fun inspect(book: Book, chapter: BookChapter): BodyOfflineState {
            require(!book.isAudio && !book.isVideo) {
                "body completeness requires a text book: ${book.bookUrl}"
            }
            if (chapter.isVolume) {
                return BodyOfflineState(
                    contentAvailable = true,
                    requiredImageCount = 0,
                    missingImageCount = 0,
                    invalidImageCount = 0,
                )
            }
            val content = BookHelp.readStoredContent(book, chapter)
                ?: return BodyOfflineState(
                    contentAvailable = false,
                    requiredImageCount = 0,
                    missingImageCount = 0,
                    invalidImageCount = 0,
                )
            val imageUrls = linkedSetOf<String>()
            val matcher = AppPattern.imgPattern.matcher(content)
            while (matcher.find()) {
                val source = matcher.group(1)?.takeIf { it.isNotBlank() } ?: continue
                imageUrls += NetworkUtils.getAbsoluteURL(chapter.url, source)
            }
            var missing = 0
            var invalid = 0
            var firstProblem: String? = null
            imageUrls.forEach { source ->
                val image = BookHelp.getImage(book, source)
                when {
                    !image.isFile || image.length() <= 0L -> {
                        missing += 1
                        if (firstProblem == null) firstProblem = source
                    }
                    !isReadableImage(image) -> {
                        invalid += 1
                        if (firstProblem == null) firstProblem = source
                    }
                }
            }
            return BodyOfflineState(
                contentAvailable = true,
                requiredImageCount = imageUrls.size,
                missingImageCount = missing,
                invalidImageCount = invalid,
                firstProblemImage = firstProblem,
            )
        }

        fun isComplete(book: Book, chapter: BookChapter): Boolean =
            inspect(book, chapter).isComplete

        internal fun isStoredImageComplete(book: Book, source: String): Boolean =
            isReadableImage(BookHelp.getImage(book, source))

        private fun isReadableImage(file: File): Boolean {
            if (!file.isFile || file.length() <= 0L) return false
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, options)
            return options.outWidth > 0 || options.outHeight > 0 ||
                SvgUtils.getSize(file.absolutePath) != null
        }
    }
}
