package io.legado.app.ui.main.bookshelf.style1.books

import android.annotation.SuppressLint
import android.graphics.Rect
import android.os.Bundle
import android.view.View
import android.view.ViewConfiguration
import android.widget.CheckBox
import android.widget.LinearLayout
import androidx.core.view.isGone
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.RecyclerView.Adapter.StateRestorationPolicy
import io.legado.app.R
import io.legado.app.base.BaseFragment
import io.legado.app.constant.AppLog
import io.legado.app.constant.BookType
import io.legado.app.constant.EventBus
import io.legado.app.data.AppDatabase
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookGroup
import io.legado.app.databinding.FragmentBooksBinding
import io.legado.app.help.book.isLocal
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.LocalConfig
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.primaryColor
import io.legado.app.ui.book.info.BookInfoActivity
import io.legado.app.ui.main.MainActivity
import io.legado.app.ui.main.MainViewModel
import io.legado.app.ui.main.bookshelf.BookCollectionActivity
import io.legado.app.ui.main.bookshelf.BookCollectionSelectDialog
import io.legado.app.ui.main.bookshelf.BookCollectionShelfItem
import io.legado.app.utils.cnCompare
import io.legado.app.utils.applyMainBottomBarPadding
import io.legado.app.utils.dpToPx
import io.legado.app.utils.flowWithLifecycleAndDatabaseChangeFirst
import io.legado.app.utils.gone
import io.legado.app.utils.observeEvent
import io.legado.app.utils.setEdgeEffectColor
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.startActivity
import io.legado.app.utils.startActivityForBook
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import io.legado.app.model.SourceCallBack
import io.legado.app.model.localBook.LocalBook
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 书架界面
 */
