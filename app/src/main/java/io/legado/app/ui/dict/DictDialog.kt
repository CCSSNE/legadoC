package io.legado.app.ui.dict

import android.os.Bundle
import android.text.method.LinkMovementMethod
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.viewModels
import com.bumptech.glide.Glide
import com.bumptech.glide.request.RequestOptions
import com.google.android.material.tabs.TabLayout
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.data.entities.DictRule
import io.legado.app.databinding.DialogDictBinding
import io.legado.app.help.GlideImageGetter
import io.legado.app.help.TextViewTagHandler
import io.legado.app.lib.theme.UiCorner
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.applyUiMenuItemTypeface
import io.legado.app.lib.theme.applyUiTabTypeface
import io.legado.app.lib.theme.backgroundColor
import io.legado.app.lib.theme.secondaryTextColor
import io.legado.app.ui.widget.dialog.PhotoDialog
import io.legado.app.utils.dpToPx
import io.legado.app.utils.gone
import io.legado.app.utils.setHtml
import io.legado.app.utils.setLayout
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import io.legado.app.utils.visible

/**
 * 词典
 */
class DictDialog() : BaseDialogFragment(R.layout.dialog_dict) {

    constructor(word: String) : this() {
        arguments = Bundle().apply {
            putString("word", word)
        }
    }

    private val viewModel by viewModels<DictViewModel>()
    private val binding by viewBinding(DialogDictBinding::bind)
    private var word: String? = null
    private val imgAvailableWidth by lazy {
        val textView = binding.tvDict
        textView.width - textView.paddingLeft - textView.paddingRight
    }
    private var initGetter = false
    private val glideImageGetter by lazy {
        initGetter = true
        GlideImageGetter(
            requireContext(),
            binding.tvDict,
            this@DictDialog.lifecycle,
            imgAvailableWidth
        )
    }

    override fun onStart() {
        super.onStart()
        setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        binding.tvDict.movementMethod = LinkMovementMethod()
        word = arguments?.getString("word")
        if (word.isNullOrEmpty()) {
            toastOnUi(R.string.cannot_empty)
            dismiss()
            return
        }
        binding.tabLayout.setBackgroundColor(backgroundColor)
        binding.tabLayout.setSelectedTabIndicatorColor(accentColor)
        binding.tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabReselected(tab: TabLayout.Tab) {
            }

            override fun onTabUnselected(tab: TabLayout.Tab) {
                updateDictTabs()
            }

            override fun onTabSelected(tab: TabLayout.Tab) {
                updateDictTabs()
                val dictRule = tab.tag as DictRule
                binding.rotateLoading.visible()
                viewModel.dict(dictRule, word!!) {
                    binding.rotateLoading.inVisible()
                    val contentTrimS = it.trimStart()
                    if (contentTrimS.startsWith("<md>")) {
                        val lastIndex = contentTrimS.lastIndexOf("<")
                        if (lastIndex < 4) {
                            binding.mdPreview.gone()
                            binding.tvDict.visible()
                            binding.tvDict.text = contentTrimS
                            return@dict
                        }
                        val mark = contentTrimS.substring(4, lastIndex)
                        binding.tvDict.gone()
                        binding.mdPreview.visible()
                        binding.mdPreview.onImageLongPress = { source ->
                            showDialogFragment(PhotoDialog(source))
                        }
                        binding.mdPreview.setMarkdown(mark)
                        return@dict
                    }
                    val textViewTagHandler = TextViewTagHandler(object : TextViewTagHandler.OnButtonClickListener {
                        override fun onButtonClick(name: String, click: String) {
                            viewModel.onButtonClick(dictRule, "button $name", click)
                        }
                    })
                    binding.mdPreview.gone()
                    binding.tvDict.visible()
                    binding.tvDict.setHtml(
                        it,
                        glideImageGetter,
                        textViewTagHandler,
                        imgOnLongClickListener = { source ->
                            showDialogFragment(PhotoDialog(source))
                        },
                        imgOnClickListener = { click  ->
                            viewModel.onButtonClick(dictRule, "image", click)
                        }
                    )
                }
            }
        })
        viewModel.initData {
            it.forEach { d  ->
                binding.tabLayout.addTab(binding.tabLayout.newTab().apply {
                    customView = createDictTabView(d.name, false)
                    tag = d
                })
            }
            setupTabLayoutMode(it.size)
            binding.tabLayout.applyUiTabTypeface(requireContext())
            updateDictTabs()
        }
    }

    private fun createDictTabView(name: String, selected: Boolean): TextView {
        return TextView(requireContext()).apply {
            text = name
            gravity = Gravity.CENTER
            maxLines = 1
            includeFontPadding = false
            isSelected = selected
            setTextColor(if (selected) accentColor else secondaryTextColor)
            textSize = 14f
            applyUiMenuItemTypeface(requireContext())
            setPadding(14.dpToPx(), 8.dpToPx(), 14.dpToPx(), 8.dpToPx())
            background = UiCorner.actionSelector(
                android.graphics.Color.TRANSPARENT,
                backgroundColor,
                UiCorner.actionRadius(requireContext())
            )
        }
    }

    private fun updateDictTabs() {
        for (index in 0 until binding.tabLayout.tabCount) {
            val tab = binding.tabLayout.getTabAt(index) ?: continue
            val selected = tab.isSelected
            (tab.customView as? TextView)?.run {
                isSelected = selected
                setTextColor(if (selected) accentColor else secondaryTextColor)
            }
        }
    }

    //根据已启用词典数动态选取布局
    private fun setupTabLayoutMode(dictCount: Int) {
        if (dictCount <= 4) {
            binding.tabLayout.tabMode = TabLayout.MODE_FIXED
            binding.tabLayout.tabGravity = TabLayout.GRAVITY_FILL
        } else {
            binding.tabLayout.tabMode = TabLayout.MODE_SCROLLABLE
            binding.tabLayout.tabGravity = TabLayout.GRAVITY_CENTER
        }
    }

    override fun onDestroyView() {
        binding.mdPreview.destroyPreview()
        super.onDestroyView()
        if (initGetter) {
            glideImageGetter.clear()
        }
    }
}
