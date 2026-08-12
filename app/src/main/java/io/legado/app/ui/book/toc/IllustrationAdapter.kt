package io.legado.app.ui.book.toc

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import io.legado.app.R
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookIllustration
import io.legado.app.help.glide.ImageLoader
import io.legado.app.help.illustration.IllustrationHelp
import io.legado.app.help.illustration.imageSrcsFromJson
import io.legado.app.utils.visible

class IllustrationAdapter(
    private val book: Book,
    private val onClick: (BookIllustration) -> Unit,
    private val onLongClick: (BookIllustration) -> Unit,
    private val onSelectionLongClick: (BookIllustration) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val TYPE_LIST = 0
        private const val TYPE_GRID = 1
    }

    var grid = false
        set(value) {
            if (field != value) {
                field = value
                notifyDataSetChanged()
            }
        }

    private val items = arrayListOf<BookIllustration>()
    private val selectedIds = linkedSetOf<Long>()

    /** 多选模式：开启后点击切换选中，关闭时清空选中 */
    var selectionMode = false
        set(value) {
            if (field != value) {
                field = value
                if (!value) {
                    selectedIds.clear()
                }
                notifyDataSetChanged()
            }
        }

    fun selectedItems(): List<BookIllustration> {
        return items.filter { it.id in selectedIds }
    }

    fun isAllSelected(): Boolean {
        return items.isNotEmpty() && selectedIds.size == items.size
    }

    fun toggleSelect(item: BookIllustration) {
        if (!selectedIds.remove(item.id)) {
            selectedIds.add(item.id)
        }
        val position = items.indexOf(item)
        if (position >= 0) {
            notifyItemChanged(position)
        }
    }

    fun toggleSelectAll() {
        if (isAllSelected()) {
            selectedIds.clear()
        } else {
            selectedIds.addAll(items.map { it.id })
        }
        notifyDataSetChanged()
    }

    fun setItems(list: List<BookIllustration>?) {
        items.clear()
        if (list != null) items.addAll(list)
        if (selectionMode) {
            selectedIds.retainAll(items.map { it.id })
        }
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = items.size

    override fun getItemViewType(position: Int): Int {
        return if (grid) TYPE_GRID else TYPE_LIST
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_GRID) {
            GridHolder(
                inflater.inflate(R.layout.item_illustration_grid, parent, false)
            )
        } else {
            ListHolder(
                inflater.inflate(R.layout.item_illustration_list, parent, false)
            )
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = items[position]
        val selected = selectedIds.contains(item.id)
        when (holder) {
            is ListHolder -> {
                val chapterName = "第 ${item.chapterIndex + 1} 章  ${item.chapterName}"
                holder.tvChapter.text = chapterName
                holder.tvChapter.visible(item.chapterName.isNotBlank())
                when (item.anchorType) {
                    BookIllustration.ANCHOR_CHAPTER_END -> {
                        holder.tvFront.text = item.frontParagraphText.ifBlank { "章末配图" }
                        holder.tvBack.visible(false)
                    }
                    else -> {
                        holder.tvFront.text = item.frontParagraphText
                        holder.tvBack.text = item.backParagraphText
                        holder.tvBack.visible(true)
                    }
                }
                loadThumb(holder.ivThumb, item)
                holder.selectionOuter.visible(selectionMode)
                holder.selectionDot.visible(selectionMode && selected)
                holder.itemView.setOnClickListener {
                    if (selectionMode) toggleSelect(item) else onClick(item)
                }
                holder.itemView.setOnLongClickListener {
                    if (selectionMode) onSelectionLongClick(item) else onLongClick(item)
                    true
                }
            }
            is GridHolder -> {
                holder.tvChapter.text = "第 ${item.chapterIndex + 1} 章  ${item.chapterName}"
                loadThumb(holder.ivThumb, item)
                holder.selectionOuter.visible(selectionMode)
                holder.selectionDot.visible(selectionMode && selected)
                holder.itemView.setOnClickListener {
                    if (selectionMode) toggleSelect(item) else onClick(item)
                }
                holder.itemView.setOnLongClickListener {
                    if (selectionMode) onSelectionLongClick(item) else onLongClick(item)
                    true
                }
            }
        }
    }

    private fun loadThumb(imageView: ImageView, item: BookIllustration) {
        val firstSrc = item.imageSrcsFromJson().firstOrNull()
        if (firstSrc == null) {
            imageView.setImageResource(R.drawable.image_loading_error)
            return
        }
        val file = IllustrationHelp.getImageFile(book, firstSrc)
        if (file.exists()) {
            ImageLoader.load(imageView.context, file).into(imageView)
        } else {
            imageView.setImageResource(R.drawable.image_loading_error)
        }
    }

    class ListHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvFront: TextView = view.findViewById(R.id.tv_front)
        val tvBack: TextView = view.findViewById(R.id.tv_back)
        val tvChapter: TextView = view.findViewById(R.id.tv_chapter)
        val ivThumb: ImageView = view.findViewById(R.id.iv_thumb)
        val selectionOuter: FrameLayout = view.findViewById(R.id.selection_outer)
        val selectionDot: ImageView = view.findViewById(R.id.selection_dot)
    }

    class GridHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvChapter: TextView = view.findViewById(R.id.tv_chapter)
        val ivThumb: ImageView = view.findViewById(R.id.iv_thumb)
        val selectionOuter: FrameLayout = view.findViewById(R.id.selection_outer)
        val selectionDot: ImageView = view.findViewById(R.id.selection_dot)
    }
}
