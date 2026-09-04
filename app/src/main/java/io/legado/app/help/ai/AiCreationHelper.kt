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

    /** 按调用方给定的素材文本生成提示词：自动流程用卡片汇总的素材渲染成LLM输入再发LLM */
    suspend fun generatePrompt(
        session: AiCreationSession,
        materialText: String
    ): String {
        val llmInput = buildLlmInput(session, materialText)
        return sendLlmInput(session, llmInput)
    }

    /** 手动提示词页上框即完整LLM输入：路由提示词与模板已渲染，直接原样发给LLM，不再二次套模板 */
    suspend fun generatePromptFromLlmInput(
        session: AiCreationSession,
        llmInput: String
    ): String {
        return sendLlmInput(session, llmInput)
    }

    /** 统一渲染入口：用LLM输入模板组合路由提示词与素材，得到发给LLM的完整输入；手动页预填与自动流程共用 */
    fun buildLlmInput(
        session: AiCreationSession,
        materialText: String
    ): String {
        //LLM 变量与输入模板来自全局 LLM 变量设置，与供应商无关
        val mode = session.paramValue(AI_CREATION_MODE_KEY)
            ?: error("AI 创作模式未设置")
        val llmDefinition = when (mode) {
            AiCreationVariables.GROUP_IMAGE -> AiCreationConfig.imageLlmDefinition
            AiCreationVariables.GROUP_VIDEO -> AiCreationConfig.videoLlmDefinition
            else -> error("未知 AI 创作模式：$mode")
        }
        val routeParams = llmDefinition.variables.associate {
            it.key to it.effectiveValue(session.llmVariableValue(mode, it.key))
        }
        val promptName = AiCreationConfig.resolvePromptName(llmDefinition, routeParams)
        val promptText = AiCreationConfig.promptTextOf(promptName)
        val rendered = renderLlmInput(
            template = llmDefinition.llmInputTemplate,
            prompt = promptText,
            material = materialText
        )
        AppLog.putAi(
            "AI_CREATION LLM_INPUT\n" +
                "route=$promptName\n" +
                "routeParams=${routeParams.entries.joinToString("，") { "${it.key}=${it.value}" }}\n" +
                "llmInputChars=${rendered.length}"
        )
        return rendered
    }

    /** 统一发送入口：上框已是完整LLM输入，原样发给LLM并落盘溯源 */
    private suspend fun sendLlmInput(
        session: AiCreationSession,
        llmInput: String
    ): String {
        val target = AiCreationConfig.requireModelTarget()
        AppLog.putAi(
            "AI_CREATION REQUEST\n" +
                "provider=${target.provider.name}\n" +
                "model=${target.modelId}\n" +
                "llmInputChars=${llmInput.length}"
        )
        val response = AiChatService.generatePlainText(
            provider = target.provider,
            model = target.modelId,
            userContent = llmInput,
            temperature = 0.7
        )
        //工作流溯源：记录本次发给 LLM 的完整输入
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
