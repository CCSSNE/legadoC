package io.legado.app.ui.book.explore

import android.content.Context
import android.os.Bundle
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.viewbinding.ViewBinding
import io.legado.app.R
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.base.adapter.RecyclerAdapter
import io.legado.app.data.entities.SearchBook
import io.legado.app.databinding.ItemExploreBookGridBinding
import io.legado.app.databinding.ItemSearchBinding
import io.legado.app.help.config.AppConfig
import io.legado.app.utils.gone
import io.legado.app.utils.visible


class ExploreShowAdapter(context: Context, val callBack: CallBack) :
    RecyclerAdapter<SearchBook, ViewBinding>(context) {

    var layoutStyle: Int = 0

    override fun getViewBinding(parent: ViewGroup): ViewBinding {
        return when (layoutStyle) {
            1, 2 -> ItemExploreBookGridBinding.inflate(inflater, parent, false)
            else -> ItemSearchBinding.inflate(inflater, parent, false)
        }
    }

    override fun getItemViewType(item: SearchBook, position: Int): Int {
        return layoutStyle
    }

    override fun convert(
        holder: ItemViewHolder,
        binding: ViewBinding,
        item: SearchBook,
        payloads: MutableList<Any>
    ) {
        when (binding) {
            is ItemSearchBinding -> {
                if (payloads.isEmpty()) {
                    bindList(binding, item)
                } else {
                    for (i in payloads.indices) {
                        val bundle = payloads[i] as Bundle
                        bindListChange(binding, item, bundle)
                    }
                }
            }
            is ItemExploreBookGridBinding -> {
                bindGrid(binding, item)
            }
        }

    }

    private fun bindList(binding: ItemSearchBinding, item: SearchBook) {
        binding.run {
            tvName.text = item.name
            tvAuthor.text = context.getString(R.string.author_show, item.author)
            ivInBookshelf.isVisible = callBack.isInBookshelf(item)
            if (item.latestChapterTitle.isNullOrEmpty()) {
                tvLasted.gone()
            } else {
                tvLasted.text = context.getString(R.string.lasted_show, item.latestChapterTitle)
                tvLasted.visible()
            }
            tvIntroduce.text = item.trimIntro(context)
            val kinds = item.getKindList()
            if (kinds.isEmpty()) {
                llKind.gone()
            } else {
                llKind.visible()
                llKind.setLabels(kinds)
            }
            ivCover.load(
                item,
                AppConfig.loadCoverOnlyWifi
            )
        }
    }

    private fun bindGrid(binding: ItemExploreBookGridBinding, item: SearchBook) {
        binding.run {
            val isCompact = layoutStyle == 2
            tvName.text = item.name
            tvAuthor.text = context.getString(R.string.author_show, item.author)
            if (item.latestChapterTitle.isNullOrEmpty()) {
                tvLasted.gone()
            } else {
                tvLasted.text = context.getString(R.string.lasted_show, item.latestChapterTitle)
                tvLasted.visible()
            }
            val kinds = item.getKindList()
            if (isCompact || kinds.isEmpty()) {
                llKind.gone()
            } else {
                llKind.visible()
                llKind.textSize = 11f
                llKind.setLabels(kinds.take(3))
            }
            if (isCompact) {
                tvIntroduce.gone()
            } else {
                tvIntroduce.text = item.trimIntro(context)
                tvIntroduce.visible()
            }
            ivInBookshelf.isVisible = callBack.isInBookshelf(item)
            ivCover.load(
                item,
                AppConfig.loadCoverOnlyWifi
            )
        }
    }

    private fun bindListChange(binding: ItemSearchBinding, item: SearchBook, bundle: Bundle) {
        binding.run {
            bundle.keySet().forEach {
                when (it) {
                    "isInBookshelf" -> ivInBookshelf.isVisible =
                        callBack.isInBookshelf(item)
                }
            }
        }
    }

    override fun registerListener(holder: ItemViewHolder, binding: ViewBinding) {
        holder.itemView.setOnClickListener {
            getItem(holder.bindingAdapterPosition - getHeaderCount())?.let {
                callBack.showBookInfo(it)
            }
        }
    }

    interface CallBack {
        /**
         * 是否已经加入书架
         */
        fun isInBookshelf(book: SearchBook): Boolean

        fun showBookInfo(book: SearchBook)
    }
}
