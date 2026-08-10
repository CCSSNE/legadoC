package io.legado.app.ui.widget

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Space
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.AppCompatImageButton
import androidx.core.content.ContextCompat
import io.legado.app.R
import io.legado.app.lib.theme.UiCorner
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.applyUiBodyTypeface
import io.legado.app.lib.theme.applyUiTitleTypeface
import io.legado.app.lib.theme.primaryColor
import io.legado.app.lib.theme.primaryTextColor
import io.legado.app.utils.dpToPx

object ExpandableTagSelector {

    const val EXPAND_THRESHOLD = 9

    data class GridItem(
        val text: CharSequence,
        val selected: Boolean,
        val value: Int
    )

    fun configureExpandButton(button: AppCompatImageButton) {
        val context = button.context
        button.setBackgroundResource(R.drawable.bg_discover_embedded_action)
        button.contentDescription = context.getString(R.string.expand)
        val padding = context.resources.getDimensionPixelSize(R.dimen.bookshelf_action_button_padding)
        button.setPadding(padding, padding, padding, padding)
        button.scaleType = ImageView.ScaleType.CENTER_INSIDE
        button.setImageResource(R.drawable.ic_arrow_drop_down)
        button.setColorFilter(context.primaryTextColor)
    }

    fun createExpandButton(
        context: Context,
        onClick: () -> Unit
    ): AppCompatImageButton {
        return AppCompatImageButton(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                resources.getDimensionPixelSize(R.dimen.bookshelf_action_button_size),
                resources.getDimensionPixelSize(R.dimen.bookshelf_action_button_size)
            ).apply {
                marginStart = 6.dpToPx()
            }
            configureExpandButton(this)
            setOnClickListener { onClick() }
        }
    }

    fun show(
        context: Context,
        title: CharSequence,
        items: List<GridItem>,
        onSelected: (Int) -> Unit
    ) {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_book_info_intro_panel)
            clipToOutline = true
        }
        val titleView = TextView(context).apply {
            text = title
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
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(12.dpToPx(), 10.dpToPx(), 12.dpToPx(), 12.dpToPx())
            applyUiBodyTypeface(context)
        }
        var dialog: AlertDialog? = null
        var row = createGridRow(context)
        var usedSpan = 0
        fun addRowIfNeeded() {
            if (row.childCount > 0) {
                if (usedSpan < 3) {
                    row.addView(Space(context), LinearLayout.LayoutParams(0, 1, (3 - usedSpan).toFloat()))
                }
                content.addView(row)
            }
            row = createGridRow(context)
            usedSpan = 0
        }
        items.forEach { item ->
            val span = gridSpan(item.text)
            if (usedSpan > 0 && usedSpan + span > 3) {
                addRowIfNeeded()
            }
            val itemView = createGridItemView(context, item, span).apply {
                setOnClickListener {
                    dialog?.dismiss()
                    onSelected(item.value)
                }
            }
            row.addView(
                itemView,
                LinearLayout.LayoutParams(
                    0,
                    if (span == 3) LinearLayout.LayoutParams.WRAP_CONTENT else 44.dpToPx(),
                    span.toFloat()
                ).apply {
                    setMargins(4.dpToPx(), 6.dpToPx(), 4.dpToPx(), 6.dpToPx())
                }
            )
            usedSpan += span
            if (usedSpan == 3) {
                addRowIfNeeded()
            }
        }
        addRowIfNeeded()
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
    }

    private fun createGridRow(context: Context): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
    }

    private fun createGridItemView(context: Context, item: GridItem, span: Int): TextView {
        return TextView(context).apply {
            text = item.text
            applyUiBodyTypeface(context)
            isSelected = item.selected
            minHeight = 44.dpToPx()
            includeFontPadding = false
            gravity = Gravity.CENTER
            textSize = 14f
            setPadding(14.dpToPx(), 8.dpToPx(), 14.dpToPx(), 8.dpToPx())
            maxLines = if (span == 3) Int.MAX_VALUE else 1
            ellipsize = if (span == 3) null else TextUtils.TruncateAt.END
            setTextColor(
                ColorStateList(
                    arrayOf(intArrayOf(android.R.attr.state_selected), intArrayOf()),
                    intArrayOf(context.accentColor, context.primaryTextColor)
                )
            )
            background = UiCorner.actionSelector(
                Color.TRANSPARENT,
                ContextCompat.getColor(context, R.color.background_card),
                UiCorner.actionRadius(context)
            )
        }
    }

    private fun gridSpan(text: CharSequence): Int {
        val count = text.toString().let { it.codePointCount(0, it.length) }
        return when {
            count > 8 -> 3
            count > 4 -> 2
            else -> 1
        }
    }
}
