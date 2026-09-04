package io.legado.app.help.ai

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import splitties.init.appCtx
import java.io.File
import java.io.FileOutputStream

object AiCreationCardImages {

    private const val DIR_NAME = "creation_images"
    private val REF_REGEX = Regex("creation_images/[A-Za-z0-9_.\\-]+")
    private val MARKDOWN_REF_REGEX = Regex("!\\[[^\\]]*]\\((creation_images/[^)\\s]+)\\)")

    val dir: File
        get() = File(appCtx.filesDir, DIR_NAME).apply { mkdirs() }

    fun import(uri: Uri, cardId: Long): String? {
        return runCatching {
            val resolver = appCtx.contentResolver
            val ext = when (resolver.getType(uri)) {
                "image/png" -> "png"
                "image/webp" -> "webp"
                "image/gif" -> "gif"
                "image/bmp" -> "bmp"
                else -> "jpg"
            }
            val name = "card_${cardId}_${System.currentTimeMillis()}.$ext"
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

    /** 引用转 base64 data URL：文件缺失或格式不明直接报错，不静默降级 */
    fun dataUrlOf(ref: String): String {
        val file = fileOf(ref)
            ?: throw IllegalStateException("图片文件不存在：${ref.substringAfterLast('/')}")
        val bytes = file.readBytes()
        return "data:${mimeOf(ref)};base64,${Base64.encodeToString(bytes, Base64.NO_WRAP)}"
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
