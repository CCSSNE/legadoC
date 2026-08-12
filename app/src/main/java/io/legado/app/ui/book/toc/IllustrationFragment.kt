package io.legado.app.ui.book.toc

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import io.legado.app.R
import io.legado.app.base.VMBaseFragment
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookIllustration
import io.legado.app.databinding.FragmentIllustrationBinding
import io.legado.app.help.illustration.IllustrationHelp
import io.legado.app.help.illustration.imageSrcsFromJson
import io.legado.app.lib.dialogs.SelectItem
import io.legado.app.lib.dialogs.selector
import io.legado.app.lib.theme.primaryColor
import io.legado.app.ui.widget.dialog.showActionBottomSheet
import io.legado.app.ui.widget.recycler.VerticalDivider
import io.legado.app.utils.applyNavigationBarPadding
import io.legado.app.utils.setEdgeEffectColor
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 目录界面"配图"页签：集中展示全书配图，支持列表/宫格切换与跳转。
 */
class IllustrationFragment : VMBaseFragment<TocViewModel>(R.layout.fragment_illustration) {

    override val viewModel by activityViewModels<TocViewModel>()
    private val binding by viewBinding(FragmentIllustrationBinding::bind)

    private var bookUrl: String? = null
    private var book: Book? = null
    private var gridSpan = 0
    private var adapter: IllustrationAdapter? = null

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        binding.recyclerView.setEdgeEffectColor(primaryColor)
        binding.recyclerView.addItemDecoration(VerticalDivider(requireContext()))
        binding.recyclerView.applyNavigationBarPadding()
        binding.tvMode.setOnClickListener {
            showModeSelector()
        }
        binding.tvMultiSelect.setOnClickListener {
            toggleSelectionMode()
        }
        binding.tvSelectAll.setOnClickListener {
            selectAllToggle()
        }
        viewModel.bookData.observe(this) { book ->
            val adapter = IllustrationAdapter(
                book,
                onClick = { illustration -> openIllustration(illustration) },
                onLongClick = { illustration -> showItemMenu(book, illustration) },
                onSelectionLongClick = { illustration ->
                    showSelectionMenu(book, illustration)
                }
            )
            this.adapter = adapter
            this.book = book
            binding.recyclerView.adapter = adapter
            upModeUi()
            bookUrl = book.bookUrl
            observeIllustrations(book.bookUrl)
        }
    }

    private fun observeIllustrations(bookUrl: String) {
        lifecycleScope.launch {
            appDb.bookIllustrationDao.flowByBook(bookUrl).flowOn(IO).collect { list ->
                adapter?.setItems(list)
                binding.tvEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    private fun showModeSelector() {
        requireContext().selector(
            listOf(
                getString(R.string.illustration_list_mode),
                getString(R.string.illustration_layout_double),
                getString(R.string.illustration_layout_triple)
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
        val isGrid = gridSpan > 0
        binding.tvMode.text = getString(
            if (isGrid) R.string.illustration_grid_mode else R.string.illustration_list_mode
        )
        if (isGrid) {
            binding.recyclerView.layoutManager = GridLayoutManager(requireContext(), gridSpan)
            if (binding.recyclerView.itemDecorationCount > 0) {
                binding.recyclerView.removeItemDecorationAt(0)
            }
        } else {
            binding.recyclerView.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(requireContext())
            if (binding.recyclerView.itemDecorationCount == 0) {
                binding.recyclerView.addItemDecoration(VerticalDivider(requireContext()))
            }
        }
        adapter?.grid = isGrid
    }

    /** 多选开关：进入/退出多选模式 */
    private fun toggleSelectionMode() {
        val adapter = adapter ?: return
        if (adapter.itemCount == 0 && !adapter.selectionMode) return
        adapter.selectionMode = !adapter.selectionMode
        upSelectionUi()
    }

    /** 全选开关：未选满则全选，已选满则全部取消；未进入多选时先进入多选 */
    private fun selectAllToggle() {
        val adapter = adapter ?: return
        if (adapter.itemCount == 0) return
        if (!adapter.selectionMode) {
            adapter.selectionMode = true
            upSelectionUi()
        }
        adapter.toggleSelectAll()
    }

    private fun upSelectionUi() {
        binding.tvMultiSelect.text = getString(
            if (adapter?.selectionMode == true) {
                R.string.illustration_done
            } else {
                R.string.illustration_multi_select
            }
        )
    }

    /** 多选模式长按：保存（单张）/ 保存所有（所选）/ 删除所选 */
    private fun showSelectionMenu(book: Book, illustration: BookIllustration) {
        showActionBottomSheet(
            requireContext(),
            listOf(
                SelectItem(getString(R.string.illustration_save_to_album), "save"),
                SelectItem(getString(R.string.illustration_save_all), "saveAll"),
                SelectItem(getString(R.string.illustration_delete_selected), "deleteSelected")
            )
        ) { action ->
            when (action) {
                "save" -> illustration.imageSrcsFromJson().firstOrNull()?.let {
                    saveToAlbum(book, it)
                }
                "saveAll" -> saveAllIllustrations(book, adapter?.selectedItems().orEmpty())
                "deleteSelected" -> deleteSelected(book)
            }
        }
    }

    /** 目录页长按配图：底部菜单，保存在前、删除在后；单图显示"保存到相册"，多图显示"保存所有" */
    private fun showItemMenu(book: Book, illustration: BookIllustration) {
        val multi = illustration.imageSrcsFromJson().size >= 2
        showActionBottomSheet(
            requireContext(),
            listOf(
                SelectItem(
                    getString(
                        if (multi) R.string.illustration_save_all
                        else R.string.illustration_save_to_album
                    ),
                    "save"
                ),
                SelectItem(getString(R.string.illustration_delete), "delete")
            )
        ) { action ->
            when (action) {
                "save" -> saveAllIllustrations(book, listOf(illustration))
                "delete" -> deleteIllustration(book, illustration)
            }
        }
    }

    private fun saveToAlbum(book: Book, src: String) {
        lifecycleScope.launch(IO) {
            val ok = IllustrationHelp.saveToAlbum(requireContext(), book, src)
            withContext(Main) {
                toastOnUi(
                    if (ok) R.string.illustration_saved_to_album else R.string.illustration_save_failed
                )
            }
        }
    }

    private fun saveAllIllustrations(book: Book, illustrations: List<BookIllustration>) {
        if (illustrations.isEmpty()) return
        lifecycleScope.launch(IO) {
            val srcs = illustrations.flatMap { it.imageSrcsFromJson() }.distinct()
            var ok = true
            srcs.forEach { src ->
                if (!IllustrationHelp.saveToAlbum(requireContext(), book, src)) {
                    ok = false
                }
            }
            withContext(Main) {
                toastOnUi(
                    if (ok) R.string.illustration_saved_to_album else R.string.illustration_save_failed
                )
            }
        }
    }

    private fun deleteSelected(book: Book) {
        val selected = adapter?.selectedItems().orEmpty()
        if (selected.isEmpty()) return
        selected.forEach { illustration ->
            appDb.bookIllustrationDao.delete(illustration)
            IllustrationHelp.deleteImages(book, illustration.imageSrcsFromJson())
        }
        toastOnUi(R.string.illustration_deleted)
        adapter?.selectionMode = false
        upSelectionUi()
    }

    private fun deleteIllustration(book: Book, illustration: BookIllustration) {
        appDb.bookIllustrationDao.delete(illustration)
        IllustrationHelp.deleteImages(book, illustration.imageSrcsFromJson())
        toastOnUi(R.string.illustration_deleted)
    }

    private fun openIllustration(illustration: BookIllustration) {
        activity?.run {
            setResult(
                Activity.RESULT_OK,
                Intent().apply {
                    putExtra("index", illustration.chapterIndex)
                    putExtra(
                        "chapterPos",
                        if (illustration.anchorType == BookIllustration.ANCHOR_CHAPTER_END) {
                            Int.MAX_VALUE
                        } else {
                            illustration.anchorPos
                        }
                    )
                }
            )
            finish()
        }
    }
}
