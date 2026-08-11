package io.legado.app.ui.main.bookshelf.style1.books

import android.content.Context
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import androidx.core.os.bundleOf
import androidx.recyclerview.widget.DiffUtil
import androidx.viewbinding.ViewBinding
import io.legado.app.base.adapter.DiffRecyclerAdapter
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.data.entities.Book
import io.legado.app.ui.main.bookshelf.BookCollectionShelfItem

abstract class BaseBooksAdapter<VB : ViewBinding>(context: Context) :
    DiffRecyclerAdapter<Any, VB>(context) {

    protected companion object {
        const val VIEW_TYPE_BOOK = 0
        const val VIEW_TYPE_COLLECTION = 1
    }

    override val keepScrollPosition = true

    override val diffItemCallback: DiffUtil.ItemCallback<Any> =
        object : DiffUtil.ItemCallback<Any>() {

            override fun areItemsTheSame(oldItem: Any, newItem: Any): Boolean {
                return when {
                    oldItem is Book && newItem is Book -> {
                        oldItem.name == newItem.name && oldItem.author == newItem.author
                    }

                    oldItem is BookCollectionShelfItem && newItem is BookCollectionShelfItem -> {
                        oldItem.id == newItem.id
                    }

                    else -> false
                }
            }

            override fun areContentsTheSame(oldItem: Any, newItem: Any): Boolean {
                return when {
                    oldItem is Book && newItem is Book -> {
                        when {
                            oldItem.durChapterTime != newItem.durChapterTime -> false
                            oldItem.name != newItem.name -> false
                            oldItem.author != newItem.author -> false
                            oldItem.durChapterTitle != newItem.durChapterTitle -> false
                            oldItem.latestChapterTitle != newItem.latestChapterTitle -> false
                            oldItem.lastCheckCount != newItem.lastCheckCount -> false
                            oldItem.type != newItem.type -> false
                            oldItem.getDisplayCover() != newItem.getDisplayCover() -> false
                            oldItem.getUnreadChapterNum() != newItem.getUnreadChapterNum() -> false
                            else -> true
                        }
                    }

                    oldItem is BookCollectionShelfItem && newItem is BookCollectionShelfItem -> {
                        oldItem.name == newItem.name &&
                                oldItem.count == newItem.count &&
                                oldItem.previewBooks.map { it.getDisplayCover() } ==
                                newItem.previewBooks.map { it.getDisplayCover() }
                    }

                    else -> false
                }
            }

            override fun getChangePayload(oldItem: Any, newItem: Any): Any? {
                if (oldItem !is Book || newItem !is Book) {
                    return null
                }
                val bundle = bundleOf()
                if (oldItem.name != newItem.name) {
                    bundle.putString("name", newItem.name)
                }
                if (oldItem.author != newItem.author) {
                    bundle.putString("author", newItem.author)
                }
                if (oldItem.durChapterTitle != newItem.durChapterTitle) {
                    bundle.putString("dur", newItem.durChapterTitle)
                }
                if (oldItem.latestChapterTitle != newItem.latestChapterTitle) {
                    bundle.putString("last", newItem.latestChapterTitle)
                }
                if (oldItem.getDisplayCover() != newItem.getDisplayCover()) {
                    bundle.putString("cover", newItem.getDisplayCover())
                }
                if (oldItem.lastCheckCount != newItem.lastCheckCount
                    || oldItem.durChapterTime != newItem.durChapterTime
                    || oldItem.getUnreadChapterNum() != newItem.getUnreadChapterNum()
                    || oldItem.lastCheckCount != newItem.lastCheckCount
                ) {
                    bundle.putBoolean("refresh", true)
                }
                if (oldItem.latestChapterTime != newItem.latestChapterTime) {
                    bundle.putBoolean("lastUpdateTime", true)
                }
                if (oldItem.type != newItem.type) {
                    bundle.putBoolean("local", true)
                }
                if (bundle.isEmpty) return null
                return bundle
            }

        }

    override fun getItemViewType(item: Any, position: Int): Int {
        return when (item) {
            is BookCollectionShelfItem -> VIEW_TYPE_COLLECTION
            else -> VIEW_TYPE_BOOK
        }
    }

    override fun onViewRecycled(holder: ItemViewHolder) {
        super.onViewRecycled(holder)
        holder.itemView.setOnClickListener(null)
        holder.itemView.setOnLongClickListener(null)
        holder.itemView.setOnTouchListener(null)
    }

    protected fun View.bindBookTouch(
        bookProvider: () -> Book?,
        callBack: CallBack
    ) {
        val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
        val longPressTimeout = ViewConfiguration.getLongPressTimeout().toLong()
        var downX = 0f
        var downY = 0f
        var longPressed = false
        var dragging = false
        var longPressRunnable: Runnable? = null
        setOnTouchListener { view, event ->
            val book = bookProvider() ?: return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    longPressed = false
                    dragging = false
                    longPressRunnable = Runnable {
                        val pressedBook = bookProvider() ?: return@Runnable
                        longPressed = true
                        view.parent?.requestDisallowInterceptTouchEvent(true)
                        callBack.onBookLongPressed(pressedBook)
                    }.also {
                        view.postDelayed(it, longPressTimeout)
                    }
                    false
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    val movedEnough = dx * dx + dy * dy > touchSlop * touchSlop
                    if (!longPressed && movedEnough) {
                        longPressRunnable?.let(view::removeCallbacks)
                        longPressRunnable = null
                    }
                    if (longPressed && movedEnough) {
                        if (!dragging) {
                            dragging = true
                            callBack.onBookTouchedForDrag(book, view, event.rawX, event.rawY)
                        }
                        callBack.onBookDragMove(event.rawX, event.rawY)
                        true
                    } else {
                        false
                    }
                }

                MotionEvent.ACTION_UP -> {
                    longPressRunnable?.let(view::removeCallbacks)
                    longPressRunnable = null
                    when {
                        dragging -> {
                            callBack.onBookDragEnd(book, event.rawX, event.rawY)
                            longPressed = false
                            dragging = false
                            true
                        }

                        else -> {
                            val handled = longPressed
                            if (longPressed) {
                                callBack.onBookLongPressFinished()
                            }
                            longPressed = false
                            handled
                        }
                    }
                }

                MotionEvent.ACTION_CANCEL -> {
                    longPressRunnable?.let(view::removeCallbacks)
                    longPressRunnable = null
                    if (dragging) {
                        callBack.onBookDragCancel()
                    } else if (longPressed) {
                        callBack.onBookLongPressFinished()
                    }
                    longPressed = false
                    dragging = false
                    false
                }

                else -> false
            }
        }
    }

    fun notification(bookUrl: String) {
        getItems().forEachIndexed { i, it ->
            if (it is Book && it.bookUrl == bookUrl) {
                notifyItemChanged(i, bundleOf(Pair("refresh", null), Pair("lastUpdateTime", null)))
                return
            }
        }
    }

    fun upLastUpdateTime() {
        notifyItemRangeChanged(0, itemCount, bundleOf(Pair("lastUpdateTime", null)))
    }

    interface CallBack {
        fun open(book: Book)
        fun openCollection(collection: BookCollectionShelfItem)
        fun openBookInfo(book: Book)
        fun onBookLongPressed(book: Book)
        fun onBookLongPressFinished()
        fun onBookTouchedForDrag(book: Book, view: android.view.View, rawX: Float, rawY: Float)
        fun onBookDragMove(rawX: Float, rawY: Float)
        fun onBookDragEnd(book: Book, rawX: Float, rawY: Float)
        fun onBookDragCancel()
        fun onBookClickInSelection(book: Book)
        fun isSelected(book: Book): Boolean
        fun isUpdate(bookUrl: String): Boolean
    }
}
