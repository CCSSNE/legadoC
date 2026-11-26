package io.legado.app.plugin

import android.content.Context
import io.legado.app.help.tts.TtsVoicePreviewers
import io.legado.app.plugin.doubao.DoubaoEnginePlugin
import io.legado.app.plugin.doubao.DoubaoVoiceDirectory
import io.legado.app.plugin.doubao.DoubaoVoicePreviewer

/**
 * 自用构建（app flavor）插件引导。
 *
 * 注册「豆包语音（内置）」朗读引擎：合成走原生 WebSocket 实现（DoubaoTtsClient），
 * 音色表内置 374 个，AI 选角通过发音人目录直接可读。
 * 开源构建不编译本源集，主代码在空注册表下正常运行。
 */
object AppPlugins {

    fun init(context: Context) {
        ReadAloudEngines.register(DoubaoEnginePlugin)
        TtsVoiceDirectories.register(DoubaoVoiceDirectory)
        TtsVoicePreviewers.register(DoubaoVoicePreviewer)
    }
}
