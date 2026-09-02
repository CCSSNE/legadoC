package io.legado.app.ui.main.homepage

import android.content.Context
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import io.legado.app.R
import io.legado.app.databinding.ItemHomepageRankingTabBinding
import io.legado.app.lib.theme.primaryTextColor
import io.legado.app.lib.theme.secondaryTextColor
import io.legado.app.utils.dpToPx

/**
 * 排行榜多分类 Tab 适配器：横向滚动分类，选中项高亮。
 */
class HomepageRankingTabAdapter(
    context: Context,
    private val onTabSelected: (index: Int) -> Unit,
) : RecyclerView.Adapter<HomepageRankingTabAdapter.TabViewHolder>() {

    private val inflater = LayoutInflater.from(context)
    private val tabs = mutableListOf<RankingTabData>()
    private var selectedIndex = 0
    private val primaryTextColor = context.primaryTextColor
    private val secondaryTextColor = context.secondaryTextColor

    fun submitItems(items: List<RankingTabData>, selected: Int) {
        val changed = tabs != items || selectedIndex != selected
        tabs.clear()
        tabs.addAll(items)
        selectedIndex = selected
        if (changed) notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TabViewHolder {
        val binding = ItemHomepageRankingTabBinding.inflate(inflater, parent, false)
        val lp = binding.root.layoutParams as RecyclerView.LayoutParams
        lp.marginEnd = 6.dpToPx()
        binding.root.layoutParams = lp
        return TabViewHolder(binding)
    }

    override fun getItemCount(): Int = tabs.size

    override fun onBindViewHolder(holder: TabViewHolder, position: Int) {
        val tab = tabs[position]
        val selected = position == selectedIndex
        holder.binding.tvTab.text = tab.title
        holder.binding.tvTab.setTextColor(if (selected) primaryTextColor else secondaryTextColor)
        holder.binding.root.setBackgroundResource(
            if (selected) R.drawable.bg_explore_book_grid_card else R.drawable.bg_homepage_ranking_tab
        )
        holder.itemView.setOnClickListener {
            if (selectedIndex != holder.layoutPosition) {
                onTabSelected(holder.layoutPosition)
            }
        }
    }

    class TabViewHolder(val binding: ItemHomepageRankingTabBinding) :
        RecyclerView.ViewHolder(binding.root)
}
