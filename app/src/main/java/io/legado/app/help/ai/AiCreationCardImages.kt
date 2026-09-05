package io.legado.app.help.ai

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import androidx.exifinterface.media.ExifInterface
import io.legado.app.constant.AppLog
import io.legado.app.constant.PreferKey
import io.legado.app.utils.getPrefInt
import io.legado.app.utils.putPrefInt
import splitties.init.appCtx
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import kotlin.math.roundToInt
import kotlin.math.sqrt

object AiCreationCardImages {

    private const val DIR_NAME = "creation_images"
    private val REF_REGEX = Regex("creation_images/[A-Za-z0-9_.\\-]+")
    private val MARKDOWN_REF_REGEX = Regex("!\\[[^\\]]*]\\((creation_images/[^)\\s]+)\\)")

    /** 发送图片最大分辨率默认值：100 万像素（约 1024×1024），输入按万像素 */
    const val DEFAULT_SEND_IMAGE_WAN_PIXELS = 100
    const val MIN_SEND_IMAGE_WAN_PIXELS = 10
    const val MAX_SEND_IMAGE_WAN_PIXELS = 5000

    /** 编辑器宫格图片总高度默认值（像素，近似 dp），单张/两宫格/四宫格共用 */
    const val DEFAULT_GRID_IMAGE_HEIGHT = 240
    const val MIN_GRID_IMAGE_HEIGHT = 80
    const val MAX_GRID_IMAGE_HEIGHT = 1000

    /** 压缩重编码 JPEG 质量 */
    private const val SEND_IMAGE_JPEG_QUALITY = 85

    val dir: File
        get() = File(appCtx.filesDir, DIR_NAME).apply { mkdirs() }

    /**
     * 发送图片最大分辨率（万像素）：所有把图片发给 AI 的路径统一在此出口压缩，
     * 保持长宽比把总像素压到预算附近；实际预算 = 值 × 1_000_000 总像素。
     */
    var sendImageMaxWanPixels: Int
        get() = appCtx.getPrefInt(PreferKey.aiSendImageMaxPixels, DEFAULT_SEND_IMAGE_WAN_PIXELS)
            .coerceIn(MIN_SEND_IMAGE_WAN_PIXELS, MAX_SEND_IMAGE_WAN_PIXELS)
        set(value) = appCtx.putPrefInt(
            PreferKey.aiSendImageMaxPixels,
            value.coerceIn(MIN_SEND_IMAGE_WAN_PIXELS, MAX_SEND_IMAGE_WAN_PIXELS)
        )

    private val sendImageMaxTotalPixels: Long
        get() = sendImageMaxWanPixels.toLong() * 1_000_000L

    /**
     * 编辑器宫格图片总高度：插入图片弹窗里调，全局一个值，单张与宫格共用；
     * 编辑器内按此高度成框显示（宫格按比例缩到框满，单张按比例限高）。
     */
    var gridImageHeight: Int
        get() = appCtx.getPrefInt(PreferKey.aiCreationGridImageHeight, DEFAULT_GRID_IMAGE_HEIGHT)
            .coerceIn(MIN_GRID_IMAGE_HEIGHT, MAX_GRID_IMAGE_HEIGHT)
        set(value) = appCtx.putPrefInt(
            PreferKey.aiCreationGridImageHeight,
            value.coerceIn(MIN_GRID_IMAGE_HEIGHT, MAX_GRID_IMAGE_HEIGHT)
        )

