@file:Suppress("DEPRECATION")

package io.legado.app.ui.main.bookshelf.style1

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.view.doOnPreDraw
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.FragmentStatePagerAdapter
import io.legado.app.R
import io.legado.app.constant.BookType
import io.legado.app.constant.EventBus
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookGroup
import io.legado.app.databinding.FragmentBookshelf1Binding
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.theme.UiCorner
import io.legado.app.lib.theme.applyUiTitleTypeface
import io.legado.app.lib.theme.primaryColor
import io.legado.app.ui.book.search.SearchActivity
import io.legado.app.ui.main.bookshelf.BaseBookshelfFragment
import io.legado.app.ui.main.bookshelf.style1.books.BooksFragment
import io.legado.app.ui.main.MainActivity
import io.legado.app.ui.widget.ExpandableTagSelector
import io.legado.app.ui.widget.RoundedTagBarView
import io.legado.app.utils.PopupMenuAction
import io.legado.app.utils.applyStatusBarPadding
import io.legado.app.utils.isCreated
import io.legado.app.utils.observeEvent
import io.legado.app.utils.setEdgeEffectColor
import io.legado.app.utils.showPopupMenu
import io.legado.app.utils.startActivity
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding

class BookshelfFragment1() : BaseBookshelfFragment(R.layout.fragment_bookshelf1) {

    constructor(position: Int) : this() {
        val bundle = Bundle()
        bundle.putInt("position", position)
        arguments = bundle
    }

    private val binding by viewBinding(FragmentBookshelf1Binding::bind)
    private val adapter by lazy { TabFragmentPageAdapter(childFragmentManager) }
    private val primaryGroups = mutableListOf<BookGroup>()
    private val secondaryGroups = mutableListOf<BookGroup>()
    private val fragmentMap = hashMapOf<Long, BooksFragment>()
    private var secondaryGroupIds = emptyList<Long>()
    private var selectedSecondaryGroupId = 0L
    private val groupBooksCache = hashMapOf<Long, List<Book>>()
    private var currentGroupIndex = 0

    override val groupId: Long get() = selectedPrimaryGroup?.groupId ?: BookGroup.IdPrimaryAll

    override val books: List<Book>
        get() = fragmentMap[selectedSecondaryGroupId]?.getBooks() ?: emptyList()

    override var onlyUpdateRead = false

