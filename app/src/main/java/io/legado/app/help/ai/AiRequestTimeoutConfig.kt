package io.legado.app.help.ai

import io.legado.app.constant.PreferKey
import io.legado.app.utils.getPrefInt
import io.legado.app.utils.getPrefString
import io.legado.app.utils.putPrefInt
import io.legado.app.utils.putPrefString
import splitties.init.appCtx

/** Shared timeout policy for every AI streaming request. */
object AiRequestTimeoutConfig {

    const val DEFAULT_SSE_IDLE_TIMEOUT_SECONDS = 30
    const val DEFAULT_GENERATION_TIMEOUT_SECONDS = 120

    const val MIN_SSE_IDLE_TIMEOUT_SECONDS = 5
    const val MAX_SSE_IDLE_TIMEOUT_SECONDS = 300
    const val MIN_GENERATION_TIMEOUT_SECONDS = 30
    const val MAX_GENERATION_TIMEOUT_SECONDS = 900
    const val MIN_THINKING_INTERRUPT_SECONDS = 5
    const val MAX_THINKING_INTERRUPT_SECONDS = 600
    const val DEFAULT_THINKING_INTERRUPT_SECONDS = 5
    const val DEFAULT_THINKING_INTERRUPT_MAX_COUNT = 3
    const val MIN_THINKING_INTERRUPT_MAX_COUNT = 1
    const val MAX_THINKING_INTERRUPT_MAX_COUNT = 20

    var sseIdleTimeoutSeconds: Int
        get() = appCtx.getPrefInt(
            PreferKey.aiSseIdleTimeoutSeconds,
            DEFAULT_SSE_IDLE_TIMEOUT_SECONDS
        ).coerceIn(MIN_SSE_IDLE_TIMEOUT_SECONDS, MAX_SSE_IDLE_TIMEOUT_SECONDS)
        set(value) = appCtx.putPrefInt(
            PreferKey.aiSseIdleTimeoutSeconds,
            value.coerceIn(MIN_SSE_IDLE_TIMEOUT_SECONDS, MAX_SSE_IDLE_TIMEOUT_SECONDS)
        )

    var generationTimeoutSeconds: Int
        get() = appCtx.getPrefInt(
            PreferKey.aiGenerationTimeoutSeconds,
            DEFAULT_GENERATION_TIMEOUT_SECONDS
        ).coerceIn(MIN_GENERATION_TIMEOUT_SECONDS, MAX_GENERATION_TIMEOUT_SECONDS)
        set(value) = appCtx.putPrefInt(
            PreferKey.aiGenerationTimeoutSeconds,
            value.coerceIn(MIN_GENERATION_TIMEOUT_SECONDS, MAX_GENERATION_TIMEOUT_SECONDS)
        )

    /** Null keeps the existing generation-timeout path. */
    var thinkingInterruptSeconds: Int?
        get() {
            val stored = appCtx.getPrefString(PreferKey.aiThinkingInterruptSeconds)
            if (stored == null) return DEFAULT_THINKING_INTERRUPT_SECONDS
            val raw = stored.trim()
            if (raw.isEmpty()) return null
            val value = raw.toIntOrNull()
                ?: error("AI thinking interrupt seconds is not an integer: $raw")
            require(value in MIN_THINKING_INTERRUPT_SECONDS..MAX_THINKING_INTERRUPT_SECONDS) {
                "AI thinking interrupt seconds must be between " +
                    "$MIN_THINKING_INTERRUPT_SECONDS and $MAX_THINKING_INTERRUPT_SECONDS"
            }
            return value
        }
        set(value) = appCtx.putPrefString(
            PreferKey.aiThinkingInterruptSeconds,
            value?.coerceIn(
                MIN_THINKING_INTERRUPT_SECONDS,
                MAX_THINKING_INTERRUPT_SECONDS
            )?.toString().orEmpty()
        )

    var thinkingInterruptMaxCount: Int
        get() = appCtx.getPrefInt(
            PreferKey.aiThinkingInterruptMaxCount,
            DEFAULT_THINKING_INTERRUPT_MAX_COUNT
        ).coerceIn(MIN_THINKING_INTERRUPT_MAX_COUNT, MAX_THINKING_INTERRUPT_MAX_COUNT)
        set(value) = appCtx.putPrefInt(
            PreferKey.aiThinkingInterruptMaxCount,
            value.coerceIn(MIN_THINKING_INTERRUPT_MAX_COUNT, MAX_THINKING_INTERRUPT_MAX_COUNT)
        )
}
