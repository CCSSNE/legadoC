package io.legado.app.ui.book.audio.config

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.databinding.DialogAudioPlayFontSettingBinding
import io.legado.app.help.config.AppConfig
import io.legado.app.ui.widget.seekbar.SeekBarChangeListener
import io.legado.app.utils.setLayout
import io.legado.app.utils.viewbindingdelegate.viewBinding

/**
 * 听书播放页（AudioPlayActivity）字体设置弹窗：正文行字号与当前朗读行放大倍率。
 * 文字书 TTS 与书源音频共用同一套设置，不区分引擎。
 */
class AudioPlayFontSettingDialog :
    BaseDialogFragment(R.layout.dialog_audio_play_font_setting) {

    private val binding by viewBinding(DialogAudioPlayFontSettingBinding::bind)

    override fun onStart() {
        super.onStart()
        setLayout(0.9f, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        binding.run {
            // 字号：SEARCH_SIZE_STEP px 步进
            sbTextSize.max =
                (AppConfig.MAX_AUDIO_PLAY_TEXT_SIZE - AppConfig.MIN_AUDIO_PLAY_TEXT_SIZE) /
                    TEXT_SIZE_STEP
            sbTextSize.progress =
                (AppConfig.audioPlayTextSize - AppConfig.MIN_AUDIO_PLAY_TEXT_SIZE) / TEXT_SIZE_STEP
            tvFontSizeValue.text = getString(
                R.string.audio_play_font_size_value,
                AppConfig.audioPlayTextSize
            )
            sbTextSize.setOnSeekBarChangeListener(object : SeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                    val size = AppConfig.MIN_AUDIO_PLAY_TEXT_SIZE + progress * TEXT_SIZE_STEP
                    AppConfig.audioPlayTextSize = size
                    tvFontSizeValue.text = getString(R.string.audio_play_font_size_value, size)
                }
            })

            // 放大倍率：5% 步进
            sbTextZoom.max =
                (AppConfig.MAX_AUDIO_PLAY_TEXT_ZOOM - AppConfig.MIN_AUDIO_PLAY_TEXT_ZOOM) /
                    TEXT_ZOOM_STEP
            sbTextZoom.progress =
                (AppConfig.audioPlayTextZoom - AppConfig.MIN_AUDIO_PLAY_TEXT_ZOOM) / TEXT_ZOOM_STEP
            tvFontZoomValue.text = getString(
                R.string.audio_play_font_zoom_value,
                AppConfig.audioPlayTextZoom
            )
            sbTextZoom.setOnSeekBarChangeListener(object : SeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                    val zoom = AppConfig.MIN_AUDIO_PLAY_TEXT_ZOOM + progress * TEXT_ZOOM_STEP
                    AppConfig.audioPlayTextZoom = zoom
                    tvFontZoomValue.text = getString(R.string.audio_play_font_zoom_value, zoom)
                }
            })
        }
    }

    private companion object {
        const val TEXT_SIZE_STEP = 2
        const val TEXT_ZOOM_STEP = 5
    }
}