package io.legado.app.ui.book.cache

import android.content.Context
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import io.legado.app.R
import io.legado.app.base.adapter.RecyclerAdapter
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.data.entities.CreationResult
import io.legado.app.databinding.ItemCreationGridBinding
import io.legado.app.help.ai.AiCreationImageFile
import io.legado.app.help.glide.ImageLoader
import io.legado.app.lib.theme.accentColor
import io.legado.app.utils.gone
import io.legado.app.utils.visible

class CreationGridAdapter(
    context: Context,
    private val callback: Callback
) : RecyclerAdapter<CreationResult, ItemCreationGridBinding>(context) {

    var selectedIds: Set<Long> = emptySet()
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    override fun getViewBinding(parent: ViewGroup): ItemCreationGridBinding {
        return ItemCreationGridBinding.inflate(inflater, parent, false)
    }

    override fun convert(
        holder: ItemViewHolder,
        binding: ItemCreationGridBinding,
        item: CreationResult,
        payloads: MutableList<Any>
    ) = with(binding) {
        ImageLoader.load(context, AiCreationImageFile.fileOf(item.fileName))
            .centerCrop()
            .into(ivResult)
        val selected = item.resultId in selectedIds
        if (selectedIds.isEmpty()) {
            vSelected.gone()
            tvCheck.gone()
        } else {
            vSelected.visible()
            tvCheck.visible()
            tvCheck.setTextColor(
                if (selected) context.accentColor else android.graphics.Color.WHITE
            )
        }
    }

    override fun registerListener(holder: ItemViewHolder, binding: ItemCreationGridBinding) {
        binding.root.setOnClickListener {
            getItem(holder.layoutPosition)?.let { item ->
                callback.onItemClick(item)
            }
        }
        binding.root.setOnLongClickListener {
            getItem(holder.layoutPosition)?.let { item ->
                callback.onItemLongClick(item)
            }
            true
        }
    }

    interface Callback {
        fun onItemClick(item: CreationResult)

        fun onItemLongClick(item: CreationResult)
    }
}
