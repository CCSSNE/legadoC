package io.legado.app.ui.book.cache

import android.content.Context
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import io.legado.app.data.entities.BookChapter
import io.legado.app.databinding.ItemTtsCacheChapterBinding
import io.legado.app.databinding.ItemTtsCacheGroupBinding

/**
 * TTS 缓存章节选择适配器：每 20 章合并为一条折叠组，默认折叠；
 * 组条目勾选 = 整组选中；点击组条目展开后可逐章勾选。
 */
class TtsCacheChapterAdapter(
    @Suppress("unused") private val context: Context,
    private val callback: Callback,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    interface Callback {
        fun onSelectionChanged()
    }

    sealed class Item {
        class Group(
            val groupIndex: Int,
            val chapters: List<BookChapter>,
        ) : Item() {
            val startIndex: Int get() = chapters.first().index
            val endIndex: Int get() = chapters.last().index
        }

        class Chapter(
            val chapter: BookChapter,
            val groupIndex: Int,
        ) : Item()
    }

    private val groups = mutableListOf<Item.Group>()
    private var items: List<Item> = emptyList()
    private val expandedGroups = mutableSetOf<Int>()
    private val selected = linkedSetOf<Int>()

    fun setChapters(chapters: List<BookChapter>) {
        groups.clear()
        groups.addAll(
            chapters.filterNot { it.isVolume }
                .chunked(GROUP_SIZE)
                .mapIndexed { index, list -> Item.Group(index, list) }
        )
        rebuild()
    }

    fun toggleExpand(group: Item.Group) {
        if (group.groupIndex in expandedGroups) {
            expandedGroups.remove(group.groupIndex)
        } else {
            expandedGroups.add(group.groupIndex)
        }
        rebuild()
    }

    fun toggleGroupSelection(group: Item.Group) {
        val allSelected = group.chapters.all { it.index in selected }
        if (allSelected) {
            group.chapters.forEach { selected.remove(it.index) }
        } else {
            group.chapters.forEach { selected.add(it.index) }
        }
        notifyDataSetChanged()
        callback.onSelectionChanged()
    }

    fun toggleChapterSelection(chapter: BookChapter) {
        if (chapter.index in selected) {
            selected.remove(chapter.index)
        } else {
            selected.add(chapter.index)
        }
        notifyDataSetChanged()
        callback.onSelectionChanged()
    }

    fun selectAll() {
        val all = groups.flatMap { it.chapters }
        val allSelected = all.isNotEmpty() && all.all { it.index in selected }
        if (allSelected) {
            selected.clear()
        } else {
            selected.addAll(all.map { it.index })
        }
        notifyDataSetChanged()
        callback.onSelectionChanged()
    }

    fun selectedCount(): Int = selected.size

    fun selectedIndexes(): List<Int> = selected.toList().sorted()

    private fun rebuild() {
        val list = mutableListOf<Item>()
        groups.forEach { group ->
            list.add(group)
            if (group.groupIndex in expandedGroups) {
                group.chapters.forEach { list.add(Item.Chapter(it, group.groupIndex)) }
            }
        }
        items = list
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int): Int = when (items[position]) {
        is Item.Group -> TYPE_GROUP
        is Item.Chapter -> TYPE_CHAPTER
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_GROUP -> GroupHolder(ItemTtsCacheGroupBinding.inflate(inflater, parent, false))
            else -> ChapterHolder(ItemTtsCacheChapterBinding.inflate(inflater, parent, false))
        }
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is Item.Group -> (holder as GroupHolder).bind(item)
            is Item.Chapter -> (holder as ChapterHolder).bind(item)
        }
    }

    inner class GroupHolder(
        private val binding: ItemTtsCacheGroupBinding,
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(group: Item.Group) = binding.run {
            tvTitle.text = root.context.getString(
                io.legado.app.R.string.tts_cache_chapter_range,
                group.startIndex + 1,
                group.endIndex + 1,
            )
            tvCount.text = root.context.getString(
                io.legado.app.R.string.tts_cache_group_chapter_count,
                group.chapters.size,
            )
            cbSelect.setOnCheckedChangeListener(null)
            cbSelect.isChecked = group.chapters.all { it.index in selected }
            cbSelect.setOnCheckedChangeListener { _, _ ->
                toggleGroupSelection(group)
            }
            root.setOnClickListener { toggleExpand(group) }
        }
    }

    inner class ChapterHolder(
        private val binding: ItemTtsCacheChapterBinding,
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: Item.Chapter) = binding.run {
            tvTitle.text = "${item.chapter.index + 1}. ${item.chapter.title}"
            cbSelect.setOnCheckedChangeListener(null)
            cbSelect.isChecked = item.chapter.index in selected
            cbSelect.setOnCheckedChangeListener { _, _ ->
                toggleChapterSelection(item.chapter)
            }
            root.setOnClickListener { toggleChapterSelection(item.chapter) }
        }
    }

    companion object {
        const val GROUP_SIZE = 20
        private const val TYPE_GROUP = 0
        private const val TYPE_CHAPTER = 1
    }
}
