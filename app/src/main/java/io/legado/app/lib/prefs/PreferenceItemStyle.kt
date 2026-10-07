package io.legado.app.lib.prefs

import android.content.Context
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceGroup
import androidx.preference.PreferenceViewHolder
import io.legado.app.R
import io.legado.app.lib.theme.UiCorner
import kotlin.math.roundToInt
import androidx.preference.Preference as AndroidPreference
import androidx.preference.PreferenceCategory as AndroidPreferenceCategory

object PreferenceItemStyle {

    /**
     * 偏好行卡片实际绘制的表面色——文字明暗判定必须与绘制同源。
     * 不能改用原始 bottomBackground 存值：主题可能把 bottomBackground 配成
     * 亮度不足 0.5 的中灰（如 #B6B6B6），而卡片经 surfaceColor 混合后渲染为浅色，
     * 按原始值判定会得到白字配浅卡。
     */
    fun itemSurfaceColor(context: Context): Int {
        return UiCorner.surfaceColor(UiCorner.themeSurfaceCardColor(context))
    }

    fun apply(preference: AndroidPreference, holder: PreferenceViewHolder) {
        val parent = preference.parent ?: return
        val hasPrev = hasVisibleSibling(parent, preference, forward = false)
        val hasNext = hasVisibleSibling(parent, preference, forward = true)
        holder.isDividerAllowedAbove = false
        holder.isDividerAllowedBelow = false
        val itemColor = itemSurfaceColor(preference.context)
        val dividerColor = UiCorner.surfaceColor(
            ContextCompat.getColor(preference.context, R.color.bg_divider_line)
        )
        val radius = UiCorner.panelRadius(preference.context)
        val dividerInset = holder.itemView.dp(16).toFloat()
        val current = holder.itemView.background as? PreferenceGroupBackgroundDrawable
        if (current == null || !current.hasSameConfig(
                normalColor = itemColor,
                pressedColor = itemColor,
                dividerColor = dividerColor,
                radius = radius,
                hasPrev = hasPrev,
                hasNext = hasNext,
                dividerInset = dividerInset
            )
        ) {
            holder.itemView.background = PreferenceGroupBackgroundDrawable(
                normalColor = itemColor,
                pressedColor = itemColor,
                dividerColor = dividerColor,
                radius = radius,
                hasPrev = hasPrev,
                hasNext = hasNext,
                dividerInset = dividerInset
            )
        }
        holder.itemView.updateGroupMargins(!hasPrev, !hasNext, parent)
    }

    private fun hasVisibleSibling(
        parent: PreferenceGroup,
        preference: AndroidPreference,
        forward: Boolean
    ): Boolean {
        val index = parent.indexOf(preference)
        if (index == -1) return false
        val range = if (forward) {
            (index + 1) until parent.preferenceCount
        } else {
            (index - 1) downTo 0
        }
        for (i in range) {
            val sibling = parent.getPreference(i)
            if (!sibling.isVisible) continue
            if (sibling is AndroidPreferenceCategory) return false
            return true
        }
        return false
    }

    private fun PreferenceGroup.indexOf(preference: AndroidPreference): Int {
        for (i in 0 until preferenceCount) {
            if (getPreference(i) == preference) return i
        }
        return -1
    }

    private fun View.updateGroupMargins(
        isFirst: Boolean,
        isLast: Boolean,
        parent: PreferenceGroup
    ) {
        val lp = layoutParams as? ViewGroup.MarginLayoutParams ?: return
        val horizontal = dp(12)
        val edge = if (parent is AndroidPreferenceCategory) 0 else dp(8)
        val top = if (isFirst) edge else 0
        val bottom = if (isLast) dp(8) else 0
        if (
            lp.leftMargin != horizontal ||
            lp.rightMargin != horizontal ||
            lp.topMargin != top ||
            lp.bottomMargin != bottom
        ) {
            lp.setMargins(horizontal, top, horizontal, bottom)
            layoutParams = lp
        }
    }

    private fun View.dp(value: Int): Int {
        return (resources.displayMetrics.density * value).roundToInt()
    }
}
