package io.legado.app.utils

import android.app.Activity.RESULT_OK
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContract

fun <T> ActivityResultLauncher<T?>.launch() {
    launch(null)
}

class SelectImageContract : ActivityResultContract<Int?, SelectImageContract.Result>() {

    private var requestCode: Int? = null

    override fun createIntent(context: Context, input: Int?): Intent {
        requestCode = input
        // 用 OPEN_DOCUMENT 替代 GET_CONTENT：返回的 uri 支持 takePersistableUriPermission，
        // 否则临时权限在发起方 Activity 结束后失效，后台服务读取（如 EPUB 导出嵌入背景图）会失败
        return Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("image/*")
            .addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
            )
    }

    override fun parseResult(resultCode: Int, intent: Intent?): Result {
        val uri = if (resultCode == RESULT_OK) {
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
