package io.legado.app.ui.main.homepage

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.TextView
import androidx.core.content.ContextCompat
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.databinding.DialogHomepageModuleEditBinding
import io.legado.app.domain.model.HomepageModuleType
import io.legado.app.domain.model.ModuleDef
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.primaryTextColor
import io.legado.app.utils.setLayout
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding

/**
 * 主页模块新建/编辑弹窗：选择模块类型，填写标题、URL 与参数 JSON。
 */
class HomepageModuleEditDialog : BaseDialogFragment(R.layout.dialog_homepage_module_edit) {

    private val binding by viewBinding(DialogHomepageModuleEditBinding::bind)

    private val viewModel: HomepageViewModel
        get() = (parentFragment as HomepageModuleManageSheet).viewModel

    private var selectedType: String = HomepageModuleType.Grid.key

    private val typeEntries: List<HomepageModuleType>
        get() = HomepageModuleType.entries.filter { it != HomepageModuleType.Unknown }

    override fun onStart() {
        super.onStart()
        dialog?.window?.let { window: Window ->
            window.attributes.gravity = Gravity.CENTER
            window.attributes.width = ViewGroup.LayoutParams.MATCH_PARENT
        }
        setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        val args = arguments ?: Bundle()
        val isCreate = args.getBoolean("isCreate")
        selectedType = args.getString("type") ?: HomepageModuleType.Grid.key

        binding.run {
            tvTitle.text = if (isCreate) {
                getString(R.string.homepage_add_custom_module)
            } else {
                getString(R.string.homepage_edit_module)
            }
            etTitle.setText(args.getString("title") ?: "")
            etUrl.setText(args.getString("url") ?: "")
            etArgs.setText(args.getString("args") ?: "")

            upTypeChips()

            tvCancel.setOnClickListener { dismiss() }
            tvOk.setOnClickListener { submit(args, isCreate) }
        }
    }

    private fun upTypeChips() {
        binding.flTypes.removeAllViews()
        typeEntries.forEach { entry ->
            val selected = entry.key == selectedType
            val chip = HomepageModuleManageSheet.createKindChip(requireContext(), "") {}
            chip.text = getString(entry.titleRes)
            chip.setTextColor(
                if (selected) accentColor
                else ContextCompat.getColor(requireContext(), R.color.primaryText)
            )
            chip.setOnClickListener {
                selectedType = entry.key
                upTypeChips()
            }
            binding.flTypes.addView(chip)
        }
    }

    private fun submit(args: Bundle, isCreate: Boolean) {
        binding.run {
            val title = etTitle.text.toString().trim()
            val url = etUrl.text.toString().trim()
            val moduleArgs = etArgs.text.toString().trim().ifBlank { null }
            val sourceUrl = args.getString("sourceUrl") ?: return
            val setId = args.getString("setId")
            val sourceName = args.getString("sourceName") ?: ""
            val sourceType = args.getString("sourceType") ?: "book"

            if (title.isBlank()) {
                requireContext().toastOnUi(getString(R.string.homepage_module_title))
                return
            }

            val def = ModuleDef(
                key = args.getString("moduleKey") ?: "",
                type = selectedType,
                title = title,
                args = moduleArgs,
                url = url.ifBlank { null },
                sourceUrl = sourceUrl,
            )
            if (isCreate) {
                if (sourceType == "rss") {
                    viewModel.addRssCustomModule(sourceUrl, setId, def, sourceName)
                } else {
                    viewModel.addCustomModule(sourceUrl, setId, def)
                }
            } else {
                viewModel.updateModule(args.getString("id") ?: "", def)
            }
            requireContext().toastOnUi(getString(R.string.homepage_module_added))
            dismiss()
        }
    }
}
