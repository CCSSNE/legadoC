package io.legado.app.lib.theme.surface

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import kotlin.math.roundToInt

/**
 * 在同一个裁剪路径内依次绘制模糊底图、半透明着色和描边。
 * 圆角不再只属于盖在最上面的颜色层，因此底图不会从圆角外泄漏。
 */
class SurfaceDrawable(
    private val backdrop: Bitmap?,
    val style: SurfaceStyle
) : Drawable() {

    private val path = Path()
    private val boundsF = RectF()
    private val backdropPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val tintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = this@SurfaceDrawable.style.tintColor
    }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = this@SurfaceDrawable.style.strokeColor
        style = Paint.Style.STROKE
        strokeWidth = this@SurfaceDrawable.style.strokeWidthPx
    }
    private var drawableAlpha = 255

    override fun onBoundsChange(bounds: Rect) {
        super.onBoundsChange(bounds)
        rebuildPath(bounds)
    }

    override fun draw(canvas: Canvas) {
        if (bounds.isEmpty) return
        val save = canvas.save()
        canvas.clipPath(path)
        backdrop?.takeUnless(Bitmap::isRecycled)?.let {
            backdropPaint.alpha = drawableAlpha
            if (style.backdropImageFitInside) {
                canvas.drawBitmap(it, null, fitCenterRect(it, boundsF), backdropPaint)
            } else {
                canvas.drawBitmap(it, centerCropSrcRect(it, boundsF), bounds, backdropPaint)
            }
        }
        tintPaint.alpha = (android.graphics.Color.alpha(style.tintColor) * drawableAlpha / 255f)
            .roundToInt()
        canvas.drawPath(path, tintPaint)
        canvas.restoreToCount(save)

        if (style.strokeWidthPx > 0f && android.graphics.Color.alpha(style.strokeColor) > 0) {
            strokePaint.alpha = (android.graphics.Color.alpha(style.strokeColor) * drawableAlpha / 255f)
                .roundToInt()
            canvas.drawPath(path, strokePaint)
        }
    }

    /**
     * 等比居中裁剪源矩形：底图与表面宽高比不一致时保持铺满不变形。
     * 模糊底图按目标区域采样生成、宽高比一致，此时裁剪结果等于全图，视觉不变。
     */
    private fun centerCropSrcRect(bitmap: Bitmap, dst: RectF): Rect {
        val bitmapRatio = bitmap.width.toFloat() / bitmap.height.toFloat()
        val targetRatio = dst.width() / dst.height()
        return if (bitmapRatio > targetRatio) {
            val cropWidth = (bitmap.height * targetRatio).roundToInt()
            val left = (bitmap.width - cropWidth) / 2
            Rect(left, 0, left + cropWidth, bitmap.height)
        } else {
            val cropHeight = (bitmap.width / targetRatio).roundToInt()
            val top = (bitmap.height - cropHeight) / 2
            Rect(0, top, bitmap.width, top + cropHeight)
        }
    }

    private fun fitCenterRect(bitmap: Bitmap, dst: RectF): RectF {
        val scale = minOf(
            dst.width() / bitmap.width.toFloat(),
            dst.height() / bitmap.height.toFloat()
        )
        val width = bitmap.width * scale
        val height = bitmap.height * scale
        val left = dst.centerX() - width / 2f
        val top = dst.centerY() - height / 2f
        return RectF(left, top, left + width, top + height)
    }

    override fun getOutline(outline: Outline) {
        if (bounds.isEmpty) return
        when (style.corners) {
            SurfaceCorners.NONE -> outline.setRect(bounds)
            SurfaceCorners.ALL -> outline.setRoundRect(bounds, style.cornerRadiusPx)
            SurfaceCorners.TOP -> outline.setConvexPath(path)
        }
        outline.alpha = android.graphics.Color.alpha(style.tintColor) / 255f
    }

    override fun setAlpha(alpha: Int) {
        drawableAlpha = alpha.coerceIn(0, 255)
        invalidateSelf()
    }

    override fun getAlpha(): Int = drawableAlpha

    override fun setColorFilter(colorFilter: ColorFilter?) {
        backdropPaint.colorFilter = colorFilter
        tintPaint.colorFilter = colorFilter
        strokePaint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    private fun rebuildPath(bounds: Rect) {
        boundsF.set(bounds)
        path.reset()
        val radius = style.cornerRadiusPx.coerceAtLeast(0f)
        when (style.corners) {
            SurfaceCorners.NONE -> path.addRect(boundsF, Path.Direction.CW)
            SurfaceCorners.ALL -> path.addRoundRect(boundsF, radius, radius, Path.Direction.CW)
            SurfaceCorners.TOP -> path.addRoundRect(
                boundsF,
                floatArrayOf(radius, radius, radius, radius, 0f, 0f, 0f, 0f),
                Path.Direction.CW
            )
        }
    }
}
