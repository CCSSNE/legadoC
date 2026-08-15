package io.legado.app.ui.book.bookmark

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import com.jaredrummler.android.colorpicker.ColorPickerDialog
import com.jaredrummler.android.colorpicker.ColorPickerDialogListener
import com.jaredrummler.android.colorpicker.ColorShape
import com.jaredrummler.android.colorpicker.ColorPanelView
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.constant.EventBus
import io.legado.app.data.appDb
import io.legado.app.data.entities.Bookmark
import io.legado.app.data.entities.BookmarkStyle
import io.legado.app.databinding.DialogBookmarkBinding
import io.legado.app.lib.prefs.ColorPreference
import io.legado.app.lib.theme.accentColor
import io.legado.app.utils.dpToPx
import io.legado.app.utils.getCompatColor
import io.legado.app.utils.gone
import io.legado.app.utils.postEvent
import io.legado.app.utils.setLayout
import io.legado.app.utils.viewbindingdelegate.viewBinding
import io.legado.app.utils.visible
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import splitties.init.appCtx

class BookmarkDialog() : BaseDialogFragment(R.layout.dialog_bookmark, true),
    ColorPickerDialogListener {

    constructor(bookmark: Bookmark, editPos: Int = -1) : this() {
        arguments = Bundle().apply {
            putInt("editPos", editPos)
            putParcelable("bookmark", bookmark)
        }
    }

    private val binding by viewBinding(DialogBookmarkBinding::bind)
    private val effectColorMap = mutableMapOf<Int, Int>()
    private var bookmark: Bookmark? = null

    override fun onStart() {
        super.onStart()
        setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        binding.toolBar.background = null
        val arguments = arguments ?: let {
            dismiss()
            return
        }

        @Suppress("DEPRECATION")
        val bookmark = arguments.getParcelable<Bookmark>("bookmark")
        bookmark ?: let {
            dismiss()
            return
        }
        this.bookmark = bookmark
        val editPos = arguments.getInt("editPos", -1)
        effectColorMap.clear()
        if (bookmark.isPageBookmark) {
            // 整页书签只保存页面位置，不允许在普通书签编辑器里写备注或效果。
            binding.toolBar.title = getString(R.string.bookmark_page_tag)
            binding.editBookText.isEnabled = false
            binding.editBookText.isFocusable = false
            binding.editBookText.isFocusableInTouchMode = false
            binding.editBookText.isClickable = false
            (binding.editContent.parent as? View)?.gone()
            binding.tvBookmarkStyle.gone()
            binding.llBookmarkStyles.gone()
            binding.llEffectColors.gone()
        } else {
            effectColorMap.putAll(BookmarkStyle.parseStyleColors(bookmark.styleColors))
            checkStyleBoxes(bookmark.style)
            initStyleCheckBoxes()
            rebuildEffectColorRows()
        }
        binding.tvFooterLeft.visible(editPos >= 0)
        binding.run {
            tvChapterName.text = bookmark.chapterName
            editBookText.setText(bookmark.bookText)
            editContent.setText(bookmark.content)
            tvCancel.setOnClickListener {
                dismiss()
            }
            tvOk.setOnClickListener {
                if (bookmark.isPageBookmark) {
                    // 防止历史数据或批量编辑遗留的普通书签属性污染整页书签。
                    bookmark.content = ""
                    bookmark.style = BookmarkStyle.NONE
                    bookmark.color = 0
                    bookmark.styleColors = ""
                } else {
                    bookmark.bookText = editBookText.text?.toString() ?: ""
                    bookmark.content = editContent.text?.toString() ?: ""
                    bookmark.style = getCheckedStyles()
                    bookmark.styleColors = BookmarkStyle.toStyleColorsJson(effectColorMap)
                }
                lifecycleScope.launch {
                    withContext(IO) {
                        appDb.bookmarkDao.insert(bookmark)
                    }
                    postEvent(EventBus.BOOKMARK_CHANGED, true)
                    dismiss()
                }
            }
            tvFooterLeft.setOnClickListener {
                lifecycleScope.launch {
                    withContext(IO) {
                        appDb.bookmarkDao.delete(bookmark)
                    }
                    postEvent(EventBus.BOOKMARK_CHANGED, true)
                    dismiss()
                }
            }
        }
    }

    private fun checkStyleBoxes(styles: Int) {
        binding.run {
            cbStyleNone.isChecked = styles == BookmarkStyle.NONE
            cbStyleSingle.isChecked = styles and BookmarkStyle.SINGLE_UNDERLINE != 0
            cbStyleDouble.isChecked = styles and BookmarkStyle.DOUBLE_UNDERLINE != 0
            cbStyleWave.isChecked = styles and BookmarkStyle.WAVE_UNDERLINE != 0
            cbStyleHighlight.isChecked = styles and BookmarkStyle.HIGHLIGHT != 0
            cbStyleTextColor.isChecked = styles and BookmarkStyle.TEXT_COLOR != 0
            cbStyleStrikethrough.isChecked = styles and BookmarkStyle.STRIKETHROUGH != 0
        }
    }

    /**
     * 效果可多选组合；"无效果"与其他效果互斥，勾选其一自动取消另一方
     */
    private fun initStyleCheckBoxes() {
        binding.run {
            val styleBoxes = listOf(
                cbStyleSingle,
                cbStyleDouble,
                cbStyleWave,
                cbStyleHighlight,
                cbStyleTextColor,
                cbStyleStrikethrough
            )
            cbStyleNone.setOnCheckedChangeListener { _, checked ->
                if (checked) {
                    styleBoxes.forEach { it.isChecked = false }
                }
                rebuildEffectColorRows()
            }
            styleBoxes.forEach { box ->
                box.setOnCheckedChangeListener { _, checked ->
                    if (checked) {
                        cbStyleNone.isChecked = false
                    }
                    rebuildEffectColorRows()
                }
            }
        }
    }

    /**
     * 为每个已勾选的效果生成一行颜色设置（点击色块弹出颜色选择器）
     */
    private fun rebuildEffectColorRows() {
        binding.llEffectColors.removeAllViews()
        val effectBits = listOf(
            BookmarkStyle.SINGLE_UNDERLINE to R.string.bookmark_style_single,
            BookmarkStyle.DOUBLE_UNDERLINE to R.string.bookmark_style_double,
            BookmarkStyle.WAVE_UNDERLINE to R.string.bookmark_style_wave,
            BookmarkStyle.HIGHLIGHT to R.string.bookmark_style_highlight,
            BookmarkStyle.TEXT_COLOR to R.string.bookmark_style_text_color,
            BookmarkStyle.STRIKETHROUGH to R.string.bookmark_style_strikethrough
        )
        effectBits.forEach { (bit, nameRes) ->
            val checked = when (bit) {
                BookmarkStyle.SINGLE_UNDERLINE -> binding.cbStyleSingle.isChecked
                BookmarkStyle.DOUBLE_UNDERLINE -> binding.cbStyleDouble.isChecked
                BookmarkStyle.WAVE_UNDERLINE -> binding.cbStyleWave.isChecked
                BookmarkStyle.HIGHLIGHT -> binding.cbStyleHighlight.isChecked
                BookmarkStyle.TEXT_COLOR -> binding.cbStyleTextColor.isChecked
                else -> binding.cbStyleStrikethrough.isChecked
            }
            if (!checked) return@forEach
            val row = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                val pad = 6.dpToPx()
                setPadding(pad, pad, pad, pad)
            }
            row.addView(
                TextView(requireContext()).apply {
                    text = getString(nameRes)
                    textSize = 13f
                    setTextColor(requireContext().getCompatColor(R.color.secondaryText))
                    layoutParams = LinearLayout.LayoutParams(
                        0,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        1f
                    )
                }
            )
            val colorPanel = ColorPanelView(requireContext()).apply {
                color = effectColorMap[bit]
                    ?: bookmark?.color?.takeIf { it != 0 }
                    ?: appCtx.accentColor
                layoutParams = LinearLayout.LayoutParams(32.dpToPx(), 32.dpToPx())
                setOnClickListener {
                    showColorPicker(bit)
                }
            }
            row.addView(colorPanel)
            binding.llEffectColors.addView(row)
        }
    }

    private fun getCheckedStyles(): Int {
        var styles = BookmarkStyle.NONE
        binding.run {
            if (cbStyleSingle.isChecked) styles = styles or BookmarkStyle.SINGLE_UNDERLINE
            if (cbStyleDouble.isChecked) styles = styles or BookmarkStyle.DOUBLE_UNDERLINE
            if (cbStyleWave.isChecked) styles = styles or BookmarkStyle.WAVE_UNDERLINE
            if (cbStyleHighlight.isChecked) styles = styles or BookmarkStyle.HIGHLIGHT
            if (cbStyleTextColor.isChecked) styles = styles or BookmarkStyle.TEXT_COLOR
            if (cbStyleStrikethrough.isChecked) styles = styles or BookmarkStyle.STRIKETHROUGH
        }
        return styles
    }

    @Suppress("DEPRECATION")
    private fun showColorPicker(dialogId: Int) {
        val bookmark = arguments?.getParcelable<Bookmark>("bookmark")
        val color = effectColorMap[dialogId]
            ?: bookmark?.color?.takeIf { it != 0 }
            ?: appCtx.accentColor
        val dialog = ColorPreference.ColorPickerDialogCompat.newBuilder()
            .setDialogType(ColorPickerDialog.TYPE_PRESETS)
            .setDialogTitle(R.string.bookmark_color)
            .setColorShape(ColorShape.CIRCLE)
            .setPresets(ColorPickerDialog.MATERIAL_COLORS)
            .setAllowPresets(true)
            .setAllowCustom(true)
            .setShowAlphaSlider(false)
            .setShowColorShades(true)
            .setShowDefaultColorButton(false)
            .setColor(color)
            .setDialogId(dialogId)
            .create()
        dialog.setColorPickerDialogListener(this)
        dialog.show(childFragmentManager, "bookmark_color_picker")
    }

    override fun onColorSelected(dialogId: Int, color: Int) {
        val value = if (color == ColorPreference.ColorPickerDialogCompat.DEFAULT_COLOR) {
            0
        } else {
            color
        }
        if (value == 0) {
            effectColorMap.remove(dialogId)
        } else {
            effectColorMap[dialogId] = value
        }
        rebuildEffectColorRows()
    }

    override fun onDialogDismissed(dialogId: Int) {
    }

}
