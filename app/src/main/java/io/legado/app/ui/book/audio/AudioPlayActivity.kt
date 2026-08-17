package io.legado.app.ui.book.audio

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.widget.SeekBar
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import com.dirror.lyricviewx.OnPlayClickListener
import io.legado.app.R
import io.legado.app.base.BaseActivity
import io.legado.app.constant.EventBus
import io.legado.app.constant.Status
import io.legado.app.constant.Theme
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.databinding.ActivityAudioPlayBinding
import io.legado.app.databinding.DialogDownloadChoiceBinding
import io.legado.app.help.book.AudioTextMapping
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.theme.ThemeStore.Companion.accentColor
import io.legado.app.model.BookCover
import io.legado.app.model.ReadAloud
import io.legado.app.model.ReadBook
import io.legado.app.service.BaseReadAloudService
import io.legado.app.service.ReadAloudEngineType
import io.legado.app.service.ReadAloudProgress
import io.legado.app.service.SourceAudioReadAloudService
import io.legado.app.ui.about.AppLogDialog
import io.legado.app.ui.book.audio.SliderPopup.Companion.SPEED
import io.legado.app.ui.book.audio.SliderPopup.Companion.TIMER
import io.legado.app.ui.book.audio.config.AudioSkipCredits
import io.legado.app.ui.book.cache.CacheManageViewModel
import io.legado.app.ui.book.toc.TocActivityResult
import io.legado.app.ui.widget.seekbar.SeekBarChangeListener
import io.legado.app.utils.applyNavigationBarPadding
import io.legado.app.utils.dpToPx
import io.legado.app.utils.gone
import io.legado.app.utils.observeEvent
import io.legado.app.utils.sendToClip
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.toDurationTime
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import io.legado.app.utils.visible
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import splitties.views.onLongClick

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
        if (!BaseReadAloudService.isRun || book == null) {
            toastOnUi("当前没有可控制的听书会话")
            finish()
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
        BaseReadAloudService.readAloudProgress?.let(::updateProgress)
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
            if (BaseReadAloudService.pause) {
                ReadAloud.resume(this@AudioPlayActivity)
            } else {
                ReadAloud.pause(this@AudioPlayActivity)
            }
        }
        fabPlayStop.onLongClick {
            ReadAloud.stop(this@AudioPlayActivity)
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
        ivPlayMode?.gone()
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
        val sourceAudio = ReadAloud.engineType == ReadAloudEngineType.SOURCE_AUDIO
        ivCache?.visible(sourceAudio)
        if (sourceAudio) {
            loadLyric(ReadBook.curTextChapter?.chapter?.getVariable("lyric"))
        } else {
            loadedLyric = null
            lyricViewX.gone()
        }
        invalidateOptionsMenu()
    }

    private fun updateChapterUi() {
        binding.tvSubTitle.text = ReadBook.curTextChapter?.title.orEmpty()
        if (ReadAloud.engineType == ReadAloudEngineType.SOURCE_AUDIO) {
            loadLyric(ReadBook.curTextChapter?.chapter?.getVariable("lyric"))
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

    private fun progressLabel(kind: ReadAloudProgress.Kind?, position: Int): String {
        return when (kind) {
            ReadAloudProgress.Kind.TIME -> position.toDurationTime()
            ReadAloudProgress.Kind.PARAGRAPH ->
                getString(R.string.read_aloud_paragraph_progress, position + 1)
            null -> ""
        }
    }

    private fun updatePlayState() = binding.run {
        progressLoading.visible(BaseReadAloudService.loading)
        fabPlayStop.isEnabled = !BaseReadAloudService.loading
        fabPlayStop.setImageResource(
            if (BaseReadAloudService.pause) R.drawable.ic_play_24dp else R.drawable.ic_pause_24dp
        )
    }

    @SuppressLint("SetTextI18n")
    private fun updateSessionIndicators() = binding.run {
        val timer = BaseReadAloudService.timeMinute.coerceAtLeast(0)
        tvTimer.text = getString(R.string.timer_m, timer)
        tvTimer.visible(timer > 0)
        val speed = if (ReadAloud.engineType == ReadAloudEngineType.SOURCE_AUDIO) {
            ReadBook.book?.getPlaySpeed() ?: 1f
        } else {
            (io.legado.app.help.config.AppConfig.ttsSpeechRate + 5) / 10f
        }
        tvSpeed.text = "%.1fX".format(speed)
        tvSpeed.visible()
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
            }
            cancelButton()
        }
    }

    override fun onCompatCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.audio_play, menu)
        val sourceAudio = ReadAloud.engineType == ReadAloudEngineType.SOURCE_AUDIO
        menu.findItem(R.id.menu_custom_btn).isVisible = false
        menu.findItem(R.id.menu_change_source).isVisible = false
        menu.findItem(R.id.menu_login).isVisible = false
        menu.findItem(R.id.menu_copy_audio_url).isVisible = sourceAudio
        menu.findItem(R.id.menu_play_mode).isVisible = false
        menu.findItem(R.id.menu_edit_source).isVisible = false
        menu.findItem(R.id.menu_wake_lock).isVisible = false
        menu.findItem(R.id.menu_skip_credits).isVisible = sourceAudio
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
            R.id.menu_log -> showDialogFragment<AppLogDialog>()
        }
        return super.onCompatOptionsItemSelected(item)
    }

    override fun observeLiveBus() {
        observeEvent<Int>(EventBus.ALOUD_STATE) { state ->
            updatePlayState()
            if (state == Status.STOP) finish()
        }
        observeEvent<ReadAloudProgress>(EventBus.READ_ALOUD_PROGRESS, ::updateProgress)
        observeEvent<Int>(EventBus.READ_ALOUD_DS) { updateSessionIndicators() }
        observeEvent<Bundle>(EventBus.TTS_PROGRESS) { updateChapterUi() }
        observeEvent<Boolean>(EventBus.MEDIA_BUTTON) { updatePlayState() }
    }
}
