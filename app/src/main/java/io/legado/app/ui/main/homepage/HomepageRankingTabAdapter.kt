package io.legado.app.ui.main.homepage

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.graphics.ColorUtils
import androidx.recyclerview.widget.RecyclerView
import io.legado.app.databinding.ItemHomepageRankingTabBinding
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.secondaryTextColor
import io.legado.app.utils.dpToPx

/**
 * 排行榜多分类 Tab 适配器：横向滚动分类，排版对齐 Max 版——
 * 选中项为主题色 12% 底、无边框；未选中为透明底、20% 透明度描边。
 */
class HomepageRankingTabAdapter(
    context: Context,
    private val onTabSelected: (index: Int) -> Unit,
) : RecyclerView.Adapter<HomepageRankingTabAdapter.TabViewHolder>() {

    private val inflater = LayoutInflater.from(context)
    private val tabs = mutableListOf<RankingTabData>()
    private var selectedIndex = 0
    private val accentColor = context.accentColor
    private val secondaryTextColor = context.secondaryTextColor

    private val selectedBackground = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = 8.dpToPx().toFloat()
        setColor(ColorUtils.setAlphaComponent(accentColor, 31))
    }

    private val unselectedBackground = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = 8.dpToPx().toFloat()
        setColor(Color.TRANSPARENT)
        setStroke(1.dpToPx(), ColorUtils.setAlphaComponent(secondaryTextColor, 51))
    }

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
        lp.marginEnd = 2.dpToPx()
        binding.root.layoutParams = lp
        return TabViewHolder(binding)
    }

    override fun getItemCount(): Int = tabs.size

    override fun onBindViewHolder(holder: TabViewHolder, position: Int) {
        val tab = tabs[position]
        val selected = position == selectedIndex
        holder.binding.tvTab.text = tab.title
        if (selected) {
            holder.binding.tvTab.setTextColor(accentColor)
            holder.binding.tvTab.setTypeface(holder.binding.tvTab.typeface, Typeface.NORMAL)
            holder.binding.root.background = selectedBackground
        } else {
            holder.binding.tvTab.setTextColor(secondaryTextColor)
            holder.binding.tvTab.setTypeface(holder.binding.tvTab.typeface, Typeface.NORMAL)
            holder.binding.root.background = unselectedBackground
        }
        holder.itemView.setOnClickListener {
            if (selectedIndex != holder.layoutPosition) {
                onTabSelected(holder.layoutPosition)
            }
        }
    }

    class TabViewHolder(val binding: ItemHomepageRankingTabBinding) :
        RecyclerView.ViewHolder(binding.root)
}
