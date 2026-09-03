package io.legado.app.ui.main.homepage

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewbinding.ViewBinding
import com.google.android.flexbox.FlexboxLayout
import com.google.android.material.tabs.TabLayout
import io.legado.app.R
import io.legado.app.base.BaseBottomSheetDialogFragment
import io.legado.app.data.entities.rule.ExploreKind
import io.legado.app.databinding.DialogHomepageManageBinding
import io.legado.app.databinding.ItemHomepageManageActionBinding
import io.legado.app.databinding.ItemHomepageManageFieldBinding
import io.legado.app.databinding.ItemHomepageManageModuleBinding
import io.legado.app.databinding.ItemHomepageManageSectionBinding
import io.legado.app.databinding.ItemHomepageManageSetBinding
import io.legado.app.databinding.ItemHomepageManageSourceBinding
import io.legado.app.domain.model.HomepageModuleType
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.dialogs.selector
import io.legado.app.utils.GSON
import io.legado.app.utils.dpToPx
import io.legado.app.utils.setLayout
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.launch

/**
 * 主页模块管理底部弹窗：集列表 → 集详情 → 浏览书源/订阅源 → 源模块详情（已加入/发现 两 Tab），
 * 以及自定义集复制模块，所有层级共用一个列表，按页面栈切换。
 */
class HomepageModuleManageSheet : BaseBottomSheetDialogFragment(R.layout.dialog_homepage_manage) {

    private val binding by viewBinding(DialogHomepageManageBinding::bind)

    internal val viewModel: HomepageViewModel
        get() = (parentFragment as HomepageFragment).viewModel

    private val adapter by lazy { ManageAdapter(requireContext()) }

    private var pageStack = mutableListOf<Page>(Page.SetList)

    /** 源模块详情页当前 Tab：0 = 已加入，1 = 发现 */
    private var sourceDetailTab = 0

    /** 发现页状态：选中的模块类型与分类 */
    private var discoverState = DiscoverState()

    private data class DiscoverState(
        val loading: Boolean = false,
        val kinds: List<ExploreKind> = emptyList(),
        val moduleType: String = HomepageModuleType.Grid.key,
        val selectedKinds: List<ExploreKind> = emptyList(),
    )

    private sealed interface Page {
        data object SetList : Page

        data class SetDetail(val setUrl: String, val setName: String) : Page

        data object BrowseSources : Page

        data object BrowseRssSources : Page

        data class SourceDetail(
            val sourceUrl: String,
            val sourceName: String,
            val setId: String?,
            val sourceType: String,
        ) : Page

        data class CustomSetAdd(val setId: String, val setName: String) : Page
    }

    private sealed interface Row {
        data class Section(val title: String) : Row

        data class Set(val ui: HomepageSourceManageUi) : Row

        data class Module(val ui: HomepageModuleManageUi, val copyMode: Boolean = false) : Row

        data class Source(val ui: HomepageSourceManageUi, val sourceType: String) : Row

        data class Field(
            val label: String,
            val value: String,
            val hint: String? = null,
            val onClick: () -> Unit,
        ) : Row

        data class Action(val label: String, val action: () -> Unit) : Row
    }

    override fun onStart() {
        super.onStart()
        setLayout(ViewGroup.LayoutParams.MATCH_PARENT, 0.72f)
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        binding.rvManage.layoutManager = LinearLayoutManager(requireContext())
        binding.rvManage.adapter = adapter

        binding.ivClose.setOnClickListener { dismiss() }
        binding.ivBack.setOnClickListener { popPage() }

        binding.tvActionAddSet.setOnClickListener { showCreateSetDialog() }
        binding.tvActionBrowseBook.setOnClickListener { pushPage(Page.BrowseSources) }
        binding.tvActionBrowseRss.setOnClickListener { pushPage(Page.BrowseRssSources) }

        binding.tabSourceDetail.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                if (sourceDetailTab != tab.position) {
                    sourceDetailTab = tab.position
                    rebuildRows()
                }
            }

            override fun onTabUnselected(tab: TabLayout.Tab) = Unit

