package io.legado.app.ui.book.cache

import android.os.Bundle
import android.view.View
import androidx.core.os.bundleOf
import androidx.core.content.ContextCompat
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.data.entities.Book
import io.legado.app.help.cache.CacheCoordinator
import io.legado.app.lib.theme.UiCorner
import io.legado.app.utils.gone
import io.legado.app.utils.setLayout
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import io.legado.app.utils.visible
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ReviewSnapshotStatusDialog :
    BaseDialogFragment(R.layout.dialog_review_snapshot_status),
    ReviewSnapshotStatusAdapter.Callback {

    private val binding by viewBinding(DialogReviewSnapshotStatusBinding::bind)
    private val viewModel by activityViewModels<CacheManageViewModel>()
    private val adapter by lazy { ReviewSnapshotStatusAdapter(requireContext(), this) }
    private val book: Book by lazy { requireArguments().getParcelable<Book>(ARG_BOOK)!! }
    private var loadJob: Job? = null
    private var reviewItems: List<ReviewSnapshotChapterItem> = emptyList()

    override fun onStart() {
        super.onStart()
        setLayout(0.92f, 0.76f)
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        binding.btnRetryAll.background = UiCorner.softActionSelector(
            ContextCompat.getColor(requireContext(), R.color.background_card),
            ContextCompat.getColor(requireContext(), R.color.background_menu),
            UiCorner.actionRadius(requireContext())
        )
        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerView.adapter = adapter
        binding.btnRetryAll.setOnClickListener {
            retryFailed(reviewItems.filter { it.failedSnapshots > 0 })
        }
        loadItems()
    }

    override fun retry(item: ReviewSnapshotChapterItem) {
        retryFailed(listOf(item))
    }

    private fun retryFailed(items: List<ReviewSnapshotChapterItem>) {
        if (items.isEmpty()) return
        lifecycleScope.launch {
            val count = withContext(Dispatchers.IO) {
                CacheCoordinator.retryReviewSnapshots(book, items.map { it.chapter })
            }
            if (count == 0) {
                toastOnUi(R.string.cache_manage_review_retry_unavailable)
            } else {
                toastOnUi(getString(R.string.cache_manage_review_retry_started, count))
            }
        }
    }

    private fun loadItems() {
        loadJob?.cancel()
        loadJob = lifecycleScope.launch {
            binding.rotateLoading.visible()
            binding.tvEmpty.gone()
            try {
                val items = viewModel.getReviewSnapshotItems(book)
                reviewItems = items
                adapter.setItems(items)
                binding.tvEmpty.run {
                    if (items.isEmpty()) {
                        setText(R.string.cache_manage_review_empty)
                        visible()
                    } else {
                        gone()
                    }
                }
                updateRetryAll(items)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                reviewItems = emptyList()
                adapter.setItems(emptyList())
                binding.tvEmpty.text = error.localizedMessage ?: getString(R.string.error)
                binding.tvEmpty.visible()
                updateRetryAll(emptyList())
            } finally {
                binding.rotateLoading.gone()
            }
        }
    }

    private fun updateRetryAll(items: List<ReviewSnapshotChapterItem>) {
        val enabled = items.any { it.failedSnapshots > 0 }
        binding.btnRetryAll.isEnabled = enabled
        binding.btnRetryAll.alpha = if (enabled) 1f else 0.45f
    }

    companion object {
        private const val ARG_BOOK = "book"

        fun newInstance(book: Book): ReviewSnapshotStatusDialog {
            return ReviewSnapshotStatusDialog().apply {
                arguments = bundleOf(ARG_BOOK to book)
            }
        }
    }
}
