package io.legado.app.ui.main.homepage

import android.content.Context
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.google.android.flexbox.FlexboxLayout
import io.legado.app.R
import io.legado.app.data.entities.SearchBook
import io.legado.app.data.entities.rule.ExploreKind
import io.legado.app.databinding.ItemHomepageModuleBinding
import io.legado.app.domain.model.HomepageModuleType
import io.legado.app.utils.dpToPx
import io.legado.app.utils.gone
import io.legado.app.utils.visible

/**
 * 主页模块列表适配器：一个模块一个 item，内部按模块类型装配内嵌书籍列表、
 * 分类按钮组或排行榜多分类 Tab，并处理加载中/失败/加载更多等状态。
 *
 * VH 缓存内嵌适配器，模块内容变化时只更新数据，不重建视图，保留滚动位置。
 */
class HomepageAdapter(
    context: Context,
    private val callBack: CallBack,
) : RecyclerView.Adapter<HomepageAdapter.ModuleViewHolder>() {

    interface CallBack {
        fun onBookClick(book: SearchBook)

        fun onBookLongClick(book: SearchBook)

        fun onModuleHeaderClick(module: HomepageModuleUi)

        fun onKindClick(module: HomepageModuleUi, kind: ExploreKind)

        fun onRetry(module: HomepageModuleUi)

        fun onLoadMore(module: HomepageModuleUi)

        fun onLoadMoreRankingTab(module: HomepageModuleUi, tabIndex: Int)

        fun onSelectRankingTab(module: HomepageModuleUi, index: Int)
    }

    private val inflater = LayoutInflater.from(context)
    private val appContext = context.applicationContext
    private val modules = mutableListOf<HomepageModuleUi>()

    fun submitModules(list: List<HomepageModuleUi>) {
        if (list == modules) return
        modules.clear()
        modules.addAll(list)
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = modules.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ModuleViewHolder {
        val binding = ItemHomepageModuleBinding.inflate(inflater, parent, false)
        return ModuleViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ModuleViewHolder, position: Int) {
        holder.bind(modules[position])
    }

    inner class ModuleViewHolder(
        private val binding: ItemHomepageModuleBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        private var booksAdapter: HomepageBooksAdapter? = null
        private var tabsAdapter: HomepageRankingTabAdapter? = null
        private var boundKey: String? = null
        private var currentModule: HomepageModuleUi? = null

        fun bind(module: HomepageModuleUi) {
            currentModule = module
            binding.tvTitle.text = module.title
            binding.tvSetName.text = module.setName
            binding.tvSetName.isVisible = module.setName.isNotBlank()
            binding.llTitle.setOnClickListener { callBack.onModuleHeaderClick(module) }

            val state = module.state
            when (state) {
                is ModuleLoadState.Loading -> bindLoading()
                is ModuleLoadState.Error -> bindError(module)
                is ModuleLoadState.Buttons -> bindButtons(module, state)
                is ModuleLoadState.Loaded -> bindLoaded(module, state)
                is ModuleLoadState.RankingTabs -> bindRankingTabs(module, state)
            }
        }

        private fun bindLoading() {
            binding.pbLoading.visible()
            binding.tvError.gone()
            binding.flButtons.gone()
            binding.rvContent.gone()
            binding.rvTabs.gone()
            binding.llLoadMore.gone()
        }

        private fun bindError(module: HomepageModuleUi) {
            binding.pbLoading.gone()
            binding.flButtons.gone()
            binding.rvContent.gone()
            binding.rvTabs.gone()
            binding.llLoadMore.gone()
            binding.tvError.visible()
            binding.tvError.setOnClickListener { callBack.onRetry(module) }
        }

        private fun bindButtons(module: HomepageModuleUi, state: ModuleLoadState.Buttons) {
            binding.pbLoading.gone()
            binding.tvError.gone()
            binding.rvContent.gone()
            binding.rvTabs.gone()
            binding.llLoadMore.gone()
            binding.flButtons.visible()
            binding.flButtons.removeAllViews()
            state.kinds.forEach { kind ->
                binding.flButtons.addView(createChip(kind.title) {
                    callBack.onKindClick(module, kind)
                })
            }
        }

        private fun bindLoaded(module: HomepageModuleUi, state: ModuleLoadState.Loaded) {
            binding.pbLoading.gone()
            binding.tvError.gone()
            binding.flButtons.gone()
            binding.rvTabs.gone()
            binding.rvContent.visible()
            ensureBooksAdapter(module.type)

            val books = when (module.type) {
                HomepageModuleType.Grid -> state.books.take(GRID_MAX_ITEMS)
                else -> state.books
            }
            booksAdapter?.setItems(books)

            bindLoadMore(state.hasMore, state.isLoadingMore) {
                callBack.onLoadMore(module)
            }
        }

        private fun bindRankingTabs(module: HomepageModuleUi, state: ModuleLoadState.RankingTabs) {
            binding.pbLoading.gone()
            binding.tvError.gone()
            binding.flButtons.gone()
            binding.rvContent.visible()
            binding.rvTabs.visible()
            ensureBooksAdapter(HomepageModuleType.Ranking)

            if (tabsAdapter == null || binding.rvTabs.adapter == null) {
                tabsAdapter = HomepageRankingTabAdapter(appContext) { index ->
                    currentModule?.let { callBack.onSelectRankingTab(it, index) }
                }
                binding.rvTabs.adapter = tabsAdapter
                binding.rvTabs.layoutManager =
                    HomepageBooksAdapter.horizontalLayoutManager(appContext)
            }
            tabsAdapter?.submitItems(state.tabs, state.selectedIndex)

            val currentTab = state.tabs.getOrNull(state.selectedIndex)
            val books = currentTab?.books ?: emptyList()
            booksAdapter?.setItems(books)

            bindLoadMore(currentTab?.hasMore == true, currentTab?.isLoadingMore == true) {
                callBack.onLoadMoreRankingTab(module, state.selectedIndex)
            }
        }

        private fun bindLoadMore(hasMore: Boolean, isLoadingMore: Boolean, action: () -> Unit) {
            binding.llLoadMore.isVisible = hasMore || isLoadingMore
            binding.pbLoadMore.isVisible = isLoadingMore
            binding.tvLoadMore.isVisible = !isLoadingMore
            binding.llLoadMore.setOnClickListener { if (!isLoadingMore && hasMore) action() }
        }

        private fun ensureBooksAdapter(type: HomepageModuleType) {
            val key = moduleKey(type)
            if (booksAdapter != null && binding.rvContent.adapter != null && boundKey == key) {
                return
            }
            boundKey = key
            booksAdapter = HomepageBooksAdapter(appContext, modeOf(type), object :
                HomepageBooksAdapter.CallBack {
                override fun onBookClick(book: SearchBook) {
                    callBack.onBookClick(book)
                }

                override fun onBookLongClick(book: SearchBook) {
                    callBack.onBookLongClick(book)
                }
            }).also { adapter ->
                binding.rvContent.adapter = adapter
                binding.rvContent.layoutManager = layoutManagerOf(type)
            }
        }

        private fun moduleKey(type: HomepageModuleType): String = when (type) {
            HomepageModuleType.Grid -> "grid"
            HomepageModuleType.InfiniteGrid -> "grid"
            HomepageModuleType.Waterfall -> "waterfall"
            HomepageModuleType.Card -> "card"
            HomepageModuleType.Banner -> "banner"
            else -> "rank"
        }

        private fun modeOf(type: HomepageModuleType): Int = when (type) {
            HomepageModuleType.Grid, HomepageModuleType.InfiniteGrid -> HomepageBooksAdapter.MODE_GRID
            HomepageModuleType.Waterfall -> HomepageBooksAdapter.MODE_WATERFALL
            HomepageModuleType.Card -> HomepageBooksAdapter.MODE_CARD
            HomepageModuleType.Banner -> HomepageBooksAdapter.MODE_BANNER
            else -> HomepageBooksAdapter.MODE_RANK
        }

        private fun layoutManagerOf(type: HomepageModuleType): RecyclerView.LayoutManager =
            when (type) {
                HomepageModuleType.Grid, HomepageModuleType.InfiniteGrid ->
                    HomepageBooksAdapter.gridLayoutManager(appContext, GRID_SPAN)
                HomepageModuleType.Waterfall ->
                    HomepageBooksAdapter.gridLayoutManager(appContext, WATERFALL_SPAN)
                HomepageModuleType.Card, HomepageModuleType.Banner ->
                    HomepageBooksAdapter.horizontalLayoutManager(appContext)
                else -> HomepageBooksAdapter.verticalLayoutManager(appContext)
            }

        private fun createChip(
            text: String,
            onClick: () -> Unit
        ): TextView {
            val chip = TextView(appContext)
            chip.text = text
            chip.textSize = 13f
            chip.setTextColor(appContext.resources.getColor(R.color.primaryText))
            chip.setBackgroundResource(R.drawable.bg_homepage_chip)
            chip.setPadding(10.dpToPx(), 6.dpToPx(), 10.dpToPx(), 6.dpToPx())
            chip.setOnClickListener { onClick() }
            val lp = FlexboxLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.setMargins(0, 0, 10.dpToPx(), 10.dpToPx())
            chip.layoutParams = lp
            return chip
        }
    }

    companion object {
        private const val GRID_SPAN = 3
        private const val WATERFALL_SPAN = 2
        private const val GRID_MAX_ITEMS = 6
    }
}
