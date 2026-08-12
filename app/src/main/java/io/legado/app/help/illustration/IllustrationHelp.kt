package io.legado.app.help.illustration

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
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

    /** 指纹窗口长度 */
    private const val FINGERPRINT_LENGTH = 24

    fun newSrc(ext: String): String {
        val safeExt = ext.substringAfter('.', "jpg").ifBlank { "jpg" }
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

    /** 将配图保存到系统相册 */
    fun saveToAlbum(context: Context, book: Book, src: String): Boolean {
        val file = getImageFile(book, src)
        if (!file.exists()) return false
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
                val bytes = cropPdfRegion(pdfBook, item.pdfPage, rect)
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
