package io.legado.app.ui.main.bookshelf.style1.books

import android.content.Context
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import androidx.core.os.bundleOf
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import androidx.viewbinding.ViewBinding
import io.legado.app.R
import io.legado.app.base.adapter.DiffRecyclerAdapter
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.data.entities.Book
import io.legado.app.ui.main.bookshelf.BookCollectionShelfItem

abstract class BaseBooksAdapter<VB : ViewBinding>(context: Context) :
    DiffRecyclerAdapter<Any, VB>(context) {

    protected companion object {
        const val VIEW_TYPE_BOOK = 0
        const val VIEW_TYPE_COLLECTION = 1
        const val PAYLOAD_SELECTION = "selection"
    }

    override val keepScrollPosition = true

    override val diffItemCallback: DiffUtil.ItemCallback<Any> =
        object : DiffUtil.ItemCallback<Any>() {

            override fun areItemsTheSame(oldItem: Any, newItem: Any): Boolean {
                return when {
                    oldItem is Book && newItem is Book -> {
                        oldItem.shelfKey == newItem.shelfKey
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
                        val oldBook = oldItem
                        val newBook = newItem
                        when {
                            oldBook.durChapterTime != newBook.durChapterTime -> false
                            oldBook.name != newBook.name -> false
                            oldBook.author != newBook.author -> false
                            oldBook.durChapterTitle != newBook.durChapterTitle -> false
                            oldBook.latestChapterTitle != newBook.latestChapterTitle -> false
                            oldBook.lastCheckCount != newBook.lastCheckCount -> false
                            oldBook.type != newBook.type -> false
                            oldBook.getDisplayCover() != newBook.getDisplayCover() -> false
                            oldBook.getUnreadChapterNum() != newBook.getUnreadChapterNum() -> false
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
                val oldBook = oldItem
                val newBook = newItem
                val bundle = bundleOf()
                if (oldBook.name != newBook.name) {
                    bundle.putString("name", newBook.name)
                }
                if (oldBook.author != newBook.author) {
                    bundle.putString("author", newBook.author)
                }
                if (oldBook.durChapterTitle != newBook.durChapterTitle) {
                    bundle.putString("dur", newBook.durChapterTitle)
                }
                if (oldBook.latestChapterTitle != newBook.latestChapterTitle) {
                    bundle.putString("last", newBook.latestChapterTitle)
                }
                if (oldBook.getDisplayCover() != newBook.getDisplayCover()) {
                    bundle.putString("cover", newBook.getDisplayCover())
                }
                if (oldBook.lastCheckCount != newBook.lastCheckCount
                    || oldBook.durChapterTime != newBook.durChapterTime
                    || oldBook.getUnreadChapterNum() != newBook.getUnreadChapterNum()
                    || oldBook.lastCheckCount != newBook.lastCheckCount
                ) {
                    bundle.putBoolean("refresh", true)
                }
                if (oldBook.latestChapterTime != newBook.latestChapterTime) {
                    bundle.putBoolean("lastUpdateTime", true)
                }
                if (oldBook.type != newBook.type) {
                    bundle.putBoolean("type", true)
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

    protected fun View.bindShelfTouch(
        itemProvider: () -> Any?,
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
            val item = itemProvider() ?: return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    longPressed = false
                    dragging = false
                    longPressRunnable = Runnable {
                        val pressedItem = itemProvider() ?: return@Runnable
                        longPressed = true
                        view.clearPressedState()
                        view.parent?.requestDisallowInterceptTouchEvent(true)
                        when (pressedItem) {
                            is Book -> callBack.onBookLongPressed(pressedItem, view)
                            is BookCollectionShelfItem -> callBack.onCollectionLongPressed(
                                pressedItem,
                                view
                            )
                        }
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
                            when (item) {
                                is Book -> callBack.onBookTouchedForDrag(
                                    item,
                                    view,
                                    event.rawX,
                                    event.rawY
                                )

                                is BookCollectionShelfItem -> callBack.onCollectionTouchedForDrag(
                                    item,
                                    view,
                                    event.rawX,
                                    event.rawY
                                )
                            }
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
                    view.clearPressedState()
                    when {
                        dragging -> {
                            when (item) {
                                is Book -> callBack.onBookDragEnd(item, event.rawX, event.rawY)
                                is BookCollectionShelfItem -> callBack.onCollectionDragEnd(
                                    item,
                                    event.rawX,
                                    event.rawY
                                )
                            }
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
                    view.clearPressedState()
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

    private fun View.clearPressedState() {
        isPressed = false
        jumpDrawablesToCurrentState()
        if (this is ViewGroup) {
            for (index in 0 until childCount) {
                getChildAt(index).clearPressedState()
            }
        }
    }

    fun notification(bookUrl: String) {
        getItems().forEachIndexed { i, it ->
            if (it is Book && it.bookUrl == bookUrl) {
                notifyItemChanged(i, bundleOf(Pair("refresh", null), Pair("lastUpdateTime", null)))
            }
        }
    }

    fun upLastUpdateTime() {
        notifyItemRangeChanged(0, itemCount, bundleOf(Pair("lastUpdateTime", null)))
    }

    fun notifySelectionChanged() {
        if (itemCount > 0) {
            notifyItemRangeChanged(0, itemCount, bundleOf(Pair(PAYLOAD_SELECTION, null)))
        }
    }

    fun renderVisibleSelectionMarks(recyclerView: RecyclerView, callBack: CallBack) {
        for (index in 0 until recyclerView.childCount) {
            val child = recyclerView.getChildAt(index)
            val position = recyclerView.getChildAdapterPosition(child)
            if (position == RecyclerView.NO_POSITION) continue
            val item = getItem(position) ?: continue
            val outer = child.findViewById<View>(R.id.selection_outer) ?: continue
            val dot = child.findViewById<View>(R.id.selection_dot) ?: continue
            renderSelectionMark(outer, dot, item, callBack)
        }
    }

    protected fun isSelectionPayload(payloads: MutableList<Any>): Boolean {
        return payloads.isNotEmpty() && payloads.all {
            it is Bundle && it.containsKey(PAYLOAD_SELECTION)
        }
    }

    protected fun renderSelectionMark(outer: View, dot: View, item: Any, callBack: CallBack) {
        val selectionMode = callBack.isInSelectionMode()
        outer.visibility = if (selectionMode) View.VISIBLE else View.GONE
        dot.visibility = if (selectionMode && callBack.isSelected(item)) View.VISIBLE else View.GONE
    }

    interface CallBack {
        fun open(book: Book)
        fun openCollection(collection: BookCollectionShelfItem)
        fun openBookInfo(book: Book)
        fun onBookLongPressed(book: Book, view: android.view.View)
        fun onBookLongPressFinished()
        fun onBookTouchedForDrag(book: Book, view: android.view.View, rawX: Float, rawY: Float)
        fun onBookDragMove(rawX: Float, rawY: Float)
        fun onBookDragEnd(book: Book, rawX: Float, rawY: Float)
        fun onBookDragCancel()
        fun onBookClickInSelection(book: Book)
        fun onCollectionLongPressed(
            collection: BookCollectionShelfItem,
            view: android.view.View
        )
        fun onCollectionTouchedForDrag(
            collection: BookCollectionShelfItem,
            view: android.view.View,
            rawX: Float,
            rawY: Float
        )
        fun onCollectionDragEnd(collection: BookCollectionShelfItem, rawX: Float, rawY: Float)
        fun onCollectionClickInSelection(collection: BookCollectionShelfItem)
        fun isInSelectionMode(): Boolean
        fun isSelected(item: Any): Boolean
        fun isUpdate(bookUrl: String): Boolean
    }
}
