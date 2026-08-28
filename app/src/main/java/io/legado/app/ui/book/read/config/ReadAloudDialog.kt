package io.legado.app.ui.book.read.config

import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.content.DialogInterface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import android.widget.SeekBar
import androidx.core.view.doOnLayout
import io.legado.app.R
import io.legado.app.constant.EventBus
import io.legado.app.data.appDb
import io.legado.app.lib.dialogs.SelectItem
import io.legado.app.databinding.DialogReadAloudBinding
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.dialogs.selector
import io.legado.app.lib.theme.applyUiBodyTypefaceDeep
import io.legado.app.lib.theme.bottomBackground
import io.legado.app.lib.theme.getPrimaryTextColor
import io.legado.app.lib.theme.uiTypeface
import io.legado.app.model.ReadAloud
import io.legado.app.model.ReadBook
import io.legado.app.model.ReadAloudUiState
import io.legado.app.service.BaseReadAloudService
import io.legado.app.service.ReadAloudDialogFloatingPresentation
import io.legado.app.service.ReadAloudEngineType
import io.legado.app.service.ReadAloudFloatingHost
import io.legado.app.service.ReadAloudProgress
import io.legado.app.ui.book.cache.TtsCacheChapterDialog
import io.legado.app.ui.book.read.ReadBookActivity
import io.legado.app.ui.widget.seekbar.SeekBarChangeListener
import io.legado.app.utils.*
import io.legado.app.utils.viewbindingdelegate.viewBinding


