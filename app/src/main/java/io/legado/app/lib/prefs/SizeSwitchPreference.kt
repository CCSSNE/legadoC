package io.legado.app.lib.prefs

import android.content.Context
import android.util.AttributeSet
import androidx.appcompat.widget.SwitchCompat
import androidx.preference.PreferenceViewHolder

/**
 * A switch whose enabled row can open one related numeric setting without adding
 * another preference row. The row and switch widget deliberately have distinct
 * click targets: the enabled row opens the size editor while the widget always
 * keeps the normal SwitchPreferenceCompat toggle behavior.
 */
class SizeSwitchPreference(context: Context, attrs: AttributeSet) :
    SwitchPreference(context, attrs) {

    private var onEnabledRowClick: ((SizeSwitchPreference) -> Unit)? = null

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        // SwitchPreferenceCompat normally makes this child non-clickable and
        // delegates every tap to the row. Make the widget consume its own tap;
        // the listener installed by SwitchPreferenceCompat still performs the
        // standard callChangeListener()/persisted checked-state update.
        (holder.findViewById(androidx.preference.R.id.switchWidget) as? SwitchCompat)?.apply {
            isClickable = true
            isFocusable = true
        }
    }

    override fun onClick() {
        val listener = onEnabledRowClick
        if (!isChecked || listener == null) {
            super.onClick()
        } else {
            listener.invoke(this)
        }
    }

    fun onEnabledRowClick(listener: (SizeSwitchPreference) -> Unit) {
        onEnabledRowClick = listener
    }
}
