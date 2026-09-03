package io.legado.app.ui.main.homepage

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.MarginPageTransformer
import androidx.viewpager2.widget.ViewPager2
import com.google.android.flexbox.FlexboxLayout
import io.legado.app.R
import io.legado.app.data.entities.SearchBook
import io.legado.app.data.entities.rule.ExploreKind
import io.legado.app.databinding.ItemHomepageModuleBinding
import io.legado.app.domain.model.HomepageModuleType
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.primaryTextColor
import io.legado.app.lib.theme.secondaryTextColor
import io.legado.app.utils.dpToPx
import io.legado.app.utils.gone
import io.legado.app.utils.visible

/**
 * 主页模块列表适配器：一个模块一个 item，内部按模块类型装配内嵌书籍列表、
 * 分类按钮组或排行榜多分类 Tab，并处理加载中/失败/加载更多等状态。
 * 排版对齐 Max 版：排行榜为序号卡片列表，网格排行榜为横向翻页卡片，
 * 按钮组为等宽卡片网格，加载中显示骨架屏。
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

        fun onModuleHeaderClick(module: HomepageModuleUi, exploreUrl: String?)

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
        private var gridRankAdapter: HomepageGridRankingPagerAdapter? = null
        private var boundKey: String? = null
        private var currentModule: HomepageModuleUi? = null
        private var expandedForModule: String? = null
        private var currentPagerKey: String? = null
        private var pagerLoadAction: (() -> Unit)? = null
        private val pagerPositions = mutableMapOf<String, Int>()

        private val pagerCallback = object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                val key = currentPagerKey ?: return
                pagerPositions[key] = position
                val pageCount = gridRankAdapter?.itemCount ?: 0
                if (pageCount > 1 && position >= pageCount - 1) {
                    pagerLoadAction?.invoke()
                }
            }
        }

        init {
            binding.vpGridRanking.registerOnPageChangeCallback(pagerCallback)
        }

        fun bind(module: HomepageModuleUi) {
            if (currentModule?.globalId != module.globalId) {
                expandedForModule = null
            }
            currentModule = module
            binding.tvTitle.text = module.title
            binding.tvRetry.setTextColor(appContext.accentColor)
            if (module.type == HomepageModuleType.ButtonGroup) {
                binding.llTitle.isClickable = false
                binding.llTitle.setOnClickListener(null)
            } else {
                binding.llTitle.setOnClickListener {
                    val target = currentModule ?: return@setOnClickListener
                    callBack.onModuleHeaderClick(target, headerExploreUrl(target))
                }
            }
            // Tab 行默认隐藏，仅由 bindRankingTabs 显示，避免各内容绑定路径误伤
            binding.llTabs.gone()
            when (val state = module.state) {
                is ModuleLoadState.Loading -> bindLoading(module)
                is ModuleLoadState.Error -> bindError(module)
                is ModuleLoadState.Buttons -> bindButtons(module, state)
                is ModuleLoadState.Loaded -> bindLoaded(module, state)
                is ModuleLoadState.RankingTabs -> bindRankingTabs(module, state)
            }
        }

        /** 标题跳转 URL：排行榜多 Tab 模块取当前 Tab 的发现地址 */
        private fun headerExploreUrl(module: HomepageModuleUi): String? {
            val tabs = module.state as? ModuleLoadState.RankingTabs
            return tabs?.tabs?.getOrNull(tabs.selectedIndex)?.exploreUrl ?: module.exploreUrl
        }

        private fun hideAll() {
            binding.llTabs.gone()
            binding.flButtons.gone()
            binding.llRankCard.gone()
            binding.vpGridRanking.gone()
            binding.llExpand.gone()
            binding.flSkeleton.gone()
            binding.llError.gone()
            binding.llLoadMore.gone()
        }

        private fun bindLoading(module: HomepageModuleUi) {
            hideAll()
            binding.flSkeleton.visible()
            buildSkeleton(module.type, binding.flSkeleton)
        }

        private fun bindError(module: HomepageModuleUi) {
            hideAll()
            binding.llError.visible()
            binding.llError.setOnClickListener { callBack.onRetry(module) }
        }

        private fun bindButtons(module: HomepageModuleUi, state: ModuleLoadState.Buttons) {
            hideAll()
            binding.flButtons.visible()
            binding.flButtons.removeAllViews()
            val kinds = state.kinds
            if (kinds.isEmpty()) return
            val maxColumns = 5
            val numRows = (kinds.size + maxColumns - 1) / maxColumns
            val actualColumns = (kinds.size + numRows - 1) / numRows
            kinds.forEach { kind ->
                val cell = TextView(appContext).apply {
                    text = kind.title
                    gravity = Gravity.CENTER
                    textSize = 11f
                    setTextColor(appContext.primaryTextColor)
                    setBackgroundResource(R.drawable.bg_explore_book_grid_card)
                    setPadding(4.dpToPx(), 12.dpToPx(), 4.dpToPx(), 12.dpToPx())
                    setOnClickListener { callBack.onKindClick(module, kind) }
                }
                val lp = FlexboxLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                // 横向等宽：basis 必须先扣除横向 margin，否则 basis+margin 超出行宽被迫换行；
                // 容器宽 = 屏幕宽 - 模块左右 padding（16dp×2），每格 basis = W/N - 左右 margin，
                // 再减 1px 防浮点取整溢出，余量由 flexGrow 均分吃掉，保证同行等宽占满
                val marginH = 4.dpToPx()
                val containerPx =
                    appContext.resources.displayMetrics.widthPixels - 32.dpToPx()
                lp.flexBasisPercent =
                    (containerPx / actualColumns - marginH * 2 - 1).toFloat() / containerPx
                lp.flexGrow = 1f
                lp.setMargins(marginH, 4.dpToPx(), marginH, 4.dpToPx())
                binding.flButtons.addView(cell, lp)
            }
        }

        private fun bindLoaded(module: HomepageModuleUi, state: ModuleLoadState.Loaded) {
            if (module.type == HomepageModuleType.GridRanking) {
                bindGridRanking(
                    books = state.books,
                    hasMore = state.hasMore,
                    isLoadingMore = state.isLoadingMore,
                    tabKey = null,
                    onLoadMore = { callBack.onLoadMore(module) },
                )
                return
            }
            val rankStyle = module.type == HomepageModuleType.Ranking
            showRankCard(rankStyle)
            ensureBooksAdapter(module.type)
            val books = when {
                module.type == HomepageModuleType.Grid -> state.books.take(GRID_MAX_ITEMS)
                rankStyle -> state.books.take(rankLimit(module))
                else -> state.books
            }
            booksAdapter?.setItems(books)
            if (rankStyle) {
                bindExpandFooter(module, state.books)
            } else {
                binding.llExpand.gone()
            }
            bindLoadMore(state.hasMore, state.isLoadingMore) {
                callBack.onLoadMore(module)
            }
        }

        private fun bindRankingTabs(module: HomepageModuleUi, state: ModuleLoadState.RankingTabs) {
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

            if (module.type == HomepageModuleType.GridRanking) {
                bindGridRanking(
                    books = currentTab?.books ?: emptyList(),
                    hasMore = currentTab?.hasMore == true,
                    isLoadingMore = currentTab?.isLoadingMore == true,
                    tabKey = currentTab?.title,
                    onLoadMore = { callBack.onLoadMoreRankingTab(module, state.selectedIndex) },
                )
            } else {
                showRankCard(rankStyle = true)
                ensureBooksAdapter(HomepageModuleType.Ranking)
                val all = currentTab?.books ?: emptyList()
                booksAdapter?.setItems(all.take(rankLimit(module)))
                bindExpandFooter(module, all)
                binding.llLoadMore.gone()
            }

            // Tab 行在内容绑定之后显示：显隐只由本方法管理
            binding.llTabs.visible()
            binding.ivTabArrow.isVisible = currentTab?.exploreUrl != null
            binding.ivTabArrow.setOnClickListener {
                currentTab?.let { tab -> callBack.onModuleHeaderClick(module, tab.exploreUrl) }
            }
        }

        private fun rankLimit(module: HomepageModuleUi): Int {
            return if (expandedForModule == module.globalId) RANK_MAX_COUNT else RANK_INITIAL_COUNT
        }

        /** 排行榜展开/收起 footer：超过 5 条显示，最多展开到 20 条 */
        private fun bindExpandFooter(module: HomepageModuleUi, all: List<HomepageBookItemUi>) {
            if (all.size <= RANK_INITIAL_COUNT) {
                binding.llExpand.gone()
                return
            }
            binding.llExpand.visible()
            val expanded = expandedForModule == module.globalId
            binding.tvExpand.setText(
                if (expanded) R.string.homepage_collapse else R.string.homepage_expand_all
            )
            val color = if (expanded) {
                appContext.secondaryTextColor
            } else {
                appContext.accentColor
            }
            binding.tvExpand.setTextColor(color)
            binding.ivExpand.setColorFilter(color)
            binding.ivExpand.rotation = if (expanded) 180f else 0f
            binding.llExpand.setOnClickListener {
                expandedForModule = if (expanded) null else module.globalId
                val nowExpanded = expandedForModule == module.globalId
                booksAdapter?.setItems(
                    all.take(if (nowExpanded) RANK_MAX_COUNT else RANK_INITIAL_COUNT)
                )
                bindExpandFooter(module, all)
            }
        }

        /** 网格排行榜：横向翻页卡片，右缘露出下一页，翻到最后一页自动加载更多 */
        private fun bindGridRanking(
            books: List<HomepageBookItemUi>,
            hasMore: Boolean,
            isLoadingMore: Boolean,
            tabKey: String?,
            onLoadMore: () -> Unit,
        ) {
            binding.llRankCard.gone()
            binding.flSkeleton.gone()
            binding.llError.gone()
            binding.flButtons.gone()
            binding.llExpand.gone()
            binding.llLoadMore.gone()
            binding.vpGridRanking.visible()

            if (gridRankAdapter == null || binding.vpGridRanking.adapter == null) {
                gridRankAdapter = HomepageGridRankingPagerAdapter(
                    appContext,
                    { item -> callBack.onBookClick(item.book) },
                    { item -> callBack.onBookLongClick(item.book) },
                )
                binding.vpGridRanking.adapter = gridRankAdapter
                binding.vpGridRanking.offscreenPageLimit = 1
                binding.vpGridRanking.layoutParams.height =
                    HomepageGridRankingPagerAdapter.PAGE_HEIGHT_DP.dpToPx()
                binding.vpGridRanking.requestLayout()
                binding.vpGridRanking.setPageTransformer(
                    MarginPageTransformer(PAGE_SPACING_DP.dpToPx())
                )
                (binding.vpGridRanking.getChildAt(0) as? RecyclerView)?.let { rv ->
                    rv.clipToPadding = false
                    rv.setPadding(0, 0, PEEK_DP.dpToPx(), 0)
                }
            }
            currentPagerKey = tabKey ?: KEY_SINGLE
            pagerLoadAction = if (hasMore && !isLoadingMore) onLoadMore else null
            gridRankAdapter?.submitPages(
                books.chunked(HomepageGridRankingPagerAdapter.ROWS_PER_PAGE)
            )
            binding.vpGridRanking.setCurrentItem(pagerPositions[currentPagerKey] ?: 0, false)
        }

        private fun showRankCard(rankStyle: Boolean) {
            binding.flSkeleton.gone()
            binding.llError.gone()
            binding.vpGridRanking.gone()
            binding.flButtons.gone()
            binding.llExpand.gone()
            binding.llRankCard.visible()
            if (rankStyle) {
                binding.llRankCard.setBackgroundResource(R.drawable.bg_homepage_card_16)
                binding.llRankCard.setPadding(0, 12.dpToPx(), 0, 0)
            } else {
                binding.llRankCard.background = null
                binding.llRankCard.setPadding(0, 0, 0, 0)
            }
        }

        private fun bindLoadMore(hasMore: Boolean, isLoadingMore: Boolean, action: () -> Unit) {
            binding.llLoadMore.isVisible = hasMore || isLoadingMore
            binding.pbLoadMore.isVisible = isLoadingMore
            binding.tvLoadMore.setText(
                if (isLoadingMore) R.string.homepage_loading else R.string.homepage_load_more
            )
            binding.ivLoadMore.isVisible = !isLoadingMore
            if (hasMore && !isLoadingMore) {
                binding.llLoadMore.background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 6.dpToPx().toFloat()
                    setColor(ColorUtils.setAlphaComponent(appContext.accentColor, 20))
                    setStroke(1.dpToPx(), ColorUtils.setAlphaComponent(appContext.accentColor, 38))
                }
                binding.tvLoadMore.setTextColor(appContext.accentColor)
                binding.ivLoadMore.setColorFilter(appContext.accentColor)
            } else {
                binding.llLoadMore.background = null
                binding.tvLoadMore.setTextColor(appContext.secondaryTextColor)
                binding.ivLoadMore.setColorFilter(appContext.secondaryTextColor)
            }
            binding.llLoadMore.setOnClickListener { if (!isLoadingMore && hasMore) action() }
        }

        /** 按模块类型生成加载占位骨架屏 */
        private fun buildSkeleton(type: HomepageModuleType, parent: FrameLayout) {
            parent.removeAllViews()
            val ctx = parent.context
            fun box(): View = View(ctx).apply {
                setBackgroundResource(R.drawable.bg_homepage_skeleton)
            }
            fun vlp(w: Int, h: Int): LinearLayout.LayoutParams =
                LinearLayout.LayoutParams(w, h)

            when (type) {
                HomepageModuleType.Banner -> {
                    val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
                    repeat(4) {
                        row.addView(box(), vlp(96.dpToPx(), 128.dpToPx()).apply {
                            marginEnd = 12.dpToPx()
                        })
                    }
                    parent.addView(row)
                }

                HomepageModuleType.Card -> {
                    val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
                    repeat(3) {
                        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
                        col.addView(box(), vlp(120.dpToPx(), 160.dpToPx()))
                        col.addView(
                            box(),
                            vlp(120.dpToPx(), 14.dpToPx()).apply { topMargin = 8.dpToPx() }
                        )
                        row.addView(
                            col,
                            LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.WRAP_CONTENT,
                                ViewGroup.LayoutParams.WRAP_CONTENT
                            ).apply { marginEnd = 12.dpToPx() }
                        )
                    }
                    parent.addView(row)
                }

                HomepageModuleType.Grid, HomepageModuleType.InfiniteGrid -> {
                    val outer = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
                    repeat(2) {
                        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
                        repeat(3) {
                            val cell = LinearLayout(ctx).apply {
                                orientation = LinearLayout.VERTICAL
                            }
                            cell.addView(
                                box(),
                                LinearLayout.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT, 110.dpToPx()
                                )
                            )
                            cell.addView(
                                box(),
                                vlp(
                                    ViewGroup.LayoutParams.MATCH_PARENT, 12.dpToPx()
                                ).apply { topMargin = 4.dpToPx() }
                            )
                            row.addView(
                                cell,
                                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                                    .apply { marginEnd = 12.dpToPx() }
                            )
                        }
                        outer.addView(
                            row,
                            LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.WRAP_CONTENT
                            ).apply { bottomMargin = 12.dpToPx() }
                        )
                    }
                    parent.addView(outer)
                }

                HomepageModuleType.Ranking -> {
                    val card = LinearLayout(ctx).apply {
                        orientation = LinearLayout.VERTICAL
                        setBackgroundResource(R.drawable.bg_homepage_card_16)
                        setPadding(12.dpToPx(), 12.dpToPx(), 12.dpToPx(), 4.dpToPx())
                    }
                    repeat(5) {
                        val row = LinearLayout(ctx).apply {
                            orientation = LinearLayout.HORIZONTAL
                            gravity = Gravity.CENTER_VERTICAL
                        }
                        row.addView(
                            box(),
                            vlp(18.dpToPx(), 18.dpToPx()).apply { marginEnd = 14.dpToPx() }
                        )
                        row.addView(
                            box(),
                            vlp(52.dpToPx(), 69.dpToPx()).apply { marginEnd = 8.dpToPx() }
                        )
                        val texts = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
                        texts.addView(
                            box(),
                            LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT, 14.dpToPx()
                            )
                        )
                        texts.addView(
                            box(),
                            vlp(120.dpToPx(), 10.dpToPx()).apply { topMargin = 4.dpToPx() }
                        )
                        row.addView(
                            texts,
                            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                        )
                        card.addView(
                            row,
                            LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.WRAP_CONTENT
                            ).apply { bottomMargin = 8.dpToPx() }
                        )
                    }
                    parent.addView(card)
                }

                HomepageModuleType.GridRanking -> {
                    val card = LinearLayout(ctx).apply {
                        orientation = LinearLayout.VERTICAL
                        setBackgroundResource(R.drawable.bg_homepage_card_20)
                        setPadding(12.dpToPx(), 12.dpToPx(), 12.dpToPx(), 4.dpToPx())
                    }
                    repeat(4) {
                        val row = LinearLayout(ctx).apply {
                            orientation = LinearLayout.HORIZONTAL
                            gravity = Gravity.CENTER_VERTICAL
                        }
                        row.addView(
                            box(),
                            vlp(48.dpToPx(), 64.dpToPx()).apply { marginEnd = 8.dpToPx() }
                        )
                        row.addView(
                            box(),
                            vlp(18.dpToPx(), 18.dpToPx()).apply { marginEnd = 4.dpToPx() }
                        )
                        val texts = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
                        texts.addView(
                            box(),
                            LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT, 14.dpToPx()
                            )
                        )
                        texts.addView(
                            box(),
                            vlp(140.dpToPx(), 10.dpToPx()).apply { topMargin = 4.dpToPx() }
                        )
                        row.addView(
                            texts,
                            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                        )
                        card.addView(
                            row,
                            LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.WRAP_CONTENT
                            ).apply { bottomMargin = 8.dpToPx() }
                        )
                    }
                    parent.addView(card)
                }

                HomepageModuleType.Waterfall -> {
                    val outer = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
                    repeat(2) {
                        val card = LinearLayout(ctx).apply {
                            orientation = LinearLayout.VERTICAL
                            setBackgroundResource(R.drawable.bg_explore_book_grid_card)
                            setPadding(8.dpToPx(), 8.dpToPx(), 8.dpToPx(), 8.dpToPx())
                        }
                        card.addView(
                            box(),
                            LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT, 180.dpToPx()
                            )
                        )
                        card.addView(
                            box(),
                            vlp(
                                ViewGroup.LayoutParams.MATCH_PARENT, 14.dpToPx()
                            ).apply { topMargin = 8.dpToPx() }
                        )
                        card.addView(
                            box(),
                            vlp(150.dpToPx(), 10.dpToPx()).apply { topMargin = 6.dpToPx() }
                        )
                        outer.addView(
                            card,
                            LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.WRAP_CONTENT
                            ).apply { bottomMargin = 8.dpToPx() }
                        )
                    }
                    parent.addView(outer)
                }

                HomepageModuleType.ButtonGroup, HomepageModuleType.Unknown -> Unit
            }
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
                    androidx.recyclerview.widget.StaggeredGridLayoutManager(
                        WATERFALL_SPAN,
                        androidx.recyclerview.widget.StaggeredGridLayoutManager.VERTICAL
                    )
                HomepageModuleType.Card, HomepageModuleType.Banner ->
                    HomepageBooksAdapter.horizontalLayoutManager(appContext)
                else -> HomepageBooksAdapter.verticalLayoutManager(appContext)
            }
    }

    companion object {
        private const val GRID_SPAN = 3
        private const val WATERFALL_SPAN = 2
        private const val GRID_MAX_ITEMS = 6
        private const val RANK_INITIAL_COUNT = 5
        private const val RANK_MAX_COUNT = 20

        /** 网格排行榜翻页：页间距与右缘露出宽度（dp） */
        private const val PAGE_SPACING_DP = 12
        private const val PEEK_DP = 100
        private const val KEY_SINGLE = "single"
    }
}
