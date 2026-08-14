package io.legado.app.ui.book.read.page

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import io.legado.app.utils.dpToPx
import kotlin.math.ceil

/**
 * 选区手柄：杆高跟随文字行高，水滴尖端与杆底端使用同一坐标。
 */
class SelectionHandleDrawable(
    private val side: Side,
    rodHeight: Float
) : Drawable() {

    enum class Side {
        LEFT,
        RIGHT
    }

    private val handleWidth = 24.dpToPx().toFloat()
    private val dropletRadius = 8.dpToPx().toFloat()
    private val rodWidth = 1.2f.dpToPx().toFloat()
    private val dropletControlOffset = 3.582f.dpToPx()
    private val dropletControlHeight = 4.418f.dpToPx()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xff595757.toInt()
        style = Paint.Style.FILL
    }
    private val path = Path()
    private var rodHeight = rodHeight.coerceAtLeast(1f)

    override fun draw(canvas: Canvas) {
        val left = bounds.left.toFloat()
        val top = bounds.top.toFloat()
        val centerX = left + bounds.width() / 2f
        val dropletTop = top + rodHeight
        val rodCenterX = if (side == Side.LEFT) {
            centerX + dropletRadius
        } else {
            centerX - dropletRadius
        }

        canvas.drawRect(
            rodCenterX - rodWidth / 2f,
            top,
            rodCenterX + rodWidth / 2f,
            dropletTop,
            paint
        )

        path.reset()
        if (side == Side.LEFT) {
            drawLeftDroplet(centerX, dropletTop)
        } else {
            drawRightDroplet(centerX, dropletTop)
        }
        canvas.drawPath(path, paint)
    }

    private fun drawLeftDroplet(centerX: Float, top: Float) {
        val radius = dropletRadius
        path.moveTo(centerX, top)
        path.lineTo(centerX + radius, top)
        path.lineTo(centerX + radius, top + radius)
        path.cubicTo(
            centerX + radius,
            top + radius + dropletControlHeight,
            centerX + radius - dropletControlOffset,
            top + radius * 2f,
            centerX,
            top + radius * 2f
        )
        path.cubicTo(
            centerX - radius + dropletControlOffset,
            top + radius * 2f,
            centerX - radius,
            top + radius + dropletControlHeight,
            centerX - radius,
            top + radius
        )
        path.cubicTo(
            centerX - radius,
            top + radius - dropletControlHeight,
            centerX - radius + dropletControlOffset,
            top,
            centerX,
            top
        )
        path.close()
    }

    private fun drawRightDroplet(centerX: Float, top: Float) {
        val radius = dropletRadius
        path.moveTo(centerX, top)
        path.lineTo(centerX - radius, top)
        path.lineTo(centerX - radius, top + radius)
        path.cubicTo(
            centerX - radius,
            top + radius + dropletControlHeight,
            centerX - radius + dropletControlOffset,
            top + radius * 2f,
            centerX,
            top + radius * 2f
        )
        path.cubicTo(
            centerX + radius - dropletControlOffset,
            top + radius * 2f,
            centerX + radius,
            top + radius + dropletControlHeight,
            centerX + radius,
            top + radius
        )
        path.cubicTo(
            centerX + radius,
            top + radius - dropletControlHeight,
            centerX + radius - dropletControlOffset,
            top,
            centerX,
            top
        )
        path.close()
    }

    override fun getIntrinsicWidth(): Int = handleWidth.toInt()

    override fun getIntrinsicHeight(): Int = ceil(rodHeight + dropletRadius * 2f).toInt()

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
