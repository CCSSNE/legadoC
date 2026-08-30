package io.legado.app.help.ai

import io.legado.app.constant.AppLog
import io.legado.app.data.entities.CreationCard

object AiCreationHelper {

    suspend fun generatePrompt(
        session: AiCreationSession,
        cardsById: Map<Long, CreationCard>
    ): String {
        //按当前模式取对应体系的变量定义：图片读图片供应商，视频读视频供应商，互不引用
        val mode = session.paramValue(AI_CREATION_MODE_KEY)
            ?: error("AI 创作模式未设置")
        val definition = when (mode) {
            AiCreationVariables.GROUP_IMAGE -> AiCreationConfig.imageDefinition
            AiCreationVariables.GROUP_VIDEO -> AiCreationConfig.videoDefinition
            else -> error("未知 AI 创作模式：$mode")
        }
        val target = AiCreationConfig.requireModelTarget()
        val requestValues = buildRequestValues(session, definition.variables)
        val routeParams = definition.variables.associate {
            it.key to requestValues[it.key].orEmpty()
        }
        val promptName = AiCreationConfig.resolvePromptName(definition, routeParams)
        val promptText = AiCreationConfig.promptTextOf(promptName)
        val userContent = renderFinalPrompt(
            finalPrompt = definition.finalPrompt,
            prompt = promptText,
            material = session.buildMaterialText(cardsById)
        )
        AppLog.putAi(
            "AI_CREATION REQUEST\n" +
                "provider=${target.provider.name}\n" +
                "model=${target.modelId}\n" +
                "route=$promptName\n" +
                "routeParams=${routeParams.entries.joinToString("，") { "${it.key}=${it.value}" }}\n" +
                "userChars=${userContent.length}"
        )
        val response = AiChatService.generatePlainText(
            provider = target.provider,
            model = target.modelId,
            userContent = userContent,
            temperature = 0.7
        )
        return response
    }

    /** 供应商变量只服务于路由与最终图片/视频请求，不进入 LLM 的 finalPrompt。 */
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

    private fun renderFinalPrompt(
        finalPrompt: String,
        prompt: String,
        material: String
    ): String {
        val rendered = finalPrompt
            .replace("\${prompt}", prompt)
            .replace("\${素材}", material)
        val unresolved = TEMPLATE_VARIABLE.findAll(rendered)
            .map { it.groupValues[1] }
            .toSet()
        require(unresolved.isEmpty()) {
            "finalPrompt 包含未定义变量：${unresolved.joinToString("、")}"
        }
        return rendered
    }

    private val TEMPLATE_VARIABLE = Regex("\\$\\{([^{}]+)\\}")
}
