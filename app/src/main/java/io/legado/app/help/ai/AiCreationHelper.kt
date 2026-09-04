package io.legado.app.help.ai

import io.legado.app.constant.AppLog
import io.legado.app.data.entities.CreationCard

object AiCreationHelper {

    suspend fun generatePrompt(
        session: AiCreationSession,
        cardsById: Map<Long, CreationCard>
    ): String {
        return generatePrompt(session, session.buildMaterialText(cardsById))
    }

    /** 按调用方给定的总素材文本生成提示词：手动提示词页用上框编辑结果替代卡片自动汇总 */
    suspend fun generatePrompt(
        session: AiCreationSession,
        materialText: String
    ): String {
        //LLM 变量与输入模板来自全局 LLM 变量设置；生图/生视频参数变量来自当前供应商，互不引用
        val mode = session.paramValue(AI_CREATION_MODE_KEY)
            ?: error("AI 创作模式未设置")
        val llmDefinition = when (mode) {
            AiCreationVariables.GROUP_IMAGE -> AiCreationConfig.imageLlmDefinition
            AiCreationVariables.GROUP_VIDEO -> AiCreationConfig.videoLlmDefinition
            else -> error("未知 AI 创作模式：$mode")
        }
        val providerVariables = when (mode) {
            AiCreationVariables.GROUP_IMAGE -> AiCreationConfig.imageVariables
            AiCreationVariables.GROUP_VIDEO -> AiCreationConfig.videoVariables
            else -> error("未知 AI 创作模式：$mode")
        }
        val target = AiCreationConfig.requireModelTarget()
        val requestValues = buildRequestValues(session, providerVariables)
        val routeParams = llmDefinition.variables.associate {
            it.key to it.effectiveValue(session.llmVariableValue(mode, it.key))
        }
        val promptName = AiCreationConfig.resolvePromptName(llmDefinition, routeParams)
        val promptText = AiCreationConfig.promptTextOf(promptName)
        val llmInput = renderLlmInput(
            template = llmDefinition.llmInputTemplate,
            prompt = promptText,
            material = materialText
        )
        AppLog.putAi(
            "AI_CREATION REQUEST\n" +
                "provider=${target.provider.name}\n" +
                "model=${target.modelId}\n" +
                "route=$promptName\n" +
                "routeParams=${routeParams.entries.joinToString("，") { "${it.key}=${it.value}" }}\n" +
                "llmInputChars=${llmInput.length}"
        )
        val response = AiChatService.generatePlainText(
            provider = target.provider,
            model = target.modelId,
            userContent = llmInput,
            temperature = 0.7
        )
        //工作流溯源：记录本次渲染后发给 LLM 的完整输入（含提示词模板文本与素材组合）
        session.setParam(AI_CREATION_LLM_INPUT_KEY, llmInput)
        return response
    }

    /** 供应商变量只服务于路由与最终图片/视频请求，不进入 LLM 输入。 */
    fun buildRequestValues(
        session: AiCreationSession,
        variables: List<AiCreationVariable>
    ): Map<String, String> {
        val values = linkedMapOf<String, String>()
        val mode = session.paramValue(AI_CREATION_MODE_KEY)
            ?: error("AI 创作模式未设置")
        variables.forEach { variable ->
            values[variable.key] = variable.effectiveValue(
                session.providerVariableValue(mode, variable.key)
            )
        }
        return values
    }

    /** 用 LLM 输入模板组合路由提示词与素材，得到发给 LLM 的完整输入。 */
    private fun renderLlmInput(
        template: String,
        prompt: String,
        material: String
    ): String {
        val rendered = template
            .replace("\${prompt}", prompt)
            .replace("\${素材}", material)
        val unresolved = TEMPLATE_VARIABLE.findAll(rendered)
            .map { it.groupValues[1] }
            .toSet()
        require(unresolved.isEmpty()) {
            "LLM 输入模板包含未定义变量：${unresolved.joinToString("、")}"
        }
        return rendered
    }

    private val TEMPLATE_VARIABLE = Regex("\\$\\{([^{}]+)\\}")
}
