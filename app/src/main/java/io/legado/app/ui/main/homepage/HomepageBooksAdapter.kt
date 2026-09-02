package io.legado.app.ui.main.homepage

import android.content.Context
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewbinding.ViewBinding
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.base.adapter.RecyclerAdapter
import io.legado.app.data.entities.SearchBook
import io.legado.app.databinding.ItemExploreBookGridBinding
import io.legado.app.databinding.ItemHomepageBookBannerBinding
import io.legado.app.databinding.ItemSearchBinding
import io.legado.app.domain.model.BookShelfState
import io.legado.app.help.config.AppConfig
import io.legado.app.utils.dpToPx
import io.legado.app.utils.gone
import io.legado.app.utils.visible

/**
 * 主页模块内嵌书籍列表适配器，按展示模式加载不同行布局：
 * 网格/瀑布流复用发现网格卡片，排行榜复用搜索列表行，横幅与卡片为横向行。
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
        val binding = when (mode) {
            MODE_BANNER -> ItemHomepageBookBannerBinding.inflate(inflater, parent, false)
            MODE_RANK -> ItemSearchBinding.inflate(inflater, parent, false)
            else -> ItemExploreBookGridBinding.inflate(inflater, parent, false)
        }
        if (mode == MODE_CARD) {
            binding.root.layoutParams = RecyclerView.LayoutParams(CARD_WIDTH_DP.dpToPx(), ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        return binding
    }

    override fun convert(
        holder: ItemViewHolder,
        binding: ViewBinding,
        item: HomepageBookItemUi,
        payloads: MutableList<Any>
    ) {
        when (binding) {
            is ItemSearchBinding -> bindRank(binding, item)
            is ItemHomepageBookBannerBinding -> bindBanner(binding, item)
            is ItemExploreBookGridBinding -> bindGrid(binding, item)
        }
    }

    private fun bindShelfDot(view: View, item: HomepageBookItemUi) {
        view.isVisible = item.shelfState != BookShelfState.NOT_IN_SHELF
    }

    private fun bindRank(binding: ItemSearchBinding, item: HomepageBookItemUi) {
        binding.run {
            tvName.text = item.book.name
            tvAuthor.text = item.book.author
            tvIntroduce.text = item.book.intro
            llKind.gone()
            bvOriginCount.gone()
            upLasted(binding, item.book.latestChapterTitle)
            bindShelfDot(ivInBookshelf, item)
            ivCover.load(item.book, AppConfig.loadCoverOnlyWifi)
        }
    }

    private fun upLasted(binding: ItemSearchBinding, latestChapterTitle: String?) {
        binding.run {
            if (latestChapterTitle.isNullOrEmpty()) {
                tvLasted.gone()
            } else {
                tvLasted.text = latestChapterTitle
                tvLasted.visible()
            }
        }
    }

    private fun bindBanner(binding: ItemHomepageBookBannerBinding, item: HomepageBookItemUi) {
        binding.run {
            tvName.text = item.book.name
            bindShelfDot(ivInBookshelf, item)
            ivCover.load(item.book, AppConfig.loadCoverOnlyWifi)
        }
    }

    private fun bindGrid(binding: ItemExploreBookGridBinding, item: HomepageBookItemUi) {
        binding.run {
            tvName.text = item.book.name
            tvAuthor.text = item.book.author
            tvLasted.gone()
            llKind.gone()
            tvIntroduce.gone()
            bindShelfDot(ivInBookshelf, item)
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

        private const val CARD_WIDTH_DP = 120

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
