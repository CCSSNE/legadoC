package io.legado.app.lib.dialogs

import android.content.Context
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import io.legado.app.lib.theme.applyUiTitleTypeface
import io.legado.app.lib.theme.primaryTextColor
import io.legado.app.utils.dpToPx

internal fun AlertDialog.Builder.setUiTitle(
    context: Context,
    title: CharSequence
): AlertDialog.Builder {
    return setCustomTitle(context.createUiDialogTitleView(title))
}

internal fun AlertDialog.Builder.setUiTitle(
    context: Context,
    @StringRes titleRes: Int
): AlertDialog.Builder {
    return setUiTitle(context, context.getString(titleRes))
}

private fun Context.createUiDialogTitleView(title: CharSequence): TextView {
    return TextView(this).apply {
        text = title
        applyUiTitleTypeface(this@createUiDialogTitleView)
        setTextColor(primaryTextColor)
        textSize = 20f
        includeFontPadding = false
        setPadding(24.dpToPx(), 22.dpToPx(), 24.dpToPx(), 4.dpToPx())
    }
}
