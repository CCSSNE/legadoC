package io.legado.app.ui.main.bookshelf.style2

import android.annotation.SuppressLint
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.LinearLayout
import androidx.activity.addCallback
import androidx.appcompat.widget.SearchView
import androidx.core.content.ContextCompat
import androidx.core.view.isGone
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.constant.EventBus
import io.legado.app.data.AppDatabase
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookGroup
import io.legado.app.databinding.FragmentBookshelf2Binding
import io.legado.app.help.book.isLocal
import io.legado.app.help.book.isShortcut
import io.legado.app.help.book.shelfKey
import io.legado.app.help.book.BookShortcutHelp
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.LocalConfig
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.primaryColor
import io.legado.app.lib.theme.UiCorner
import io.legado.app.ui.book.group.GroupEditDialog
import io.legado.app.ui.book.info.BookInfoActivity
import io.legado.app.ui.book.search.SearchActivity
import io.legado.app.ui.main.MainActivity
import io.legado.app.ui.main.bookshelf.BaseBookshelfFragment
import io.legado.app.ui.main.bookshelf.BookCollectionActivity
import io.legado.app.ui.main.bookshelf.BookCollectionShelfItem
import io.legado.app.utils.applyMainBottomBarPadding
import io.legado.app.utils.cnCompare
import io.legado.app.utils.dpToPx
import io.legado.app.utils.flowWithLifecycleAndDatabaseChangeFirst
import io.legado.app.utils.observeEvent
import io.legado.app.utils.postEvent
import io.legado.app.utils.setEdgeEffectColor
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.startActivity
import io.legado.app.utils.startActivityForBook
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max

/**
 * 书架界面
 */
