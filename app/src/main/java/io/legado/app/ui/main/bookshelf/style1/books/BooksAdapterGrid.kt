package io.legado.app.ui.main.bookshelf.style1.books

import android.content.Context
import android.os.Bundle
import android.view.ViewGroup
import androidx.viewbinding.ViewBinding
import io.legado.app.R
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.data.entities.Book
import io.legado.app.databinding.ItemBookshelfCollectionGridBinding
import io.legado.app.databinding.ItemBookshelfGrid2Binding
import io.legado.app.databinding.ItemBookshelfGridBinding
import io.legado.app.help.book.isLocal
import io.legado.app.help.config.AppConfig
import io.legado.app.ui.main.bookshelf.BookCollectionShelfItem
import io.legado.app.ui.main.bookshelf.loadCollectionCovers
import io.legado.app.utils.gone
import io.legado.app.utils.invisible
import io.legado.app.utils.visible

class BooksAdapterGrid(context: Context, private val callBack: CallBack) :
    BaseBooksAdapter<ViewBinding>(context) {
    private val showBookname = AppConfig.showBookname
    override fun getViewBinding(parent: ViewGroup): ViewBinding {
        return getViewBinding(parent, VIEW_TYPE_BOOK)
    }

    override fun getViewBinding(parent: ViewGroup, viewType: Int): ViewBinding {
        if (viewType == VIEW_TYPE_COLLECTION) {
            return ItemBookshelfCollectionGridBinding.inflate(inflater, parent, false)
        }
        return when (showBookname) {
            2 -> ItemBookshelfGrid2Binding.inflate(inflater, parent, false)
            else -> ItemBookshelfGridBinding.inflate(inflater, parent, false)
        }
    }

    override fun convert(
        holder: ItemViewHolder,
        binding: ViewBinding,
        item: Any,
        payloads: MutableList<Any>
    ) {
        if (item is BookCollectionShelfItem && binding is ItemBookshelfCollectionGridBinding) {
            binding.run {
                renderSelectionMark(selectionOuter, selectionDot, item, callBack)
                if (isSelectionPayload(payloads)) return
                tvName.text = item.name
                listOf(
                    coverMosaic.ivCover1,
                    coverMosaic.ivCover2,
                    coverMosaic.ivCover3,
                    coverMosaic.ivCover4
                ).loadCollectionCovers(item.previewBooks)
            }
            return
        }
        if (item !is Book) return
        when (binding) {
            is ItemBookshelfGridBinding -> binding.run {
                renderSelectionMark(selectionOuter, selectionDot, item, callBack)
                if (payloads.isEmpty()) {
                    if (showBookname == 0) {
                        tvName.visible()
                        tvName.text = item.name
                    } else {
                        tvName.gone()
                    }
                    ivCover.loadThumb(item, false)
                    ivLocal.visible(AppConfig.showLocalBookIcon && item.isLocal)
                    upRefresh(binding, item)
                } else {
                    for (i in payloads.indices) {
                        val bundle = payloads[i] as Bundle
                        bundle.keySet().forEach {
                            when (it) {
                                "name" -> tvName.text = item.name
                                "cover" -> ivCover.loadThumb(
                                    item,
                                    false
                                )

                                "refresh" -> upRefresh(binding, item)
                                "local" -> ivLocal.visible(AppConfig.showLocalBookIcon && item.isLocal)
                            }
                        }
                    }
                }
            }
            is ItemBookshelfGrid2Binding -> binding.run {
                renderSelectionMark(selectionOuter, selectionDot, item, callBack)
                if (payloads.isEmpty()) {
                    tvName.text = item.name
                    ivCover.loadThumb(item, false)
                    ivLocal.visible(AppConfig.showLocalBookIcon && item.isLocal)
                    upRefresh(binding, item)
                } else {
                    for (i in payloads.indices) {
                        val bundle = payloads[i] as Bundle
                        bundle.keySet().forEach {
                            when (it) {
                                "name" -> tvName.text = item.name
                                "cover" -> ivCover.loadThumb(
                                    item,
                                    false
                                )

                                "refresh" -> upRefresh(binding, item)
                                "local" -> ivLocal.visible(AppConfig.showLocalBookIcon && item.isLocal)
                            }
                        }
                    }
                }
            }
        }

    }

    private fun upRefresh(binding: ViewBinding, item: Book) {
        when (binding) {
            is ItemBookshelfGridBinding -> binding.run {
                if (!item.isLocal && callBack.isUpdate(item.bookUrl)) {
                    bvUnread.invisible()
                    rlLoading.visible()
                } else {
                    rlLoading.inVisible()
                    if (AppConfig.showUnread) {
                        bvUnread.setBadgeCount(item.getUnreadChapterNum())
                        bvUnread.setHighlight(item.lastCheckCount > 0)
                    } else {
                        bvUnread.invisible()
                    }
                }
            }
            is ItemBookshelfGrid2Binding -> binding.run {
                if (!item.isLocal && callBack.isUpdate(item.bookUrl)) {
                    bvUnread.invisible()
                    rlLoading.visible()
                } else {
                    rlLoading.inVisible()
                    if (AppConfig.showUnread) {
                        bvUnread.setBadgeCount(item.getUnreadChapterNum())
                        bvUnread.setHighlight(item.lastCheckCount > 0)
                    } else {
                        bvUnread.invisible()
                    }
                }
            }
        }
    }

    override fun registerListener(holder: ItemViewHolder, binding: ViewBinding) {
        holder.itemView.apply {
            setOnClickListener {
                getItem(holder.layoutPosition)?.let {
                    when (it) {
                        is Book -> callBack.onBookClickInSelection(it)
                        is BookCollectionShelfItem -> callBack.onCollectionClickInSelection(it)
                    }
                }
            }

            bindShelfTouch(
                itemProvider = { getItem(holder.layoutPosition) },
                callBack = callBack
            )
        }
    }
}
