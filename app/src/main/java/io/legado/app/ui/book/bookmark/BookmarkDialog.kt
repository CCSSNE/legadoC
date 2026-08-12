package io.legado.app.ui.book.bookmark

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.lifecycleScope
import com.jaredrummler.android.colorpicker.ColorPickerDialog
import com.jaredrummler.android.colorpicker.ColorPickerDialogListener
import com.jaredrummler.android.colorpicker.ColorShape
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
import io.legado.app.utils.postEvent
import io.legado.app.utils.setLayout
import io.legado.app.utils.viewbindingdelegate.viewBinding
import io.legado.app.utils.visible
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import splitties.init.appCtx
import splitties.views.onClick

class BookmarkDialog() : BaseDialogFragment(R.layout.dialog_bookmark, true),
    ColorPickerDialogListener {

    constructor(bookmark: Bookmark, editPos: Int = -1) : this() {
        arguments = Bundle().apply {
            putInt("editPos", editPos)
            putParcelable("bookmark", bookmark)
        }
    }

    private val binding by viewBinding(DialogBookmarkBinding::bind)
    private var selectedColor = 0

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
        selectedColor = bookmark.color
        checkStyleRadio(bookmark.style)
        upColorPanel()
        binding.tvFooterLeft.visible(editPos >= 0)
        binding.run {
            tvChapterName.text = bookmark.chapterName
            editBookText.setText(bookmark.bookText)
            editContent.setText(bookmark.content)
            colorPanel.onClick {
                showColorPicker()
            }
            tvColorDefault.onClick {
                selectedColor = 0
                upColorPanel()
            }
            tvCancel.setOnClickListener {
                dismiss()
            }
            tvOk.setOnClickListener {
                bookmark.bookText = editBookText.text?.toString() ?: ""
                bookmark.content = editContent.text?.toString() ?: ""
                bookmark.style = getCheckedStyle()
                bookmark.color = selectedColor
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

    private fun checkStyleRadio(style: Int) {
        binding.rgStyle.check(
            when (style) {
                BookmarkStyle.SINGLE_UNDERLINE -> R.id.rb_style_single
                BookmarkStyle.DOUBLE_UNDERLINE -> R.id.rb_style_double
                BookmarkStyle.WAVE_UNDERLINE -> R.id.rb_style_wave
                BookmarkStyle.HIGHLIGHT -> R.id.rb_style_highlight
                BookmarkStyle.TEXT_COLOR -> R.id.rb_style_text_color
                BookmarkStyle.STRIKETHROUGH -> R.id.rb_style_strikethrough
                else -> R.id.rb_style_none
            }
        )
    }

    private fun getCheckedStyle(): Int {
        return when (binding.rgStyle.checkedRadioButtonId) {
            R.id.rb_style_single -> BookmarkStyle.SINGLE_UNDERLINE
            R.id.rb_style_double -> BookmarkStyle.DOUBLE_UNDERLINE
            R.id.rb_style_wave -> BookmarkStyle.WAVE_UNDERLINE
            R.id.rb_style_highlight -> BookmarkStyle.HIGHLIGHT
            R.id.rb_style_text_color -> BookmarkStyle.TEXT_COLOR
            R.id.rb_style_strikethrough -> BookmarkStyle.STRIKETHROUGH
            else -> BookmarkStyle.NONE
        }
    }

    private fun upColorPanel() {
        binding.colorPanel.color = if (selectedColor != 0) selectedColor else appCtx.accentColor
    }

    private fun showColorPicker() {
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
            .setColor(if (selectedColor != 0) selectedColor else appCtx.accentColor)
            .setDialogId(1)
            .create()
        dialog.setColorPickerDialogListener(this)
        dialog.show(childFragmentManager, "bookmark_color_picker")
    }

    override fun onColorSelected(dialogId: Int, color: Int) {
        selectedColor = if (color == ColorPreference.ColorPickerDialogCompat.DEFAULT_COLOR) {
            0
        } else {
            color
        }
        upColorPanel()
    }

    override fun onDialogDismissed(dialogId: Int) {
    }

}
