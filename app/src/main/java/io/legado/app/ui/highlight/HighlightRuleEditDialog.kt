package io.legado.app.ui.highlight

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.constant.AppLog
import io.legado.app.constant.EventBus
import io.legado.app.data.appDb
import io.legado.app.data.entities.HighlightRule
import io.legado.app.data.entities.BookmarkStyle
import io.legado.app.databinding.DialogHighlightRuleEditBinding
import io.legado.app.utils.postEvent
import io.legado.app.utils.setLayout
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import io.legado.app.utils.visible
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

class HighlightRuleEditDialog() : BaseDialogFragment(R.layout.dialog_highlight_rule_edit, true) {

    constructor(rule: HighlightRule, editPos: Int = -1) : this() {
        arguments = Bundle().apply {
            putInt("editPos", editPos)
            putParcelable("rule", rule)
        }
    }

    private val binding by viewBinding(DialogHighlightRuleEditBinding::bind)

    override fun onStart() {
        super.onStart()
        setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        binding.toolBar.background = null
        // 新建规则时无 arguments，直接用默认新规则；编辑时 arguments 携带待编辑规则
        @Suppress("DEPRECATION")
        val rule = arguments?.getParcelable<HighlightRule>("rule") ?: HighlightRule()
        val editPos = arguments?.getInt("editPos", -1) ?: -1
        binding.editName.setText(rule.name)
        binding.editPattern.setText(rule.pattern)
        binding.editScope.setText(rule.scope)
        binding.editExcludeScope.setText(rule.excludeScope)
        binding.effectStylePicker.setFragmentManager(childFragmentManager)
        binding.effectStylePicker.setStyles(
            rule.style,
            BookmarkStyle.parseStyleColors(rule.styleColors),
            0
        )
        binding.tvFooterLeft.visible(editPos >= 0)
        binding.run {
            tvCancel.setOnClickListener {
                dismiss()
            }
            tvOk.setOnClickListener {
                rule.name = editName.text?.toString() ?: ""
                rule.pattern = editPattern.text?.toString() ?: ""
                rule.scope = editScope.text?.toString()
                rule.excludeScope = editExcludeScope.text?.toString()
                rule.style = effectStylePicker.getCheckedStyles()
                rule.styleColors = effectStylePicker.getStyleColorsJson()
                if (rule.pattern.isBlank()) {
                    toastOnUi(R.string.highlight_rule_invalid)
                    return@setOnClickListener
                }
                try {
                    Pattern.compile(rule.pattern)
                } catch (ex: PatternSyntaxException) {
                    AppLog.put("高亮规则 ${rule.name} 正则语法错误或不支持：${ex.localizedMessage}", ex)
                    toastOnUi(R.string.highlight_rule_invalid)
                    return@setOnClickListener
                }
                lifecycleScope.launch {
                    withContext(IO) {
                        if (rule.order == Int.MIN_VALUE) {
                            rule.order = appDb.highlightRuleDao.maxOrder + 1
                        }
                        appDb.highlightRuleDao.insert(rule)
                    }
                    postEvent(EventBus.HIGHLIGHT_RULE_CHANGED, true)
                    dismiss()
                }
            }
            tvFooterLeft.setOnClickListener {
                lifecycleScope.launch {
                    withContext(IO) {
                        appDb.highlightRuleDao.delete(rule)
                    }
                    postEvent(EventBus.HIGHLIGHT_RULE_CHANGED, true)
                    dismiss()
                }
            }
        }
    }

}