class ReadAloudDialog : BaseReaderSheetDialogFragment(R.layout.dialog_read_aloud),
    SpeakEngineDialog.CallBack {
    private val callBack: CallBack? get() = activity as? CallBack
    private val binding by viewBinding(DialogReadAloudBinding::bind)
    private var loadingAnimator: ObjectAnimator? = null
    private var showMainMenuOnDismiss = false
    private var ownsDialogVisibility = false
    private var dialogPresentationReady = false
    private var displayedReadProgress: ReadAloudProgress? = null
    private var trackingReadProgress = false
    private val menuLayoutChangeListener =
        View.OnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
            publishMenuTopOnScreen(view)
        }
    private val isSourceAudioSelected
        get() = ReadAloud.selectedEngineType == ReadAloudEngineType.SOURCE_AUDIO

    override fun onStart() {
        super.onStart()
        dialog?.setCanceledOnTouchOutside(false)
        dialog?.window?.run {
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)
            setBackgroundDrawableResource(android.R.color.transparent)
            decorView.setPadding(0, 0, 0, 0)
            val attr = attributes
            attr.dimAmount = 0.0f
            attr.gravity = Gravity.BOTTOM
            attributes = attr
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        binding.rootView.removeOnLayoutChangeListener(menuLayoutChangeListener)
        binding.rootView.addOnLayoutChangeListener(menuLayoutChangeListener)
        binding.rootView.doOnLayout(::publishMenuTopOnScreen)
        publishDialogVisibilityAfterFirstDraw()
    }

    private fun resolveFloatingHost(): ReadAloudFloatingHost {
        val window = dialog?.window ?: error("ReadAloudDialog window is unavailable")
        val token = window.decorView.windowToken
            ?: error("ReadAloudDialog window token is unavailable")
        return ReadAloudFloatingHost(window.windowManager, token)
    }

    private fun publishMenuTopOnScreen(view: View) {
        check(view.height > 0) { "Read-aloud menu has no measurable height" }
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        (activity as? ReadBookActivity)?.postReadAloudFloatingAvoidanceFromScreenBounds(
            EventBus.FLOATING_AVOID_SOURCE_READ_ALOUD_DIALOG,
            location[1],
            location[1] + view.height,
        )
    }

    private fun publishDialogVisibilityAfterFirstDraw() {
        val root = binding.rootView
        root.doAfterFirstDraw {
            if (!ownsDialogVisibility || dialogPresentationReady) return@doAfterFirstDraw
            updateDialogVisibility(true)
        }
    }

    private fun updateDialogVisibility(visible: Boolean) {
        if (visible) {
            if (!ownsDialogVisibility || dialogPresentationReady || dialog?.isShowing != true) {
                return
            }
        } else if (!dialogPresentationReady) {
            return
        }
        val floatingPresentation = ReadAloudDialogFloatingPresentation(
            if (visible) resolveFloatingHost() else null
        )
        dialogPresentationReady = visible
        ReadAloudUiState.setReadAloudDialogVisible(visible)
        postEvent(EventBus.READ_ALOUD_DIALOG_VISIBILITY, visible)
        postEvent(EventBus.READ_ALOUD_DIALOG_FLOATING_PRESENTATION, floatingPresentation)
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        binding.rootView.removeOnLayoutChangeListener(menuLayoutChangeListener)
        if (ownsDialogVisibility) {
            ownsDialogVisibility = false
            updateDialogVisibility(false)
        }
        stopLoadingAnimation()
        (activity as ReadBookActivity).bottomDialog--
        (activity as? ReadBookActivity)?.clearReadAloudFloatingAvoidance(
            EventBus.FLOATING_AVOID_SOURCE_READ_ALOUD_DIALOG
        )
        if (showMainMenuOnDismiss) {
            showMainMenuOnDismiss = false
            callBack?.showMenuBar()
        }
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        val bottomDialog = (activity as ReadBookActivity).bottomDialog++
        if (bottomDialog > 0) {
            dismiss()
            return
        }
        ownsDialogVisibility = true
        binding.root.applyUiBodyTypefaceDeep(requireContext().uiTypeface())
        val bg = requireContext().bottomBackground
        val isLight = ColorUtils.isColorLight(bg)
        val textColor = requireContext().getPrimaryTextColor(isLight)
        val palette = ReaderSheetStyle.resolve(requireContext())
        binding.run {
            panelTransport.background = null
            panelTimer.background = null
            panelTts.background = null
            panelActions.background = null
            tvPre.setTextColor(textColor)
            tvNext.setTextColor(textColor)
            ivPlayPrev.setColorFilter(textColor)
            ivPlayPause.setColorFilter(textColor)
            ivOpenAudioPlay.setColorFilter(textColor)
            ivPlayNext.setColorFilter(textColor)
            ivStop.setColorFilter(textColor)
            ivTimer.setColorFilter(textColor)
            tvTimer.setTextColor(textColor)
            ivTtsSpeechReduce.setColorFilter(textColor)
            tvTtsSpeed.setTextColor(palette.secondaryTextColor)
            tvTtsSpeedValue.setTextColor(textColor)
            ivTtsSpeechAdd.setColorFilter(textColor)
            ivCatalog.setColorFilter(textColor)
            tvCatalog.setTextColor(textColor)
            ivTtsCache.setColorFilter(textColor)
            tvTtsCache.setTextColor(textColor)
            ivMainMenu.setColorFilter(textColor)
            tvMainMenu.setTextColor(textColor)
            ivToBackstage.setColorFilter(textColor)
            tvToBackstage.setTextColor(textColor)
            ivSetting.setColorFilter(textColor)
            tvSetting.setTextColor(textColor)
            cbTtsFollowSys.setTextColor(textColor)
        }
        initData()
        initEvent()
    }

    private fun initData() = binding.run {
        upPlayState()
        upSpeakEngineSummary()
        upTimerText(BaseReadAloudService.timeMinute)
        upSeekTimer()
        upReadProgress()
    }

    private fun initEvent() = binding.run {
        ivCatalog.gone()
        llMainMenu.visible(AppConfig.readAloudHideFloatingWindow && BaseReadAloudService.isRun)
        // TTS 缓存入口：仅系统 TTS 引擎；TTS-Wav 模式关闭时置灰不可点（合成产物前置）
        llTtsCache.visible(ReadAloud.selectedEngineType == ReadAloudEngineType.SYSTEM_TTS)
        llTtsCache.isEnabled = AppConfig.ttsWavMode
        llTtsCache.alpha = if (AppConfig.ttsWavMode) 1f else 0.45f
        llCatalog.setOnClickListener {
            SpeakEngineDialog().show(childFragmentManager, "speakEngineDialog")
        }
        llTtsCache.setOnClickListener {
            ReadBook.book?.let { book ->
                TtsCacheChapterDialog.newInstance(book)
                    .show(childFragmentManager, "ttsCacheChapterDialog")
            }
        }
        llMainMenu.setOnClickListener {
            showMainMenuOnDismiss = true
            dismissAllowingStateLoss()
        }
        llSetting.setOnClickListener {
            ReadAloudConfigDialog().show(childFragmentManager, "readAloudConfigDialog")
        }
        tvPre.setOnClickListener {
            if (BaseReadAloudService.isRun) {
                ReadAloud.prevChapter(requireContext())
            } else {
                ReadBook.moveToPrevChapter(upContent = true, toLast = false)
            }
        }
        tvNext.setOnClickListener {
            if (BaseReadAloudService.isRun) {
                ReadAloud.nextChapter(requireContext())
            } else {
                ReadBook.moveToNextChapter(true)
            }
        }
        ivStop.setOnClickListener {
            ReadAloud.stop(requireContext())
            dismissAllowingStateLoss()
        }
        ivPlayPause.setOnClickListener { callBack?.onClickReadAloud() }
        ivOpenAudioPlay.setOnClickListener {
            updateDialogVisibility(false)
            dismissAllowingStateLoss()
            ReadAloud.openAudioPlayActivity(requireContext())
        }
        ivPlayPrev.setOnClickListener { ReadAloud.prevParagraph(requireContext()) }
        ivPlayNext.setOnClickListener { ReadAloud.nextParagraph(requireContext()) }
        llToBackstage.setOnClickListener {
            (activity as? ReadBookActivity)?.toReadAloudBackstage()
            dismissAllowingStateLoss()
        }
        ivTtsSpeechReduce.setOnClickListener {
            seekTtsSpeechRate.progress -= 1
            saveSpeechRate(seekTtsSpeechRate.progress)
            upTtsSpeechRate()
        }
        ivTtsSpeechAdd.setOnClickListener {
            seekTtsSpeechRate.progress += 1
            saveSpeechRate(seekTtsSpeechRate.progress)
            upTtsSpeechRate()
        }
        ivTimer.setOnClickListener {
            AppConfig.ttsTimer = seekTimer.progress
            toastOnUi("保存设定时间成功！")
        }
        tvTimer.setOnClickListener {
            val times = intArrayOf(0, 5, 10, 15, 30, 60, 90, 180)
            val timeKeys = times.map { "$it 分钟" }
            context?.selector("设定时间", timeKeys) { _, index ->
                ReadAloud.setTimer(requireContext(), times[index])
            }
        }
        seekTtsSpeechRate.setOnSeekBarChangeListener(object : SeekBarChangeListener {

            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                super.onProgressChanged(seekBar, progress, fromUser)
                upTtsSpeechRateText(progress)
            }

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                saveSpeechRate(seekBar.progress)
                upTtsSpeechRate()
            }
        })
        seekTimer.setOnSeekBarChangeListener(object : SeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                upTimerText(progress)
            }

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                ReadAloud.setTimer(requireContext(), seekTimer.progress)
            }
        })

        seekReadProgress.setOnSeekBarChangeListener(object : SeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                when (displayedReadProgress?.kind) {
                    ReadAloudProgress.Kind.PARAGRAPH -> {
                        binding.tvDurTime.text = getString(
                            R.string.read_aloud_paragraph_progress,
                            progress + 1
                        )
                    }
                    ReadAloudProgress.Kind.TIME -> {
                        binding.tvDurTime.text = formatTime(progress)
                    }
                    null -> Unit
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {
                trackingReadProgress = true
            }

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                trackingReadProgress = false
                val progress = displayedReadProgress ?: return
                if (seekBar.progress != progress.position) {
                    ReadAloud.seekToProgress(
                        requireContext(),
                        progress.chapterIndex,
                        seekBar.progress
                    )
                }
            }
        })
    }

    override fun upSpeakEngineSummary() {
        binding.tvCatalog.text = getString(
            R.string.current_tts_engine_summary,
            speakEngineSummary()
        )
        bindSpeechRateControls()
    }

    private fun speakEngineSummary(): String {
        val ttsEngine = ReadAloud.ttsEngine ?: return getString(R.string.system_tts)
        if (ttsEngine == ReadAloud.SOURCE_AUDIO_ENGINE_ID) {
            return getString(R.string.source_audio_engine)
        }
        if (StringUtils.isNumeric(ttsEngine)) {
            return appDb.httpTTSDao.getName(ttsEngine.toLong())
                ?: getString(R.string.http_tts_missing, ttsEngine)
        }
        return GSON.fromJsonObject<SelectItem<String>>(ttsEngine).getOrNull()?.title
            ?: getString(R.string.system_tts)
    }

    private fun upTtsSpeechRateEnabled(enabled: Boolean) {
        binding.run {
            upTtsSpeechRateText(currentSpeechRateProgress())
            tvTtsSpeedValue.visible(enabled)
            seekTtsSpeechRate.isEnabled = enabled
            ivTtsSpeechReduce.isEnabled = enabled
            ivTtsSpeechAdd.isEnabled = enabled
        }
    }

    private fun bindSpeechRateControls() = binding.run {
        cbTtsFollowSys.setOnCheckedChangeListener(null)
        cbTtsFollowSys.visible(!isSourceAudioSelected)
        cbTtsFollowSys.isChecked = AppConfig.ttsFlowSys
        configureSpeechRateSlider()
        upTtsSpeechRateEnabled(isSourceAudioSelected || !cbTtsFollowSys.isChecked)
        cbTtsFollowSys.setOnCheckedChangeListener { _, isChecked ->
            if (isSourceAudioSelected) {
                return@setOnCheckedChangeListener
            }
            AppConfig.ttsFlowSys = isChecked
            upTtsSpeechRateEnabled(!isChecked)
            upTtsSpeechRate()
        }
    }

    private fun upPlayState() {
        if (BaseReadAloudService.loading) {
            binding.ivPlayPause.setImageResource(R.drawable.ic_refresh_black_24dp)
            binding.ivPlayPause.contentDescription = getString(R.string.loading)
            binding.ivPlayPause.isEnabled = false
            startLoadingAnimation()
        } else if (!BaseReadAloudService.pause) {
            stopLoadingAnimation()
            binding.ivPlayPause.setImageResource(R.drawable.ic_pause_24dp)
            binding.ivPlayPause.contentDescription = getString(R.string.pause)
            binding.ivPlayPause.isEnabled = true
        } else {
            stopLoadingAnimation()
            binding.ivPlayPause.setImageResource(R.drawable.ic_play_24dp)
            binding.ivPlayPause.contentDescription = getString(R.string.audio_play)
            binding.ivPlayPause.isEnabled = true
        }
        binding.ivPlayPause.setColorFilter(ReaderSheetStyle.resolve(requireContext()).textColor)
    }

    private fun startLoadingAnimation() {
        if (loadingAnimator?.isStarted == true) return
        loadingAnimator?.cancel()
        loadingAnimator = ObjectAnimator
            .ofFloat(binding.ivPlayPause, View.ROTATION, 0f, 360f)
            .apply {
                duration = 900
                repeatCount = ObjectAnimator.INFINITE
                interpolator = LinearInterpolator()
                start()
            }
    }

    private fun stopLoadingAnimation() {
        loadingAnimator?.cancel()
        loadingAnimator = null
        binding.ivPlayPause.rotation = 0f
    }

    private fun upSeekTimer() {
        binding.seekTimer.post {
            if (BaseReadAloudService.timeMinute > 0) {
                binding.seekTimer.progress = BaseReadAloudService.timeMinute
            } else {
                binding.seekTimer.progress = AppConfig.ttsTimer
            }
        }
    }

    private fun upTimerText(timeMinute: Int) {
        if (timeMinute < 0) {
            binding.tvTimer.text = requireContext().getString(R.string.timer_m, 0)
        } else {
            binding.tvTimer.text = requireContext().getString(R.string.timer_m, timeMinute)
        }
    }

    @SuppressLint("SetTextI18n")
    private fun upTtsSpeechRateText(value: Int) {
        binding.tvTtsSpeedValue.text = if (isSourceAudioSelected) {
            "%.1f".format(value / 10f + 0.5f)
        } else {
            ((value + 5) / 10f).toString()
        }
    }

    private fun upTtsSpeechRate() {
        if (isSourceAudioSelected) {
            ReadAloud.setSpeed(
                requireContext(),
                binding.seekTtsSpeechRate.progress / 10f + 0.5f,
            )
        } else {
            ReadAloud.upTtsSpeechRate(requireContext())
        }
    }

    private fun configureSpeechRateSlider() = binding.seekTtsSpeechRate.run {
        if (isSourceAudioSelected) {
            max = 25
        } else {
            max = 45
        }
        progress = currentSpeechRateProgress()
        upTtsSpeechRateText(progress)
    }

    private fun currentSpeechRateProgress(): Int {
        return if (isSourceAudioSelected) {
            (((ReadBook.book?.getPlaySpeed() ?: 1f) - 0.5f) * 10).toInt().coerceIn(0, 25)
        } else {
            AppConfig.ttsSpeechRate
        }
    }

    private fun saveSpeechRate(progress: Int) {
        if (!isSourceAudioSelected) {
            AppConfig.ttsSpeechRate = progress
        }
    }

    private fun upReadProgress() {
        val progress = ReadAloud.progressForSelectedEngine()
        if (progress != null) {
            updateReadProgress(progress)
        } else {
            showPendingProgressForSelectedEngine()
        }
    }

    private fun showPendingProgressForSelectedEngine() = binding.run {
        displayedReadProgress = null
        panelProgress.visible()
        seekReadProgress.isEnabled = false
        seekReadProgress.max = 1
        seekReadProgress.progress = 0
        if (isSourceAudioSelected) {
            tvDurTime.setText(R.string.read_aloud_time_pending)
            tvAllTime.setText(R.string.read_aloud_time_pending)
        } else {
            tvDurTime.setText(R.string.read_aloud_paragraph_pending)
            tvAllTime.setText(R.string.read_aloud_paragraph_pending)
        }
    }

    private fun formatTime(milliseconds: Int): String {
        val seconds = milliseconds / 1000
        val minutes = seconds / 60
        val secs = seconds % 60
        return String.format("%02d:%02d", minutes, secs)
    }

    override fun observeLiveBus() {
        observeEvent<Int>(EventBus.ALOUD_STATE) { upPlayState() }
        observeEvent<Int>(EventBus.READ_ALOUD_DS) { binding.seekTimer.progress = it }
        observeEvent<Boolean>(EventBus.CLOSE_READ_ALOUD_DIALOG) {
            dismissAllowingStateLoss()
        }
        observeEvent<ReadAloudProgress>(EventBus.READ_ALOUD_PROGRESS) { progress ->
            if (ReadAloud.isProgressForSelectedEngine(progress)) {
                updateReadProgress(progress)
            }
        }
        observeEvent<ReadAloudEngineType>(EventBus.READ_ALOUD_ENGINE_CHANGED) {
            upSpeakEngineSummary()
            upReadProgress()
        }
    }

    private fun updateReadProgress(progress: ReadAloudProgress) = binding.run {
        displayedReadProgress = progress
        panelProgress.visible()
        when (progress.kind) {
            ReadAloudProgress.Kind.PARAGRAPH -> {
                tvDurTime.text = getString(
                    R.string.read_aloud_paragraph_progress,
                    progress.position + 1
                )
                tvAllTime.text = getString(R.string.read_aloud_paragraph_progress, progress.total)
                seekReadProgress.max = progress.total - 1
                seekReadProgress.isEnabled = progress.total > 1
            }
            ReadAloudProgress.Kind.TIME -> {
                tvDurTime.text = formatTime(progress.position)
                tvAllTime.text = formatTime(progress.total)
                seekReadProgress.max = progress.total
                seekReadProgress.isEnabled = progress.total > 0
            }
        }
        if (!trackingReadProgress) {
            seekReadProgress.progress = progress.position
        }
    }

    interface CallBack {
        fun showMenuBar()
        fun onClickReadAloud()
    }
}
