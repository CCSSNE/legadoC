package io.legado.app.help.ai

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.text.Spanned
import android.text.style.ImageSpan
import android.util.LruCache
import android.widget.EditText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Collections

/**
 * 卡片图片在纯文本框里的行内渲染：把 `![](creation_images/xxx)` 这行引用文本
 * 显示成图片（ImageSpan），底下的 markdown 原文原样保留。
 *
 * 只动显示层：复制、存盘、发 LLM 拿到的都是原文本，不受影响；
 * 文件被删则该处恢复显示原文，不做任何兜底替换。
 */
object AiCreationInlineImages {

    private val REF_IN_MARKDOWN = Regex("!\\[[^\\]]*]\\((creation_images/[^)\\s]+)\\)")

    private class CreationImageSpan(drawable: BitmapDrawable, val ref: String) :
        ImageSpan(drawable)

    /** 解码上限约 1.6MP：手机屏显示足够，避免大照片直接吃满内存 */
    private const val MAX_PIXELS = 1280 * 1280

    private val cache = object : LruCache<String, Bitmap>(6 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    private val inFlight =
        Collections.synchronizedSet(mutableSetOf<String>()) as MutableSet<String>

    /**
     * 主线程调用：先用缓存贴图，未命中的引用后台解码后再按当前文本重贴。
     * 重贴时按调用时刻的文本重新匹配，框内容变了也不怕贴错。
     */
    fun refresh(scope: CoroutineScope, edit: EditText) {
        applyCached(edit)
        val missing = REF_IN_MARKDOWN.findAll(edit.text)
            .map { it.groupValues[1] }
            .distinct()
            .filter { cache.get(it) == null && !inFlight.contains(it) }
        if (missing.isEmpty()) return
        inFlight.addAll(missing)
        val targetWidth = contentWidth(edit)
        scope.launch(Dispatchers.IO) {
            missing.forEach { ref ->
                decode(ref, targetWidth)?.let { cache.put(ref, it) }
                inFlight.remove(ref)
            }
            withContext(Dispatchers.Main) {
                runCatching { applyCached(edit) }
            }
        }
    }

    private fun applyCached(edit: EditText) {
        val text = edit.text ?: return
        text.getSpans(0, text.length, CreationImageSpan::class.java)
            .forEach { text.removeSpan(it) }
        val targetWidth = contentWidth(edit)
        if (targetWidth <= 0) return
        REF_IN_MARKDOWN.findAll(text).forEach { match ->
            val bitmap = cache.get(match.groupValues[1]) ?: return@forEach
            val width = minOf(bitmap.width, targetWidth).coerceAtLeast(1)
            val height = (bitmap.height * (width.toFloat() / bitmap.width))
                .toInt().coerceAtLeast(1)
            val drawable = BitmapDrawable(edit.resources, bitmap)
                .apply { setBounds(0, 0, width, height) }
            text.setSpan(
                CreationImageSpan(drawable, match.groupValues[1]),
                match.range.first,
                match.range.last + 1,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
    }

    private fun contentWidth(edit: EditText): Int {
        val viewWidth = edit.width - edit.paddingStart - edit.paddingEnd
        if (viewWidth > 0) return viewWidth
        val metrics = edit.resources.displayMetrics
        return metrics.widthPixels - (32 * metrics.density).toInt()
    }

    private fun decode(ref: String, targetWidth: Int): Bitmap? {
        val file = AiCreationCardImages.fileOf(ref) ?: return null
        if (!file.isFile || file.length() <= 0) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        val rawWidth = bounds.outWidth
        val rawHeight = bounds.outHeight
        if (rawWidth <= 0 || rawHeight <= 0) return null
        var sample = 1
        val minWidth = targetWidth.coerceAtLeast(1)
        while (rawWidth / sample > minWidth ||
            (rawWidth.toLong() / sample) * (rawHeight / sample) > MAX_PIXELS
        ) {
            sample *= 2
            if (sample > 64) break
        }
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        return runCatching {
            BitmapFactory.decodeFile(file.absolutePath, options)
        }.getOrNull()
    }
}
