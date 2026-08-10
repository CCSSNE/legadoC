package io.legado.app.ui.image

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContract

class ImageCropContract : ActivityResultContract<ImageCropContract.Params, String?>() {

    override fun createIntent(context: Context, input: Params): Intent {
        return Intent(context, ImageCropActivity::class.java).apply {
            putExtra(ImageCropActivity.EXTRA_URI, input.uri.toString())
            putExtra(ImageCropActivity.EXTRA_ASPECT_WIDTH, input.aspectWidth)
            putExtra(ImageCropActivity.EXTRA_ASPECT_HEIGHT, input.aspectHeight)
            putExtra(ImageCropActivity.EXTRA_DIR_NAME, input.dirName)
            putExtra(ImageCropActivity.EXTRA_PREFIX, input.prefix)
            putExtra(ImageCropActivity.EXTRA_TARGET_WIDTH, input.targetWidth)
            putExtra(ImageCropActivity.EXTRA_OUTPUT_PATH, input.outputPath)
        }
    }

    override fun parseResult(resultCode: Int, intent: Intent?): String? {
        if (resultCode != Activity.RESULT_OK) return null
        return intent?.getStringExtra(ImageCropActivity.EXTRA_RESULT_PATH)
    }

    data class Params(
        val uri: Uri,
        val aspectWidth: Int,
        val aspectHeight: Int,
        val dirName: String,
        val prefix: String,
        val targetWidth: Int = 1600,
        val outputPath: String? = null
    )
}
