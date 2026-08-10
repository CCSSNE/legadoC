package io.legado.app.ui.widget

import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.TextView
import io.legado.app.R
import io.legado.app.data.entities.rule.RowUi
import io.legado.app.databinding.ItemFilletTextBinding
import io.legado.app.databinding.ItemSelectorSingleBinding
import io.legado.app.lib.theme.applyUiBodyTypeface
import io.legado.app.utils.dpToPx
import io.legado.app.utils.setSelectionSafely

object RowUiViewFactory {

    fun applyModernRowUiStyle(rowUi: RowUi, view: View) {
        rowUi.applyModernStyle(view)
        view.minimumHeight = 44.dpToPx()
        if (view.paddingLeft == 0 && view.paddingRight == 0) {
            view.setPadding(12.dpToPx(), 4.dpToPx(), 12.dpToPx(), 4.dpToPx())
        }
    }

    fun applyModernTextButtonStyle(rowUi: RowUi, textView: TextView) {
        rowUi.applyModernStyle(textView)
        textView.minHeight = 44.dpToPx()
        textView.maxLines = 2
        textView.ellipsize = android.text.TextUtils.TruncateAt.END
        textView.includeFontPadding = false
        textView.setPadding(14.dpToPx(), 0, 14.dpToPx(), 0)
    }

    fun selectView(
        inflater: LayoutInflater,
        parent: ViewGroup,
        rowUi: RowUi,
        chars: List<String>,
        selectedValue: String?,
        onSelected: (String) -> Unit
    ): ItemSelectorSingleBinding {
        val binding = ItemSelectorSingleBinding.inflate(inflater, parent, false)
        applyModernRowUiStyle(rowUi, binding.root)
        bindSelectTitle(rowUi, binding)
        val adapter = object : ArrayAdapter<String>(
            parent.context,
            R.layout.item_text_common,
            chars
        ) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                return super.getView(position, convertView, parent).apply {
                    applyUiBodyTypeface(context)
                }
            }

            override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
                return super.getDropDownView(position, convertView, parent).apply {
                    applyUiBodyTypeface(context)
                }
            }
        }
        adapter.setDropDownViewResource(R.layout.item_spinner_dropdown)
        val selector = binding.spType
        selector.adapter = adapter
        selector.setSelectionSafely(chars.indexOf(selectedValue))
        selector.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            var isInitializing = true
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (isInitializing) {
                    isInitializing = false
                    return
                }
                onSelected(chars[position])
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        return binding
    }

    fun bindSelectTitle(rowUi: RowUi, binding: ItemSelectorSingleBinding) {
        val displayName = rowUi.name.takeIf { it.isNotBlank() }
        binding.spName.text = displayName?.let { "$it:" }.orEmpty()
        binding.spName.visibility = if (displayName.isNullOrBlank()) {
            View.GONE
        } else {
            View.VISIBLE
        }
    }

    fun buttonView(
        inflater: LayoutInflater,
        parent: ViewGroup,
        rowUi: RowUi,
        text: CharSequence,
        onClick: (View) -> Unit
    ): ItemFilletTextBinding {
        val binding = ItemFilletTextBinding.inflate(inflater, parent, false)
        applyModernTextButtonStyle(rowUi, binding.textView)
        binding.textView.text = text
        binding.root.setOnClickListener(onClick)
        return binding
    }

    fun applyJustify(rowUi: RowUi, view: View, textView: TextView? = view as? TextView) {
        rowUi.style().apply {
            when (layout_justifySelf) {
                "flex_start" -> textView?.gravity = Gravity.CENTER_VERTICAL or Gravity.START
                "flex_end" -> textView?.gravity = Gravity.CENTER_VERTICAL or Gravity.END
            }
            apply(view)
        }
    }
}
