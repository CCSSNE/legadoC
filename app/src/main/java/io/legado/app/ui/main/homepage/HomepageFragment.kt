package io.legado.app.ui.main.homepage

import android.os.Bundle
import android.view.View
import androidx.core.view.isVisible
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import io.legado.app.R
import io.legado.app.base.VMBaseFragment
import io.legado.app.data.appDb
import io.legado.app.data.entities.SearchBook
import io.legado.app.data.entities.rule.ExploreKind
import io.legado.app.databinding.FragmentHomepageBinding
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

            viewLifecycleOwner.lifecycleScope.launch {
                viewModel.uiState.collect { state ->
                    adapter.submitModules(state.modules)
                    llEmpty.isVisible = state.modules.isEmpty() && !state.isRefreshing
                    swipeRefreshLayout.isRefreshing = state.isRefreshing
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

    override fun onCompatCreateOptionsMenu(menu: android.view.Menu) {
        super.onCompatCreateOptionsMenu(menu)
        menuInflater.inflate(R.menu.menu_homepage, menu)
    }

    override fun onCompatOptionsItemSelected(item: android.view.MenuItem) {
        super.onCompatOptionsItemSelected(item)
        when (item.itemId) {
            R.id.menu_manage -> showManageSheet()
            R.id.menu_refresh -> viewModel.onRefresh()
        }
    }

    fun gotoTop() {
        binding.rvModules.smoothScrollToPosition(0)
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

}
