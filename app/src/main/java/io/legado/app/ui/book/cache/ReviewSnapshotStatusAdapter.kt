package io.legado.app.ui.book.cache

import android.content.Context
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import io.legado.app.R
import io.legado.app.base.adapter.DiffRecyclerAdapter
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.databinding.ItemReviewSnapshotStatusBinding
import io.legado.app.utils.gone
import io.legado.app.utils.visible

class ReviewSnapshotStatusAdapter(
    context: Context,
    private val callback: Callback,
) : DiffRecyclerAdapter<ReviewSnapshotChapterItem, ItemReviewSnapshotStatusBinding>(context) {

    override val diffItemCallback: DiffUtil.ItemCallback<ReviewSnapshotChapterItem> =
        object : DiffUtil.ItemCallback<ReviewSnapshotChapterItem>() {
            override fun areItemsTheSame(
                oldItem: ReviewSnapshotChapterItem,
                newItem: ReviewSnapshotChapterItem,
            ): Boolean {
                return oldItem.chapter.bookUrl == newItem.chapter.bookUrl &&
                    oldItem.chapter.url == newItem.chapter.url
            }

            override fun areContentsTheSame(
                oldItem: ReviewSnapshotChapterItem,
                newItem: ReviewSnapshotChapterItem,
            ): Boolean = oldItem == newItem
        }

    override fun getViewBinding(parent: ViewGroup): ItemReviewSnapshotStatusBinding {
        return ItemReviewSnapshotStatusBinding.inflate(inflater, parent, false)
    }

    override fun convert(
        holder: ItemViewHolder,
        binding: ItemReviewSnapshotStatusBinding,
        item: ReviewSnapshotChapterItem,
        payloads: MutableList<Any>,
    ) = binding.run {
        tvChapter.text = context.getString(R.string.cache_manage_review_chapter, item.chapter.index + 1)
        tvProgress.text = item.totalSnapshots?.let { total ->
            context.getString(R.string.cache_manage_review_progress, item.completedSnapshots, total)
        } ?: context.getString(R.string.cache_manage_review_cached_unknown, item.completedSnapshots)
        if (item.failedSnapshots > 0) {
            tvState.text = context.getString(R.string.cache_manage_review_failed, item.failedSnapshots)
            btnRetry.visible()
        } else {
            tvState.setText(R.string.cache_manage_review_success)
            btnRetry.gone()
        }
    }

    override fun registerListener(holder: ItemViewHolder, binding: ItemReviewSnapshotStatusBinding) {
        binding.btnRetry.setOnClickListener {
            getItem(holder.layoutPosition)
                ?.takeIf { it.failedSnapshots > 0 }
                ?.let(callback::retry)
        }
    }

    interface Callback {
        fun retry(item: ReviewSnapshotChapterItem)
    }
}
