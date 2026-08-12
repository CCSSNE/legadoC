package io.legado.app.ui.book.read.page.entities.column

import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import androidx.annotation.Keep
import io.legado.app.R
import io.legado.app.help.illustration.AudioBlockPlayer
import io.legado.app.help.illustration.IllustrationHelp
import io.legado.app.model.ImageProvider
import io.legado.app.model.ReadBook
import io.legado.app.ui.book.read.page.ContentTextView
import io.legado.app.ui.book.read.page.entities.TextLine
import io.legado.app.ui.book.read.page.entities.TextLine.Companion.emptyTextLine
import io.legado.app.utils.dpToPx
import io.legado.app.utils.toastOnUi
import splitties.init.appCtx

/**
 * 媒体列：图片 / 视频 / 音频（音频为独立块，不参与宫格）
 */
@Keep
data class ImageColumn(
    override var start: Float,
    override var end: Float,
    var src: String,
    var click: String? = null,
    var lazyLoad: Boolean = false,
    var mediaType: String = "image"
) : BaseColumn {

    override var textLine: TextLine = emptyTextLine

    override fun draw(view: ContentTextView, canvas: Canvas) {
        when (mediaType) {
            "audio" -> drawAudioBlock(view, canvas)
            "video" -> drawVideo(view, canvas)
            else -> drawImage(view, canvas)
        }
    }

    private fun drawImage(view: ContentTextView, canvas: Canvas) {
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

    private fun drawVideo(view: ContentTextView, canvas: Canvas) {
        val book = ReadBook.book ?: return
        val height = textLine.height
        val width = (end - start).toInt().coerceAtLeast(1)
        val file = IllustrationHelp.getImageFile(book, src)
        val bitmap = if (file.exists()) {
            IllustrationHelp.getVideoFrame(file, width, height.toInt().coerceAtLeast(1))
                ?: BitmapFactory.decodeResource(view.resources, R.drawable.image_loading_error)
        } else {
            ImageProvider.loadingBitmap
        }
        val rectF = mediaRectF(height)
        kotlin.runCatching {
            canvas.drawBitmap(bitmap, null, rectF, view.imagePaint)
        }.onFailure { e ->
            appCtx.toastOnUi(e.localizedMessage)
        }
        // 半透明播放键
        val cx = (start + end) / 2f
        val cy = height / 2f
        val radius = minOf(24f.dpToPx(), rectF.height() / 3f)
        val overlayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(110, 0, 0, 0) }
        canvas.drawCircle(cx, cy, radius, overlayPaint)
        drawPlayTriangle(canvas, cx, cy, radius * 0.45f, Color.WHITE)
        // 时长
        if (file.exists()) {
            val duration = IllustrationHelp.getMediaDurationMs(file)
            if (duration > 0) {
                val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.WHITE
                    textSize = 11f.dpToPx()
                    isFakeBoldText = true
                }
                val text = IllustrationHelp.formatDuration(duration)
                val textWidth = textPaint.measureText(text)
                canvas.drawText(
                    text,
                    rectF.right - textWidth - 4f.dpToPx(),
                    rectF.bottom - 3f.dpToPx(),
                    textPaint
                )
            }
        }
    }

    private fun drawAudioBlock(view: ContentTextView, canvas: Canvas) {
        val height = textLine.height
        val rectF = mediaRectF(height)
        val playing = AudioBlockPlayer.playingSrc() == src && AudioBlockPlayer.isPlaying
        val padding = 10f.dpToPx()
        val radius = 8f.dpToPx()
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(28, 0, 0, 0) }
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1f.dpToPx()
            color = Color.argb(70, 0, 0, 0)
        }
        canvas.drawRoundRect(rectF, radius, radius, bgPaint)
        canvas.drawRoundRect(rectF, radius, radius, borderPaint)
        // 播放/暂停按钮（左）
        val btnSize = (height - padding * 2).coerceAtLeast(20f.dpToPx())
        val btnLeft = rectF.left + padding
        val btnTop = rectF.top + (rectF.height() - btnSize) / 2f
        val btnPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(200, 0, 0, 0) }
        canvas.drawRoundRect(
            RectF(btnLeft, btnTop, btnLeft + btnSize, btnTop + btnSize),
            6f.dpToPx(), 6f.dpToPx(), btnPaint
        )
        val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        if (playing) {
            val barWidth = btnSize * 0.12f
            val barHeight = btnSize * 0.45f
            val barY = btnTop + (btnSize - barHeight) / 2f
            canvas.drawRect(
                RectF(btnLeft + btnSize * 0.32f, barY, btnLeft + btnSize * 0.44f, barY + barHeight),
                barPaint
            )
            canvas.drawRect(
                RectF(btnLeft + btnSize * 0.62f, barY, btnLeft + btnSize * 0.74f, barY + barHeight),
                barPaint
            )
        } else {
            drawPlayTriangle(
                canvas,
                btnLeft + btnSize / 2f,
                btnTop + btnSize / 2f,
                btnSize * 0.28f,
                Color.WHITE
            )
        }
        // 进度条（右）+ 时长
        val trackLeft = btnLeft + btnSize + padding
        val trackRight = rectF.right - padding
        val trackTop = btnTop + btnSize * 0.42f
        val trackBottom = btnTop + btnSize * 0.58f
        AudioBlockPlayer.updateProgress()
        val book = ReadBook.book ?: return
        val duration = if (AudioBlockPlayer.durationMs > 0) {
            AudioBlockPlayer.durationMs
        } else {
            IllustrationHelp.getMediaDurationMs(IllustrationHelp.getImageFile(book, src))
        }
        val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(60, 0, 0, 0) }
        canvas.drawRoundRect(
            RectF(trackLeft, trackTop, trackRight, trackBottom),
            2f.dpToPx(), 2f.dpToPx(), trackPaint
        )
        if (duration > 0) {
            val progress = (AudioBlockPlayer.positionMs.toFloat() / duration).coerceIn(0f, 1f)
            val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(190, 0, 0, 0) }
            canvas.drawRoundRect(
                RectF(trackLeft, trackTop, trackLeft + (trackRight - trackLeft) * progress, trackBottom),
                2f.dpToPx(), 2f.dpToPx(), fillPaint
            )
        }
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(170, 0, 0, 0)
            textSize = 11f.dpToPx()
        }
        val durationText = IllustrationHelp.formatDuration(duration)
        canvas.drawText(
            durationText,
            trackRight - textPaint.measureText(durationText),
            btnTop + btnSize - 2f.dpToPx(),
            textPaint
        )
    }

    private fun mediaRectF(height: Float): RectF {
        return RectF(start, 0f, end, height)
    }

    private fun drawPlayTriangle(canvas: Canvas, cx: Float, cy: Float, size: Float, color: Int) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = color }
        val path = Path().apply {
            moveTo(cx - size * 0.6f, cy - size)
            lineTo(cx - size * 0.6f, cy + size)
            lineTo(cx + size, cy)
            close()
        }
        canvas.drawPath(path, paint)
    }

    override fun isTouch(x: Float): Boolean {
        return x > start && x < end + 20.dpToPx()
    }

}
