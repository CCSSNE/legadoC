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
 * 听书分镜模型选择：默认复用当前 AI 提供商与模型，可关闭后选择独立供应商+模型，与 AI 创作互不影响。
 */
object AiStoryboardConfig {

    var reuseCurrentModel: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.aiStoryboardReuseCurrentModel, true)
        set(value) = appCtx.putPrefBoolean(PreferKey.aiStoryboardReuseCurrentModel, value)

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

    fun isConfigured(): Boolean = runCatching { requireModelTarget() }.isSuccess

    fun requireModelTarget(): Pair<AiProviderConfig, String> {
        if (reuseCurrentModel) {
            val provider = AppConfig.aiCurrentProvider
                ?: error("请先配置当前 AI 提供商，或关闭“复用当前 AI 模型”后选择听书分镜模型")
            val model = AppConfig.aiCurrentModelConfig?.modelId.orEmpty()
            check(model.isNotBlank()) {
                "请先配置当前 AI 模型，或关闭“复用当前 AI 模型”后选择听书分镜模型"
            }
            return provider to model
        }
        val independentProvider = provider
            ?: error("请先在 AI 设置中选择听书分镜供应商（或开启“复用当前 AI 模型”）")
        check(independentProvider.baseUrl.isNotBlank()) { "听书分镜所选供应商的 API 地址不能为空" }
        val independentModel = model
            ?: error("请先在 AI 设置中选择听书分镜模型（或开启“复用当前 AI 模型”）")
        return independentProvider to independentModel.modelId
    }
}
