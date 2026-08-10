package io.legado.app.ui.main.rss

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.SubMenu
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import androidx.appcompat.widget.PopupMenu
import androidx.appcompat.widget.SearchView
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.fragment.app.commit
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import io.legado.app.R
import io.legado.app.base.VMBaseFragment
import io.legado.app.constant.AppLog
import io.legado.app.data.AppDatabase
import io.legado.app.data.appDb
import io.legado.app.data.entities.RssSource
import io.legado.app.databinding.FragmentRssBinding
import io.legado.app.help.config.AppConfig
import io.legado.app.help.source.sortUrls
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.applyUiBodyTypeface
import io.legado.app.lib.theme.applyUiTitleTypeface
import io.legado.app.lib.theme.primaryColor
import io.legado.app.lib.theme.primaryTextColor
import io.legado.app.ui.login.SourceLoginActivity
import io.legado.app.ui.main.MainFragmentInterface
import io.legado.app.ui.rss.article.ReadRecordDialog
import io.legado.app.ui.rss.article.RssArticlesFragment
import io.legado.app.ui.rss.article.RssSortActivity
import io.legado.app.ui.rss.article.RssSortViewModel
import io.legado.app.ui.rss.favorites.RssFavoritesActivity
import io.legado.app.ui.rss.read.ReadRssActivity
import io.legado.app.ui.rss.source.edit.RssSourceEditActivity
import io.legado.app.ui.rss.source.manage.RssSourceActivity
import io.legado.app.ui.widget.dialog.VariableDialog
import io.legado.app.ui.widget.ExpandableTagSelector
import io.legado.app.ui.widget.RoundedTagBarView
import io.legado.app.utils.applyMainBottomBarPadding
import io.legado.app.utils.applyStatusBarPadding
import io.legado.app.utils.applyTint
import io.legado.app.utils.dpToPx
import io.legado.app.utils.flowWithLifecycleAndDatabaseChange
import io.legado.app.utils.gone
import io.legado.app.utils.openUrl
import io.legado.app.utils.navigationBarHeight
import io.legado.app.utils.sendToClip
import io.legado.app.utils.setEdgeEffectColor
import io.legado.app.utils.setOnApplyWindowInsetsListenerCompat
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.startActivity
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.transaction
import io.legado.app.utils.visible
import io.legado.app.utils.viewbindingdelegate.viewBinding
import io.legado.app.ui.widget.SourceSelectDialog
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 订阅界面
 */