class BookshelfFragment2() : BaseBookshelfFragment(R.layout.fragment_bookshelf2),
    SearchView.OnQueryTextListener,
    BaseBooksAdapter.CallBack {

    constructor(position: Int) : this() {
        val bundle = Bundle()
        bundle.putInt("position", position)
        arguments = bundle
    }

    private val binding by viewBinding(FragmentBookshelf2Binding::bind)
    private val bookshelfLayout by lazy { AppConfig.bookshelfLayout }
    private val booksAdapter: BaseBooksAdapter<*> by lazy {
        if (bookshelfLayout >= 2) {
            BooksAdapterGrid(requireContext(), this)
        } else {
            BooksAdapterList(requireContext(), this)
        }
    }
    private var bookGroups: List<BookGroup> = emptyList()
    private var booksFlowJob: Job? = null
    override var groupId = BookGroup.IdRoot
    override var books: List<Book> = emptyList()
    private var enableRefresh = true
    override var onlyUpdateRead = false
    private val bookshelfMargin by lazy { AppConfig.bookshelfMargin }
    private var itemCount = 0
    private var totalRows = 0
    private var collectionItems: List<BookCollectionShelfItem> = emptyList()
    private var actionItem: Any? = null

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        setSupportToolbar(binding.titleBar.toolbar)
        initBackHandler()
        initRecyclerView()
        initBookActionBar()
        initBookGroupData()
        initBooksData()
    }

    private fun initBackHandler() {
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner) {
            if (back()) {
                return@addCallback
            }
            isEnabled = false
            requireActivity().onBackPressedDispatcher.onBackPressed()
            isEnabled = true
        }
    }

    override fun onDestroyView() {
        setMainBottomBarHidden(false)
        super.onDestroyView()
    }

    private fun initBookActionBar() = binding.run {
        bookActionBar.background = ColorDrawable(
            UiCorner.surfaceColor(ContextCompat.getColor(requireContext(), R.color.background_card))
        )
        actionBookInfo.setOnClickListener {
            (actionItem as? Book)?.let {
                openBookInfo(it)
                clearBookActionBar()
            }
        }
        actionAddCollection.setOnClickListener {
        }
        actionAddGroup.setOnClickListener {
        }
        actionDeleteBook.setOnClickListener {
            when (val item = actionItem) {
                is Book -> alertDeleteBook(item)
                is BookCollectionShelfItem -> deleteCollection(item)
            }
        }
        bookActionBar.isGone = true
    }

    private fun initRecyclerView() {
        binding.rvBookshelf.setEdgeEffectColor(primaryColor)
        binding.rvBookshelf.clipToPadding = true
        binding.rvBookshelf.applyMainBottomBarPadding()
        binding.refreshLayout.setColorSchemeColors(accentColor)
        binding.refreshLayout.setProgressViewOffset(true, (-28).dpToPx(), 56.dpToPx())
        binding.refreshLayout.setOnRefreshListener {
            binding.refreshLayout.isRefreshing = false
            activityViewModel.upToc(books.distinctBy { it.bookUrl }, onlyUpdateRead)
        }
        if (bookshelfLayout >= 2) {
            binding.rvBookshelf.layoutManager = GridLayoutManager(context, bookshelfLayout)
        } else {
            binding.rvBookshelf.layoutManager = LinearLayoutManager(context)
        }
        binding.rvBookshelf.adapter = booksAdapter
        /**
         * 采用 layoutManager?.onRestoreInstanceState(layoutState)
         * 恢复滚动位置
         * **/
        binding.rvBookshelf.itemAnimator =  null
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
    }

    override fun upGroup(data: List<BookGroup>) {
        if (data != bookGroups) {
            bookGroups = data
            booksAdapter.updateItems(groupId)
            itemCount = getItemCount()
            val spanCount = bookshelfLayout
            if (spanCount >= 2) {
                totalRows = if (itemCount % spanCount == 0) itemCount / spanCount else itemCount / spanCount + 1
            }
            binding.tvEmptyMsg.isGone = itemCount > 0
            binding.refreshLayout.isEnabled = enableRefresh && itemCount > 0
        }
    }

    override fun upSort() {
        initBooksData()
    }

    private fun initBooksData() {
        if (groupId == BookGroup.IdRoot) {
            if (isAdded) {
                binding.titleBar.title = getString(R.string.bookshelf)
                binding.refreshLayout.isEnabled = true
                enableRefresh = true
            }
        } else {
            bookGroups.firstOrNull {
                groupId == it.groupId
            }?.let {
                binding.titleBar.title = "${getString(R.string.bookshelf)}(${it.groupName})"
                binding.refreshLayout.isEnabled = it.enableRefresh
                enableRefresh = it.enableRefresh
                onlyUpdateRead = it.onlyUpdateRead
            }
        }
        booksFlowJob?.cancel()
        booksFlowJob = viewLifecycleOwner.lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                appDb.bookCollectionDao.normalizeLocations()
            }
            val booksFlow = BookShortcutHelp.flowByGroup(groupId).map { list ->
                //排序
                when (AppConfig.getBookSortByGroupId(groupId)) {
                    1 -> list.sortedByDescending {
                        it.latestChapterTime
                    }

                    2 -> list.sortedWith { o1, o2 ->
                        o1.name.cnCompare(o2.name)
                    }

                    3 -> list.sortedBy {
                        it.order
                    }

                    4 -> list.sortedByDescending {
                        max(it.latestChapterTime, it.durChapterTime)
                    }

                    else -> list.sortedByDescending {
                        it.durChapterTime
                    }
                }
            }
            combine(
                booksFlow,
                appDb.bookCollectionDao.flowRootCollections(),
                appDb.bookCollectionDao.flowCollectedBookUrls(),
                BookShortcutHelp.flowCollectionBooks()
            ) { list, collections, collectedBookUrls, collectionShortcuts ->
                val shelfCollections = if (groupId == BookGroup.IdRoot) {
                    val visibleBookUrls = list
                        .filterNot { it.isShortcut }
                        .mapTo(hashSetOf()) { it.bookUrl }
                    collections.mapNotNull { item ->
                        val visibleBooks = item.books.filter { it.bookUrl in visibleBookUrls } +
                            collectionShortcuts[item.collection.collectionId].orEmpty()
                        if (visibleBooks.isEmpty() && item.childCollections.isEmpty()) {
                            null
                        } else {
                            BookCollectionShelfItem(
                                collection = item.collection,
                                books = visibleBooks,
                                childCollections = item.childCollections,
                                previewBooks = (
                                    visibleBooks + appDb.bookCollectionDao.previewBooksInCollection(
                                        item.collection.collectionId,
                                        4
                                    )
                                    ).distinctBy { it.shelfKey }.take(4)
                            )
                        }
                    }
                } else {
                    emptyList()
                }
                val visibleBooks = if (groupId == BookGroup.IdRoot) {
                    val collectedBookUrlSet = collectedBookUrls.toHashSet()
                    list.filter { it.isShortcut || it.bookUrl !in collectedBookUrlSet }
                } else {
                    list
                }
                visibleBooks to shelfCollections
            }.flowWithLifecycleAndDatabaseChangeFirst(
                viewLifecycleOwner.lifecycle,
                Lifecycle.State.RESUMED,
                AppDatabase.BOOK_TABLE_NAME,
                AppDatabase.BOOK_SHORTCUT_TABLE_NAME,
                AppDatabase.BOOK_COLLECTION_TABLE_NAME,
                AppDatabase.BOOK_COLLECTION_ITEM_TABLE_NAME,
                AppDatabase.BOOK_COLLECTION_CHILD_TABLE_NAME
            ).catch {
                AppLog.put("书架更新出错", it)
            }.conflate().flowOn(Dispatchers.Default).collect { (list, collections) ->
                books = list
                collectionItems = collections
                booksAdapter.updateItems(groupId)
                itemCount = getItemCount()
                val spanCount = bookshelfLayout
                if (spanCount >= 2) {
                    totalRows = if (itemCount % spanCount == 0) itemCount / spanCount else itemCount / spanCount + 1
                }
                binding.tvEmptyMsg.isGone = itemCount > 0
                binding.refreshLayout.isEnabled = enableRefresh && itemCount > 0
                delay(100)
            }
        }
    }

    override fun back(): Boolean {
        if (actionItem != null || !binding.bookActionBar.isGone) {
            clearBookActionBar()
            return true
        }
        if (groupId != BookGroup.IdRoot) {
            groupId = BookGroup.IdRoot
            initBooksData()
            return true
        }
        return false
    }

    fun switchToGroupId(targetGroupId: Long) {
        groupId = targetGroupId
        initBooksData()
    }

    override fun onQueryTextSubmit(query: String?): Boolean {
        SearchActivity.start(requireContext(), query)
        return false
    }

    override fun onQueryTextChange(newText: String?): Boolean {
        return false
    }

    override fun gotoTop() {
        if (AppConfig.isEInkMode) {
            binding.rvBookshelf.scrollToPosition(0)
        } else {
            binding.rvBookshelf.smoothScrollToPosition(0)
        }
    }

    override fun onItemClick(item: Any) {
        if (!binding.bookActionBar.isGone) {
            if (item is Book || item is BookCollectionShelfItem) {
                showBookActionBar(item)
            }
            return
        }
        when (item) {
            is Book -> startActivityForBook(item)

            is BookGroup -> {
                groupId = item.groupId
                initBooksData()
            }

            is BookCollectionShelfItem -> startActivity<BookCollectionActivity> {
                putExtra("collectionId", item.id)
            }
        }
    }

    override fun onItemLongClick(item: Any) {
        when (item) {
            is Book -> showBookActionBar(item)

            is BookGroup -> showDialogFragment(GroupEditDialog(item))

            is BookCollectionShelfItem -> showBookActionBar(item)
        }
    }

    private fun showBookActionBar(item: Any) {
        actionItem = item
        val isCollection = item is BookCollectionShelfItem
        setActionEnabled(binding.actionBookInfo, item is Book)
        setActionEnabled(binding.actionAddCollection, false)
        setActionEnabled(binding.actionAddGroup, false)
        setActionEnabled(binding.actionDeleteBook, item is Book || isCollection)
        binding.actionBookInfo.isGone = isCollection
        binding.tvDeleteAction.setText(
            if (isCollection) {
                R.string.delete_book_collection
            } else {
                R.string.remove_from_bookshelf
            }
        )
        binding.bookActionBar.isGone = false
        binding.bookActionBar.bringToFront()
        setMainBottomBarHidden(true)
    }

    private fun clearBookActionBar() {
        actionItem = null
        binding.bookActionBar.isGone = true
        binding.actionBookInfo.isGone = false
        setMainBottomBarHidden(false)
    }

    private fun setMainBottomBarHidden(hidden: Boolean) {
        (activity as? MainActivity)?.setBookshelfActionMode(hidden)
    }

    private fun setActionEnabled(view: View, enabled: Boolean) {
        view.isEnabled = enabled
        view.alpha = if (enabled) 1f else 0.38f
        setChildrenEnabled(view, enabled)
    }

    private fun setChildrenEnabled(view: View, enabled: Boolean) {
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                val child = view.getChildAt(index)
                child.isEnabled = enabled
                setChildrenEnabled(child, enabled)
            }
        }
    }

    private fun openBookInfo(book: Book) {
        startActivity<BookInfoActivity> {
            putExtra("name", book.name)
            putExtra("author", book.author)
            putExtra("bookUrl", book.bookUrl)
        }
    }

    private fun alertDeleteBook(book: Book) {
        alert(titleResource = R.string.draw, messageResource = R.string.sure_del) {
            var deleteBodyCheckBox: CheckBox? = null
            var deleteOriginalCheckBox: CheckBox? = null
            val checks = LinearLayout(requireContext()).apply {
                setPadding(16.dpToPx(), 0, 16.dpToPx(), 0)
            }
            if (book.isShortcut) {
                deleteBodyCheckBox = CheckBox(requireContext()).apply {
                    setText(R.string.delete_book_body)
                    setOnCheckedChangeListener { _, isChecked ->
                        if (!isChecked) deleteOriginalCheckBox?.isChecked = false
                    }
                }
                checks.addView(deleteBodyCheckBox)
            }
            if (book.isLocal) {
                deleteOriginalCheckBox = CheckBox(requireContext()).apply {
                    setText(R.string.delete_book_file)
                    setOnCheckedChangeListener { _, isChecked ->
                        if (isChecked) deleteBodyCheckBox?.isChecked = true
                    }
                    isChecked = !book.isShortcut && LocalConfig.deleteBookOriginal
                }
                checks.addView(deleteOriginalCheckBox)
            }
            if (checks.childCount > 0) {
                customView { checks }
            }
            okButton {
                deleteOriginalCheckBox?.let {
                    LocalConfig.deleteBookOriginal = it.isChecked
                }
                viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                    BookShortcutHelp.delete(
                        listOf(book),
                        deleteBody = deleteBodyCheckBox?.isChecked == true,
                        deleteOriginal = deleteOriginalCheckBox?.isChecked == true
                    )
                }
                clearBookActionBar()
            }
            noButton()
        }
    }

    private fun deleteCollection(collection: BookCollectionShelfItem) {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            appDb.bookCollectionDao.deleteCollectionsAndRelease(listOf(collection.id))
            withContext(Dispatchers.Main) {
                clearBookActionBar()
                postEvent(EventBus.BOOKSHELF_REFRESH, "")
            }
        }
    }

    override fun isUpdate(bookUrl: String): Boolean {
        return activityViewModel.isUpdate(bookUrl)
    }

    fun getItemCount(): Int {
        return if (groupId == BookGroup.IdRoot) {
            collectionItems.size + bookGroups.size + books.size
        } else {
            books.size
        }
    }

    override fun getItems(): List<Any> {
        if (groupId != BookGroup.IdRoot) {
            return books
        }
        return collectionItems + bookGroups + books
    }

    @SuppressLint("NotifyDataSetChanged")
    override fun observeLiveBus() {
        super.observeLiveBus()
        observeEvent<String>(EventBus.UP_BOOKSHELF) {
            booksAdapter.notification(it)
        }
        observeEvent<String>(EventBus.BOOKSHELF_REFRESH) {
            initBooksData()
        }
    }
}