class BooksFragment() : BaseFragment(R.layout.fragment_books),
    BaseBooksAdapter.CallBack {

    constructor(
        position: Int,
        group: BookGroup,
        secondaryGroupId: Long,
        bookSort: Int,
        enableRefresh: Boolean,
        onlyUpdateRead: Boolean
    ) : this() {
        val bundle = Bundle()
        bundle.putInt("position", position)
        bundle.putLong("groupId", group.groupId)
        bundle.putLong("secondaryGroupId", secondaryGroupId)
        bundle.putInt("bookSort", bookSort)
        bundle.putBoolean("enableRefresh", enableRefresh)
        bundle.putBoolean("onlyUpdateRead", onlyUpdateRead)
        arguments = bundle
    }

    private val binding by viewBinding(FragmentBooksBinding::bind)
    private val activityViewModel by activityViewModels<MainViewModel>()
    private val bookshelfLayout by lazy { AppConfig.bookshelfLayout }
    private val booksAdapter: BaseBooksAdapter<*> by lazy {
        when (bookshelfLayout) {
            0 -> {
                BooksAdapterList(requireContext(), this, this, viewLifecycleOwner.lifecycle)
            }
            1 -> {
                BooksAdapterList2(requireContext(), this, this, viewLifecycleOwner.lifecycle)
            }
            else -> {
                BooksAdapterGrid(requireContext(), this)
            }
        }
    }
    private var booksFlowJob: Job? = null
    var position = 0
        private set
    var groupId = -1L
        private set
    var secondaryGroupId = BookGroup.IdAll
        private set
    var bookSort = 0
        private set
    private var upLastUpdateTimeJob: Job? = null
    private var enableRefresh = true
    private var onlyUpdateRead = false
    private var secondaryGroupFilterId = BookGroup.IdAll
    private val bookshelfMargin by lazy { AppConfig.bookshelfMargin }
    private var itemCount = 0
    private var totalRows = 0
    private val selectedBooks = linkedMapOf<String, Book>()
    private var draggingBookView: View? = null
    private var draggingBooks: List<Book> = emptyList()
    private var draggingStartRawX = 0f
    private var draggingStartRawY = 0f
    private var draggingOriginalTranslationX = 0f
    private var draggingOriginalTranslationY = 0f
    private var draggingOriginalElevation = 0f

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        arguments?.let {
            position = it.getInt("position", 0)
            groupId = it.getLong("groupId", -1)
            secondaryGroupId = it.getLong("secondaryGroupId", BookGroup.IdAll)
            bookSort = it.getInt("bookSort", 0)
            enableRefresh = it.getBoolean("enableRefresh", true)
            onlyUpdateRead = it.getBoolean("onlyUpdateRead", false)
            secondaryGroupFilterId = secondaryGroupId
            binding.refreshLayout.isEnabled = enableRefresh
        }
        initRecyclerView()
        initBookActionBar()
        upRecyclerData()
    }

    private fun initRecyclerView() {
        binding.rvBookshelf.setEdgeEffectColor(primaryColor)
        binding.root.clipChildren = false
        binding.refreshLayout.clipChildren = false
        binding.rvBookshelf.clipChildren = false
        binding.rvBookshelf.clipToPadding = false
        binding.rvBookshelf.applyMainBottomBarPadding(
            usePaddingForRecyclerView = true
        )
        upFastScrollerBar()
        binding.refreshLayout.setColorSchemeColors(accentColor)
        binding.refreshLayout.setProgressViewOffset(true, (-28).dpToPx(), 56.dpToPx())
        binding.refreshLayout.setOnRefreshListener {
            binding.refreshLayout.isRefreshing = false
            activityViewModel.upToc(getBooks(), onlyUpdateRead)
        }
        if (bookshelfLayout >= 2) {
            binding.rvBookshelf.layoutManager = GridLayoutManager(context, bookshelfLayout)
            binding.rvBookshelf.setRecycledViewPool(activityViewModel.booksGridRecycledViewPool)
        } else {
            binding.rvBookshelf.layoutManager = LinearLayoutManager(context)
            binding.rvBookshelf.setRecycledViewPool(activityViewModel.booksListRecycledViewPool)
        }
        booksAdapter.stateRestorationPolicy = StateRestorationPolicy.PREVENT_WHEN_EMPTY
        binding.rvBookshelf.adapter = booksAdapter
        /**
         * 应该是当初没有使用override val keepScrollPosition = true 加的代码
         * 最近阅读插入顶部时会造成滚动
         * 但是采用keepScrollPosition = true复原滚动后,代码就多余了
         * 采用下面代码反而会向上多滚动一个行
         * 再加上2025/12/19代码,因为下面的代码会出现很奇怪的自动滚动到顶部现象,没理出原因,注释掉下面代码
         * **/
//        booksAdapter.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
//            override fun onItemRangeInserted(positionStart: Int, itemCount: Int) {
//                val layoutManager = binding.rvBookshelf.layoutManager
//                if (positionStart == 0 && itemCount == 1 && layoutManager is LinearLayoutManager) {
//                    val scrollTo = layoutManager.findFirstVisibleItemPosition() - itemCount
//                    binding.rvBookshelf.scrollToPosition(max(0, scrollTo))
//                }
//            }
//
//            override fun onItemRangeMoved(fromPosition: Int, toPosition: Int, itemCount: Int) {
//                val layoutManager = binding.rvBookshelf.layoutManager
//                if (toPosition == 0 && itemCount == 1 && layoutManager is LinearLayoutManager) {
//                    val scrollTo = layoutManager.findFirstVisibleItemPosition() - itemCount
//                    binding.rvBookshelf.scrollToPosition(max(0, scrollTo))
//                }
//            }
//        })
        binding.rvBookshelf.addItemDecoration( object : RecyclerView.ItemDecoration() {
            override fun getItemOffsets(
                outRect: Rect,
                view: View,
                parent: RecyclerView,
                state: RecyclerView.State
            ) {
                val position = parent.getChildAdapterPosition(view)
                if (bookshelfLayout >= 2) {
                    val spanCount = bookshelfLayout
                    val rowIndex = position / spanCount
                    when (rowIndex) {
                        0 -> { //第一行加额外上边距
                            outRect.set(bookshelfMargin, bookshelfMargin + 24, bookshelfMargin, bookshelfMargin)
                        }
                        totalRows - 1 -> { //最后一行加额外下边距
                            outRect.set(bookshelfMargin, bookshelfMargin, bookshelfMargin, bookshelfMargin)
                        }
                        else -> {
                            outRect.set(bookshelfMargin, bookshelfMargin, bookshelfMargin, bookshelfMargin)
                        }
                    }
                } else {
                    when (position) {
                        0 -> {
                            outRect.set(0, bookshelfMargin + 24, 0, bookshelfMargin)
                        }
                        itemCount - 1 -> {
                            outRect.set(0, bookshelfMargin, 0, bookshelfMargin)
                        }
                        else -> {
                            outRect.set(0, bookshelfMargin, 0, bookshelfMargin)
                        }
                    }
                }
            }
        })
        startLastUpdateTimeJob()
    }

    private fun upFastScrollerBar() {
        val showBookshelfFastScroller = AppConfig.showBookshelfFastScroller
        binding.rvBookshelf.setFastScrollEnabled(showBookshelfFastScroller)
        if (showBookshelfFastScroller) {
            binding.rvBookshelf.scrollBarSize = 0
        } else {
            binding.rvBookshelf.scrollBarSize =
                ViewConfiguration.get(requireContext()).scaledScrollBarSize
        }
    }

    private fun initBookActionBar() = binding.run {
        actionBookInfo.setOnClickListener {
            val selected = selectedBookList()
            if (selected.size != 1) {
                toastOnUi(R.string.book_info_single_only)
                return@setOnClickListener
            }
            openBookInfo(selected.first())
            clearSelection()
        }
        actionAddCollection.setOnClickListener {
            val urls = selectedBookList().map { it.bookUrl }
            if (urls.isEmpty()) return@setOnClickListener
            showDialogFragment(BookCollectionSelectDialog(ArrayList(urls)))
            clearSelection()
        }
        actionAddGroup.setOnClickListener {
            showAddToGroupDialog()
        }
        actionDeleteBook.setOnClickListener {
            alertDeleteSelectedBooks()
        }
        bookActionBar.gone()
    }

    private fun selectedBookList(): List<Book> {
        return selectedBooks.values.toList()
    }

    private fun clearSelection() {
        if (selectedBooks.isEmpty() && binding.bookActionBar.isGone) {
            setMainBottomBarHidden(false)
            return
        }
        selectedBooks.clear()
        binding.bookActionBar.gone()
        setMainBottomBarHidden(false)
        booksAdapter.notifyDataSetChanged()
    }

    private fun toggleSelection(book: Book) {
        if (selectedBooks.remove(book.bookUrl) == null) {
            selectedBooks[book.bookUrl] = book
        }
        updateSelectionBar()
    }

    private fun selectBook(book: Book, refreshItems: Boolean = true) {
        selectedBooks[book.bookUrl] = book
        updateSelectionBar(refreshItems)
    }

    private fun updateSelectionBar(refreshItems: Boolean = true) {
        val hasSelection = selectedBooks.isNotEmpty()
        binding.bookActionBar.isGone = !hasSelection
        if (hasSelection) {
            binding.bookActionBar.bringToFront()
        }
        setMainBottomBarHidden(hasSelection)
        if (refreshItems) {
            booksAdapter.notifyDataSetChanged()
        }
    }

    private fun setMainBottomBarHidden(hidden: Boolean) {
        (activity as? MainActivity)?.setBookshelfActionMode(hidden)
    }

    private fun showAddToGroupDialog() {
        val books = selectedBookList()
        if (books.isEmpty()) return
        viewLifecycleOwner.lifecycleScope.launch {
            val groups = withContext(Dispatchers.IO) {
                appDb.bookGroupDao.all
                    .filter { it.groupId > 0 }
                    .sortedWith(compareBy({ it.order }, { it.groupId }))
            }
            if (groups.isEmpty()) {
                toastOnUi(R.string.book_group_custom_empty)
                return@launch
            }
            alert(titleResource = R.string.add_to_group) {
                items(groups.map { it.groupName }) { dialog, index ->
                    dialog.dismiss()
                    addBooksToGroup(books, groups[index].groupId, clearAfter = true)
                }
            }
        }
    }

    private fun alertDeleteSelectedBooks() {
        val books = selectedBookList()
        if (books.isEmpty()) return
        alert(titleResource = R.string.draw, messageResource = R.string.sure_del) {
            var checkBox: CheckBox? = null
            if (books.any { it.isLocal }) {
                checkBox = CheckBox(requireContext()).apply {
                    setText(R.string.delete_book_file)
                    isChecked = LocalConfig.deleteBookOriginal
                }
                val view = LinearLayout(requireContext()).apply {
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
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
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

    private fun addBooksToGroup(books: List<Book>, groupId: Long, clearAfter: Boolean) {
        if (groupId <= 0) {
            toastOnUi(R.string.book_drop_system_group_invalid)
            return
        }
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val array = Array(books.size) { index ->
                val book = books[index]
                book.copy(group = book.group or groupId)
            }
            appDb.bookDao.update(*array)
            withContext(Dispatchers.Main) {
                toastOnUi(R.string.book_group_added)
                if (clearAfter) {
                    clearSelection()
                }
            }
        }
    }

    fun upBookSort(sort: Int) {
        binding.root.post {
            arguments?.putInt("bookSort", sort)
            bookSort = sort
            upRecyclerData()
        }
    }

    fun setEnableRefresh(enable: Boolean) {
        enableRefresh = enable
        binding.refreshLayout.isEnabled = enable
    }

    fun setOnlyUpdateRead(onlyRead: Boolean) {
        onlyUpdateRead = onlyRead
        arguments?.putBoolean("onlyUpdateRead", onlyRead)
    }

    fun setSecondaryGroupFilter(groupId: Long) {
        if (secondaryGroupFilterId == groupId) return
        secondaryGroupId = groupId
        arguments?.putLong("secondaryGroupId", groupId)
        secondaryGroupFilterId = groupId
        upRecyclerData()
    }

    /**
     * 更新书籍列表信息
     */
    private fun upRecyclerData() {
        booksFlowJob?.cancel()
        booksFlowJob = viewLifecycleOwner.lifecycleScope.launch {
            val userGroupIds = appDb.bookGroupDao.idsSum
            val booksFlow = appDb.bookDao.flowByGroup(groupId).map { list ->
                //排序
                when (bookSort) {
                    1 -> list.sortedByDescending { it.latestChapterTime }
                    2 -> list.sortedWith { o1, o2 ->
                        o1.name.cnCompare(o2.name)
                    }

                    3 -> list.sortedBy { it.order }

                    // 综合排序 issue #3192
                    4 -> list.sortedByDescending {
                        max(it.latestChapterTime, it.durChapterTime)
                    }
                    // 按作者排序
                    5 -> list.sortedWith { o1, o2 ->
                        o1.author.cnCompare(o2.author)
                    }

                    else -> list.sortedByDescending { it.durChapterTime }
                }
            }.map { list ->
                val filteredList = if (secondaryGroupFilterId == BookGroup.IdAll) {
                    list
                } else {
                    list.filter { it.isInSecondaryGroup(secondaryGroupFilterId, userGroupIds) }
                }
                list to filteredList
            }
            booksFlow.combine(appDb.bookCollectionDao.flowCollections()) { bookData, collections ->
                val (allBooks, filteredBooks) = bookData
                val visibleBookUrls = filteredBooks.mapTo(hashSetOf()) { it.bookUrl }
                val collectionItems = collections.mapNotNull { item ->
                    val visibleBooks = item.books.filter { it.bookUrl in visibleBookUrls }
                    if (visibleBooks.isEmpty()) {
                        null
                    } else {
                        BookCollectionShelfItem(item.collection, visibleBooks)
                    }
                }
                Triple(allBooks, filteredBooks, collectionItems + filteredBooks)
            }.flowWithLifecycleAndDatabaseChangeFirst(
                viewLifecycleOwner.lifecycle,
                Lifecycle.State.RESUMED,
                AppDatabase.BOOK_TABLE_NAME
            ).catch {
                AppLog.put("书架更新出错", it)
            }.conflate().flowOn(Dispatchers.Default).collect { (allBooks, list, items) ->
                (parentFragment as? io.legado.app.ui.main.bookshelf.style1.BookshelfFragment1)
                    ?.onBooksChanged(groupId, allBooks)
                itemCount = items.size
                val spanCount = bookshelfLayout
                if (spanCount >= 2) {
                    totalRows = if (itemCount % spanCount == 0) itemCount / spanCount else itemCount / spanCount + 1
                }
                binding.tvEmptyMsg.isGone = itemCount > 0
                binding.refreshLayout.isEnabled = enableRefresh && list.isNotEmpty()
                booksAdapter.setItems(items)
                delay(100)
            }
        }
    }

    private fun startLastUpdateTimeJob() {
        upLastUpdateTimeJob?.cancel()
        if (!AppConfig.showLastUpdateTime || bookshelfLayout >= 2) {
            return
        }
        upLastUpdateTimeJob = viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while (isActive) {
                    booksAdapter.upLastUpdateTime()
                    delay(30 * 1000)
                }
            }
        }
    }

    fun getBooks(): List<Book> {
        return booksAdapter.getItems().filterIsInstance<Book>()
    }

    fun gotoTop() {
        if (AppConfig.isEInkMode) {
            binding.rvBookshelf.scrollToPosition(0)
        } else {
            binding.rvBookshelf.smoothScrollToPosition(0)
        }
    }

    fun getBooksCount(): Int {
        return booksAdapter.itemCount
    }

    fun exitSelectionIfNeeded(): Boolean {
        if (selectedBooks.isEmpty() && binding.bookActionBar.isGone) {
            return false
        }
        resetDraggingView()
        clearSelection()
        return true
    }

    override fun onDestroyView() {
        setMainBottomBarHidden(false)
        super.onDestroyView()
        /**
         * 将 RecyclerView 中的视图全部回收到 RecycledViewPool 中
         */
        binding.rvBookshelf.setItemViewCacheSize(0)
        binding.rvBookshelf.adapter = null
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
        selectBook(book)
    }

    override fun onBookLongPressFinished() {
        updateSelectionBar(refreshItems = true)
    }

    override fun onBookTouchedForDrag(book: Book, view: View, rawX: Float, rawY: Float) {
        draggingBooks = if (selectedBooks.containsKey(book.bookUrl)) {
            selectedBookList()
        } else {
            listOf(book)
        }
        draggingBookView = view
        draggingStartRawX = rawX
        draggingStartRawY = rawY
        draggingOriginalTranslationX = view.translationX
        draggingOriginalTranslationY = view.translationY
        draggingOriginalElevation = view.elevation
        binding.bookActionBar.gone()
        setMainBottomBarHidden(true)
        view.alpha = 0.45f
        view.elevation = 24.dpToPx().toFloat()
    }

    override fun onBookDragMove(rawX: Float, rawY: Float) {
        draggingBookView?.let {
            it.translationX = draggingOriginalTranslationX + rawX - draggingStartRawX
            it.translationY = draggingOriginalTranslationY + rawY - draggingStartRawY
        }
    }

    override fun onBookDragEnd(book: Book, rawX: Float, rawY: Float) {
        val books = draggingBooks.ifEmpty { listOf(book) }
        val collection = findCollectionAt(rawX, rawY)
        if (collection != null) {
            addBooksToCollection(books, collection.id)
            resetDraggingView()
            return
        }
        val targetGroupId = (parentFragment as? io.legado.app.ui.main.bookshelf.style1.BookshelfFragment1)
            ?.findSecondaryGroupIdAtRaw(rawX, rawY)
        when {
            targetGroupId == null -> Unit
            targetGroupId > 0 -> addBooksToGroup(books, targetGroupId, clearAfter = true)
            else -> toastOnUi(R.string.book_drop_system_group_invalid)
        }
        resetDraggingView()
        if (targetGroupId == null || targetGroupId <= 0) {
            clearSelection()
        }
    }

    override fun onBookDragCancel() {
        resetDraggingView()
        clearSelection()
    }

    override fun onBookClickInSelection(book: Book) {
        if (selectedBooks.isEmpty()) {
            open(book)
        } else {
            toggleSelection(book)
        }
    }

    override fun isInSelectionMode(): Boolean {
        return selectedBooks.isNotEmpty()
    }

    override fun isSelected(book: Book): Boolean {
        return selectedBooks.containsKey(book.bookUrl)
    }

    override fun isUpdate(bookUrl: String): Boolean {
        return activityViewModel.isUpdate(bookUrl)
    }

    private fun addBooksToCollection(books: List<Book>, collectionId: Long) {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            appDb.bookCollectionDao.addBookUrls(collectionId, books.map { it.bookUrl })
            withContext(Dispatchers.Main) {
                toastOnUi(R.string.book_collection_added)
                upRecyclerData()
                clearSelection()
            }
        }
    }

    private fun findCollectionAt(rawX: Float, rawY: Float): BookCollectionShelfItem? {
        val location = IntArray(2)
        binding.rvBookshelf.getLocationOnScreen(location)
        val x = rawX - location[0]
        val y = rawY - location[1]
        val hitRect = Rect()
        for (index in binding.rvBookshelf.childCount - 1 downTo 0) {
            val child = binding.rvBookshelf.getChildAt(index)
            if (child == draggingBookView) continue
            hitRect.set(
                (child.left + child.translationX).roundToInt(),
                (child.top + child.translationY).roundToInt(),
                (child.right + child.translationX).roundToInt(),
                (child.bottom + child.translationY).roundToInt()
            )
            if (!hitRect.contains(x.roundToInt(), y.roundToInt())) continue
            val position = binding.rvBookshelf.getChildAdapterPosition(child)
            if (position == RecyclerView.NO_POSITION) continue
            val item = booksAdapter.getItem(position)
            if (item is BookCollectionShelfItem) {
                return item
            }
        }
        return null
    }

    private fun resetDraggingView() {
        draggingBookView?.let {
            it.alpha = 1f
            it.translationX = draggingOriginalTranslationX
            it.translationY = draggingOriginalTranslationY
            it.elevation = draggingOriginalElevation
        }
        draggingBookView = null
        draggingBooks = emptyList()
    }

    private fun Book.isInSecondaryGroup(groupId: Long, userGroupIds: Long): Boolean {
        return when (groupId) {
            BookGroup.IdAll -> true
            BookGroup.IdLocal -> type and BookType.local > 0
            BookGroup.IdAudio -> type and BookType.audio > 0
            BookGroup.IdImage -> type and BookType.image > 0
            BookGroup.IdVideo -> type and BookType.video > 0
            BookGroup.IdError -> type and BookType.updateError > 0
            BookGroup.IdUngrouped -> userGroupIds and group == 0L && type and BookType.local == 0
            else -> groupId > 0 && group and groupId > 0
        }
    }

    @SuppressLint("NotifyDataSetChanged")
    override fun observeLiveBus() {
        super.observeLiveBus()
        observeEvent<String>(EventBus.UP_BOOKSHELF) {
            booksAdapter.notification(it)
        }
        observeEvent<String>(EventBus.BOOKSHELF_REFRESH) {
            upRecyclerData()
            startLastUpdateTimeJob()
            upFastScrollerBar()
        }
    }
}
