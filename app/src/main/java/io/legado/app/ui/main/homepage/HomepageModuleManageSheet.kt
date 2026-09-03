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
import io.legado.app.R
import io.legado.app.base.BaseBottomSheetDialogFragment
import io.legado.app.data.entities.rule.ExploreKind
import io.legado.app.databinding.DialogHomepageManageBinding
import io.legado.app.databinding.ItemHomepageManageActionBinding
import io.legado.app.databinding.ItemHomepageManageKindsBinding
import io.legado.app.databinding.ItemHomepageManageModuleBinding
import io.legado.app.databinding.ItemHomepageManageSectionBinding
import io.legado.app.databinding.ItemHomepageManageSetBinding
import io.legado.app.databinding.ItemHomepageManageSourceBinding
import io.legado.app.domain.model.HomepageModuleType
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.dialogs.selector
import io.legado.app.utils.dpToPx
import io.legado.app.utils.setLayout
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.launch

/**
 * 主页模块管理底部弹窗：集列表 → 集详情 → 浏览书源/订阅源 → 源模块详情，
 * 以及自定义集复制模块，所有层级共用一个列表，按页面栈切换。
 */
class HomepageModuleManageSheet : BaseBottomSheetDialogFragment(R.layout.dialog_homepage_manage) {

    private val binding by viewBinding(DialogHomepageManageBinding::bind)

    internal val viewModel: HomepageViewModel
        get() = (parentFragment as HomepageFragment).viewModel

    private val adapter by lazy { ManageAdapter(requireContext()) }

    private var pageStack = mutableListOf<Page>(Page.SetList)
    private var kindsState = KindsState()

