package io.legado.app.plugin.doubao

import io.legado.app.plugin.TtsCacheSynthesizer

/**
 * 把豆包原生引擎接入 legadoC 的 TTS 缓存体系（「TTS 缓存」界面 / 批量缓存 / 缓存命中维度设置）。
 *
 * - [activeVoiceKey] 参与缓存 key 的音色维度（对应「命中维度：音色」开关）；
 * - [synthesize] 返回完整 WAV（含 44 字节头），播放端按内容嗅探；
 * - 语速不参与合成：豆包引擎的语速统一由播放端变速实现，speed 参数仅为接口对齐。
 */
object DoubaoTtsCacheSynthesizer : TtsCacheSynthesizer {

    override fun activeVoiceKey(): String? = runCatching { DoubaoVoices.resolveVoice() }.getOrNull()

    override suspend fun synthesize(text: String, speed: Float): ByteArray {
        val voiceId = DoubaoVoices.resolveVoice()
        val timeoutMs = runCatching {
            io.legado.app.help.config.AppConfig.ttsCacheSegmentTimeoutSeconds * 1000L
        }.getOrDefault(90_000L)
        return DoubaoTtsClient.synthesize(
            text = text,
            voiceId = voiceId,
            timeoutMs = timeoutMs
        ).wav
    }
}
