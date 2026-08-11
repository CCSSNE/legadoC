package io.legado.app.ui.main.bookshelf

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.LinearLayout
import androidx.activity.addCallback
import androidx.core.view.isGone
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.legado.app.R
import io.legado.app.base.BaseActivity
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookCollectionWithItems
import io.legado.app.databinding.ActivityBookCollectionBinding
import io.legado.app.help.book.isLocal
import io.legado.app.help.book.isNotShelf
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.LocalConfig
import io.legado.app.lib.dialogs.alert
import io.legado.app.model.SourceCallBack
import io.legado.app.model.localBook.LocalBook
import io.legado.app.ui.book.info.BookInfoActivity
import io.legado.app.ui.main.bookshelf.style1.books.BaseBooksAdapter
import io.legado.app.ui.main.bookshelf.style1.books.BooksAdapterGrid
import io.legado.app.utils.applyMainBottomBarPadding
import io.legado.app.utils.cnCompare
import io.legado.app.utils.dpToPx
import io.legado.app.utils.gone
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.startActivity
import io.legado.app.utils.startActivityForBook
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.roundToInt

class BookCollectionActivity : BaseActivity<ActivityBookCollectionBinding>(),
    BaseBooksAdapter.CallBack {

    override val binding by viewBinding(ActivityBookCollectionBinding::inflate)
    private val collectionId by lazy { intent.getLongExtra("collectionId", 0L) }
    private val adapter by lazy { BooksAdapterGrid(this, this) }
    private val selectedBooks = linkedMapOf<String, Book>()
    private val selectedCollections = linkedMapOf<Long, BookCollectionShelfItem>()
    private val draggingViewStates = mutableListOf<DraggingViewState>()
    private var draggingBooks: List<Book> = emptyList()
    private var draggingCollections: List<BookCollectionShelfItem> = emptyList()
    private var draggingStartRawX = 0f
    private var draggingStartRawY = 0f
    private var selectionRefreshPosted = false

    private data class DraggingViewState(
        val view: View,
        val translationX: Float,
        val translationY: Float,
        val elevation: Float
    )

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        val spanCount = AppConfig.bookshelfLayout.takeIf { it >= 2 } ?: 3
        binding.rvBooks.layoutManager = GridLayoutManager(this, spanCount)
        binding.rvBooks.clipToPadding = false
        binding.rvBooks.applyMainBottomBarPadding(usePaddingForRecyclerView = true)
        binding.rvBooks.adapter = adapter
        initBookActionBar()
        onBackPressedDispatcher.addCallback(this) {
            if (hasSelection()) {
                resetDraggingView()
                clearSelection()
            } else {
                finish()
            }
        }
        lifecycleScope.launch(Dispatchers.IO) {
            val collection = appDb.bookCollectionDao.getCollection(collectionId)
            withContext(Dispatchers.Main) {
                binding.titleBar.title = collection?.name ?: getString(R.string.book_collection)
            }
        }
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                appDb.bookCollectionDao.normalizeLocations()
            }
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

    private fun initBookActionBar() = binding.run {
        actionBookInfo.setOnClickListener {
            selectedBookList().singleOrNull()?.let {
                openBookInfo(it)
                clearSelection()
            }
        }
        actionAddCollection.setOnClickListener {
            val urls = selectedBookList().map { it.bookUrl }
            val collectionIds = selectedCollectionList().map { it.id }.toLongArray()
            if (urls.isEmpty() && collectionIds.isEmpty()) return@setOnClickListener
            showDialogFragment(
                BookCollectionSelectDialog(
                    ArrayList(urls),
                    collectionIds,
                    parentCollectionId = collectionId
                )
            )
            clearSelection()
        }
        actionAddGroup.setOnClickListener {
            val urls = selectedBookList().map { it.bookUrl }
            if (urls.isEmpty() || selectedCollections.isNotEmpty()) return@setOnClickListener
            showDialogFragment(BookGroupSelectDialog(ArrayList(urls)))
            clearSelection()
        }
        actionDeleteBook.setOnClickListener {
            when {
                selectedCollections.isNotEmpty() && selectedBooks.isEmpty() -> deleteSelectedCollections()
                selectedBooks.isNotEmpty() && selectedCollections.isEmpty() -> alertDeleteSelectedBooks()
            }
        }
        bookActionBar.gone()
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
        val visibleBooksByCollectionId = collections.associate { item ->
            item.collection.collectionId to item.books.filter {
                it.bookUrl in visibleBookUrls || !it.isNotShelf
            }
        }
        return collections.mapNotNull { item ->
            val visibleBooks = visibleBooksByCollectionId[item.collection.collectionId].orEmpty()
            val childPreviewBooks = item.childCollections.flatMap { childCollection ->
                visibleBooksByCollectionId[childCollection.collectionId].orEmpty()
            }
            val previewBooks = (visibleBooks + childPreviewBooks)
                .distinctBy { it.bookUrl }
                .take(4)
            if (visibleBooks.isEmpty() && item.childCollections.isEmpty()) {
                null
            } else {
                BookCollectionShelfItem(
                    collection = item.collection,
                    books = visibleBooks,
                    childCollections = item.childCollections,
                    previewBooks = previewBooks
                )
            }
        }
    }

    private fun selectedBookList(): List<Book> {
        return selectedBooks.values.toList()
    }

    private fun selectedCollectionList(): List<BookCollectionShelfItem> {
        return selectedCollections.values.toList()
    }

    private fun hasSelection(): Boolean {
        return selectedBooks.isNotEmpty() || selectedCollections.isNotEmpty()
    }

    private fun clearSelection() {
        selectedBooks.clear()
        selectedCollections.clear()
        binding.bookActionBar.gone()
        notifySelectionChanged()
    }

    private fun toggleSelection(book: Book) {
        if (selectedBooks.remove(book.bookUrl) == null) {
            selectedBooks[book.bookUrl] = book
        }
        updateSelectionBar()
    }

    private fun toggleSelection(collection: BookCollectionShelfItem) {
        if (selectedCollections.remove(collection.id) == null) {
            selectedCollections[collection.id] = collection
        }
        updateSelectionBar()
    }

    private fun selectBook(
        book: Book,
        showActionBar: Boolean = true,
        refreshItems: Boolean = true
    ) {
        selectedBooks[book.bookUrl] = book
        updateSelectionBar(showActionBar, refreshItems)
    }

    private fun selectCollection(
        collection: BookCollectionShelfItem,
        showActionBar: Boolean = true,
        refreshItems: Boolean = true
    ) {
        selectedCollections[collection.id] = collection
        updateSelectionBar(showActionBar, refreshItems)
    }

    private fun updateSelectionBar(
        showActionBar: Boolean = true,
        refreshItems: Boolean = true
    ) {
        val hasSelection = hasSelection()
        binding.bookActionBar.isGone = !hasSelection || !showActionBar
        if (hasSelection && showActionBar) {
            binding.bookActionBar.bringToFront()
        }
        setActionEnabled(
            binding.actionBookInfo,
            selectedBooks.size == 1 && selectedCollections.isEmpty()
        )
        setActionEnabled(binding.actionAddCollection, hasSelection)
        setActionEnabled(
            binding.actionAddGroup,
            selectedBooks.isNotEmpty() && selectedCollections.isEmpty()
        )
        val canDeleteBooks = selectedBooks.isNotEmpty() && selectedCollections.isEmpty()
        val canDeleteCollections = selectedCollections.isNotEmpty() && selectedBooks.isEmpty()
        setActionEnabled(binding.actionDeleteBook, canDeleteBooks || canDeleteCollections)
        val collectionOnly = selectedCollections.isNotEmpty() && selectedBooks.isEmpty()
        binding.actionBookInfo.isGone = collectionOnly
        binding.actionAddGroup.isGone = collectionOnly
        binding.tvDeleteAction.setText(
            if (canDeleteCollections) {
                R.string.delete_book_collection
            } else {
                R.string.remove_from_bookshelf
            }
        )
        if (refreshItems) {
            notifySelectionChanged()
        }
    }

    private fun notifySelectionChanged() {
        if (selectionRefreshPosted) return
        selectionRefreshPosted = true
        binding.rvBooks.post {
            selectionRefreshPosted = false
            adapter.notifySelectionChanged()
        }
    }

    private fun setActionEnabled(view: View, enabled: Boolean) {
        view.isEnabled = enabled
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                view.getChildAt(index).isEnabled = enabled
            }
        }
    }

    private fun alertDeleteSelectedBooks() {
        val books = selectedBookList()
        if (books.isEmpty()) return
        alert(titleResource = R.string.draw, messageResource = R.string.sure_del) {
            var checkBox: CheckBox? = null
            if (books.any { it.isLocal }) {
                checkBox = CheckBox(this@BookCollectionActivity).apply {
                    setText(R.string.delete_book_file)
                    isChecked = LocalConfig.deleteBookOriginal
                }
                val view = LinearLayout(this@BookCollectionActivity).apply {
                    setPadding(16.dpToPx(), 0, 16.dpToPx(), 0)
                    addView(checkBox)
                }
                customView { view }
            }
            okButton {
                checkBox?.let {
                    LocalConfig.deleteBookOriginal = it.isChecked
                }
                deleteBooks(books, LocalConfig.deleteBookOriginal)
                clearSelection()
            }
            noButton()
        }
    }

    private fun deleteBooks(books: List<Book>, deleteOriginal: Boolean) {
        lifecycleScope.launch(Dispatchers.IO) {
            books.forEach {
                if (it.isLocal) {
                    LocalBook.clearBookShelfCache(it)
                }
            }
            appDb.bookDao.delete(*books.toTypedArray())
            books.forEach {
                if (it.isLocal) {
                    LocalBook.deleteBook(it, deleteOriginal)
                } else {
                    val source = appDb.bookSourceDao.getBookSource(it.origin)
                    SourceCallBack.callBackBook(SourceCallBack.DEL_BOOK_SHELF, source, it)
                }
            }
        }
    }

    private fun deleteSelectedCollections() {
        val collectionIds = selectedCollectionList().map { it.id }
        if (collectionIds.isEmpty()) return
        lifecycleScope.launch(Dispatchers.IO) {
            appDb.bookCollectionDao.deleteCollectionsAndRelease(collectionIds)
            withContext(Dispatchers.Main) {
                clearSelection()
            }
        }
    }

    override fun open(book: Book) {
        startActivityForBook(book)
    }

    override fun openCollection(collection: BookCollectionShelfItem) {
        startActivity(
            Intent(this, BookCollectionActivity::class.java).putExtra(
                "collectionId",
                collection.id
            )
        )
    }

    override fun openBookInfo(book: Book) {
        startActivity<BookInfoActivity> {
            putExtra("name", book.name)
            putExtra("author", book.author)
        }
    }

    override fun onBookLongPressed(book: Book) {
        selectBook(book, showActionBar = true, refreshItems = false)
        adapter.renderVisibleSelectionMarks(binding.rvBooks, this)
    }

    override fun onBookLongPressFinished() {
        updateSelectionBar(showActionBar = true, refreshItems = true)
    }

    override fun onBookTouchedForDrag(book: Book, view: View, rawX: Float, rawY: Float) {
        draggingBooks = if (selectedBooks.containsKey(book.bookUrl)) {
            selectedBookList()
        } else {
            listOf(book)
        }
        draggingCollections = if (selectedBooks.containsKey(book.bookUrl)) {
            selectedCollectionList()
        } else {
            emptyList()
        }
        startDragging(view, rawX, rawY)
    }

    override fun onBookDragMove(rawX: Float, rawY: Float) {
        val dx = rawX - draggingStartRawX
        val dy = rawY - draggingStartRawY
        draggingViewStates.forEach {
            it.view.translationX = it.translationX + dx
            it.view.translationY = it.translationY + dy
        }
    }

    override fun onBookDragEnd(book: Book, rawX: Float, rawY: Float) {
        finishDragging(rawX, rawY)
    }

    override fun onBookDragCancel() {
        resetDraggingView()
        clearSelection()
    }

    override fun onBookClickInSelection(book: Book) {
        if (!hasSelection()) {
            open(book)
        } else {
            toggleSelection(book)
        }
    }

    override fun onCollectionLongPressed(collection: BookCollectionShelfItem) {
        selectCollection(collection, showActionBar = true, refreshItems = false)
        adapter.renderVisibleSelectionMarks(binding.rvBooks, this)
    }

    override fun onCollectionTouchedForDrag(
        collection: BookCollectionShelfItem,
        view: View,
        rawX: Float,
        rawY: Float
    ) {
        draggingBooks = if (selectedCollections.containsKey(collection.id)) {
            selectedBookList()
        } else {
            emptyList()
        }
        draggingCollections = if (selectedCollections.containsKey(collection.id)) {
            selectedCollectionList()
        } else {
            listOf(collection)
        }
        startDragging(view, rawX, rawY)
    }

    override fun onCollectionDragEnd(
        collection: BookCollectionShelfItem,
        rawX: Float,
        rawY: Float
    ) {
        finishDragging(rawX, rawY)
    }

    override fun onCollectionClickInSelection(collection: BookCollectionShelfItem) {
        if (!hasSelection()) {
            openCollection(collection)
        } else {
            toggleSelection(collection)
        }
    }

    override fun isInSelectionMode(): Boolean {
        return hasSelection()
    }

    override fun isSelected(item: Any): Boolean {
        return when (item) {
            is Book -> selectedBooks.containsKey(item.bookUrl)
            is BookCollectionShelfItem -> selectedCollections.containsKey(item.id)
            else -> false
        }
    }

    override fun isUpdate(bookUrl: String): Boolean = false

    private fun startDragging(view: View, rawX: Float, rawY: Float) {
        draggingStartRawX = rawX
        draggingStartRawY = rawY
        binding.bookActionBar.gone()
        showRootDropTarget()
        val draggingBookUrls = draggingBooks.mapTo(hashSetOf()) { it.bookUrl }
        val draggingCollectionIds = draggingCollections.mapTo(hashSetOf()) { it.id }
        draggingViewStates.clear()
        for (index in 0 until binding.rvBooks.childCount) {
            val child = binding.rvBooks.getChildAt(index)
            val position = binding.rvBooks.getChildAdapterPosition(child)
            if (position == RecyclerView.NO_POSITION) continue
            val item = adapter.getItem(position)
            val shouldDrag = when (item) {
                is Book -> item.bookUrl in draggingBookUrls
                is BookCollectionShelfItem -> item.id in draggingCollectionIds
                else -> false
            }
            if (shouldDrag) {
                draggingViewStates.add(child.toDraggingViewState())
            }
        }
        if (draggingViewStates.none { it.view == view }) {
            draggingViewStates.add(view.toDraggingViewState())
        }
        draggingViewStates.forEach {
            it.view.elevation = 24.dpToPx().toFloat()
        }
    }

    private fun View.toDraggingViewState(): DraggingViewState {
        return DraggingViewState(
            view = this,
            translationX = translationX,
            translationY = translationY,
            elevation = elevation
        )
    }

    private fun finishDragging(rawX: Float, rawY: Float) {
        val books = draggingBooks
        val collections = draggingCollections
        if (isRootDropTargetAt(rawX, rawY)) {
            moveItemsToRoot(books, collections)
            resetDraggingView()
            return
        }
        val collection = findCollectionAt(rawX, rawY)
        if (collection != null) {
            addItemsToCollection(books, collections, collection.id)
            resetDraggingView()
            return
        }
        val targetBook = findBookAt(rawX, rawY, books.mapTo(hashSetOf()) { it.bookUrl })
        if (targetBook != null) {
            val urls = (books + targetBook).distinctBy { it.bookUrl }.map { it.bookUrl }
            val collectionIds = collections.map { it.id }.toLongArray()
            showDialogFragment(
                BookCollectionSelectDialog(
                    ArrayList(urls),
                    collectionIds,
                    openCreate = true,
                    parentCollectionId = collectionId
                )
            )
            resetDraggingView()
            clearSelection()
            return
        }
        resetDraggingView()
        clearSelection()
    }

    private fun addItemsToCollection(
        books: List<Book>,
        collections: List<BookCollectionShelfItem>,
        collectionId: Long
    ) {
        lifecycleScope.launch(Dispatchers.IO) {
            appDb.bookCollectionDao.addBookUrls(collectionId, books.map { it.bookUrl })
            appDb.bookCollectionDao.addChildCollectionIds(collectionId, collections.map { it.id })
            withContext(Dispatchers.Main) {
                toastOnUi(R.string.book_collection_added)
                clearSelection()
            }
        }
    }

    private fun moveItemsToRoot(
        books: List<Book>,
        collections: List<BookCollectionShelfItem>
    ) {
        if (books.isEmpty() && collections.isEmpty()) return
        lifecycleScope.launch(Dispatchers.IO) {
            val bookUrls = books.map { it.bookUrl }
            val collectionIds = collections.map { it.id }
            if (bookUrls.isNotEmpty()) {
                appDb.bookCollectionDao.deleteItemsByBookUrls(bookUrls)
            }
            if (collectionIds.isNotEmpty()) {
                appDb.bookCollectionDao.deleteParentsByChildCollectionIds(collectionIds)
            }
            withContext(Dispatchers.Main) {
                clearSelection()
            }
        }
    }

    private fun showRootDropTarget() {
        binding.rootDropTarget.isGone = false
        binding.rootDropTarget.bringToFront()
    }

    private fun isRootDropTargetAt(rawX: Float, rawY: Float): Boolean {
        val target = binding.rootDropTarget
        if (target.isGone || target.width <= 0 || target.height <= 0) return false
        val location = IntArray(2)
        target.getLocationOnScreen(location)
        return rawX >= location[0] &&
                rawX <= location[0] + target.width &&
                rawY >= location[1] &&
                rawY <= location[1] + target.height
    }

    private fun findCollectionAt(rawX: Float, rawY: Float): BookCollectionShelfItem? {
        val location = IntArray(2)
        binding.rvBooks.getLocationOnScreen(location)
        val x = rawX - location[0]
        val y = rawY - location[1]
        val hitRect = android.graphics.Rect()
        for (index in binding.rvBooks.childCount - 1 downTo 0) {
            val child = binding.rvBooks.getChildAt(index)
            if (draggingViewStates.any { it.view == child }) continue
            hitRect.set(
                (child.left + child.translationX).roundToInt(),
                (child.top + child.translationY).roundToInt(),
                (child.right + child.translationX).roundToInt(),
                (child.bottom + child.translationY).roundToInt()
            )
            if (!hitRect.contains(x.roundToInt(), y.roundToInt())) continue
            val position = binding.rvBooks.getChildAdapterPosition(child)
            if (position == RecyclerView.NO_POSITION) continue
            val item = adapter.getItem(position)
            if (item is BookCollectionShelfItem && draggingCollections.none { it.id == item.id }) {
                return item
            }
        }
        return null
    }

    private fun findBookAt(rawX: Float, rawY: Float, excludedBookUrls: Set<String>): Book? {
        val location = IntArray(2)
        binding.rvBooks.getLocationOnScreen(location)
        val x = rawX - location[0]
        val y = rawY - location[1]
        val hitRect = android.graphics.Rect()
        for (index in binding.rvBooks.childCount - 1 downTo 0) {
            val child = binding.rvBooks.getChildAt(index)
            if (draggingViewStates.any { it.view == child }) continue
            hitRect.set(
                (child.left + child.translationX).roundToInt(),
                (child.top + child.translationY).roundToInt(),
                (child.right + child.translationX).roundToInt(),
                (child.bottom + child.translationY).roundToInt()
            )
            if (!hitRect.contains(x.roundToInt(), y.roundToInt())) continue
            val position = binding.rvBooks.getChildAdapterPosition(child)
            if (position == RecyclerView.NO_POSITION) continue
            val item = adapter.getItem(position)
            if (item is Book && item.bookUrl !in excludedBookUrls) {
                return item
            }
        }
        return null
    }

    private fun resetDraggingView() {
        binding.rootDropTarget.gone()
        draggingViewStates.forEach {
            it.view.translationX = it.translationX
            it.view.translationY = it.translationY
            it.view.elevation = it.elevation
        }
        draggingViewStates.clear()
        draggingBooks = emptyList()
        draggingCollections = emptyList()
    }
}