    private data class KindsState(
        val loading: Boolean = false,
        val kinds: List<ExploreKind> = emptyList(),
        val selected: MutableSet<String> = mutableSetOf(),
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

        data object Kinds : Row

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
            kindsState = KindsState()
            loadKinds(page)
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
        binding.ivBack.isVisible = pageStack.size > 1
        binding.tvTitle.text = when (val page = currentPage()) {
            Page.SetList -> getString(R.string.homepage_module_manage)
            is Page.SetDetail -> page.setName.ifBlank { getString(R.string.homepage_set_detail) }
            Page.BrowseSources -> getString(R.string.homepage_browse_book_sources)
            Page.BrowseRssSources -> getString(R.string.homepage_browse_rss_sources)
            is Page.SourceDetail -> page.sourceName
            is Page.CustomSetAdd -> page.setName
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
                val effectiveSetId = page.setId ?: when (page.sourceType) {
                    "rss" -> "rss_${page.sourceUrl}"
                    else -> "src_${page.sourceUrl}"
                }
                val modules = state.allJoinedModules.filter {
                    it.sourceUrl == page.sourceUrl && it.customSetId == effectiveSetId
                }
                if (modules.isNotEmpty()) {
                    rows.add(Row.Section(getString(R.string.homepage_joined_modules)))
                }
                modules.forEach { rows.add(Row.Module(it)) }
                if (page.sourceType == "book") {
                    rows.add(Row.Action(getString(R.string.homepage_sync)) {
                        viewModel.syncSourceModules(page.sourceUrl) {
                            requireContext().toastOnUi(getString(R.string.homepage_sync_done))
                        }
                    })
                }
                rows.add(Row.Action(getString(R.string.homepage_add_custom_module)) {
                    showEditDialog(
                        HomepageModuleManageUi(
                            id = "",
                            sourceUrl = page.sourceUrl,
                            sourceName = page.sourceName,
                            moduleKey = "",
                            title = "",
                            type = HomepageModuleType.Grid.key,
                            originalTitle = "",
                            sourceType = page.sourceType,
                        ),
                        isCreate = true,
                        setId = page.setId,
                    )
                })
                rows.add(Row.Kinds)
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

    private fun resolveSetId(setUrl: String): String {
        return if (HomepageViewModel.isCustomSetUrl(setUrl)) {
            HomepageViewModel.customSetIdFromUrl(setUrl)
        } else {
            setUrl
        }
    }

    private fun loadKinds(page: Page.SourceDetail) {
        viewLifecycleOwner.lifecycleScope.launch {
            kindsState = kindsState.copy(loading = true, kinds = emptyList())
            rebuildRows()
            val kinds = if (page.sourceType == "rss") {
                viewModel.getRssKinds(page.sourceUrl).map { (title, url) ->
                    ExploreKind(title = title, url = url)
                }
            } else {
                viewModel.getExploreKinds(page.sourceUrl)
            }
            kindsState = kindsState.copy(loading = false, kinds = kinds)
            rebuildRows()
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

    // ==================== 分类选择 ====================

    private fun toggleKind(kind: ExploreKind) {
        if (!kindsState.selected.remove(kind.title)) {
            kindsState.selected.add(kind.title)
        }
        rebuildRows()
    }

    private fun addSelectedAsButtonGroup() {
        val page = currentPage()
        if (page !is Page.SourceDetail) return
        val titles = kindsState.kinds.filter { it.title in kindsState.selected }.map { it.title }
        if (titles.isEmpty()) return
        if (page.sourceType == "rss") {
            viewModel.addRssButtonGroupFromKinds(
                page.sourceUrl, page.setId, page.sourceName, page.sourceName, titles
            )
        } else {
            viewModel.addButtonGroupFromKinds(
                page.sourceUrl, page.setId, page.sourceName, titles
            )
        }
        kindsState.selected.clear()
        rebuildRows()
        requireContext().toastOnUi(getString(R.string.homepage_module_added))
    }

    private fun addSelectedAsRanking() {
        val page = currentPage()
        if (page !is Page.SourceDetail) return
        val categories = kindsState.kinds.filter { it.title in kindsState.selected }
            .mapNotNull { it.url?.let { url -> it.title to url } }
        if (categories.isEmpty()) return
        if (page.sourceType == "rss") {
            viewModel.addRssRankingGroupFromKinds(
                page.sourceUrl, page.setId, page.sourceName, page.sourceName, categories
            )
        } else {
            viewModel.addRankingGroupFromKinds(
                page.sourceUrl, page.setId, page.sourceName, categories
            )
        }
        kindsState.selected.clear()
        rebuildRows()
        requireContext().toastOnUi(getString(R.string.homepage_module_added))
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
            is Row.Kinds -> TYPE_KINDS
            is Row.Action -> TYPE_ACTION
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ManageViewHolder {
            val binding: ViewBinding = when (viewType) {
                TYPE_SECTION -> ItemHomepageManageSectionBinding.inflate(inflater, parent, false)
                TYPE_SET -> ItemHomepageManageSetBinding.inflate(inflater, parent, false)
                TYPE_MODULE -> ItemHomepageManageModuleBinding.inflate(inflater, parent, false)
                TYPE_SOURCE -> ItemHomepageManageSourceBinding.inflate(inflater, parent, false)
                TYPE_KINDS -> ItemHomepageManageKindsBinding.inflate(inflater, parent, false)
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

                is Row.Kinds -> holder.kindsBinding?.run {
                    tvHint.text = getString(R.string.homepage_kinds_hint)
                    pbKinds.isVisible = kindsState.loading
                    flKinds.isVisible = !kindsState.loading
                    llKindActions.isVisible =
                        !kindsState.loading && kindsState.selected.isNotEmpty()
                    flKinds.removeAllViews()
                    kindsState.kinds.forEach { kind ->
                        flKinds.addView(createKindChip(context, kind.title) {
                            toggleKind(kind)
                        })
                    }
                    tvAddButtons.setOnClickListener { addSelectedAsButtonGroup() }
                    tvAddRanking.setOnClickListener { addSelectedAsRanking() }
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

        val kindsBinding: ItemHomepageManageKindsBinding? =
            binding as? ItemHomepageManageKindsBinding

        val actionBinding: ItemHomepageManageActionBinding? =
            binding as? ItemHomepageManageActionBinding
    }

    companion object {
        private const val TYPE_SECTION = 0
        private const val TYPE_SET = 1
        private const val TYPE_MODULE = 2
        private const val TYPE_SOURCE = 3
        private const val TYPE_KINDS = 4
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
