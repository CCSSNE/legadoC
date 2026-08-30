package io.legado.app.help.ai

import io.legado.app.constant.AppLog
import io.legado.app.data.entities.CreationCard

object AiCreationHelper {

    suspend fun generatePrompt(
        session: AiCreationSession,
        cardsById: Map<Long, CreationCard>
    ): String {
        //按当前模式取对应体系的变量定义：图片读图片供应商，视频读视频供应商，互不引用
        val isVideo = session.paramValue(AI_CREATION_MODE_KEY) == AiCreationVariables.GROUP_VIDEO
        val definition = if (isVideo) {
            AiCreationConfig.videoDefinition
        } else {
            AiCreationConfig.imageDefinition
        }
        val target = AiCreationConfig.requireModelTarget()
        val values = buildValues(session, cardsById, definition.variables)
        val routeParams = definition.variables.associate {
            it.key to values[it.key].orEmpty()
        } + (AI_CREATION_MODE_KEY to values[AI_CREATION_MODE_KEY].orEmpty())
        val templateName = AiCreationConfig.resolveTemplateName(definition, routeParams)
        val promptText = AiCreationConfig.promptTextOf(templateName)
        val systemPrompt = renderTemplate(AiCreationConfig.promptTemplate, values)
        val userContent = renderTemplate(promptText, values)
        AppLog.putAi(
            "AI_CREATION REQUEST\n" +
                "provider=${target.provider.name}\n" +
                "model=${target.modelId}\n" +
                "route=$templateName\n" +
                "routeParams=${routeParams.entries.joinToString("，") { "${it.key}=${it.value}" }}\n" +
                "systemChars=${systemPrompt.length}\n" +
                "userChars=${userContent.length}"
        )
        val response = AiChatService.generateStructuredText(
            provider = target.provider,
            model = target.modelId,
            systemPrompt = systemPrompt,
            userContent = userContent,
            temperature = 0.7
        )
        return stripThinking(response)
    }

    fun buildValues(
        session: AiCreationSession,
        cardsById: Map<Long, CreationCard>,
        variables: List<AiCreationVariable>
    ): Map<String, String> {
        val values = linkedMapOf<String, String>()
        values[AI_CREATION_MODE_KEY] = session.paramValue(AI_CREATION_MODE_KEY).orEmpty()
        variables.forEach { variable ->
            //经 effectiveValue 清洗：变量定义变更后，持久层旧值/无效值回退默认值
            values[variable.key] = variable.effectiveValue(session.paramValue(variable.key))
        }
        AiCreationConfig.sectionOrder.forEach { section ->
            values[session.sectionLabel(section)] = sectionText(session, section, cardsById)
        }
        values["素材"] = session.buildMaterialText(cardsById)
        return values
    }

    private fun sectionText(
        session: AiCreationSession,
        section: String,
        cardsById: Map<Long, CreationCard>
    ): String {
        return session.sectionItems[section].orEmpty()
            .mapNotNull { cardsById[it.cardId]?.content?.trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n")
    }

    private fun renderTemplate(template: String, values: Map<String, String>): String {
        return values.entries.fold(template) { acc, (key, value) ->
            acc.replace("\${$key}", value)
        }
    }

    private fun stripThinking(text: String): String {
        return text
            .replace(Regex("<think>[\\s\\S]*?</think>", RegexOption.IGNORE_CASE), "")
            .trim()
    }
}
