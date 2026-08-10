package io.legado.app.ui.book.cache

import android.os.Bundle
import android.graphics.Color
import android.view.Gravity
import android.view.MotionEvent
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.SimpleItemAnimator
import io.legado.app.R
import io.legado.app.base.VMBaseActivity
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.databinding.ActivityCacheManageBinding
import io.legado.app.help.AppWebDav
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.dialogs.selector
import io.legado.app.lib.theme.UiCorner
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.primaryTextColor
import io.legado.app.utils.applyNavigationBarMargin
import io.legado.app.utils.applyNavigationBarPadding
import io.legado.app.utils.gone
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.startActivityForBook
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import io.legado.app.utils.visible
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

class CacheManageActivity :
    VMBaseActivity<ActivityCacheManageBinding, CacheManageViewModel>(),
    CacheManageAdapter.Callback,
    CacheChapterDialog.Callback {

    override val binding by viewBinding(ActivityCacheManageBinding::inflate)
    override val viewModel by viewModels<CacheManageViewModel>()

    private val adapter by lazy { CacheManageAdapter(this, this) }
    private var audioTaskReloadJob: Job? = null
    private var lastMissingTaskReloadAt = 0L
    private val handledTerminalTaskReloads = hashSetOf<String>()
    private var showingStats = false
    private var swipeDownX = 0f
    private var swipeDownY = 0f

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        initView()
        observeData()
        observeTasks()
        viewModel.load(CacheManageMode.BOOK)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                swipeDownX = ev.x
                swipeDownY = ev.y
            }
            MotionEvent.ACTION_UP -> {
                val dx = ev.x - swipeDownX
                val dy = ev.y - swipeDownY
                if (abs(dx) > SWIPE_TAB_DISTANCE_DP.dp && abs(dx) > abs(dy) * 1.35f) {
                    switchAdjacentTab(if (dx < 0) 1 else -1)
                    return true
                }
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun initView() = binding.run {
        tabBar.background = UiCorner.opaqueRounded(
            ContextCompat.getColor(this@CacheManageActivity, R.color.background_menu),
            UiCorner.panelRadius(this@CacheManageActivity)
        )
        listOf(btnBooks, btnAudio, btnVideo, btnManga, btnStats).forEach {
            it.background = UiCorner.actionSelector(
                Color.TRANSPARENT,
                ContextCompat.getColor(this@CacheManageActivity, R.color.background_card),
                UiCorner.actionRadius(this@CacheManageActivity)
            )
        }
        listOf(cardStatsTotal, cardStatsDetail, cardStatsCache).forEach {
            it.background = UiCorner.rounded(
                ContextCompat.getColor(this@CacheManageActivity, R.color.background_card),
                UiCorner.panelRadius(this@CacheManageActivity)
            )
        }
        recyclerView.layoutManager = LinearLayoutManager(this@CacheManageActivity)
        recyclerView.adapter = adapter
        (recyclerView.itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false
        btnBooks.setOnClickListener { switchMode(CacheManageMode.BOOK) }
        btnAudio.setOnClickListener { switchMode(CacheManageMode.AUDIO) }
        btnVideo.setOnClickListener { switchMode(CacheManageMode.VIDEO) }
        btnManga.setOnClickListener { switchMode(CacheManageMode.MANGA) }
        btnStats.setOnClickListener { showStats() }
        btnUploadAll.setOnClickListener { uploadAll() }
        btnDeleteAll.setOnClickListener { deleteAll() }
        batchBar.applyNavigationBarMargin(withInitialMargin = true)
        statsScroll.applyNavigationBarPadding(withInitialPadding = true)
        updateTabs(CacheManageMode.BOOK)
    }

    private fun observeData() {
        viewModel.itemsLiveData.observe(this) { items ->
            adapter.setItems(items)
            binding.tvEmpty.run {
                if (!showingStats && items.isEmpty()) {
                    text = getString(R.string.cache_manage_empty, getString(viewModel.mode.titleRes))
                    visible()
                } else {
                    gone()
                }
            }
        }
        viewModel.summaryLiveData.observe(this) { summary ->
            if (showingStats) renderStats(summary)
        }
        viewModel.loadingLiveData.observe(this) { loading ->
            if (loading && !showingStats) binding.rotateLoading.visible() else binding.rotateLoading.gone()
        }
    }

    private fun observeTasks() {
        lifecycleScope.launch {
            AudioCacheTaskManager.states.collectLatest { states ->
                adapter.updateTaskStates(states)
                if (viewModel.mode == CacheManageMode.AUDIO || viewModel.mode == CacheManageMode.VIDEO) {
                    reloadAudioItemsWhenNeeded(states)
                }
            }
        }
    }

    private fun reloadAudioItemsWhenNeeded(states: Map<String, AudioCacheTaskState>) {
        val stateValues = states.values
        val activeTaskBookUrls = stateValues
            .asSequence()
            .filter { it.active }
            .mapTo(hashSetOf<String>()) { it.bookUrl }
        if (activeTaskBookUrls.isNotEmpty()) {
            val visibleBookUrls = hashSetOf<String>()
            adapter.getItems().forEach { item ->
                if (item.sourceVariants.isEmpty()) {
                    visibleBookUrls.add(item.book.bookUrl)
                } else {
                    item.sourceVariants.forEach { visibleBookUrls.add(it.book.bookUrl) }
                }
            }
            val missingActiveTasks = activeTaskBookUrls - visibleBookUrls
            if (missingActiveTasks.isNotEmpty()) {
                val now = System.currentTimeMillis()
                if (now - lastMissingTaskReloadAt > MISSING_TASK_RELOAD_INTERVAL_MS && !viewModel.isLoading()) {
                    lastMissingTaskReloadAt = now
                    scheduleAudioTaskReload(MISSING_TASK_RELOAD_DELAY_MS)
                }
            }
        }
        stateValues
            .filter { !it.active && it.status.isTerminalForListRefresh() }
            .forEach { state ->
                val key = "${state.bookUrl}:${state.status}:${state.completedChapters}:${state.totalChapters}"
                if (handledTerminalTaskReloads.add(key)) {
                    scheduleAudioTaskReload(TERMINAL_TASK_RELOAD_DELAY_MS)
                }
            }
    }

    private fun scheduleAudioTaskReload(delayMs: Long) {
        if (audioTaskReloadJob?.isActive == true) return
        audioTaskReloadJob = lifecycleScope.launch {
            delay(delayMs)
            val mode = viewModel.mode
            if ((mode == CacheManageMode.AUDIO || mode == CacheManageMode.VIDEO) && !viewModel.isLoading()) {
                viewModel.load(mode)
            }
        }
    }

    private fun switchMode(mode: CacheManageMode) {
        showingStats = false
        updateTabs(mode)
        binding.recyclerView.visible()
        binding.statsScroll.gone()
        binding.batchBar.visible()
        binding.tvEmpty.gone()
        if (viewModel.mode == mode) return
        viewModel.load(mode)
    }

    private fun showStats() = binding.run {
        showingStats = true
        updateTabs(null)
        recyclerView.gone()
        tvEmpty.gone()
        rotateLoading.gone()
        batchBar.gone()
        statsScroll.visible()
        viewModel.loadStats()
    }

    private fun switchAdjacentTab(offset: Int) {
        val currentIndex = tabOrder.indexOfFirst { tab ->
            if (showingStats) tab == null else tab == viewModel.mode
        }
        val targetIndex = currentIndex + offset
        if (targetIndex !in tabOrder.indices) return
        val target = tabOrder[targetIndex]
        if (target == null) {
            showStats()
        } else {
            switchMode(target)
        }
    }

    private fun updateTabs(mode: CacheManageMode?) = binding.run {
        btnBooks.isSelected = mode == CacheManageMode.BOOK
        btnAudio.isSelected = mode == CacheManageMode.AUDIO
        btnVideo.isSelected = mode == CacheManageMode.VIDEO
        btnManga.isSelected = mode == CacheManageMode.MANGA
        btnStats.isSelected = mode == null
        btnBooks.setTextColor(if (mode == CacheManageMode.BOOK) accentColor else primaryTextColor)
        btnAudio.setTextColor(if (mode == CacheManageMode.AUDIO) accentColor else primaryTextColor)
        btnVideo.setTextColor(if (mode == CacheManageMode.VIDEO) accentColor else primaryTextColor)
        btnManga.setTextColor(if (mode == CacheManageMode.MANGA) accentColor else primaryTextColor)
        btnStats.setTextColor(if (mode == null) accentColor else primaryTextColor)
    }

    private fun renderStats(summary: CacheSummary) = binding.run {
        tvStatsTotal.text = summarySize(summary.totalCacheSize)
        layStatsDetails.removeAllViews()
        layStatsCacheDetails.removeAllViews()
        val details = summary.storageDetails
            .asSequence()
            .filter { it.bytes > 0L }
            .toList()
        if (details.isEmpty()) {
            layStatsDetails.addView(statsEmptyRow())
            layStatsCacheDetails.addView(statsEmptyRow())
            return@run
        }
        val dataDetails = sortStatsDetails(details.filter { it.deleteTarget == null })
        val cacheDetails = sortStatsDetails(details.filter { it.deleteTarget != null })
        if (dataDetails.isEmpty()) {
            layStatsDetails.addView(statsEmptyRow())
        } else {
            dataDetails.forEach { detail ->
                layStatsDetails.addView(statsDetailRow(detail))
            }
        }
        if (cacheDetails.isEmpty()) {
            layStatsCacheDetails.addView(statsEmptyRow())
        } else {
            cacheDetails.forEach { detail ->
                layStatsCacheDetails.addView(statsDetailRow(detail))
            }
        }
    }

    private fun sortStatsDetails(details: List<CacheStorageDetail>): List<CacheStorageDetail> {
        val otherName = getString(R.string.cache_manage_storage_other)
        return details
            .filterNot { it.name == otherName }
            .sortedByDescending { it.bytes } +
                details.filter { it.name == otherName }
    }

    private fun statsDetailRow(detail: CacheStorageDetail): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 9.dp, 0, 9.dp)
            addView(TextView(context).apply {
                text = detail.name
                setTextColor(secondaryTextColor())
                textSize = 13f
                maxLines = 1
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(TextView(context).apply {
                text = summarySize(detail.bytes)
                setTextColor(primaryTextColor)
                textSize = 14f
                gravity = Gravity.END
                maxLines = 1
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            detail.deleteTarget?.let { target ->
                addView(ImageButton(context).apply {
                    setImageResource(R.drawable.ic_outline_delete)
                    setColorFilter(secondaryTextColor())
                    background = UiCorner.actionSelector(
                        Color.TRANSPARENT,
                        ContextCompat.getColor(this@CacheManageActivity, R.color.background_menu),
                        UiCorner.actionRadius(this@CacheManageActivity)
                    )
                    contentDescription = getString(R.string.delete)
                    setPadding(8.dp, 8.dp, 8.dp, 8.dp)
                    setOnClickListener { confirmDeleteStorage(detail.name, target) }
                }, LinearLayout.LayoutParams(36.dp, 36.dp).apply {
                    marginStart = 6.dp
                })
            }
        }
    }

    private fun confirmDeleteStorage(name: String, target: CacheStorageDeleteTarget) {
        val message = if (target == CacheStorageDeleteTarget.WEBVIEW) {
            getString(R.string.cache_manage_delete_webview_confirm, name)
        } else {
            getString(R.string.cache_manage_delete_storage_confirm, name)
        }
        alert(getString(R.string.delete), message) {
            yesButton {
                viewModel.deleteStorageDetail(target) {
                    toastOnUi(R.string.delete_success)
                }
            }
            noButton()
        }
    }

    private fun statsEmptyRow(): TextView {
        return TextView(this).apply {
            text = getString(R.string.cache_manage_stats_empty)
            setTextColor(secondaryTextColor())
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(0, 24.dp, 0, 20.dp)
        }
    }

    private fun secondaryTextColor(): Int {
        return ContextCompat.getColor(this, R.color.secondaryText)
    }

    override fun openChapters(item: CacheBookItem) {
        showDialogFragment(CacheChapterDialog.newInstance(item.book))
    }

    override fun upload(item: CacheBookItem) {
        lifecycleScope.launch {
            toastOnUi(R.string.cache_manage_uploading)
            kotlin.runCatching {
                val zipFile = viewModel.createCachePackage(item.book)
                withContext(Dispatchers.IO) {
                    AppWebDav.uploadCachePackage(zipFile.name, zipFile)
                }
            }.onSuccess {
                toastOnUi(R.string.cache_manage_upload_success)
            }.onFailure {
                toastOnUi(getString(R.string.cache_manage_upload_failed, it.localizedMessage))
            }
        }
    }

    override fun restoreToBookshelf(item: CacheBookItem) {
        lifecycleScope.launch {
            kotlin.runCatching {
                viewModel.restoreCacheToBookshelf(item)
            }.onSuccess { success ->
                if (success) {
                    toastOnUi(
                        if (item.inBookshelf) R.string.cache_manage_use_cache_success
                        else R.string.cache_manage_add_bookshelf_success
                    )
                    viewModel.load()
                } else {
                    toastOnUi(R.string.cache_manage_no_cache)
                }
            }.onFailure {
                toastOnUi(it.localizedMessage ?: getString(R.string.error))
            }
        }
    }

    override fun deleteBookCache(item: CacheBookItem) {
        alert(getString(R.string.delete), getString(R.string.cache_manage_delete_book_confirm, item.book.name)) {
            yesButton {
                viewModel.deleteBookCache(item.book) {
                    toastOnUi(R.string.delete_success)
                }
            }
            noButton()
        }
    }

    override fun stopAudioCache(item: CacheBookItem) {
        AudioCacheTaskManager.togglePause(item.book.bookUrl)
    }

    override fun selectSource(item: CacheBookItem) {
        val variants = item.sourceVariants
        if (variants.size <= 1) return
        val labels: List<CharSequence> = variants.map { variant ->
            buildString {
                append(
                    if (variant.sourceAvailable) {
                        variant.sourceName
                    } else {
                        getString(R.string.cache_manage_source_deleted, variant.sourceName)
                    }
                )
                append(" · ")
                append(
                    getString(
                        R.string.cache_manage_cached_count,
                        variant.cachedCount,
                        variant.totalChapterCount
                    )
                )
                append(" · ")
                append(summarySize(variant.storageSizeBytes))
            }
        }
        selector(getString(R.string.cache_manage_select_source), labels) { _, index ->
            val variant = variants.getOrNull(index) ?: return@selector
            viewModel.selectSource(item.groupKey, variant.sourceKey)
        }
    }

    private fun uploadAll() {
        val items = adapter.getItems().filter { it.cachedCount > 0 && !it.hasLockedAudioTask() }
        if (items.isEmpty()) {
            toastOnUi(R.string.cache_manage_batch_empty)
            return
        }
        lifecycleScope.launch {
            toastOnUi(R.string.cache_manage_uploading)
            var success = 0
            var failed = 0
            items.forEach { item ->
                kotlin.runCatching {
                    val zipFile = viewModel.createCachePackage(item.book)
                    withContext(Dispatchers.IO) {
                        AppWebDav.uploadCachePackage(zipFile.name, zipFile)
                    }
                }.onSuccess {
                    success++
                }.onFailure {
                    failed++
                }
            }
            toastOnUi(getString(R.string.cache_manage_batch_upload_done, success, failed))
        }
    }

    private fun deleteAll() {
        val items = adapter.getItems().filter { it.cachedCount > 0 && !it.hasLockedAudioTask() }
        if (items.isEmpty()) {
            toastOnUi(R.string.cache_manage_batch_empty)
            return
        }
        alert(
            getString(R.string.delete),
            getString(R.string.cache_manage_delete_all_confirm, items.size)
        ) {
            yesButton {
                viewModel.deleteBookCaches(items.map { it.book }) {
                    toastOnUi(R.string.delete_success)
                }
            }
            noButton()
        }
    }

    override fun onCacheChanged() {
        viewModel.load()
    }

    override fun openCacheChapter(book: Book, chapter: BookChapter) {
        val target = book.apply {
            durChapterIndex = chapter.index
            durChapterTitle = chapter.title
            durChapterPos = 0
        }
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                appDb.bookDao.update(target)
            }
            startActivityForBook(target)
        }
    }
}

