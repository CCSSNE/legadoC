package io.legado.app.utils

import android.app.Activity.RESULT_OK
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import splitties.init.appCtx

fun <T> ActivityResultLauncher<T?>.launch() {
    launch(null)
}

class SelectImageContract : ActivityResultContract<Int?, SelectImageContract.Result>() {

    private val delegate = ActivityResultContracts.PickVisualMedia()
    private var requestCode: Int? = null
    private var useFallback = false

    override fun createIntent(context: Context, input: Int?): Intent {
        requestCode = input
        val intent = Intent(Intent.ACTION_GET_CONTENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("image/*")
        if (intent.resolveActivity(appCtx.packageManager) == null) {
            useFallback = true
            val request = PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
            return delegate.createIntent(context, request)
        }
        return intent
    }

    override fun parseResult(resultCode: Int, intent: Intent?): Result {
        val uri = if (useFallback) {
            delegate.parseResult(resultCode, intent)
        } else if (resultCode == RESULT_OK) {
            intent?.data
        } else {
            null
        }
        return Result(requestCode, uri)
    }

    data class Result(
        val requestCode: Int?,
        val uri: Uri? = null
    )

}

class SelectImagesContract : ActivityResultContract<Int?, SelectImagesContract.Result>() {

    private var requestCode: Int? = null

    override fun createIntent(context: Context, input: Int?): Intent {
        requestCode = input
        // 统一文件选择器：图片/视频/音频一次多选（系统相册选择器不支持音频）
        // 只设 */* 不设 EXTRA_MIME_TYPES：部分 ROM 会把 MIME 过滤组合解析成"仅图片/视频"，
        // 导致音频选不到；放开 */* 后所有媒体文件都能访问
        return Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("*/*")
            .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
    }

    override fun parseResult(resultCode: Int, intent: Intent?): Result {
        val uris = if (resultCode == RESULT_OK && intent != null) {
            val clipData = intent.clipData
            if (clipData != null) {
                (0 until clipData.itemCount).map { clipData.getItemAt(it).uri }
            } else {
                intent.data?.let { listOf(it) }.orEmpty()
            }
        } else {
            emptyList()
        }
        return Result(requestCode, uris)
    }

    data class Result(
        val requestCode: Int?,
        val uris: List<Uri> = emptyList()
    )

}

class StartActivityContract(private val cls: Class<*>) :
    ActivityResultContract<(Intent.() -> Unit)?, ActivityResult>() {

    override fun createIntent(context: Context, input: (Intent.() -> Unit)?): Intent {
        val intent = Intent(context, cls)
        input?.let {
            intent.apply(input)
        }
        return intent
    }

    override fun parseResult(
        resultCode: Int, intent: Intent?
    ): ActivityResult {
        return ActivityResult(resultCode, intent)
    }

}
