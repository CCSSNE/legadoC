package io.legado.app.ui.widget.dialog

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.databinding.DialogTextViewBinding
import io.legado.app.help.CacheManager
import io.legado.app.help.IntentData
import io.legado.app.lib.theme.primaryColor
import io.legado.app.ui.code.CodeEditActivity
import io.legado.app.utils.applyUiMenuStyle
import io.legado.app.utils.gone
import io.legado.app.utils.setHtml
import io.legado.app.utils.setLayout
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.startActivity
import io.legado.app.utils.viewbindingdelegate.viewBinding
import io.legado.app.utils.visible
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch


class TextDialog() : BaseDialogFragment(R.layout.dialog_text_view) {

    enum class Mode {
        MD, HTML, TEXT
    }

    constructor(
        title: String,
        content: String?,
        mode: Mode = Mode.TEXT,
        time: Long = 0,
        autoClose: Boolean = false
    ) : this() {
        arguments = Bundle().apply {
            putString("title", title)
            putString("content", IntentData.put(content))
            putString("mode", mode.name)
            putLong("time", time)
        }
        isCancelable = false
        this.autoClose = autoClose
    }

    /**
     * MD 可编辑模式：全屏编辑用可写编辑器打开（Markdown 高亮），
     * 编辑返回后直接用最新内容重新渲染并回调（如配图备注保存回数据库）。
     */
    constructor(
        title: String,
        content: String?,
        onContentEdited: (String) -> Unit
    ) : this(title, content, Mode.MD) {
        this.onContentEdited = onContentEdited
    }

    private val binding by viewBinding(DialogTextViewBinding::bind)
    private var time = 0L
    private var autoClose: Boolean = false
    private var onContentEdited: ((String) -> Unit)? = null
    private var mdContent = ""

    private val contentEditLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val text = result.data?.getStringExtra("text")
            if (result.resultCode == Activity.RESULT_OK && text != null) {
                mdContent = text
                renderMd(text)
                onContentEdited?.invoke(text)
            }
        }

    override fun onStart() {
        super.onStart()
        setLayout(ViewGroup.LayoutParams.MATCH_PARENT, 0.9f)
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        binding.toolBar.setBackgroundColor(primaryColor)
        binding.toolBar.inflateMenu(R.menu.dialog_text)
        binding.toolBar.menu.applyUiMenuStyle(requireContext())
        arguments?.let {
            val title = it.getString("title")
            binding.toolBar.title = title
            val content = IntentData.get(it.getString("content")) ?: ""
            val mode = it.getString("mode")
            when (mode) {
                Mode.MD.name -> {
                    mdContent = content
                    renderMd(content)
                }

                Mode.HTML.name -> binding.textView.setHtml(content)
                else -> {
                    if (content.length >= 32 * 1024) {
                        val truncatedContent =
                            content.take(32 * 1024) + "\n\n数据太大，无法全部显示…"
                        binding.textView.text = truncatedContent
                    } else {
                        binding.textView.text = content
                    }
                }
            }
            binding.toolBar.setOnMenuItemClickListener { menu ->
                when (menu.itemId) {
                    R.id.menu_close -> dismissAllowingStateLoss()
                    R.id.menu_fullscreen_edit -> {
                        if (onContentEdited != null) {
                            // 可编辑模式：用 "text" extra 进可写编辑器，返回 RESULT_OK + text 才算有修改
                            contentEditLauncher.launch(
                                Intent(requireActivity(), CodeEditActivity::class.java).apply {
                                    putExtra("text", mdContent)
                                    putExtra("title", title)
                                    putExtra("languageName", "text.html.markdown")
                                }
                            )
                        } else {
                            val cacheKey = "code_text_${System.currentTimeMillis()}"
                            CacheManager.putMemory(cacheKey, content)
                            startActivity<CodeEditActivity> {
                                putExtra("cacheKey", cacheKey)
                                putExtra("title", title)
                                putExtra(
                                    "languageName",
                                    if (mode == Mode.MD.name) "text.html.markdown" else "text.html.basic"
                                )
                            }
                        }
                    }
                }
                true
            }
            time = it.getLong("time", 0L)
        }
        if (time > 0) {
            binding.badgeView.setBadgeCount((time / 1000).toInt())
            lifecycleScope.launch {
                while (time > 0) {
                    delay(1000)
                    time -= 1000
                    binding.badgeView.setBadgeCount((time / 1000).toInt())
                    if (time <= 0) {
                        view.post {
                            dialog?.setCancelable(true)
                            if (autoClose) dialog?.cancel()
                        }
                    }
                }
            }
        } else {
            view.post {
                dialog?.setCancelable(true)
            }
        }
    }

    private fun renderMd(content: String) {
        binding.textView.gone()
        binding.mdPreview.visible()
        binding.mdPreview.onImageLongPress = { source ->
            showDialogFragment(PhotoDialog(source))
        }
        binding.mdPreview.setMarkdown(content)
    }

    override fun onDestroyView() {
        binding.mdPreview.destroyPreview()
        super.onDestroyView()
    }

}
