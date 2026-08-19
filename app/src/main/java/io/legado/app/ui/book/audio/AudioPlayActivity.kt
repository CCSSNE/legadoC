package io.legado.app.ui.book.audio

import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.SystemClock
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.TypedValue
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.SeekBar
import androidx.activity.addCallback
import androidx.activity.viewModels
import androidx.core.view.doOnLayout
import androidx.lifecycle.lifecycleScope
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
import io.legado.app.help.book.ContentProcessor
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.permission.NotificationPermission
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
import io.legado.app.ui.book.audio.config.AudioPlayDisplaySettingDialog
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
import io.legado.app.utils.observeSharedPreferences
import io.legado.app.utils.sendToClip
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.startActivity
import io.legado.app.utils.StringUtils
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
    private var sourceAudioTextMapping: AudioTextMapping? = null
    private var boundListeningTextItems = emptyList<ListeningTextItem>()
    private val listeningTextRows = arrayListOf<ListeningTextRow>()
    private var listeningTextChapterIndex = -1
    private var listeningTextScrollTouching = false
    private var listeningTextScrollFollowBlockedUntil = 0L
    private var listeningTextScrollFollowJob: Job? = null
    private var pendingListeningTextHighlightIndex: Int? = null
    private var preserveAfterEngineSwitch = false
    private var coverRotationAnimator: ObjectAnimator? = null
    /**
     * 当前 UI 已显示的朗读章节 index。章节名链路以朗读服务的 chapterIndex 为准
     * （READ_ALOUD_PROGRESS 事件），书源音频 TextChapter 未加载也不受影响。
     */
    private var displayedChapterIndex = ReadBook.durChapterIndex
    /** [displayedChapterIndex] 对应的目录章节名缓存，切章时清空重取 */
    private var displayedChapterTitleCache: String? = null

    private val tocActivityResult = registerForActivityResult(TocActivityResult()) { result ->
        result ?: return@registerForActivityResult
        val chapterIndex = result[0] as Int
        val chapterPosition = result[1] as Int
        if (chapterIndex == ReadBook.durChapterIndex && chapterPosition != 0) return@registerForActivityResult
        ReadBook.skipReadAloudSyncOnce = true
        val opened = ReadBook.openChapter(chapterIndex, chapterPosition, false) {
            ReadBook.skipReadAloudSyncOnce = false
            ReadBook.readAloud()
            syncDisplayedChapter(ReadBook.durChapterIndex)
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
        applyDisplaySettings()
        loadCover(book)
        initProgressControl()
        initControls(book)
        binding.listeningTextContent.addOnLayoutChangeListener { _, left, _, right, _, oldLeft, _, oldRight, _ ->
            if (right - left != oldRight - oldLeft) {
                updateListeningTextIndentation()
            }
        }
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
        listeningTextScroll.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    listeningTextScrollTouching = true
                    listeningTextScrollFollowJob?.cancel()
                    listeningTextScrollFollowJob = null
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    listeningTextScrollTouching = false
                    listeningTextScrollFollowBlockedUntil =
                        SystemClock.uptimeMillis() + AppConfig.readAloudScrollFollowTimeout
                    scheduleListeningTextCenter()
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
        bindListeningText()
        invalidateOptionsMenu()
    }

    /**
     * 应用听书页显示设置：顶部标题显示模式、下方章节名是否显示。
     * 文字书 TTS 与书源音频共用同一套设置，不区分引擎。
     */
    private fun applyDisplaySettings() = binding.run {
        updateTopTitle()
        if (AppConfig.audioPlayShowChapterTitle) {
            tvSubTitle.visible()
        } else {
            // 必须 GONE 而非 INVISIBLE/清空文字：这一整行不再占高度，
            // play_content 底部约束自然落到进度区域上方，正文区域向下扩展。
            tvSubTitle.gone()
        }
    }

    /**
     * 以朗读章节 index 建立章节名显示身份（文字书 TTS 与书源音频共用，不分引擎）。
     * TextChapter 未加载/无正文时不依赖 curTextChapter，章节名直接查询目录 BookChapter。
     */
    private fun syncDisplayedChapter(chapterIndex: Int) {
        if (displayedChapterIndex != chapterIndex) {
            displayedChapterIndex = chapterIndex
            displayedChapterTitleCache = null
        }
    }

    /**
     * 当前朗读章节的标题：按当前朗读 chapterIndex 查询目录 BookChapter，
     * 并使用与正文阅读一致的章节标题替换规则生成显示标题。
     * 目录无此章节或显示标题为空时回退书名。按章节缓存，切章才重新查询。
     */
    private fun currentChapterTitle(): String {
        if (displayedChapterTitleCache == null) {
            val book = ReadBook.book
            val chapter = book?.let {
                appDb.bookChapterDao.getChapter(it.bookUrl, displayedChapterIndex)
            }
            val title = if (book != null && chapter != null) {
                val contentProcessor = ContentProcessor.get(book.name, book.origin)
                chapter.getDisplayTitle(
                    contentProcessor.getTitleReplaceRules(),
                    book.getUseReplaceRule(),
                    replaceBook = book.toReplaceBook(),
                ).takeIf { it.isNotBlank() }
            } else {
                null
            }
            displayedChapterTitleCache = title ?: book?.name.orEmpty()
        }
        return displayedChapterTitleCache ?: ""
    }

    /**
     * 按“顶部标题显示”设置刷新标题栏。章节模式下随切章实时更新，
     * 当前章节名为空时回退显示书名。
     */
    private fun updateTopTitle() {
        binding.titleBar.title = if (
            AppConfig.audioPlayTopTitleMode == AppConfig.AUDIO_PLAY_TOP_TITLE_CHAPTER
        ) {
            currentChapterTitle()
        } else {
            ReadBook.book?.name.orEmpty()
        }
    }

    private fun updateChapterUi() {
        binding.tvSubTitle.text = currentChapterTitle()
        updateTopTitle()
        bindListeningText()
    }

    private fun loadCover(book: Book) {
        val sourceOrigin = ReadBook.bookSource?.bookSourceUrl
        BookCover.load(this, book.getDisplayCover(), sourceOrigin = sourceOrigin) {
            BookCover.loadBlur(this, book.getDisplayCover(), sourceOrigin = sourceOrigin)
                .into(binding.ivBg)
        }.into(binding.ivCover)
    }

    private fun bindListeningText() {
        if (ReadAloud.selectedEngineType == ReadAloudEngineType.SOURCE_AUDIO) {
            bindSourceAudioText()
        } else {
            bindTtsText()
        }
    }

    private fun bindSourceAudioText() {
        val chapter = ReadBook.curTextChapter ?: run {
            clearListeningText()
            return
        }
        val mapping = AudioTextMapping.parse(chapter.chapter.getVariable("lyric"))
        if (!mapping.hasTimeMapping) {
            clearListeningText()
            return
        }
        sourceAudioTextMapping = mapping
        bindListeningTextItems(
            chapterIndex = chapter.chapter.index,
            items = mapping.cues.map { cue ->
                ListeningTextItem(
                    text = cue.text,
                    isTitle = false,
                    target = ListeningTextTarget.Time(cue.startMs),
                )
            },
        )
    }

    private fun bindTtsText() {
        val chapter = ReadBook.curTextChapter ?: run {
            clearListeningText()
            return
        }
        sourceAudioTextMapping = null
        bindListeningTextItems(
            chapterIndex = chapter.chapter.index,
            items = chapter.getParagraphs(false).map { paragraph ->
                ListeningTextItem(
                    text = paragraph.text,
                    isTitle = paragraph.isTitle,
                    target = ListeningTextTarget.Text(paragraph.chapterPosition),
                )
            },
        )
    }

    private fun bindListeningTextItems(
        chapterIndex: Int,
        items: List<ListeningTextItem>,
    ) = binding.run {
        val canReuseViews = listeningTextChapterIndex == chapterIndex &&
                boundListeningTextItems == items
        if (canReuseViews) {
            listeningTextScroll.visible(items.isNotEmpty())
            scheduleListeningTextIndentation()
            updateListeningTextHighlight(displayedProgress)
            return@run
        }
        resetListeningTextFollowState()
        listeningTextChapterIndex = chapterIndex
        boundListeningTextItems = items
        listeningTextContent.removeAllViews()
        listeningTextRows.clear()
        items.forEach { item ->
            val normalAlpha = if (item.isTitle) 1f else BODY_TEXT_ALPHA
            val normalizedText = StringUtils.trim(item.text)
            val view = TextView(this@AudioPlayActivity).apply {
                text = normalizedText
                setTextSize(TypedValue.COMPLEX_UNIT_PX, NORMAL_TEXT_SIZE_PX)
                setTextColor(Color.WHITE)
                alpha = normalAlpha
                gravity = Gravity.CENTER_HORIZONTAL
                setLineSpacing(0f, LISTENING_LINE_SPACING_MULTIPLIER)
                setPadding(0, 8.dpToPx(), 0, 8.dpToPx())
                isClickable = true
                setOnClickListener {
                    seekToListeningText(chapterIndex, item.target)
                }
            }
            listeningTextRows += ListeningTextRow(
                view = view,
                normalizedText = normalizedText,
                isTitle = item.isTitle,
                normalAlpha = normalAlpha,
            )
            listeningTextContent.addView(
                view,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
            )
        }
        listeningTextScroll.visible(items.isNotEmpty())
        scheduleListeningTextIndentation()
        updateListeningTextHighlight(displayedProgress)
    }

    private fun scheduleListeningTextIndentation() {
        binding.listeningTextContent.doOnLayout {
            updateListeningTextIndentation()
        }
    }

    private fun updateListeningTextIndentation() {
        val availableWidth = binding.listeningTextContent.width
        if (availableWidth <= 0) return
        listeningTextRows.forEach { row ->
            val shouldIndent = !row.isTitle &&
                    row.normalizedText.isNotEmpty() &&
                    listeningTextLineCount(row.normalizedText, row.view, availableWidth) >=
                    LISTENING_LONG_TEXT_MIN_LINES
            if (row.isIndented == shouldIndent) return@forEach
            row.isIndented = shouldIndent
            row.view.text = if (shouldIndent) {
                "$LISTENING_PARAGRAPH_INDENT${row.normalizedText}"
            } else {
                row.normalizedText
            }
        }
    }

    private fun listeningTextLineCount(
        text: String,
        view: TextView,
        availableWidth: Int,
    ): Int {
        // Measure the expanded text without indentation; apply the spaces only after this decision.
        val paint = TextPaint(view.paint)
        paint.textSize = CURRENT_TEXT_SIZE_PX
        return StaticLayout.Builder.obtain(text, 0, text.length, paint, availableWidth)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setIncludePad(view.includeFontPadding)
            .setLineSpacing(0f, LISTENING_LINE_SPACING_MULTIPLIER)
            .setBreakStrategy(view.breakStrategy)
            .setHyphenationFrequency(view.hyphenationFrequency)
            .build()
            .lineCount
    }

    private fun seekToListeningText(chapterIndex: Int, target: ListeningTextTarget) {
        when (target) {
            is ListeningTextTarget.Text -> ReadAloud.seekToTextPosition(
                this,
                chapterIndex,
                target.chapterPosition,
            )
            is ListeningTextTarget.Time -> ReadAloud.seekToProgress(
                this,
                chapterIndex,
                target.positionMs,
            )
        }
    }

    private fun updateListeningTextHighlight(progress: ReadAloudProgress?) {
        if (progress == null || progress.chapterIndex != listeningTextChapterIndex) {
            applyListeningTextHighlight(null)
            return
        }
        val selectedIndex = when (progress.kind) {
            ReadAloudProgress.Kind.TIME -> sourceAudioTextMapping?.paragraphAt(progress.position)
            ReadAloudProgress.Kind.PARAGRAPH -> ttsHighlightIndex(progress.position)
        }
        applyListeningTextHighlight(selectedIndex?.takeIf { it in listeningTextRows.indices })
    }

    private fun ttsHighlightIndex(serviceParagraphIndex: Int): Int? {
        if (ReadAloud.selectedEngineType == ReadAloudEngineType.SOURCE_AUDIO) return null
        val chapter = ReadBook.curTextChapter ?: return null
        val serviceParagraph = chapter.getParagraphs(
            getPrefBoolean(PreferKey.readAloudByPage, false)
        ).getOrNull(serviceParagraphIndex) ?: return null
        return chapter.getParagraphs(false).indexOfFirst {
            serviceParagraph.chapterPosition in it.chapterIndices
        }.takeIf { it >= 0 }
    }

    private fun updateListeningTextHighlightAt(chapterIndex: Int, chapterPosition: Int) {
        if (chapterIndex != listeningTextChapterIndex) {
            applyListeningTextHighlight(null)
            return
        }
        val chapter = ReadBook.curTextChapter ?: return
        val selectedIndex = chapter.getParagraphs(false).indexOfFirst {
            chapterPosition in it.chapterIndices
        }
        applyListeningTextHighlight(selectedIndex.takeIf { it in listeningTextRows.indices })
    }

    private fun applyListeningTextHighlight(selectedIndex: Int?) {
        pendingListeningTextHighlightIndex = selectedIndex
        listeningTextRows.forEachIndexed { index, row ->
            val selected = index == selectedIndex
            row.view.setTextSize(
                TypedValue.COMPLEX_UNIT_PX,
                if (selected) CURRENT_TEXT_SIZE_PX else NORMAL_TEXT_SIZE_PX,
            )
            row.view.setTextColor(Color.WHITE)
            row.view.alpha = when {
                selected -> 1f
                selectedIndex == null -> row.normalAlpha
                else -> INACTIVE_TEXT_ALPHA
            }
        }
        scheduleListeningTextIndentation()
        if (selectedIndex == null) {
            listeningTextScrollFollowJob?.cancel()
            listeningTextScrollFollowJob = null
            return
        }
        scheduleListeningTextCenter()
    }

    private fun scheduleListeningTextCenter() {
        listeningTextScrollFollowJob?.cancel()
        listeningTextScrollFollowJob = null
        val selectedIndex = pendingListeningTextHighlightIndex ?: return
        if (listeningTextScrollTouching) return
        val delayMillis = (listeningTextScrollFollowBlockedUntil - SystemClock.uptimeMillis())
            .coerceAtLeast(0L)
        if (delayMillis == 0L) {
            centerListeningText(selectedIndex)
            return
        }
        listeningTextScrollFollowJob = lifecycleScope.launch {
            delay(delayMillis)
            listeningTextScrollFollowJob = null
            if (!listeningTextScrollTouching &&
                pendingListeningTextHighlightIndex == selectedIndex
            ) {
                centerListeningText(selectedIndex)
            }
        }
    }

    private fun centerListeningText(index: Int) {
        val selected = listeningTextRows.getOrNull(index)?.view ?: return
        selected.doOnLayout {
            binding.listeningTextScroll.post {
                if (listeningTextScrollTouching ||
                    pendingListeningTextHighlightIndex != index
                ) return@post
                if (listeningTextRows.getOrNull(index)?.view !== selected) return@post
                val viewportHeight = binding.listeningTextScroll.height
                if (viewportHeight <= 0) return@post
                val maxScroll = (binding.listeningTextContent.height - viewportHeight)
                    .coerceAtLeast(0)
                val target = (selected.top - (viewportHeight - selected.height) / 2)
                    .coerceIn(0, maxScroll)
                if (kotlin.math.abs(binding.listeningTextScroll.scrollY - target) > 4) {
                    binding.listeningTextScroll.smoothScrollTo(0, target)
                }
            }
        }
    }

    private fun clearListeningText() = binding.run {
        sourceAudioTextMapping = null
        boundListeningTextItems = emptyList()
        listeningTextRows.clear()
        listeningTextChapterIndex = -1
        resetListeningTextFollowState()
        listeningTextContent.removeAllViews()
        listeningTextScroll.gone()
    }

    private fun resetListeningTextFollowState() {
        listeningTextScrollTouching = false
        listeningTextScrollFollowBlockedUntil = 0L
        pendingListeningTextHighlightIndex = null
        listeningTextScrollFollowJob?.cancel()
        listeningTextScrollFollowJob = null
    }

    private fun updateProgress(progress: ReadAloudProgress) = binding.run {
        displayedProgress = progress
        when (progress.kind) {
            ReadAloudProgress.Kind.TIME -> {
                playerProgress.max = progress.total
                tvDurTime.text = progress.position.toDurationTime()
                tvAllTime.text = progress.total.toDurationTime()
                updateListeningTextHighlight(progress)
                ivRewind15?.setImageResource(R.drawable.ic_replay_15)
                ivForward15?.setImageResource(R.drawable.ic_forward_15)
            }
            ReadAloudProgress.Kind.PARAGRAPH -> {
                playerProgress.max = progress.total - 1
                tvDurTime.text = getString(R.string.read_aloud_paragraph_progress, progress.position + 1)
                tvAllTime.text = getString(R.string.read_aloud_paragraph_progress, progress.total)
                ivRewind15?.setImageResource(R.drawable.ic_skip_previous)
                ivForward15?.setImageResource(R.drawable.ic_skip_next)
                updateListeningTextHighlight(progress)
            }
        }
        playerProgress.isEnabled = playerProgress.max > 0
        if (!trackingProgress) {
            playerProgress.progress = progress.position
        }
        if (progress.chapterIndex != displayedChapterIndex) {
            syncDisplayedChapter(progress.chapterIndex)
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
        }
        applyListeningTextHighlight(null)
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
        updateCoverRotation()
    }

    private fun updateCoverRotation(restart: Boolean = false) {
        if (AppConfig.readAloudCoverRotation &&
            BaseReadAloudService.isPlay() &&
            !BaseReadAloudService.loading
        ) {
            if (!restart && coverRotationAnimator?.isStarted == true) return
            coverRotationAnimator?.cancel()
            val startRotation = binding.ivCover.rotation
            coverRotationAnimator = ObjectAnimator.ofFloat(
                binding.ivCover,
                View.ROTATION,
                startRotation,
                startRotation + 360f
            ).apply {
                duration = AppConfig.readAloudCoverRotationDuration.toLong()
                repeatCount = ObjectAnimator.INFINITE
                interpolator = LinearInterpolator()
                start()
            }
        } else {
            coverRotationAnimator?.cancel()
            coverRotationAnimator = null
            binding.ivCover.rotation = 0f
        }
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
            R.id.menu_audio_play_display_setting -> showDialogFragment<AudioPlayDisplaySettingDialog>()
            R.id.menu_log -> showDialogFragment<AppLogDialog>()
        }
        return super.onCompatOptionsItemSelected(item)
    }

    override fun observeLiveBus() {
        observeSharedPreferences { _, key ->
            when (key) {
                PreferKey.audioPlayTopTitleMode,
                PreferKey.audioPlayShowChapterTitle -> applyDisplaySettings()
            }
        }
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
                updateListeningTextHighlightAt(
                    progress.getInt("chapterIndex", ReadBook.durChapterIndex),
                    progress.getInt("chapterPos", 0),
                )
            }
        }
        observeEvent<Boolean>(EventBus.MEDIA_BUTTON) { updatePlayState() }
        observeEvent<String>(PreferKey.readAloudCoverRotation) { updateCoverRotation() }
        observeEvent<String>(PreferKey.readAloudCoverRotationDuration) {
            updateCoverRotation(restart = true)
        }
    }

    override fun onDestroy() {
        coverRotationAnimator?.cancel()
        coverRotationAnimator = null
        binding.ivCover.rotation = 0f
        super.onDestroy()
    }

    private data class ListeningTextItem(
        val text: String,
        val isTitle: Boolean,
        val target: ListeningTextTarget,
    )

    private sealed interface ListeningTextTarget {
        data class Text(val chapterPosition: Int) : ListeningTextTarget
        data class Time(val positionMs: Int) : ListeningTextTarget
    }

    private data class ListeningTextRow(
        val view: TextView,
        val normalizedText: String,
        val isTitle: Boolean,
        val normalAlpha: Float,
        var isIndented: Boolean = false,
    )

    private companion object {
        const val LISTENING_PARAGRAPH_INDENT = "  "
        const val LISTENING_LINE_SPACING_MULTIPLIER = 1.12f
        const val LISTENING_LONG_TEXT_MIN_LINES = 3
        const val NORMAL_TEXT_SIZE_PX = 50f
        const val CURRENT_TEXT_SIZE_PX = 60f
        const val BODY_TEXT_ALPHA = 0.9f
        const val INACTIVE_TEXT_ALPHA = 0.42f
    }
}
