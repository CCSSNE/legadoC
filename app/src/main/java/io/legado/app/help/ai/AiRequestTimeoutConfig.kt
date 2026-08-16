package io.legado.app.help.ai

import io.legado.app.constant.PreferKey
import io.legado.app.utils.getPrefInt
import io.legado.app.utils.putPrefInt
import splitties.init.appCtx

/** Shared timeout policy for every AI streaming request. */
object AiRequestTimeoutConfig {

    const val DEFAULT_SSE_IDLE_TIMEOUT_SECONDS = 30
    const val DEFAULT_GENERATION_TIMEOUT_SECONDS = 120

    const val MIN_SSE_IDLE_TIMEOUT_SECONDS = 5
    const val MAX_SSE_IDLE_TIMEOUT_SECONDS = 300
    const val MIN_GENERATION_TIMEOUT_SECONDS = 30
    const val MAX_GENERATION_TIMEOUT_SECONDS = 900

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
}
