package io.legado.app.ui.book.read.page.entities.column

import android.graphics.Canvas
import android.graphics.RectF
import androidx.annotation.Keep
import io.legado.app.model.ImageProvider
import io.legado.app.model.ReadBook
import io.legado.app.ui.book.read.page.ContentTextView
import io.legado.app.ui.book.read.page.entities.TextLine
import io.legado.app.ui.book.read.page.entities.TextLine.Companion.emptyTextLine
import io.legado.app.utils.dpToPx
import io.legado.app.utils.toastOnUi
import splitties.init.appCtx

/**
 * 图片列
 */
@Keep
data class ImageColumn(
    override var start: Float,
    override var end: Float,
    var src: String,
    var click: String? = null,
    var lazyLoad: Boolean = false
) : BaseColumn {

    override var textLine: TextLine = emptyTextLine
    override fun draw(view: ContentTextView, canvas: Canvas) {
        val book = ReadBook.book ?: return

        val height = textLine.height

        val width = (end - start).toInt().coerceAtLeast(1)
        val bitmap = if (lazyLoad && !ImageProvider.isImageExist(book, src)) {
            ImageProvider.cacheImageAsync(
                book = book,
                src = src,
                bookSource = ReadBook.bookSource,
                width = width,
                height = height.toInt().coerceAtLeast(1)
            ) {
                textLine.invalidate()
            }
            ImageProvider.loadingBitmap
        } else {
            ImageProvider.getImage(
                book,
                src,
                width,
                height.toInt()
            )
        }

        val rectF = if (textLine.isImage) {
            RectF(start, 0f, end, height)
        } else {
            /*以宽度为基准保持图片的原始比例叠加，当div为负数时，允许高度比字符更高*/
            val h = (end - start) / bitmap.width * bitmap.height
            val div = (height - h) / 2
            RectF(start, div, end, height - div)
        }
        kotlin.runCatching {
            canvas.drawBitmap(bitmap, null, rectF, view.imagePaint)
        }.onFailure { e ->
            appCtx.toastOnUi(e.localizedMessage)
        }
    }
    override fun isTouch(x: Float): Boolean {
        return x > start && x < end + 20.dpToPx()
    }

}
