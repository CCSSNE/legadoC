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
    var bookmarkStyleColors: Map<Int, Int> = emptyMap(),
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

    // 复用绘制对象，避免滚动时每帧大量创建 Paint/Path 导致卡顿
    private val highlightPaint by lazy {
        Paint().apply {
            style = Paint.Style.FILL
        }
    }
    private val decorationPaint by lazy {
        Paint().apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.5f.dpToPx()
        }
    }
    private val wavePaint by lazy {
        Paint().apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.5f.dpToPx()
            isAntiAlias = true
        }
    }
    private val wavePath by lazy { Path() }

    override fun draw(view: ContentTextView, canvas: Canvas) {
        val styleMask = bookmarkStyle
        if (styleMask and io.legado.app.data.entities.BookmarkStyle.HIGHLIGHT != 0) {
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
            styleMask and io.legado.app.data.entities.BookmarkStyle.TEXT_COLOR != 0 -> {
                effectColor(io.legado.app.data.entities.BookmarkStyle.TEXT_COLOR)
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
            styleMask and (
                io.legado.app.data.entities.BookmarkStyle.SINGLE_UNDERLINE or
                    io.legado.app.data.entities.BookmarkStyle.DOUBLE_UNDERLINE or
                    io.legado.app.data.entities.BookmarkStyle.WAVE_UNDERLINE or
                    io.legado.app.data.entities.BookmarkStyle.STRIKETHROUGH
                ) != 0
        ) {
            drawBookmarkDecoration(canvas)
        }
        if (selected) {
            canvas.drawRect(start, 0f, end, textLine.height, view.selectedPaint)
        }
    }

    private fun drawHighlightBackground(canvas: Canvas) {
        val color = effectColor(io.legado.app.data.entities.BookmarkStyle.HIGHLIGHT)
        val red = Color.red(color)
        val green = Color.green(color)
        val blue = Color.blue(color)
        highlightPaint.color = Color.argb(0x66, red, green, blue)
        canvas.drawRect(start, 0f, end, textLine.height, highlightPaint)
    }

    private fun drawBookmarkDecoration(canvas: Canvas) {
        if (bookmarkStyle and io.legado.app.data.entities.BookmarkStyle.SINGLE_UNDERLINE != 0) {
            val color = effectColor(io.legado.app.data.entities.BookmarkStyle.SINGLE_UNDERLINE)
            decorationPaint.color = color
            val y = underlineY()
            canvas.drawLine(start, y, end, y, decorationPaint)
        }

        if (bookmarkStyle and io.legado.app.data.entities.BookmarkStyle.DOUBLE_UNDERLINE != 0) {
            val color = effectColor(io.legado.app.data.entities.BookmarkStyle.DOUBLE_UNDERLINE)
            decorationPaint.color = color
            val y1 = underlineY()
            val y2 = y1 + 3f.dpToPx()
            canvas.drawLine(start, y1, end, y1, decorationPaint)
            canvas.drawLine(start, y2, end, y2, decorationPaint)
        }

        if (bookmarkStyle and io.legado.app.data.entities.BookmarkStyle.WAVE_UNDERLINE != 0) {
            val color = effectColor(io.legado.app.data.entities.BookmarkStyle.WAVE_UNDERLINE)
            wavePaint.color = color
            val y = underlineY()
            val waveLength = 6f.dpToPx()
            val amplitude = 2f.dpToPx()
            wavePath.reset()
            wavePath.moveTo(start, y)
            var x = start
            var up = true
            while (x < end) {
                val nextX = minOf(x + waveLength, end)
                val targetY = if (up) y - amplitude else y + amplitude
                wavePath.quadTo(
                    (x + nextX) / 2f,
                    targetY,
                    nextX,
                    y
                )
                x = nextX
                up = !up
            }
            canvas.drawPath(wavePath, wavePaint)
        }

        if (bookmarkStyle and io.legado.app.data.entities.BookmarkStyle.STRIKETHROUGH != 0) {
            val color = effectColor(io.legado.app.data.entities.BookmarkStyle.STRIKETHROUGH)
            decorationPaint.color = color
            val baseline = textLine.lineBase - textLine.lineTop
            val fontMetrics = if (textLine.isTitle) {
                ChapterProvider.titlePaint.fontMetrics
            } else {
                ChapterProvider.contentPaint.fontMetrics
            }
            // 删除线画在文字中线（基线向上半个 ascent 处），与系统删除线位置一致
            val y = baseline + fontMetrics.ascent * 0.5f
            canvas.drawLine(start, y, end, y, decorationPaint)
        }
    }

    private fun underlineY(): Float {
        val baseline = textLine.lineBase - textLine.lineTop
        return baseline + 2f.dpToPx()
    }

    /**
     * 效果颜色优先级：该效果的独立颜色 > 书签全局颜色 > 主题强调色
     */
    private fun effectColor(styleBit: Int): Int {
        bookmarkStyleColors[styleBit]?.let { if (it != 0) return it }
        return if (bookmarkColor != 0) bookmarkColor else appCtx.accentColor
    }

}
