package io.legado.app.lib.prefs

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceViewHolder
import io.legado.app.R
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.applyUiTitleTypeface


class PreferenceCategory(context: Context, attrs: AttributeSet? = null) :
    PreferenceCategory(context, attrs) {

    init {
        isPersistent = true
        layoutResource = R.layout.view_preference_category
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        val view = holder.findViewById(R.id.preference_title)
        if (view is TextView) {  //  && !view.isInEditMode
            view.text = title
            if (view.isInEditMode) return
            Preference.applyTypeface(context, holder)
            view.applyUiTitleTypeface(context)
            view.setTextColor(context.accentColor)
            view.isVisible = !title.isNullOrEmpty()

            // 分类标题之间不再绘制额外横杠。分类本身的标题背景和间距已经足够区分层级，
            // 也避免 Preference 的分隔规则在不同页面上产生一条漏网的实色带。
            holder.findViewById(R.id.preference_divider_above).isVisible = false
            holder.findViewById(R.id.preference_divider_below).isVisible = false
        }
    }

}
