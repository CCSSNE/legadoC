package io.legado.app.ui.main.homepage

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import io.legado.app.R
import io.legado.app.base.VMBaseFragment
import io.legado.app.data.appDb
import io.legado.app.data.entities.SearchBook
import io.legado.app.data.entities.rule.ExploreKind
import io.legado.app.databinding.FragmentHomepageBinding
import io.legado.app.databinding.ItemHomepageSourcePageBinding
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.dialogs.selector
import io.legado.app.lib.theme.accentColor
import io.legado.app.ui.book.SearchBookOpenHelper
import io.legado.app.ui.book.explore.ExploreShowActivity
import io.legado.app.ui.main.MainFragmentInterface
import io.legado.app.ui.rss.article.RssSortActivity
import io.legado.app.utils.applyMainBottomBarPadding
import io.legado.app.utils.dpToPx
import io.legado.app.utils.setEdgeEffectColor
import io.legado.app.utils.startActivity
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.launch

/**
 * 聚合主页 Fragment：以模块为单位聚合展示多个书源/订阅源的发现内容。
 * 数据由 [HomepageViewModel] 提供，界面遵循本项目主题语义（UiCorner/ThemeStore UI 组）。
 * 支持两种布局：混合列表（所有模块单列表）与分源Tab（按集分页，Tab 切换）。
 */
