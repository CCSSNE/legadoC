package io.legado.app.ui.book.toc

import android.annotation.SuppressLint
import android.app.Activity.RESULT_OK
import android.content.Intent
import android.graphics.PorterDuff
import android.os.Bundle
import android.view.View
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.VMBaseFragment
import io.legado.app.constant.EventBus
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.databinding.FragmentChapterListBinding
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.isAudio
import io.legado.app.help.book.isLocal
import io.legado.app.help.book.isVideo
import io.legado.app.help.book.simulatedTotalChapterNum
import io.legado.app.lib.theme.UiCorner
import io.legado.app.lib.theme.bottomBackground
import io.legado.app.lib.theme.getPrimaryTextColor
import io.legado.app.ui.widget.recycler.UpLinearLayoutManager
import io.legado.app.ui.widget.recycler.VerticalDivider
import io.legado.app.utils.ColorUtils
import io.legado.app.utils.applyNavigationBarPadding
import io.legado.app.utils.observeEvent
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ChapterListFragment : VMBaseFragment<TocViewModel>(R.layout.fragment_chapter_list),
    ChapterListAdapter.Callback,
    TocViewModel.ChapterListCallBack {
    override val viewModel by activityViewModels<TocViewModel>()
    private val binding by viewBinding(FragmentChapterListBinding::bind)
    private var mLayoutManager: UpLinearLayoutManager? = null
    private val adapter by lazy { ChapterListAdapter(requireContext(), this) }
    private val collapsedVolumeIndexes = linkedSetOf<Int>()
    private var durChapterIndex = 0
    private var chapterList: List<BookChapter> = emptyList()
    private var displayChapterList: List<BookChapter> = emptyList()
    private var currentSearchKey: String? = null
    private var suppressNextListScroll = false

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) = binding.run {
        viewModel.registerChapterListCallBack(this@ChapterListFragment)
        val bbg = bottomBackground
        val btc = requireContext().getPrimaryTextColor(ColorUtils.isColorLight(bbg))
        llChapterBaseInfo.setBackgroundColor(
            UiCorner.groupColor(UiCorner.SurfaceGroup.UI, bbg)
        )
        tvCurrentChapterInfo.setTextColor(btc)
        ivChapterTop.setColorFilter(btc, PorterDuff.Mode.SRC_IN)
        ivChapterBottom.setColorFilter(btc, PorterDuff.Mode.SRC_IN)
        initRecyclerView()
        initView()
        viewModel.bookData.observe(viewLifecycleOwner) {
            initBook(it)
        }
    }

    override fun onDestroyView() {
        viewModel.unregisterChapterListCallBack(this)
        adapter.clearDisplayTitle()
        binding.recyclerView.adapter = null
        mLayoutManager = null
        super.onDestroyView()
    }

    private fun initRecyclerView() {
        // 每次视图重建都新建 LayoutManager，避免复用已绑定旧 RecyclerView 的实例导致崩溃
        val layoutManager = UpLinearLayoutManager(requireContext())
        mLayoutManager = layoutManager
        binding.recyclerView.layoutManager = layoutManager
        binding.recyclerView.addItemDecoration(VerticalDivider(requireContext()))
        binding.recyclerView.adapter = adapter
    }

    private fun initView() = binding.run {
        ivChapterTop.setOnClickListener {
            mLayoutManager?.scrollToPositionWithOffset(0, 0)
        }
        ivChapterBottom.setOnClickListener {
            if (adapter.itemCount > 0) {
                mLayoutManager?.scrollToPositionWithOffset(adapter.itemCount - 1, 0)
            }
        }
        tvCurrentChapterInfo.setOnClickListener {
            mLayoutManager?.scrollToPositionWithOffset(visiblePositionOf(durChapterIndex), 0)
        }
        binding.llChapterBaseInfo.applyNavigationBarPadding()
    }

    @SuppressLint("SetTextI18n")
    private fun initBook(book: Book) {
        viewLifecycleOwner.lifecycleScope.launch {
            durChapterIndex = book.durChapterIndex
            upChapterList(null)
            binding.tvCurrentChapterInfo.text =
                "${book.durChapterTitle}(${book.durChapterIndex + 1}/${book.simulatedTotalChapterNum()})"
            initCacheFileNames(book)
        }
    }

    private fun initCacheFileNames(book: Book) {
        viewLifecycleOwner.lifecycleScope.launch(IO) {
            adapter.cacheFileNames.addAll(BookHelp.getChapterFiles(book))
            withContext(Main) {
                adapter.notifyItemRangeChanged(0, adapter.itemCount, true)
            }
        }
    }

    override fun observeLiveBus() {
        observeEvent<Pair<Book, BookChapter>>(EventBus.SAVE_CONTENT) { (book, chapter) ->
            if (view == null) return@observeEvent
            viewModel.bookData.value?.bookUrl?.let { bookUrl ->
                if (book.bookUrl == bookUrl) {
                    adapter.cacheFileNames.addAll(BookHelp.getChapterCacheFileNames(book, chapter))
                    if (viewModel.searchKey.isNullOrEmpty()) {
                        val position = visiblePositionOf(chapter.index)
                        if (position in 0 until adapter.itemCount) {
                            adapter.notifyItemChanged(position, true)
                        }
                    } else {
                        adapter.getItems().forEachIndexed { index, bookChapter ->
                            if (bookChapter.index == chapter.index) {
                                adapter.notifyItemChanged(index, true)
                            }
                        }
                    }
                }
            }
        }
    }

    override fun upChapterList(searchKey: String?) {
        viewLifecycleOwner.lifecycleScope.launch {
            withContext(IO) {
                val end = (book?.simulatedTotalChapterNum() ?: Int.MAX_VALUE) - 1
                when {
                    searchKey.isNullOrBlank() ->
                        appDb.bookChapterDao.getChapterList(viewModel.bookUrl, 0, end)

                    else -> appDb.bookChapterDao.search(viewModel.bookUrl, searchKey, 0, end)
                }
            }.let { chapters ->
                val displayChapters = if (viewModel.isTocDisplayReversed) {
                    chapters.asReversed()
                } else {
                    chapters
                }
                currentSearchKey = searchKey
                if (searchKey.isNullOrBlank()) {
                    chapterList = chapters
                    resetCollapsedVolumes(chapters)
                }
                displayChapterList = displayChapters
                adapter.setItems(visibleChapters(displayChapters, searchKey))
            }
        }
    }

    override fun onListChanged() {
        val viewOwner = viewLifecycleOwnerLiveData.value ?: return
        if (suppressNextListScroll) {
            suppressNextListScroll = false
            adapter.upDisplayTitles(
                mLayoutManager?.findFirstVisibleItemPosition()?.coerceAtLeast(0) ?: 0
            )
            return
        }
        viewOwner.lifecycleScope.launch {
            val scrollPos = visiblePositionOf(durChapterIndex)
            val recyclerView = binding.recyclerView
            recyclerView.post {
                if (viewLifecycleOwnerLiveData.value == null) return@post
                val centerOffset = (recyclerView.height / 2).coerceAtLeast(0)
                mLayoutManager?.scrollToPositionWithOffset(scrollPos, centerOffset)
                adapter.upDisplayTitles(scrollPos)
            }
        }
    }

    override fun clearDisplayTitle() {
        adapter.clearDisplayTitle()
        adapter.upDisplayTitles(mLayoutManager?.findFirstVisibleItemPosition() ?: 0)
    }

    override fun upAdapter() {
        adapter.notifyItemRangeChanged(0, adapter.itemCount)
    }

    override val scope: CoroutineScope
        get() = viewLifecycleOwner.lifecycleScope

    override val book: Book?
        get() = viewModel.bookData.value

    override val isLocalBook: Boolean
        get() = viewModel.bookData.value?.isLocal == true

    override val isAudioBook: Boolean
        get() = viewModel.bookData.value?.let { it.isAudio || it.isVideo } == true

    override fun durChapterIndex(): Int {
        return durChapterIndex
    }

    override fun isVolumeCollapsed(bookChapter: BookChapter): Boolean {
        return bookChapter.isVolume && collapsedVolumeIndexes.contains(bookChapter.index)
    }

    override fun toggleVolume(bookChapter: BookChapter) {
        if (!bookChapter.isVolume || !currentSearchKey.isNullOrBlank()) {
            return
        }
        if (!collapsedVolumeIndexes.add(bookChapter.index)) {
            collapsedVolumeIndexes.remove(bookChapter.index)
        }
        val visible = visibleChapters(displayChapterList, currentSearchKey)
        suppressNextListScroll = true
        adapter.setItems(visible)
        binding.recyclerView.post {
            visible.indexOfFirst { it.index == bookChapter.index }.takeIf { it >= 0 }?.let {
                adapter.notifyItemChanged(it, true)
            }
        }
    }

    private fun visibleChapters(
        chapters: List<BookChapter>,
        searchKey: String?
    ): List<BookChapter> {
        if (!searchKey.isNullOrBlank() || collapsedVolumeIndexes.isEmpty()) {
            return chapters
        }
        val visible = arrayListOf<BookChapter>()
        var hideUntilNextVolume = false
        chapters.forEach { chapter ->
            if (chapter.isVolume) {
                visible.add(chapter)
                hideUntilNextVolume = collapsedVolumeIndexes.contains(chapter.index)
            } else if (!hideUntilNextVolume) {
                visible.add(chapter)
            }
        }
        return visible
    }

    private fun resetCollapsedVolumes(chapters: List<BookChapter>) {
        collapsedVolumeIndexes.clear()
        val currentVolumeIndex = chapters
            .filter { it.isVolume && it.index <= durChapterIndex }
            .maxByOrNull { it.index }
            ?.index
        chapters.filter { it.isVolume }.forEach { chapter ->
            if (chapter.index != currentVolumeIndex) {
                collapsedVolumeIndexes.add(chapter.index)
            }
        }
    }

    private fun visiblePositionOf(chapterIndex: Int): Int {
        val items = adapter.getItems()
        val exact = items.indexOfFirst { it.index == chapterIndex }
        if (exact >= 0) return exact
        return items.indexOfLast { it.index < chapterIndex }.coerceAtLeast(0)
    }

    override fun openChapter(bookChapter: BookChapter) {
        activity?.run {
            if (book?.isVideo == true) {
                val volumes = arrayListOf<BookChapter>()
                chapterList.forEach { chapter ->
                    if (chapter.isVolume) {
                        volumes.add(chapter)
                    }
                }
                var chapterInVolumeIndex = 0
                var durVolumeIndex = 0
                if (volumes.isNotEmpty()) {
                    for ((index, volume) in volumes.reversed().withIndex()) {
                        val first = bookChapter.index
                        if (volume.index < first) {
                            chapterInVolumeIndex = first - volume.index - 1
                            durVolumeIndex = volumes.size - index - 1
                            break
                        } else if (volume.index == first) {
                            chapterInVolumeIndex = 0
                            durVolumeIndex = volumes.size - index - 1
                            break
                        }
                    }
                } else {
                    chapterInVolumeIndex = bookChapter.index
                }
                setResult(
                    RESULT_OK, Intent()
                        .putExtra("index", bookChapter.index)
                        .putExtra("chapterChanged", bookChapter.index != durChapterIndex)
                        .putExtra("durVolumeIndex", durVolumeIndex)
                        .putExtra("chapterInVolumeIndex", chapterInVolumeIndex)
                )
                finish()
                return@run
            }
            setResult(
                RESULT_OK, Intent()
                    .putExtra("index", bookChapter.index)
                    .putExtra("chapterChanged", bookChapter.index != durChapterIndex)
            )
            finish()
        }
    }

}