    private val selectedPrimaryGroup: BookGroup?
        get() = primaryGroups.getOrNull(currentGroupIndex)

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        initView()
        initBookGroupData()
    }

    private fun initView() {
        binding.root.applyStatusBarPadding()
        binding.viewPagerBookshelf.setEdgeEffectColor(primaryColor)
        binding.btnMore.setOnClickListener {
            showBookshelfMenu(it)
        }
        updateSearchButtonVisibility()
        binding.btnSearch.setOnClickListener {
            startActivity<SearchActivity>()
        }
        binding.llTitleSelect.setOnClickListener {
            showGroupSwitchMenu(it)
        }
        binding.tabBarGlassView.visibility = View.VISIBLE
        binding.tabBarShellOverlay.visibility = View.VISIBLE
        // 分组标签自身负责选中态。这里不再叠加固定在最左侧的独立指示器，
        // 避免与标签选中背景重复绘制，导致第一项异常发白且切换后指示器错位。
        binding.tabIndicatorContainer.visibility = View.GONE
        binding.btnMoreGlassView.visibility = View.GONE
        binding.btnMoreShellOverlay.visibility = View.GONE
        binding.btnMore.setBackgroundResource(R.drawable.bg_more_icon_button_clear)
        val iconColor = ContextCompat.getColor(requireContext(), R.color.primaryText)
        binding.btnMore.setColorFilter(iconColor)
        binding.ivBookshelfTitleArrow.setColorFilter(iconColor)
        ExpandableTagSelector.configureExpandButton(binding.btnSecondaryTagsExpand)
        binding.btnSecondaryTagsExpand.setOnClickListener {
            showSecondaryGroupSelector()
        }
        binding.btnSelectAll.setOnClickListener {
            fragmentMap[selectedSecondaryGroupId]?.toggleSelectAll()
        }
        binding.btnShortcut.setOnClickListener {
            fragmentMap[selectedSecondaryGroupId]?.createShortcuts()
        }
        binding.btnFusion.setOnClickListener {
            fragmentMap[selectedSecondaryGroupId]?.fuseSelectedBooks()
        }
        binding.tabLayout.setOnTagClickListener { index ->
            val secondaryGroupId = secondaryGroupIds.getOrNull(index) ?: BookGroup.IdAll
            if (secondaryGroupId == selectedSecondaryGroupId) {
                selectedPrimaryGroup?.let { group ->
                    fragmentMap[secondaryGroupId]?.let { fragment ->
                        val label = secondaryGroupName(secondaryGroupId)
                        toastOnUi("${group.groupName} / $label(${fragment.getBooksCount()})")
                    }
                }
            } else {
                switchToSecondaryGroup(index, smooth = true)
            }
        }
        binding.tabLayout.setOnTagLongClickListener { index ->
            switchToSecondaryGroup(index, smooth = true)
            true
        }
        binding.viewPagerBookshelf.offscreenPageLimit = 1
        binding.viewPagerBookshelf.swipeEnabled = AppConfig.bottomBarLayoutMode != "sidebar"
        binding.viewPagerBookshelf.adapter = adapter
        binding.viewPagerBookshelf.addOnPageChangeListener(
            object : androidx.viewpager.widget.ViewPager.SimpleOnPageChangeListener() {
                override fun onPageSelected(position: Int) {
                    val secondaryGroupId = secondaryGroupIds.getOrNull(position) ?: return
                    selectedSecondaryGroupId = secondaryGroupId
                    updateOnlyUpdateRead()
                    binding.tabLayout.setSelectedIndex(position, smooth = true)
                    updateSelectAllButtonVisibility()
                }
            }
        )
        binding.root.doOnPreDraw { refreshLiquidGlassSurfaces() }
        updateHeaderTitle()
    }

    override fun onResume() {
        super.onResume()
        binding.viewPagerBookshelf.swipeEnabled = AppConfig.bottomBarLayoutMode != "sidebar"
        binding.root.post { refreshLiquidGlassSurfaces() }
    }

    private fun refreshLiquidGlassSurfaces() {
        val host = activity as? MainActivity ?: return
        host.setupAuxiliaryLiquidGlassSurface(
            liquidGlassView = binding.tabBarGlassView,
            shellOverlay = binding.tabBarShellOverlay,
            sourceViewGroup = binding.viewPagerBookshelf,
            cornerRadius = UiCorner.panelRadius(requireContext()),
            oval = false
        )
    }

    @Synchronized
    override fun upGroup(data: List<BookGroup>) {
        val newPrimaryGroups = buildPrimaryGroups()
        val newSecondaryGroups = buildSecondaryGroups(data)
        if (newPrimaryGroups != primaryGroups || newSecondaryGroups != secondaryGroups) {
            primaryGroups.clear()
            primaryGroups.addAll(newPrimaryGroups)
            secondaryGroups.clear()
            secondaryGroups.addAll(newSecondaryGroups)
            rebuildSecondaryGroupIds()
            adapter.notifyDataSetChanged()
            selectSavedGroup()
        } else {
            renderSecondaryGroups()
        }
        updateHeaderTitle()
    }

    override fun upSort() {
        adapter.notifyDataSetChanged()
    }

    private fun selectSavedGroup() {
        binding.viewPagerBookshelf.post {
            if (primaryGroups.isEmpty()) {
                binding.tabLayout.submitItems(emptyList(), -1)
                binding.btnSecondaryTagsExpand.visibility = View.GONE
                updateHeaderTitle()
                return@post
            }
            val target = AppConfig.saveTabPosition.coerceIn(0, primaryGroups.lastIndex)
            switchToPrimaryGroup(target)
        }
    }

    override fun gotoTop() {
        fragmentMap[selectedSecondaryGroupId]?.gotoTop()
    }

    override fun back(): Boolean {
        return fragmentMap[selectedSecondaryGroupId]?.exitSelectionIfNeeded() == true ||
                fragmentMap.values.any { it.exitSelectionIfNeeded() }
    }

    fun onChildSelectionChanged(fragment: BooksFragment, hasSelection: Boolean) {
        if (fragmentMap[selectedSecondaryGroupId] == fragment) {
            updateSelectAllButtonVisibility()
        }
    }

    fun setSelectAllButtonText(text: CharSequence) {
        binding.btnSelectAll.text = text
    }

    private fun updateSelectAllButtonVisibility() {
        val currentFragment = fragmentMap[selectedSecondaryGroupId]
        val selecting = currentFragment?.isSelecting() == true
        binding.btnSelectAll.visibility = if (selecting) View.VISIBLE else View.GONE
        binding.btnFusion.visibility = if (selecting) View.VISIBLE else View.GONE
        binding.btnShortcut.visibility = if (selecting) View.VISIBLE else View.GONE
        val shortcutAvailable = currentFragment?.canCreateShortcuts() == true
        binding.btnShortcut.isEnabled = shortcutAvailable
        binding.btnShortcut.alpha = if (shortcutAvailable) 1f else 0.38f
        val fusionAvailable = currentFragment?.fusionActionAvailable() == true
        binding.btnFusion.isEnabled = fusionAvailable
        binding.btnFusion.alpha = if (fusionAvailable) 1f else 0.38f
        if (selecting) {
            binding.btnSecondaryTagsExpand.visibility = View.GONE
        } else {
            updateSecondaryTagsExpandVisibility()
        }
    }

    override fun onSearchPlacementChanged() {
        updateSearchButtonVisibility()
    }

    private fun updateSearchButtonVisibility() {
        binding.btnSearchContainer.visibility =
            if (AppConfig.moveSearchToBookshelf) View.VISIBLE else View.GONE
    }

    private fun updateHeaderTitle() {
        binding.tvBookshelfTitle.text = selectedPrimaryGroup?.groupName ?: getString(R.string.bookshelf)
        binding.tvBookshelfTitle.applyUiTitleTypeface(requireContext())
    }

    fun onBooksChanged(groupId: Long, books: List<Book>) {
        groupBooksCache[groupId] = books
        if (groupId != this.groupId) return
        renderSecondaryGroups()
    }

    private fun renderSecondaryGroups() {
        if (!isAdded) return
        val oldSecondaryGroupIds = secondaryGroupIds
        rebuildSecondaryGroupIds()
        if (oldSecondaryGroupIds != secondaryGroupIds) {
            adapter.notifyDataSetChanged()
        }
        if (selectedSecondaryGroupId !in secondaryGroupIds) {
            selectedSecondaryGroupId = firstSecondaryGroupId()
        }
        if (oldSecondaryGroupIds != secondaryGroupIds) {
            val selectedIndex = secondaryGroupIds.indexOf(selectedSecondaryGroupId)
                .takeIf { it >= 0 } ?: 0
            binding.viewPagerBookshelf.setCurrentItem(selectedIndex, false)
        }
        binding.tabLayout.submitItems(
            secondaryGroupIds.map {
                RoundedTagBarView.Item(secondaryGroupName(it), showFullText = true)
            },
            secondaryGroupIds.indexOf(selectedSecondaryGroupId).takeIf { it >= 0 } ?: 0
        )
        updateSecondaryTagsExpandVisibility()
        updateSelectAllButtonVisibility()
    }

    private fun updateSecondaryTagsExpandVisibility() {
        binding.btnSecondaryTagsExpand.visibility =
            if (secondaryGroupIds.size >= ExpandableTagSelector.EXPAND_THRESHOLD) {
                View.VISIBLE
            } else {
                View.GONE
            }
    }

    private fun switchToSecondaryGroup(index: Int, smooth: Boolean) {
        val secondaryGroupId = secondaryGroupIds.getOrNull(index) ?: return
        selectSecondaryGroup(secondaryGroupId, smooth)
    }

    private fun selectSecondaryGroup(secondaryGroupId: Long, smooth: Boolean) {
        val index = secondaryGroupIds.indexOf(secondaryGroupId).takeIf { it >= 0 } ?: return
        selectedSecondaryGroupId = secondaryGroupId
        updateOnlyUpdateRead()
        binding.tabLayout.setSelectedIndex(index, smooth = smooth)
        binding.viewPagerBookshelf.setCurrentItem(index, smooth)
    }

    private fun showSecondaryGroupSelector() {
        if (secondaryGroupIds.size < ExpandableTagSelector.EXPAND_THRESHOLD) return
        val selectedIndex = secondaryGroupIds.indexOf(selectedSecondaryGroupId)
        ExpandableTagSelector.show(
            context = requireContext(),
            title = getString(R.string.select),
            items = secondaryGroupIds.mapIndexed { index, groupId ->
                ExpandableTagSelector.GridItem(
                    text = secondaryGroupName(groupId),
                    selected = index == selectedIndex,
                    value = index
                )
            }
        ) { index ->
            switchToSecondaryGroup(index, smooth = true)
        }
    }

    private fun secondaryGroupName(groupId: Long): String {
        return secondaryGroups.firstOrNull { it.groupId == groupId }?.groupName
            ?: when (groupId) {
                BookGroup.IdAll -> getString(R.string.bookshelf_tag_all)
                BookGroup.IdLocal -> getString(R.string.local)
                BookGroup.IdUngrouped -> getString(R.string.no_group)
                BookGroup.IdError -> getString(R.string.update_book_fail)
                else -> getString(R.string.bookshelf_tag_all)
            }
    }

    private fun showGroupSwitchMenu(anchor: View) {
        if (primaryGroups.isEmpty()) return
        val selectedId = selectedPrimaryGroup?.groupId
        val actions = primaryGroups.mapIndexed { index, group ->
            val prefix = if (group.groupId == selectedId) "✓" else ""
            PopupMenuAction(prefix + group.groupName) {
                switchToPrimaryGroup(index)
            }
        }
        anchor.showPopupMenu(actions)
    }

    private fun switchToPrimaryGroup(index: Int) {
        if (index !in primaryGroups.indices) return
        currentGroupIndex = index
        AppConfig.saveTabPosition = index
        val newGroupId = selectedPrimaryGroup?.groupId
        fragmentMap.entries.removeAll { it.value.groupId != newGroupId }
        adapter.notifyDataSetChanged()
        renderSecondaryGroups()
        selectSecondaryGroup(defaultSecondaryGroupId(), smooth = false)
        updateHeaderTitle()
        updateSelectAllButtonVisibility()
    }

    private fun firstSecondaryGroupId(): Long {
        return secondaryGroupIds.firstOrNull() ?: BookGroup.IdAll
    }

    /**
     * 启动/切换主分类时的默认二级分组：
     * 1. 勾选了"默认显示"的分组（且当前可见，与排序无关）；
     * 2. 否则"全部"（若未关闭）；
     * 3. 否则第一个分组。
     */
    private fun defaultSecondaryGroupId(): Long {
        val defaultId = secondaryGroups.firstOrNull { it.defaultShow }?.groupId
        if (defaultId != null && defaultId in secondaryGroupIds) return defaultId
        return BookGroup.IdAll.takeIf { it in secondaryGroupIds } ?: firstSecondaryGroupId()
    }

    fun switchToGroupId(targetGroupId: Long) {
        val primaryIndex = primaryGroups.indexOfFirst { it.groupId == targetGroupId }
        if (primaryIndex >= 0) {
            switchToPrimaryGroup(primaryIndex)
            return
        }
        val secondaryIndex = secondaryGroupIds.indexOf(targetGroupId)
        if (secondaryIndex >= 0) {
            switchToSecondaryGroup(secondaryIndex, smooth = false)
        }
    }

    fun findSecondaryGroupIdAtRaw(rawX: Float, rawY: Float): Long? {
        val index = binding.tabLayout.findIndexAtRaw(rawX, rawY) ?: return null
        return secondaryGroupIds.getOrNull(index)
    }

    override fun observeLiveBus() {
        super.observeLiveBus()
        observeEvent<String>(EventBus.BOOKSHELF_REFRESH) {
            renderSecondaryGroups()
        }
    }

    private fun rebuildSecondaryGroupIds() {
        val primaryBooks = groupBooksCache[groupId]
        val visibleSecondaryGroups = if (primaryBooks == null) {
            secondaryGroups
        } else {
            val userGroupIds = appDb.bookGroupDao.idsSum
            secondaryGroups.filter { group ->
                primaryBooks.any { book -> book.isInSecondaryGroup(group.groupId, userGroupIds) }
            }
        }
        var visibleIds = visibleSecondaryGroups
            .sortedWith(compareBy({ it.order }, { it.groupId }))
            .map { it.groupId }
        // 尊重用户在分组管理里的开关：secondaryGroups 本身已按 show 过滤，
        // 用户关掉的分组（如"全部"）不会出现在这里，也不能被兜底强行加回。
        // 仅在确实没有任何分组时，且用户没有显式关闭"全部"（即 secondaryGroups 中仍有 IdAll）
        // 才兜底补上，避免界面意外空白；用户已关闭"全部"则保持空列表。
        if (visibleIds.isEmpty() && secondaryGroups.any { it.groupId == BookGroup.IdAll }) {
            visibleIds = listOf(BookGroup.IdAll)
        }
        secondaryGroupIds = visibleIds
    }

    private fun buildPrimaryGroups(): List<BookGroup> {
        return BookGroup.primaryGroupIds.map { defaultPrimaryGroup(it) }
    }

    private fun buildSecondaryGroups(data: List<BookGroup>): List<BookGroup> {
        return data.filterNot { it.groupId in BookGroup.primaryGroupIds }
    }

    private fun secondaryGroup(groupId: Long): BookGroup? {
        return secondaryGroups.firstOrNull { it.groupId == groupId }
    }

    private fun enableRefreshForSecondaryGroup(groupId: Long): Boolean {
        return secondaryGroup(groupId)?.enableRefresh ?: true
    }

    private fun onlyUpdateReadForSecondaryGroup(groupId: Long): Boolean {
        return secondaryGroup(groupId)?.onlyUpdateRead ?: false
    }

    private fun bookSortForSecondaryGroup(groupId: Long): Int {
        return secondaryGroup(groupId)?.getRealBookSort() ?: AppConfig.bookshelfSort
    }

    private fun updateOnlyUpdateRead() {
        onlyUpdateRead = onlyUpdateReadForSecondaryGroup(selectedSecondaryGroupId)
    }

    private fun Book.isInSecondaryGroup(groupId: Long, userGroupIds: Long): Boolean {
        return when (groupId) {
            BookGroup.IdAll -> true
            BookGroup.IdLocal -> type and BookType.local > 0
            BookGroup.IdAudio -> type and BookType.audio > 0
            BookGroup.IdImage -> type and BookType.image > 0
            BookGroup.IdVideo -> type and BookType.video > 0
            BookGroup.IdError -> type and BookType.updateError > 0
            BookGroup.IdUngrouped -> userGroupIds and group == 0L && type and BookType.local == 0
            else -> groupId > 0 && group and groupId > 0
        }
    }

    private fun defaultPrimaryGroup(groupId: Long): BookGroup {
        return BookGroup(
            groupId = groupId,
            groupName = when (groupId) {
                BookGroup.IdNovel -> getString(R.string.novel)
                BookGroup.IdImage -> getString(R.string.manga)
                BookGroup.IdAudio -> getString(R.string.audio)
                BookGroup.IdVideo -> getString(R.string.video)
                else -> getString(R.string.all)
            },
            order = BookGroup.primaryGroupIds.indexOf(groupId)
        )
    }

    private inner class TabFragmentPageAdapter(fm: FragmentManager) :
        FragmentStatePagerAdapter(fm, BEHAVIOR_RESUME_ONLY_CURRENT_FRAGMENT) {

        override fun getPageTitle(position: Int): CharSequence {
            return secondaryGroupName(secondaryGroupIds[position])
        }

        override fun getItemPosition(any: Any): Int {
            val fragment = any as BooksFragment
            val position = fragment.position
            val primaryGroup = selectedPrimaryGroup
            val secondaryGroupId = secondaryGroupIds.getOrNull(position)
            if (fragment.groupId != primaryGroup?.groupId ||
                fragment.secondaryGroupId != secondaryGroupId
            ) {
                return POSITION_NONE
            }
            val bookSort = bookSortForSecondaryGroup(fragment.secondaryGroupId)
            fragment.setEnableRefresh(enableRefreshForSecondaryGroup(fragment.secondaryGroupId))
            fragment.setOnlyUpdateRead(onlyUpdateReadForSecondaryGroup(fragment.secondaryGroupId))
            if (fragment.bookSort != bookSort) {
                fragment.upBookSort(bookSort)
            }
            return POSITION_UNCHANGED
        }

        override fun getItem(position: Int): Fragment {
            val group = selectedPrimaryGroup ?: defaultPrimaryGroup(BookGroup.IdPrimaryAll)
            val secondaryGroupId = secondaryGroupIds.getOrNull(position) ?: BookGroup.IdAll
            if (secondaryGroupId == selectedSecondaryGroupId) {
                updateOnlyUpdateRead()
            }
            return BooksFragment(
                position,
                group,
                secondaryGroupId,
                bookSortForSecondaryGroup(secondaryGroupId),
                enableRefreshForSecondaryGroup(secondaryGroupId),
                onlyUpdateReadForSecondaryGroup(secondaryGroupId)
            )
        }

        override fun getCount(): Int {
            return secondaryGroupIds.size
        }

        override fun instantiateItem(container: ViewGroup, position: Int): Any {
            var fragment = super.instantiateItem(container, position) as BooksFragment
            val secondaryGroupId = secondaryGroupIds.getOrNull(position) ?: BookGroup.IdAll
            if (fragment.isCreated && getItemPosition(fragment) == POSITION_NONE) {
                destroyItem(container, position, fragment)
                fragment = super.instantiateItem(container, position) as BooksFragment
            }
            fragmentMap[secondaryGroupId] = fragment
            return fragment
        }

        override fun destroyItem(container: ViewGroup, position: Int, any: Any) {
            fragmentMap.entries.removeAll { it.value == any }
            super.destroyItem(container, position, any)
        }

    }
}
