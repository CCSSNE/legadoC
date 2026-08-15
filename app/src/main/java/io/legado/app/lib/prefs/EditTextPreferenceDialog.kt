package io.legado.app.lib.prefs

import android.app.Dialog
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.preference.EditTextPreferenceDialogFragmentCompat
import androidx.preference.PreferenceDialogFragmentCompat
import io.legado.app.lib.theme.accentColor
import io.legado.app.utils.applyPreferenceDialogSurface

class EditTextPreferenceDialog : EditTextPreferenceDialogFragmentCompat() {

    companion object {

        fun newInstance(key: String): EditTextPreferenceDialog {
            val fragment = EditTextPreferenceDialog()
            val b = Bundle(1)
            b.putString(PreferenceDialogFragmentCompat.ARG_KEY, key)
            fragment.arguments = b
            return fragment
        }

    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState)
        dialog.window?.decorView?.post {
            (dialog as AlertDialog).run {
                getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(accentColor)
                getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(accentColor)
                getButton(AlertDialog.BUTTON_NEUTRAL)?.setTextColor(accentColor)
            }
        }
        return dialog
    }

    override fun onStart() {
        super.onStart()
        dialog?.applyPreferenceDialogSurface()
    }

}
