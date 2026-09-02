package io.legado.app.ui.main.explore

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import android.view.SubMenu
import android.view.View
import androidx.appcompat.widget.SearchView
import androidx.core.content.ContextCompat
import androidx.core.widget.NestedScrollView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import androidx.core.os.bundleOf
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.flexbox.FlexboxLayout
import com.script.rhino.runScriptWithContext
import io.legado.app.R
import io.legado.app.base.VMBaseFragment
import io.legado.app.constant.AppLog
import io.legado.app.data.AppDatabase
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.BookSourcePart
import io.legado.app.data.entities.SearchBook
import io.legado.app.data.entities.rule.ExploreKind
import io.legado.app.data.entities.rule.RowUi
import io.legado.app.databinding.FragmentExploreBinding
import io.legado.app.databinding.ItemFindBookBinding
import io.legado.app.databinding.ItemFilletCompleteTextBinding
import io.legado.app.databinding.ItemFilletSelectorSingleBinding
import io.legado.app.databinding.ItemFilletTextBinding
import io.legado.app.help.book.isNotShelf
import io.legado.app.help.config.AppConfig
import io.legado.app.help.source.clearExploreKindsCache
import io.legado.app.help.source.exploreKinds
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.theme.UiCorner
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.applyUiTitleTypeface
import io.legado.app.lib.theme.primaryColor
import io.legado.app.lib.theme.primaryTextColor
import io.legado.app.lib.theme.surface.SurfaceStyles
import io.legado.app.lib.theme.uiTypeface
import io.legado.app.model.webBook.WebBook
import io.legado.app.ui.book.explore.ExploreShowAdapter
import io.legado.app.ui.book.explore.ExploreShowActivity
import io.legado.app.ui.widget.menu.SurfacePopupMenu
import io.legado.app.ui.book.SearchBookOpenHelper
import io.legado.app.ui.book.search.SearchActivity
import io.legado.app.ui.book.source.edit.BookSourceEditActivity
import io.legado.app.ui.login.SourceLoginActivity
import io.legado.app.ui.login.SourceLoginJsExtensions
import io.legado.app.ui.main.MainFragmentInterface
import io.legado.app.ui.widget.ExpandableTagSelector
import io.legado.app.ui.widget.RoundedTagBarView
import io.legado.app.ui.widget.RowUiDialog
import io.legado.app.ui.widget.SourceSelectDialog
import io.legado.app.utils.applyMainBottomBarPadding
import io.legado.app.utils.applyStatusBarPadding
import io.legado.app.utils.applyTint
import io.legado.app.utils.dpToPx
import io.legado.app.utils.flowWithLifecycleAndDatabaseChange
import io.legado.app.utils.gone
import io.legado.app.utils.InfoMap
import io.legado.app.utils.SurfaceBackdrop
import io.legado.app.utils.applyAdaptiveDim
import io.legado.app.utils.windowSize
import io.legado.app.utils.setEdgeEffectColor
import io.legado.app.utils.startActivity
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.transaction
import io.legado.app.utils.visible
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * 发现界面
 */
