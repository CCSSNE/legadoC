package io.legado.app.ui.book.audio

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.SeekBar
import androidx.activity.addCallback
import androidx.activity.viewModels
import androidx.core.view.doOnLayout
import androidx.lifecycle.lifecycleScope
import com.dirror.lyricviewx.OnPlayClickListener
import io.legado.app.R
import io.legado.app.base.BaseActivity
import io.legado.app.constant.AppLog
import io.legado.app.constant.EventBus
import io.legado.app.constant.PreferKey
import io.legado.app.constant.Status
import io.legado.app.constant.Theme
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.databinding.ActivityAudioPlayBinding
import io.legado.app.databinding.DialogDownloadChoiceBinding
import io.legado.app.help.book.AudioTextMapping
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.permission.NotificationPermission
import io.legado.app.lib.theme.ThemeStore.Companion.accentColor
import io.legado.app.model.BookCover
import io.legado.app.model.ReadAloud
import io.legado.app.model.ReadAloudUiState
import io.legado.app.model.ReadBook
import io.legado.app.service.BaseReadAloudService
import io.legado.app.service.ReadAloudEngineType
import io.legado.app.service.ReadAloudProgress
import io.legado.app.service.SourceAudioReadAloudService
import io.legado.app.ui.about.AppLogDialog
import io.legado.app.ui.book.audio.SliderPopup.Companion.SPEED
import io.legado.app.ui.book.audio.SliderPopup.Companion.TIMER
import io.legado.app.ui.book.audio.config.AudioSkipCredits
import io.legado.app.ui.book.read.config.SpeakEngineDialog
import io.legado.app.ui.book.cache.CacheManageViewModel
import io.legado.app.ui.book.cache.CacheManageActivity
import io.legado.app.ui.book.toc.TocActivityResult
import io.legado.app.ui.widget.seekbar.SeekBarChangeListener
import io.legado.app.utils.applyNavigationBarPadding
import io.legado.app.utils.dpToPx
import io.legado.app.utils.gone
import io.legado.app.utils.observeEvent
import io.legado.app.utils.sendToClip
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.startActivity
import io.legado.app.utils.toDurationTime
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import io.legado.app.utils.visible
import io.legado.app.utils.getPrefBoolean
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@SuppressLint("ObsoleteSdkInt")
class AudioPlayActivity : BaseActivity<ActivityAudioPlayBinding>(toolBarTheme = Theme.Dark) {

    override val binding by viewBinding(ActivityAudioPlayBinding::inflate)
    private val cacheViewModel by viewModels<CacheManageViewModel>()
    private val timerSliderPopup by lazy {
        SliderPopup(this, TIMER, ::updateSessionIndicators)
    }
    private val speedControlPopup by lazy {
        SliderPopup(this, SPEED, ::updateSessionIndicators)
    }
    private var displayedProgress: ReadAloudProgress? = null
    private var trackingProgress = false
    private var loadedLyric: String? = null
    private val ttsParagraphRows = arrayListOf<TtsParagraphRow>()
    private var ttsTextChapterIndex = -1
    private var ttsScrollTouching = false
    private var ttsScrollFollowBlockedUntil = 0L
    private var ttsScrollFollowJob: Job? = null
    private var pendingTtsHighlightIndex: Int? = null
    private var preserveAfterEngineSwitch = false