class HomepageFragment() : VMBaseFragment<HomepageViewModel>(R.layout.fragment_homepage),
    MainFragmentInterface,
    HomepageAdapter.CallBack {

    constructor(position: Int) : this() {
        val bundle = Bundle()
        bundle.putInt("position", position)
        arguments = bundle
    }

    override val position: Int? get() = arguments?.getInt("position")

    public override val viewModel by viewModels<HomepageViewModel>()

    private val binding by viewBinding(FragmentHomepageBinding::bind)

    private val adapter by lazy { HomepageAdapter(requireContext(), this) }

    /** 分源Tab 模式：页与 Tab 数据 */
    private val sourcePagerAdapter = SourcePagerAdapter()
    private var tabSets = listOf<HomepageSourceManageUi>()
    private var currentTabIndex = 0
    private var tabMediator: TabLayoutMediator? = null

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        binding.run {
            setSupportToolbar(titleBar.toolbar)
            swipeRefreshLayout.setColorSchemeColors(accentColor)
            swipeRefreshLayout.setProgressViewOffset(true, (-28).dpToPx(), 56.dpToPx())
            swipeRefreshLayout.setOnChildScrollUpCallback { _, _ ->
                rvModules.canScrollVertically(-1)
            }
            swipeRefreshLayout.setOnRefreshListener {
                viewModel.onRefresh()
            }
            rvModules.layoutManager = LinearLayoutManager(requireContext())
            rvModules.setEdgeEffectColor(accentColor)
            rvModules.applyMainBottomBarPadding()
            rvModules.adapter = adapter

            viewPagerSource.adapter = sourcePagerAdapter
            viewPagerSource.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
                override fun onPageSelected(position: Int) {
                    currentTabIndex = position
                    notifyCurrentTab()
                }
            })

            viewLifecycleOwner.lifecycleScope.launch {
                viewModel.uiState.collect { state ->
                    upLayoutMode(state.layoutMode == 1)
                    if (state.layoutMode == 1) {
                        upSourceTabs(state)
                    } else {
                        adapter.submitModules(state.modules)
                        llEmpty.isVisible = state.modules.isEmpty() && !state.isRefreshing
                        swipeRefreshLayout.isRefreshing = state.isRefreshing
                    }
                }
            }
            viewLifecycleOwner.lifecycleScope.launch {
                viewModel.effects.collect { effect ->
                    when (effect) {
                        is HomepageEffect.ShowSnackbar ->
                            requireContext().toastOnUi(effect.message)
                    }
                }
            }
        }
    }

    /** 切换布局模式：0 = 混合列表，1 = 分源Tab */
    private fun upLayoutMode(tabMode: Boolean) {
        if (binding.llSourceTabRoot.isVisible == tabMode) return
        binding.llSourceTabRoot.isVisible = tabMode
        binding.swipeRefreshLayout.isVisible = !tabMode
        supportToolbar?.menu?.findItem(R.id.menu_preload)?.let { item ->
            item.isVisible = tabMode
            item.isChecked = viewModel.uiState.value.preloadMode == 1
        }
    }

    /**
     * 刷新分源Tab 数据：页列表、Tab 行与空态。
     * 仅显示已选中且含模块的集；页按 setUrl 对齐复用，保持各页滚动位置与内嵌适配器。
     */
    private fun upSourceTabs(state: HomepageUiState) {
        val sets = state.manageState.sets.filter { it.isSelected && it.moduleCount > 0 }
        val urlsChanged = sets.map { it.sourceUrl } != tabSets.map { it.sourceUrl }
        sourcePagerAdapter.submitSets(sets)
        sourcePagerAdapter.submitModules(state.modules)
        sourcePagerAdapter.setRefreshing(state.isRefreshing)
        tabSets = sets
        if (currentTabIndex >= sets.size) currentTabIndex = 0
        binding.llEmpty.isVisible = sets.isEmpty() && !state.isRefreshing
        binding.tabSource.isVisible = sets.isNotEmpty()
        binding.viewPagerSource.isVisible = sets.isNotEmpty()
        if (urlsChanged) {
            upTabMediator()
        } else {
            sets.forEachIndexed { index, set ->
                binding.tabSource.getTabAt(index)?.text = set.sourceName
            }
        }
        notifyCurrentTab()
    }

    private fun upTabMediator() {
        binding.tabSource.removeAllTabs()
        tabMediator?.detach()
        if (tabSets.isEmpty()) return
        binding.viewPagerSource.post {
            if (isAdded && currentTabIndex < tabSets.size) {
                binding.viewPagerSource.setCurrentItem(currentTabIndex, false)
            }
        }
        tabMediator = TabLayoutMediator(
            binding.tabSource, binding.viewPagerSource
        ) { tab, position ->
            tab.text = tabSets.getOrNull(position)?.sourceName
        }.also { it.attach() }
    }

    /** 页面选中后把当前 Tab 索引与集列表回传 ViewModel，驱动按需加载 */
    private fun notifyCurrentTab() {
        if (binding.llSourceTabRoot.isVisible) {
            viewModel.updateCurrentTab(currentTabIndex, tabSets.map { it.sourceUrl })
        }
    }

    override fun onCompatCreateOptionsMenu(menu: android.view.Menu) {
        super.onCompatCreateOptionsMenu(menu)
        menuInflater.inflate(R.menu.menu_homepage, menu)
    }

    override fun onCompatOptionsItemSelected(item: android.view.MenuItem) {
        super.onCompatOptionsItemSelected(item)
        when (item.itemId) {
            R.id.menu_manage -> showManageSheet()
            R.id.menu_refresh -> viewModel.onRefresh()
            R.id.menu_switch_layout -> showSwitchLayoutMenu()
            R.id.menu_preload -> {
                val newMode = if (AppConfig.homepagePreload == 1) 0 else 1
                viewModel.setPreloadMode(newMode)
                item.isChecked = newMode == 1
            }
        }
    }

    private fun showSwitchLayoutMenu() {
        val current = AppConfig.homepageLayoutMode
        val options = listOf(
            getString(R.string.homepage_layout_mixed),
            getString(R.string.homepage_layout_source_tab)
        ).mapIndexed { index, label ->
            if (index == current) "✓ $label" else label
        }
        requireContext().selector(
            getString(R.string.homepage_switch_layout), options
        ) { _, index ->
            if (index != current) {
                viewModel.setLayoutMode(index)
            }
        }
    }

    fun gotoTop() {
        if (binding.llSourceTabRoot.isVisible) {
            sourcePagerAdapter.getPage(currentTabIndex)?.scrollToTop()
        } else {
            binding.rvModules.smoothScrollToPosition(0)
        }
    }

    private fun showManageSheet() {
        HomepageModuleManageSheet().show(childFragmentManager, "homepageManage")
    }

    override fun onBookClick(book: SearchBook) {
        viewModel.onBookClick(book)
        SearchBookOpenHelper.open(requireContext(), book, false)
    }

    override fun onBookLongClick(book: SearchBook) {
        requireContext().selector(
            book.name,
            listOf(getString(R.string.homepage_add_to_shelf), getString(R.string.homepage_view_info))
        ) { _, index ->
            when (index) {
                0 -> viewModel.onAddToShelf(book)
                1 -> onBookClick(book)
            }
        }
    }

    override fun onModuleHeaderClick(module: HomepageModuleUi) {
        navigateToExplore(module.sourceUrl, module.exploreUrl, module.title)
    }

    override fun onKindClick(module: HomepageModuleUi, kind: ExploreKind) {
        navigateToExplore(module.sourceUrl, kind.url, kind.title)
    }

    override fun onRetry(module: HomepageModuleUi) {
        viewModel.retryModule(module.globalId)
    }

    override fun onLoadMore(module: HomepageModuleUi) {
        viewModel.loadMoreModule(module.globalId)
    }

    override fun onLoadMoreRankingTab(module: HomepageModuleUi, tabIndex: Int) {
        viewModel.loadMoreRankingTab(module.globalId, tabIndex)
    }

    override fun onSelectRankingTab(module: HomepageModuleUi, index: Int) {
        viewModel.selectRankingTab(module.globalId, index)
    }

    private fun navigateToExplore(sourceUrl: String, exploreUrl: String?, title: String?) {
        val context = requireContext()
        if (appDb.rssSourceDao.has(sourceUrl)) {
            RssSortActivity.start(context, exploreUrl, sourceUrl, key = title)
            return
        }
        if (exploreUrl.isNullOrBlank()) return
        startActivity<ExploreShowActivity> {
            putExtra("exploreName", title ?: "")
            putExtra("sourceUrl", sourceUrl)
            putExtra("exploreUrl", exploreUrl)
        }
    }

    /** 集内模块过滤：集 URL 与模块 customSetId 的对应——源集 URL 即 ID，自定义集需去掉 custom:// 前缀 */
    private fun modulesOfSet(
        setUrl: String,
        modules: List<HomepageModuleUi>
    ): List<HomepageModuleUi> {
        val setId = if (HomepageViewModel.isCustomSetUrl(setUrl)) {
            HomepageViewModel.customSetIdFromUrl(setUrl)
        } else {
            setUrl
        }
        return modules.filter { it.customSetId == setId }
    }

    /** 分源Tab 模式的页适配器：每页一个集的模块列表，页按集 URL 对齐复用 */
    private inner class SourcePagerAdapter :
        androidx.recyclerview.widget.RecyclerView.Adapter<SourcePageHolder>() {

        private val pages = mutableListOf<SourcePage>()
        var lastModules = listOf<HomepageModuleUi>()
            private set

        fun submitSets(newSets: List<HomepageSourceManageUi>) {
            val oldUrls = pages.map { it.setUrl }
            val newUrls = newSets.map { it.sourceUrl }
            if (oldUrls == newUrls) {
                pages.forEach { page ->
                    page.setUi = newSets.find { it.sourceUrl == page.setUrl } ?: page.setUi
                }
                return
            }
            val retained = pages.associateBy { it.setUrl }
            pages.clear()
            newSets.forEach { set ->
                pages.add(retained[set.sourceUrl] ?: SourcePage(set))
            }
            notifyDataSetChanged()
        }

        fun submitModules(modules: List<HomepageModuleUi>) {
            lastModules = modules
            pages.forEach { page ->
                page.adapter.submitModules(modulesOfSet(page.setUrl, modules))
            }
        }

        fun setRefreshing(refreshing: Boolean) {
            pages.forEach { page ->
                page.attachedBinding?.root?.isRefreshing = refreshing
            }
        }

        fun getPage(index: Int): SourcePage? = pages.getOrNull(index)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SourcePageHolder {
            val pageBinding = ItemHomepageSourcePageBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
            return SourcePageHolder(pageBinding)
        }

        override fun onBindViewHolder(holder: SourcePageHolder, position: Int) {
            pages.getOrNull(position)?.attach(holder.binding, this@HomepageFragment)
        }

        override fun onViewRecycled(holder: SourcePageHolder) {
            pages.forEach { page ->
                if (page.attachedBinding === holder.binding) page.detach()
            }
        }

        override fun getItemCount(): Int = pages.size
    }

    /** 分源Tab 的单页：所属集、内嵌模块适配器与当前持有的视图绑定 */
    private inner class SourcePage(var setUi: HomepageSourceManageUi?) {
        val setUrl: String get() = setUi?.sourceUrl ?: ""
        val adapter by lazy { HomepageAdapter(requireContext(), this@HomepageFragment) }
        var attachedBinding: ItemHomepageSourcePageBinding? = null
            private set

        fun attach(
            binding: ItemHomepageSourcePageBinding,
            fragment: HomepageFragment
        ) {
            attachedBinding = binding
            binding.rvPage.layoutManager = LinearLayoutManager(requireContext())
            binding.rvPage.setEdgeEffectColor(accentColor)
            binding.rvPage.applyMainBottomBarPadding()
            binding.rvPage.adapter = adapter
            binding.root.setColorSchemeColors(accentColor)
            binding.root.setProgressViewOffset(true, (-28).dpToPx(), 56.dpToPx())
            binding.root.setOnChildScrollUpCallback { _, _ ->
                binding.rvPage.canScrollVertically(-1)
            }
            binding.root.setOnRefreshListener {
                fragment.viewModel.onRefresh()
            }
            binding.root.isRefreshing = fragment.viewModel.uiState.value.isRefreshing
        }

        fun detach() {
            attachedBinding = null
        }

        fun scrollToTop() {
            attachedBinding?.rvPage?.smoothScrollToPosition(0)
        }
    }

    class SourcePageHolder(val binding: ItemHomepageSourcePageBinding) :
        androidx.recyclerview.widget.RecyclerView.ViewHolder(binding.root)

}
