package io.legado.app.help.ai

import io.legado.app.constant.AppLog
import io.legado.app.data.entities.CreationCard

object AiCreationHelper {

    suspend fun generatePrompt(
        session: AiCreationSession,
        cardsById: Map<Long, CreationCard>,
        onStreamProgress: (AiStreamProgress) -> Unit = {}
    ): String {
        val variables = AiCreationConfig.variables
        val target = AiCreationConfig.requireModelTarget()
        val systemPrompt = buildSystemPrompt(session, cardsById, variables)
        val userContent = buildUserContent(session, cardsById, variables)
        AppLog.putAi(
            "AI_CREATION REQUEST\n" +
                "provider=${target.provider.name}\n" +
                "model=${target.modelId}\n" +
                "systemChars=${systemPrompt.length}\n" +
                "userChars=${userContent.length}"
        )
        val response = AiChatService.generateStructuredText(
            provider = target.provider,
            model = target.modelId,
            systemPrompt = systemPrompt,
            userContent = userContent,
            temperature = 0.7,
            requestTemplate = AiCreationConfig.requestTemplate,
            onStreamProgress = onStreamProgress
        )
        return stripThinking(response)
    }

    fun buildSystemPrompt(
        session: AiCreationSession,
        cardsById: Map<Long, CreationCard>,
        variables: List<AiCreationVariable>
    ): String {
        val values = linkedMapOf<String, String>()
        AiCreationConfig.sectionOrder.forEach { section ->
            values[session.sectionLabel(section)] = sectionText(session, section, cardsById)
        }
        values["素材"] = session.buildMaterialText(cardsById)
        values["参数"] = session.buildParamsText(variables)
        variables.forEach { variable ->
            values[variable.key] = session.params[variable.key].orEmpty()
        }
        return renderTemplate(AiCreationConfig.promptTemplate, values)
    }

    fun buildUserContent(
        session: AiCreationSession,
        cardsById: Map<Long, CreationCard>,
        variables: List<AiCreationVariable>
    ): String {
        val builder = StringBuilder()
        val params = session.buildParamsText(variables)
        if (params.isNotBlank()) {
            builder.append("【参数】\n").append(params).append("\n\n")
        }
        builder.append("【素材】\n")
            .append(session.buildMaterialText(cardsById).ifBlank { "（无）" })
            .append("\n\n请根据以上素材与参数，直接输出最终提示词。")
        return builder.toString()
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
