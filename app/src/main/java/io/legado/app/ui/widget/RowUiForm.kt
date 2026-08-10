package io.legado.app.ui.widget

import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.widget.TextView
import com.google.android.flexbox.FlexboxLayout
import io.legado.app.data.entities.rule.RowUi
import io.legado.app.databinding.ItemRowUiTextBinding
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.applyUiBodyTypeface
import io.legado.app.utils.dpToPx

object RowUiForm {

    interface Callback {
        fun onValueChanged(rowUi: RowUi, value: String) = Unit
        fun onAction(rowUi: RowUi, isLongClick: Boolean) = Unit
        fun resolveViewName(rowUi: RowUi, fallback: String, apply: (String) -> Unit) {
            apply(fallback)
        }
    }

    fun render(
        container: FlexboxLayout,
        rows: List<RowUi>,
        values: Map<String, String> = emptyMap(),
        callback: Callback,
        idOffset: Int = 1000
    ) {
        val inflater = LayoutInflater.from(container.context)
        container.clipChildren = false
        container.clipToPadding = false
        container.removeAllViews()
        rows.forEachIndexed { index, rowUi ->
            val row = createRow(inflater, container, rowUi, values, callback)
            row.view.applyUiBodyTypeface(container.context)
            container.addView(row.view, createRowLayoutParams(rowUi))
            row.view.id = index + idOffset
            row.applyStyleAfterAdd(row.view)
        }
    }

    private fun createRow(
        inflater: LayoutInflater,
        container: FlexboxLayout,
        rowUi: RowUi,
        values: Map<String, String>,
        callback: Callback
    ): FormRow {
        return when (rowUi.type) {
            RowUi.Type.text, RowUi.Type.password -> createTextRow(
                inflater,
                container,
                rowUi,
                values[rowUi.name] ?: rowUi.default.orEmpty(),
                callback
            )
            RowUi.Type.select -> createSelectRow(inflater, container, rowUi, values, callback)
            RowUi.Type.toggle -> createToggleRow(inflater, container, rowUi, values, callback)
            else -> createButtonRow(inflater, container, rowUi, callback)
        }
    }

