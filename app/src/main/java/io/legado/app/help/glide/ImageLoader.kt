package io.legado.app.help.glide

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.net.Uri
import androidx.annotation.DrawableRes
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import com.bumptech.glide.Glide
import com.bumptech.glide.RequestBuilder
import io.legado.app.utils.isAbsUrl
import io.legado.app.utils.isContentScheme
import io.legado.app.utils.isDataUrl
import io.legado.app.utils.lifecycle
import java.io.File
import androidx.core.net.toUri
import io.legado.app.utils.externalFiles
import io.legado.app.utils.getFile
import splitties.init.appCtx

//https://bumptech.github.io/glide/doc/generatedapi.html
//Instead of GlideApp, use com.bumptech.Glide
@Suppress("unused")
object ImageLoader {

    private fun normalizeLocalPath(path: String?): String? {
        if (path.isNullOrEmpty() ||
            path.isDataUrl() ||
            path.isAbsUrl() ||
            path.isContentScheme() ||
            !path.contains(File.separator)
        ) {
            return path
        }
        if (File(path).exists()) {
            return path
        }
        val fileName = File(path).name.takeIf { it.isNotBlank() } ?: return null
        val localDir = when {
            path.contains("${File.separator}covers${File.separator}") -> "covers"
            path.contains("${File.separator}bg${File.separator}") -> "bg"
            path.contains("${File.separator}font${File.separator}") -> "font"
            else -> return null
        }
        return appCtx.externalFiles.getFile(localDir, fileName)
            .takeIf { it.exists() }
            ?.absolutePath
    }

    /**
     * 自动判断path类型
     */
    fun load(context: Context, path: String?): RequestBuilder<Drawable> {
        val normalizedPath = normalizeLocalPath(path)
        return when {
            normalizedPath.isNullOrEmpty() -> Glide.with(context).load(normalizedPath)
            normalizedPath.isDataUrl() -> Glide.with(context).load(normalizedPath)
            normalizedPath.isAbsUrl() -> Glide.with(context).load(normalizedPath)
            normalizedPath.isContentScheme() -> Glide.with(context).load(normalizedPath.toUri())
            else -> kotlin.runCatching {
                Glide.with(context).load(File(normalizedPath))
            }.getOrElse {
                Glide.with(context).load(normalizedPath)
            }
        }
    }

    fun load(fragment: Fragment, lifecycle: Lifecycle, path: String?): RequestBuilder<Drawable> {
        val normalizedPath = normalizeLocalPath(path)
        val requestManager = Glide.with(fragment).lifecycle(lifecycle)
        return when {
            normalizedPath.isNullOrEmpty() -> requestManager.load(normalizedPath)
            normalizedPath.isDataUrl() -> requestManager.load(normalizedPath)
            normalizedPath.isAbsUrl() -> requestManager.load(normalizedPath)
            normalizedPath.isContentScheme() -> requestManager.load(normalizedPath.toUri())

            else -> kotlin.runCatching {
                requestManager.load(File(normalizedPath))
            }.getOrElse {
                requestManager.load(normalizedPath)
            }
        }
    }

    fun loadBitmap(context: Context, path: String?): RequestBuilder<Bitmap> {
        val normalizedPath = normalizeLocalPath(path)
        val requestManager = Glide.with(context).`as`(Bitmap::class.java)
        return when {
            normalizedPath.isNullOrEmpty() -> requestManager.load(normalizedPath)
            normalizedPath.isDataUrl() -> requestManager.load(normalizedPath)
            normalizedPath.isAbsUrl() -> requestManager.load(normalizedPath)
            normalizedPath.isContentScheme() -> requestManager.load(normalizedPath.toUri())
            else -> kotlin.runCatching {
                requestManager.load(File(normalizedPath))
            }.getOrElse {
                requestManager.load(normalizedPath)
            }
        }
    }

    fun loadFile(context: Context, path: String?): RequestBuilder<File> {
        val normalizedPath = normalizeLocalPath(path)
        return when {
            normalizedPath.isNullOrEmpty() -> Glide.with(context).asFile().load(normalizedPath)
            normalizedPath.isAbsUrl() -> Glide.with(context).asFile().load(normalizedPath)
            normalizedPath.isContentScheme() -> Glide.with(context).asFile().load(normalizedPath.toUri())
            else -> kotlin.runCatching {
                Glide.with(context).asFile().load(File(normalizedPath))
            }.getOrElse {
                Glide.with(context).asFile().load(normalizedPath)
            }
        }
    }

    fun load(context: Context, @DrawableRes resId: Int?): RequestBuilder<Drawable> {
        return Glide.with(context).load(resId)
    }

    fun load(context: Context, file: File?): RequestBuilder<Drawable> {
        return Glide.with(context).load(file)
    }

    fun load(context: Context, uri: Uri?): RequestBuilder<Drawable> {
        return Glide.with(context).load(uri)
    }

    fun load(context: Context, drawable: Drawable?): RequestBuilder<Drawable> {
        return Glide.with(context).load(drawable)
    }

    fun load(context: Context, bitmap: Bitmap?): RequestBuilder<Drawable> {
        return Glide.with(context).load(bitmap)
    }

    fun load(context: Context, bytes: ByteArray?): RequestBuilder<Drawable> {
        return Glide.with(context).load(bytes)
    }

}
