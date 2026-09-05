package io.legado.app.ui.code

import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.webkit.WebViewAssetLoader
import io.legado.app.R
import io.legado.app.base.VMBaseActivity
import io.legado.app.constant.AppLog
import io.legado.app.databinding.ActivityCreationCardEditBinding
import io.legado.app.databinding.DialogEditTextBinding
import io.legado.app.help.ai.AiCreationCardImages
import io.legado.app.help.ai.AiCreationImageFile
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

    /** 插入宫格列数（单张=1）：弹窗里选，多选图片时按此排表格 */
    private var pendingGridCols = 1

    private val imagePicker = registerForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        if (uris.isEmpty()) return@registerForActivityResult
        insertCreationImages(uris, pendingGridCols)
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
            //Vditor 偶尔会在地址后带查询串，先剥掉再取文件名，否则按缺失处理
            val name = path.substringAfterLast('/').substringBefore('?').substringBefore('#')
            if (name.isBlank() || name.contains("..")) return@PathHandler null
            val file = File(AiCreationCardImages.dir, name)
            if (!file.isFile) {
                AppLog.put("创作卡片图片加载失败，文件不存在：$name")
                return@PathHandler null
            }
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

        /** 编辑器宫格图片总高度（像素）：弹窗里调，全局一个值 */
        @JavascriptInterface
        fun gridImageHeight(): Int = AiCreationCardImages.gridImageHeight

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

    /**
     * 插入图片弹窗：上选版式（单张/两宫格/四宫格），下填总高度；
     * 确认后打开多选，单张逐行直插，多张按版式排成原生表格。
     * 存库的都是标准图片引用，编号识别不受排版影响。
     */
    private fun showInsertImageDialog() {
        val context = this
        val density = resources.displayMetrics.density
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (16 * density).toInt()
            setPadding(padding, (8 * density).toInt(), padding, (4 * density).toInt())
        }
        val radioGroup = RadioGroup(context).apply {
            orientation = RadioGroup.HORIZONTAL
        }
        val singleButton = RadioButton(context).apply {
            text = getString(R.string.ai_creation_grid_single)
            id = View.generateViewId()
        }
        val twoButton = RadioButton(context).apply {
            text = getString(R.string.ai_creation_grid_two)
            id = View.generateViewId()
        }
        val fourButton = RadioButton(context).apply {
            text = getString(R.string.ai_creation_grid_four)
            id = View.generateViewId()
        }
        radioGroup.addView(singleButton)
        radioGroup.addView(twoButton)
        radioGroup.addView(fourButton)
        radioGroup.check(singleButton.id)
        layout.addView(radioGroup)
        val heightRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        heightRow.addView(TextView(context).apply {
            text = getString(R.string.ai_creation_grid_height)
            setPadding(0, 0, (8 * density).toInt(), 0)
        })
        val heightInput = EditText(context).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(AiCreationCardImages.gridImageHeight.toString())
            setSelection(text.length)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        heightRow.addView(heightInput)
        layout.addView(heightRow)
        alert(titleResource = R.string.ai_creation_insert_image_title) {
            customView { layout }
            okButton {
                pendingGridCols = when (radioGroup.checkedRadioButtonId) {
                    twoButton.id -> 2
                    fourButton.id -> 4
                    else -> 1
                }
                heightInput.text?.toString()?.toIntOrNull()?.let { height ->
                    AiCreationCardImages.gridImageHeight = height
                }
                imagePicker.launch("image/*")
            }
            cancelButton()
        }
    }

    private fun insertCreationImages(uris: List<Uri>, cols: Int) {
        if (uris.isEmpty()) return
        val cardId = viewModel.creationCardId
        lifecycleScope.launch {
            val refs = withContext(IO) {
                uris.mapNotNull { uri -> AiCreationCardImages.import(uri, "card_$cardId") }
            }
            if (refs.isEmpty()) {
                toastOnUi(R.string.creation_image_import_failed)
                return@launch
            }
            //单张（或单选）逐行直插；多张按版式排成原生表格，末行不满补空格
            val text = if (refs.size == 1 || cols <= 1) {
                refs.joinToString("\n") { "![](/$it)" }
            } else {
                buildGridTable(refs, cols)
            }
            val literal = JSONObject.quote(text)
            binding.webView.evaluateJavascript("window.editorApi.insertText($literal)", null)
            binding.webView.evaluateJavascript("window.editorApi.focusEditor()", null)
        }
    }

    /** 宫格表格：首块直接做表头，不留空行；引用保持标准格式，编号识别不受影响 */
    private fun buildGridTable(refs: List<String>, cols: Int): String {
        val cells = refs.map { "![](/$it)" }.toMutableList()
        repeat((cols - refs.size % cols) % cols) { cells.add(" ") }
        val rows = cells.chunked(cols)
        return buildString {
            append("| ").append(rows[0].joinToString(" | ")).append(" |")
            append("\n| ").append(List(cols) { "---" }.joinToString(" | ")).append(" |")
            rows.drop(1).forEach { row ->
                append("\n| ").append(row.joinToString(" | ")).append(" |")
            }
        }
    }

    override fun onCompatCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_creation_card_edit, menu)
        return super.onCompatCreateOptionsMenu(menu)
    }

    override fun onCompatOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_save -> save()
            R.id.menu_insert_image -> showInsertImageDialog()
            R.id.menu_save_md -> saveMarkdown()
            R.id.menu_rename_card -> renameCreationCard()
            R.id.menu_delete_card -> confirmDeleteCreationCard()
        }
        return super.onCompatOptionsItemSelected(item)
    }

    /** 保存 MD 连图片：正文与引用图片一起落到 Download/Legado，引用指向相对目录 */
    private fun saveMarkdown() {
        fetchEditorContent { text ->
            val card = viewModel.creationCard
            val baseName = card?.name?.trim().orEmpty().ifBlank { "card_${viewModel.creationCardId}" }
            val context = this
            lifecycleScope.launch {
                val ok = withContext(IO) {
                    AiCreationImageFile.saveMarkdownWithImages(context, baseName, text)
                }
                toastOnUi(
                    if (ok) R.string.ai_creation_md_saved
                    else R.string.ai_creation_md_save_failed
                )
            }
        }
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
