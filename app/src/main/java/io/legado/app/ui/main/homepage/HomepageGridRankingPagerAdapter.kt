package io.legado.app.ui.main.homepage

import android.content.Context
import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.Space
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import io.legado.app.databinding.ItemHomepageGridRankingPageBinding
import io.legado.app.databinding.ItemHomepageGridRankingRowBinding
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.secondaryTextColor
import io.legado.app.utils.dpToPx

/**
 * 网格排行榜分页适配器：书籍按每页 5 行分页，页为圆角卡片，不足一行用等高占位补齐。
 */
class HomepageGridRankingPagerAdapter(
    context: Context,
    private val onClick: (HomepageBookItemUi) -> Unit,
    private val onLongClick: (HomepageBookItemUi) -> Unit,
) : RecyclerView.Adapter<HomepageGridRankingPagerAdapter.PageViewHolder>() {

    private val inflater = LayoutInflater.from(context)
    private val pages = mutableListOf<List<HomepageBookItemUi>>()

    fun submitPages(newPages: List<List<HomepageBookItemUi>>) {
        if (newPages == pages) return
        pages.clear()
        pages.addAll(newPages)
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = pages.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageViewHolder {
        val binding = ItemHomepageGridRankingPageBinding.inflate(inflater, parent, false)
        return PageViewHolder(binding.root as LinearLayout)
    }

    override fun onBindViewHolder(holder: PageViewHolder, position: Int) {
        val page = pages.getOrNull(position) ?: return
        holder.container.removeAllViews()
        page.forEachIndexed { rowIndex, item ->
            holder.container.addView(
                bindRow(item, position * ROWS_PER_PAGE + rowIndex, holder.container)
            )
        }
        repeat(ROWS_PER_PAGE - page.size) {
            holder.container.addView(
                Space(holder.container.context).apply {
                    layoutParams = LinearLayout.LayoutParams(1, ROW_HEIGHT_DP.dpToPx())
                }
            )
        }
    }

    private fun bindRow(
        item: HomepageBookItemUi,
        rank: Int,
        parent: ViewGroup,
    ): ItemHomepageGridRankingRowBinding {
        val binding = ItemHomepageGridRankingRowBinding.inflate(inflater, parent, false)
        val book = item.book
        binding.tvRank.text = rank.toString()
        if (rank <= 3) {
            binding.tvRank.setTextColor(parent.context.accentColor)
            binding.tvRank.setTypeface(binding.tvRank.typeface, Typeface.BOLD_ITALIC)
        } else {
            binding.tvRank.setTextColor(parent.context.secondaryTextColor)
            binding.tvRank.setTypeface(binding.tvRank.typeface, Typeface.BOLD)
        }
        binding.tvName.text = book.name
        val subTitle = buildString {
            append(book.kind?.split(",")?.firstOrNull() ?: "")
            if (book.author.isNotBlank()) {
                if (isNotEmpty()) append(" · ")
                append(book.author)
            }
        }
        binding.tvSub.text = subTitle
        binding.tvSub.isVisible = subTitle.isNotBlank()
        binding.tvSub.alpha = 0.8f
        HomepageBookBadge.bind(binding.flBadge, binding.ivBadge, item)
        binding.ivCover.load(book, AppConfig.loadCoverOnlyWifi)
        binding.root.setOnClickListener { onClick(item) }
        binding.root.setOnLongClickListener {
            onLongClick(item)
            true
        }
        return binding
    }

    class PageViewHolder(val container: LinearLayout) : RecyclerView.ViewHolder(container)

    companion object {
        const val ROWS_PER_PAGE = 5
        const val ROW_HEIGHT_DP = 72

        /** 页高 = 5 行 + 页内上下 padding 24dp */
        const val PAGE_HEIGHT_DP = ROWS_PER_PAGE * ROW_HEIGHT_DP + 24
    }
}
