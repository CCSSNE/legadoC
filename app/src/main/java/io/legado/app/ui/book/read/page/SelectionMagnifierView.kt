package io.legado.app.ui.book.read.page

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import io.legado.app.utils.dpToPx

/**
 * 圆形选区放大镜
 * 画面内容中心跟随"动杆"（正在移动的选区手柄尖端），镜片位置跟随手指（画在手指上方留一段距离），
 * 原样 1x 投影，把被手指/动杆遮挡的文字投影出来
 */
class SelectionMagnifierView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var sourceView: View? = null
    private var overlayViews: List<View> = emptyList()
    private var sourceBitmap: Bitmap? = null

    // 动杆尖端（画面内容中心）
    private var handleX = 0f
    private var handleY = 0f
    // 手指（镜片位置）
    private var fingerX = 0f
    private var fingerY = 0f
    private var fingerSet = false
    private var showing = false

    private val radius = 60f.dpToPx()
    private val zoom = 1.0f
    private val gap = 36f.dpToPx()

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f.dpToPx()
        color = Color.WHITE
        setShadowLayer(4f.dpToPx(), 0f, 1f.dpToPx(), 0x66000000)
    }
    private val srcRect = Rect()
    private val dstRect = RectF()
    private val clipPath = Path()

    fun bindSource(view: View) {
        sourceView = view
    }

    /**
     * 将正文上方的选区手柄作为投影源的一部分绘入放大镜。
     */
    fun bindOverlays(vararg views: View) {
        overlayViews = views.toList()
    }

    /**
     * 更新动杆尖端位置：画面内容以它为中心（杆固定在画面中间）
     */
    fun setHandle(x: Float, y: Float) {
        handleX = x
        handleY = y
        if (!showing) {
            showing = true
            visibility = VISIBLE
        }
        invalidate()
    }

    /**
     * 更新手指位置：镜片跟随手指，画在手指上方
     */
    fun setFinger(x: Float, y: Float) {
        fingerX = x
        fingerY = y
        fingerSet = true
        if (showing) invalidate()
    }

    fun dismiss() {
        if (!showing && visibility != VISIBLE) return
        showing = false
        fingerSet = false
        visibility = GONE
    }

    override fun onDraw(canvas: Canvas) {
        if (!showing) return
        val src = sourceView ?: return
        val w = src.width
        val h = src.height
        if (w <= 0 || h <= 0) return
        var bmp = sourceBitmap
        if (bmp == null || bmp.width != w || bmp.height != h) {
            bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            sourceBitmap = bmp
        }
        src.draw(Canvas(bmp))
        drawOverlays(bmp, src)

        // 镜片画在手指上方留 gap 距离（未记录手指时用杆位置兜底）
        val lensX = if (fingerSet) fingerX else handleX
        val lensY = if (fingerSet) fingerY else handleY
        val cx = lensX.coerceIn(radius, width.toFloat() - radius)
        val cy = (lensY - radius - gap).coerceIn(radius, height.toFloat() - radius)

        // 画面内容以动杆尖端为中心，原样 1x 投影
        val srcRadius = radius / zoom
        srcRect.set(
            (handleX - srcRadius).toInt(),
            (handleY - srcRadius).toInt(),
            (handleX + srcRadius).toInt(),
            (handleY + srcRadius).toInt()
        )
        dstRect.set(cx - radius, cy - radius, cx + radius, cy + radius)
        clipPath.reset()
        clipPath.addCircle(cx, cy, radius, Path.Direction.CW)
        canvas.save()
        canvas.clipPath(clipPath)
        canvas.drawBitmap(bmp, srcRect, dstRect, paint)
        canvas.restore()
        canvas.drawCircle(cx, cy, radius, strokePaint)
    }

    private fun drawOverlays(bitmap: Bitmap, source: View) {
        if (overlayViews.isEmpty()) return
        val sourceLocation = IntArray(2)
        source.getLocationOnScreen(sourceLocation)
        val overlayCanvas = Canvas(bitmap)
        for (overlay in overlayViews) {
            if (overlay.visibility != VISIBLE || overlay.width <= 0 || overlay.height <= 0) continue
            val overlayLocation = IntArray(2)
            overlay.getLocationOnScreen(overlayLocation)
            overlayCanvas.save()
            overlayCanvas.translate(
                (overlayLocation[0] - sourceLocation[0]).toFloat(),
                (overlayLocation[1] - sourceLocation[1]).toFloat()
            )
            overlay.draw(overlayCanvas)
            overlayCanvas.restore()
        }
    }
}
