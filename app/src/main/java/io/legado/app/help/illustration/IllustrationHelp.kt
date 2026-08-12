package io.legado.app.help.illustration

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.graphics.pdf.PdfRenderer
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Size
import android.webkit.MimeTypeMap
import androidx.collection.LruCache
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookIllustration
import io.legado.app.help.book.BookHelp
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.FileUtils
import io.legado.app.utils.GSON
import io.legado.app.utils.createFileIfNotExist
import io.legado.app.utils.externalFiles
import io.legado.app.utils.getFile
import io.legado.app.utils.writeBytes
import splitties.init.appCtx
import java.io.File
import java.io.ByteArrayOutputStream
import java.io.FileOutputStream
import java.util.UUID

/**
 * 配图（插图）存储、指纹与导出/导入辅助
 *
 * 图片文件存放在 externalFiles/illustrations/{bookFolder}/ 下，
 * 独立于章节缓存，便于纳入备份、恢复与迁移。
 */
object IllustrationHelp {

    const val SRC_PREFIX = "illustration://"
    const val ILLUSTRATIONS_DIR_NAME = "illustrations"
    const val EXPORT_JSON_NAME = "illustrations.json"
    const val EPUB_SIDECAR_NAME = "legado_illustrations.json"
    const val EXPORT_IMAGES_DIR = "images"
    const val EXPORT_JSON_VERSION = 1

    val VIDEO_EXTS = setOf("mp4", "webm", "mkv", "mov", "m4v", "3gp", "avi", "ts", "flv")
    val AUDIO_EXTS = setOf(
        "mp3", "m4a", "aac", "wav", "flac", "ogg", "opus", "wma", "amr", "mid", "midi", "3ga"
    )
    val IMAGE_EXTS = setOf(
        "jpg", "jpeg", "png", "webp", "gif", "bmp", "svg", "heic", "heif", "avif", "ico"
    )

    /** 指纹窗口长度 */
    private const val FINGERPRINT_LENGTH = 24

    fun newSrc(ext: String): String {
        // 入参可能是 "png"/"mp4"（无点号），substringAfterLast 找不到分隔符时返回原串，
        // 不能像旧实现那样默认成 jpg，否则视频/音频全部被存成图片扩展名
        val safeExt = ext.substringAfterLast('.').lowercase().ifBlank { "jpg" }
        return "$SRC_PREFIX${UUID.randomUUID()}.$safeExt"
    }

    fun getImageDir(book: Book): File {
        return appCtx.externalFiles.getFile(ILLUSTRATIONS_DIR_NAME, book.getFolderName())
            .apply { mkdirs() }
    }

    fun getImageFile(book: Book, src: String): File {
        val name = src.substringAfter(SRC_PREFIX).substringBeforeLast('.')
            .ifBlank { return File(getImageDir(book), "missing.jpg") }
        val ext = src.substringAfterLast('.', "jpg")
        return File(getImageDir(book), "$name.$ext")
    }

    fun saveImage(book: Book, src: String, bytes: ByteArray): File {
        val file = getImageFile(book, src)
        FileUtils.createFileIfNotExist(file.absolutePath).writeBytes(bytes)
        return file
    }

    fun deleteImages(book: Book, srcs: List<String>) {
        srcs.forEach { src ->
            kotlin.runCatching { getImageFile(book, src).delete() }
        }
    }

    /** 媒体类型：image / video / audio（按扩展名推断） */
    fun srcType(src: String): String {
        val ext = src.substringAfterLast('.', "").lowercase()
        return when {
            ext in VIDEO_EXTS -> "video"
            ext in AUDIO_EXTS -> "audio"
            else -> "image"
        }
    }

    fun isImageSrc(src: String): Boolean = srcType(src) == "image"

    fun isVideoSrc(src: String): Boolean = srcType(src) == "video"

    fun isAudioSrc(src: String): Boolean = srcType(src) == "audio"

    /** 从系统文件选择器返回的 Uri 读取显示名（优先取文件名扩展名判断类型），失败返回 null */
    fun queryDisplayName(context: Context, uri: Uri): String? {
        var name: String? = null
        runCatching {
            context.contentResolver.query(
                uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null
            )?.use { c ->
                if (c.moveToFirst()) name = c.getString(0)
            }
        }
        return name?.takeIf { it.isNotBlank() }
            ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
    }

