package io.legado.app.ui.main.homepage

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.android.flexbox.FlexboxLayout
import io.legado.app.R
import io.legado.app.base.BaseBottomSheetDialogFragment
import io.legado.app.data.entities.rule.ExploreKind
import io.legado.app.databinding.DialogHomepageKindSelectBinding
import io.legado.app.lib.theme.accentColor
import io.legado.app.utils.setLayout
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.launch

/**
 * 主页源模块分类选择底部弹窗：点选切换勾选 1~N 个分类后点确定，
 * 结果回传 [HomepageModuleManageSheet.onKindsSelected] 打开添加模块对话框。
 */
class HomepageKindSelectSheet :
    BaseBottomSheetDialogFragment(R.layout.dialog_homepage_kind_select) {

    private val binding by viewBinding(DialogHomepageKindSelectBinding::bind)

    private val viewModel: HomepageViewModel
        get() = (parentFragment as HomepageModuleManageSheet).viewModel

    private var kinds = listOf<ExploreKind>()
    private val selectedUrls = mutableSetOf<String>()

    override fun onStart() {
        super.onStart()
        setLayout(ViewGroup.LayoutParams.MATCH_PARENT, 0.6f)
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        val args = arguments ?: Bundle()
        val sourceUrl = args.getString("sourceUrl") ?: ""
        val sourceType = args.getString("sourceType") ?: "book"
        selectedUrls.clear()
        selectedUrls.addAll(args.getStringArrayList("selected").orEmpty())

        binding.tvKindOk.isVisible = true
        binding.tvKindOk.setOnClickListener { confirmSelection() }

        loadKinds(sourceUrl, sourceType)
    }

    private fun loadKinds(sourceUrl: String, sourceType: String) {
        binding.pbKind.isVisible = true
        binding.svKinds.isVisible = false
        viewLifecycleOwner.lifecycleScope.launch {
            kinds = if (sourceType == "rss") {
                viewModel.getRssKinds(sourceUrl).map { (title, url) ->
                    ExploreKind(title = title, url = url)
                }
            } else {
                viewModel.getExploreKinds(sourceUrl)
            }
            binding.pbKind.isVisible = false
            binding.svKinds.isVisible = true
            upKinds()
        }
    }

    private fun upKinds() {
        binding.flKinds.removeAllViews()
        kinds.forEach { kind ->
            val url = kind.url ?: kind.title
            val selected = url in selectedUrls
            val chip = HomepageModuleManageSheet.createKindChip(requireContext(), kind.title) {
                if (selected) {
                    selectedUrls.remove(url)
                } else {
                    selectedUrls.add(url)
                }
                upKinds()
            }
            if (selected) {
                chip.setBackgroundResource(R.drawable.bg_homepage_field)
                chip.setTextColor(accentColor)
            }
            binding.flKinds.addView(chip)
        }
    }

    private fun confirmSelection() {
        val selected = kinds.filter { (it.url ?: it.title) in selectedUrls }
        if (selected.isEmpty()) return
        (parentFragment as? HomepageModuleManageSheet)?.onKindsSelected(selected)
        dismiss()
    }
}
