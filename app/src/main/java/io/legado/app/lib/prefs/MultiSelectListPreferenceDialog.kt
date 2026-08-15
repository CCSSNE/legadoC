package io.legado.app.lib.prefs

import android.app.Dialog
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.core.view.forEach
import androidx.preference.MultiSelectListPreferenceDialogFragmentCompat
import io.legado.app.lib.theme.accentColor
import io.legado.app.utils.applyPreferenceDialogSurface
import io.legado.app.utils.applyTint

class MultiSelectListPreferenceDialog : MultiSelectListPreferenceDialogFragmentCompat() {

    companion object {

        fun newInstance(key: String?): MultiSelectListPreferenceDialog {
            val fragment =
                MultiSelectListPreferenceDialog()
            val b = Bundle(1)
            b.putString(ARG_KEY, key)
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
                listView?.forEach {
                    it.applyTint(accentColor)
                }
            }
        }
        return dialog
    }

    override fun onStart() {
        super.onStart()
        dialog?.applyPreferenceDialogSurface()
    }

}
