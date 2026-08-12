package io.legado.app.ui.book.toc

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import io.legado.app.R
import io.legado.app.data.entities.Bookmark
import io.legado.app.data.entities.BookmarkStyle
import io.legado.app.databinding.ItemBookmarkBinding
import io.legado.app.databinding.ItemBookmarkGridBinding
import io.legado.app.lib.theme.accentColor
import io.legado.app.utils.gone
import io.legado.app.utils.visible
import splitties.init.appCtx
import splitties.views.onLongClick

/**
 * 书签列表适配器：支持列表/网格二列/网格三列切换，以及多选/全选
 */
class BookmarkAdapter(private val context: Context, val callback: Callback) :
    RecyclerView.Adapter<RecyclerView.ViewHolder>() {

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

    private val items = arrayListOf<Bookmark>()
    private val selectedTimes = linkedSetOf<Long>()

    /**
     * 多选模式：开启后单击切换选中，关闭时清空选中
     */
    var selectionMode = false
        set(value) {
            if (field != value) {
                field = value
                if (!value) {
                    selectedTimes.clear()
                }
                notifyDataSetChanged()
            }
        }

    fun setItems(list: List<Bookmark>?) {
        items.clear()
        if (list != null) items.addAll(list)
        if (selectionMode) {
            selectedTimes.retainAll(items.map { it.time })
        }
        notifyDataSetChanged()
    }

    fun getItems(): List<Bookmark> = items

    fun selectedItems(): List<Bookmark> = items.filter { it.time in selectedTimes }

    fun isAllSelected(): Boolean = items.isNotEmpty() && selectedTimes.size == items.size

    fun toggleSelect(item: Bookmark) {
        if (!selectedTimes.remove(item.time)) {
            selectedTimes.add(item.time)
        }
        val position = items.indexOf(item)
        if (position >= 0) {
            notifyItemChanged(position)
        }
    }

    fun toggleSelectAll() {
        if (isAllSelected()) {
            selectedTimes.clear()
        } else {
            selectedTimes.addAll(items.map { it.time })
        }
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = items.size

    override fun getItemViewType(position: Int): Int = if (grid) TYPE_GRID else TYPE_LIST

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_GRID) {
            GridHolder(ItemBookmarkGridBinding.inflate(inflater, parent, false))
        } else {
            ListHolder(ItemBookmarkBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = items[position]
        val selected = selectedTimes.contains(item.time)
        when (holder) {
            is ListHolder -> {
                val binding = holder.binding
                binding.tvChapterName.text = item.chapterName
                binding.tvBookText.gone(item.bookText.isEmpty())
                binding.tvBookText.text = item.bookText
                binding.tvContent.gone(item.content.isEmpty())
                binding.tvContent.text = item.content
                upSelectionUi(binding.selectionOuter, binding.selectionDot, selectionMode, selected)
                binding.root.setOnClickListener {
                    if (selectionMode) toggleSelect(item) else callback.onClick(item)
                }
                binding.root.onLongClick {
                    onItemLongClick(item)
                    true
                }
            }

            is GridHolder -> {
                val binding = holder.binding
                binding.tvChapterName.text = item.chapterName
                binding.tvBookText.text = item.bookText
                binding.tvContent.visible(item.content.isNotEmpty())
                binding.tvContent.text = item.content
                binding.vStyleColor.background.setTint(styleColor(item))
                binding.tvStyle.text = styleText(item)
                upSelectionUi(binding.selectionOuter, binding.selectionDot, selectionMode, selected)
                binding.root.setOnClickListener {
                    if (selectionMode) toggleSelect(item) else callback.onClick(item)
                }
                binding.root.onLongClick {
                    onItemLongClick(item)
                    true
                }
            }
        }
    }

    /**
     * 多选模式下仅长按已选中的条目弹出批量操作菜单；
     * 长按未选中的条目不弹菜单，需先单击选中。
     */
    private fun onItemLongClick(item: Bookmark) {
        if (selectionMode) {
            if (selectedTimes.contains(item.time)) {
                callback.onSelectionLongClick(item)
            }
        } else {
            callback.onLongClick(item, items.indexOf(item))
        }
    }

    private fun upSelectionUi(outer: View, dot: View, selectionMode: Boolean, selected: Boolean) {
        outer.visible(selectionMode)
        dot.visible(selectionMode && selected)
    }

    private fun styleColor(item: Bookmark): Int {
        return if (item.color != 0) item.color else appCtx.accentColor
    }

    private fun styleText(item: Bookmark): String {
        if (item.style == BookmarkStyle.NONE) return ""
        val names = arrayListOf<String>()
        if (item.style and BookmarkStyle.SINGLE_UNDERLINE != 0) {
            names.add(context.getString(R.string.bookmark_style_single))
        }
        if (item.style and BookmarkStyle.DOUBLE_UNDERLINE != 0) {
            names.add(context.getString(R.string.bookmark_style_double))
        }
        if (item.style and BookmarkStyle.WAVE_UNDERLINE != 0) {
            names.add(context.getString(R.string.bookmark_style_wave))
        }
        if (item.style and BookmarkStyle.HIGHLIGHT != 0) {
            names.add(context.getString(R.string.bookmark_style_highlight))
        }
        if (item.style and BookmarkStyle.TEXT_COLOR != 0) {
            names.add(context.getString(R.string.bookmark_style_text_color))
        }
        if (item.style and BookmarkStyle.STRIKETHROUGH != 0) {
            names.add(context.getString(R.string.bookmark_style_strikethrough))
        }
        return names.joinToString("、")
    }

    class ListHolder(val binding: ItemBookmarkBinding) : RecyclerView.ViewHolder(binding.root)

    class GridHolder(val binding: ItemBookmarkGridBinding) : RecyclerView.ViewHolder(binding.root)

    interface Callback {
        fun onClick(bookmark: Bookmark)
        fun onLongClick(bookmark: Bookmark, pos: Int)
        fun onSelectionLongClick(bookmark: Bookmark)
    }
}