class RssFragment() : VMBaseFragment<RssViewModel>(R.layout.fragment_rss), MainFragmentInterface,
    RssAdapter.CallBack, VariableDialog.Callback {

    constructor(position: Int) : this() {
        val bundle = Bundle()
        bundle.putInt("position", position)
        arguments = bundle
    }

    override val position: Int? get() = arguments?.getInt("position")

    private val binding by viewBinding(FragmentRssBinding::bind)
    override val viewModel by viewModels<RssViewModel>()
    private val sortHostViewModel by viewModels<RssSortViewModel>()
    private val adapter by lazy {
        RssAdapter(requireContext(), this, this, viewLifecycleOwner.lifecycle)
    }
    private val searchView: SearchView by lazy {
        binding.titleBar.findViewById(R.id.search_view)
    }

    private var groupsFlowJob: Job? = null
    private var rssFlowJob: Job? = null
    private val groups = linkedSetOf<String>()
    private var groupsMenu: SubMenu? = null
    private var rssWebView: WebView? = null
    private var selectedRssSource: RssSource? = null
    private val rssSources = mutableListOf<RssSource>()
    private val currentSorts = mutableListOf<Pair<String, String>>()
    private var rssTagBar: RoundedTagBarView? = null
    private var selectedTagIndex = 0
    private var currentSearchKey: String? = null
    private var usingModernRss = false
    private var webSourceVersion = 0L
    private var lastRenderedWebSourceUrl: String? = null

    private companion object {
        const val MENU_RSS_FAVORITES = 2
        const val MENU_RSS_LOGIN = 3
        const val MENU_RSS_SWITCH_LAYOUT = 4
        const val MENU_RSS_REFRESH = 5
        const val MENU_RSS_OPEN_BROWSER = 6
        const val MENU_RSS_COPY_URL = 7
        const val MENU_RSS_GROUP = 8
        const val MENU_RSS_SOURCE_READ_RECORD = 9
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        setSupportToolbar(binding.titleBar.toolbar)
        binding.titleBar.applyStatusBarPadding(withInitialPadding = true)
        applyWebContainerBottomPadding()
        initSearchView()
        initGroupData()
        applyRssMode()
    }

    override fun onResume() {
        super.onResume()
        if (usingModernRss != AppConfig.modernRssPage) {
            applyRssMode()
        }
    }

    override fun onCompatCreateOptionsMenu(menu: Menu) {
        menuInflater.inflate(R.menu.main_rss, menu)
        groupsMenu = menu.findItem(R.id.menu_group)?.subMenu
        menu.findItem(R.id.menu_rss_star)?.isVisible = !usingModernRss
        menu.findItem(R.id.menu_rss_config)?.isVisible = !usingModernRss
        upGroupsMenu()
    }

    override fun onCompatOptionsItemSelected(item: MenuItem) {
        super.onCompatOptionsItemSelected(item)
        when (item.itemId) {
            R.id.menu_read_record -> showDialogFragment<ReadRecordDialog>()
            R.id.menu_rss_config -> startActivity<RssSourceActivity>()
            R.id.menu_rss_star -> startActivity<RssFavoritesActivity>()
            else -> if (!usingModernRss && item.groupId == R.id.menu_group_text) {
                searchView.setQuery("group:${item.title}", true)
            }
        }
    }

    override fun onPause() {
        super.onPause()
        searchView.clearFocus()
    }

    override fun onDestroyView() {
        rssWebView?.let { webView ->
            binding.rssWebContainer.removeView(webView)
            webView.stopLoading()
            webView.loadUrl("about:blank")
            webView.destroy()
        }
        rssWebView = null
        super.onDestroyView()
    }

    private fun applyRssMode() {
        usingModernRss = AppConfig.modernRssPage
        binding.titleBar.isGone = usingModernRss
        binding.llRssSourceRow.isVisible = usingModernRss
        binding.llRssTagsContainer.isVisible = false
        binding.rssFragmentContainer.isGone = true
        binding.rssWebContainer.isGone = true
        binding.recyclerView.isGone = usingModernRss
        binding.pbRssLoading.gone()
        binding.tvEmptyMsg.gone()
        binding.btnOpenRss.gone()
        if (usingModernRss) {
            initModernRssView()
            observeRssSources()
        } else {
            initClassicRecycler()
            observeClassicRssSources()
        }
        activity?.invalidateOptionsMenu()
    }

    private fun upGroupsMenu() = groupsMenu?.transaction { subMenu ->
        subMenu.removeGroup(R.id.menu_group_text)
        groups.forEach {
            subMenu.add(R.id.menu_group_text, Menu.NONE, Menu.NONE, it)
        }
    }

    private fun initSearchView() {
        searchView.applyTint(primaryTextColor)
        searchView.isSubmitButtonEnabled = true
        searchView.queryHint = getString(R.string.rss)
        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean = false

            override fun onQueryTextChange(newText: String?): Boolean {
                if (usingModernRss) {
                    observeRssSources(newText)
                } else {
                    observeClassicRssSources(newText)
                }
                return false
            }
        })
    }

    private fun initClassicRecycler() {
        binding.recyclerView.setEdgeEffectColor(primaryColor)
        binding.recyclerView.clipToPadding = false
        binding.recyclerView.applyMainBottomBarPadding(withInitialPadding = true)
        if (binding.recyclerView.adapter !== adapter) {
            binding.recyclerView.adapter = adapter
        }
        binding.swipeRefreshLayout.setColorSchemeColors(accentColor)
        binding.swipeRefreshLayout.setProgressViewOffset(true, (-28).dpToPx(), 56.dpToPx())
        binding.swipeRefreshLayout.setOnRefreshListener {
            observeClassicRssSources(searchView.query?.toString())
        }
        binding.swipeRefreshLayout.setOnChildScrollUpCallback { _, _ ->
            currentRssScrollTarget()?.canScrollVertically(-1) == true
        }
    }

    private fun initModernRssView() {
        binding.tvRssSourceSelect.applyUiTitleTypeface(requireContext())
        binding.llRssSourceRow.applyStatusBarPadding(withInitialPadding = true)
        binding.swipeRefreshLayout.setColorSchemeColors(accentColor)
        binding.swipeRefreshLayout.setProgressViewOffset(true, (-28).dpToPx(), 56.dpToPx())
        binding.swipeRefreshLayout.setOnChildScrollUpCallback { _, _ ->
            currentRssScrollTarget()?.canScrollVertically(-1) == true
        }
        val updateSourceNameWidth = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            binding.llRssSourceRow.post(::updateRssSourceNameWidth)
        }
        binding.llRssSourceRow.addOnLayoutChangeListener(updateSourceNameWidth)
        binding.llRssSourceRow.post(::updateRssSourceNameWidth)
        binding.swipeRefreshLayout.setOnRefreshListener {
            refreshCurrentRssContent()
        }
        binding.llRssSourceSelect.setOnClickListener {
            showSourceSelector()
        }
        binding.btnRssSourceSearch.setOnClickListener {
            openRssSearch()
        }
        binding.btnRssMore.setOnClickListener {
            showRssMoreMenu()
        }
        binding.btnOpenRss.applyUiBodyTypeface(requireContext())
    }

    private fun updateRssSourceNameWidth() {
        val rowWidth = binding.llRssSourceRow.width
        if (rowWidth <= 0) return
        val actionsWidth = listOf(
            binding.btnRssSourceSearch,
            binding.btnRssMore
        ).filter { it.isVisible }.sumOf {
            it.measuredWidth.takeIf { width -> width > 0 } ?: it.layoutParams.width
        }
        val spacing = 36.dpToPx()
        val maxWidth = (rowWidth - actionsWidth - spacing).coerceIn(96.dpToPx(), 190.dpToPx())
        if (binding.tvRssSourceSelect.maxWidth != maxWidth) {
            binding.tvRssSourceSelect.maxWidth = maxWidth
        }
    }

    private fun showRssMoreMenu() {
        val source = selectedRssSource
        val webVisible = binding.rssWebContainer.isVisible
        PopupMenu(requireContext(), binding.btnRssMore).apply {
            menu.add(Menu.NONE, R.id.menu_read_record, Menu.NONE, R.string.history)
                .setIcon(R.drawable.ic_history)
            // menu.add(Menu.NONE, MENU_RSS_FAVORITES, Menu.NONE, R.string.favorite)
            //     .setIcon(R.drawable.ic_star)
            // menu.addSubMenu(Menu.NONE, MENU_RSS_GROUP, Menu.NONE, R.string.group)
            //     .apply {
            //         item.setIcon(R.drawable.ic_groups)
            //         groups.forEach {
            //             add(R.id.menu_group_text, Menu.NONE, Menu.NONE, it)
            //         }
            //     }
            // menu.add(Menu.NONE, R.id.menu_rss_config, Menu.NONE, R.string.setting)
            //     .setIcon(R.drawable.ic_settings)
            if (source != null) {
                // menu.add(Menu.NONE, R.id.menu_edit, Menu.NONE, R.string.edit)
                // menu.add(Menu.NONE, R.id.menu_top, Menu.NONE, R.string.to_top)
                if (source.loginUrl?.isNotBlank() == true) {
                    menu.add(Menu.NONE, MENU_RSS_LOGIN, Menu.NONE, R.string.login)
                        .setIcon(R.drawable.ic_bottom_person)
                }
                // menu.add(Menu.NONE, R.id.menu_disable, Menu.NONE, R.string.disable_source)
                // menu.add(Menu.NONE, R.id.menu_del, Menu.NONE, R.string.delete)
                menu.add(Menu.NONE, R.id.menu_refresh_sort, Menu.NONE, R.string.refresh_sort)
                menu.add(Menu.NONE, R.id.menu_set_source_variable, Menu.NONE, R.string.set_source_variable)
                menu.add(Menu.NONE, MENU_RSS_SOURCE_READ_RECORD, Menu.NONE, R.string.read_record)
                menu.add(Menu.NONE, R.id.menu_clear, Menu.NONE, R.string.clear)
            }
            if (source != null && !webVisible && !source.ruleArticles.isNullOrBlank()) {
                menu.add(Menu.NONE, MENU_RSS_SWITCH_LAYOUT, Menu.NONE, R.string.switchLayout)
                    .setIcon(R.drawable.ic_view_quilt)
            }
            if (source != null) {
                menu.add(Menu.NONE, MENU_RSS_REFRESH, Menu.NONE, R.string.refresh)
                    .setIcon(R.drawable.ic_refresh_black_24dp)
            }
            if (webVisible) {
                menu.add(Menu.NONE, MENU_RSS_OPEN_BROWSER, Menu.NONE, R.string.open_in_browser)
                menu.add(Menu.NONE, MENU_RSS_COPY_URL, Menu.NONE, R.string.copy_url)
            }
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    R.id.menu_read_record -> {
                        showDialogFragment<ReadRecordDialog>()
                        true
                    }
                    MENU_RSS_FAVORITES -> {
                        startActivity<RssFavoritesActivity>()
                        true
                    }
                    R.id.menu_rss_config -> {
                        startActivity<RssSourceActivity>()
                        true
                    }
                    R.id.menu_edit -> {
                        source?.let(::edit)
                        true
                    }
                    R.id.menu_top -> {
                        source?.let(::toTop)
                        true
                    }
                    MENU_RSS_LOGIN -> {
                        source?.let(::openRssLogin)
                        true
                    }
                    R.id.menu_disable -> {
                        source?.let(::disable)
                        true
                    }
                    R.id.menu_del -> {
                        source?.let(::del)
                        true
                    }
                    R.id.menu_refresh_sort -> {
                        source?.let(::refreshRssSortCache)
                        true
                    }
                    R.id.menu_set_source_variable -> {
                        source?.let(::setSourceVariable)
                        true
                    }
                    MENU_RSS_SWITCH_LAYOUT -> {
                        switchModernRssLayout()
                        true
                    }
                    MENU_RSS_REFRESH -> {
                        refreshCurrentRssContent(forceWebRefresh = true)
                        true
                    }
                    MENU_RSS_OPEN_BROWSER -> {
                        currentWebUrl()?.let { context?.openUrl(it) }
                        true
                    }
                    MENU_RSS_COPY_URL -> {
                        currentWebUrl()?.let { requireContext().sendToClip(it) }
                        true
                    }
                    MENU_RSS_SOURCE_READ_RECORD -> {
                        showDialogFragment(ReadRecordDialog(source?.sourceUrl))
                        true
                    }
                    R.id.menu_clear -> {
                        source?.let(::clearRssArticles)
                        true
                    }
                    else -> if (item.groupId == R.id.menu_group_text) {
                        searchView.setQuery("group:${item.title}", true)
                        true
                    } else {
                        false
                    }
                }
            }
            show()
        }
    }

    private fun refreshRssSortCache(source: RssSource) {
        viewModel.clearSortCache(source) {
            refreshCurrentRssContent(forceWebRefresh = true)
        }
    }

    private fun clearRssArticles(source: RssSource) {
        viewModel.clearArticles(source.sourceUrl) {
            refreshCurrentRssContent(forceWebRefresh = true)
        }
    }

    private fun setSourceVariable(source: RssSource) {
        viewLifecycleOwner.lifecycleScope.launch {
            val comment = source.getDisplayVariableComment(
                "Source variables can be accessed in js through source.getVariable()"
            )
            val variable = withContext(IO) { source.getVariable() }
            showDialogFragment(
                VariableDialog(
                    getString(R.string.set_source_variable),
                    source.getKey(),
                    variable,
                    comment
                )
            )
        }
    }

    private fun currentWebUrl(): String? {
        return rssWebView?.url
            ?.takeIf { it.isNotBlank() && it != "about:blank" }
            ?: lastRenderedWebSourceUrl?.takeIf { it.isNotBlank() }
            ?: selectedRssSource?.sourceUrl?.takeIf { it.isNotBlank() }
    }

    private fun switchModernRssLayout() {
        if (binding.rssWebContainer.isVisible) return
        sortHostViewModel.switchLayout()
        renderCurrentSort()
    }

    private fun currentRssScrollTarget(): View? {
        return when {
            usingModernRss && binding.rssWebContainer.isVisible -> rssWebView
            usingModernRss && binding.rssFragmentContainer.isVisible ->
                childFragmentManager.findFragmentById(R.id.rss_fragment_container)
                    ?.view
                    ?.findViewById<View>(R.id.recycler_view)
            else -> binding.recyclerView
        }
    }

    private fun initGroupData() {
        groupsFlowJob?.cancel()
        groupsFlowJob = viewLifecycleOwner.lifecycleScope.launch {
            appDb.rssSourceDao.flowEnabledGroups().catch {
                AppLog.put("订阅界面获取分组数据失败\n${it.localizedMessage}", it)
            }.flowWithLifecycleAndDatabaseChange(
                viewLifecycleOwner.lifecycle,
                Lifecycle.State.RESUMED,
                AppDatabase.RSS_SOURCE_TABLE_NAME
            ).conflate().collect {
                groups.clear()
                groups.addAll(it)
                upGroupsMenu()
            }
        }
    }

    private fun observeClassicRssSources(searchKey: String? = currentSearchKey) {
        currentSearchKey = searchKey
        rssFlowJob?.cancel()
        rssFlowJob = viewLifecycleOwner.lifecycleScope.launch {
            when {
                searchKey.isNullOrEmpty() -> appDb.rssSourceDao.flowEnabled()
                searchKey.startsWith("group:") -> appDb.rssSourceDao.flowEnabledByGroup(searchKey.substringAfter("group:"))
                else -> appDb.rssSourceDao.flowEnabled(searchKey)
            }.flowWithLifecycleAndDatabaseChange(
                viewLifecycleOwner.lifecycle,
                Lifecycle.State.RESUMED,
                AppDatabase.RSS_SOURCE_TABLE_NAME
            ).catch {
                AppLog.put("订阅界面更新数据出错", it)
            }.flowOn(IO).collect {
                binding.swipeRefreshLayout.isRefreshing = false
                adapter.setItems(it)
                binding.tvEmptyMsg.visibility = if (it.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    private fun observeRssSources(searchKey: String? = currentSearchKey) {
        currentSearchKey = searchKey
        rssFlowJob?.cancel()
        rssFlowJob = viewLifecycleOwner.lifecycleScope.launch {
            when {
                searchKey.isNullOrEmpty() -> appDb.rssSourceDao.flowEnabled()
                searchKey.startsWith("group:") -> appDb.rssSourceDao.flowEnabledByGroup(searchKey.substringAfter("group:"))
                else -> appDb.rssSourceDao.flowEnabled(searchKey)
            }.flowWithLifecycleAndDatabaseChange(
                viewLifecycleOwner.lifecycle,
                Lifecycle.State.RESUMED,
                AppDatabase.RSS_SOURCE_TABLE_NAME
            ).catch {
                AppLog.put("订阅界面更新数据出错", it)
            }.flowOn(IO).collect { sources ->
                binding.swipeRefreshLayout.isRefreshing = false
                rssSources.clear()
                rssSources.addAll(sources)
                val keep = selectedRssSource?.sourceUrl?.let { key ->
                    sources.firstOrNull { it.sourceUrl == key }
                }
                val remembered = if (keep == null && searchKey.isNullOrEmpty()) {
                    AppConfig.modernRssSourceUrl?.let { key ->
                        sources.firstOrNull { it.sourceUrl == key }
                    }
                } else {
                    null
                }
                when {
                    keep != null -> selectSource(keep, reload = false)
                    remembered != null -> selectSource(remembered, reload = true)
                    sources.isNotEmpty() -> selectSource(sources.first(), reload = true)
                    else -> renderEmptyState()
                }
            }
        }
    }

    private fun selectSource(source: RssSource, reload: Boolean) {
        val changed = selectedRssSource?.sourceUrl != source.sourceUrl
        selectedRssSource = source
        AppConfig.modernRssSourceUrl = source.sourceUrl
        binding.tvRssSourceSelect.text = source.sourceName
        binding.btnRssSourceSearch.isVisible = !source.searchUrl.isNullOrBlank()
        binding.llRssSourceRow.post(::updateRssSourceNameWidth)
        if (changed) {
            selectedTagIndex = 0
        }
        if (changed || reload) {
            clearModernRssContent(showLoading = true)
            viewLifecycleOwner.lifecycleScope.launch {
                presentSource(source)
            }
        }
    }

    private suspend fun presentSource(source: RssSource) {
        sortHostViewModel.url = source.sourceUrl
        sortHostViewModel.rssSource = source
        sortHostViewModel.sourceName = source.sourceName
        sortHostViewModel.searchKey = null

        if (source.opensInWebPopup()) {
            currentSorts.clear()
            renderRssTabs()
            renderModernRssWebOpen(source)
            return
        }

        val sorts = runCatching { source.sortUrls() }
            .getOrElse {
                AppLog.put("订阅界面加载分类失败\n${it.localizedMessage}", it)
                renderModernRssError(it)
                return
            }.ifEmpty {
                listOf(Pair("", source.sourceUrl))
            }
        currentSorts.clear()
        currentSorts.addAll(sorts)
        renderRssTabs()
        renderCurrentSort()
    }

    private fun clearModernRssContent(showLoading: Boolean) {
        currentSorts.clear()
        renderRssTabs()
        binding.recyclerView.gone()
        binding.rssFragmentContainer.gone()
        binding.rssWebContainer.gone()
        childFragmentManager.findFragmentById(R.id.rss_fragment_container)?.let { fragment ->
            childFragmentManager.commit {
                remove(fragment)
            }
        }
        binding.tvEmptyMsg.gone()
        binding.btnOpenRss.gone()
        binding.swipeRefreshLayout.isRefreshing = false
        binding.swipeRefreshLayout.isEnabled = true
        if (showLoading) {
            binding.pbRssLoading.visible()
        } else {
            binding.pbRssLoading.gone()
        }
    }

    private fun renderModernRssWebOpen(source: RssSource) {
        binding.pbRssLoading.gone()
        binding.swipeRefreshLayout.isRefreshing = false
        binding.recyclerView.gone()
        binding.rssFragmentContainer.gone()
        binding.rssWebContainer.gone()
        binding.tvEmptyMsg.gone()
        binding.btnOpenRss.apply {
            text = getString(R.string.open_rss_source, source.sourceName)
            setOnClickListener {
                openRssLegacy(
                    rssSource = source,
                    onError = { renderModernRssError(it) }
                )
            }
            visible()
        }
    }

    private fun renderModernRssMessage(message: CharSequence?) {
        binding.pbRssLoading.gone()
        binding.swipeRefreshLayout.isRefreshing = false
        binding.btnOpenRss.gone()
        binding.tvEmptyMsg.text = message?.takeIf { it.isNotBlank() } ?: getString(R.string.rss)
        binding.tvEmptyMsg.visible()
    }

    private fun renderModernRssError(error: Throwable) {
        val message = error.localizedMessage
            ?: error.message
            ?: getString(R.string.unknown_error)
        renderModernRssMessage(message)
    }

    private fun RssSource.opensInWebPopup(): Boolean {
        return singleUrl || ruleArticles.isNullOrBlank()
    }

    private fun renderRssTabs() {
        binding.llRssTagsContainer.removeAllViews()
        rssTagBar = null
        val hasVisibleTags = currentSorts.size > 1 ||
            (currentSorts.size == 1 && currentSorts.first().first.isNotBlank())
        if (!hasVisibleTags) {
            binding.llRssTagsContainer.gone()
            return
        }
        binding.llRssTagsContainer.visible()
        selectedTagIndex = selectedTagIndex.coerceIn(0, currentSorts.lastIndex)
        val rowLayout = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        val tagBar = RoundedTagBarView(requireContext()).apply {
            setOnTagClickListener { index ->
                if (index == selectedTagIndex) return@setOnTagClickListener
                selectedTagIndex = index
                updateRssTabSelection(smooth = true)
                renderCurrentSort()
            }
            submitItems(
                currentSorts.mapIndexed { index, sort ->
                    RoundedTagBarView.Item(sort.first.ifBlank { "${index + 1}" }, showFullText = true)
                },
                selectedTagIndex
            )
        }
        rssTagBar = tagBar
        rowLayout.addView(
            tagBar,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
        )
        if (currentSorts.size >= ExpandableTagSelector.EXPAND_THRESHOLD) {
            rowLayout.addView(
                ExpandableTagSelector.createExpandButton(requireContext()) {
                    showRssTagsSelector()
                }
            )
        }
        binding.llRssTagsContainer.addView(
            rowLayout,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                resources.getDimensionPixelSize(R.dimen.bookshelf_tag_bar_height)
            )
        )
    }

    private fun updateRssTabSelection(smooth: Boolean) {
        rssTagBar?.setSelectedIndex(selectedTagIndex, smooth)
    }

    private fun showRssTagsSelector() {
        if (currentSorts.isEmpty()) return
        ExpandableTagSelector.show(
            context = requireContext(),
            title = getString(R.string.select),
            items = currentSorts.mapIndexed { index, sort ->
                ExpandableTagSelector.GridItem(
                    text = sort.first.ifBlank { "${index + 1}" },
                    selected = index == selectedTagIndex,
                    value = index
                )
            }
        ) { index ->
            if (index != selectedTagIndex) {
                selectedTagIndex = index
                updateRssTabSelection(smooth = true)
                renderCurrentSort()
            }
        }
    }

    private fun renderCurrentSort() {
        val source = selectedRssSource ?: return
        if (currentSorts.isEmpty()) {
            binding.pbRssLoading.gone()
            renderEmptyState()
            return
        }
        binding.swipeRefreshLayout.isEnabled = true
        selectedTagIndex = selectedTagIndex.coerceIn(0, currentSorts.lastIndex)
        updateRssTabSelection(smooth = false)
        val sort = currentSorts[selectedTagIndex]
        binding.recyclerView.gone()
        binding.rssWebContainer.gone()
        binding.btnOpenRss.gone()
        binding.tvEmptyMsg.gone()
        binding.rssFragmentContainer.visible()
        binding.pbRssLoading.gone()
        childFragmentManager.commit {
            replace(
                R.id.rss_fragment_container,
                RssArticlesFragment(sort.first, sort.second, null),
                "rss_articles_${source.sourceUrl}_${selectedTagIndex}"
            )
        }
    }

    private fun refreshCurrentRssContent(forceWebRefresh: Boolean = false) {
        if (!usingModernRss) {
            observeClassicRssSources(searchView.query?.toString())
            return
        }
        selectedRssSource?.let { source ->
            clearModernRssContent(showLoading = true)
            if (source.opensInWebPopup()) {
                binding.swipeRefreshLayout.isRefreshing = false
                renderModernRssWebOpen(source)
            } else {
                viewLifecycleOwner.lifecycleScope.launch {
                    presentSource(source)
                    binding.swipeRefreshLayout.isRefreshing = false
                }
            }
        } ?: run {
            binding.swipeRefreshLayout.isRefreshing = false
        }
    }

    private fun renderEmptyState() {
        selectedRssSource = null
        currentSorts.clear()
        binding.tvRssSourceSelect.text = getString(R.string.rss)
        binding.btnRssSourceSearch.gone()
        binding.swipeRefreshLayout.isEnabled = true
        renderRssTabs()
        binding.recyclerView.gone()
        binding.rssFragmentContainer.gone()
        binding.rssWebContainer.gone()
        binding.btnOpenRss.gone()
        binding.pbRssLoading.gone()
        binding.tvEmptyMsg.setText(R.string.rss_source_empty)
        binding.tvEmptyMsg.visible()
    }

    private fun applyWebContainerBottomPadding() {
        val initialPadding = binding.rssWebContainer.paddingBottom
        val webBottomSpace =
            resources.getDimensionPixelSize(R.dimen.main_bottom_controls_bottom_padding) +
                resources.getDimensionPixelSize(R.dimen.main_bottom_bar_height) +
                5.dpToPx()
        binding.rssWebContainer.setOnApplyWindowInsetsListenerCompat { view, windowInsets ->
            view.setPadding(
                view.paddingLeft,
                view.paddingTop,
                view.paddingRight,
                initialPadding + windowInsets.navigationBarHeight + webBottomSpace
            )
            windowInsets
        }
    }

    private fun showSourceSelector() {
        if (rssSources.isEmpty()) return
        SourceSelectDialog.show(
            context = requireContext(),
            title = getString(R.string.rss),
            items = rssSources,
            selectedKey = selectedRssSource?.sourceUrl,
            displayName = { it.getDisplayNameGroup() },
            searchTexts = {
                listOfNotNull(it.sourceName, it.sourceUrl, it.sourceGroup)
            },
            searchHint = getString(R.string.screen),
            itemKey = { it.sourceUrl }
        ) {
            selectSource(it, reload = true)
        }
    }

    private fun openRssSearch() {
        val source = selectedRssSource ?: return
        if (source.searchUrl.isNullOrBlank()) return
        RssSortActivity.start(requireContext(), null, source.sourceUrl, focusSearch = true)
    }

    private fun openRssLogin(rssSource: RssSource) {
        startActivity<SourceLoginActivity> {
            putExtra("type", "rssSource")
            putExtra("key", rssSource.sourceUrl)
        }
    }

    override fun openRss(rssSource: RssSource) {
        openRssLegacy(rssSource)
    }

    private fun openRssLegacy(
        rssSource: RssSource,
        onOpened: (() -> Unit)? = null,
        onError: ((Throwable) -> Unit)? = null
    ) {
        if (rssSource.singleUrl) {
            viewModel.getSingleUrl(rssSource, { url ->
                if (url.startsWith("http", true)) {
                    ReadRssActivity.start(
                        requireContext(),
                        true,
                        rssSource.sourceUrl,
                        rssSource.sourceName,
                        url
                    )
                } else {
                    context?.openUrl(url)
                }
                onOpened?.invoke()
            }, onError)
        } else {
            viewModel.launchRssWithHtml(
                rssSource = rssSource,
                noStartHtml = {
                    startActivity<RssSortActivity> {
                        putExtra("sourceUrl", rssSource.sourceUrl)
                    }
                    onOpened?.invoke()
                },
                isStartHtml = { html ->
                    ReadRssActivity.start(
                        requireContext(),
                        true,
                        rssSource.sourceUrl,
                        rssSource.sourceName,
                        startHtml = html
                    )
                    onOpened?.invoke()
                },
                onError = onError
            )
        }
    }

    override fun toTop(rssSource: RssSource) {
        viewModel.topSource(rssSource)
    }

    override fun login(rssSource: RssSource) {
        openRssLogin(rssSource)
    }

    override fun edit(rssSource: RssSource) {
        startActivity<RssSourceEditActivity> {
            putExtra("sourceUrl", rssSource.sourceUrl)
        }
    }

    override fun del(rssSource: RssSource) {
        alert(R.string.draw) {
            setMessage(getString(R.string.sure_del) + "\n" + rssSource.sourceName)
            noButton()
            yesButton {
                viewModel.del(rssSource)
            }
        }
    }

    override fun disable(rssSource: RssSource) {
        viewModel.disable(rssSource)
    }

    override fun setVariable(key: String, variable: String?) {
        (selectedRssSource?.takeIf { it.getKey() == key }
            ?: rssSources.firstOrNull { it.getKey() == key })
            ?.setVariable(variable)
    }

    fun gotoTop() {
        val target = when {
            binding.rssWebContainer.isVisible -> rssWebView
            else -> childFragmentManager.findFragmentById(R.id.rss_fragment_container)?.view?.findViewById<View>(R.id.recycler_view)
        }
        when (target) {
            is WebView -> target.scrollTo(0, 0)
            is RecyclerView -> target.scrollToPosition(0)
        }
    }
}
