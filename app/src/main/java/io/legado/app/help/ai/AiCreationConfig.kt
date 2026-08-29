package io.legado.app.help.ai

import io.legado.app.constant.PreferKey
import io.legado.app.help.config.AppConfig
import io.legado.app.ui.main.ai.AiModelConfig
import io.legado.app.ui.main.ai.AiProviderConfig
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.getPrefString
import io.legado.app.utils.putPrefBoolean
import io.legado.app.utils.putPrefString
import io.legado.app.utils.removePref
import splitties.init.appCtx

data class AiCreationModelTarget(
    val provider: AiProviderConfig,
    val modelId: String
)

object AiCreationConfig {

    const val SECTION_SELECTED_TEXT = "selected_text"
    const val SECTION_BACKGROUND = "background"
    const val SECTION_SCENE = "scene"
    const val SECTION_CHARACTER = "character"
    const val SECTION_NOTE = "note"

    val sectionOrder = listOf(
        SECTION_SELECTED_TEXT,
        SECTION_BACKGROUND,
        SECTION_SCENE,
        SECTION_CHARACTER,
        SECTION_NOTE
    )

    const val SCOPE_GLOBAL = "global"
    const val SCOPE_BOOK = "book"
    const val SCOPE_SESSION = "session"
    val scopeValues = listOf(SCOPE_GLOBAL, SCOPE_BOOK, SCOPE_SESSION)

    val defaultPromptTemplate = """
        你是专业的 AI 绘画与视频提示词生成器。
        请根据素材与参数，生成一段高质量的图像或视频生成提示词。

        # 参数
        \${参数}

        # 素材
        \${素材}

        要求：
        1. 只输出最终提示词正文，不要任何解释、前言或标题。
        2. 使用中文，画面描述具体可执行，充分利用素材中的场景、人设与参考信息。
        3. 素材未覆盖的部分按参数要求补全，不要虚构与素材冲突的设定。
    """.trimIndent()

    val defaultRequestTemplate = """
        {
          "model": "{{model}}",
          "stream": true,
          "messages": [
            {
              "role": "system",
              "content": "{{systemPrompt}}"
            },
            {
              "role": "user",
              "content": "{{userContent}}"
            }
          ],
          "temperature": 0.7
        }
    """.trimIndent()

    var reuseCurrentModel: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.aiCreationReuseCurrentModel, true)
        set(value) = appCtx.putPrefBoolean(PreferKey.aiCreationReuseCurrentModel, value)

    var independentProviderId: String
        get() = appCtx.getPrefString(PreferKey.aiCreationProvider).orEmpty().trim()
        set(value) = appCtx.putPrefString(PreferKey.aiCreationProvider, value.trim())

    val independentProvider: AiProviderConfig?
        get() = AppConfig.aiProviderList.firstOrNull { it.id == independentProviderId }

    var independentModelId: String
        get() = appCtx.getPrefString(PreferKey.aiCreationModel).orEmpty().trim()
        set(value) = appCtx.putPrefString(PreferKey.aiCreationModel, value.trim())

    val independentModel: AiModelConfig?
        get() = AppConfig.aiModelConfigList.firstOrNull {
            it.id == independentModelId && it.providerId == independentProviderId
        }

    var promptTemplate: String
        get() = appCtx.getPrefString(PreferKey.aiCreationPromptTemplate)
            ?.takeIf { it.isNotBlank() }
            ?: defaultPromptTemplate
        set(value) {
            val normalized = value.trim()
            appCtx.putPrefString(
                PreferKey.aiCreationPromptTemplate,
                if (normalized == defaultPromptTemplate) "" else normalized
            )
        }

    var requestTemplate: String
        get() = appCtx.getPrefString(PreferKey.aiCreationRequestTemplate)
            ?.takeIf { it.isNotBlank() }
            ?: defaultRequestTemplate
        set(value) = appCtx.putPrefString(PreferKey.aiCreationRequestTemplate, value.trim())

    var variablesJson: String
        get() = appCtx.getPrefString(PreferKey.aiCreationVariables)
            ?.takeIf { it.isNotBlank() }
            ?: AiCreationVariables.defaultJson
        set(value) {
            val normalized = value.trim()
            appCtx.putPrefString(
                PreferKey.aiCreationVariables,
                if (normalized == AiCreationVariables.defaultJson) "" else normalized
            )
        }

    val variables: List<AiCreationVariable>
        get() = AiCreationVariables.parse(variablesJson)

    fun requireModelTarget(): AiCreationModelTarget {
        if (reuseCurrentModel) {
            val provider = AppConfig.aiCurrentProvider
                ?: error("请先配置当前 AI 提供商，或关闭“复用当前 AI 模型”后选择 AI 创作模型")
            val model = AppConfig.aiCurrentModelConfig?.modelId.orEmpty()
            check(model.isNotBlank()) {
                "请先配置当前 AI 模型，或关闭“复用当前 AI 模型”后选择 AI 创作模型"
            }
            return AiCreationModelTarget(provider, model)
        }
        val provider = independentProvider
            ?: error("请先在 AI 设置中选择 AI 创作供应商（或开启“复用当前 AI 模型”）")
        check(provider.baseUrl.isNotBlank()) { "AI 创作所选供应商的 API 地址不能为空" }
        val model = independentModel
            ?.takeIf { it.providerId == provider.id }
            ?: error("请先在 AI 设置中选择 AI 创作模型（或开启“复用当前 AI 模型”）")
        return AiCreationModelTarget(provider, model.modelId)
    }

    fun scopeKeyOf(section: String): String = when (section) {
        SECTION_SELECTED_TEXT -> PreferKey.aiCreationScopeSelectedText
        SECTION_BACKGROUND -> PreferKey.aiCreationScopeBackground
        SECTION_SCENE -> PreferKey.aiCreationScopeScene
        SECTION_CHARACTER -> PreferKey.aiCreationScopeCharacter
        SECTION_NOTE -> PreferKey.aiCreationScopeNote
        else -> PreferKey.aiCreationScopeBackground
    }

    fun sectionScope(section: String): String {
        val raw = appCtx.getPrefString(scopeKeyOf(section)).orEmpty()
        return if (raw in scopeValues) raw else defaultScopeOf(section)
    }

    fun setSectionScope(section: String, scope: String) {
        if (scope !in scopeValues) return
        if (scope == defaultScopeOf(section)) {
            appCtx.removePref(scopeKeyOf(section))
        } else {
            appCtx.putPrefString(scopeKeyOf(section), scope)
        }
    }

    fun defaultScopeOf(section: String): String = when (section) {
        SECTION_NOTE -> SCOPE_SESSION
        SECTION_SELECTED_TEXT -> SCOPE_BOOK
        else -> SCOPE_GLOBAL
    }
}
