package io.legado.app.ui.widget

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.google.android.flexbox.FlexboxLayout
import com.google.android.flexbox.FlexWrap
import io.legado.app.R
import io.legado.app.data.entities.rule.RowUi
import io.legado.app.lib.theme.applyUiBodyTypeface
import io.legado.app.lib.theme.applyUiTitleTypeface
import io.legado.app.lib.theme.primaryColor
import io.legado.app.lib.theme.primaryTextColor
import io.legado.app.utils.dpToPx

object RowUiDialog {

    data class Config(
        val title: CharSequence,
        val rows: List<RowUi>,
        val values: Map<String, String> = emptyMap(),
        val dismissOnAction: Boolean = true,
        val dismissOnSelect: Boolean = true,
        val dismissOnToggle: Boolean = false
    )

    interface Callback {
        fun onValueChanged(rowUi: RowUi, value: String) = Unit
        fun onAction(rowUi: RowUi, isLongClick: Boolean) = Unit
    }

    fun show(
        context: Context,
        config: Config,
        callback: Callback
    ): AlertDialog {
        val content = FlexboxLayout(context).apply {
            flexWrap = FlexWrap.WRAP
            setPadding(12.dpToPx(), 10.dpToPx(), 12.dpToPx(), 12.dpToPx())
            applyUiBodyTypeface(context)
        }
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_book_info_intro_panel)
            clipToOutline = true
        }
        val titleView = TextView(context).apply {
            text = config.title
            applyUiTitleTypeface(context)
            setTextColor(context.primaryTextColor)
            textSize = 18f
            gravity = Gravity.CENTER_VERTICAL
            setPadding(16.dpToPx(), 0, 16.dpToPx(), 0)
            setBackgroundColor(context.primaryColor)
        }
        root.addView(
            titleView,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                48.dpToPx()
            )
        )
        var dialog: AlertDialog? = null
        RowUiForm.render(
            container = content,
            rows = config.rows,
            values = config.values,
            callback = object : RowUiForm.Callback {
                override fun onValueChanged(rowUi: RowUi, value: String) {
                    callback.onValueChanged(rowUi, value)
                    if (rowUi.type == RowUi.Type.select && config.dismissOnSelect) {
                        dialog?.dismiss()
                    }
                }

                override fun onAction(rowUi: RowUi, isLongClick: Boolean) {
                    callback.onAction(rowUi, isLongClick)
                    when {
                        rowUi.type == RowUi.Type.toggle && config.dismissOnToggle -> dialog?.dismiss()
                        rowUi.type != RowUi.Type.toggle && config.dismissOnAction -> dialog?.dismiss()
                    }
                }
            }
        )
        root.addView(
            ScrollView(context).apply { addView(content) },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
        val dialogView = FrameLayout(context).apply {
            setPadding(16.dpToPx(), 16.dpToPx(), 16.dpToPx(), 16.dpToPx())
            addView(
                root,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                )
            )
            applyUiBodyTypeface(context)
        }
        dialog = AlertDialog.Builder(context)
            .setView(dialogView)
            .show()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        return dialog
    }
}
