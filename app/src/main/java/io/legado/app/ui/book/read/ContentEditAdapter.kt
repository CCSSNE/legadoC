package io.legado.app.ui.book.read

import android.text.Editable
import android.text.TextWatcher
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import io.legado.app.lib.theme.primaryTextColor
import io.legado.app.lib.theme.view.ThemeEditText
import io.legado.app.utils.dpToPx

/**
 * 大文本分块编辑适配器，每块一个 EditText，只渲染可见块
 */
class ContentEditAdapter : RecyclerView.Adapter<ContentEditAdapter.ChunkHolder>() {

    private var chunks: MutableList<String> = mutableListOf()

    fun submit(list: List<String>) {
        chunks = list.toMutableList()
        notifyDataSetChanged()
    }

    fun getText(): String = chunks.joinToString("")

    override fun getItemCount(): Int = chunks.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChunkHolder {
        val editText = ThemeEditText(parent.context).apply {
            layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            isSingleLine = false
            background = null
            setTextColor(parent.context.primaryTextColor)
            textSize = 16f
            setPadding(8.dpToPx(), 4.dpToPx(), 8.dpToPx(), 4.dpToPx())
        }
        return ChunkHolder(editText)
    }

    override fun onBindViewHolder(holder: ChunkHolder, position: Int) {
        holder.bind(position)
    }

    inner class ChunkHolder(val editText: ThemeEditText) : RecyclerView.ViewHolder(editText) {

        private var binding = false
        private val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (binding) return
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION && pos < chunks.size) {
                    chunks[pos] = s?.toString() ?: ""
                }
            }
        }

        init {
            editText.addTextChangedListener(watcher)
        }

        fun bind(position: Int) {
            binding = true
            val text = chunks[position]
            if (editText.text?.toString() != text) {
                editText.setText(text)
            }
            binding = false
        }
    }
}
