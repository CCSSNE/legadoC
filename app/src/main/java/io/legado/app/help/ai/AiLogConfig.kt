package io.legado.app.help.ai

import io.legado.app.constant.PreferKey
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.putPrefBoolean
import splitties.init.appCtx

/** Shared policy for displaying and logging credentials used by AI providers. */
object AiLogConfig {

    const val DEFAULT_API_REDACTION_ENABLED = true

    var apiRedactionEnabled: Boolean
        get() = appCtx.getPrefBoolean(
            PreferKey.aiApiRedactionEnabled,
            DEFAULT_API_REDACTION_ENABLED
        )
        set(value) = appCtx.putPrefBoolean(PreferKey.aiApiRedactionEnabled, value)
}