    /**
     * 确定媒体扩展名，按可靠性依次：
     * 1. 文件名扩展名（文件选择器可能返回错误的 MIME，甚至把视频/音频报成 image 类）；
     * 2. MIME 映射；
     * 3. 文件头嗅探。
     */
    fun resolveMediaExt(name: String?, mime: String?, bytes: ByteArray): String {
        val nameExt = name?.substringAfterLast('.', "")?.lowercase()?.trim()
        if (nameExt != null && (nameExt in VIDEO_EXTS || nameExt in AUDIO_EXTS || nameExt in IMAGE_EXTS)) {
            return nameExt
        }
        val mimeExt = when {
            mime?.startsWith("video/") == true ->
                MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)
                    ?.takeIf { it.isNotBlank() } ?: "mp4"
            mime?.startsWith("audio/") == true ->
                MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)
                    ?.takeIf { it.isNotBlank() } ?: "mp3"
            mime?.startsWith("image/") == true ->
                MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)
                    ?.takeIf { it.isNotBlank() } ?: "jpg"
            else -> null
        }
        if (mimeExt != null) return mimeExt
        return sniffMediaExt(bytes)
    }

    /** 按文件头判断容器类型，识别不了时按图片处理 */
    private fun sniffMediaExt(bytes: ByteArray): String {
        if (bytes.size >= 12) {
            val b = bytes
            // ISO BMFF：mp4 / m4a / mov 等，offset 4 起为 "ftyp"
            if (b[4] == 'f'.code.toByte() && b[5] == 't'.code.toByte() &&
                b[6] == 'y'.code.toByte() && b[7] == 'p'.code.toByte()
            ) {
                val brand = String(b, 8, 4, Charsets.ISO_8859_1)
                return if (brand.startsWith("M4A") || brand.startsWith("M4B")) "m4a" else "mp4"
            }
            // EBML：webm / mkv
            if (b[0] == 0x1A.toByte() && b[1] == 0x45.toByte() &&
                b[2] == 0xDF.toByte() && b[3] == 0xA3.toByte()
            ) {
                return "webm"
            }
            // RIFF：wav / avi / webp
            if (b[0] == 'R'.code.toByte() && b[1] == 'I'.code.toByte() &&
                b[2] == 'F'.code.toByte() && b[3] == 'F'.code.toByte()
            ) {
                return when (String(b, 8, 4, Charsets.ISO_8859_1)) {
                    "WAVE" -> "wav"
                    "AVI " -> "avi"
                    "WEBP" -> "webp"
                    else -> "jpg"
                }
            }
        }
        if (bytes.size >= 4) {
            if (bytes[0] == 'f'.code.toByte() && bytes[1] == 'L'.code.toByte() &&
                bytes[2] == 'a'.code.toByte() && bytes[3] == 'C'.code.toByte()
            ) return "flac"
            if (bytes[0] == 'O'.code.toByte() && bytes[1] == 'g'.code.toByte() &&
                bytes[2] == 'g'.code.toByte() && bytes[3] == 'S'.code.toByte()
            ) return "ogg"
        }
        if (bytes.size >= 3 && bytes[0] == 'I'.code.toByte() && bytes[1] == 'D'.code.toByte() &&
            bytes[2] == '3'.code.toByte()
        ) return "mp3"
        // MP3 帧同步：FF Ex
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && (bytes[1].toInt() and 0xE0) == 0xE0) {
            return "mp3"
        }
        // JPEG / PNG / GIF
        if (bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() &&
            bytes[2] == 0xFF.toByte()
        ) return "jpg"
        if (bytes.size >= 8 && bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte() &&
            bytes[2] == 'N'.code.toByte() && bytes[3] == 'G'.code.toByte()
        ) return "png"
        if (bytes.size >= 6 && bytes[0] == 'G'.code.toByte() && bytes[1] == 'I'.code.toByte() &&
            bytes[2] == 'F'.code.toByte() && bytes[3] == '8'.code.toByte()
        ) return "gif"
        return "jpg"
    }

    /** 音频/视频时长（毫秒），失败返回 0 */
    private val mediaDurationCache = object : LruCache<String, Long>(32) {}

    fun getMediaDurationMs(file: File): Long {
        val key = file.name
        mediaDurationCache.get(key)?.let { return it }
        val ms = kotlin.runCatching {
            val mmr = MediaMetadataRetriever()
            try {
                mmr.setDataSource(file.absolutePath)
                mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull() ?: 0L
            } finally {
                mmr.release()
            }
        }.getOrDefault(0L)
        mediaDurationCache.put(key, ms)
        return ms
    }

    /** 毫秒格式化为 mm:ss（超过一小时为 h:mm:ss） */
    fun formatDuration(ms: Long): String {
        val totalSeconds = (ms / 1000).coerceAtLeast(0L)
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            "%d:%02d:%02d".format(hours, minutes, seconds)
        } else {
            "%02d:%02d".format(minutes, seconds)
        }
    }

    /** 视频首帧缓存（按文件名+尺寸） */
    private val videoFrameCache = object : LruCache<String, Bitmap>(8) {}

    /** 取视频首帧，缩放到指定尺寸；失败返回 null */
    fun getVideoFrame(file: File, width: Int, height: Int): Bitmap? {
        val key = "${file.name}_${width}x$height"
        videoFrameCache.get(key)?.let { return it }
        val frame = kotlin.runCatching {
            val mmr = MediaMetadataRetriever()
            try {
                mmr.setDataSource(file.absolutePath)
                val raw = mmr.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    ?: return@runCatching null
                if (width <= 0 || height <= 0) {
                    raw
                } else {
                    val scaled = Bitmap.createScaledBitmap(raw, width, height, true)
                    if (scaled !== raw) raw.recycle()
                    scaled
                }
            } finally {
                mmr.release()
            }
        }.getOrNull() ?: return null
        videoFrameCache.put(key, frame)
        return frame
    }

    /** 视频原始宽高；非视频返回 null */
    fun getMediaSize(book: Book, src: String): Size? {
        if (!isVideoSrc(src)) return null
        val file = getImageFile(book, src)
        if (!file.exists()) return null
        return kotlin.runCatching {
            val mmr = MediaMetadataRetriever()
            try {
                mmr.setDataSource(file.absolutePath)
                val w = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
                val h = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
                if (w != null && h != null && w > 0 && h > 0) Size(w, h) else null
            } finally {
                mmr.release()
            }
        }.getOrNull()
    }

    /** 将配图媒体保存到系统相册：图片→Pictures，视频→Movies，音频→Music */
    fun saveToAlbum(context: Context, book: Book, src: String): Boolean {
        val file = getImageFile(book, src)
        if (!file.exists()) return false
        return when (srcType(src)) {
            "video" -> saveToMediaStore(
                context, file,
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                "video/*",
                Environment.DIRECTORY_MOVIES
            )
            "audio" -> saveToMediaStore(
                context, file,
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                "audio/*",
                Environment.DIRECTORY_MUSIC
            )
            else -> saveImageToGallery(context, file)
        }
    }

    private fun saveImageToGallery(context: Context, file: File): Boolean {
        return kotlin.runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, file.name)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
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
                val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
                    .let { File(it, "Legado") }
                if (!dir.exists() && !dir.mkdirs()) return false
                val target = File(dir, file.name)
                FileOutputStream(target).use { out ->
                    file.inputStream().use { it.copyTo(out) }
                }
                MediaScannerConnection.scanFile(context, arrayOf(target.absolutePath), null, null)
                true
            }
        }.getOrDefault(false)
    }

    private fun saveToMediaStore(
        context: Context,
        file: File,
        collection: Uri,
        mime: String,
        relativeDir: String
    ): Boolean {
        return kotlin.runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
                    put(MediaStore.MediaColumns.MIME_TYPE, mime)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "$relativeDir/Legado")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val uri = context.contentResolver.insert(collection, values) ?: return false
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    file.inputStream().use { it.copyTo(out) }
                } ?: return false
                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                context.contentResolver.update(uri, values, null, null)
                true
            } else {
                @Suppress("DEPRECATION")
                val dir = Environment.getExternalStoragePublicDirectory(relativeDir)
                    .let { File(it, "Legado") }
                if (!dir.exists() && !dir.mkdirs()) return false
                val target = File(dir, file.name)
                FileOutputStream(target).use { out ->
                    file.inputStream().use { it.copyTo(out) }
                }
                MediaScannerConnection.scanFile(context, arrayOf(target.absolutePath), arrayOf(mime), null)
                true
            }
        }.getOrDefault(false)
    }

    /** 生成段落指纹：head=true 取开头，否则取末尾；归一化空白 */
    fun fingerprint(text: String, head: Boolean): String {
        val normalized = text.trim().replace(Regex("\\s+"), "")
        return if (head) {
            normalized.take(FINGERPRINT_LENGTH)
        } else {
            normalized.takeLast(FINGERPRINT_LENGTH)
        }
    }

    /** 查找与某段落边界匹配的段间配图（anchorPos 优先，指纹兜底） */
    fun findForBoundary(
        illustrations: List<BookIllustration>,
        anchorPos: Int,
        frontText: String,
        backText: String
    ): List<BookIllustration> {
        return illustrations.filter { it.anchorType == BookIllustration.ANCHOR_BETWEEN_PARAGRAPHS }
            .filter {
                it.anchorPos == anchorPos || (
                    it.frontFingerprint.isNotBlank() &&
                        it.backFingerprint.isNotBlank() &&
                        it.frontFingerprint == fingerprint(frontText, false) &&
                        it.backFingerprint == fingerprint(backText, true)
                    )
            }
            .sortedBy { it.sortOrder }
    }

    // ---------- 导出 / 导入 ----------

    data class IllustrationJsonItem(
        val chapterIndex: Int = 0,
        val chapterName: String = "",
        val anchorType: String = BookIllustration.ANCHOR_BETWEEN_PARAGRAPHS,
        val anchorPos: Int = -1,
        val frontParagraphText: String = "",
        val backParagraphText: String = "",
        val frontFingerprint: String = "",
        val backFingerprint: String = "",
        val images: List<String> = emptyList(),
        val layoutType: String = BookIllustration.LAYOUT_SINGLE,
        val displayHeight: Int = 0,
        val pageBreak: Boolean = false,
        val sortOrder: Int = 0
    )

    data class IllustrationJson(
        val version: Int = EXPORT_JSON_VERSION,
        val bookFile: String = "",
        val illustrations: List<IllustrationJsonItem> = emptyList()
    )

    /** 生成导出 JSON（images 为相对路径 images/{uuid}.{ext}） */
    fun buildExportJson(book: Book, txtFileName: String): String? {
        val records = appDb.bookIllustrationDao.getByBook(book.bookUrl)
        if (records.isEmpty()) return null
        val items = records.map { record ->
            IllustrationJsonItem(
                chapterIndex = record.chapterIndex,
                chapterName = record.chapterName,
                anchorType = record.anchorType,
                anchorPos = record.anchorPos,
                frontParagraphText = record.frontParagraphText,
                backParagraphText = record.backParagraphText,
                frontFingerprint = record.frontFingerprint,
                backFingerprint = record.backFingerprint,
                images = record.imageSrcsFromJson().map { src ->
                    "$EXPORT_IMAGES_DIR/${src.substringAfter(SRC_PREFIX)}"
                },
                layoutType = record.layoutType,
                displayHeight = record.displayHeight,
                pageBreak = record.pageBreak,
                sortOrder = record.sortOrder
            )
        }
        return GSON.toJson(IllustrationJson(bookFile = txtFileName, illustrations = items))
    }

    /**
     * 从导出压缩包还原配图。
     * @param jsonText illustrations.json 内容
     * @param extractedFiles 压缩包解压出的文件（含 images/ 下图片）
     * @return 是否成功还原
     */
    fun restoreFromExport(
        book: Book,
        jsonText: String,
        extractedFiles: List<File>,
        context: Context = appCtx
    ): Boolean {
        val json = kotlin.runCatching {
            GSON.fromJson(jsonText, IllustrationJson::class.java)
        }.getOrNull() ?: return false
        val filesByName = extractedFiles.associateBy { it.name }
        val newRecords = arrayListOf<BookIllustration>()
        json.illustrations.forEachIndexed { index, item ->
            val srcs = arrayListOf<String>()
            item.images.forEach { imagePath ->
                val imageName = imagePath.substringAfterLast('/')
                val imageFile = filesByName[imageName]
                    ?: extractedFiles.firstOrNull { it.absolutePath.replace('\\', '/').endsWith(imagePath.replace('\\', '/')) }
                if (imageFile?.exists() == true) {
                    val src = "$SRC_PREFIX$imageName"
                    saveImage(book, src, imageFile.readBytes())
                    srcs.add(src)
                }
            }
            if (srcs.isEmpty()) return@forEachIndexed
            newRecords.add(
                BookIllustration(
                    bookUrl = book.bookUrl,
                    chapterIndex = item.chapterIndex,
                    chapterName = item.chapterName,
                    anchorType = item.anchorType,
                    anchorPos = item.anchorPos,
                    frontParagraphText = item.frontParagraphText,
                    backParagraphText = item.backParagraphText,
                    frontFingerprint = item.frontFingerprint,
                    backFingerprint = item.backFingerprint,
                    imageSrcs = imageSrcsToJson(srcs),
                    layoutType = item.layoutType,
                    displayHeight = item.displayHeight,
                    pageBreak = item.pageBreak,
                    sortOrder = item.sortOrder
                )
            )
        }
        if (newRecords.isEmpty()) return false
        appDb.bookIllustrationDao.deleteByBook(book.bookUrl)
        appDb.bookIllustrationDao.insert(*newRecords.toTypedArray())
        return true
    }

    // ---------- EPUB 侧车清单 ----------

    data class EpubIllustrationRecord(
        val chapterIndex: Int = 0,
        val chapterName: String = "",
        val anchorType: String = BookIllustration.ANCHOR_BETWEEN_PARAGRAPHS,
        val anchorPos: Int = -1,
        val frontParagraphText: String = "",
        val backParagraphText: String = "",
        val frontFingerprint: String = "",
        val backFingerprint: String = "",
        val srcs: List<String> = emptyList(),
        val layoutType: String = BookIllustration.LAYOUT_SINGLE,
        val displayHeight: Int = 0,
        val pageBreak: Boolean = false,
        val sortOrder: Int = 0
    )

    data class EpubIllustrationJson(
        val version: Int = EXPORT_JSON_VERSION,
        val records: List<EpubIllustrationRecord> = emptyList()
    )

    fun buildEpubSidecarJson(records: List<BookIllustration>): String {
        val items = records.map { record ->
            EpubIllustrationRecord(
                chapterIndex = record.chapterIndex,
                chapterName = record.chapterName,
                anchorType = record.anchorType,
                anchorPos = record.anchorPos,
                frontParagraphText = record.frontParagraphText,
                backParagraphText = record.backParagraphText,
                frontFingerprint = record.frontFingerprint,
                backFingerprint = record.backFingerprint,
                srcs = record.imageSrcsFromJson(),
                layoutType = record.layoutType,
                displayHeight = record.displayHeight,
                pageBreak = record.pageBreak,
                sortOrder = record.sortOrder
            )
        }
        return GSON.toJson(EpubIllustrationJson(records = items))
    }

    /** 由配图 src 键计算 EPUB 内图片资源路径（与导出端一致） */
    fun epubImageHref(src: String): String {
        return "Images/${MD5Utils.md5Encode16(src)}.${getSuffixOf(src)}"
    }

    fun epubImageHrefWithParent(src: String): String {
        return "../${epubImageHref(src)}"
    }

    // ---------- PDF 导出 / 再导入 ----------

    data class PdfExportItem(
        val chapterIndex: Int = 0,
        val chapterName: String = "",
        val anchorType: String = BookIllustration.ANCHOR_BETWEEN_PARAGRAPHS,
        val anchorPos: Int = -1,
        val frontParagraphText: String = "",
        val backParagraphText: String = "",
        val frontFingerprint: String = "",
        val backFingerprint: String = "",
        val srcs: List<String> = emptyList(),
        val layoutType: String = BookIllustration.LAYOUT_SINGLE,
        val displayHeight: Int = 0,
        val pageBreak: Boolean = false,
        val sortOrder: Int = 0,
        val pdfPage: Int = -1,
        val pdfRects: List<String> = emptyList()
    )

    data class PdfIllustrationJson(
        val version: Int = EXPORT_JSON_VERSION,
        val bookFile: String = "",
        val records: List<PdfExportItem> = emptyList()
    )

    fun buildPdfExportJson(
        book: Book,
        pdfFileName: String,
        items: List<PdfExportItem>
    ): String {
        return GSON.toJson(PdfIllustrationJson(bookFile = pdfFileName, records = items))
    }

    /**
     * 从自导出的 PDF 压缩包还原配图：按 PDF 页坐标裁切出配图原图，
     * 保存到配图目录并写入记录（pdfPage/pdfRect 用于阅读页热区）。
     */
    fun restoreFromPdfExport(
        book: Book,
        pdfBook: Book,
        jsonText: String,
        files: List<File> = emptyList(),
        context: Context = appCtx
    ): Boolean {
        val json = kotlin.runCatching {
            GSON.fromJson(jsonText, PdfIllustrationJson::class.java)
        }.getOrNull() ?: return false
        if (json.records.isEmpty()) return false
        val records = arrayListOf<BookIllustration>()
        json.records.forEachIndexed { index, item ->
            val srcs = arrayListOf<String>()
            val rects = arrayListOf<String>()
            item.srcs.forEachIndexed { i, src ->
                val rect = item.pdfRects.getOrNull(i)
                val bytes = if (isVideoSrc(src) || isAudioSrc(src)) {
                    // 视频/音频无法从 PDF 页裁出，直接取压缩包里的原文件
                    val name = src.substringAfter(SRC_PREFIX)
                    files.firstOrNull { it.name == name }?.readBytes()
                } else {
                    cropPdfRegion(pdfBook, item.pdfPage, rect)
                }
                if (bytes != null) {
                    saveImage(book, src, bytes)
                    srcs.add(src)
                    if (rect != null) rects.add(rect)
                }
            }
            if (srcs.isEmpty()) return@forEachIndexed
            records.add(
                BookIllustration(
                    bookUrl = book.bookUrl,
                    chapterIndex = item.chapterIndex,
                    chapterName = item.chapterName,
                    anchorType = item.anchorType,
                    anchorPos = item.anchorPos,
                    frontParagraphText = item.frontParagraphText,
                    backParagraphText = item.backParagraphText,
                    frontFingerprint = item.frontFingerprint,
                    backFingerprint = item.backFingerprint,
                    imageSrcs = imageSrcsToJson(srcs),
                    layoutType = item.layoutType,
                    displayHeight = item.displayHeight,
                    pageBreak = item.pageBreak,
                    sortOrder = item.sortOrder,
                    pdfPage = item.pdfPage,
                    pdfRect = imageSrcsToJson(rects)
                )
            )
        }
        if (records.isEmpty()) return false
        appDb.bookIllustrationDao.deleteByBook(book.bookUrl)
        appDb.bookIllustrationDao.insert(*records.toTypedArray())
        return true
    }

    private fun cropPdfRegion(
        pdfBook: Book,
        pageIndex: Int,
        rectStr: String?
    ): ByteArray? {
        if (rectStr == null || pageIndex < 0) return null
        val parts = rectStr.split(",").mapNotNull { it.trim().toFloatOrNull() }
        if (parts.size != 4) return null
        val (x, y, w, h) = parts
        return kotlin.runCatching {
            val pfd = BookHelp.getBookPFD(pdfBook) ?: return null
            PdfRenderer(pfd).use { renderer ->
                if (pageIndex >= renderer.pageCount) return null
                renderer.openPage(pageIndex).use { page ->
                    val scale = 2f
                    val pageW = (page.width * scale).toInt().coerceAtLeast(1)
                    val pageH = (page.height * scale).toInt().coerceAtLeast(1)
                    val bitmap = Bitmap.createBitmap(pageW, pageH, Bitmap.Config.ARGB_8888)
                    bitmap.eraseColor(Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    val left = (x * pageW).toInt().coerceIn(0, pageW - 1)
                    val top = (y * pageH).toInt().coerceIn(0, pageH - 1)
                    val right = ((x + w) * pageW).toInt().coerceIn(left + 1, pageW)
                    val bottom = ((y + h) * pageH).toInt().coerceIn(top + 1, pageH)
                    val crop = Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top)
                    ByteArrayOutputStream().use { out ->
                        crop.compress(Bitmap.CompressFormat.PNG, 90, out)
                        out.toByteArray()
                    }
                }
            }
        }.getOrNull()
    }

    private fun getSuffixOf(src: String): String {
        return src.substringAfterLast('.', "jpg").ifBlank { "jpg" }
    }
}

fun BookIllustration.imageSrcsFromJson(): List<String> {
    return kotlin.runCatching {
        GSON.fromJson(
            imageSrcs,
            Array<String>::class.java
        ).toList()
    }.getOrDefault(emptyList())
}

fun BookIllustration.pdfRectsFromJson(): List<String> {
    return kotlin.runCatching {
        GSON.fromJson(
            pdfRect,
            Array<String>::class.java
        ).toList()
    }.getOrDefault(emptyList())
}

fun imageSrcsToJson(srcs: List<String>): String {
    return GSON.toJson(srcs)
}
