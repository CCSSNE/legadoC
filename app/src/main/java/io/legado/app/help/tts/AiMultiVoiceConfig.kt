package io.legado.app.help.tts

import io.legado.app.constant.PreferKey
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.getPrefString
import io.legado.app.utils.putPrefBoolean
import splitties.init.appCtx

/**
 * AI 多角色朗读全局开关与兜底发音人。
 * 兜底发音人（旁白/对白男/对白女）为全局配置；角色级绑定在 book_tts_voice_bindings 表。
 */
object AiMultiVoiceConfig {

    var enabled: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.aiMultiVoice)
        set(value) = appCtx.putPrefBoolean(PreferKey.aiMultiVoice, value)

    val narratorSpeakerId: String
        get() = appCtx.getPrefString(PreferKey.aiNarratorSpeakerId).orEmpty()

    val dialogueMaleSpeakerId: String
        get() = appCtx.getPrefString(PreferKey.aiDialogueMaleSpeakerId).orEmpty()

    val dialogueFemaleSpeakerId: String
        get() = appCtx.getPrefString(PreferKey.aiDialogueFemaleSpeakerId).orEmpty()
}
