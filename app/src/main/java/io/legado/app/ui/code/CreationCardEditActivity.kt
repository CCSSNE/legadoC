package io.legado.app.ui.code

import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.webkit.WebViewAssetLoader
import io.legado.app.R
import io.legado.app.base.VMBaseActivity
import io.legado.app.databinding.ActivityCreationCardEditBinding
import io.legado.app.databinding.DialogEditTextBinding
import io.legado.app.help.ai.AiCreationCardImages
import io.legado.app.lib.dialogs.alert
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * 创作卡片编辑：WebView 内嵌 Vditor markdown 编辑器（IR 即时渲染模式），
 * 图片引用在编辑器内直接渲染原图，不再使用编号条与点击预览。
 * 卡片存库格式不变（creation_images/ 相对引用），改写只发生在编辑器载入/导出两端。
 */
class CreationCardEditActivity :
    VMBaseActivity<ActivityCreationCardEditBinding, CreationCardEditViewModel>() {

    companion object {
        private const val PAGE_URL =
            "https://appassets.androidplatform.net/assets/mdeditor/index.html"
    }

    override val binding by viewBinding(ActivityCreationCardEditBinding::inflate)
    override val viewModel by viewModels<CreationCardEditViewModel>()

    private var editorReady = false
    private var finishing = false

    private val imagePicker = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        uri ?: return@registerForActivityResult
        insertCreationImage(uri)
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        viewModel.initData(intent) {
            viewModel.title?.let {
                binding.titleBar.title = it
            }
            setupWebView()
        }
    }

    override fun onDestroy() {
        binding.webView.destroy()
        super.onDestroy()
    }

    override fun onPause() {
        super.onPause()
        //内容快照：界面重建（深浅色切换等）后经 ViewModel 恢复未保存编辑
        if (editorReady) {
            fetchEditorContent { text ->
                viewModel.cacheText(text)
            }
        }
    }

    private fun setupWebView() {
        val isDark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        val loader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .addPathHandler("/creation_images/", creationImagesHandler)
            .build()
        binding.webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
        }
        binding.webView.addJavascriptInterface(Bridge(isDark), "AndroidBridge")
        binding.webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? {
                return loader.shouldInterceptRequest(request.url)
            }
        }
        binding.webView.loadUrl(PAGE_URL)
    }

    /** creation_images 引用原图：由应用私有目录按文件名直接提供 */
    private val creationImagesHandler =
        WebViewAssetLoader.PathHandler { path ->
            val name = path.substringAfterLast('/')
            if (name.isBlank() || name.contains("..")) return@PathHandler null
            val file = File(AiCreationCardImages.dir, name)
            if (!file.isFile) return@PathHandler null
            val mime = when (file.extension.lowercase()) {
                "png" -> "image/png"
                "webp" -> "image/webp"
                "gif" -> "image/gif"
                "bmp" -> "image/bmp"
                "jpg", "jpeg" -> "image/jpeg"
                else -> return@PathHandler null
            }
            WebResourceResponse(mime, null, file.inputStream())
        }

    private inner class Bridge(private val isDark: Boolean) {

        @JavascriptInterface
        fun loadContent(): String = viewModel.initialText

        @JavascriptInterface
        fun isDark(): Boolean = isDark

        @JavascriptInterface
        fun onReady() {
            runOnUiThread {
                editorReady = true
            }
        }
    }

    /** 从编辑器拉取当前 markdown（导出端已把图片引用还原为存库格式） */
    private fun fetchEditorContent(onDone: (String) -> Unit) {
        if (!editorReady) {
            onDone(viewModel.cachedContent())
            return
        }
        binding.webView.evaluateJavascript("window.editorApi.exportContent()") { result ->
            val text = if (result == null || result == "null") {
                null
            } else {
                runCatching {
                    JSONObject("{\"v\":$result}").optString("v")
                }.getOrNull()
            }
            onDone(text ?: viewModel.cachedContent())
        }
    }

    private fun insertCreationImage(uri: Uri) {
        val cardId = viewModel.creationCardId
        lifecycleScope.launch {
            val ref = withContext(IO) { AiCreationCardImages.import(uri, "card_$cardId") }
            if (ref == null) {
                toastOnUi(R.string.creation_image_import_failed)
                return@launch
            }
            val literal = JSONObject.quote("![]($ref)")
            binding.webView.evaluateJavascript("window.editorApi.insertText($literal)", null)
            binding.webView.evaluateJavascript("window.editorApi.focusEditor()", null)
        }
    }

    override fun onCompatCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_creation_card_edit, menu)
        return super.onCompatCreateOptionsMenu(menu)
    }

    override fun onCompatOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_save -> save()
            R.id.menu_insert_image -> imagePicker.launch("image/*")
            R.id.menu_rename_card -> renameCreationCard()
            R.id.menu_delete_card -> confirmDeleteCreationCard()
        }
        return super.onCompatOptionsItemSelected(item)
    }

    private fun renameCreationCard() {
        val card = viewModel.creationCard ?: return
        val editBinding = DialogEditTextBinding.inflate(layoutInflater).apply {
            editView.setText(card.name)
            editView.setSelection(card.name.length)
        }
        alert(titleResource = R.string.rename) {
            customView { editBinding.root }
            okButton {
                viewModel.renameCreationCard(editBinding.editView.text?.toString().orEmpty()) { updated ->
                    binding.titleBar.title = updated.name
                }
            }
            cancelButton()
        }
    }

    private fun confirmDeleteCreationCard() {
        alert(titleResource = R.string.delete) {
            setMessage(R.string.creation_card_delete_confirm)
            okButton {
                val cardId = viewModel.creationCardId
                viewModel.deleteCreationCard {
                    finishCreationCard(deleted = true, cardId = cardId)
                }
            }
            cancelButton()
        }
    }

    private fun save() {
        if (finishing) return
        finishing = true
        fetchEditorContent { text ->
            val cardId = viewModel.creationCardId
            viewModel.saveCreationCard(
                text,
                onBlankDeleted = { finishCreationCard(deleted = true, cardId = cardId) },
                onSaved = { finishCreationCard(deleted = false, cardId = cardId) },
                onFailed = { finishing = false }
            )
        }
    }

    private fun finishCreationCard(deleted: Boolean, cardId: Long) {
        val result = Intent().apply {
            putExtra("creationCardDeleted", deleted)
            putExtra("creationCardId", cardId)
        }
        setResult(RESULT_OK, result)
        super.finish()
    }

    override fun finish() {
        save()
    }
}
