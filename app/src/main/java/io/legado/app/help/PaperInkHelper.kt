package io.legado.app.help

import android.graphics.Canvas
import android.graphics.Paint
import io.legado.app.help.config.ReadBookConfig

object PaperInkHelper {

    val strength: Int
        get() = ReadBookConfig.paperInkStrength

    fun drawBackground(canvas: Canvas, width: Int, height: Int, paint: Paint) {
        // 文字阴影不改背景，避免页面发灰或发黄。
    }

    fun drawText(
        canvas: Canvas,
        text: String,
        start: Int,
        end: Int,
        x: Float,
        y: Float,
        paint: Paint,
        enableBlend: Boolean = true
    ) {
        if (strength <= 0 || !enableBlend) {
            canvas.drawText(text, start, end, x, y, paint)
            return
        }
        drawTextBlock(canvas, paint) {
            canvas.drawText(text, start, end, x, y, paint)
        }
    }

    fun drawText(
        canvas: Canvas,
        text: String,
        x: Float,
        y: Float,
        paint: Paint,
        enableBlend: Boolean = true
    ) {
        drawText(canvas, text, 0, text.length, x, y, paint, enableBlend)
    }

    fun drawTextBlock(canvas: Canvas, paint: Paint, draw: () -> Unit) {
        val strength = strength
        if (strength <= 0) {
            draw()
            return
        }
        val ratio = strength / 100f
        val radius = 0.3f + 3.0f * ratio
        val offset = 0.5f + 4.5f * ratio
        paint.setShadowLayer(radius, offset, offset, 0xFF000000.toInt())
        draw()
        paint.clearShadowLayer()
    }

}
