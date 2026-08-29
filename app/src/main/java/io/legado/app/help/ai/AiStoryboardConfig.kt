package io.legado.app.help.ai

import io.legado.app.constant.PreferKey
import io.legado.app.help.config.AppConfig
import io.legado.app.ui.main.ai.AiModelConfig
import io.legado.app.ui.main.ai.AiProviderConfig
import io.legado.app.utils.getPrefInt
import io.legado.app.utils.getPrefString
import io.legado.app.utils.putPrefInt
import io.legado.app.utils.putPrefString
import splitties.init.appCtx

/**
 * 听书分镜模型选择：独立供应商+模型，与 AI 创作互不影响。
 */
object AiStoryboardConfig {

    var providerId: String
        get() = appCtx.getPrefString(PreferKey.aiStoryboardProviderId).orEmpty().trim()
        set(value) = appCtx.putPrefString(PreferKey.aiStoryboardProviderId, value.trim())

    var modelConfigId: String
        get() = appCtx.getPrefString(PreferKey.aiStoryboardModelId).orEmpty().trim()
        set(value) = appCtx.putPrefString(PreferKey.aiStoryboardModelId, value.trim())

    var preloadCount: Int
        get() = appCtx.getPrefInt(PreferKey.aiStoryboardPreloadCount, 2).coerceIn(0, 10)
        set(value) = appCtx.putPrefInt(PreferKey.aiStoryboardPreloadCount, value)

    val provider: AiProviderConfig?
        get() = AppConfig.aiProviderList.firstOrNull { it.id == providerId }

    val model: AiModelConfig?
        get() = AppConfig.aiModelConfigList.firstOrNull {
            it.id == modelConfigId && it.providerId == providerId
        }

    fun isConfigured(): Boolean = provider != null && model != null

    fun requireModelTarget(): Pair<AiProviderConfig, String> {
        val provider = provider ?: error("请先在 AI 设置中选择听书分镜供应商")
        val model = model ?: error("请先在 AI 设置中选择听书分镜模型")
        check(provider.baseUrl.isNotBlank()) { "听书分镜所选供应商的 API 地址不能为空" }
        return provider to model.modelId
    }
}
