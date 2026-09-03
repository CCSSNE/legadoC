package io.legado.app.ui.book.cache

import android.os.Bundle
import android.graphics.Color
import android.view.Gravity
import android.view.MotionEvent
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.addCallback
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.SimpleItemAnimator
import io.legado.app.R
import io.legado.app.base.VMBaseActivity
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.CreationResult
import io.legado.app.databinding.ActivityCacheManageBinding
import io.legado.app.help.AppWebDav
import io.legado.app.help.ai.AiCreationImageFile
import io.legado.app.help.cache.CacheCoordinator
import io.legado.app.help.cache.CacheLifecycle
import io.legado.app.help.cache.CacheTaskStatus
import io.legado.app.help.cache.CacheTaskState
import io.legado.app.help.cache.MediaCacheTaskState
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.dialogs.selector
import io.legado.app.lib.theme.SegmentedControlStyle
import io.legado.app.lib.theme.UiCorner
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.primaryTextColor
import io.legado.app.ui.book.read.creation.AiCreationPhotoDialog
import io.legado.app.utils.applyNavigationBarMargin
import io.legado.app.utils.applyNavigationBarPadding
import io.legado.app.utils.gone
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.startActivityForBook
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import io.legado.app.utils.visible
import io.legado.app.utils.compress.ZipUtils
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
    CreationGridAdapter.Callback,
    CacheChapterDialog.Callback {

    companion object {
        const val EXTRA_MODE = "cacheManageMode"
        const val MODE_AUDIO = "audio"
    }

    override val binding by viewBinding(ActivityCacheManageBinding::inflate)
    override val viewModel by viewModels<CacheManageViewModel>()

    private val adapter by lazy { CacheManageAdapter(this, this) }
    private val creationAdapter by lazy { CreationGridAdapter(this, this) }
    private var creationItems: List<CreationResult> = emptyList()
    private val creationSelection = linkedSetOf<Long>()
    private var showingCreation = false
    private var audioTaskReloadJob: Job? = null
    private var lastMissingTaskReloadAt = 0L
    private val handledTerminalTaskReloads = hashSetOf<String>()
    private var showingStats = false
    private var swipeDownX = 0f
    private var swipeDownY = 0f

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        val initialMode = when (intent.getStringExtra(EXTRA_MODE)) {
            MODE_AUDIO -> CacheManageMode.AUDIO
            else -> CacheManageMode.BOOK
        }
        initView(initialMode)
        observeData()
        observeTasks()
        viewModel.load(initialMode)
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

    private fun initView(initialMode: CacheManageMode) = binding.run {
        listOf(cardStatsTotal, cardStatsDetail, cardStatsCache).forEach {
            it.background = UiCorner.rounded(
                UiCorner.themeSurfaceCardColor(this@CacheManageActivity),
                UiCorner.panelRadius(this@CacheManageActivity)
            )
        }
        listOf(btnUploadAll, btnDeleteAll).forEach {
            it.background = UiCorner.softActionSelector(
                Color.TRANSPARENT,
                UiCorner.themeSurfaceCardColor(this@CacheManageActivity),
                UiCorner.actionRadius(this@CacheManageActivity)
            )
        }
        recyclerView.layoutManager = LinearLayoutManager(this@CacheManageActivity)
        recyclerView.adapter = adapter
        (recyclerView.itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false
        btnBooks.setOnClickListener { switchMode(CacheManageMode.BOOK) }
        btnAudio.setOnClickListener { switchMode(CacheManageMode.AUDIO) }
        btnVideo.setOnClickListener { switchMode(CacheManageMode.VIDEO) }
        btnManga.setOnClickListener { switchMode(CacheManageMode.MANGA) }
        btnCreation.setOnClickListener { showCreation() }
        btnStats.setOnClickListener { showStats() }
        btnUploadAll.setOnClickListener { uploadAll() }
        btnDeleteAll.setOnClickListener { deleteAll() }
        btnSelectAll.setOnClickListener { toggleSelectAll() }
        batchBar.applyNavigationBarMargin(withInitialMargin = true)
        statsScroll.applyNavigationBarPadding(withInitialPadding = true)
        updateTabs(initialMode)
        onBackPressedDispatcher.addCallback(this@CacheManageActivity) {
            if (showingCreation && creationSelection.isNotEmpty()) {
                exitCreationSelection()
                return@addCallback
            }
            finish()
        }
    }

    private fun observeData() {
        viewModel.itemsLiveData.observe(this) { items ->
            adapter.setItems(items)
            binding.tvEmpty.run {
                if (!showingStats && !showingCreation && items.isEmpty()) {
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
            CacheCoordinator.snapshot.collectLatest { snapshot ->
                val states = snapshot.toMediaTaskStates()
                adapter.updateTaskStates(states)
                if (viewModel.mode == CacheManageMode.AUDIO || viewModel.mode == CacheManageMode.VIDEO) {
                    reloadAudioItemsWhenNeeded(states)
                }
            }
        }
    }

    private fun reloadAudioItemsWhenNeeded(states: Map<String, MediaCacheTaskState>) {
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
        if (showingCreation) {
            exitCreationMode()
        }
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
        if (showingCreation) {
            exitCreationMode()
        }
        updateTabs(null)
        recyclerView.gone()
        tvEmpty.gone()
        rotateLoading.gone()
        batchBar.gone()
        statsScroll.visible()
        viewModel.loadStats()
    }

    private fun showCreation() = binding.run {
        showingStats = false
        showingCreation = true
        updateTabs(CREATION_TAB)
        recyclerView.visible()
        recyclerView.layoutManager = GridLayoutManager(this@CacheManageActivity, 3)
        recyclerView.adapter = creationAdapter
        statsScroll.gone()
        rotateLoading.gone()
        batchBar.visible()
        btnUploadAll.gone()
        btnDeleteAll.gone()
        btnSelectAll.visible()
        tvEmpty.gone()
        exitCreationSelection()
        loadCreation()
    }

    private fun exitCreationMode() {
        showingCreation = false
        exitCreationSelection()
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter
        binding.btnUploadAll.visible()
        binding.btnDeleteAll.visible()
        binding.btnSelectAll.gone()
    }

    private fun exitCreationSelection() {
        creationSelection.clear()
        creationAdapter.selectedIds = emptySet()
    }

    private fun loadCreation() {
        lifecycleScope.launch {
            creationItems = withContext(Dispatchers.IO) {
                appDb.creationResultDao.getAll()
            }
            creationAdapter.setItems(creationItems)
            if (showingCreation) {
                if (creationItems.isEmpty()) {
                    binding.tvEmpty.text =
                        getString(R.string.cache_manage_empty, getString(R.string.cache_manage_creation))
                    binding.tvEmpty.visible()
                } else {
                    binding.tvEmpty.gone()
                }
            }
        }
    }

    private fun toggleSelectAll() {
        if (creationSelection.size >= creationItems.size && creationItems.isNotEmpty()) {
            exitCreationSelection()
        } else {
            creationSelection.clear()
            creationSelection.addAll(creationItems.map { it.resultId })
            creationAdapter.selectedIds = creationSelection.toSet()
        }
    }

    private fun uploadCreationSelection() {
        val files = creationItems
            .filter { it.resultId in creationSelection }
            .map { AiCreationImageFile.fileOf(it.fileName) }
        if (files.isEmpty()) {
            toastOnUi(R.string.cache_manage_batch_empty)
            return
        }
        lifecycleScope.launch {
            toastOnUi(R.string.cache_manage_uploading)
            runCatching {
                val zipFile = java.io.File(cacheDir, "creation_${System.currentTimeMillis()}.zip")
                ZipUtils.zipFiles(files, zipFile)
                withContext(Dispatchers.IO) {
                    AppWebDav.uploadCachePackage(
                        zipFile.name.removeSuffix(".zip"),
                        zipFile
                    )
                }
                zipFile.delete()
            }.onSuccess {
                toastOnUi(R.string.cache_manage_upload_success)
            }.onFailure {
                toastOnUi(getString(R.string.cache_manage_upload_failed, it.localizedMessage))
            }
        }
    }

    private fun saveCreationSelection() {
        val selected = creationItems.filter { it.resultId in creationSelection }
        lifecycleScope.launch {
            var success = 0
            selected.forEach { item ->
                if (AiCreationImageFile.saveToAlbum(this@CacheManageActivity, item.fileName)) {
                    success++
                }
            }
            toastOnUi(
                if (success == selected.size && selected.isNotEmpty()) {
                    R.string.illustration_saved_to_album
                } else {
                    R.string.illustration_save_failed
                }
            )
        }
    }

    private fun deleteCreationSelection() {
        val count = creationSelection.size
        if (count == 0) return
        alert(getString(R.string.delete), getString(R.string.cache_manage_creation_delete_confirm, count)) {
            yesButton {
                lifecycleScope.launch {
                    val targets = creationItems.filter { it.resultId in creationSelection }
                    withContext(Dispatchers.IO) {
                        targets.forEach { AiCreationImageFile.delete(it.fileName) }
                        appDb.creationResultDao.delete(targets)
                    }
                    toastOnUi(R.string.delete_success)
                    exitCreationSelection()
                    loadCreation()
                }
            }
            noButton()
        }
    }

    private fun switchAdjacentTab(offset: Int) {
        val currentIndex = tabOrder.indexOfFirst { tab ->
            when {
                showingStats -> tab == null
                showingCreation -> tab === CREATION_TAB
                else -> tab == viewModel.mode
            }
        }
        val targetIndex = currentIndex + offset
        if (targetIndex !in tabOrder.indices) return
        when (val target = tabOrder[targetIndex]) {
            null -> showStats()
            is CacheManageMode -> switchMode(target)
            CREATION_TAB -> showCreation()
        }
    }

    private fun updateTabs(mode: Any?) = binding.run {
        SegmentedControlStyle.apply(
            track = tabBar,
            items = listOf(btnBooks, btnAudio, btnVideo, btnManga, btnCreation, btnStats),
            selectedIndex = when (mode) {
                CacheManageMode.BOOK -> 0
                CacheManageMode.AUDIO -> 1
                CacheManageMode.VIDEO -> 2
                CacheManageMode.MANGA -> 3
                CREATION_TAB -> 4
                null -> 5
                else -> 0
            },
            palette = SegmentedControlStyle.Palette(
                trackColor = UiCorner.surfaceColor(
                    UiCorner.themeSurfaceMutedColor(this@CacheManageActivity)
                ),
                selectedColor = UiCorner.surfaceColor(
                    UiCorner.themeSurfaceCardColor(this@CacheManageActivity),
                    pressed = true
                ),
                textColor = this@CacheManageActivity.primaryTextColor,
                selectedTextColor = this@CacheManageActivity.accentColor
            )
        )
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
                    background = UiCorner.softActionSelector(
                        Color.TRANSPARENT,
                        UiCorner.themeSurfaceMutedColor(this@CacheManageActivity),
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

    override fun openReviewSnapshots(item: CacheBookItem) {
        showDialogFragment(ReviewSnapshotStatusDialog.newInstance(item.book))
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
        val task = CacheCoordinator.snapshot.value.findMediaDownloadTask(item.book.bookUrl) ?: return
        when (task.second.status) {
            CacheLifecycle.PAUSED -> CacheCoordinator.resume(task.first)
            CacheLifecycle.RUNNING,
            CacheLifecycle.QUEUED,
            CacheLifecycle.PAUSING -> CacheCoordinator.pause(task.first)
            else -> Unit
        }
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

    override fun onItemClick(item: CreationResult) {
        if (creationSelection.isEmpty()) {
            val files = creationItems.map { it.fileName }
            val position = creationItems.indexOfFirst { it.resultId == item.resultId }
            showDialogFragment(AiCreationPhotoDialog.newInstance(files, position))
            return
        }
        if (item.resultId in creationSelection) {
            creationSelection.remove(item.resultId)
        } else {
            creationSelection.add(item.resultId)
        }
        creationAdapter.selectedIds = creationSelection.toSet()
    }

    override fun onItemLongClick(item: CreationResult) {
        if (creationSelection.isEmpty()) {
            creationSelection.add(item.resultId)
            creationAdapter.selectedIds = creationSelection.toSet()
            return
        }
        if (item.resultId !in creationSelection) {
            creationSelection.add(item.resultId)
            creationAdapter.selectedIds = creationSelection.toSet()
            return
        }
        selector(
            AiCreationImageFile.fileOf(item.fileName).name,
            listOf(
                getString(R.string.cache_manage_upload),
                getString(R.string.illustration_save_to_album),
                getString(R.string.delete)
            )
        ) { _, _, index ->
            when (index) {
                0 -> uploadCreationSelection()
                1 -> saveCreationSelection()
                2 -> deleteCreationSelection()
            }
        }
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

private val CREATION_TAB = Any()

private val tabOrder = listOf<Any?>(
    CacheManageMode.BOOK,
    CacheManageMode.AUDIO,
    CacheManageMode.VIDEO,
    CacheManageMode.MANGA,
    CREATION_TAB,
    null
)

private fun CacheBookItem.hasLockedAudioTask(): Boolean {
    if (CacheCoordinator.snapshot.value.findMediaDownloadTask(book.bookUrl)?.second?.locksCacheActions() == true) {
        return true
    }
    return sourceVariants.any {
        CacheCoordinator.snapshot.value.findMediaDownloadTask(it.book.bookUrl)?.second?.locksCacheActions() == true
    }
}

private fun CacheTaskState.locksCacheActions(): Boolean {
    return status == CacheLifecycle.RUNNING ||
        status == CacheLifecycle.QUEUED ||
        status == CacheLifecycle.PAUSING ||
        status == CacheLifecycle.PAUSED
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
