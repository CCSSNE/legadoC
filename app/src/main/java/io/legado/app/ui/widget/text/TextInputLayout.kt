package io.legado.app.ui.widget.text

import android.content.Context
import android.graphics.Typeface
import android.util.AttributeSet
import com.google.android.material.textfield.TextInputLayout
import io.legado.app.lib.theme.Selector
import io.legado.app.lib.theme.ThemeStore
import io.legado.app.lib.theme.uiTypeface

class TextInputLayout(context: Context, attrs: AttributeSet?) : TextInputLayout(context, attrs) {

    private var appliedTypeface: Typeface? = null

    init {
        if (!isInEditMode) {
            defaultHintTextColor =
                Selector.colorBuild().setDefaultColor(ThemeStore.accentColor(context)).create()
            applyUiInputTypeface()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        applyUiInputTypeface()
    }

    override fun setHint(hint: CharSequence?) {
        super.setHint(hint)
        applyUiInputTypeface()
    }

    private fun applyUiInputTypeface() {
        if (isInEditMode) return
        val typeface = context.uiTypeface()
        if (appliedTypeface != typeface) {
            setTypeface(typeface)
            appliedTypeface = typeface
        }
        editText?.typeface = typeface
    }

}
