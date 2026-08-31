package io.legado.app.ui.book.cache

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.core.os.bundleOf
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.databinding.DialogTtsCacheChaptersBinding
import io.legado.app.help.cache.CacheCoordinator
import io.legado.app.help.cache.CacheRequestSource
import io.legado.app.help.tts.TtsCacheParams
import io.legado.app.lib.permission.NotificationPermission
import io.legado.app.utils.setLayout
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import io.legado.app.utils.visible
import kotlinx.coroutines.launch

/**
 * TTS 缓存章节选择弹窗：每 20 章合并为一条折叠组（默认折叠、点开展开、
 * 组条目可整组勾选），提交走 CacheCoordinator 的 TEXT+TTS 任务。
 */
class TtsCacheChapterDialog :
    BaseDialogFragment(R.layout.dialog_tts_cache_chapters),
    TtsCacheChapterAdapter.Callback {

    private val binding by viewBinding(DialogTtsCacheChaptersBinding::bind)
    private val adapter by lazy { TtsCacheChapterAdapter(requireContext(), this) }
    private val book: Book by lazy {
        requireArguments().getParcelable<Book>("book")!!
    }

    override fun onStart() {
        super.onStart()
        setLayout(0.92f, 0.8f)
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerView.adapter = adapter
        binding.btnSelectAll.setOnClickListener { adapter.selectAll() }
        binding.btnStartCache.setOnClickListener { startCache() }
        updateSelectionBar()
        loadChapters()
    }

    override fun onSelectionChanged() {
        updateSelectionBar()
    }

    private fun loadChapters() {
        binding.rotateLoading.visible()
        lifecycleScope.launch {
            kotlin.runCatching {
                appDb.bookChapterDao.getChapterList(book.bookUrl)
            }.onSuccess { chapters ->
                binding.rotateLoading.gone()
                adapter.setChapters(chapters)
                binding.tvEmpty.visible(chapters.isEmpty())
            }.onFailure {
                binding.rotateLoading.gone()
                toastOnUi(it.localizedMessage)
            }
        }
    }

    private fun updateSelectionBar() {
        val count = adapter.selectedCount()
        binding.tvSelectionCount.text = getString(R.string.tts_cache_selected_count, count)
        binding.btnStartCache.isEnabled = count > 0
        binding.btnStartCache.alpha = if (count > 0) 1f else 0.45f
    }

    private fun startCache() {
        val indexes = adapter.selectedIndexes()
        if (indexes.isEmpty()) return
        // 前置与各入口按钮同源（[TtsCacheParams.unavailableReasonRes]）
        TtsCacheParams.unavailableReasonRes(book)?.let { reason ->
            toastOnUi(reason)
            return
        }
        NotificationPermission.ensure(
            requireContext(),
            onGranted = { submit(indexes) },
            onDenied = {
                toastOnUi(R.string.notification_permission_required_for_download)
            }
        )
    }

    private fun submit(indexes: List<Int>) {
        lifecycleScope.launch {
            kotlin.runCatching {
                CacheCoordinator.submitTtsCacheDownload(
                    book,
                    indexes,
                    CacheRequestSource.READER,
                )
            }.onSuccess {
                toastOnUi(R.string.tts_cache_started)
                dismissAllowingStateLoss()
            }.onFailure {
                toastOnUi(getString(R.string.tts_cache_failed, it.localizedMessage))
            }
        }
    }

    companion object {
        fun newInstance(book: Book): TtsCacheChapterDialog {
            return TtsCacheChapterDialog().apply {
                arguments = bundleOf("book" to book)
            }
        }
    }
}
