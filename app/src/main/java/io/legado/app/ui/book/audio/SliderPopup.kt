package io.legado.app.ui.book.audio

import android.annotation.SuppressLint
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupWindow
import android.widget.SeekBar
import io.legado.app.R
import io.legado.app.databinding.PopupSeekBarBinding
import io.legado.app.help.config.AppConfig
import io.legado.app.model.ReadAloud
import io.legado.app.model.ReadBook
import io.legado.app.service.BaseReadAloudService
import io.legado.app.service.ReadAloudEngineType
import io.legado.app.ui.widget.seekbar.SeekBarChangeListener

class SliderPopup(
    private val context: Context,
    private val name: Int,
    private val onValueChanged: (() -> Unit)? = null,
) :
    PopupWindow(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT) {
    companion object {
        const val TIMER = 1
        const val SPEED = 2
    }

    private val binding = PopupSeekBarBinding.inflate(LayoutInflater.from(context))
    init {
        contentView = binding.root
        isTouchable = true
        isOutsideTouchable = false
        isFocusable = true
        setProcess()
        binding.seekBar.setOnSeekBarChangeListener(object : SeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (name == TIMER) {
                    setProcessTimerText(progress)
                    if (fromUser) {
                        ReadAloud.setTimer(context, progress)
                        onValueChanged?.invoke()
                    }
                    return
                }
                val speed = speedFromProgress(progress)
                setProcessSpeedText(speed)
                if (fromUser) {
                    if (ReadAloud.engineType == ReadAloudEngineType.SOURCE_AUDIO) {
                        ReadAloud.setSpeed(context, speed)
                    } else {
                        AppConfig.ttsSpeechRate = progress
                        ReadAloud.upTtsSpeechRate(context)
                    }
                    onValueChanged?.invoke()
                }
            }
        })
    }

    override fun showAsDropDown(anchor: View?, xoff: Int, yoff: Int, gravity: Int) {
        super.showAsDropDown(anchor, xoff, yoff, gravity)
        if (name == TIMER) {
            binding.seekBar.progress = BaseReadAloudService.timeMinute.coerceAtLeast(0)
        } else {
            binding.seekBar.progress = currentSpeedProgress()
        }
    }

    override fun showAtLocation(parent: View?, gravity: Int, x: Int, y: Int) {
        super.showAtLocation(parent, gravity, x, y)
        if (name == TIMER) {
            binding.seekBar.progress = BaseReadAloudService.timeMinute.coerceAtLeast(0)
        } else {
            binding.seekBar.progress = currentSpeedProgress()
        }
    }

    private fun setProcessTimerText(process: Int) {
        binding.tvSeekValue.text = context.getString(R.string.timer_m, process)
    }

    @SuppressLint("SetTextI18n")
    private fun setProcessSpeedText(speed: Float) {
        binding.tvSeekValue.text = "%.1fX".format(speed)
    }

    private fun setProcess() {
        if (name == TIMER) {
            binding.seekBar.max = 180
            setProcessTimerText(0)
            return
        }
        binding.seekBar.max = if (ReadAloud.engineType == ReadAloudEngineType.SOURCE_AUDIO) 25 else 45
        binding.seekBar.progress = currentSpeedProgress()
        setProcessSpeedText(speedFromProgress(binding.seekBar.progress))
    }

    private fun currentSpeedProgress(): Int {
        return if (ReadAloud.engineType == ReadAloudEngineType.SOURCE_AUDIO) {
            (((ReadBook.book?.getPlaySpeed() ?: 1f) - 0.5f) * 10).toInt().coerceIn(0, 25)
        } else {
            AppConfig.ttsSpeechRate.coerceIn(0, 45)
        }
    }

    private fun speedFromProgress(progress: Int): Float {
        return (progress + 5) / 10f
    }
}
