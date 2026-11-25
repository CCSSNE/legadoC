package io.legado.app.help.tts

/**
 * 音色试听扩展点：由内置引擎（app 源集）注册实现，主代码只按接口调用。
 * 开源构建没有实现时，选择界面里的喇叭明确提示「该引擎不支持试听」，不静默失效。
 */
interface TtsVoicePreviewer {

    /** [engineId] 为音色所属引擎 id，[voiceId] 为该引擎内的音色 id。 */
    fun preview(engineId: String, voiceId: String, text: String): Boolean
}

object TtsVoicePreviewers {

    @Volatile
    var active: TtsVoicePreviewer? = null

    fun register(previewer: TtsVoicePreviewer) {
        active = previewer
    }
}
