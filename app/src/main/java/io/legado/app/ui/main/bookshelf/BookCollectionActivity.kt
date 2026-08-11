package io.legado.app.ui.main.bookshelf

import android.os.Bundle
import android.view.View
import androidx.core.view.isGone
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import io.legado.app.R
import io.legado.app.base.BaseActivity
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookCollectionWithItems
import io.legado.app.databinding.ActivityBookCollectionBinding
import io.legado.app.help.book.isNotShelf
import io.legado.app.help.config.AppConfig
import io.legado.app.ui.book.info.BookInfoActivity
import io.legado.app.ui.main.bookshelf.style1.books.BaseBooksAdapter
import io.legado.app.ui.main.bookshelf.style1.books.BooksAdapterGrid
import io.legado.app.utils.applyMainBottomBarPadding
import io.legado.app.utils.cnCompare
import io.legado.app.utils.startActivity
import io.legado.app.utils.startActivityForBook
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max

class BookCollectionActivity : BaseActivity<ActivityBookCollectionBinding>(),
    BaseBooksAdapter.CallBack {

    override val binding by viewBinding(ActivityBookCollectionBinding::inflate)
    private val collectionId by lazy { intent.getLongExtra("collectionId", 0L) }
    private val adapter by lazy { BooksAdapterGrid(this, this) }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        val spanCount = AppConfig.bookshelfLayout.takeIf { it >= 2 } ?: 3
        binding.rvBooks.layoutManager = GridLayoutManager(this, spanCount)
        binding.rvBooks.clipToPadding = false
        binding.rvBooks.applyMainBottomBarPadding(usePaddingForRecyclerView = true)
        binding.rvBooks.adapter = adapter
        lifecycleScope.launch(Dispatchers.IO) {
            val collection = appDb.bookCollectionDao.getCollection(collectionId)
            withContext(Dispatchers.Main) {
                binding.titleBar.title = collection?.name ?: getString(R.string.book_collection)
            }
        }
        lifecycleScope.launch {
            combine(
                appDb.bookCollectionDao.flowBooks(collectionId),
                appDb.bookCollectionDao.flowChildCollections(collectionId)
            ) { list, childCollections ->
                val sortedBooks = sortBooks(list.filterNot { it.isNotShelf })
                val visibleBookUrls = sortedBooks.mapTo(hashSetOf()) { it.bookUrl }
                val childItems = buildCollectionShelfItems(childCollections, visibleBookUrls)
                childItems + sortedBooks
            }.catch {
                AppLog.put("合集详情更新出错", it)
            }.flowOn(Dispatchers.IO).conflate().collect {
                adapter.setItems(it)
                binding.tvEmptyMsg.isGone = it.isNotEmpty()
                val title = appDb.bookCollectionDao.getCollection(collectionId)?.name
                    ?: getString(R.string.book_collection)
                binding.titleBar.title = "$title (${it.size})"
            }
        }
    }

    private fun sortBooks(books: List<Book>): List<Book> {
        return when (AppConfig.bookshelfSort) {
            1 -> books.sortedByDescending { it.latestChapterTime }
            2 -> books.sortedWith { o1, o2 -> o1.name.cnCompare(o2.name) }
            3 -> books.sortedBy { it.order }
            4 -> books.sortedByDescending {
                max(it.latestChapterTime, it.durChapterTime)
            }

            5 -> books.sortedWith { o1, o2 -> o1.author.cnCompare(o2.author) }
            else -> books
        }
    }

    private fun buildCollectionShelfItems(
        collections: List<BookCollectionWithItems>,
        visibleBookUrls: Set<String>
    ): List<BookCollectionShelfItem> {
        return collections.mapNotNull { item ->
            val visibleBooks = item.books.filter {
                it.bookUrl in visibleBookUrls || !it.isNotShelf
            }
            if (visibleBooks.isEmpty() && item.childCollections.isEmpty()) {
                null
            } else {
                BookCollectionShelfItem(
                    collection = item.collection,
                    books = visibleBooks,
                    childCollections = item.childCollections,
                    previewBooks = visibleBooks.take(4)
                )
            }
        }
    }

    override fun open(book: Book) {
        startActivityForBook(book)
    }

    override fun openCollection(collection: BookCollectionShelfItem) {
        startActivity<BookCollectionActivity> {
            putExtra("collectionId", collection.id)
        }
    }

    override fun openBookInfo(book: Book) {
        startActivity<BookInfoActivity> {
            putExtra("name", book.name)
            putExtra("author", book.author)
        }
    }

    override fun onBookLongPressed(book: Book) {
        openBookInfo(book)
    }

    override fun onBookLongPressFinished() {
    }

    override fun onBookTouchedForDrag(book: Book, view: View, rawX: Float, rawY: Float) {
    }

    override fun onBookDragMove(rawX: Float, rawY: Float) {
    }

    override fun onBookDragEnd(book: Book, rawX: Float, rawY: Float) {
    }

    override fun onBookDragCancel() {
    }

    override fun onBookClickInSelection(book: Book) {
        open(book)
    }

    override fun onCollectionLongPressed(collection: BookCollectionShelfItem) {
        openCollection(collection)
    }

    override fun onCollectionTouchedForDrag(
        collection: BookCollectionShelfItem,
        view: View,
        rawX: Float,
        rawY: Float
    ) {
    }

    override fun onCollectionDragEnd(
        collection: BookCollectionShelfItem,
        rawX: Float,
        rawY: Float
    ) {
    }

    override fun onCollectionClickInSelection(collection: BookCollectionShelfItem) {
        openCollection(collection)
    }

    override fun isInSelectionMode(): Boolean = false

    override fun isSelected(item: Any): Boolean = false

    override fun isUpdate(bookUrl: String): Boolean = false
}