            override fun onTabReselected(tab: TabLayout.Tab) = Unit
        })

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.manageStateFlow.collect {
                rebuildRows()
            }
        }
        rebuildRows()
        upFooter()
        upHeader()
    }

    private fun pushPage(page: Page) {
        pageStack.add(page)
        if (page is Page.SourceDetail) {
            sourceDetailTab = 0
            discoverState = DiscoverState()
            loadDiscoverKinds(page)
        }
        rebuildRows()
        upFooter()
        upHeader()
    }

    private fun popPage() {
        if (pageStack.size > 1) {
            pageStack.removeAt(pageStack.lastIndex)
            rebuildRows()
            upFooter()
            upHeader()
        } else {
            dismiss()
        }
    }

    private fun currentPage(): Page = pageStack.last()

    private fun upHeader() {
        val page = currentPage()
        binding.ivBack.isVisible = pageStack.size > 1
        binding.tvTitle.text = when (page) {
            Page.SetList -> getString(R.string.homepage_module_manage)
            is Page.SetDetail -> page.setName.ifBlank { getString(R.string.homepage_set_detail) }
            Page.BrowseSources -> getString(R.string.homepage_browse_book_sources)
            Page.BrowseRssSources -> getString(R.string.homepage_browse_rss_sources)
            is Page.SourceDetail -> page.sourceName
            is Page.CustomSetAdd -> page.setName
        }
        binding.tabSourceDetail.isVisible = page is Page.SourceDetail
        if (page is Page.SourceDetail && binding.tabSourceDetail.tabCount != 2) {
            binding.tabSourceDetail.removeAllTabs()
            binding.tabSourceDetail.addTab(
                binding.tabSourceDetail.newTab().setText(R.string.homepage_joined)
            )
            binding.tabSourceDetail.addTab(
                binding.tabSourceDetail.newTab().setText(R.string.homepage_discover)
            )
        }
        if (page is Page.SourceDetail) {
            binding.tabSourceDetail.getTabAt(sourceDetailTab)?.select()
        }
    }

    private fun upFooter() {
        val page = currentPage()
        binding.tvActionAddSet.isVisible = page is Page.SetList
        binding.tvActionBrowseBook.isVisible = page is Page.SetList
        binding.tvActionBrowseRss.isVisible = page is Page.SetList
    }

    private fun rebuildRows() {
        val rows = mutableListOf<Row>()
        val state = adapter.manageState
        when (val page = currentPage()) {
            Page.SetList -> {
                if (state.sets.isEmpty()) {
                    rows.add(Row.Section(getString(R.string.homepage_empty_title)))
                }
                state.sets.forEach { rows.add(Row.Set(it)) }
            }

            is Page.SetDetail -> {
                val setId = resolveSetId(page.setUrl)
                val modules = state.allJoinedModules.filter { it.customSetId == setId }
                if (modules.isNotEmpty()) {
                    rows.add(Row.Section(getString(R.string.homepage_set_detail)))
                }
                modules.forEach { rows.add(Row.Module(it)) }
                when {
                    page.setUrl.startsWith("src_") -> rows.add(
                        Row.Source(
                            HomepageSourceManageUi(
                                sourceUrl = page.setUrl.removePrefix("src_"),
                                sourceName = page.setName,
                            ),
                            "book"
                        )
                    )

                    page.setUrl.startsWith("rss_") -> rows.add(
                        Row.Source(
                            HomepageSourceManageUi(
                                sourceUrl = page.setUrl.removePrefix("rss_"),
                                sourceName = page.setName,
                            ),
                            "rss"
                        )
                    )

                    else -> rows.add(
                        Row.Action(getString(R.string.homepage_copy_from_other_sets)) {
                            pushPage(Page.CustomSetAdd(setId, page.setName))
                        }
                    )
                }
            }

            Page.BrowseSources -> {
                state.browseSources.forEach {
                    rows.add(Row.Source(it, "book"))
                }
                if (state.browseSources.isEmpty()) {
                    rows.add(Row.Section(getString(R.string.homepage_empty_title)))
                }
            }

            Page.BrowseRssSources -> {
                state.rssSources.forEach { rows.add(Row.Source(it, "rss")) }
                if (state.rssSources.isEmpty()) {
                    rows.add(Row.Section(getString(R.string.homepage_empty_title)))
                }
            }

            is Page.SourceDetail -> {
                if (sourceDetailTab == 0) {
                    buildJoinedRows(rows, state, page)
                } else {
                    buildDiscoverRows(rows, page)
                }
            }

            is Page.CustomSetAdd -> {
                val others = state.allJoinedModules.filter { it.customSetId != page.setId }
                rows.add(Row.Section(getString(R.string.homepage_copy_from_other_sets)))
                others.forEach { rows.add(Row.Module(it, copyMode = true)) }
                if (others.isEmpty()) {
                    rows.add(Row.Section(getString(R.string.homepage_empty_title)))
                }
            }
        }
        adapter.submitRows(rows)
    }

    /** 已加入 Tab：该源在当前集的模块列表，附同步入口 */
    private fun buildJoinedRows(
        rows: MutableList<Row>,
        state: HomepageManageUiState,
        page: Page.SourceDetail,
    ) {
        val effectiveSetId = page.setId ?: when (page.sourceType) {
            "rss" -> "rss_${page.sourceUrl}"
            else -> "src_${page.sourceUrl}"
        }
        val modules = state.allJoinedModules.filter {
            it.sourceUrl == page.sourceUrl && it.customSetId == effectiveSetId
        }
        if (modules.isEmpty()) {
            rows.add(Row.Section(getString(R.string.homepage_empty_title)))
        }
        modules.forEach { rows.add(Row.Module(it)) }
        if (page.sourceType == "book") {
            rows.add(Row.Action(getString(R.string.homepage_sync)) {
                viewModel.syncSourceModules(page.sourceUrl) {
                    requireContext().toastOnUi(getString(R.string.homepage_sync_done))
                }
            })
        }
    }

    /** 发现 Tab：模块类型选择 → 分类选择 → 手动添加，选完分类自动打开添加对话框 */
    private fun buildDiscoverRows(rows: MutableList<Row>, page: Page.SourceDetail) {
        val moduleType = HomepageModuleType.fromKey(discoverState.moduleType)
        if (discoverState.loading) {
            rows.add(Row.Section(getString(R.string.homepage_loading_categories)))
        }
        rows.add(
            Row.Field(
                label = getString(R.string.homepage_module_type),
                value = getString(moduleType.titleRes),
            ) {
                showModuleTypeMenu()
            }
        )
        val multiSelect = isMultiSelectType(discoverState.moduleType)
        val value = if (multiSelect) {
            when {
                discoverState.selectedKinds.isEmpty() -> ""
                discoverState.selectedKinds.size <= 3 ->
                    discoverState.selectedKinds.joinToString("、") { it.title }

                else -> getString(
                    R.string.homepage_selected_categories_count,
                    discoverState.selectedKinds.size
                )
            }
        } else {
            discoverState.selectedKinds.firstOrNull()?.title ?: ""
        }
        rows.add(
            Row.Field(
                label = getString(R.string.homepage_select_category),
                value = value,
                hint = if (multiSelect) getString(R.string.homepage_multi_select_hint) else null,
            ) {
                showKindSelectSheet(page)
            }
        )
        rows.add(Row.Action(getString(R.string.homepage_manual_add_module)) {
            showEditDialog(
                HomepageModuleManageUi(
                    id = "",
                    sourceUrl = page.sourceUrl,
                    sourceName = page.sourceName,
                    moduleKey = "",
                    title = "",
                    type = discoverState.moduleType,
                    originalTitle = "",
                    sourceType = page.sourceType,
                ),
                isCreate = true,
                setId = page.setId,
            )
        })
    }

    private fun isMultiSelectType(type: String): Boolean {
        return type == HomepageModuleType.ButtonGroup.key
                || type == HomepageModuleType.Ranking.key
                || type == HomepageModuleType.GridRanking.key
    }

    private fun showModuleTypeMenu() {
        val entries = HomepageModuleType.entries.filter { it != HomepageModuleType.Unknown }
        val options = entries.map { entry ->
            val label = getString(entry.titleRes)
            if (entry.key == discoverState.moduleType) "✓ $label" else label
        }
        requireContext().selector(
            getString(R.string.homepage_module_type), options
        ) { _, index ->
            val selected = entries.getOrNull(index) ?: return@selector
            if (selected.key != discoverState.moduleType) {
                discoverState = discoverState.copy(
                    moduleType = selected.key,
                    selectedKinds = emptyList(),
                )
                rebuildRows()
            }
        }
    }

    private fun loadDiscoverKinds(page: Page.SourceDetail) {
        viewLifecycleOwner.lifecycleScope.launch {
            discoverState = discoverState.copy(loading = true, kinds = emptyList())
            rebuildRows()
            val kinds = if (page.sourceType == "rss") {
                viewModel.getRssKinds(page.sourceUrl).map { (title, url) ->
                    ExploreKind(title = title, url = url)
                }
            } else {
                viewModel.getExploreKinds(page.sourceUrl)
            }
            discoverState = discoverState.copy(loading = false, kinds = kinds)
            rebuildRows()
        }
    }

    private fun showKindSelectSheet(page: Page.SourceDetail) {
        if (discoverState.loading || discoverState.kinds.isEmpty()) {
            requireContext().toastOnUi(getString(R.string.homepage_loading_categories))
            return
        }
        val sheet = HomepageKindSelectSheet()
        sheet.arguments = Bundle().apply {
            putString("sourceUrl", page.sourceUrl)
            putString("sourceType", page.sourceType)
            putBoolean("multiple", isMultiSelectType(discoverState.moduleType))
            putStringArrayList(
                "selected",
                ArrayList(discoverState.selectedKinds.map { it.url ?: it.title })
            )
        }
        sheet.show(childFragmentManager, "homepageKindSelect")
    }

    /** 分类选择弹窗回调：单选/多选都打开添加对话框并预填参数 */
    internal fun onKindsSelected(kinds: List<ExploreKind>) {
        if (kinds.isEmpty()) return
        val page = currentPage()
        if (page !is Page.SourceDetail) return
        val multiSelect = isMultiSelectType(discoverState.moduleType)
        discoverState = discoverState.copy(selectedKinds = kinds)
        val def = if (multiSelect) {
            ModuleDef(
                type = discoverState.moduleType,
                title = kinds.joinToString("、") { it.title },
                args = GSON.toJson(
                    kinds.map { mapOf("t" to it.title, "u" to (it.url ?: "")) }
                ),
                url = kinds.firstOrNull()?.url,
                sourceUrl = page.sourceUrl,
            )
        } else {
            val kind = kinds.first()
            ModuleDef(
                key = "explore_${kind.title}_${kind.url}",
                type = discoverState.moduleType,
                title = kind.title,
                url = kind.url,
                sourceUrl = page.sourceUrl,
            )
        }
        showEditDialog(
            HomepageModuleManageUi(
                id = "",
                sourceUrl = page.sourceUrl,
                sourceName = page.sourceName,
                moduleKey = def.key,
                title = def.title,
                type = def.type,
                url = def.url,
                args = def.args,
                originalTitle = "",
                sourceType = page.sourceType,
            ),
            isCreate = true,
            setId = page.setId,
        )
        rebuildRows()
    }

    private fun resolveSetId(setUrl: String): String {
        return if (HomepageViewModel.isCustomSetUrl(setUrl)) {
            HomepageViewModel.customSetIdFromUrl(setUrl)
        } else {
            setUrl
        }
    }

    private fun showCreateSetDialog() {
        requireContext().alert(getString(R.string.homepage_new_custom_set)) {
            val editText = EditText(requireContext())
            editText.hint = getString(R.string.homepage_set_name)
            editText.setPadding(48.dpToPx(), 16.dpToPx(), 48.dpToPx(), 8.dpToPx())
            setCustomView(editText)
            positiveButton(R.string.ok) {
                val name = editText.text.toString().trim()
                if (name.isNotEmpty()) {
                    viewModel.createCustomSet(name)
                    requireContext().toastOnUi(getString(R.string.homepage_set_created))
                }
            }
            negativeButton(R.string.cancel)
        }
    }

    // ==================== 行事件 ====================

    private fun onSetRowClick(ui: HomepageSourceManageUi) {
        pushPage(Page.SetDetail(ui.sourceUrl, ui.sourceName))
    }

    private fun onSetToggle(ui: HomepageSourceManageUi, visible: Boolean) {
        viewModel.toggleSet(ui.sourceUrl, visible)
    }

    private fun onSetMoreMenu(ui: HomepageSourceManageUi) {
        requireContext().selector(
            ui.sourceName,
            listOf(
                getString(R.string.homepage_rename_set),
                getString(R.string.homepage_delete_set),
                getString(R.string.homepage_move_up),
                getString(R.string.homepage_move_down),
            )
        ) { _, index ->
            when (index) {
                0 -> showRenameSetDialog(ui)
                1 -> showDeleteSetDialog(ui)
                2 -> moveSet(ui, -1)
                3 -> moveSet(ui, 1)
            }
        }
    }

    private fun showRenameSetDialog(ui: HomepageSourceManageUi) {
        val setId = resolveSetId(ui.sourceUrl)
        requireContext().alert(getString(R.string.homepage_rename_set)) {
            val editText = EditText(requireContext())
            editText.setText(ui.sourceName)
            editText.setPadding(48.dpToPx(), 16.dpToPx(), 48.dpToPx(), 8.dpToPx())
            setCustomView(editText)
            positiveButton(R.string.ok) {
                val name = editText.text.toString().trim()
                if (name.isNotEmpty() && name != ui.sourceName) {
                    viewModel.renameCustomSet(setId, name)
                }
            }
            negativeButton(R.string.cancel)
        }
    }

    private fun showDeleteSetDialog(ui: HomepageSourceManageUi) {
        requireContext().alert(
            getString(R.string.homepage_delete_set),
            getString(R.string.homepage_delete_set_confirm, ui.sourceName)
        ) {
            positiveButton(R.string.ok) {
                viewModel.deleteCustomSet(resolveSetId(ui.sourceUrl))
                val page = pageStack.lastOrNull()
                if (page is Page.SetDetail && page.setUrl == ui.sourceUrl) {
                    popPage()
                }
            }
            negativeButton(R.string.cancel)
        }
    }

    private fun moveSet(ui: HomepageSourceManageUi, direction: Int) {
        val urls = adapter.manageState.sets.map { it.sourceUrl }
        val index = urls.indexOf(ui.sourceUrl)
        val target = index + direction
        if (index < 0 || target < 0 || target >= urls.size) return
        val reordered = urls.toMutableList().apply {
            removeAt(index)
            add(target, ui.sourceUrl)
        }
        viewModel.reorderCustomSets(reordered)
    }

    private fun onModuleToggle(ui: HomepageModuleManageUi, visible: Boolean) {
        viewModel.toggleModule(ui.id, visible)
    }

    private fun onModuleMoreMenu(ui: HomepageModuleManageUi) {
        requireContext().selector(
            ui.title,
            listOf(
                getString(R.string.homepage_edit_module),
                getString(R.string.homepage_delete_module),
                getString(R.string.homepage_move_up),
                getString(R.string.homepage_move_down),
            )
        ) { _, index ->
            when (index) {
                0 -> showEditDialog(ui, isCreate = false)
                1 -> showDeleteModuleDialog(ui)
                2 -> moveModule(ui, -1)
                3 -> moveModule(ui, 1)
            }
        }
    }

    private fun showDeleteModuleDialog(ui: HomepageModuleManageUi) {
        requireContext().alert(
            getString(R.string.homepage_delete_module),
            getString(R.string.homepage_delete_module_confirm, ui.title)
        ) {
            positiveButton(R.string.ok) {
                viewModel.deleteModule(ui.id)
            }
            negativeButton(R.string.cancel)
        }
    }

    private fun moveModule(ui: HomepageModuleManageUi, direction: Int) {
        val setId = ui.customSetId ?: return
        val ids = adapter.manageState.allJoinedModules
            .filter { it.customSetId == setId }
            .map { it.id }
        val index = ids.indexOf(ui.id)
        val target = index + direction
        if (index < 0 || target < 0 || target >= ids.size) return
        val reordered = ids.toMutableList().apply {
            removeAt(index)
            add(target, ui.id)
        }
        viewModel.reorderModules(reordered)
    }

    private fun onSourceRowClick(ui: HomepageSourceManageUi, sourceType: String) {
        val page = currentPage()
        val setId = if (page is Page.SetDetail) {
            page.setUrl.takeIf { it.startsWith("src_") || it.startsWith("rss_") }
        } else {
            null
        }
        pushPage(
            Page.SourceDetail(
                sourceUrl = ui.sourceUrl,
                sourceName = ui.sourceName,
                setId = setId,
                sourceType = sourceType,
            )
        )
    }

    private fun onCopyModule(ui: HomepageModuleManageUi) {
        val page = currentPage()
        if (page !is Page.CustomSetAdd) return
        viewModel.assignModuleToCustomSet(ui.id, resolveSetId(page.setId))
        requireContext().toastOnUi(getString(R.string.homepage_copied))
    }

    private fun showEditDialog(
        ui: HomepageModuleManageUi,
        isCreate: Boolean,
        setId: String? = null,
    ) {
        val dialog = HomepageModuleEditDialog()
        dialog.arguments = Bundle().apply {
            putString("id", ui.id)
            putString("sourceUrl", ui.sourceUrl)
            putString("sourceName", ui.sourceName)
            putString("moduleKey", ui.moduleKey)
            putString("title", ui.title)
            putString("type", ui.type)
            putString("url", ui.url)
            putString("args", ui.args)
            putString("setId", setId)
            putString("sourceType", ui.sourceType)
            putBoolean("isCreate", isCreate)
        }
        dialog.show(childFragmentManager, "homepageModuleEdit")
    }

    // ==================== 适配器 ====================

    private inner class ManageAdapter(
        context: Context
    ) : RecyclerView.Adapter<ManageViewHolder>() {

        val manageState get() = viewModel.manageStateFlow.value

        private val inflater = LayoutInflater.from(context)
        private val rows = mutableListOf<Row>()

        fun submitRows(newRows: List<Row>) {
            rows.clear()
            rows.addAll(newRows)
            notifyDataSetChanged()
        }

        override fun getItemViewType(position: Int): Int = when (rows[position]) {
            is Row.Section -> TYPE_SECTION
            is Row.Set -> TYPE_SET
            is Row.Module -> TYPE_MODULE
            is Row.Source -> TYPE_SOURCE
            is Row.Field -> TYPE_FIELD
            is Row.Action -> TYPE_ACTION
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ManageViewHolder {
            val binding: ViewBinding = when (viewType) {
                TYPE_SECTION -> ItemHomepageManageSectionBinding.inflate(inflater, parent, false)
                TYPE_SET -> ItemHomepageManageSetBinding.inflate(inflater, parent, false)
                TYPE_MODULE -> ItemHomepageManageModuleBinding.inflate(inflater, parent, false)
                TYPE_SOURCE -> ItemHomepageManageSourceBinding.inflate(inflater, parent, false)
                TYPE_FIELD -> ItemHomepageManageFieldBinding.inflate(inflater, parent, false)
                else -> ItemHomepageManageActionBinding.inflate(inflater, parent, false)
            }
            return ManageViewHolder(binding)
        }

        override fun getItemCount(): Int = rows.size

        override fun onBindViewHolder(holder: ManageViewHolder, position: Int) {
            val context = holder.binding.root.context
            when (val row = rows[position]) {
                is Row.Section -> holder.sectionBinding?.tvSection?.text = row.title

                is Row.Set -> holder.setBinding?.run {
                    tvName.text = row.ui.sourceName
                    tvDesc.text = context.getString(R.string.homepage_module_count, row.ui.moduleCount)
                    swVisible.setOnCheckedChangeListener(null)
                    swVisible.isChecked = row.ui.isSelected
                    swVisible.setOnCheckedChangeListener { _, isChecked ->
                        onSetToggle(row.ui, isChecked)
                    }
                    root.setOnClickListener { onSetRowClick(row.ui) }
                    ivMore.setOnClickListener { onSetMoreMenu(row.ui) }
                }

                is Row.Module -> holder.moduleBinding?.run {
                    tvTitle.text = row.ui.title
                    tvDesc.text = moduleTypeLabel(row.ui.type)
                    swVisible.isVisible = !row.copyMode
                    if (!row.copyMode) {
                        swVisible.setOnCheckedChangeListener(null)
                        swVisible.isChecked = row.ui.isVisible
                        swVisible.setOnCheckedChangeListener { _, isChecked ->
                            onModuleToggle(row.ui, isChecked)
                        }
                    }
                    ivMore.isVisible = !row.copyMode
                    ivMore.setOnClickListener { onModuleMoreMenu(row.ui) }
                    root.setOnClickListener {
                        if (row.copyMode) onCopyModule(row.ui)
                    }
                }

                is Row.Source -> holder.sourceBinding?.run {
                    tvName.text = row.ui.sourceName
                    tvDesc.text = buildString {
                        row.ui.sourceGroup?.takeIf { it.isNotBlank() }?.let {
                            append(it)
                            if (row.ui.moduleCount > 0) append(" · ")
                        }
                        if (row.ui.moduleCount > 0) {
                            append(context.getString(R.string.homepage_module_count, row.ui.moduleCount))
                        }
                    }
                    root.setOnClickListener { onSourceRowClick(row.ui, row.sourceType) }
                }

                is Row.Field -> holder.fieldBinding?.run {
                    tvFieldLabel.text = row.label
                    tvFieldValue.text = row.value
                    tvFieldValue.isVisible = row.value.isNotEmpty()
                    tvFieldHint.isVisible = !row.hint.isNullOrBlank()
                    tvFieldHint.text = row.hint ?: ""
                    root.setOnClickListener { row.onClick() }
                }

                is Row.Action -> holder.actionBinding?.root?.let { tv ->
                    tv.text = row.label
                    tv.setOnClickListener { row.action() }
                }
            }
        }

        private fun moduleTypeLabel(type: String): String {
            val res = HomepageModuleType.fromKey(type).titleRes
            return requireContext().getString(res)
        }
    }

    class ManageViewHolder(val binding: ViewBinding) :
        RecyclerView.ViewHolder(binding.root) {
        val sectionBinding: ItemHomepageManageSectionBinding? =
            binding as? ItemHomepageManageSectionBinding

        val setBinding: ItemHomepageManageSetBinding? = binding as? ItemHomepageManageSetBinding

        val moduleBinding: ItemHomepageManageModuleBinding? =
            binding as? ItemHomepageManageModuleBinding

        val sourceBinding: ItemHomepageManageSourceBinding? =
            binding as? ItemHomepageManageSourceBinding

        val fieldBinding: ItemHomepageManageFieldBinding? =
            binding as? ItemHomepageManageFieldBinding

        val actionBinding: ItemHomepageManageActionBinding? =
            binding as? ItemHomepageManageActionBinding
    }

    companion object {
        private const val TYPE_SECTION = 0
        private const val TYPE_SET = 1
        private const val TYPE_MODULE = 2
        private const val TYPE_SOURCE = 3
        private const val TYPE_FIELD = 4
        private const val TYPE_ACTION = 5

        internal fun createKindChip(
            context: Context,
            text: String,
            onClick: () -> Unit
        ): TextView {
            val chip = TextView(context)
            chip.text = text
            chip.textSize = 13f
            chip.setTextColor(context.getColor(R.color.primaryText))
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
}