    private fun createTextRow(
        inflater: LayoutInflater,
        container: FlexboxLayout,
        rowUi: RowUi,
        value: String,
        callback: Callback
    ): FormRow {
        val binding = ItemRowUiTextBinding.inflate(inflater, container, false)
        bindTextInput(rowUi, binding, callback)
        binding.vUnderline.setBackgroundColor(container.context.accentColor)
        val editText = binding.editText
        if (rowUi.type == RowUi.Type.password) {
            editText.inputType =
                InputType.TYPE_TEXT_VARIATION_PASSWORD or InputType.TYPE_CLASS_TEXT
        }
        editText.setText(value)
        editText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(
                s: CharSequence?,
                start: Int,
                count: Int,
                after: Int
            ) = Unit

            override fun onTextChanged(
                s: CharSequence?,
                start: Int,
                before: Int,
                count: Int
            ) = Unit

            override fun afterTextChanged(s: Editable?) {
                updateTextInputLabel(binding)
                callback.onValueChanged(rowUi, s?.toString().orEmpty())
            }
        })
        editText.setOnFocusChangeListener { _, _ ->
            updateTextInputLabel(binding)
        }
        updateTextInputLabel(binding)
        return FormRow(binding.root) {
            rowUi.style().apply {
                when (layout_justifySelf) {
                    "center" -> editText.gravity = Gravity.CENTER
                    "flex_end" -> editText.gravity = Gravity.END
                }
                apply(binding.root)
            }
        }
    }

    private fun createSelectRow(
        inflater: LayoutInflater,
        container: FlexboxLayout,
        rowUi: RowUi,
        values: Map<String, String>,
        callback: Callback
    ): FormRow {
        val chars = rowUi.chars?.filterNotNull()?.filter { it.isNotBlank() }
            ?: emptyList()
        val options = chars.ifEmpty { listOf(rowUi.default.orEmpty()) }
        val binding = RowUiViewFactory.selectView(
            inflater,
            container,
            rowUi,
            options,
            values[rowUi.name] ?: rowUi.default
        ) { value ->
            callback.onValueChanged(rowUi, value)
        }
        return FormRow(binding.root) {
            rowUi.style().apply {
                when (layout_justifySelf) {
                    "flex_start" -> binding.spType.gravity = Gravity.START
                    "flex_end" -> binding.spType.gravity = Gravity.END
                }
                apply(binding.root)
            }
        }
    }

    private fun createButtonRow(
        inflater: LayoutInflater,
        container: FlexboxLayout,
        rowUi: RowUi,
        callback: Callback
    ): FormRow {
        val binding = RowUiViewFactory.buttonView(
            inflater,
            container,
            rowUi,
            rowUi.viewName ?: rowUi.name
        ) {
            callback.onAction(rowUi, false)
        }
        callback.resolveViewName(rowUi, rowUi.name) { name ->
            rowUi.viewName = name
            binding.textView.text = name
        }
        bindActionTouch(binding.root, rowUi, callback)
        return FormRow(binding.root) {
            RowUiViewFactory.applyJustify(rowUi, binding.root, binding.textView)
        }
    }

    private fun createToggleRow(
        inflater: LayoutInflater,
        container: FlexboxLayout,
        rowUi: RowUi,
        values: Map<String, String>,
        callback: Callback
    ): FormRow {
        val chars = rowUi.chars?.filterNotNull()?.filter { it.isNotBlank() }
            ?: emptyList()
        var value = values[rowUi.name]
            ?: rowUi.default
            ?: chars.firstOrNull()
            ?: ""
        var label = rowUi.viewName ?: rowUi.name
        var left = rowUi.style?.layout_justifySelf != "right"
        val binding = RowUiViewFactory.buttonView(
            inflater,
            container,
            rowUi,
            formatToggleText(left, value, label)
        ) {
            value = nextValue(chars, value)
            callback.onValueChanged(rowUi, value)
            bindingText(it)?.text = formatToggleText(left, value, label)
            callback.onAction(rowUi, false)
        }
        callback.resolveViewName(rowUi, rowUi.name) { name ->
            rowUi.viewName = name
            label = name
            binding.textView.text = formatToggleText(left, value, label)
        }
        binding.textView.text = formatToggleText(left, value, label)
        bindActionTouch(binding.root, rowUi, callback) {
            value = nextValue(chars, value)
            callback.onValueChanged(rowUi, value)
            binding.textView.text = formatToggleText(left, value, label)
        }
        return FormRow(binding.root) {
            rowUi.style().apply {
                when (layout_justifySelf) {
                    "flex_start" -> binding.textView.gravity = Gravity.START
                    "flex_end" -> binding.textView.gravity = Gravity.END
                    "right" -> left = false
                }
                apply(binding.root)
            }
            binding.textView.text = formatToggleText(left, value, label)
        }
    }

    private fun bindTextInput(
        rowUi: RowUi,
        binding: ItemRowUiTextBinding,
        callback: Callback
    ) {
        callback.resolveViewName(rowUi, rowUi.name) { name ->
            binding.tvLabel.text = name
            binding.editText.hint = name
            val accentColor = binding.root.context.accentColor
            binding.tvLabel.setTextColor(accentColor)
            binding.editText.setHintTextColor(accentColor)
        }
    }

    private fun updateTextInputLabel(binding: ItemRowUiTextBinding) {
        val shouldFloat = binding.editText.hasFocus()
            || !binding.editText.text.isNullOrEmpty()
        binding.tvLabel.visibility = if (shouldFloat) View.VISIBLE else View.GONE
        binding.editText.hint = if (shouldFloat) null else binding.tvLabel.text
    }

    private fun bindActionTouch(
        view: View,
        rowUi: RowUi,
        callback: Callback,
        beforeAction: (() -> Unit)? = null
    ) {
        var downTime = 0L
        var lastClickTime = 0L
        view.setOnTouchListener { touchedView, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    touchedView.isSelected = true
                    downTime = System.currentTimeMillis()
                }
                MotionEvent.ACTION_UP -> {
                    touchedView.isSelected = false
                    val upTime = System.currentTimeMillis()
                    if (upTime - lastClickTime < 200) {
                        return@setOnTouchListener true
                    }
                    lastClickTime = upTime
                    beforeAction?.invoke()
                    callback.onAction(rowUi, upTime > downTime + 666)
                }
                MotionEvent.ACTION_CANCEL -> {
                    touchedView.isSelected = false
                }
            }
            true
        }
    }

    private fun createRowLayoutParams(rowUi: RowUi): FlexboxLayout.LayoutParams {
        val style = rowUi.style()
        val width = when {
            style.layout_flexBasisPercent >= 0f -> 0
            style.layout_flexGrow > 0f -> FlexboxLayout.LayoutParams.WRAP_CONTENT
            else -> FlexboxLayout.LayoutParams.MATCH_PARENT
        }
        return FlexboxLayout.LayoutParams(
            width,
            FlexboxLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            setMargins(4.dpToPx(), 6.dpToPx(), 4.dpToPx(), 6.dpToPx())
        }
    }

    private fun bindingText(view: View): TextView? {
        return view as? TextView
    }

    private fun nextValue(chars: List<String>, value: String): String {
        if (chars.isEmpty()) return ""
        val index = chars.indexOf(value)
        return chars.getOrNull((index + 1).floorMod(chars.size)).orEmpty()
    }

    private fun formatToggleText(left: Boolean, value: String, label: String): String {
        return if (left) value + label else label + value
    }

    private fun Int.floorMod(mod: Int): Int {
        if (mod <= 0) return 0
        val result = this % mod
        return if (result < 0) result + mod else result
    }

    private data class FormRow(
        val view: View,
        val applyStyleAfterAdd: (View) -> Unit
    )
}
