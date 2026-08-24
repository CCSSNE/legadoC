package io.legado.app.ui.book.cache

import android.content.Context
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import io.legado.app.R
import io.legado.app.base.adapter.DiffRecyclerAdapter
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.data.entities.Book
import io.legado.app.databinding.ItemDownloadBinding
import io.legado.app.help.book.isAudio
import io.legado.app.help.book.isLocal
import io.legado.app.help.book.isVideo
import io.legado.app.help.cache.CacheCoordinator
import io.legado.app.help.cache.CacheKind
import io.legado.app.help.cache.CacheLifecycleRules
import io.legado.app.help.cache.CacheSubmission
import io.legado.app.utils.gone
import io.legado.app.utils.visible

class CacheAdapter(context: Context, private val callBack: CallBack) :
    DiffRecyclerAdapter<Book, ItemDownloadBinding>(context) {

    override val diffItemCallback: DiffUtil.ItemCallback<Book>
        get() = object : DiffUtil.ItemCallback<Book>() {
            override fun areItemsTheSame(oldItem: Book, newItem: Book): Boolean {
                return oldItem.bookUrl == newItem.bookUrl
            }

            override fun areContentsTheSame(oldItem: Book, newItem: Book): Boolean {
                return oldItem.name == newItem.name
                        && oldItem.author == newItem.author
            }

        }

    override fun getViewBinding(parent: ViewGroup): ItemDownloadBinding {
        return ItemDownloadBinding.inflate(inflater, parent, false)
    }

    override fun convert(
        holder: ItemViewHolder,
        binding: ItemDownloadBinding,
        item: Book,
        payloads: MutableList<Any>
    ) {
        binding.run {
            if (payloads.isEmpty()) {
                tvName.text = item.name
                tvAuthor.text = context.getString(R.string.author_show, item.getRealAuthor())
                if (item.isLocal) {
                    tvDownload.setText(R.string.local_book)
                } else {
                    upDownloadCount(item)
                }
            } else {
                if (item.isLocal) {
                    tvDownload.setText(R.string.local_book)
                } else {
                    upDownloadCount(item)
                }
            }
            if (item.isAudio) ivAudio.visible() else ivAudio.gone()
            upDownloadIv(ivDownload, item)
            upExportInfo(tvMsg, progressExport, item)
        }
    }

    /** 书籍类型对应的离线内容与评论双统计。 */
    private fun ItemDownloadBinding.upDownloadCount(item: Book) {
        val cacheSize = callBack.cacheChapters[item.bookUrl]?.size
        if (cacheSize == null) {
            tvDownload.setText(R.string.loading)
        } else {
            val reviewSize = callBack.reviewChapters[item.bookUrl]?.size ?: 0
            tvDownload.text = context.getString(
                if (item.isAudio) {
                    R.string.download_count_review_audio
                } else {
                    R.string.download_count_review
                },
                cacheSize,
                item.totalChapterNum,
                reviewSize,
                item.totalChapterNum
            )
        }
    }

    override fun registerListener(holder: ItemViewHolder, binding: ItemDownloadBinding) {
        binding.run {
            ivDownload.setOnClickListener {
                getItem(holder.layoutPosition)?.let { book ->
                    CacheCoordinator.snapshot.value.findDownloadTask(book)?.let {
                        CacheCoordinator.cancel(it)
                    } ?: callBack.showCacheRange(book)
                }
            }
            tvExport.setOnClickListener {
                callBack.export(holder.layoutPosition)
            }
        }
    }

    private fun upDownloadIv(iv: ImageView, book: Book) {
        if (book.isLocal) {
            iv.gone()
        } else {
            iv.visible()
            if (CacheCoordinator.snapshot.value.findDownloadTask(book) != null) {
                iv.setImageResource(R.drawable.ic_stop_black_24dp)
            } else {
                iv.setImageResource(R.drawable.ic_play_24dp)
            }
        }
    }

    private fun upExportInfo(msgView: TextView, progressView: ProgressBar, book: Book) {
        val msg = callBack.exportMsg(book.bookUrl)
        if (msg != null) {
            msgView.text = msg
            msgView.visible()
            progressView.gone()
            return
        }
        msgView.gone()
        val progress = callBack.exportProgress(book.bookUrl)
        if (progress != null) {
            progressView.max = book.totalChapterNum
            progressView.progress = progress
            progressView.visible()
            return
        }
        progressView.gone()
    }

    interface CallBack {
        val cacheChapters: HashMap<String, HashSet<String>>
        val reviewChapters: HashMap<String, HashSet<String>>
        fun showCacheRange(book: Book)
        fun sureCacheBook(action: () -> Unit)
        fun export(position: Int)
        fun exportProgress(bookUrl: String): Int?
        fun exportMsg(bookUrl: String): String?
    }
}

private fun io.legado.app.help.cache.CacheSnapshot.findDownloadTask(
    book: Book,
): CacheSubmission? {
    val kind = when {
        book.isAudio -> CacheKind.AUDIO
        book.isVideo -> CacheKind.VIDEO
        else -> CacheKind.TEXT
    }
    val task = sessions.asSequence()
        .flatMap { it.tasks.asSequence() }
        .firstOrNull {
            it.bookUrl == book.bookUrl &&
                it.kind == kind &&
                it.isCoordinatorDownloadTask() &&
                !CacheLifecycleRules.isTerminal(it.status)
        }
    return task?.let { CacheSubmission(it.sessionId, it.taskId) }
}
