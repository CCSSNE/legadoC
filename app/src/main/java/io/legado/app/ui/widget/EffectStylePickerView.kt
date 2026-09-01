package io.legado.app.ui.widget

import android.content.Context
import android.util.AttributeSet
import android.view.Gravity
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.FragmentManager
import com.jaredrummler.android.colorpicker.ColorPanelView
import com.jaredrummler.android.colorpicker.ColorPickerDialog
import com.jaredrummler.android.colorpicker.ColorPickerDialogListener
import com.jaredrummler.android.colorpicker.ColorShape
import io.legado.app.R
import io.legado.app.data.entities.BookmarkStyle
import io.legado.app.lib.prefs.ColorPreference
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.view.ThemeCheckBox
import io.legado.app.utils.dpToPx
import io.legado.app.utils.getCompatColor
import splitties.init.appCtx

/**
 * 显示效果选择器：效果勾选（无效果/单下划线/双下划线/波浪线/高亮/文字颜色/删除线）
 * + 逐效果颜色设置。供高亮规则编辑与书签编辑共用。
 */
class EffectStylePickerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs), ColorPickerDialogListener {

    private val cbStyleNone by lazy { findViewById<ThemeCheckBox>(R.id.cb_style_none) }
    private val cbStyleSingle by lazy { findViewById<ThemeCheckBox>(R.id.cb_style_single) }
    private val cbStyleDouble by lazy { findViewById<ThemeCheckBox>(R.id.cb_style_double) }
    private val cbStyleWave by lazy { findViewById<ThemeCheckBox>(R.id.cb_style_wave) }
    private val cbStyleHighlight by lazy { findViewById<ThemeCheckBox>(R.id.cb_style_highlight) }
    private val cbStyleTextColor by lazy { findViewById<ThemeCheckBox>(R.id.cb_style_text_color) }
    private val cbStyleStrikethrough by lazy { findViewById<ThemeCheckBox>(R.id.cb_style_strikethrough) }
    private val llEffectColors by lazy { findViewById<LinearLayout>(R.id.ll_effect_colors) }

    private val effectColorMap = mutableMapOf<Int, Int>()
    private var fallbackColor = 0
    private var fragmentManager: FragmentManager? = null

    init {
        orientation = VERTICAL
        LayoutInflater.from(context).inflate(R.layout.view_effect_style_picker, this, true)
        initStyleCheckBoxes()
    }

    fun setFragmentManager(manager: FragmentManager?) {
        fragmentManager = manager
    }

    /**
     * 设置初始状态。fallbackColor 为未单独设置颜色时的展示兜底色（如书签全局颜色，规则场景传 0）。
     */
    fun setStyles(style: Int, styleColors: Map<Int, Int>, fallbackColor: Int) {
        this.fallbackColor = fallbackColor
        effectColorMap.clear()
        effectColorMap.putAll(styleColors)
        checkStyleBoxes(style)
        rebuildEffectColorRows()
    }

    fun getCheckedStyles(): Int {
        var styles = BookmarkStyle.NONE
        if (cbStyleSingle.isChecked) styles = styles or BookmarkStyle.SINGLE_UNDERLINE
        if (cbStyleDouble.isChecked) styles = styles or BookmarkStyle.DOUBLE_UNDERLINE
        if (cbStyleWave.isChecked) styles = styles or BookmarkStyle.WAVE_UNDERLINE
        if (cbStyleHighlight.isChecked) styles = styles or BookmarkStyle.HIGHLIGHT
        if (cbStyleTextColor.isChecked) styles = styles or BookmarkStyle.TEXT_COLOR
        if (cbStyleStrikethrough.isChecked) styles = styles or BookmarkStyle.STRIKETHROUGH
        return styles
    }

    fun getStyleColorsJson(): String {
        return BookmarkStyle.toStyleColorsJson(effectColorMap)
    }

    private fun checkStyleBoxes(styles: Int) {
        cbStyleNone.isChecked = styles == BookmarkStyle.NONE
        cbStyleSingle.isChecked = styles and BookmarkStyle.SINGLE_UNDERLINE != 0
        cbStyleDouble.isChecked = styles and BookmarkStyle.DOUBLE_UNDERLINE != 0
        cbStyleWave.isChecked = styles and BookmarkStyle.WAVE_UNDERLINE != 0
        cbStyleHighlight.isChecked = styles and BookmarkStyle.HIGHLIGHT != 0
        cbStyleTextColor.isChecked = styles and BookmarkStyle.TEXT_COLOR != 0
        cbStyleStrikethrough.isChecked = styles and BookmarkStyle.STRIKETHROUGH != 0
    }

    /**
     * 效果可多选组合；"无效果"与其他效果互斥，勾选其一自动取消另一方
     */
    private fun initStyleCheckBoxes() {
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

    /**
     * 为每个已勾选的效果生成一行颜色设置（点击色块弹出颜色选择器）
     */
    private fun rebuildEffectColorRows() {
        llEffectColors.removeAllViews()
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
                BookmarkStyle.SINGLE_UNDERLINE -> cbStyleSingle.isChecked
                BookmarkStyle.DOUBLE_UNDERLINE -> cbStyleDouble.isChecked
                BookmarkStyle.WAVE_UNDERLINE -> cbStyleWave.isChecked
                BookmarkStyle.HIGHLIGHT -> cbStyleHighlight.isChecked
                BookmarkStyle.TEXT_COLOR -> cbStyleTextColor.isChecked
                else -> cbStyleStrikethrough.isChecked
            }
            if (!checked) return@forEach
            val row = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                val pad = 6.dpToPx()
                setPadding(pad, pad, pad, pad)
            }
            row.addView(
                TextView(context).apply {
                    text = context.getString(nameRes)
                    textSize = 13f
                    setTextColor(context.getCompatColor(R.color.secondaryText))
                    layoutParams = LayoutParams(
                        0,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        1f
                    )
                }
            )
            val colorPanel = ColorPanelView(context).apply {
                color = effectColorOrFallback(bit)
                layoutParams = LayoutParams(32.dpToPx(), 32.dpToPx())
                setOnClickListener {
                    showColorPicker(bit)
                }
            }
            row.addView(colorPanel)
            llEffectColors.addView(row)
        }
    }

    private fun effectColorOrFallback(styleBit: Int): Int {
        return effectColorMap[styleBit]?.takeIf { it != 0 }
            ?: fallbackColor.takeIf { it != 0 }
            ?: appCtx.accentColor
    }

    private fun showColorPicker(dialogId: Int) {
        val fragmentManager = fragmentManager ?: return
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
            .setColor(effectColorOrFallback(dialogId))
            .setDialogId(dialogId)
            .create()
        dialog.setColorPickerDialogListener(this)
        dialog.show(fragmentManager, "effect_style_color_picker")
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
