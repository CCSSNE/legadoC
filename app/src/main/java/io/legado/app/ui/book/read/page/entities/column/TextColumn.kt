package io.legado.app.ui.book.read.page.entities.column

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.os.Build
import androidx.annotation.Keep
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.ThemeStore
import io.legado.app.ui.book.read.page.ContentTextView
import io.legado.app.ui.book.read.page.entities.TextLine
import io.legado.app.ui.book.read.page.entities.TextLine.Companion.emptyTextLine
import io.legado.app.ui.book.read.page.provider.ChapterProvider
import io.legado.app.utils.dpToPx
import splitties.init.appCtx

/**
 * 文字列
 */
@Keep
data class TextColumn(
    override var start: Float,
    override var end: Float,
    override val charData: String,
    var bookmarkStyle: Int = 0,
    var bookmarkColor: Int = 0,
    var bookmarkTime: Long = 0,
) : TextBaseColumn {

    override var textLine: TextLine = emptyTextLine

    override var selected: Boolean = false
        set(value) {
            if (field != value) {
                textLine.invalidate()
            }
            field = value
        }
    override var isSearchResult: Boolean = false
        set(value) {
            if (field != value) {
                textLine.invalidate()
                if (value) {
                    textLine.searchResultColumnCount++
                } else {
                    textLine.searchResultColumnCount--
                }
            }
            field = value
        }

    override fun draw(view: ContentTextView, canvas: Canvas) {
        val bookmarkStyleValue = bookmarkStyle
        if (bookmarkStyleValue == io.legado.app.data.entities.BookmarkStyle.HIGHLIGHT) {
            // 高亮作为背景层先绘制，文字绘制在其上，避免颜色叠加导致文字对比度下降
            drawHighlightBackground(canvas)
        }
        val textPaint = if (textLine.isTitle) {
            ChapterProvider.titlePaint
        } else {
            ChapterProvider.contentPaint
        }
        val textColor = when {
            textLine.isReadAloud || isSearchResult -> ReadBookConfig.textAccentColor
            bookmarkStyleValue == io.legado.app.data.entities.BookmarkStyle.TEXT_COLOR -> {
                if (bookmarkColor != 0) bookmarkColor else appCtx.accentColor
            }
            else -> ReadBookConfig.textColor
        }
        val enablePaperInk = !textLine.isReadAloud && !isSearchResult
        if (textPaint.color != textColor) {
            textPaint.color = textColor
        }
        val y = textLine.lineBase - textLine.lineTop
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            val letterSpacing = textPaint.letterSpacing * textPaint.textSize
            val letterSpacingHalf = letterSpacing * 0.5f
            view.drawTextWithPaperInk(canvas, charData, start + letterSpacingHalf, y, textPaint, enablePaperInk)
        } else {
            view.drawTextWithPaperInk(canvas, charData, start, y, textPaint, enablePaperInk)
        }
        if (
            bookmarkStyleValue == io.legado.app.data.entities.BookmarkStyle.SINGLE_UNDERLINE ||
            bookmarkStyleValue == io.legado.app.data.entities.BookmarkStyle.DOUBLE_UNDERLINE ||
            bookmarkStyleValue == io.legado.app.data.entities.BookmarkStyle.WAVE_UNDERLINE ||
            bookmarkStyleValue == io.legado.app.data.entities.BookmarkStyle.STRIKETHROUGH
        ) {
            drawBookmarkDecoration(canvas)
        }
        if (selected) {
            canvas.drawRect(start, 0f, end, textLine.height, view.selectedPaint)
        }
    }

    private fun drawHighlightBackground(canvas: Canvas) {
        val color = if (bookmarkColor != 0) bookmarkColor else appCtx.accentColor
        val red = Color.red(color)
        val green = Color.green(color)
        val blue = Color.blue(color)
        val paint = Paint().apply {
            this.color = Color.argb(0x66, red, green, blue)
            style = Paint.Style.FILL
        }
        canvas.drawRect(start, 0f, end, textLine.height, paint)
    }

    private fun drawBookmarkDecoration(canvas: Canvas) {
        val color = if (bookmarkColor != 0) bookmarkColor else appCtx.accentColor
        when (bookmarkStyle) {
            io.legado.app.data.entities.BookmarkStyle.SINGLE_UNDERLINE -> {
                val paint = Paint().apply {
                    this.color = color
                    strokeWidth = 1.5f.dpToPx()
                    style = Paint.Style.STROKE
                }
                val y = underlineY()
                canvas.drawLine(start, y, end, y, paint)
            }

            io.legado.app.data.entities.BookmarkStyle.DOUBLE_UNDERLINE -> {
                val paint = Paint().apply {
                    this.color = color
                    strokeWidth = 1.5f.dpToPx()
                    style = Paint.Style.STROKE
                }
                val y1 = underlineY()
                val y2 = y1 + 3f.dpToPx()
                canvas.drawLine(start, y1, end, y1, paint)
                canvas.drawLine(start, y2, end, y2, paint)
            }

            io.legado.app.data.entities.BookmarkStyle.WAVE_UNDERLINE -> {
                val paint = Paint().apply {
                    this.color = color
                    strokeWidth = 1.5f.dpToPx()
                    style = Paint.Style.STROKE
                    isAntiAlias = true
                }
                val y = underlineY()
                val waveLength = 6f.dpToPx()
                val amplitude = 2f.dpToPx()
                val path = Path()
                path.moveTo(start, y)
                var x = start
                var up = true
                while (x < end) {
                    val nextX = minOf(x + waveLength, end)
                    val targetY = if (up) y - amplitude else y + amplitude
                    path.quadTo(
                        (x + nextX) / 2f,
                        targetY,
                        nextX,
                        y
                    )
                    x = nextX
                    up = !up
                }
                canvas.drawPath(path, paint)
            }

            io.legado.app.data.entities.BookmarkStyle.STRIKETHROUGH -> {
                val paint = Paint().apply {
                    this.color = color
                    strokeWidth = 1.5f.dpToPx()
                    style = Paint.Style.STROKE
                }
                val baseline = textLine.lineBase - textLine.lineTop
                val fontMetrics = if (textLine.isTitle) {
                    ChapterProvider.titlePaint.fontMetrics
                } else {
                    ChapterProvider.contentPaint.fontMetrics
                }
                // 删除线画在文字中线（基线向上半个 ascent 处），与系统删除线位置一致
                val y = baseline + fontMetrics.ascent * 0.5f
                canvas.drawLine(start, y, end, y, paint)
            }
        }
    }

    private fun underlineY(): Float {
        val baseline = textLine.lineBase - textLine.lineTop
        return baseline + 2f.dpToPx()
    }

}
