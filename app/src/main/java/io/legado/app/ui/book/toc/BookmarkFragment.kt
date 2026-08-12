package io.legado.app.ui.book.toc

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.jaredrummler.android.colorpicker.ColorPickerDialog
import com.jaredrummler.android.colorpicker.ColorPickerDialogListener
import com.jaredrummler.android.colorpicker.ColorShape
import io.legado.app.R
import io.legado.app.base.VMBaseFragment
import io.legado.app.constant.AppLog
import io.legado.app.constant.EventBus
import io.legado.app.data.appDb
import io.legado.app.data.entities.Bookmark
import io.legado.app.data.entities.BookmarkStyle
import io.legado.app.databinding.FragmentBookmarkBinding
import io.legado.app.lib.dialogs.SelectItem
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.dialogs.selector
import io.legado.app.lib.prefs.ColorPreference
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.primaryColor
import io.legado.app.lib.theme.view.ThemeCheckBox
import io.legado.app.ui.book.bookmark.BookmarkDialog
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.widget.dialog.showActionBottomSheet
import io.legado.app.ui.widget.recycler.UpLinearLayoutManager
import io.legado.app.ui.widget.recycler.VerticalDivider
import io.legado.app.utils.FileDoc
import io.legado.app.utils.GSON
import io.legado.app.utils.applyNavigationBarPadding
import io.legado.app.utils.createFileIfNotExist
import io.legado.app.utils.dpToPx
import io.legado.app.utils.openOutputStream
import io.legado.app.utils.postEvent
import io.legado.app.utils.setEdgeEffectColor
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import splitties.init.appCtx