    private val tocActivityResult = registerForActivityResult(TocActivityResult()) { result ->
        result ?: return@registerForActivityResult
        val chapterIndex = result[0] as Int
        val chapterPosition = result[1] as Int
        if (chapterIndex == ReadBook.durChapterIndex && chapterPosition != 0) return@registerForActivityResult
        ReadBook.skipReadAloudSyncOnce = true
        val opened = ReadBook.openChapter(chapterIndex, chapterPosition, false) {
            ReadBook.skipReadAloudSyncOnce = false
            ReadBook.readAloud()
            updateChapterUi()
        }
        if (!opened) {
            ReadBook.skipReadAloudSyncOnce = false
            toastOnUi("章节位置无效：$chapterIndex")
        }
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        binding.titleBar.setBackgroundResource(R.color.transparent)
        val book = ReadBook.book
        if (book == null) {
            AppLog.put(
                "AudioPlayActivity cannot open: ReadBook.book is null, " +
                    "bookUrl=${intent.getStringExtra("bookUrl")}"
            )
            toastOnUi("当前没有可控制的听书会话")
            binding.root.post {
                if (!isFinishing && !isDestroyed) finish()
            }
            return
        }
        binding.titleBar.title = book.name
        loadCover(book)
        initProgressControl()
        initControls(book)
        updateChapterUi()
        updateEngineUi()
        updatePlayState()
        updateSessionIndicators()
        updateProgressForSelectedEngine()
        onBackPressedDispatcher.addCallback(this) {
            ReadBook.saveRead()
            if (intent.getBooleanExtra("returnToReader", false)) {
                ReadAloudUiState.markAudioPlayerReturn()
            }
            finish()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    private fun initProgressControl() = binding.playerProgress.run {
        setOnSeekBarChangeListener(object : SeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                binding.tvDurTime.text = progressLabel(displayedProgress?.kind, progress)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {
                trackingProgress = true
            }

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                trackingProgress = false
                val state = displayedProgress ?: return
                if (state.position != seekBar.progress) {
                    ReadAloud.seekToProgress(
                        this@AudioPlayActivity,
                        state.chapterIndex,
                        seekBar.progress,
                    )
                }
            }
        })
    }

