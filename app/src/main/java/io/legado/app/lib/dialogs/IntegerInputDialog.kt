package io.legado.app.lib.dialogs

import android.content.DialogInterface
import android.text.InputType
import android.view.inputmethod.EditorInfo
import androidx.annotation.StringRes
import androidx.fragment.app.Fragment
import io.legado.app.R
import io.legado.app.databinding.DialogEditTextBinding
import io.legado.app.utils.requestInputMethod
import io.legado.app.utils.showSoftInput
import io.legado.app.utils.toastOnUi

fun Fragment.showIntegerInputDialog(
    @StringRes title: Int,
    currentValue: Int,
    validRange: IntRange,
    defaultValue: Int? = null,
    onValueConfirmed: (Int) -> Unit
) {
    require(!validRange.isEmpty()) { "Integer input range must not be empty" }
    require(defaultValue == null || defaultValue in validRange) {
        "Default integer input value $defaultValue is outside $validRange"
    }

    val binding = DialogEditTextBinding.inflate(layoutInflater).apply {
        editView.hint = getString(
            R.string.integer_input_range_hint,
            validRange.first,
            validRange.last
        )
        editView.inputType = InputType.TYPE_CLASS_NUMBER
        editView.imeOptions = EditorInfo.IME_ACTION_DONE
        editView.setText(currentValue.toString())
        editView.selectAll()
    }
    val dialog = alert(titleResource = title) {
        customView { binding.root }
        okButton()
        if (defaultValue != null) {
            neutralButton(R.string.btn_default_s)
        }
        cancelButton()
    }

    fun confirm(value: Int) {
        onValueConfirmed(value)
        dialog.dismiss()
    }

    dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
        val value = binding.editView.text?.toString()?.trim()?.toIntOrNull()
        if (value == null || value !in validRange) {
            toastOnUi(
                getString(
                    R.string.integer_input_range_invalid,
                    validRange.first,
                    validRange.last
                )
            )
            return@setOnClickListener
        }
        confirm(value)
    }
    defaultValue?.let { value ->
        dialog.getButton(DialogInterface.BUTTON_NEUTRAL).setOnClickListener {
            confirm(value)
        }
    }
    binding.editView.setOnEditorActionListener { _, actionId, _ ->
        if (actionId == EditorInfo.IME_ACTION_DONE) {
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
            true
        } else {
            false
        }
    }
    dialog.requestInputMethod()
    binding.editView.post { binding.editView.showSoftInput() }
}
