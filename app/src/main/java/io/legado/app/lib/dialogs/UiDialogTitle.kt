package io.legado.app.lib.dialogs

import android.content.Context
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import io.legado.app.lib.theme.applyUiTitleTypeface
import io.legado.app.lib.theme.primaryTextColor
import io.legado.app.utils.dpToPx

/**
 * Compatibility entry point for legacy builders. The shared surface policy moves
 * this view into dialog content after the hierarchy is attached, rather than
 * rendering it in the framework title panel.
 */
internal fun AlertDialog.Builder.setUiTitle(
    context: Context,
    title: CharSequence
): AlertDialog.Builder {
    return setCustomTitle(TextView(context).apply {
        text = title
        applyUiTitleTypeface(context)
        setTextColor(context.primaryTextColor)
        textSize = 20f
        includeFontPadding = false
        setPadding(24.dpToPx(), 22.dpToPx(), 24.dpToPx(), 4.dpToPx())
    })
}

internal fun AlertDialog.Builder.setUiTitle(
    context: Context,
    @StringRes titleRes: Int
): AlertDialog.Builder = setUiTitle(context, context.getString(titleRes))