    private fun initControls(book: Book) = binding.run {
        fabPlayStop.setOnClickListener {
            if (!BaseReadAloudService.isRun) {
                ReadAloud.play(this@AudioPlayActivity)
            } else if (BaseReadAloudService.pause) {
                ReadAloud.resume(this@AudioPlayActivity)
            } else {
                ReadAloud.pause(this@AudioPlayActivity)
            }
        }
        ivSkipPrevious.setOnClickListener {
            ReadAloud.prevChapter(this@AudioPlayActivity)
        }
        ivSkipNext.setOnClickListener {
            ReadAloud.nextChapter(this@AudioPlayActivity)
        }
        ivRewind15?.setOnClickListener { adjustProgressBy(-progressStep()) }
        ivForward15?.setOnClickListener { adjustProgressBy(progressStep()) }
        ivTimer.setOnClickListener {
            timerSliderPopup.showAsDropDown(it, 0, (-100).dpToPx(), Gravity.TOP)
        }
        ivSpeedControl.setOnClickListener {
            speedControlPopup.showAsDropDown(it, 0, (-100).dpToPx(), Gravity.TOP)
        }
        ivChapter.setOnClickListener { tocActivityResult.launch(book.bookUrl) }
        ivCache?.setOnClickListener { showAudioCacheRangeDialog(book) }
        ttsContentScroll.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    ttsScrollTouching = true
                    ttsScrollFollowJob?.cancel()
                    ttsScrollFollowJob = null
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    ttsScrollTouching = false
                    ttsScrollFollowBlockedUntil =
                        SystemClock.uptimeMillis() + AppConfig.readAloudScrollFollowTimeout
                    scheduleTtsParagraphCenter()
                }
            }
            false
        }
        llPlayMenu.applyNavigationBarPadding()
    }

    private fun adjustProgressBy(offset: Int) {
        val state = displayedProgress ?: return
        val target = (binding.playerProgress.progress + offset).coerceIn(0, binding.playerProgress.max)
        ReadAloud.seekToProgress(this, state.chapterIndex, target)
    }

    private fun progressStep(): Int {
        return if (displayedProgress?.kind == ReadAloudProgress.Kind.TIME) 15_000 else 1
    }

    private fun updateEngineUi() = binding.run {
        val sourceAudio = ReadAloud.selectedEngineType == ReadAloudEngineType.SOURCE_AUDIO
        ivCache?.visible(sourceAudio)
        if (sourceAudio) {
            ttsScrollFollowJob?.cancel()
            ttsScrollFollowJob = null
            ttsScrollTouching = false
            pendingTtsHighlightIndex = null
            ttsContentScroll.gone()
            loadLyric(ReadBook.curTextChapter?.chapter?.getVariable("lyric"))
        } else {
            loadedLyric = null
            lyricViewX.gone()
            bindTtsText()
        }
        invalidateOptionsMenu()
    }

    private fun updateChapterUi() {
        binding.tvSubTitle.text = ReadBook.curTextChapter?.title.orEmpty()
        if (ReadAloud.selectedEngineType == ReadAloudEngineType.SOURCE_AUDIO) {
            loadLyric(ReadBook.curTextChapter?.chapter?.getVariable("lyric"))
        } else {
            bindTtsText()
        }
    }

    private fun loadCover(book: Book) {
        val sourceOrigin = ReadBook.bookSource?.bookSourceUrl
        BookCover.load(this, book.getDisplayCover(), sourceOrigin = sourceOrigin) {
            BookCover.loadBlur(this, book.getDisplayCover(), sourceOrigin = sourceOrigin)
                .into(binding.ivBg)
        }.into(binding.ivCover)
    }

    private fun loadLyric(lyric: String?) {
        if (loadedLyric == lyric) return
        loadedLyric = lyric
        val mapping = AudioTextMapping.parse(lyric)
        if (!mapping.hasTimeMapping || lyric.isNullOrBlank()) {
            binding.lyricViewX.gone()
            return
        }
        binding.lyricViewX.run {
            loadLyric(lyric)
            visible()
            setNormalTextSize(50F)
            setCurrentTextSize(60F)
            setTimelineTextColor(accentColor)
            setDraggable(true, object : OnPlayClickListener {
                override fun onPlayClick(time: Long): Boolean {
                    val progress = displayedProgress ?: return false
                    ReadAloud.seekToProgress(
                        this@AudioPlayActivity,
                        progress.chapterIndex,
                        time.coerceIn(0L, progress.total.toLong()).toInt(),
                    )
                    return true
                }
            })
        }
    }

    private fun bindTtsText() {
        val chapter = ReadBook.curTextChapter ?: run {
            binding.ttsContentScroll.gone()
            return
        }
        binding.run {
            val paragraphs = chapter.getParagraphs(false)
            val canReuseViews = ttsTextChapterIndex == chapter.chapter.index &&
                    ttsParagraphRows.size == paragraphs.size &&
                    paragraphs.indices.all { index ->
                        ttsParagraphRows[index].view.text.toString() == paragraphs[index].text
                    }
            if (canReuseViews) {
                ttsContentScroll.visible(paragraphs.isNotEmpty())
                updateTtsParagraphHighlight(displayedProgress)
                return@run
            }
            ttsTextChapterIndex = chapter.chapter.index
            ttsContent.removeAllViews()
            ttsParagraphRows.clear()
            paragraphs.forEach { paragraph ->
                val normalTextSizeSp = if (paragraph.isTitle) {
                    TTS_TITLE_TEXT_SIZE_SP
                } else {
                    TTS_BODY_TEXT_SIZE_SP
                }
                val normalAlpha = if (paragraph.isTitle) 1f else TTS_BODY_TEXT_ALPHA
                val view = TextView(this@AudioPlayActivity).apply {
                    text = paragraph.text
                    textSize = normalTextSizeSp
                    setTextColor(Color.WHITE)
                    alpha = normalAlpha
                    setLineSpacing(0f, 1.12f)
                    setPadding(0, 8.dpToPx(), 0, 8.dpToPx())
                    isClickable = true
                    setOnClickListener {
                        ReadAloud.seekToTextPosition(
                            this@AudioPlayActivity,
                            chapter.chapter.index,
                            paragraph.chapterPosition,
                        )
                    }
                }
                ttsParagraphRows += TtsParagraphRow(
                    view = view,
                    normalTextSizeSp = normalTextSizeSp,
                    normalAlpha = normalAlpha,
                )
                ttsContent.addView(
                    view,
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    )
                )
            }
            ttsContentScroll.visible(paragraphs.isNotEmpty())
            updateTtsParagraphHighlight(displayedProgress)
        }
    }

    private fun updateTtsParagraphHighlight(progress: ReadAloudProgress?) {
        if (progress?.kind != ReadAloudProgress.Kind.PARAGRAPH ||
            progress.chapterIndex != ttsTextChapterIndex
        ) {
            applyTtsParagraphHighlight(null)
            return
        }
        val chapter = ReadBook.curTextChapter ?: return
        val serviceParagraphs = chapter.getParagraphs(
            getPrefBoolean(PreferKey.readAloudByPage, false)
        )
        val serviceParagraph = serviceParagraphs.getOrNull(progress.position) ?: run {
            applyTtsParagraphHighlight(null)
            return
        }
        val selectedIndex = chapter.getParagraphs(false).indexOfFirst {
            serviceParagraph.chapterPosition in it.chapterIndices
        }
        applyTtsParagraphHighlight(selectedIndex.takeIf { it in ttsParagraphRows.indices })
    }

    private fun updateTtsParagraphHighlightAt(chapterIndex: Int, chapterPosition: Int) {
        if (chapterIndex != ttsTextChapterIndex) {
            applyTtsParagraphHighlight(null)
            return
        }
        val chapter = ReadBook.curTextChapter ?: return
        val selectedIndex = chapter.getParagraphs(false).indexOfFirst {
            chapterPosition in it.chapterIndices
        }
        applyTtsParagraphHighlight(selectedIndex.takeIf { it in ttsParagraphRows.indices })
    }

    private fun applyTtsParagraphHighlight(selectedIndex: Int?) {
        pendingTtsHighlightIndex = selectedIndex
        ttsParagraphRows.forEachIndexed { index, row ->
            val selected = index == selectedIndex
            row.view.textSize = if (selected) {
                row.normalTextSizeSp * TTS_CURRENT_TEXT_SIZE_SCALE
            } else {
                row.normalTextSizeSp
            }
            row.view.setTextColor(Color.WHITE)
            row.view.alpha = when {
                selected -> 1f
                selectedIndex == null -> row.normalAlpha
                else -> TTS_INACTIVE_TEXT_ALPHA
            }
        }
        if (selectedIndex == null) {
            ttsScrollFollowJob?.cancel()
            ttsScrollFollowJob = null
            return
        }
        scheduleTtsParagraphCenter()
    }

    private fun scheduleTtsParagraphCenter() {
        ttsScrollFollowJob?.cancel()
        ttsScrollFollowJob = null
        val selectedIndex = pendingTtsHighlightIndex ?: return
        if (ttsScrollTouching) return
        val delayMillis = (ttsScrollFollowBlockedUntil - SystemClock.uptimeMillis())
            .coerceAtLeast(0L)
        if (delayMillis == 0L) {
            centerTtsParagraph(selectedIndex)
            return
        }
        ttsScrollFollowJob = lifecycleScope.launch {
            delay(delayMillis)
            ttsScrollFollowJob = null
            if (!ttsScrollTouching && pendingTtsHighlightIndex == selectedIndex) {
                centerTtsParagraph(selectedIndex)
            }
        }
    }

    private fun centerTtsParagraph(index: Int) {
        val selected = ttsParagraphRows.getOrNull(index)?.view ?: return
        selected.doOnLayout {
            binding.ttsContentScroll.post {
                if (ttsScrollTouching || pendingTtsHighlightIndex != index) return@post
                if (ttsParagraphRows.getOrNull(index)?.view !== selected) return@post
                val viewportHeight = binding.ttsContentScroll.height
                if (viewportHeight <= 0) return@post
                val maxScroll = (binding.ttsContent.height - viewportHeight).coerceAtLeast(0)
                val target = (selected.top - (viewportHeight - selected.height) / 2)
                    .coerceIn(0, maxScroll)
                if (kotlin.math.abs(binding.ttsContentScroll.scrollY - target) > 4) {
                    binding.ttsContentScroll.smoothScrollTo(0, target)
                }
            }
        }
    }

    private fun updateProgress(progress: ReadAloudProgress) = binding.run {
        displayedProgress = progress
        when (progress.kind) {
            ReadAloudProgress.Kind.TIME -> {
                playerProgress.max = progress.total
                tvDurTime.text = progress.position.toDurationTime()
                tvAllTime.text = progress.total.toDurationTime()
                lyricViewX.updateTime(progress.position.toLong(), false)
                ivRewind15?.setImageResource(R.drawable.ic_replay_15)
                ivForward15?.setImageResource(R.drawable.ic_forward_15)
            }
            ReadAloudProgress.Kind.PARAGRAPH -> {
                playerProgress.max = progress.total - 1
                tvDurTime.text = getString(R.string.read_aloud_paragraph_progress, progress.position + 1)
                tvAllTime.text = getString(R.string.read_aloud_paragraph_progress, progress.total)
                ivRewind15?.setImageResource(R.drawable.ic_skip_previous)
                ivForward15?.setImageResource(R.drawable.ic_skip_next)
                updateTtsParagraphHighlight(progress)
            }
        }
        playerProgress.isEnabled = playerProgress.max > 0
        if (!trackingProgress) {
            playerProgress.progress = progress.position
        }
        if (progress.chapterIndex != ReadBook.curTextChapter?.chapter?.index) {
            updateChapterUi()
        }
    }

    private fun updateProgressForSelectedEngine() = binding.run {
        val progress = ReadAloud.progressForSelectedEngine()
        if (progress != null) {
            updateProgress(progress)
            return@run
        }
        displayedProgress = null
        playerProgress.isEnabled = false
        playerProgress.max = 1
        playerProgress.progress = 0
        if (ReadAloud.selectedEngineType == ReadAloudEngineType.SOURCE_AUDIO) {
            tvDurTime.setText(R.string.read_aloud_time_pending)
            tvAllTime.setText(R.string.read_aloud_time_pending)
        } else {
            tvDurTime.setText(R.string.read_aloud_paragraph_pending)
            tvAllTime.setText(R.string.read_aloud_paragraph_pending)
            updateTtsParagraphHighlight(null)
        }
    }

    private fun progressLabel(kind: ReadAloudProgress.Kind?, position: Int): String {
        return when (kind) {
            ReadAloudProgress.Kind.TIME -> position.toDurationTime()
            ReadAloudProgress.Kind.PARAGRAPH ->
                getString(R.string.read_aloud_paragraph_progress, position + 1)
            null -> ""
        }
    }

    private fun updatePlayState() = binding.run {
        val running = BaseReadAloudService.isRun
        progressLoading.visible(running && BaseReadAloudService.loading)
        fabPlayStop.isEnabled = !running || !BaseReadAloudService.loading
        fabPlayStop.setImageResource(
            if (!running || BaseReadAloudService.pause) {
                R.drawable.ic_play_24dp
            } else {
                R.drawable.ic_pause_24dp
            }
        )
    }

    @SuppressLint("SetTextI18n")
    private fun updateSessionIndicators() = binding.run {
        val timer = BaseReadAloudService.timeMinute.coerceAtLeast(0)
        tvTimer.text = getString(R.string.timer_m, timer)
        tvTimer.visible(timer > 0)
    }

    private fun showAudioCacheRangeDialog(book: Book) {
        alert(titleResource = R.string.offline_cache) {
            val total = ReadBook.simulatedChapterSize.coerceAtLeast(book.totalChapterNum).coerceAtLeast(1)
            val alertBinding = DialogDownloadChoiceBinding.inflate(layoutInflater).apply {
                editStart.setText((ReadBook.durChapterIndex + 1).coerceAtLeast(1).toString())
                editEnd.setText(total.toString())
            }
            customView { alertBinding.root }
            okButton {
                NotificationPermission.ensure(
                    this@AudioPlayActivity,
                    onGranted = {
                        lifecycleScope.launch {
                            val start = alertBinding.editStart.text?.toString()?.toIntOrNull()
                                ?.coerceIn(1, total) ?: 1
                            val end = alertBinding.editEnd.text?.toString()?.toIntOrNull()
                                ?.coerceIn(start, total) ?: total
                            val chapters = withContext(IO) {
                                appDb.bookChapterDao.getChapterList(book.bookUrl, start - 1, end - 1)
                            }
                            if (chapters.isEmpty()) {
                                toastOnUi(R.string.chapter_list_empty)
                                return@launch
                            }
                            runCatching { cacheViewModel.cacheAudioChapters(book, chapters) }
                                .onSuccess { count ->
                                    if (count > 0) {
                                        toastOnUi(getString(R.string.cache_manage_audio_cache_started, count))
                                    } else {
                                        toastOnUi(R.string.cache_manage_batch_empty)
                                    }
                                }
                                .onFailure {
                                    toastOnUi(getString(R.string.cache_manage_cache_failed, it.localizedMessage))
                                }
                        }
                    },
                    onDenied = {
                        toastOnUi(R.string.notification_permission_required_for_download)
                    }
                )
            }
            cancelButton()
        }
    }

    override fun onCompatCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.audio_play, menu)
        val sourceAudio = ReadAloud.selectedEngineType == ReadAloudEngineType.SOURCE_AUDIO
        menu.findItem(R.id.menu_custom_btn).isVisible = false
        menu.findItem(R.id.menu_change_source).isVisible = false
        menu.findItem(R.id.menu_login).isVisible = false
        menu.findItem(R.id.menu_copy_audio_url).isVisible = sourceAudio
        menu.findItem(R.id.menu_play_mode).isVisible = false
        menu.findItem(R.id.menu_edit_source).isVisible = false
        menu.findItem(R.id.menu_wake_lock).isVisible = false
        menu.findItem(R.id.menu_skip_credits).isVisible = sourceAudio
        menu.findItem(R.id.menu_audio_cache).isVisible = sourceAudio
        menu.findItem(R.id.menu_audio_engine).isVisible = true
        return super.onCompatCreateOptionsMenu(menu)
    }

    override fun onCompatOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_copy_audio_url -> {
                val url = SourceAudioReadAloudService.currentMediaUrl
                if (url.isNullOrBlank()) {
                    toastOnUi("音频地址尚未就绪")
                } else {
                    sendToClip(url)
                }
            }
            R.id.menu_skip_credits -> ReadBook.book?.let {
                showDialogFragment(AudioSkipCredits.newInstance(it))
            }
            R.id.menu_audio_cache -> startActivity<CacheManageActivity> {
                putExtra(CacheManageActivity.EXTRA_MODE, CacheManageActivity.MODE_AUDIO)
            }
            R.id.menu_audio_engine -> showDialogFragment<SpeakEngineDialog>()
            R.id.menu_log -> showDialogFragment<AppLogDialog>()
        }
        return super.onCompatOptionsItemSelected(item)
    }

    override fun observeLiveBus() {
        observeEvent<Int>(EventBus.ALOUD_STATE) { state ->
            updatePlayState()
            if (state == Status.STOP) {
                if (preserveAfterEngineSwitch) {
                    preserveAfterEngineSwitch = false
                } else {
                    finish()
                }
            }
        }
        observeEvent<ReadAloudProgress>(EventBus.READ_ALOUD_PROGRESS) {
            if (ReadAloud.isProgressForSelectedEngine(it)) updateProgress(it)
        }
        observeEvent<ReadAloudEngineType>(EventBus.READ_ALOUD_ENGINE_CHANGED) {
            preserveAfterEngineSwitch = true
            updateEngineUi()
            updateProgressForSelectedEngine()
        }
        observeEvent<Int>(EventBus.READ_ALOUD_DS) { updateSessionIndicators() }
        observeEvent<Bundle>(EventBus.TTS_PROGRESS) { progress ->
            updateChapterUi()
            if (ReadAloud.selectedEngineType != ReadAloudEngineType.SOURCE_AUDIO) {
                updateTtsParagraphHighlightAt(
                    progress.getInt("chapterIndex", ReadBook.durChapterIndex),
                    progress.getInt("chapterPos", 0),
                )
            }
        }
        observeEvent<Boolean>(EventBus.MEDIA_BUTTON) { updatePlayState() }
    }

    private data class TtsParagraphRow(
        val view: TextView,
        val normalTextSizeSp: Float,
        val normalAlpha: Float,
    )

    private companion object {
        const val TTS_BODY_TEXT_SIZE_SP = 19f
        const val TTS_TITLE_TEXT_SIZE_SP = 22f
        const val TTS_BODY_TEXT_ALPHA = 0.9f
        const val TTS_INACTIVE_TEXT_ALPHA = 0.42f
        const val TTS_CURRENT_TEXT_SIZE_SCALE = 1.2f
    }
}
