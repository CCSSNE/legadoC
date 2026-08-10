package io.legado.app.ui.main.bookshelf.style1.books

import android.content.Context
import android.os.Bundle
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.viewbinding.ViewBinding
import io.legado.app.R
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.data.entities.Book
import io.legado.app.databinding.ItemBookshelfCollectionListBinding
import io.legado.app.databinding.ItemBookshelfListBinding
import io.legado.app.help.book.isLocal
import io.legado.app.help.config.AppConfig
import io.legado.app.ui.main.bookshelf.BookCollectionShelfItem
import io.legado.app.ui.main.bookshelf.loadCollectionCovers
import io.legado.app.utils.invisible
import io.legado.app.utils.toTimeAgo
import io.legado.app.utils.visible

class BooksAdapterList(
    context: Context,
    private val fragment: Fragment,
    private val callBack: CallBack,
    private val lifecycle: Lifecycle
) : BaseBooksAdapter<ViewBinding>(context) {

    override fun getViewBinding(parent: ViewGroup): ViewBinding {
        return getViewBinding(parent, VIEW_TYPE_BOOK)
    }

    override fun getViewBinding(parent: ViewGroup, viewType: Int): ViewBinding {
        if (viewType == VIEW_TYPE_COLLECTION) {
            return ItemBookshelfCollectionListBinding.inflate(inflater, parent, false)
        }
        return ItemBookshelfListBinding.inflate(inflater, parent, false)
    }

    override fun convert(
        holder: ItemViewHolder,
        binding: ViewBinding,
        item: Any,
        payloads: MutableList<Any>
    ) {
        holder.itemView.alpha = if (item is Book && callBack.isSelected(item)) 0.62f else 1f
        if (item is BookCollectionShelfItem && binding is ItemBookshelfCollectionListBinding) {
            binding.run {
                tvName.text = item.name
                tvCount.text = context.getString(R.string.book_collection_count, item.count)
                listOf(
                    coverMosaic.ivCover1,
                    coverMosaic.ivCover2,
                    coverMosaic.ivCover3,
                    coverMosaic.ivCover4
                ).loadCollectionCovers(item.previewBooks, fragment, lifecycle)
            }
            return
        }
        if (item !is Book || binding !is ItemBookshelfListBinding) return
        binding.run {
        if (payloads.isEmpty()) {
            tvName.text = item.name
            tvAuthor.text = item.author
            tvRead.text = item.durChapterTitle
            tvLast.text = item.latestChapterTitle
            ivCover.loadThumb(item, false, fragment, lifecycle)
            ivLocal.visible(AppConfig.showLocalBookIcon && item.isLocal)
            upRefresh(binding, item)
            upLastUpdateTime(binding, item)
        } else {
            for (i in payloads.indices) {
                val bundle = payloads[i] as Bundle
                bundle.keySet().forEach {
                    when (it) {
                        "name" -> tvName.text = item.name
                        "author" -> tvAuthor.text = item.author
                        "dur" -> tvRead.text = item.durChapterTitle
                        "last" -> tvLast.text = item.latestChapterTitle
                        "cover" -> ivCover.loadThumb(
                            item,
                            false,
                            fragment,
                            lifecycle
                        )

                        "refresh" -> upRefresh(binding, item)
                        "lastUpdateTime" -> upLastUpdateTime(binding, item)
                        "local" -> ivLocal.visible(AppConfig.showLocalBookIcon && item.isLocal)
                    }
                }
            }
        }
        }
    }

    private fun upRefresh(binding: ItemBookshelfListBinding, item: Book) {
        if (!item.isLocal && callBack.isUpdate(item.bookUrl)) {
            binding.bvUnread.invisible()
            binding.rlLoading.visible()
        } else {
            binding.rlLoading.gone()
            if (AppConfig.showUnread) {
                binding.bvUnread.setHighlight(item.lastCheckCount > 0)
                binding.bvUnread.setBadgeCount(item.getUnreadChapterNum())
            } else {
                binding.bvUnread.invisible()
            }
        }
    }

    private fun upLastUpdateTime(binding: ItemBookshelfListBinding, item: Book) {
        if (AppConfig.showLastUpdateTime && !item.isLocal) {
            val time = item.latestChapterTime.toTimeAgo()
            if (binding.tvLastUpdateTime.text != time) {
                binding.tvLastUpdateTime.text = time
            }
        } else {
            binding.tvLastUpdateTime.text = ""
        }
    }

    override fun registerListener(holder: ItemViewHolder, binding: ViewBinding) {
        holder.itemView.apply {
            setOnClickListener {
                getItem(holder.layoutPosition)?.let {
                    when (it) {
                        is Book -> callBack.onBookClickInSelection(it)
                        is BookCollectionShelfItem -> callBack.openCollection(it)
                    }
                }
            }

            bindBookTouch(
                bookProvider = { getItem(holder.layoutPosition) as? Book },
                callBack = callBack
            )
        }
    }
}
