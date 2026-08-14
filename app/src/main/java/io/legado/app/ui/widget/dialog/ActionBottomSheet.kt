package io.legado.app.ui.widget.dialog

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.material.bottomsheet.BottomSheetDialog
import io.legado.app.R
import io.legado.app.lib.dialogs.SelectItem
import io.legado.app.lib.theme.UiCorner
import io.legado.app.utils.applyAdaptiveDim
import io.legado.app.utils.dpToPx

/**
 * 从屏幕底部弹出的操作菜单。
 * 与阅读页长按的悬浮窗区分：全屏看图、目录配图页等场景使用底部弹层。
 */
fun showActionBottomSheet(
    context: Context,
    items: List<SelectItem<String>>,
    onActionClick: (String) -> Unit
) {
    val dialog = BottomSheetDialog(context)
    val layout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = UiCorner.rounded(
            ContextCompat.getColor(context, R.color.background_card),
            UiCorner.panelRadius(context)
        )
    }
    val selectableBackground = context.theme.obtainStyledAttributes(
        intArrayOf(android.R.attr.selectableItemBackground)
    ).let { typedArray ->
        val resId = typedArray.getResourceId(0, 0)
        typedArray.recycle()
        resId
    }
    val dividerColor = ContextCompat.getColor(context, R.color.divider)
    items.forEach { item ->
        val textView = TextView(context).apply {
            text = item.title
            textSize = 16f
            gravity = Gravity.CENTER_VERTICAL
            setTextColor(ContextCompat.getColor(context, R.color.primaryText))
            setPadding(20.dpToPx(), 0, 20.dpToPx(), 0)
            if (selectableBackground != 0) {
                setBackgroundResource(selectableBackground)
            }
            setOnClickListener {
                dialog.dismiss()
                onActionClick(item.value)
            }
        }
        layout.addView(
            textView,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                52.dpToPx()
            )
        )
        val divider = View(context).apply {
            setBackgroundColor(dividerColor)
        }
        layout.addView(
            divider,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                1.dpToPx()
            ).apply {
                leftMargin = 16.dpToPx()
                rightMargin = 16.dpToPx()
            }
        )
    }
    dialog.setContentView(layout)
    dialog.show()
    dialog.applyAdaptiveDim()
}