class BookmarkFragment : VMBaseFragment<TocViewModel>(R.layout.fragment_bookmark),
    BookmarkAdapter.Callback,
    ColorPickerDialogListener,
    TocViewModel.BookmarkCallBack {
    override val viewModel by activityViewModels<TocViewModel>()
    private val binding by viewBinding(FragmentBookmarkBinding::bind)
    private var mLayoutManager: UpLinearLayoutManager? = null
    private val adapter by lazy { BookmarkAdapter(requireContext(), this) }
    private var durChapterIndex = 0
    private var gridSpan = 0
    private var exportSelectedBookmarks: List<Bookmark> = emptyList()
    private var batchColorTargets: List<Bookmark> = emptyList()

    private val exportDir = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { uri ->
            when (it.requestCode) {
                1 -> exportBookmarkJson(uri, exportSelectedBookmarks)
                2 -> exportBookmarkMd(uri, exportSelectedBookmarks)
            }
        }
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        viewModel.bookMarkCallBack = this
        initView()
        initRecyclerView()
        viewModel.bookData.observe(this) {
            durChapterIndex = it.durChapterIndex
            upBookmark(null)
        }
    }

    private fun initView() {
        binding.tvMode.setOnClickListener {
            showModeSelector()
        }
        binding.tvMultiSelect.setOnClickListener {
            toggleSelectionMode()
        }
        binding.tvSelectAll.setOnClickListener {
            selectAllToggle()
        }
    }

    private fun initRecyclerView() {
        // 每次视图重建都新建 LayoutManager，避免复用已绑定旧 RecyclerView 的实例导致崩溃
        val layoutManager = UpLinearLayoutManager(requireContext())
        mLayoutManager = layoutManager
        binding.recyclerView.setEdgeEffectColor(primaryColor)
        binding.recyclerView.layoutManager = layoutManager
        binding.recyclerView.addItemDecoration(VerticalDivider(requireContext()))
        binding.recyclerView.adapter = adapter
        binding.recyclerView.applyNavigationBarPadding()
    }

    override fun upBookmark(searchKey: String?) {
        val book = viewModel.bookData.value ?: return
        lifecycleScope.launch {
            when {
                searchKey.isNullOrBlank() -> appDb.bookmarkDao.flowByBook(book.name, book.author)
                else -> appDb.bookmarkDao.flowSearch(book.name, book.author, searchKey)
            }.catch {
                AppLog.put("目录界面获取书签数据失败\n${it.localizedMessage}", it)
            }.flowOn(IO).collect {
                adapter.setItems(it)
                binding.tvEmpty.visibility = if (it.isEmpty()) View.VISIBLE else View.GONE
                var scrollPos = 0
                withContext(Dispatchers.Default) {
                    adapter.getItems().forEachIndexed { index, bookmark ->
                        if (bookmark.chapterIndex >= durChapterIndex) {
                            return@withContext
                        }
                        scrollPos = index
                    }
                }
                if (gridSpan == 0) {
                    mLayoutManager?.scrollToPositionWithOffset(scrollPos, 0)
                }
            }
        }
    }

    /** 显示模式：列表 / 网格二列 / 网格三列 */
    private fun showModeSelector() {
        requireContext().selector(
            listOf(
                getString(R.string.bookmark_list_mode),
                getString(R.string.bookmark_grid_two),
                getString(R.string.bookmark_grid_three)
            )
        ) { _, _, index ->
            gridSpan = when (index) {
                1 -> 2
                2 -> 3
                else -> 0
            }
            upModeUi()
        }
    }

    private fun upModeUi() {
        binding.tvMode.text = when (gridSpan) {
            2 -> getString(R.string.bookmark_grid_two)
            3 -> getString(R.string.bookmark_grid_three)
            else -> getString(R.string.bookmark_list_mode)
        }
        if (gridSpan > 0) {
            binding.recyclerView.layoutManager = GridLayoutManager(requireContext(), gridSpan)
            if (binding.recyclerView.itemDecorationCount > 0) {
                binding.recyclerView.removeItemDecorationAt(0)
            }
        } else {
            val layoutManager = UpLinearLayoutManager(requireContext())
            mLayoutManager = layoutManager
            binding.recyclerView.layoutManager = layoutManager
            if (binding.recyclerView.itemDecorationCount == 0) {
                binding.recyclerView.addItemDecoration(VerticalDivider(requireContext()))
            }
        }
        adapter.grid = gridSpan > 0
    }

    private fun toggleSelectionMode() {
        if (adapter.itemCount == 0 && !adapter.selectionMode) return
        adapter.selectionMode = !adapter.selectionMode
        upSelectionUi()
    }

    private fun selectAllToggle() {
        if (adapter.itemCount == 0) return
        if (!adapter.selectionMode) {
            adapter.selectionMode = true
            upSelectionUi()
        }
        adapter.toggleSelectAll()
    }

    private fun upSelectionUi() {
        binding.tvMultiSelect.text = getString(
            if (adapter.selectionMode) R.string.bookmark_done else R.string.bookmark_multi_select
        )
    }

    /** 多选模式下长按已选中书签：弹出批量操作菜单 */
    override fun onSelectionLongClick(bookmark: Bookmark) {
        showActionBottomSheet(
            requireContext(),
            listOf(
                SelectItem(getString(R.string.bookmark_delete_selected), "delete"),
                SelectItem(getString(R.string.bookmark_export_selected), "export"),
                SelectItem(getString(R.string.bookmark_export_selected_md), "exportMd"),
                SelectItem(getString(R.string.bookmark_edit_remark), "remark"),
                SelectItem(getString(R.string.bookmark_edit_style), "style"),
                SelectItem(getString(R.string.bookmark_edit_color), "color")
            )
        ) { action ->
            when (action) {
                "delete" -> deleteSelected()
                "export" -> exportSelected(false)
                "exportMd" -> exportSelected(true)
                "remark" -> editSelectedRemark()
                "style" -> editSelectedStyle()
                "color" -> editSelectedColor()
            }
        }
    }

    private fun deleteSelected() {
        val selected = adapter.selectedItems()
        if (selected.isEmpty()) return
        lifecycleScope.launch(IO) {
            appDb.bookmarkDao.delete(*selected.toTypedArray())
            postEvent(EventBus.BOOKMARK_CHANGED, true)
            withContext(Dispatchers.Main) {
                adapter.selectionMode = false
                upSelectionUi()
            }
        }
    }

    private fun exportSelected(isMd: Boolean) {
        val selected = adapter.selectedItems()
        if (selected.isEmpty()) return
        exportSelectedBookmarks = selected
        exportDir.launch {
            requestCode = if (isMd) 2 else 1
        }
    }

    private fun exportBookmarkJson(uri: android.net.Uri, list: List<Bookmark>) {
        lifecycleScope.launch(IO) {
            runCatching {
                val dateFormat = SimpleDateFormat("yyMMddHHmmss", Locale.getDefault())
                val fileName = "bookmark-${dateFormat.format(Date())}.json"
                val dirDoc = FileDoc.fromUri(uri, true)
                dirDoc.createFileIfNotExist(fileName).openOutputStream().getOrThrow().use {
                    GSON.writeToOutputStream(it, list)
                }
                withContext(Dispatchers.Main) {
                    toastOnUi(R.string.export_success)
                }
            }.onFailure {
                AppLog.put("导出书签失败\n${it.localizedMessage}", it, true)
            }
        }
    }

    private fun exportBookmarkMd(uri: android.net.Uri, list: List<Bookmark>) {
        lifecycleScope.launch(IO) {
            runCatching {
                val dateFormat = SimpleDateFormat("yyMMddHHmmss", Locale.getDefault())
                val fileName = "bookmark-${dateFormat.format(Date())}.md"
                val dirDoc = FileDoc.fromUri(uri, true)
                val fileDoc = dirDoc.createFileIfNotExist(fileName).openOutputStream().getOrThrow()
                fileDoc.use { outputStream ->
                    list.forEach { bookmark ->
                        outputStream.write("#### ${bookmark.chapterName}\n\n".toByteArray())
                        outputStream.write("###### 原文\n ${bookmark.bookText}\n\n".toByteArray())
                        outputStream.write("###### 摘要\n ${bookmark.content}\n\n".toByteArray())
                    }
                }
                withContext(Dispatchers.Main) {
                    toastOnUi(R.string.export_success)
                }
            }.onFailure {
                AppLog.put("导出书签失败\n${it.localizedMessage}", it, true)
            }
        }
    }

    private fun editSelectedRemark() {
        val selected = adapter.selectedItems()
        if (selected.isEmpty()) return
        val editText = EditText(requireContext())
        alert(R.string.bookmark_edit_remark) {
            customView { editText }
            okButton {
                val value = editText.text?.toString().orEmpty()
                batchUpdate(selected) { it.content = value }
            }
            noButton()
        }
    }

    private fun editSelectedStyle() {
        val selected = adapter.selectedItems()
        if (selected.isEmpty()) return
        val styleBoxes = arrayListOf<ThemeCheckBox>()
        val container = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            val padding = 8.dpToPx()
            setPadding(padding, padding, padding, padding)
        }
        fun addBox(text: String): ThemeCheckBox {
            return ThemeCheckBox(requireContext()).apply {
                this.text = text
                container.addView(this, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ))
                styleBoxes.add(this)
            }
        }
        val cbNone = addBox(getString(R.string.bookmark_style_none))
        val cbSingle = addBox(getString(R.string.bookmark_style_single))
        val cbDouble = addBox(getString(R.string.bookmark_style_double))
        val cbWave = addBox(getString(R.string.bookmark_style_wave))
        val cbHighlight = addBox(getString(R.string.bookmark_style_highlight))
        val cbTextColor = addBox(getString(R.string.bookmark_style_text_color))
        val cbStrikethrough = addBox(getString(R.string.bookmark_style_strikethrough))
        val effectBoxes = listOf(cbSingle, cbDouble, cbWave, cbHighlight, cbTextColor, cbStrikethrough)
        cbNone.setOnCheckedChangeListener { _, checked ->
            if (checked) effectBoxes.forEach { it.isChecked = false }
        }
        effectBoxes.forEach { box ->
            box.setOnCheckedChangeListener { _, checked ->
                if (checked) cbNone.isChecked = false
            }
        }
        alert(R.string.bookmark_select_style_title) {
            customView { container }
            okButton {
                var styles = BookmarkStyle.NONE
                if (cbSingle.isChecked) styles = styles or BookmarkStyle.SINGLE_UNDERLINE
                if (cbDouble.isChecked) styles = styles or BookmarkStyle.DOUBLE_UNDERLINE
                if (cbWave.isChecked) styles = styles or BookmarkStyle.WAVE_UNDERLINE
                if (cbHighlight.isChecked) styles = styles or BookmarkStyle.HIGHLIGHT
                if (cbTextColor.isChecked) styles = styles or BookmarkStyle.TEXT_COLOR
                if (cbStrikethrough.isChecked) styles = styles or BookmarkStyle.STRIKETHROUGH
                batchUpdate(selected) { it.style = styles }
            }
            noButton()
        }
    }

    private fun editSelectedColor() {
        val selected = adapter.selectedItems()
        if (selected.isEmpty()) return
        batchColorTargets = selected
        val dialog = ColorPreference.ColorPickerDialogCompat.newBuilder()
            .setDialogType(ColorPickerDialog.TYPE_PRESETS)
            .setDialogTitle(R.string.bookmark_edit_color)
            .setColorShape(ColorShape.CIRCLE)
            .setPresets(ColorPickerDialog.MATERIAL_COLORS)
            .setAllowPresets(true)
            .setAllowCustom(true)
            .setShowAlphaSlider(false)
            .setShowColorShades(true)
            .setShowDefaultColorButton(true)
            .setColor(appCtx.accentColor)
            .setDialogId(1)
            .create()
        dialog.setColorPickerDialogListener(this)
        dialog.show(childFragmentManager, "bookmark_batch_color_picker")
    }

    override fun onColorSelected(dialogId: Int, color: Int) {
        val targets = batchColorTargets
        if (targets.isEmpty()) return
        val value = if (color == ColorPreference.ColorPickerDialogCompat.DEFAULT_COLOR) 0 else color
        batchUpdate(targets) { it.color = value }
        batchColorTargets = emptyList()
    }

    override fun onDialogDismissed(dialogId: Int) {
    }

    private fun batchUpdate(
        targets: List<Bookmark>,
        modify: (Bookmark) -> Unit
    ) {
        lifecycleScope.launch(IO) {
            targets.forEach { modify(it) }
            appDb.bookmarkDao.update(*targets.toTypedArray())
            postEvent(EventBus.BOOKMARK_CHANGED, true)
            withContext(Dispatchers.Main) {
                toastOnUi(R.string.bookmark_batch_edit_success)
            }
        }
    }

    override fun onClick(bookmark: Bookmark) {
        activity?.run {
            setResult(Activity.RESULT_OK, Intent().apply {
                putExtra("index", bookmark.chapterIndex)
                putExtra("chapterPos", bookmark.chapterPos)
            })
            finish()
        }
    }

    override fun onLongClick(bookmark: Bookmark, pos: Int) {
        showDialogFragment(BookmarkDialog(bookmark, pos))
    }

}
