package io.legado.app.ui.book.read.page.entities.column

import android.graphics.Canvas
import android.graphics.Paint
import android.text.style.ReplacementSpan
import io.legado.app.ui.book.read.page.ContentTextView
import io.legado.app.ui.book.read.page.entities.ReviewButton
import io.legado.app.ui.book.read.page.entities.TextLine
import io.legado.app.ui.book.read.page.entities.TextLine.Companion.emptyTextLine

/**
 * 零评论段评泡的元数据列：不绘制、不占宽度、永远不可命中。
 */
data class HiddenReviewColumn(
    override var start: Float,
    override var end: Float,
    val reviewButton: ReviewButton,
) : BaseColumn {

    override var textLine: TextLine = emptyTextLine

    override fun draw(view: ContentTextView, canvas: Canvas) = Unit

    override fun isTouch(x: Float): Boolean = false
}

/** 让富文本路径中的零评论泡保留一个零宽语义节点。 */
class HiddenReviewSpan(val reviewButton: ReviewButton) : ReplacementSpan() {

    override fun getSize(
        paint: Paint,
        text: CharSequence?,
        start: Int,
        end: Int,
        fm: Paint.FontMetricsInt?
    ): Int = 0

    override fun draw(
        canvas: Canvas,
        text: CharSequence?,
        start: Int,
        end: Int,
        x: Float,
        top: Int,
        y: Int,
        bottom: Int,
        paint: Paint
    ) = Unit
}
