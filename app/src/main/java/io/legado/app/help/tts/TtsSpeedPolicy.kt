package io.legado.app.help.tts

/**
 * 本地播放速度与脚本引擎合成速度的解耦策略（对齐 legado_NG）。
 */
object TtsSpeedPolicy {

    fun playbackRate(speechRateProgress: Int): Float {
        return (speechRateProgress.coerceIn(0, 45) + 5) / 10f
    }

    fun synthesisSpeed(engine: TtsEngineSetting): Int {
        return engine.effectiveSpeed()
    }

    fun playbackLabel(speechRateProgress: Int): String {
        return "${playbackRate(speechRateProgress)}x"
    }
}
