package io.legado.app.ui.widget.seekbar

import android.content.Context
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import io.legado.app.R
import io.legado.app.lib.dialogs.setUiTitle
import io.legado.app.utils.applyTint

class SeekBarDialog(private val context: Context) {
    private val builder = AlertDialog.Builder(context)
    private var maxValue = 100
    private var minValue = 0
    private var value = 0

    init {
        builder.setView(R.layout.dialog_seek_bar)
    }

    fun setTitle(title: String): SeekBarDialog {
        builder.setUiTitle(context, title)
        return this
    }

    fun setMaxValue(value: Int): SeekBarDialog {
        maxValue = value
        return this
    }

    fun setMinValue(value: Int): SeekBarDialog {
        minValue = value
        return this
    }

    fun setValue(value: Int): SeekBarDialog {
        this.value = value
        return this
    }

    fun setCustomButton(textId: Int, listener: (() -> Unit)?): SeekBarDialog {
        builder.setNeutralButton(textId) { _, _ -> listener?.invoke() }
        return this
    }

    fun show(callback: ((value: Int) -> Unit)?) {
        val lower = minValue.coerceAtMost(maxValue)
        val upper = maxValue.coerceAtLeast(minValue)
        val initialValue = value.coerceIn(lower, upper)
        var selectedValue = initialValue
        builder.setPositiveButton(R.string.ok) { _, _ -> callback?.invoke(selectedValue) }
        builder.setNegativeButton(R.string.cancel, null)
        val dialog = builder.show().applyTint()
        val seekBar = dialog.findViewById<SeekBar>(R.id.seek_bar) ?: return
        val valueView = dialog.findViewById<TextView>(R.id.seek_value)
        val range = upper - lower
        seekBar.max = range
        seekBar.progress = initialValue - lower

        fun updateValue(progress: Int) {
            selectedValue = (progress + lower).coerceIn(lower, upper)
            valueView?.text = "${selectedValue}%"
        }

        updateValue(seekBar.progress)
        seekBar.setOnSeekBarChangeListener(object : SeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                updateValue(progress)
            }
        })
    }
}
