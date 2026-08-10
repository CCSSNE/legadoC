package io.legado.app.ui.widget.dialog

import android.app.Dialog
import android.content.Context
import androidx.core.view.isGone
import androidx.core.view.isVisible
import io.legado.app.databinding.DialogWaitBinding


@Suppress("unused")
class WaitDialog(context: Context) : Dialog(context) {

    val binding = DialogWaitBinding.inflate(layoutInflater)

    init {
        setCanceledOnTouchOutside(false)
        setContentView(binding.root)
    }

    fun setText(text: String): WaitDialog {
        binding.tvMsg.text = text
        return this
    }

    fun setText(res: Int): WaitDialog {
        binding.tvMsg.setText(res)
        return this
    }

    fun setCancelButton(onClick: (() -> Unit)?): WaitDialog {
        if (onClick != null) {
            setCancelable(false)
            setCanceledOnTouchOutside(false)
        }
        binding.btnCancel.isVisible = onClick != null
        binding.btnCancel.setOnClickListener {
            onClick?.invoke()
        }
        return this
    }

    fun hideCancelButton(): WaitDialog {
        binding.btnCancel.isGone = true
        binding.btnCancel.setOnClickListener(null)
        return this
    }

}
