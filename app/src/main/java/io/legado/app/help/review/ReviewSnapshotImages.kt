package io.legado.app.help.review

import android.net.Uri
import android.webkit.WebView
import android.webkit.MimeTypeMap
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import io.legado.app.constant.AppLog
import io.legado.app.data.entities.Book
import io.legado.app.ui.widget.dialog.PhotoDialog
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 评论快照两个浏览器宿主共用的图片交互与本地资源读取。 */
object ReviewSnapshotImages {
    fun install(view: WebView) {
        view.evaluateJavascript("""
            (function(){
                if(window.__legadoReviewImages)return;
                window.__legadoReviewImages=true;
                document.addEventListener('click',function(e){
                    var img=e.target.closest && e.target.closest('img');
                    if(!img)return;
                    var src=img.currentSrc || img.src;
                    if(!src || !/^(review-resource:|data:image\/)/.test(src))return;
                    e.preventDefault();e.stopImmediatePropagation();
                    location.href='legado-review-image://preview?src='+encodeURIComponent(src);
                },true);
            })();
        """.trimIndent(), null)
    }

    fun resource(book: Book?, url: String): ReviewSnapshotResourceHandle? {
        val key = ReviewSnapshotResourceStore.keyFromReference(url) ?: return null
        return checkNotNull(ReviewSnapshotResourceStore.open(checkNotNull(book) {
            "评论图片缺少书籍上下文"
        }, key)) { "评论图片资源不存在：$url" }
    }

    fun readLocal(book: Book?, url: String): Pair<String, ByteArray>? {
        val local = resource(book, url) ?: return null
        return local.inputStream.use { stream ->
            val extension = requireNotNull(MimeTypeMap.getSingleton().getExtensionFromMimeType(local.mimeType)) {
                "评论图片类型无法识别：${local.mimeType}"
            }
            extension to stream.readBytes()
        }
    }

    fun open(activity: FragmentActivity, book: Book?, uri: Uri): Boolean {
        if (uri.scheme != "legado-review-image") return false
        activity.lifecycleScope.launch {
            try {
                val src = withContext(Dispatchers.IO) {
                    val reference = requireNotNull(uri.getQueryParameter("src")) { "评论图片地址为空" }
                    val local = resource(book, reference)
                    if (local != null) {
                        local.inputStream.close()
                        local.file.absolutePath
                    } else {
                        require(reference.startsWith("data:image/")) { "离线图片不是本地资源" }
                        reference
                    }
                }
                PhotoDialog(src).show(activity.supportFragmentManager, "reviewImage")
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                AppLog.put("评论图片打开失败", error)
                activity.toastOnUi("评论图片打开失败：${error.localizedMessage}")
            }
        }
        return true
    }
}