private fun CacheTaskStatus.isTerminalForListRefresh(): Boolean {
    return this == CacheTaskStatus.COMPLETED ||
        this == CacheTaskStatus.PAUSED ||
        this == CacheTaskStatus.CANCELLED ||
        this == CacheTaskStatus.FAILED
}

private const val MISSING_TASK_RELOAD_INTERVAL_MS = 2500L
private const val MISSING_TASK_RELOAD_DELAY_MS = 250L
private const val TERMINAL_TASK_RELOAD_DELAY_MS = 600L
private const val SWIPE_TAB_DISTANCE_DP = 72

private val tabOrder = listOf(
    CacheManageMode.BOOK,
    CacheManageMode.AUDIO,
    CacheManageMode.VIDEO,
    CacheManageMode.MANGA,
    null
)

private fun CacheBookItem.hasLockedAudioTask(): Boolean {
    if (AudioCacheTaskManager.snapshot(book.bookUrl).locksCacheActions()) return true
    return sourceVariants.any { AudioCacheTaskManager.snapshot(it.book.bookUrl).locksCacheActions() }
}

private fun AudioCacheTaskState?.locksCacheActions(): Boolean {
    return this?.active == true || this?.status == CacheTaskStatus.PAUSED
}

private fun summarySize(bytes: Long): String {
    val mb = bytes.toDouble() / 1024.0 / 1024.0
    val gb = mb / 1024.0
    return when {
        gb >= 1.0 -> String.format(java.util.Locale.getDefault(), "%.2f GB", gb)
        mb >= 0.01 -> String.format(java.util.Locale.getDefault(), "%.2f MB", mb)
        else -> String.format(java.util.Locale.getDefault(), "%.1f KB", bytes / 1024.0)
    }
}

private val Int.dp: Int
    get() = (this * splitties.init.appCtx.resources.displayMetrics.density).toInt()
