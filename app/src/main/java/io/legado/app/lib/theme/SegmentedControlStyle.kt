package io.legado.app.lib.theme

import android.graphics.Color
import android.view.View
import android.widget.TextView

/**
 * Shared visual contract for equal-width, single-selection controls.
 *
 * The layout contract is a 42dp effective track with 4dp insets and 14sp regular labels.
 * Callers resolve their own surface colors before applying this style, so Activity and Dialog
 * controls share hierarchy and state behavior without bypassing their host surface alpha.
 */
object SegmentedControlStyle {

    data class Palette(
        val trackColor: Int,
        val selectedColor: Int,
        val textColor: Int,
        val selectedTextColor: Int
    )

    fun apply(
        track: View,
        items: List<TextView>,
        selectedIndex: Int,
        palette: Palette
    ) {
        require(items.isNotEmpty()) { "Segmented control requires at least one item" }
        require(selectedIndex in items.indices) {
            "Selected index $selectedIndex is outside ${items.indices}"
        }
        val context = track.context
        track.background = UiCorner.opaqueRounded(
            palette.trackColor,
            UiCorner.panelRadius(context)
        )
        items.forEachIndexed { index, item ->
            val selected = index == selectedIndex
            item.isSelected = selected
            item.setTextColor(if (selected) palette.selectedTextColor else palette.textColor)
            item.background = UiCorner.actionSelector(
                Color.TRANSPARENT,
                palette.selectedColor,
                UiCorner.actionRadius(context)
            )
        }
    }
}
