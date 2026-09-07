package io.legado.app.help.ai

import io.legado.app.constant.PreferKey
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.getPrefInt
import io.legado.app.utils.getPrefString
import io.legado.app.utils.putPrefBoolean
import io.legado.app.utils.putPrefInt
import io.legado.app.utils.putPrefString
import org.json.JSONObject
import splitties.init.appCtx

/**
 * 模型上下文设置（全局）：发给模型的上下文超过 [contextMaxTokens] 时，从最新往回按
 * 用户消息边界整轮裁剪历史到 [trimToTokens]；工具输出超过 [toolOutputTrimChars] 字符时
 * 按模式保留头/尾。裁剪只影响发给模型的请求表面，会话原始记录保持完整。
 */
object AiContextTrimConfig {
    const val DEFAULT_CONTEXT_MAX_TOKENS = 200_000
    const val DEFAULT_TRIM_TO_TOKENS = 50_000
    const val DEFAULT_TOOL_OUTPUT_TRIM_CHARS = 8192
    const val MODE_HEAD_TAIL = "head_tail"
    const val MODE_HEAD = "head"
    const val MODE_TAIL = "tail"
    val MODES = setOf(MODE_HEAD_TAIL, MODE_HEAD, MODE_TAIL)

    var contextMaxTokens: Int
        get() = appCtx.getPrefInt(PreferKey.aiContextMaxTokens, DEFAULT_CONTEXT_MAX_TOKENS)
        set(value) = appCtx.putPrefInt(PreferKey.aiContextMaxTokens, value)

    var trimToTokens: Int
        get() = appCtx.getPrefInt(PreferKey.aiContextTrimToTokens, DEFAULT_TRIM_TO_TOKENS)
        set(value) = appCtx.putPrefInt(PreferKey.aiContextTrimToTokens, value)

    var toolOutputTrimEnabled: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.aiToolOutputTrimEnabled, true)
        set(value) = appCtx.putPrefBoolean(PreferKey.aiToolOutputTrimEnabled, value)

    var toolOutputTrimMode: String
        get() = appCtx.getPrefString(PreferKey.aiToolOutputTrimMode)?.takeIf { it in MODES }
            ?: MODE_HEAD_TAIL
        set(value) = appCtx.putPrefString(PreferKey.aiToolOutputTrimMode, value)

    var toolOutputTrimChars: Int
        get() = appCtx.getPrefInt(PreferKey.aiToolOutputTrimChars, DEFAULT_TOOL_OUTPUT_TRIM_CHARS)
        set(value) = appCtx.putPrefInt(PreferKey.aiToolOutputTrimChars, value)

    /** Agent JS 请求策略快照；随任务 config 读取，值非法时 JS 端不裁剪。 */
    fun snapshot(): JSONObject = JSONObject()
        .put("maxTokens", contextMaxTokens)
        .put("trimToTokens", trimToTokens)
        .put("toolOutputTrimEnabled", toolOutputTrimEnabled)
        .put("toolOutputTrimMode", toolOutputTrimMode)
        .put("toolOutputTrimChars", toolOutputTrimChars)
}
