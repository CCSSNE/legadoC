package io.legado.app.ui.book.audio

import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import android.os.SystemClock
import android.text.Layout
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.ClickableSpan
import android.text.style.ReplacementSpan
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
import io.legado.app.help.book.BookImgClick
import io.legado.app.help.book.ContentProcessor
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.permission.NotificationPermission
import io.legado.app.model.BookCover
import io.legado.app.model.ImageProvider
import io.legado.app.model.ReadAloud
import io.legado.app.model.ReadAloudUiState
import io.legado.app.model.ReadBook
import io.legado.app.ui.book.read.page.entities.ParagraphSegment
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
import kotlin.math.roundToInt

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
    private var sourceAudioLayoutBinding: AudioTextMapping.LayoutBinding? = null
    private var sourceAudioFallbackMapping: AudioTextMapping? = null
    private var boundListeningTextItems = emptyList<ListeningTextItem>()
    private val listeningTextRows = arrayListOf<ListeningTextRow>()
    private var listeningTextChapterIndex = -1
    private var listeningTextScrollTouching = false
    private var listeningTextScrollFollowBlockedUntil = 0L
    private var listeningTextScrollFollowJob: Job? = null
    private var pendingListeningTextHighlightIndex: Int? = null
    /** 字号/放大倍率拖动高频触发时，合并到每帧最多执行一次就地重排 */
    private var fontSettingsApplyPending = false
    /** 缩进测量在 layout 回调排队中时不再重复挂载 */
    private var listeningTextIndentationPending = false
    /** 切章节/清空时递增，使上一代已排队的字体/缩进 UI 回调失效 */
    private var listeningTextCallbackGeneration = 0L
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
     * 当前朗读章节的显示标题：数据源是目录中对应 BookChapter（不依赖 TextChapter 是否已加载），
     * 显示处理与正文阅读共用同一入口 [BookChapter.getDisplayTitle]（替换规则、繁简转换完全同参），
     * 保证听书页与阅读页/目录显示的章节名一致。目录无此章节或处理后标题为空时回退书名。
     * 按章节缓存，切章才重新查询与处理。
     */
    private fun currentChapterTitle(): String {
        if (displayedChapterTitleCache == null) {
            val book = ReadBook.book
            val displayTitle = if (book != null) {
                appDb.bookChapterDao.getChapter(book.bookUrl, displayedChapterIndex)?.let { chapter ->
                    val contentProcessor = ContentProcessor.get(book.name, book.origin)
                    chapter.getDisplayTitle(
                        contentProcessor.getTitleReplaceRules(),
                        book.getUseReplaceRule(),
                        replaceBook = book.toReplaceBook(),
                    )
                }
            } else {
                null
            }
            displayedChapterTitleCache = displayTitle?.takeIf { it.isNotBlank() }
                ?: book?.name.orEmpty()
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

    /**
     * 书源音频正文渲染：优先与 TTS 引擎同一数据源——TextChapter 结构化段落 +
     * [AudioTextMapping.bindLayout] 显式布局绑定（与 SourceAudioReadAloudService 同一路径），
     * `<usehtml>` 结构块（含评论节点）按显示顺序原位渲染，两种引擎沉浸页完全一致。
     * 段落目标时间是绑定层给出的音频时间；结构段（标题/usehtml）取最近正文段时间。
     * 绑定失败（字幕与正文不一致的异常书源）回退纯字幕模式：保留 cues 文本与时间，
     * 高亮用 fallback mapping 的 paragraphAt()，至少不退化成字幕全空。
     */
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
        val layoutBinding = try {
            chapter.bindAudioTextMapping(mapping)
        } catch (e: Exception) {
            // 字幕与正文段落不一致属数据问题；服务端会继续播放音频，仅失去正文同步/高亮。
            // 沉浸页回退纯字幕模式并记录原因，直接暴露，不静默兜底也不清空字幕。
            AppLog.put("源音频正文映射失败，回退纯字幕模式：${e.message}", e)
            sourceAudioLayoutBinding = null
            sourceAudioFallbackMapping = mapping
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
            return
        }
        sourceAudioLayoutBinding = layoutBinding
        sourceAudioFallbackMapping = null
        val paragraphs = chapter.getParagraphs(false)
        bindListeningTextItems(
            chapterIndex = chapter.chapter.index,
            items = paragraphs.mapIndexed { layoutIndex, paragraph ->
                val timeMs = layoutBinding.timeForLayoutParagraph(layoutIndex) ?: run {
                    // 结构段位于最后一段正文之后时回退到其前最近的正文段时间
                    var previous = layoutIndex - 1
                    var result: Int? = null
                    while (previous >= 0 && result == null) {
                        result = layoutBinding.timeForLayoutParagraph(previous)
                        previous--
                    }
                    result ?: 0
                }
                ListeningTextItem(
                    text = paragraph.text,
                    isTitle = paragraph.isTitle,
                    target = ListeningTextTarget.Time(timeMs),
                    segments = paragraph.segments,
                )
            },
        )
    }

    private fun bindTtsText() {
        val chapter = ReadBook.curTextChapter ?: run {
            clearListeningText()
            return
        }
        sourceAudioLayoutBinding = null
        sourceAudioFallbackMapping = null
        bindListeningTextItems(
            chapterIndex = chapter.chapter.index,
            items = chapter.getParagraphs(false).map { paragraph ->
                ListeningTextItem(
                    text = paragraph.text,
                    isTitle = paragraph.isTitle,
                    target = ListeningTextTarget.Text(paragraph.chapterPosition),
                    segments = paragraph.segments,
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
            // 只给真正含评论的段落启用评论交互（纯文本段落只有 Text 节点）
            val hasReview = item.segments.any { it is ParagraphSegment.Review }
            val view = TextView(this@AudioPlayActivity).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_PX, normalTextSizePx())
                setTextColor(Color.WHITE)
                alpha = normalAlpha
                gravity = Gravity.CENTER_HORIZONTAL
                setLineSpacing(0f, LISTENING_LINE_SPACING_MULTIPLIER)
                setPadding(0, 8.dpToPx(), 0, 8.dpToPx())
                isClickable = true
                setOnClickListener {
                    seekToListeningText(chapterIndex, item.target)
                }
                // 统一触摸分流：命中 Review 气泡的 ACTION_DOWN 起即持有整个手势
                // （TextView 不介入，不产生按压/tap 状态），UP/CANCEL 成对结束；
                // UP 仍落在同一气泡内才触发评论，否则整个手势被消费、不落行点击。
                // 未命中气泡的 DOWN 正常回落到 TextView 行点击 seek。
                if (hasReview) {
                    var holdingReview: ListeningReviewClickableSpan? = null
                    setOnTouchListener { _, event ->
                        when (event.actionMasked) {
                            MotionEvent.ACTION_DOWN -> {
                                val span = reviewingSpanAt(this, event)
                                if (span != null) {
                                    holdingReview = span
                                    true
                                } else {
                                    false
                                }
                            }
                            MotionEvent.ACTION_UP -> {
                                val span = holdingReview
                                holdingReview = null
                                if (span != null) {
                                    if (reviewingSpanAt(this, event) === span) {
                                        onListeningReviewClick(span.click, span.src)
                                    }
                                    true
                                } else {
                                    false
                                }
                            }
                            MotionEvent.ACTION_CANCEL -> {
                                val wasHolding = holdingReview != null
                                holdingReview = null
                                wasHolding
                            }
                            else -> holdingReview != null
                        }
                    }
                }
            }
            val row = ListeningTextRow(
                view = view,
                normalizedText = normalizedText,
                isTitle = item.isTitle,
                normalAlpha = normalAlpha,
                spannable = if (hasReview) {
                    buildListeningSpannable(item, view)
                } else {
                    null
                },
            )
            view.text = row.displayText()
            listeningTextRows += row
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
        if (listeningTextIndentationPending) return
        listeningTextIndentationPending = true
        val callbackGeneration = listeningTextCallbackGeneration
        binding.listeningTextContent.doOnLayout {
            if (callbackGeneration != listeningTextCallbackGeneration) return@doOnLayout
            listeningTextIndentationPending = false
            updateListeningTextIndentation()
        }
    }

    private fun updateListeningTextIndentation() {
        val availableWidth = binding.listeningTextContent.width
        if (availableWidth <= 0) return
        val currentPx = AppConfig.audioPlayTextSize * AppConfig.audioPlayTextZoom / 100f
        listeningTextRows.forEach { row ->
            val shouldIndent = !row.isTitle &&
                    row.normalizedText.isNotEmpty() &&
                    listeningTextLineCount(
                        row.normalizedText, row.view, availableWidth, currentPx
                    ) >= LISTENING_LONG_TEXT_MIN_LINES
            if (row.isIndented == shouldIndent) return@forEach
            row.isIndented = shouldIndent
            row.view.text = row.displayText()
        }
    }

    private fun listeningTextLineCount(
        text: String,
        view: TextView,
        availableWidth: Int,
        currentTextSizePx: Float,
    ): Int {
        // Measure the expanded text without indentation; apply the spaces only after this decision.
        val paint = TextPaint(view.paint)
        paint.textSize = currentTextSizePx
        val builder = StaticLayout.Builder.obtain(text, 0, text.length, paint, availableWidth)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setIncludePad(view.includeFontPadding)
            .setLineSpacing(0f, LISTENING_LINE_SPACING_MULTIPLIER)
            .setBreakStrategy(view.breakStrategy)
            .setHyphenationFrequency(view.hyphenationFrequency)
        val lineCount = builder.build().lineCount
        return lineCount
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
            ReadAloudProgress.Kind.TIME ->
                // 绑定成功：段落序号即行序；回退纯字幕：cue 序号即行序
                sourceAudioLayoutBinding?.layoutParagraphAt(progress.position)
                    ?: sourceAudioFallbackMapping?.paragraphAt(progress.position)
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
        val normalPx = AppConfig.audioPlayTextSize.toFloat()
        val currentPx = normalPx * AppConfig.audioPlayTextZoom / 100f
        listeningTextRows.forEachIndexed { index, row ->
            val selected = index == selectedIndex
            row.view.setTextSize(
                TypedValue.COMPLEX_UNIT_PX,
                if (selected) currentPx else normalPx,
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

    /** 字号/放大倍率变化后的就地重排：合并到每帧最多执行一次（拖动高频触发时不重活） */
    private fun applyListeningFontSettings() {
        if (listeningTextRows.isEmpty()) return
        if (fontSettingsApplyPending) return
        fontSettingsApplyPending = true
        val callbackGeneration = listeningTextCallbackGeneration
        binding.listeningTextContent.postOnAnimation {
            if (callbackGeneration != listeningTextCallbackGeneration) return@postOnAnimation
            fontSettingsApplyPending = false
            if (isDestroyed || isFinishing) return@postOnAnimation
            updateListeningTextHighlight(displayedProgress)
        }
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
        sourceAudioLayoutBinding = null
        sourceAudioFallbackMapping = null
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
        // 旧 callback 即使已入队也不得作用于下一章节，且不得清掉新一代 pending 状态。
        listeningTextCallbackGeneration++
        fontSettingsApplyPending = false
        listeningTextIndentationPending = false
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
                PreferKey.audioPlayTextSize,
                PreferKey.audioPlayTextZoom -> applyListeningFontSettings()
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

    /**
     * 把结构化段落节点拼成可显示的 Spannable：
     * 评论节点在原占位字符位置插入“原图 span + 点击 span”，
     * 字符序列与 [ListeningTextItem.text] 完全一致，首尾裁剪边界可直接复用。
     */
    private fun buildListeningSpannable(item: ListeningTextItem, view: TextView): CharSequence {
        val text = item.text
        val builder = SpannableStringBuilder(text)
        var offset = 0
        item.segments.forEach { segment ->
            when (segment) {
                is ParagraphSegment.Text -> offset += segment.text.length
                is ParagraphSegment.Review -> {
                    if (offset < text.length) {
                        builder.setSpan(
                            ListeningReviewImageSpan(segment.src) { view.invalidate() },
                            offset,
                            offset + 1,
                            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
                        )
                        builder.setSpan(
                            ListeningReviewClickableSpan(
                                segment.click,
                                segment.src,
                                ::onListeningReviewClick,
                            ),
                            offset,
                            offset + 1,
                            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
                        )
                    }
                    offset += 1
                }
            }
        }
        val (start, end) = listeningTrimBounds(text)
        return builder.subSequence(start, end)
    }

    /** 与 [StringUtils.trim] 完全一致的首尾空字符边界（含空格与全角空格），供 Spannable 裁剪 */
    private fun listeningTrimBounds(s: String): Pair<Int, Int> {
        if (s.isEmpty()) return 0 to 0
        var start = 0
        val len = s.length
        var end = len - 1
        while (start < end && (s[start].code <= 0x20 || s[start] == '　')) {
            ++start
        }
        while (start < end && (s[end].code <= 0x20 || s[end] == '　')) {
            --end
        }
        ++end
        return start to end
    }

    /**
     * 评论气泡点击：复用阅读页同一套 src + click JS 执行逻辑（弹评论小页）。
     * 只有点击气泡触发评论，正文点击仍走行点击跳转朗读位置。
     */
    private fun onListeningReviewClick(click: String?, src: String) {
        if (click.isNullOrBlank()) {
            BookImgClick.oldClickImg(this, lifecycleScope, src)
        } else {
            BookImgClick.clickImg(this, lifecycleScope, click, src)
        }
    }

    /**
     * 命中测试：与 [ListeningReviewImageSpan] 绘制共用同一套图片几何
     * （宽度＝字符单元宽，高度按位图比例在行盒内垂直居中），
     * 只在真实可见气泡矩形内判定，行盒空白区不再被误判为气泡。
     */
    private fun reviewingSpanAt(view: TextView, event: MotionEvent): ListeningReviewClickableSpan? {
        val layout = view.layout ?: return null
        val text = view.text as? Spanned ?: return null
        val px = event.x - view.totalPaddingLeft + view.scrollX
        val py = event.y - view.totalPaddingTop + view.scrollY
        val slop = 4.dpToPx()
        return text.getSpans(0, text.length, ListeningReviewClickableSpan::class.java)
            .firstOrNull { clickSpan ->
                val start = text.getSpanStart(clickSpan)
                if (start < 0 || start >= layout.text.length) return@firstOrNull false
                val imageSpan = text
                    .getSpans(start, start + 1, ListeningReviewImageSpan::class.java)
                    .firstOrNull() ?: return@firstOrNull false
                val line = layout.getLineForOffset(start)
                val lineHeight = (layout.getLineBottom(line) - layout.getLineTop(line)).coerceAtLeast(1)
                val width = (view.paint.textSize * REVIEW_IMAGE_WIDTH_SCALE).roundToInt().coerceAtLeast(1)
                val bitmap = imageSpan.resolveBitmap(width, lineHeight) ?: return@firstOrNull false
                val rect = imageSpan.imageRect(
                    width = width,
                    height = lineHeight,
                    bitmapWidth = bitmap.width,
                    bitmapHeight = bitmap.height,
                    x = layout.getPrimaryHorizontal(start),
                    top = layout.getLineTop(line),
                    bottom = layout.getLineBottom(line),
                )
                px >= rect.left - slop && px <= rect.right + slop &&
                    py >= rect.top - slop && py <= rect.bottom + slop
            }
    }

    private data class ListeningTextItem(
        val text: String,
        val isTitle: Boolean,
        val target: ListeningTextTarget,
        val segments: List<ParagraphSegment> = emptyList(),
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
        val spannable: CharSequence? = null,
        var isIndented: Boolean = false,
    ) {
        /** 当前展示文本：评论行带原图 span；缩进时仅在前缀位置追加，span 偏移不变 */
        fun displayText(): CharSequence {
            val base = spannable ?: normalizedText
            return if (isIndented) {
                SpannableStringBuilder(LISTENING_PARAGRAPH_INDENT).append(base)
            } else {
                base
            }
        }
    }

    /**
     * 评论按钮原图 span：复用书源 src 图片（保留原样式与评论数量），
     * 与阅读页 ImageColumn 一致——宽度固定为字符宽，高度按原图比例垂直居中。
     * 绘制（draw）与命中测试（[reviewingSpanAt]）共用
     * [resolveBitmap] + [imageRect] 同一套取图与几何计算。
     */
    private class ListeningReviewImageSpan(
        private val src: String,
        private val onImageChanged: () -> Unit,
    ) : ReplacementSpan() {

        override fun getSize(
            paint: Paint,
            text: CharSequence?,
            start: Int,
            end: Int,
            fm: Paint.FontMetricsInt?,
        ): Int {
            return (paint.textSize * REVIEW_IMAGE_WIDTH_SCALE).toInt().coerceAtLeast(1)
        }

        override fun draw(
            canvas: Canvas,
            text: CharSequence?,
            start: Int,
            end: Int,
            x: Float,
            top: Int,
            y: Int,
            bottom: Int,
            paint: Paint,
        ) {
            val book = ReadBook.book ?: return
            val height = (bottom - top).coerceAtLeast(1)
            val width = (paint.textSize * REVIEW_IMAGE_WIDTH_SCALE).roundToInt().coerceAtLeast(1)
            val bitmap = resolveBitmap(width, height) ?: return
            val rect = imageRect(width, height, bitmap.width, bitmap.height, x, top, bottom)
            canvas.drawBitmap(bitmap, null, rect, paint)
        }

        /**
         * 与绘制共用：按与 draw() 完全相同的规则取图。
         * 未缓存时先异步缓存并回占位图；绘制与命中测试都用这张图计算几何，保证一致。
         */
        fun resolveBitmap(width: Int, height: Int): Bitmap? {
            val book = ReadBook.book ?: return null
            return if (!ImageProvider.isImageExist(book, src)) {
                ImageProvider.cacheImageAsync(
                    book = book,
                    src = src,
                    bookSource = ReadBook.bookSource,
                    width = width,
                    height = height,
                ) { onImageChanged() }
                ImageProvider.loadingBitmap
            } else {
                ImageProvider.getImage(book, src, width, height)
            }
        }

        /**
         * 图片像素矩形——命中测试与绘制共用同一套几何：
         * 宽度固定为字符单元宽，高度按位图比例计算，
         * 在行盒 [top, bottom] 内垂直居中（div 为负时允许高于行盒）。
         */
        fun imageRect(
            width: Int,
            height: Int,
            bitmapWidth: Int,
            bitmapHeight: Int,
            x: Float,
            top: Int,
            bottom: Int,
        ): RectF {
            val imgHeight = width.toFloat() / bitmapWidth.coerceAtLeast(1) * bitmapHeight
            val div = (height - imgHeight) / 2f
            return RectF(x, top + div, x + width, bottom - div)
        }
    }

    /**
     * 评论气泡命中标记与点击载荷：配合统一触摸分流，
     * 命中其可见矩形时消费触摸并复用公共评论点击入口；
     * 正文点击不经过它，走行点击跳转朗读位置。
     */
    private class ListeningReviewClickableSpan(
        val click: String?,
        val src: String,
        private val onReviewClick: (click: String?, src: String) -> Unit,
    ) : ClickableSpan() {
        override fun onClick(widget: View) {
            onReviewClick(click, src)
        }

        override fun updateDrawState(ds: TextPaint) {
            ds.isUnderlineText = false
        }
    }

    private companion object {
        const val LISTENING_PARAGRAPH_INDENT = "  "
        const val LISTENING_LINE_SPACING_MULTIPLIER = 1.12f
        const val LISTENING_LONG_TEXT_MIN_LINES = 3
        fun normalTextSizePx(): Float = AppConfig.audioPlayTextSize.toFloat()
        const val BODY_TEXT_ALPHA = 0.9f
        const val INACTIVE_TEXT_ALPHA = 0.42f
        /** 评论原图按字符宽比例显示，与阅读页 reviewCharWidth 的缩放比例一致 */
        const val REVIEW_IMAGE_WIDTH_SCALE = 1.5556f
    }
}
