package io.legado.app.ui.main.homepage

import android.content.Context
import android.graphics.Typeface
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewbinding.ViewBinding
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.base.adapter.RecyclerAdapter
import io.legado.app.data.entities.SearchBook
import io.legado.app.databinding.ItemHomepageBookBannerBinding
import io.legado.app.databinding.ItemHomepageBookCardBinding
import io.legado.app.databinding.ItemHomepageBookGridBinding
import io.legado.app.databinding.ItemHomepageBookWaterfallBinding
import io.legado.app.databinding.ItemHomepageRankingRowBinding
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.secondaryTextColor
import io.legado.app.utils.StringUtils

/**
 * 主页模块内嵌书籍列表适配器，按展示模式加载不同行布局，排版对齐 Max 版：
 * 排行榜 = 序号+封面+文字三段行；网格 = 纯封面+双行书名；卡片 = 120dp 圆角卡；
 * 瀑布流 = 圆角卡（封面/书名/作者/简介）；横幅 = 纯封面列。
 */
class HomepageBooksAdapter(
    context: Context,
    private val mode: Int,
    private val callBack: CallBack,
) : RecyclerAdapter<HomepageBookItemUi, ViewBinding>(context) {

    interface CallBack {
        fun onBookClick(book: SearchBook)

        fun onBookLongClick(book: SearchBook)
    }

    override fun getViewBinding(parent: ViewGroup): ViewBinding {
        return when (mode) {
            MODE_BANNER -> ItemHomepageBookBannerBinding.inflate(inflater, parent, false)
            MODE_RANK -> ItemHomepageRankingRowBinding.inflate(inflater, parent, false)
            MODE_CARD -> ItemHomepageBookCardBinding.inflate(inflater, parent, false)
            MODE_WATERFALL -> ItemHomepageBookWaterfallBinding.inflate(inflater, parent, false)
            else -> ItemHomepageBookGridBinding.inflate(inflater, parent, false)
        }
    }

    override fun convert(
        holder: ItemViewHolder,
        binding: ViewBinding,
        item: HomepageBookItemUi,
        payloads: MutableList<Any>
    ) {
        when (binding) {
            is ItemHomepageRankingRowBinding -> bindRank(binding, item, holder.layoutPosition)
            is ItemHomepageBookBannerBinding -> bindBanner(binding, item)
            is ItemHomepageBookCardBinding -> bindCard(binding, item)
            is ItemHomepageBookWaterfallBinding -> bindWaterfall(binding, item)
            is ItemHomepageBookGridBinding -> bindGrid(binding, item)
        }
    }

    private fun bindRank(binding: ItemHomepageRankingRowBinding, item: HomepageBookItemUi, position: Int) {
        binding.run {
            val rank = position + 1
            tvRank.text = rank.toString()
            if (rank <= 3) {
                tvRank.setTextColor(context.accentColor)
                tvRank.setTypeface(tvRank.typeface, Typeface.BOLD_ITALIC)
            } else {
                tvRank.setTextColor(context.secondaryTextColor)
                tvRank.setTypeface(tvRank.typeface, Typeface.BOLD)
            }
            tvName.text = item.book.name
            val stat = buildString {
                val wc = StringUtils.wordCountFormat(item.book.wordCount)
                if (wc.isNotEmpty()) append(wc)
                if (item.book.author.isNotBlank()) {
                    if (isNotEmpty()) append(" · ")
                    append(item.book.author)
                }
            }
            tvStat.text = stat
            tvStat.isVisible = stat.isNotBlank()
            val kind = item.book.kind?.split(",")?.firstOrNull()
            tvKind.text = kind
            tvKind.isVisible = !kind.isNullOrBlank()
            val intro = item.book.intro?.takeIf { it.isNotBlank() }?.replace("\\s+".toRegex(), " ")
            tvIntro.text = intro
            tvIntro.isVisible = !intro.isNullOrBlank()
            HomepageBookBadge.bind(flBadge, ivBadge, item)
            ivCover.load(item.book, AppConfig.loadCoverOnlyWifi)
        }
    }

    private fun bindBanner(binding: ItemHomepageBookBannerBinding, item: HomepageBookItemUi) {
        binding.run {
            HomepageBookBadge.bind(flBadge, ivBadge, item)
            ivCover.load(item.book, AppConfig.loadCoverOnlyWifi)
        }
    }

    private fun bindGrid(binding: ItemHomepageBookGridBinding, item: HomepageBookItemUi) {
        binding.run {
            tvName.text = item.book.name
            HomepageBookBadge.bind(flBadge, ivBadge, item)
            ivCover.load(item.book, AppConfig.loadCoverOnlyWifi)
        }
    }

    private fun bindCard(binding: ItemHomepageBookCardBinding, item: HomepageBookItemUi) {
        binding.run {
            tvName.text = item.book.name
            val intro = item.book.intro?.takeIf { it.isNotBlank() }?.replace("\\s+".toRegex(), " ")
            tvIntro.text = intro
            tvIntro.isVisible = !intro.isNullOrBlank()
            HomepageBookBadge.bind(flBadge, ivBadge, item)
            ivCover.load(item.book, AppConfig.loadCoverOnlyWifi)
        }
    }

    private fun bindWaterfall(binding: ItemHomepageBookWaterfallBinding, item: HomepageBookItemUi) {
        binding.run {
            tvName.text = item.book.name
            tvAuthor.text = item.book.author
            val intro = item.book.intro?.takeIf { it.isNotBlank() }?.replace("\\s+".toRegex(), " ")
            tvIntro.text = intro
            tvIntro.isVisible = !intro.isNullOrBlank()
            HomepageBookBadge.bind(flBadge, ivBadge, item)
            ivCover.load(item.book, AppConfig.loadCoverOnlyWifi)
        }
    }

    override fun registerListener(holder: ItemViewHolder, binding: ViewBinding) {
        holder.itemView.setOnClickListener {
            getItemByLayoutPosition(holder.layoutPosition)?.let { item ->
                callBack.onBookClick(item.book)
            }
        }
        holder.itemView.setOnLongClickListener {
            getItemByLayoutPosition(holder.layoutPosition)?.let { item ->
                callBack.onBookLongClick(item.book)
            }
            true
        }
    }

    companion object {
        const val MODE_GRID = 0
        const val MODE_RANK = 1
        const val MODE_BANNER = 2
        const val MODE_CARD = 3
        const val MODE_WATERFALL = 4

        /** 纵向布局管理器 */
        fun verticalLayoutManager(context: Context): LinearLayoutManager =
            LinearLayoutManager(context)

        /** 横向布局管理器 */
        fun horizontalLayoutManager(context: Context): LinearLayoutManager =
            LinearLayoutManager(context).apply { orientation = LinearLayoutManager.HORIZONTAL }

        /** 网格布局管理器 */
        fun gridLayoutManager(context: Context, spanCount: Int): RecyclerView.LayoutManager =
            GridLayoutManager(context, spanCount)
    }
}