class ExploreFragment() : VMBaseFragment<ExploreViewModel>(R.layout.fragment_explore),
    MainFragmentInterface,
    ExploreAdapter.CallBack,
    ExploreShowAdapter.CallBack {

    constructor(position: Int) : this() {
        val bundle = Bundle()
        bundle.putInt("position", position)
        arguments = bundle
    }

    override val position: Int? get() = arguments?.getInt("position")

    override val viewModel by viewModels<ExploreViewModel>()
    private val binding by viewBinding(FragmentExploreBinding::bind)
    private val adapter by lazy { ExploreAdapter(requireContext(), this) }
    private val discoverBookAdapter by lazy { ExploreShowAdapter(requireContext(), this) }
    private val linearLayoutManager by lazy { LinearLayoutManager(context) }
    private val searchView: SearchView? by lazy {
        binding.titleBar.findViewById<SearchView?>(R.id.search_view)
    }
    private val diffItemCallBack = ExploreDiffItemCallBack()
    private val groups = linkedSetOf<String>()
    private var exploreFlowJob: Job? = null
    private var groupsMenu: SubMenu? = null
    private var oldModeInitialized = false
    private var modernModeInitialized = false
    private var usingModernDiscovery = false
    private var sourceMenuPopup: PopupWindow? = null
    private var tagFilterPopup: PopupWindow? = null
    private var discoverSourceFlowJob: Job? = null
    private var discoverBookshelfFlowJob: Job? = null
    private var discoverLoadJob: Job? = null
    private var discoverActionJob: Job? = null
    private val discoverSources = mutableListOf<BookSourcePart>()
    private val discoverAllTagItems = mutableListOf<DiscoverTagItem>()
    private val discoverTagItems = mutableListOf<DiscoverTagItem>()
    private val discoverSelectItems = mutableListOf<DiscoverTagItem>()
    private val discoverSettingItems = mutableListOf<DiscoverTagItem>()
    private val discoverMajorGroups = mutableListOf<String>()
    private val discoverBookshelf = linkedSetOf<String>()
    private val discoverBooks = linkedSetOf<SearchBook>()
    private val blockedButtonActions = hashMapOf<String, MutableSet<String>>()
    private var selectedDiscoverSourcePart: BookSourcePart? = null
    private var selectedDiscoverSource: BookSource? = null
    private var discoverCurrentUrl: String? = null
    private var discoverPage = 1
    private var discoverHasMore = true
    private var discoverLoading = false
    private var selectedDiscoverMajorGroup: String? = null
    private var selectedDiscoverTagIndex = -1
    private var selectedDiscoverUrlIndex = -1
    private var discoverRequestVersion = 0L
    private var discoverSourceVersion = 0L
    private var discoverLoadingSignals = 0
    private var discoverLoadingGeneration = 0L
    private var discoveryModeLoaded = false
    private val discoverTextActionJobs = hashMapOf<String, Job>()

    private fun areBookSourcePartListsSame(
        old: List<BookSourcePart>,
        new: List<BookSourcePart>
    ): Boolean {
        if (old.size != new.size) return false
        return old.zip(new).all { (oldItem, newItem) ->
            oldItem.bookSourceUrl == newItem.bookSourceUrl
                    && oldItem.bookSourceName == newItem.bookSourceName
                    && oldItem.bookSourceGroup == newItem.bookSourceGroup
                    && oldItem.customOrder == newItem.customOrder
                    && oldItem.enabled == newItem.enabled
                    && oldItem.enabledExplore == newItem.enabledExplore
                    && oldItem.hasLoginUrl == newItem.hasLoginUrl
                    && oldItem.hasExploreUrl == newItem.hasExploreUrl
                    && oldItem.bookSourceType == newItem.bookSourceType
        }
    }

    private companion object {
        const val MENU_DISCOVER_LOGIN = 1
        const val MENU_DISCOVER_SWITCH_LAYOUT = 2
        const val MENU_DISCOVER_RELOAD_SOURCE = 3
        const val DISCOVER_LAYOUT_COUNT = 3
        const val DISCOVER_DIALOG_WIDTH_RATIO = 0.90f
        const val DISCOVER_DIALOG_HEIGHT_RATIO = 0.72f
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        setSupportToolbar(binding.titleBar.toolbar)
        usingModernDiscovery = AppConfig.modernDiscoveryPage
        discoveryModeLoaded = false
        binding.swipeRefreshLayout.setColorSchemeColors(accentColor)
        binding.swipeRefreshLayout.setProgressViewOffset(true, (-28).dpToPx(), 56.dpToPx())
        binding.swipeRefreshLayout.setOnChildScrollUpCallback { _, _ ->
            currentDiscoverScrollTarget()?.canScrollVertically(-1) == true
        }
        binding.swipeRefreshLayout.setOnRefreshListener {
            if (usingModernDiscovery) {
                loadDiscoverBooks(reset = true)
            } else {
                upExploreData(searchView?.query?.toString())
            }
        }
        binding.llDiscoverSourceRow.applyStatusBarPadding(withInitialPadding = true)
        binding.rvFind.clipToPadding = false
        binding.rvFind.applyMainBottomBarPadding()
        binding.rvDiscoverBooks.clipToPadding = false
        binding.rvDiscoverBooks.applyMainBottomBarPadding(
            withInitialPadding = true,
            usePaddingForRecyclerView = true
        )
        applyDiscoveryMode(loadData = false)
    }

    override fun onCompatCreateOptionsMenu(menu: Menu) {
        super.onCompatCreateOptionsMenu(menu)
        if (usingModernDiscovery) {
            groupsMenu = null
            return
        }
        menuInflater.inflate(R.menu.main_explore, menu)
        groupsMenu = menu.findItem(R.id.menu_group)?.subMenu
        upGroupsMenu()
    }

    private fun applyDiscoveryMode(loadData: Boolean = true) {
        val modern = AppConfig.modernDiscoveryPage
        usingModernDiscovery = modern
        binding.titleBar.isGone = modern
        binding.llModernDiscovery.isVisible = modern
        binding.rvFind.isGone = modern
        binding.tvEmptyMsg.isGone = modern
        searchView?.isGone = modern
        if (!loadData) {
            activity?.invalidateOptionsMenu()
            return
        }
        if (modern) {
            exploreFlowJob?.cancel()
            initModernMode()
        } else {
            stopModernMode()
            initClassicMode()
        }
        activity?.invalidateOptionsMenu()
    }

    private fun currentDiscoverScrollTarget(): View? {
        return when {
            usingModernDiscovery -> binding.rvDiscoverBooks
            else -> binding.rvFind
        }
    }

    private fun showDiscoverLoading(): Long {
        discoverLoadingSignals += 1
        binding.tvDiscoverEmpty.gone()
        binding.tvDiscoverLoading.setText(R.string.data_loading)
        binding.llDiscoverLoading.visible()
        return discoverLoadingGeneration
    }

    private fun hideDiscoverLoading(generation: Long) {
        if (generation != discoverLoadingGeneration) return
        discoverLoadingSignals = (discoverLoadingSignals - 1).coerceAtLeast(0)
        if (discoverLoadingSignals == 0) {
            binding.llDiscoverLoading.gone()
        }
    }

    private fun clearDiscoverLoading() {
        discoverLoadingGeneration += 1
        discoverLoadingSignals = 0
        binding.llDiscoverLoading.gone()
    }

    private fun resetExplore() {
        discoverRequestVersion += 1
        discoverLoadJob?.cancel()
        discoverLoadJob = null
        discoverLoading = false
        binding.swipeRefreshLayout.isRefreshing = false
        discoverTagItems.clear()
        discoverSelectItems.clear()
        selectedDiscoverTagIndex = -1
        selectedDiscoverUrlIndex = -1
        discoverCurrentUrl = null
        discoverHasMore = true
        discoverPage = 1
        discoverBooks.clear()
        discoverBookAdapter.clearItems()
        binding.llDiscoverSelectsBar.gone()
        binding.rvDiscoverSelects.submitItems(emptyList(), -1)
        binding.rvDiscoverTags.submitItems(emptyList(), -1)
        binding.btnDiscoverTagsExpand.gone()
        binding.tvDiscoverEmpty.gone()
    }

    private inner class DiscoverRefreshController {
        private val loadingActive = AtomicBoolean(false)
        private val loadingGeneration = AtomicLong(Long.MIN_VALUE)
        private val refreshRequested = AtomicBoolean(false)

        val requested: Boolean
            get() = refreshRequested.get()

        fun showLoading() {
            refreshRequested.set(true)
            if (!loadingActive.compareAndSet(false, true)) return
            binding.root.post {
                if (!isAdded || !loadingActive.get()) return@post
                loadingGeneration.set(showDiscoverLoading())
                resetExplore()
            }
        }

        fun finish() {
            loadingActive.set(false)
            if (!isAdded) return
            val generation = loadingGeneration.get()
            if (generation != Long.MIN_VALUE) {
                hideDiscoverLoading(generation)
            }
        }
    }

    private fun discoverRefreshCallback(
        controller: DiscoverRefreshController,
        onOpen: (
            name: String,
            url: String?,
            title: String?,
            origin: String?
        ) -> Boolean = { _, _, _, _ -> false }
    ): SourceLoginJsExtensions.Callback {
        return object : SourceLoginJsExtensions.Callback {
            override fun upUiData(data: Map<String, Any?>?) = Unit

            override fun reUiView(deltaUp: Boolean) {
                controller.showLoading()
            }

            override fun open(
                name: String,
                url: String?,
                title: String?,
                origin: String?
            ): Boolean {
                return onOpen(name, url, title, origin)
            }
        }
    }

    private fun discoverJsExtensions(
        source: BookSource,
        controller: DiscoverRefreshController,
        onOpen: (
            name: String,
            url: String?,
            title: String?,
            origin: String?
        ) -> Boolean = { _, _, _, _ -> false }
    ): SourceLoginJsExtensions {
        return SourceLoginJsExtensions(
            activity as? AppCompatActivity,
            source,
            callback = discoverRefreshCallback(controller, onOpen)
        )
    }

    private fun initClassicMode() {
        if (!oldModeInitialized) {
            oldModeInitialized = true
            initSearchView()
            initRecyclerView()
            initGroupData()
        }
        if (exploreFlowJob?.isActive != true) {
            upExploreData(searchView?.query?.toString())
        }
    }

    private fun initModernMode() {
        if (!modernModeInitialized) {
            modernModeInitialized = true
            initDiscoverRecycler()
            bindDiscoverSourceSelector()
            updateDiscoverLoginButtonState()
        }
        observeDiscoverSources()
        observeDiscoverBookshelf()
    }

    private fun stopModernMode() {
        sourceMenuPopup?.dismiss()
        sourceMenuPopup = null
        tagFilterPopup?.dismiss()
        tagFilterPopup = null
        discoverSourceFlowJob?.cancel()
        discoverSourceFlowJob = null
        discoverBookshelfFlowJob?.cancel()
        discoverBookshelfFlowJob = null
        discoverActionJob?.cancel()
        discoverActionJob = null
        discoverLoadJob?.cancel()
        discoverLoadJob = null
        discoverSourceVersion += 1
        discoverRequestVersion += 1
        discoverLoading = false
        clearDiscoverLoading()
        discoverAllTagItems.clear()
        discoverMajorGroups.clear()
        discoverTagItems.clear()
        discoverSelectItems.clear()
        discoverSettingItems.clear()
        selectedDiscoverMajorGroup = null
        selectedDiscoverTagIndex = -1
        selectedDiscoverUrlIndex = -1
    }

    private fun initSearchView() {
        val view = searchView ?: return
        view.applyTint(primaryTextColor)
        view.isSubmitButtonEnabled = true
        view.queryHint = getString(R.string.screen_find)
        view.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                return false
            }

            override fun onQueryTextChange(newText: String?): Boolean {
                upExploreData(newText)
                return false
            }
        })
    }

    private fun initDiscoverRecycler() {
        binding.rvDiscoverTags.setOnTagClickListener { index ->
            val item = discoverTagItems.getOrNull(index) ?: return@setOnTagClickListener
            if (item.isButton) {
                handleDiscoverButtonTag(item)
                return@setOnTagClickListener
            }
            selectDiscoverTag(index, item, selectTab = true)
        }
        binding.rvDiscoverSelects.setOnTagClickListener { index ->
            val group = discoverMajorGroups.getOrNull(index) ?: return@setOnTagClickListener
            selectedDiscoverMajorGroup = group
            applyDiscoverTagFilterAndSelect(preferredUrl = discoverCurrentUrl)
        }
        binding.btnDiscoverTagsExpand.setOnClickListener {
            showDiscoverTagsSelector()
        }
        ExpandableTagSelector.configureExpandButton(binding.btnDiscoverTagsExpand)
        binding.btnDiscoverSelectsExpand.setOnClickListener {
            showDiscoverMajorGroupsSelector()
        }
        ExpandableTagSelector.configureExpandButton(binding.btnDiscoverSelectsExpand)
        applyDiscoverBookLayout()
        binding.rvDiscoverBooks.adapter = discoverBookAdapter
        binding.rvDiscoverBooks.setEdgeEffectColor(primaryColor)
        binding.rvDiscoverBooks.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(recyclerView, dx, dy)
                if (dy > 0 && !recyclerView.canScrollVertically(1)) {
                    loadDiscoverBooks(reset = false)
                }
            }
        })
    }

    private fun bindDiscoverSourceSelector() {
        binding.tvDiscoverSourceSelect.applyUiTitleTypeface(requireContext())
        val updateSourceNameWidth = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            binding.llDiscoverSourceRow.post(::updateDiscoverSourceNameWidth)
        }
        binding.llDiscoverSourceRow.addOnLayoutChangeListener(updateSourceNameWidth)
        binding.llDiscoverSourceRow.post(::updateDiscoverSourceNameWidth)
        binding.llDiscoverSourceSelect.setOnClickListener {
            showDiscoverSourceMenu()
        }
        binding.llDiscoverSourceSelect.setOnLongClickListener {
            showDiscoverKindsDialog()
            true
        }
        binding.btnDiscoverSourceSearch.setOnClickListener {
            openDiscoverSearch()
        }
        binding.btnDiscoverTagFilter.setOnClickListener {
            showDiscoverSettingsDialog()
        }
        binding.btnDiscoverMore.setOnClickListener {
            showDiscoverMoreMenu()
        }
        updateDiscoverTagFilterButtonState()
        updateDiscoverSearchButtonState()
    }

    private fun updateDiscoverSourceNameWidth() {
        val rowWidth = binding.llDiscoverSourceRow.width
        if (rowWidth <= 0) return
        val actionsWidth = listOf(
            binding.btnDiscoverSourceSearch,
            binding.btnDiscoverTagFilter,
            binding.btnDiscoverMore
        ).filter { it.isVisible }.sumOf { it.measuredWidth.takeIf { width -> width > 0 } ?: it.layoutParams.width }
        val spacing = 36.dpToPx()
        val maxWidth = (rowWidth - actionsWidth - spacing).coerceIn(96.dpToPx(), 190.dpToPx())
        if (binding.tvDiscoverSourceSelect.maxWidth != maxWidth) {
            binding.tvDiscoverSourceSelect.maxWidth = maxWidth
        }
    }

    private fun openSelectedSourceLogin() {
        val source = selectedDiscoverSourcePart ?: return
        if (!source.hasLoginUrl) {
            context?.toastOnUi(R.string.source_no_login)
            return
        }
        startActivity<SourceLoginActivity> {
            putExtra("type", "bookSource")
            putExtra("key", source.bookSourceUrl)
        }
    }

    private fun updateDiscoverLoginButtonState() {
        binding.btnDiscoverMore.alpha = 1f
        binding.llDiscoverSourceRow.post(::updateDiscoverSourceNameWidth)
    }

    private fun showDiscoverMoreMenu() {
        SurfacePopupMenu(requireContext(), binding.btnDiscoverMore).apply {
            menu.add(Menu.NONE, MENU_DISCOVER_LOGIN, Menu.NONE, R.string.login).apply {
                isVisible = selectedDiscoverSourcePart?.hasLoginUrl == true
                setIcon(R.drawable.ic_bottom_person)
            }
            menu.add(Menu.NONE, MENU_DISCOVER_SWITCH_LAYOUT, Menu.NONE, R.string.switchLayout).apply {
                setIcon(R.drawable.ic_view_quilt)
            }
            menu.add(Menu.NONE, MENU_DISCOVER_RELOAD_SOURCE, Menu.NONE, R.string.reload_book_source).apply {
                setIcon(R.drawable.ic_refresh_black_24dp)
            }
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    MENU_DISCOVER_LOGIN -> {
                        openSelectedSourceLogin()
                        true
                    }
                    MENU_DISCOVER_SWITCH_LAYOUT -> {
                        switchDiscoverBookLayout()
                        true
                    }
                    MENU_DISCOVER_RELOAD_SOURCE -> {
                        reloadDiscoverSource()
                        true
                    }
                    else -> false
                }
            }
            show()
        }
    }

    private fun switchDiscoverBookLayout() {
        AppConfig.modernDiscoveryLayout = (AppConfig.modernDiscoveryLayout + 1) % DISCOVER_LAYOUT_COUNT
        applyDiscoverBookLayout()
    }

    private fun applyDiscoverBookLayout() {
        val style = AppConfig.modernDiscoveryLayout
        discoverBookAdapter.layoutStyle = style
        binding.rvDiscoverBooks.layoutManager = when (style) {
            1 -> GridLayoutManager(requireContext(), 2)
            2 -> GridLayoutManager(requireContext(), 3)
            else -> LinearLayoutManager(requireContext())
        }
        discoverBookAdapter.notifyDataSetChanged()
    }

    private fun reloadDiscoverSource() {
        val sourcePart = selectedDiscoverSourcePart ?: return
        tagFilterPopup?.dismiss()
        tagFilterPopup = null
        discoverSourceVersion += 1
        val currentSourceVersion = discoverSourceVersion
        discoverRequestVersion += 1
        discoverActionJob?.cancel()
        discoverLoadJob?.cancel()
        discoverLoadJob = null
        discoverLoading = false
        clearDiscoverLoading()
        resetExplore()
        discoverAllTagItems.clear()
        discoverMajorGroups.clear()
        discoverSettingItems.clear()
        selectedDiscoverMajorGroup = null
        renderDiscoverMajorGroups()
        updateDiscoverTagFilterButtonState()
        viewLifecycleOwner.lifecycleScope.launch {
            val loadingGeneration = showDiscoverLoading()
            try {
                val fullSource = withContext(IO) {
                    appDb.bookSourceDao.getBookSource(sourcePart.bookSourceUrl)?.also {
                        it.clearExploreKindsCache()
                    }
                }
                if (!isAdded || currentSourceVersion != discoverSourceVersion) {
                    return@launch
                }
                selectedDiscoverSource = fullSource
                updateDiscoverSourceTitle()
                updateDiscoverLoginButtonState()
                updateDiscoverSearchButtonState()
                loadDiscoverKindsAndDefault()
            } finally {
                if (isAdded && currentSourceVersion == discoverSourceVersion) {
                    hideDiscoverLoading(loadingGeneration)
                    binding.swipeRefreshLayout.isRefreshing = false
                }
            }
        }
    }

    private fun updateDiscoverSearchButtonState() {
        val canSearch = !selectedDiscoverSource?.searchUrl.isNullOrBlank()
        binding.btnDiscoverSourceSearch.isVisible = canSearch
        binding.btnDiscoverSourceSearch.isEnabled = canSearch
        binding.btnDiscoverSourceSearch.alpha = if (canSearch) 1f else 0.45f
        binding.llDiscoverSourceRow.post(::updateDiscoverSourceNameWidth)
    }

    private fun openDiscoverSearch() {
        val source = selectedDiscoverSource ?: return
        if (source.searchUrl.isNullOrBlank()) {
            context?.toastOnUi(R.string.search_book_key)
            return
        }
        startActivity<SearchActivity> {
            putExtra("searchScope", "${source.bookSourceName}::${source.bookSourceUrl}")
        }
    }

    private fun observeDiscoverSources() {
        if (discoverSourceFlowJob?.isActive == true) return
        discoverSourceFlowJob = viewLifecycleOwner.lifecycleScope.launch {
            appDb.bookSourceDao.flowExplore()
                .flowWithLifecycleAndDatabaseChange(
                    viewLifecycleOwner.lifecycle,
                    Lifecycle.State.STARTED,
                    AppDatabase.BOOK_SOURCE_TABLE_NAME
                )
                .conflate()
                .distinctUntilChanged(::areBookSourcePartListsSame)
                .collect { list ->
                    discoverSources.clear()
                    discoverSources.addAll(list)
                    if (discoverSources.isEmpty()) {
                        selectedDiscoverSourcePart = null
                        selectedDiscoverSource = null
                        AppConfig.modernDiscoverySourceUrl = null
                        discoverCurrentUrl = null
                        discoverAllTagItems.clear()
                        discoverMajorGroups.clear()
                        discoverSettingItems.clear()
                        selectedDiscoverMajorGroup = null
                        clearDiscoverBooksToEmpty(getString(R.string.explore_empty))
                        renderDiscoverTags(emptyList(), -1)
                        renderDiscoverMajorGroups()
                        binding.tvDiscoverSourceSelect.text = getString(R.string.explore_empty)
                        updateDiscoverLoginButtonState()
                        updateDiscoverSearchButtonState()
                        updateDiscoverTagFilterButtonState()
                        clearDiscoverLoading()
                        return@collect
                    }
                    val keepSource = selectedDiscoverSourcePart?.bookSourceUrl
                        ?: AppConfig.modernDiscoverySourceUrl
                    val selected = discoverSources.firstOrNull { it.bookSourceUrl == keepSource }
                        ?: discoverSources.first()
                    if (selectedDiscoverSourcePart?.bookSourceUrl != selected.bookSourceUrl
                        || discoverTagItems.isEmpty()
                    ) {
                        selectDiscoverSource(selected)
                    } else {
                        selectedDiscoverSourcePart = selected
                        selectedDiscoverSource?.takeIf {
                            it.bookSourceUrl == selected.bookSourceUrl
                        }?.apply {
                            bookSourceName = selected.bookSourceName
                            bookSourceGroup = selected.bookSourceGroup
                            bookSourceType = selected.bookSourceType
                        }
                        updateDiscoverSourceTitle()
                        updateDiscoverLoginButtonState()
                        updateDiscoverSearchButtonState()
                    }
                }
        }
    }

    private fun observeDiscoverBookshelf() {
        if (discoverBookshelfFlowJob?.isActive == true) return
        discoverBookshelfFlowJob = viewLifecycleOwner.lifecycleScope.launch {
            appDb.bookDao.flowAll()
                .flowWithLifecycleAndDatabaseChange(
                    viewLifecycleOwner.lifecycle,
                    Lifecycle.State.STARTED,
                    AppDatabase.BOOK_TABLE_NAME
                )
                .conflate()
                .distinctUntilChanged()
                .collect { books ->
                    discoverBookshelf.clear()
                    books.filterNot { it.isNotShelf }
                        .forEach {
                            discoverBookshelf.add("${it.name}-${it.author}")
                            discoverBookshelf.add(it.name)
                            discoverBookshelf.add(it.bookUrl)
                        }
                    if (discoverBookAdapter.itemCount > 0) {
                        discoverBookAdapter.notifyItemRangeChanged(
                            0,
                            discoverBookAdapter.itemCount,
                            bundleOf("isInBookshelf" to null)
                        )
                    }
                }
        }
    }

    private fun showDiscoverSourceMenu() {
        if (discoverSources.isEmpty()) return
        SourceSelectDialog.show(
            context = requireContext(),
            title = getString(R.string.book_source),
            items = discoverSources,
            selectedKey = selectedDiscoverSourcePart?.bookSourceUrl,
            searchHint = getString(R.string.screen_find),
            displayName = { it.getDisPlayNameGroup() },
            searchTexts = {
                listOfNotNull(it.bookSourceName, it.bookSourceUrl, it.bookSourceGroup)
            },
            itemKey = { it.bookSourceUrl }
        ) {
            selectDiscoverSource(it)
        }
    }

    private fun showDiscoverKindsDialog() {
        val context = context ?: return
        val source = selectedDiscoverSource ?: return
        var dialog: AlertDialog? = null
        val screenSize = requireActivity().windowManager.windowSize
        val dialogWidth = (screenSize.widthPixels * DISCOVER_DIALOG_WIDTH_RATIO).toInt()
        val dialogHeight = (screenSize.heightPixels * DISCOVER_DIALOG_HEIGHT_RATIO).toInt()
        val itemBinding = ItemFindBookBinding.inflate(layoutInflater, null, false)
        val flexbox = itemBinding.flexbox
        val scrollView = NestedScrollView(context).apply {
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            isFillViewport = true
            addView(
                itemBinding.root,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }
        val refreshLayout = SwipeRefreshLayout(context).apply {
            setColorSchemeColors(accentColor)
            setOnChildScrollUpCallback { _, _ ->
                scrollView.canScrollVertically(-1)
            }
            setOnRefreshListener {
                refreshDiscoverKindsDialog(itemBinding, source, dialog, this, clearCache = true)
            }
            addView(
                scrollView,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
        }
        val dialogContent = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(dialogWidth, dialogHeight)
            SurfaceBackdrop.installStatic(this, SurfaceStyles.dialog(context))
            setPadding(8.dpToPx(), 8.dpToPx(), 8.dpToPx(), 10.dpToPx())
            addView(
                refreshLayout,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    0,
                    1f
                )
            )
        }
        itemBinding.apply {
            root.setPadding(0, 0, 0, 0)
            root.background = null
            llTitle.isClickable = false
            llTitle.background = UiCorner.opaqueRounded(
                UiCorner.dialogSurfaceColor(
                    ContextCompat.getColor(context, R.color.background_menu)
                ),
                UiCorner.actionRadius(context)
            )
            tvName.text = source.bookSourceName
            ivStatus.gone()
            flexbox.visible()
            rotateLoading.visible()
        }
        dialog = AlertDialog.Builder(context)
            .setView(dialogContent)
            .create()
        dialog.show()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.applyAdaptiveDim(dialogContent)
        dialog.window?.setLayout(dialogWidth, dialogHeight)
        refreshDiscoverKindsDialog(itemBinding, source, dialog, refreshLayout, clearCache = true)
    }

    private fun refreshDiscoverKindsDialog(
        itemBinding: ItemFindBookBinding,
        source: BookSource,
        dialog: AlertDialog?,
        refreshLayout: SwipeRefreshLayout?,
        clearCache: Boolean
    ) {
        itemBinding.rotateLoading.visible()
        viewLifecycleOwner.lifecycleScope.launch {
            val result = runCatching {
                withContext(IO) {
                    if (clearCache) {
                        source.clearExploreKindsCache()
                    }
                    source.exploreKinds()
                }
            }
            if (!isAdded || dialog?.isShowing != true) return@launch
            refreshLayout?.isRefreshing = false
            itemBinding.rotateLoading.gone()
            result.onSuccess { kinds ->
                if (kinds.isEmpty()) {
                    context?.toastOnUi(R.string.explore_empty)
                    return@onSuccess
                }
                renderDiscoverDialogKinds(itemBinding, source, kinds, dialog)
            }.onFailure {
                AppLog.put("完整发现刷新失败", it)
                context?.toastOnUi(it.localizedMessage ?: getString(R.string.unknown_error))
            }
        }
    }

    private fun renderDiscoverDialogKinds(
        itemBinding: ItemFindBookBinding,
        source: BookSource,
        kinds: List<ExploreKind>,
        dialog: AlertDialog?
    ) {
        val flexbox = itemBinding.flexbox
        val infoMap = getDiscoverInfoMap(source.bookSourceUrl)
        val java = SourceLoginJsExtensions(
            activity as? AppCompatActivity,
            source,
            callback = object : SourceLoginJsExtensions.Callback {
                override fun upUiData(data: Map<String, Any?>?) = Unit

                override fun reUiView(deltaUp: Boolean) {
                    refreshDiscoverKindsDialog(itemBinding, source, dialog, null, clearCache = true)
                }
            }
        )
        flexbox.removeAllViews()
        kinds.forEach { kind ->
            when (kind.type) {
                ExploreKind.Type.url -> renderDiscoverDialogUrl(flexbox, source, kind, dialog, infoMap)
                ExploreKind.Type.button -> renderDiscoverDialogButton(flexbox, source, kind, infoMap, java)
                ExploreKind.Type.toggle -> renderDiscoverDialogToggle(flexbox, source, kind, infoMap, java)
                ExploreKind.Type.select -> renderDiscoverDialogSelect(flexbox, source, kind, infoMap, java)
                ExploreKind.Type.text -> renderDiscoverDialogTextInput(flexbox, source, kind, infoMap, java)
            }
        }
    }

    private fun renderDiscoverDialogUrl(
        flexbox: FlexboxLayout,
        source: BookSource,
        kind: ExploreKind,
        dialog: AlertDialog?,
        infoMap: InfoMap
    ) {
        val tv = createDiscoverDialogTextView(flexbox)
        flexbox.addView(tv)
        applyDiscoverKindTextStyle(tv, kind)
        bindDiscoverDialogKindText(tv, kind, source, infoMap)
        tv.setOnClickListener {
            val url = kind.url?.trim()?.takeIf {
                it.isNotBlank() && !it.equals("null", ignoreCase = true)
            } ?: return@setOnClickListener
            dialog?.dismiss()
            openDiscoverDialogTagInCurrentPage(source, kind, url)
        }
    }

    private fun openDiscoverDialogTagInCurrentPage(
        source: BookSource,
        kind: ExploreKind,
        url: String
    ) {
        val item = discoverAllTagItems.firstOrNull {
            !it.isButton && it.kind.url == url
        } ?: DiscoverTagItem(
            kind = kind.copy(url = url),
            text = resolveDiscoverTagText(kind),
            isButton = false,
            group = null
        )
        val index = discoverTagItems.indexOfFirst { !it.isButton && it.kind.url == url }
        if (index >= 0) {
            selectDiscoverTag(index, discoverTagItems[index], selectTab = true)
        } else {
            selectDiscoverSettingUrl(item)
        }
    }

    private fun renderDiscoverDialogButton(
        flexbox: FlexboxLayout,
        source: BookSource,
        kind: ExploreKind,
        infoMap: InfoMap,
        java: SourceLoginJsExtensions
    ) {
        val tv = createDiscoverDialogTextView(flexbox)
        flexbox.addView(tv)
        applyDiscoverKindTextStyle(tv, kind)
        bindDiscoverDialogKindText(tv, kind, source, infoMap)
        tv.setOnClickListener {
            val action = kind.action?.takeIf { it.isNotBlank() } ?: return@setOnClickListener
            viewLifecycleOwner.lifecycleScope.launch(IO) {
                evalDiscoverDialogButtonClick(action, source, infoMap, kind.title, java)
            }
        }
    }

    private fun renderDiscoverDialogToggle(
        flexbox: FlexboxLayout,
        source: BookSource,
        kind: ExploreKind,
        infoMap: InfoMap,
        java: SourceLoginJsExtensions
    ) {
        val tv = createDiscoverDialogTextView(flexbox)
        flexbox.addView(tv)
        var left = true
        kind.style().apply {
            when (layout_justifySelf) {
                "flex_start" -> tv.gravity = Gravity.START
                "flex_end" -> tv.gravity = Gravity.END
                "right" -> left = false
                else -> tv.gravity = Gravity.CENTER
            }
            apply(tv)
        }
        val chars = kind.chars?.filterNotNull() ?: listOf("chars", "is null")
        var newName = kind.title
        var char = infoMap[kind.title] ?: (kind.default ?: chars.firstOrNull().orEmpty()).also {
            infoMap[kind.title] = it
        }
        fun updateText() {
            tv.text = if (left) char + newName else newName + char
        }
        val viewName = kind.viewName
        if (viewName != null && viewName.length in 3..19 && viewName.first() == '\'' && viewName.last() == '\'') {
            newName = viewName.substring(1, viewName.length - 1)
        } else if (!viewName.isNullOrBlank()) {
            viewLifecycleOwner.lifecycleScope.launch {
                val name = withContext(IO) { evalDiscoverDialogUiJs(viewName, source, infoMap) }
                if (!name.isNullOrBlank()) {
                    newName = name
                    updateText()
                }
            }
        }
        updateText()
        tv.setOnClickListener {
            val currentIndex = chars.indexOf(char)
            char = chars.getOrNull(currentIndex + 1) ?: chars.firstOrNull().orEmpty()
            infoMap[kind.title] = char
            updateText()
            val action = kind.action?.takeIf { it.isNotBlank() } ?: return@setOnClickListener
            viewLifecycleOwner.lifecycleScope.launch(IO) {
                evalDiscoverDialogButtonClick(action, source, infoMap, kind.title, java)
            }
        }
    }

    private fun renderDiscoverDialogSelect(
        flexbox: FlexboxLayout,
        source: BookSource,
        kind: ExploreKind,
        infoMap: InfoMap,
        java: SourceLoginJsExtensions
    ) {
        val binding = ItemFilletSelectorSingleBinding.inflate(layoutInflater, flexbox, false)
        val root = binding.root
        flexbox.addView(root)
        kind.style().apply {
            when (layout_justifySelf) {
                "flex_start" -> root.gravity = Gravity.START
                "flex_end" -> root.gravity = Gravity.END
                else -> root.gravity = Gravity.CENTER
            }
            apply(root)
        }
        bindDiscoverDialogKindText(binding.spName, kind, source, infoMap)
        val chars = kind.chars?.filterNotNull() ?: listOf("chars", "is null")
        val adapter = ArrayAdapter(requireContext(), R.layout.item_text_common, chars)
        adapter.setDropDownViewResource(R.layout.item_spinner_dropdown)
        binding.spType.adapter = adapter
        val char = infoMap[kind.title] ?: (kind.default ?: chars.firstOrNull().orEmpty()).also {
            infoMap[kind.title] = it
        }
        binding.spType.setSelection(chars.indexOf(char).coerceAtLeast(0))
        binding.spType.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            var initializing = true
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (initializing) {
                    initializing = false
                    return
                }
                if (position !in chars.indices) return
                infoMap[kind.title] = chars[position]
                val action = kind.action?.takeIf { it.isNotBlank() } ?: return
                viewLifecycleOwner.lifecycleScope.launch(IO) {
                    evalDiscoverDialogButtonClick(action, source, infoMap, kind.title, java)
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
    }

    private fun renderDiscoverDialogTextInput(
        flexbox: FlexboxLayout,
        source: BookSource,
        kind: ExploreKind,
        infoMap: InfoMap,
        java: SourceLoginJsExtensions
    ) {
        val input = ItemFilletCompleteTextBinding.inflate(layoutInflater, flexbox, false).root
        flexbox.addView(input)
        kind.style().apply {
            when (layout_justifySelf) {
                "center" -> input.gravity = Gravity.CENTER
                "flex_end" -> input.gravity = Gravity.END
                else -> input.gravity = Gravity.START
            }
            apply(input)
        }
        bindDiscoverDialogKindHint(input, kind, source, infoMap)
        input.setText(infoMap[kind.title])
        var actionJob: Job? = null
        input.addTextChangedListener(object : TextWatcher {
            var content: String? = null
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
                content = s?.toString()
            }

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit

            override fun afterTextChanged(s: Editable?) {
                val value = s?.toString().orEmpty()
                infoMap[kind.title] = value
                val action = kind.action?.takeIf { it.isNotBlank() } ?: return
                if (value == content) return
                actionJob?.cancel()
                actionJob = viewLifecycleOwner.lifecycleScope.launch(IO) {
                    delay(600)
                    evalDiscoverDialogButtonClick(action, source, infoMap, kind.title, java)
                    content = value
                }
            }
        })
    }

    private fun createDiscoverDialogTextView(flexbox: FlexboxLayout): TextView {
        return ItemFilletTextBinding.inflate(layoutInflater, flexbox, false).root.apply {
            typeface = requireContext().uiTypeface()
        }
    }

    private fun applyDiscoverKindTextStyle(tv: TextView, kind: ExploreKind) {
        kind.style().apply {
            when (layout_justifySelf) {
                "flex_start" -> tv.gravity = Gravity.START
                "flex_end" -> tv.gravity = Gravity.END
                else -> tv.gravity = Gravity.CENTER
            }
            apply(tv)
        }
    }

    private fun bindDiscoverDialogKindText(
        tv: TextView,
        kind: ExploreKind,
        source: BookSource,
        infoMap: InfoMap
    ) {
        val viewName = kind.viewName
        if (viewName == null) {
            tv.text = kind.title
        } else if (viewName.length in 3..19 && viewName.first() == '\'' && viewName.last() == '\'') {
            tv.text = viewName.substring(1, viewName.length - 1)
        } else {
            tv.text = kind.title
            viewLifecycleOwner.lifecycleScope.launch {
                val name = withContext(IO) { evalDiscoverDialogUiJs(viewName, source, infoMap) }
                tv.text = name ?: "err"
            }
        }
    }

    private fun bindDiscoverDialogKindHint(
        input: AutoCompleteTextView,
        kind: ExploreKind,
        source: BookSource,
        infoMap: InfoMap
    ) {
        val viewName = kind.viewName
        if (viewName == null) {
            input.hint = kind.title
        } else if (viewName.length in 3..19 && viewName.first() == '\'' && viewName.last() == '\'') {
            input.hint = viewName.substring(1, viewName.length - 1)
        } else {
            input.hint = kind.title
            viewLifecycleOwner.lifecycleScope.launch {
                val name = withContext(IO) { evalDiscoverDialogUiJs(viewName, source, infoMap) }
                input.hint = name ?: "err"
            }
        }
    }

    private suspend fun evalDiscoverDialogUiJs(
        jsStr: String,
        source: BookSource,
        infoMap: InfoMap
    ): String? {
        return try {
            runScriptWithContext {
                source.evalJS(jsStr) {
                    put("infoMap", infoMap)
                }.toString()
            }
        } catch (e: Throwable) {
            AppLog.put(source.getTag() + " exploreUi err:" + (e.localizedMessage ?: e.toString()), e)
            null
        }
    }

    private suspend fun evalDiscoverDialogButtonClick(
        jsStr: String,
        source: BookSource,
        infoMap: InfoMap,
        name: String,
        java: SourceLoginJsExtensions
    ) {
        try {
            runScriptWithContext {
                source.evalJS(normalizeDiscoverActionScript(jsStr)) {
                    put("java", java)
                    put("infoMap", infoMap)
                }
            }
        } catch (e: Throwable) {
            AppLog.put("ExploreUI Button $name JavaScript error", e)
        }
    }

    private fun normalizeDiscoverActionScript(action: String): String {
        var value = action.trim()
        if (value.startsWith("@js:", ignoreCase = true)) {
            return value.substring(4).trim()
        }
        if (value.startsWith("<js>", ignoreCase = true) && value.endsWith("</js>", ignoreCase = true)) {
            return value.substring(4, value.length - 5).trim()
        }
        value = when {
            value.startsWith("{\\{") && value.endsWith("}}") -> value.substring(3, value.length - 2)
            value.startsWith("{{") && value.endsWith("}}") -> value.substring(2, value.length - 2)
            value.startsWith("{") && value.endsWith("}") && isDiscoverExecutableObjectScript(value) ->
                value.substring(1, value.length - 1)
            else -> value
        }
        return value.trim()
    }

    private fun isDiscoverExecutableObjectScript(value: String): Boolean {
        return value.contains("java.", ignoreCase = true) ||
            value.contains("source.", ignoreCase = true) ||
            value.contains("infoMap", ignoreCase = true)
    }

    private fun selectDiscoverSource(source: BookSourcePart) {
        selectedDiscoverSourcePart = source
        AppConfig.modernDiscoverySourceUrl = source.bookSourceUrl
        updateDiscoverLoginButtonState()
        tagFilterPopup?.dismiss()
        tagFilterPopup = null
        discoverSourceVersion += 1
        val currentSourceVersion = discoverSourceVersion
        discoverRequestVersion += 1
        discoverLoadJob?.cancel()
        discoverLoadJob = null
        discoverLoading = false
        clearDiscoverLoading()
        discoverCurrentUrl = null
        discoverBooks.clear()
        discoverBookAdapter.clearItems()
        binding.tvDiscoverEmpty.gone()
        discoverAllTagItems.clear()
        discoverMajorGroups.clear()
        discoverSelectItems.clear()
        discoverSettingItems.clear()
        selectedDiscoverMajorGroup = null
        renderDiscoverTags(emptyList(), -1)
        renderDiscoverMajorGroups()
        updateDiscoverTagFilterButtonState()
        viewLifecycleOwner.lifecycleScope.launch {
            val loadingGeneration = showDiscoverLoading()
            try {
                val fullSource = withContext(IO) {
                    appDb.bookSourceDao.getBookSource(source.bookSourceUrl)
                }
                if (currentSourceVersion != discoverSourceVersion || !isAdded) {
                    return@launch
                }
                selectedDiscoverSource = fullSource
                updateDiscoverSourceTitle()
                updateDiscoverSearchButtonState()
                loadDiscoverKindsAndDefault()
            } finally {
                if (isAdded && currentSourceVersion == discoverSourceVersion) {
                    hideDiscoverLoading(loadingGeneration)
                }
            }
        }
    }

    private fun updateDiscoverSourceTitle() {
        val name = selectedDiscoverSourcePart?.bookSourceName
            ?: getString(R.string.discovery)
        binding.tvDiscoverSourceSelect.text = name
        binding.llDiscoverSourceRow.post(::updateDiscoverSourceNameWidth)
    }

    private suspend fun loadDiscoverKindsAndDefault() {
        val source = selectedDiscoverSource ?: return
        val kinds = withContext(IO) {
            source.exploreKinds()
        }
        val items = buildDiscoverTagItems(source, kinds)
        discoverAllTagItems.clear()
        discoverAllTagItems.addAll(items)
        if (items.isEmpty()) {
            discoverMajorGroups.clear()
            selectedDiscoverMajorGroup = null
            renderDiscoverTags(emptyList(), -1)
            renderDiscoverMajorGroups()
            updateDiscoverTagFilterButtonState()
            clearDiscoverBooksToEmpty(getString(R.string.explore_empty))
            return
        }
        applyDiscoverTagFilterAndSelect(preferredUrl = discoverCurrentUrl)
    }

    private fun buildDiscoverTagItems(
        source: BookSource,
        kinds: List<ExploreKind>
    ): List<DiscoverTagItem> {
        val blocked = blockedButtonActions[source.bookSourceUrl]
        var currentGroup: String? = null
        var unnamedGroupIndex = 0
        val usedGroupNames = mutableSetOf<String>()
        val result = mutableListOf<DiscoverTagItem>()
        kinds.forEach { kind ->
            val action = kind.action?.takeIf { it.isNotBlank() }
            val url = kind.url?.takeIf { it.isNotBlank() }
            val isSelect = kind.type == ExploreKind.Type.select
            val isButton = kind.type == ExploreKind.Type.button && !action.isNullOrBlank()

            if (isDiscoverMajorGroupKind(kind, currentGroup != null)) {
                currentGroup = if (isDiscoverAbnormalGroupTitle(kind)) {
                    var candidate: String
                    do {
                        unnamedGroupIndex++
                        candidate = getString(R.string.discover_group_unnamed, unnamedGroupIndex)
                    } while (usedGroupNames.contains(candidate))
                    candidate
                } else {
                    resolveDiscoverGroupTitle(kind)
                }
                usedGroupNames.add(currentGroup)
                if (!url.isNullOrBlank()) {
                    result += DiscoverTagItem(
                        kind = kind.copy(title = getString(R.string.all), viewName = null, url = url),
                        text = getString(R.string.all),
                        isButton = false,
                        group = currentGroup
                    )
                }
                return@forEach
            }

            if (kind.type == ExploreKind.Type.text) {
                result += DiscoverTagItem(
                    kind = kind.copy(type = ExploreKind.Type.text),
                    text = resolveDiscoverTagText(kind),
                    isButton = false,
                    group = currentGroup
                )
                return@forEach
            }

            if (!url.isNullOrBlank() && !isButton && !isSelect) {
                result += DiscoverTagItem(
                    kind = kind.copy(url = url),
                    text = resolveDiscoverTagText(kind),
                    isButton = false,
                    group = currentGroup
                )
                return@forEach
            }

            if (isSelect) {
                result += DiscoverTagItem(
                    kind = kind.copy(type = ExploreKind.Type.select),
                    text = resolveDiscoverTagText(kind),
                    isButton = false,
                    group = currentGroup
                )
                return@forEach
            }

            if (!action.isNullOrBlank()) {
                if (blocked?.contains(action) == true) return@forEach
                result += DiscoverTagItem(
                    kind = kind.copy(type = ExploreKind.Type.button),
                    text = resolveDiscoverTagText(kind),
                    isButton = true,
                    group = currentGroup
                )
            }
        }
        val hasMajorGroup = result.any { !it.group.isNullOrBlank() }
        val normalized = if (hasMajorGroup) {
            result
        } else {
            result.map { it.copy(group = getString(R.string.discover_group_other)) }
        }
        return normalized.distinctBy { "${it.group}|${it.kind.type}|${it.kind.title}|${it.kind.url}|${it.kind.action}" }
    }

    private fun isDiscoverMajorGroupKind(
        kind: ExploreKind,
        hasStartedGroup: Boolean
    ): Boolean {
        if (!kind.action.isNullOrBlank()) return false
        if (kind.type == ExploreKind.Type.button || kind.type == ExploreKind.Type.select) return false
        if (!kind.url.isNullOrBlank() && !hasStartedGroup) return false
        return isDiscoverFullLineKind(kind)
    }

    private fun isDiscoverAbnormalGroupTitle(kind: ExploreKind): Boolean {
        val raw = resolveDiscoverTagText(kind).trim()
        if (raw.isBlank()) return true
        val normalized = normalizeDiscoverGroupTitle(raw)
        if (normalized.isBlank()) return true
        return normalized.length >= 8
    }

    private fun normalizeDiscoverGroupTitle(raw: String): String {
        return raw
            .replace(Regex("^[^\\p{L}\\p{N}]+|[^\\p{L}\\p{N}]+$"), "")
            .replace(Regex("\\s{2,}"), " ")
            .trim()
    }

    private fun isDiscoverFullLineKind(kind: ExploreKind): Boolean {
        val style = kind.style()
        if (style.layout_flexBasisPercent >= 0.95f) return true
        if (style.layout_flexGrow >= 1f && style.layout_flexBasisPercent < 0f) return true
        return false
    }

    private fun resolveDiscoverGroupTitle(kind: ExploreKind): String {
        val raw = resolveDiscoverTagText(kind).trim()
        if (raw.isBlank()) return getString(R.string.discovery)
        val normalized = normalizeDiscoverGroupTitle(raw)
        return normalized.ifBlank { raw }
    }

    private fun resolveDiscoverTagText(kind: ExploreKind): String {
        val viewName = kind.viewName
        if (!viewName.isNullOrBlank()
            && viewName.length in 3..28
            && viewName.first() == '\''
            && viewName.last() == '\''
        ) {
            return viewName.substring(1, viewName.length - 1)
        }
        return kind.title.ifBlank { kind.type }
    }

    private fun applyDiscoverTagFilterAndSelect(preferredUrl: String?) {
        val groupList = discoverAllTagItems
            .mapNotNull { it.group?.takeIf { name -> name.isNotBlank() } }
            .distinct()
        discoverMajorGroups.clear()
        discoverMajorGroups.addAll(groupList)

        if (discoverMajorGroups.isEmpty()) {
            selectedDiscoverMajorGroup = null
        } else {
            if (selectedDiscoverMajorGroup !in discoverMajorGroups) {
                selectedDiscoverMajorGroup = discoverMajorGroups.first()
            }
        }

        val filtered = if (discoverMajorGroups.isEmpty()) {
            discoverAllTagItems.toList()
        } else {
            discoverAllTagItems.filter { it.group == selectedDiscoverMajorGroup }
        }

        discoverSettingItems.clear()
        discoverSettingItems.addAll(buildDiscoverSettingItems())
        renderDiscoverMajorGroups()
        updateDiscoverTagFilterButtonState()
        val tagItems = filtered.filter { it.kind.type != ExploreKind.Type.select && !it.isButton }
        val targetIndexByUrl = preferredUrl
            ?.takeIf { it.isNotBlank() }
            ?.let { url ->
                tagItems.indexOfFirst { !it.isButton && it.kind.url == url }
                    .takeIf { idx -> idx >= 0 }
            }
        val targetIndex = targetIndexByUrl
            ?: tagItems.indexOfFirst { !it.isButton && !it.kind.url.isNullOrBlank() }
        renderDiscoverTags(tagItems, targetIndex)
        if (targetIndex >= 0) {
            selectDiscoverTag(targetIndex, tagItems[targetIndex], selectTab = true)
        } else {
            clearDiscoverBooksToEmpty(getString(R.string.explore_empty))
        }
    }

    private fun updateDiscoverTagFilterButtonState() {
        val enabled = discoverSettingItems.isNotEmpty()
        binding.btnDiscoverTagFilter.isVisible = enabled
        binding.btnDiscoverTagFilter.isEnabled = enabled
        binding.btnDiscoverTagFilter.alpha = if (enabled) 1f else 0.45f
    }

    private fun buildDiscoverSettingItems(): List<DiscoverTagItem> {
        val hasMajorGroup = discoverMajorGroups.isNotEmpty()
        return discoverAllTagItems.filter {
            it.kind.type == ExploreKind.Type.select
                || it.kind.type == ExploreKind.Type.text
                || it.isButton
                || (hasMajorGroup && it.group.isNullOrBlank())
        }
    }

    private fun showDiscoverSettingsDialog() {
        if (discoverSettingItems.isEmpty()) return
        val itemMap = discoverSettingItems.associateBy { it.toDiscoverRowUi().name }
        RowUiDialog.show(
            requireContext(),
            RowUiDialog.Config(
                title = getString(R.string.discovery_settings_title),
                rows = discoverSettingItems.map { it.toDiscoverRowUi() },
                values = discoverSettingItems.associate {
                    it.toDiscoverRowUi().name to currentDiscoverSelectValue(it)
                },
                dismissOnAction = true,
                dismissOnSelect = true,
                dismissOnToggle = false
            ),
            object : RowUiDialog.Callback {
                override fun onValueChanged(rowUi: RowUi, value: String) {
                    val item = itemMap[rowUi.name] ?: return
                    when (rowUi.type) {
                        RowUi.Type.select -> handleDiscoverSelectValue(item, value)
                        RowUi.Type.text -> handleDiscoverTextValue(item, value)
                    }
                }

                override fun onAction(rowUi: RowUi, isLongClick: Boolean) {
                    val item = itemMap[rowUi.name] ?: return
                    if (item.isButton) {
                        handleDiscoverButtonTag(item)
                    } else {
                        selectDiscoverSettingUrl(item)
                    }
                }
            }
        )
    }

    private fun DiscoverTagItem.toDiscoverRowUi(): RowUi {
        val type = when {
            kind.type == ExploreKind.Type.select -> RowUi.Type.select
            kind.type == ExploreKind.Type.text -> RowUi.Type.text
            isButton || isDefaultUrlKind -> RowUi.Type.button
            else -> RowUi.Type.text
        }
        return RowUi(
            name = if (type == RowUi.Type.select) kind.title else text,
            type = type,
            action = kind.action,
            chars = kind.chars,
            default = kind.default,
            viewName = kind.viewName,
            style = kind.style
        )
    }

    private val DiscoverTagItem.isDefaultUrlKind: Boolean
        get() = kind.type == ExploreKind.Type.url && !kind.url.isNullOrBlank()

    private fun selectDiscoverSettingUrl(item: DiscoverTagItem) {
        val url = item.kind.url?.takeIf { it.isNotBlank() } ?: return
        if (executeDiscoverUrlScriptIfNeeded(item, url)) {
            return
        }
        selectedDiscoverTagIndex = -1
        selectedDiscoverUrlIndex = -1
        binding.rvDiscoverTags.setSelectedIndex(-1, smooth = false)
        if (discoverCurrentUrl == url && discoverBooks.isNotEmpty()) {
            return
        }
        discoverCurrentUrl = url
        loadDiscoverBooks(reset = true)
    }

    private fun renderDiscoverTags(items: List<DiscoverTagItem>, selectedIndex: Int) {
        discoverTagItems.clear()
        discoverTagItems.addAll(items)
        selectedDiscoverTagIndex = selectedIndex.coerceIn(-1, items.lastIndex)
        selectedDiscoverUrlIndex = if (selectedDiscoverTagIndex in items.indices && !items[selectedDiscoverTagIndex].isButton) {
            selectedDiscoverTagIndex
        } else {
            items.indexOfFirst { !it.isButton && !it.kind.url.isNullOrBlank() }
        }
        binding.rvDiscoverTags.submitItems(
            items.map { RoundedTagBarView.Item(it.text, if (it.isButton) 0.9f else 1f, showFullText = true) },
            selectedDiscoverTagIndex
        )
        binding.btnDiscoverTagsExpand.isVisible = items.size >= ExpandableTagSelector.EXPAND_THRESHOLD
    }

    private fun renderDiscoverMajorGroups() {
        discoverSelectItems.clear()
        if (discoverMajorGroups.isEmpty()) {
            binding.llDiscoverSelectsBar.gone()
            binding.rvDiscoverSelects.submitItems(emptyList(), -1)
            return
        }
        binding.llDiscoverSelectsBar.visible()
        binding.rvDiscoverSelects.submitItems(
            discoverMajorGroups.map { RoundedTagBarView.Item(it, 1f, showFullText = true) },
            discoverMajorGroups.indexOf(selectedDiscoverMajorGroup)
        )
        binding.btnDiscoverSelectsExpand.isVisible =
            discoverMajorGroups.size >= ExpandableTagSelector.EXPAND_THRESHOLD
    }

    private fun showDiscoverTagsSelector() {
        if (discoverTagItems.size < ExpandableTagSelector.EXPAND_THRESHOLD) return
        ExpandableTagSelector.show(
            context = requireContext(),
            title = getString(R.string.select),
            items = discoverTagItems.mapIndexed { index, item ->
                ExpandableTagSelector.GridItem(
                    text = item.text,
                    selected = index == selectedDiscoverTagIndex,
                    value = index
                )
            }
        ) onSelected@{ index ->
            val item = discoverTagItems.getOrNull(index) ?: return@onSelected
            if (item.isButton) {
                handleDiscoverButtonTag(item)
                return@onSelected
            }
            selectDiscoverTag(index, item, selectTab = true)
        }
    }

    private fun showDiscoverMajorGroupsSelector() {
        if (discoverMajorGroups.size < ExpandableTagSelector.EXPAND_THRESHOLD) return
        val selectedIndex = discoverMajorGroups.indexOf(selectedDiscoverMajorGroup)
        ExpandableTagSelector.show(
            context = requireContext(),
            title = getString(R.string.select),
            items = discoverMajorGroups.mapIndexed { index, group ->
                ExpandableTagSelector.GridItem(
                    text = group,
                    selected = index == selectedIndex,
                    value = index
                )
            }
        ) onSelected@{ index ->
            val group = discoverMajorGroups.getOrNull(index) ?: return@onSelected
            selectedDiscoverMajorGroup = group
            applyDiscoverTagFilterAndSelect(preferredUrl = discoverCurrentUrl)
        }
    }

    private fun currentDiscoverSelectValue(item: DiscoverTagItem): String {
        val source = selectedDiscoverSource ?: return item.kind.default ?: ""
        val key = item.kind.title
        if (key.isBlank()) return item.kind.default ?: ""
        val info = getDiscoverInfoMap(source.bookSourceUrl)
        return info[key]?.takeIf { it.isNotBlank() }
            ?: item.kind.default?.takeIf { it.isNotBlank() }
            ?: item.kind.chars?.firstOrNull()?.orEmpty()
            ?: ""
    }

    private fun handleDiscoverSelectValue(item: DiscoverTagItem, value: String) {
        val source = selectedDiscoverSource ?: return
        val key = item.kind.title
        if (key.isBlank()) return
        val infoMap = getDiscoverInfoMap(source.bookSourceUrl)
        infoMap[key] = value
        val refreshController = DiscoverRefreshController()
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                withContext(IO) {
                    source.clearExploreKindsCache()
                    val action = item.kind.action?.takeIf { it.isNotBlank() }
                    if (!action.isNullOrBlank()) {
                        runScriptWithContext {
                            source.evalJS(action) {
                                put(
                                    "java",
                                    discoverJsExtensions(source, refreshController)
                                )
                                put("infoMap", infoMap)
                            }
                        }
                    }
                }
                loadDiscoverKindsAndDefault()
            } finally {
                refreshController.finish()
            }
        }
    }

    private fun handleDiscoverTextValue(item: DiscoverTagItem, value: String) {
        val source = selectedDiscoverSource ?: return
        val key = item.kind.title
        if (key.isBlank()) return
        val infoMap = getDiscoverInfoMap(source.bookSourceUrl)
        infoMap[key] = value
        val action = item.kind.action?.takeIf { it.isNotBlank() } ?: return
        discoverTextActionJobs.remove(key)?.cancel()
        val job = viewLifecycleOwner.lifecycleScope.launch {
            delay(600)
            val refreshController = DiscoverRefreshController()
            try {
                withContext(IO) {
                    runScriptWithContext {
                        source.evalJS(action) {
                            put(
                                "java",
                                discoverJsExtensions(source, refreshController)
                            )
                            put("infoMap", infoMap)
                        }
                    }
                }
                if (refreshController.requested && isAdded) {
                    withContext(IO) {
                        source.clearExploreKindsCache()
                    }
                    loadDiscoverKindsAndDefault()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.put("发现文本输入执行失败: ${item.text}", e)
                context?.toastOnUi(e.localizedMessage ?: getString(R.string.unknown_error))
            } finally {
                refreshController.finish()
                if (discoverTextActionJobs[key] === coroutineContext[Job]) {
                    discoverTextActionJobs.remove(key)
                }
            }
        }
        discoverTextActionJobs[key] = job
    }

    private fun selectDiscoverTabByCode(index: Int, smooth: Boolean) {
        if (index !in discoverTagItems.indices) return
        binding.rvDiscoverTags.setSelectedIndex(index, smooth)
    }

    private fun selectDiscoverTag(index: Int, item: DiscoverTagItem, selectTab: Boolean) {
        val url = item.kind.url?.takeIf { it.isNotBlank() } ?: return
        if (executeDiscoverUrlScriptIfNeeded(item, url)) {
            return
        }
        selectedDiscoverTagIndex = index
        selectedDiscoverUrlIndex = index
        if (selectTab) {
            selectDiscoverTabByCode(index, smooth = true)
        }
        if (discoverCurrentUrl == url && discoverBooks.isNotEmpty()) {
            return
        }
        discoverCurrentUrl = url
        loadDiscoverBooks(reset = true)
    }

    private fun executeDiscoverUrlScriptIfNeeded(item: DiscoverTagItem, url: String): Boolean {
        val script = extractDiscoverUrlScript(url) ?: return false
        val source = selectedDiscoverSource ?: return true
        val infoMap = getDiscoverInfoMap(source.bookSourceUrl)
        val refreshController = DiscoverRefreshController()
        discoverActionJob?.cancel()
        discoverActionJob = viewLifecycleOwner.lifecycleScope.launch {
            try {
            val result = withContext(IO) {
                kotlin.runCatching {
                    runScriptWithContext {
                        source.evalJS(script) {
                            put(
                                "java",
                                discoverJsExtensions(source, refreshController)
                            )
                            put("infoMap", infoMap)
                        }
                    }
                }
            }
            if (refreshController.requested && isAdded) {
                withContext(IO) {
                    source.clearExploreKindsCache()
                }
                loadDiscoverKindsAndDefault()
            }
            result.onFailure {
                AppLog.put("发现 URL 脚本执行失败: ${item.text}", it)
                context?.toastOnUi(it.localizedMessage ?: getString(R.string.unknown_error))
            }
            } finally {
                refreshController.finish()
            }
        }
        return true
    }

    private fun extractDiscoverUrlScript(url: String): String? {
        val trimmed = url.trim()
        return when {
            trimmed.startsWith("{{") && trimmed.endsWith("}}") -> {
                trimmed.substring(2, trimmed.length - 2).trim()
            }
            trimmed.startsWith("{\\{") && trimmed.endsWith("}}") -> {
                trimmed.substring(3, trimmed.length - 2).trim()
            }
            else -> null
        }?.takeIf { it.isNotBlank() }
    }

    private fun handleDiscoverButtonTag(item: DiscoverTagItem) {
        val source = selectedDiscoverSource ?: return
        val action = item.kind.action?.takeIf { it.isNotBlank() } ?: return
        val infoMap = getDiscoverInfoMap(source.bookSourceUrl)
        val actionLower = action.lowercase()
        val isNavigationAction = actionLower.contains("showbrowser(")
            || actionLower.contains("open(\"explore\"")
            || actionLower.contains("open('explore'")
        val refreshController = DiscoverRefreshController()
        discoverActionJob?.cancel()
        discoverActionJob = viewLifecycleOwner.lifecycleScope.launch {
            try {
            val result = withContext(IO) {
                kotlin.runCatching {
                    var handledByAction = false
                    val java = discoverJsExtensions(
                        source,
                        refreshController
                    ) { name, url, title, origin ->
                            if (!isAdded) return@discoverJsExtensions false
                            if (name != "explore") return@discoverJsExtensions false
                            handledByAction = true
                            val targetUrl = url?.takeIf { it.isNotBlank() } ?: return@discoverJsExtensions true
                            val targetSourceUrl = origin
                                ?.takeIf { it.isNotBlank() }
                                ?: selectedDiscoverSource?.bookSourceUrl
                                ?: source.bookSourceUrl
                            val targetTitle = title ?: item.text
                            binding.root.post {
                                openExplore(targetSourceUrl, targetTitle, targetUrl)
                            }
                            true
                    }
                    runScriptWithContext {
                        source.evalJS(action) {
                            put("java", java)
                            put("infoMap", infoMap)
                        }
                    }
                    when {
                        handledByAction || isNavigationAction -> null
                        else -> {
                            source.clearExploreKindsCache()
                            source.exploreKinds()
                        }
                    }
                }
            }
            if (!isAdded) return@launch
            result.onSuccess { kinds ->
                if (kinds == null) {
                    return@onSuccess
                }
                applyDiscoverButtonResult(source, action, kinds)
            }.onFailure {
                AppLog.put("发现标签按钮执行失败", it)
                context?.toastOnUi(it.localizedMessage ?: getString(R.string.unknown_error))
            }
            } finally {
                refreshController.finish()
            }
        }
    }

    private fun applyDiscoverButtonResult(
        source: BookSource,
        action: String,
        kinds: List<ExploreKind>
    ) {
        val items = buildDiscoverTagItems(source, kinds)
        val firstUrlIndex = items.indexOfFirst { !it.isButton && !it.kind.url.isNullOrBlank() }
        if (firstUrlIndex >= 0) {
            discoverAllTagItems.clear()
            discoverAllTagItems.addAll(items)
            applyDiscoverTagFilterAndSelect(preferredUrl = discoverCurrentUrl)
            return
        }
        if (items.isNotEmpty()) {
            discoverAllTagItems.clear()
            discoverAllTagItems.addAll(items)
            applyDiscoverTagFilterAndSelect(preferredUrl = discoverCurrentUrl)
        }
        context?.toastOnUi("该按钮未返回可用列表，保留当前标签")
    }

    private fun getDiscoverInfoMap(sourceUrl: String): InfoMap {
        return ExploreAdapter.exploreInfoMapList[sourceUrl] ?: InfoMap(sourceUrl).also {
            ExploreAdapter.exploreInfoMapList.put(sourceUrl, it)
        }
    }

    private fun clearDiscoverBooksToEmpty(message: String) {
        discoverRequestVersion += 1
        discoverLoadJob?.cancel()
        discoverLoadJob = null
        discoverLoading = false
        binding.swipeRefreshLayout.isRefreshing = false
        clearDiscoverLoading()
        discoverCurrentUrl = null
        discoverHasMore = false
        discoverPage = 1
        discoverBooks.clear()
        discoverBookAdapter.clearItems()
        binding.tvDiscoverEmpty.text = message
        binding.tvDiscoverEmpty.visible()
    }

    private fun loadDiscoverBooks(reset: Boolean) {
        if (!usingModernDiscovery) return
        val source = selectedDiscoverSource ?: return
        val url = discoverCurrentUrl?.takeIf { it.isNotBlank() } ?: return
        if (!reset && !discoverHasMore) return
        if (reset) {
            discoverLoadJob?.cancel()
        } else if (discoverLoading) {
            return
        }
        val requestVersion = if (reset) {
            discoverRequestVersion += 1
            discoverRequestVersion
        } else {
            discoverRequestVersion
        }
        discoverLoadJob = viewLifecycleOwner.lifecycleScope.launch {
            if (reset) {
                discoverPage = 1
                discoverHasMore = true
                discoverBooks.clear()
                discoverBookAdapter.clearItems()
                binding.tvDiscoverEmpty.gone()
            }
            discoverLoading = true
            val loadingGeneration = showDiscoverLoading()
            try {
                val newBooks = withContext(IO) {
                    WebBook.exploreBookAwait(source, url, discoverPage)
                }
                if (!isAdded || requestVersion != discoverRequestVersion || url != discoverCurrentUrl) {
                    return@launch
                }
                if (newBooks.isEmpty()) {
                    discoverHasMore = false
                    if (discoverBooks.isEmpty()) {
                        binding.tvDiscoverEmpty.text = getString(R.string.explore_empty)
                        binding.tvDiscoverEmpty.visible()
                    }
                } else {
                    withContext(IO) {
                        appDb.searchBookDao.insert(*newBooks.toTypedArray())
                    }
                    discoverPage += 1
                    discoverBooks.addAll(newBooks)
                    discoverBookAdapter.setItems(discoverBooks.toList())
                    binding.tvDiscoverEmpty.gone()
                }
            } catch (_: CancellationException) {
                return@launch
            } catch (e: Throwable) {
                if (!isAdded || requestVersion != discoverRequestVersion || url != discoverCurrentUrl) {
                    return@launch
                }
                AppLog.put("新版发现页加载失败", e)
                if (discoverBooks.isEmpty()) {
                    binding.tvDiscoverEmpty.text = e.localizedMessage ?: getString(R.string.unknown_error)
                    binding.tvDiscoverEmpty.visible()
                }
            } finally {
                if (isAdded) {
                    hideDiscoverLoading(loadingGeneration)
                }
                if (isAdded && requestVersion == discoverRequestVersion && url == discoverCurrentUrl) {
                    binding.swipeRefreshLayout.isRefreshing = false
                    discoverLoading = false
                }
            }
        }
    }

    private fun initRecyclerView() {
        binding.rvFind.setEdgeEffectColor(primaryColor)
        binding.rvFind.layoutManager = linearLayoutManager
        binding.rvFind.adapter = adapter
        adapter.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {

            override fun onItemRangeInserted(positionStart: Int, itemCount: Int) {
                super.onItemRangeInserted(positionStart, itemCount)
                if (positionStart == 0) {
                    binding.rvFind.scrollToPosition(0)
                }
            }
        })
    }

    private fun initGroupData() {
        viewLifecycleOwner.lifecycleScope.launch {
            appDb.bookSourceDao.flowExploreGroups()
                .flowWithLifecycleAndDatabaseChange(
                    viewLifecycleOwner.lifecycle,
                    Lifecycle.State.RESUMED,
                    AppDatabase.BOOK_SOURCE_TABLE_NAME
                )
                .conflate()
                .distinctUntilChanged()
                .collect {
                    groups.clear()
                    groups.addAll(it)
                    upGroupsMenu()
                    delay(500)
                }
        }
    }

    private fun upExploreData(searchKey: String? = null) {
        exploreFlowJob?.cancel()
        exploreFlowJob = viewLifecycleOwner.lifecycleScope.launch {
            when {
                searchKey.isNullOrBlank() -> {
                    appDb.bookSourceDao.flowExplore()
                }

                searchKey.startsWith("group:") -> {
                    val key = searchKey.substringAfter("group:")
                    appDb.bookSourceDao.flowGroupExplore(key)
                }

                else -> {
                    appDb.bookSourceDao.flowExplore(searchKey)
                }
            }.flowWithLifecycleAndDatabaseChange(
                viewLifecycleOwner.lifecycle,
                Lifecycle.State.RESUMED,
                AppDatabase.BOOK_SOURCE_TABLE_NAME
            ).distinctUntilChanged(::areBookSourcePartListsSame).catch {
                AppLog.put("发现界面更新数据出错", it)
            }.conflate().flowOn(IO).collect {
                binding.swipeRefreshLayout.isRefreshing = false
                binding.tvEmptyMsg.isGone = it.isNotEmpty() || (searchView?.query?.isNotEmpty() == true)
                adapter.setItems(it, diffItemCallBack)
                delay(500)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (usingModernDiscovery != AppConfig.modernDiscoveryPage || !discoveryModeLoaded) {
            applyDiscoveryMode(loadData = true)
            discoveryModeLoaded = true
        }
        if (!usingModernDiscovery) {
            adapter.upResumed(true)
        }
    }

    override fun onPause() {
        if (!usingModernDiscovery) {
            adapter.upResumed(false)
            searchView?.clearFocus()
            adapter.onPause()
        }
        super.onPause()
    }

    override fun onDestroyView() {
        stopModernMode()
        oldModeInitialized = false
        modernModeInitialized = false
        groupsMenu = null
        super.onDestroyView()
    }

    private fun upGroupsMenu() = groupsMenu?.transaction { subMenu ->
        subMenu.removeGroup(R.id.menu_group_text)
        groups.forEach {
            subMenu.add(R.id.menu_group_text, Menu.NONE, Menu.NONE, it)
        }
    }

    override val scope: CoroutineScope
        get() = viewLifecycleOwner.lifecycleScope

    override fun onCompatOptionsItemSelected(item: MenuItem) {
        super.onCompatOptionsItemSelected(item)
        if (usingModernDiscovery) return
        if (item.itemId == R.id.menu_reload_book_source) {
            adapter.reloadExplore()
            upExploreData(searchView?.query?.toString())
            return
        }
        if (item.groupId == R.id.menu_group_text) {
            searchView?.setQuery("group:${item.title}", true) ?: upExploreData("group:${item.title}")
        }
    }

    override fun scrollTo(pos: Int) {
        (binding.rvFind.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(pos, 0)
    }

    override fun openExplore(sourceUrl: String, title: String, exploreUrl: String?) {
        if (exploreUrl.isNullOrBlank()) return
        startActivity<ExploreShowActivity> {
            putExtra("exploreName", title)
            putExtra("sourceUrl", sourceUrl)
            putExtra("exploreUrl", exploreUrl)
        }
    }

    override fun editSource(sourceUrl: String) {
        startActivity<BookSourceEditActivity> {
            putExtra("sourceUrl", sourceUrl)
        }
    }

    override fun toTop(source: BookSourcePart) {
        viewModel.topSource(source)
    }

    override fun deleteSource(source: BookSourcePart) {
        alert(R.string.draw) {
            setMessage(getString(R.string.sure_del) + "\n" + source.bookSourceName)
            noButton()
            yesButton {
                viewModel.deleteSource(source)
            }
        }
    }

    override fun searchBook(bookSource: BookSourcePart) {
        SearchActivity.start(requireContext(), bookSource)
    }

    override fun isInBookshelf(book: SearchBook): Boolean {
        val key = if (book.author.isNotBlank()) "${book.name}-${book.author}" else book.name
        return discoverBookshelf.contains(key) || discoverBookshelf.contains(book.bookUrl)
    }

    override fun showBookInfo(book: SearchBook) {
        viewLifecycleOwner.lifecycleScope.launch {
            withContext(IO) {
                appDb.searchBookDao.insert(book)
            }
            val isVideo = withContext(IO) {
                SearchBookOpenHelper.isVideoResult(
                    book,
                    selectedDiscoverSourcePart?.bookSourceType ?: selectedDiscoverSource?.bookSourceType
                )
            }
            SearchBookOpenHelper.open(requireContext(), book, isVideo)
        }
    }

    fun compressExplore() {
        if (usingModernDiscovery) {
            if (binding.rvDiscoverBooks.canScrollVertically(-1)) {
                if (AppConfig.isEInkMode) {
                    binding.rvDiscoverBooks.scrollToPosition(0)
                } else {
                    binding.rvDiscoverBooks.smoothScrollToPosition(0)
                }
            }
            return
        }
        if (!adapter.compressExplore()) {
            if (AppConfig.isEInkMode) {
                binding.rvFind.scrollToPosition(0)
            } else {
                binding.rvFind.smoothScrollToPosition(0)
            }
        }
    }

}

private fun String.limitDiscoverText(max: Int): String {
    return if (length <= max) this else "${take(max.coerceAtLeast(2) - 1)}…"
}
