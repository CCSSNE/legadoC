package io.legado.app.utils

import android.content.Context
import android.net.Uri
import io.legado.app.ui.image.ImageCropContract
import java.io.File
import kotlin.math.abs

object ImageCropHelper {

    data class Request(
        val requestCode: Int,
        val outputPath: String,
        val params: ImageCropContract.Params
    )

    fun buildRequest(
        context: Context,
        sourceUri: Uri,
        requestCode: Int,
        aspectWidth: Int,
        aspectHeight: Int,
        dirName: String,
        prefix: String,
        targetWidth: Int
    ): Request {
        val aspect = normalizeAspect(aspectWidth, aspectHeight)
        val outputPath = createOutputPath(context, dirName, prefix)
        return Request(
            requestCode = requestCode,
            outputPath = outputPath,
            params = ImageCropContract.Params(
                uri = sourceUri,
                aspectWidth = aspect.first,
                aspectHeight = aspect.second,
                dirName = dirName,
                prefix = prefix,
                targetWidth = targetWidth,
                outputPath = outputPath
            )
        )
    }

    fun screenAspect(context: Context): Pair<Int, Int> {
        val metrics = context.resources.displayMetrics
        return normalizeAspect(
            metrics.widthPixels.coerceAtLeast(1),
            metrics.heightPixels.coerceAtLeast(1)
        )
    }

    private fun createOutputPath(context: Context, dirName: String, prefix: String): String {
        val dir = context.externalFiles.getFile(dirName).apply { mkdirs() }
        return File(dir, "${prefix}_${System.currentTimeMillis()}.jpg").absolutePath
    }

    private fun normalizeAspect(width: Int, height: Int): Pair<Int, Int> {
        val safeWidth = abs(width).coerceAtLeast(1)
        val safeHeight = abs(height).coerceAtLeast(1)
        val divisor = gcd(safeWidth, safeHeight)
        return safeWidth / divisor to safeHeight / divisor
    }

    private tailrec fun gcd(a: Int, b: Int): Int {
        return if (b == 0) a else gcd(b, a % b)
    }
}