    fun import(uri: Uri, prefix: String): String? {
        return runCatching {
            val resolver = appCtx.contentResolver
            val ext = when (resolver.getType(uri)) {
                "image/png" -> "png"
                "image/webp" -> "webp"
                "image/gif" -> "gif"
                "image/bmp" -> "bmp"
                else -> "jpg"
            }
            val name = "${prefix}_${System.currentTimeMillis()}.$ext"
            val target = File(dir, name)
            resolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output ->
                    input.copyTo(output)
                }
            } ?: return null
            "creation_images/$name"
        }.getOrNull()
    }

    fun fileOf(ref: String): File? {
        val name = ref.substringAfterLast('/')
        if (name.contains("..")) return null
        val file = File(dir, name)
        return if (file.isFile) file else null
    }

    fun cleanup(content: String) {
        REF_REGEX.findAll(content)
            .map { it.value.substringAfterLast('/') }
            .filter { !it.contains("..") }
            .distinct()
            .forEach { name ->
                runCatching { File(dir, name).delete() }
            }
    }

    /** 按出现顺序取正文里全部图片引用（不去重，同一文件多处出现各算一处） */
    fun markdownRefs(text: String): List<String> =
        MARKDOWN_REF_REGEX.findAll(text).map { it.groupValues[1] }.toList()

    /** 把正文里的图片引用整段替换为 replacementOf(ref) 的返回值（编号标记由调用方统一编号） */
    fun replaceMarkdownRefs(text: String, replacementOf: (String) -> String): String =
        MARKDOWN_REF_REGEX.replace(text) { replacementOf(it.groupValues[1]) }

    /**
     * 引用转 base64 data URL：文件缺失或格式不明直接报错，不静默降级。
     * 全应用唯一把图片发给 AI 的出口：总像素超出"发送图片最大分辨率"预算的图片
     * 在此统一等比压缩重编码（LLM 输入份与生图/生视频请求共用，溯源记录的即实际发送的版本）；
     * 原图不超预算则按原文件字节发送。
     */
    fun dataUrlOf(ref: String): String {
        val file = fileOf(ref)
            ?: throw IllegalStateException("图片文件不存在：${ref.substringAfterLast('/')}")
        compressForSend(file)?.let { (mime, bytes) ->
            return "data:$mime;base64,${Base64.encodeToString(bytes, Base64.NO_WRAP)}"
        }
        val bytes = file.readBytes()
        return "data:${mimeOf(ref)};base64,${Base64.encodeToString(bytes, Base64.NO_WRAP)}"
    }

    /**
     * 等比压缩到总像素预算附近：保持长宽比，超预算才压缩，重编码 JPEG（透明底铺白，避免黑底）；
     * EXIF 旋转先烘焙进位图，避免压缩后方向错乱；不超预算返回 null（按原文件字节发送）。
     */
    private fun compressForSend(file: File): Pair<String, ByteArray>? {
        val maxPixels = sendImageMaxTotalPixels
        if (maxPixels <= 0) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        val width = bounds.outWidth
        val height = bounds.outHeight
        if (width <= 0 || height <= 0) return null
        //EXIF 方向在解码后烘焙，90°/270° 时宽高互换后参与预算计算
        val rotationDegrees = runCatching {
            ExifInterface(file.absolutePath).rotationDegrees
        }.getOrDefault(0)
        val swap = rotationDegrees == 90 || rotationDegrees == 270
        val effectiveWidth = if (swap) height else width
        val effectiveHeight = if (swap) width else height
        val totalPixels = effectiveWidth.toLong() * effectiveHeight.toLong()
        if (totalPixels <= maxPixels) return null
        val scale = sqrt(maxPixels.toDouble() / totalPixels)
        val targetWidth = maxOf(1, (effectiveWidth * scale).roundToInt())
        val targetHeight = maxOf(1, (effectiveHeight * scale).roundToInt())
        //先按采样率粗解码限制内存，再精确缩放到目标尺寸
        var sampleSize = 1
        while (width / (sampleSize * 2) >= targetWidth && height / (sampleSize * 2) >= targetHeight) {
            sampleSize *= 2
        }
        val decoded = BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = sampleSize }
        ) ?: return null
        val rotated = if (rotationDegrees != 0) {
            val matrix = android.graphics.Matrix().apply { postRotate(rotationDegrees.toFloat()) }
            Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
                .also { if (it != decoded) decoded.recycle() }
        } else {
            decoded
        }
        val scaled = if (rotated.width != targetWidth || rotated.height != targetHeight) {
            Bitmap.createScaledBitmap(rotated, targetWidth, targetHeight, true)
                .also { if (it != rotated) rotated.recycle() }
        } else {
            rotated
        }
        //JPEG 无透明通道，透明底统一铺白
        val flattened = if (scaled.hasAlpha()) {
            val base = Bitmap.createBitmap(scaled.width, scaled.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(base)
            canvas.drawColor(Color.WHITE)
            canvas.drawBitmap(scaled, 0f, 0f, null)
            scaled.recycle()
            base
        } else {
            scaled
        }
        val bytes = ByteArrayOutputStream().use { buffer ->
            flattened.compress(Bitmap.CompressFormat.JPEG, SEND_IMAGE_JPEG_QUALITY, buffer)
            buffer.toByteArray()
        }
        flattened.recycle()
        AppLog.put(
            "发送图片压缩：${effectiveWidth}×$effectiveHeight → $targetWidth×$targetHeight"
                + "（${file.name}，${bytes.size} 字节）"
        )
        return "image/jpeg" to bytes
    }

    private fun mimeOf(ref: String): String {
        val name = ref.substringAfterLast('/')
        return when (name.substringAfterLast('.', "").lowercase()) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            "bmp" -> "image/bmp"
            else -> throw IllegalStateException("图片格式不支持：$name")
        }
    }

    /** 按自定义文件名把引用图片存入相册 Pictures/Legado（保存全部按序号命名用） */
    fun saveToAlbum(context: Context, ref: String, displayName: String): Boolean {
        val file = fileOf(ref) ?: return false
        return kotlin.runCatching {
            val mime = mimeOf(ref)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
                    put(MediaStore.Images.Media.MIME_TYPE, mime)
                    put(
                        MediaStore.Images.Media.RELATIVE_PATH,
                        "${Environment.DIRECTORY_PICTURES}/Legado"
                    )
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
                val uri = context.contentResolver.insert(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    values
                ) ?: return false
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    file.inputStream().use { it.copyTo(out) }
                } ?: return false
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                context.contentResolver.update(uri, values, null, null)
                true
            } else {
                @Suppress("DEPRECATION")
                val legacyDir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                    "Legado"
                )
                if (!legacyDir.exists() && !legacyDir.mkdirs()) return false
                val target = File(legacyDir, displayName)
                FileOutputStream(target).use { out ->
                    file.inputStream().use { it.copyTo(out) }
                }
                true
            }
        }.getOrDefault(false)
    }
}
