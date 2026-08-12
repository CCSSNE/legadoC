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
import io.legado.app.lib.theme.primaryColor
import io.legado.app.utils.dpToPx
import io.legado.app.utils.getCompatColor
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

    override fun onStart() {
        super.onStart()
        setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        binding.toolBar.setBackgroundColor(primaryColor)
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
        val editPos = arguments.getInt("editPos", -1)
        effectColorMap.clear()
        effectColorMap.putAll(BookmarkStyle.parseStyleColors(bookmark.styleColors))
        checkStyleBoxes(bookmark.style)
        initStyleCheckBoxes()
        rebuildEffectColorRows()
        binding.tvFooterLeft.visible(editPos >= 0)
        binding.run {
            tvChapterName.text = bookmark.chapterName
            editBookText.setText(bookmark.bookText)
            editContent.setText(bookmark.content)
            tvCancel.setOnClickListener {
                dismiss()
            }
            tvOk.setOnClickListener {
                bookmark.bookText = editBookText.text?.toString() ?: ""
                bookmark.content = editContent.text?.toString() ?: ""
                bookmark.style = getCheckedStyles()
                bookmark.styleColors = BookmarkStyle.toStyleColorsJson(effectColorMap)
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
     * 为每个已勾选的效果生成一行颜色设置（色块点击设置，默认恢复）
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
                    ?: bookmark.color.takeIf { it != 0 }
                    ?: appCtx.accentColor
                layoutParams = LinearLayout.LayoutParams(32.dpToPx(), 32.dpToPx())
                setOnClickListener {
                    showColorPicker(bit)
                }
            }
            row.addView(colorPanel)
            row.addView(
                TextView(requireContext()).apply {
                    text = getString(R.string.bookmark_color_default)
                    textSize = 12f
                    setTextColor(requireContext().getCompatColor(R.color.secondaryText))
                    setPadding(8.dpToPx(), 8.dpToPx(), 8.dpToPx(), 8.dpToPx())
                    setOnClickListener {
                        effectColorMap.remove(bit)
                        rebuildEffectColorRows()
                    }
                }
            )
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
            .setShowDefaultColorButton(true)
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
