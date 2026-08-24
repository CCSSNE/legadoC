package io.legado.app.lib.prefs

import android.content.Context
import android.util.AttributeSet

/**
 * A switch whose enabled row can open one related numeric setting without adding
 * another preference row. Tapping the switch widget itself still changes the value.
 */
class SizeSwitchPreference(context: Context, attrs: AttributeSet) :
    SwitchPreference(context, attrs) {

    private var onEnabledRowClick: ((SizeSwitchPreference) -> Unit)? = null

    override fun onClick() {
        if (isChecked) {
            onEnabledRowClick?.invoke(this)
        } else {
            super.onClick()
        }
    }

    fun onEnabledRowClick(listener: (SizeSwitchPreference) -> Unit) {
        onEnabledRowClick = listener
    }
}
