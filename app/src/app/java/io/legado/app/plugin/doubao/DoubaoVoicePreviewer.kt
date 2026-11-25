package io.legado.app.plugin.doubao

import io.legado.app.constant.AppLog
import io.legado.app.help.tts.TtsPreviewPlayer
import io.legado.app.help.tts.TtsVoicePreviewer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 内置豆包引擎的音色试听：合成一句样本，用 AudioTrack 直放。
 * 音色 id 可能是复合形式（doubao\nvoiceId），也可能已是裸音色 id。
 */
object DoubaoVoicePreviewer : TtsVoicePreviewer {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun preview(engineId: String, voiceId: String, text: String): Boolean {
        val resolved = resolveVoiceId(voiceId) ?: return false
        scope.launch {
            runCatching {
                DoubaoTtsClient.synthesize(text = text, voiceId = resolved)
            }.onSuccess { result ->
                TtsPreviewPlayer.play(result.wav)
            }.onFailure {
                AppLog.put("豆包试听失败：${DoubaoVoices.describe(resolved)}\n${it.localizedMessage}", it, true)
            }
        }
        return true
    }

    private fun resolveVoiceId(voiceId: String): String? {
        DoubaoVoices.parseGlobal(voiceId)?.let { return it }
        if (DoubaoVoices.byId(voiceId) != null) return voiceId
        // 内置目录下也可能是别的引擎的音色，明确拒绝而不是瞎试
        return null
    }
}
