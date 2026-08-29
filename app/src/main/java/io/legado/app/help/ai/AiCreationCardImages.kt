package io.legado.app.help.ai

import android.net.Uri
import splitties.init.appCtx
import java.io.File

object AiCreationCardImages {

    private const val DIR_NAME = "creation_images"
    private val REF_REGEX = Regex("creation_images/[A-Za-z0-9_.\\-]+")

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
}
