package io.legado.app.ui.book.audio.config

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.databinding.DialogAudioPlayDisplaySettingBinding
import io.legado.app.help.config.AppConfig
import io.legado.app.utils.setLayout
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.viewbindingdelegate.viewBinding

/**
 * 听书播放页（AudioPlayActivity）显示设置弹窗。
 * 文字书 TTS 与书源音频共用同一套设置，不区分引擎。
 */
class AudioPlayDisplaySettingDialog :
    BaseDialogFragment(R.layout.dialog_audio_play_display_setting) {

    private val binding by viewBinding(DialogAudioPlayDisplaySettingBinding::bind)

    override fun onStart() {
        super.onStart()
        setLayout(0.9f, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        binding.run {
            when (AppConfig.audioPlayTopTitleMode) {
                AppConfig.AUDIO_PLAY_TOP_TITLE_CHAPTER -> rbTopTitleChapterName.isChecked = true
                else -> rbTopTitleBookName.isChecked = true
            }
            rgTopTitleMode.setOnCheckedChangeListener { _, checkedId ->
                AppConfig.audioPlayTopTitleMode = when (checkedId) {
                    R.id.rb_top_title_chapter_name -> AppConfig.AUDIO_PLAY_TOP_TITLE_CHAPTER
                    else -> AppConfig.AUDIO_PLAY_TOP_TITLE_BOOK
                }
            }
            swShowChapterTitle.isChecked = AppConfig.audioPlayShowChapterTitle
            swShowChapterTitle.setOnUserCheckedChangeListener { checked ->
                AppConfig.audioPlayShowChapterTitle = checked
            }
            tvFontSetting.setOnClickListener {
                showDialogFragment<AudioPlayFontSettingDialog>()
            }
        }
    }
}