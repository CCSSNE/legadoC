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
import java.math.BigDecimal

fun Fragment.showDecimalInputDialog(
    @StringRes title: Int,
    currentValue: Double,
    validRange: ClosedFloatingPointRange<Double>,
    defaultValue: Double? = null,
    validationError: ((Double) -> String?)? = null,
    onValueConfirmed: (Double) -> Unit
) {
    require(validRange.start <= validRange.endInclusive) {
        "Decimal input range must not be empty"
    }
    require(defaultValue == null || defaultValue in validRange) {
        "Default decimal input value $defaultValue is outside $validRange"
    }

    val rangeStart = validRange.start.toPlainString()
    val rangeEnd = validRange.endInclusive.toPlainString()
    val binding = DialogEditTextBinding.inflate(layoutInflater).apply {
        editView.hint = getString(R.string.decimal_input_range_hint, rangeStart, rangeEnd)
        editView.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        editView.imeOptions = EditorInfo.IME_ACTION_DONE
        editView.setText(currentValue.toPlainString())
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

    fun confirm(value: Double) {
        onValueConfirmed(value)
        dialog.dismiss()
    }

    dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
        val value = binding.editView.text?.toString()?.trim()?.toDoubleOrNull()
        if (value == null || !value.isFinite() || value !in validRange) {
            toastOnUi(getString(R.string.decimal_input_range_invalid, rangeStart, rangeEnd))
            return@setOnClickListener
        }
        validationError?.invoke(value)?.let { error ->
            toastOnUi(error)
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

private fun Double.toPlainString(): String =
    BigDecimal.valueOf(this).stripTrailingZeros().toPlainString()
