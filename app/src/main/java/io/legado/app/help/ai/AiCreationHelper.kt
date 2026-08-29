package io.legado.app.help.ai

import io.legado.app.constant.AppLog
import io.legado.app.data.entities.CreationCard
import org.json.JSONArray
import org.json.JSONObject

object AiCreationHelper {

    suspend fun generatePrompt(
        session: AiCreationSession,
        cardsById: Map<Long, CreationCard>
    ): String {
        val definition = AiCreationConfig.definition
        val target = AiCreationConfig.requireModelTarget()
        val values = buildValues(session, cardsById, definition.variables)
        val routeParams = definition.variables.associate {
            it.key to values[it.key].orEmpty()
        } + (AI_CREATION_MODE_KEY to values[AI_CREATION_MODE_KEY].orEmpty())
        val templateName = AiCreationConfig.resolveTemplateName(definition, routeParams)
        val bodyTemplate = AiCreationConfig.requestTemplateBody(templateName)
        val systemPrompt = renderTemplate(AiCreationConfig.promptTemplate, values)
        val userContent = values["素材"].orEmpty()
        val bodyJson = renderBodyTemplate(bodyTemplate, values)
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
            temperature = 0.7,
            requestTemplate = bodyJson
        )
        return stripThinking(response)
    }

    fun buildValues(
        session: AiCreationSession,
        cardsById: Map<Long, CreationCard>,
        variables: List<AiCreationVariable>
    ): Map<String, String> {
        val values = linkedMapOf<String, String>()
        values[AI_CREATION_MODE_KEY] = session.params[AI_CREATION_MODE_KEY].orEmpty()
        variables.forEach { variable ->
            values[variable.key] = session.params[variable.key] ?: variable.defaultValue
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

    private fun renderBodyTemplate(bodyJson: String, values: Map<String, String>): String {
        val root = try {
            JSONObject(bodyJson)
        } catch (throwable: Throwable) {
            throw IllegalStateException(
                "请求模板 body 不是合法 JSON：${throwable.message}",
                throwable
            )
        }
        replaceTokens(root, values)
        return root.toString()
    }

    private fun replaceTokens(json: JSONObject, values: Map<String, String>) {
        val keys = json.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            when (val value = json.opt(key)) {
                is JSONObject -> replaceTokens(value, values)
                is JSONArray -> replaceTokens(value, values)
                is String -> json.put(key, replaceTokensInString(value, values))
            }
        }
    }

    private fun replaceTokens(array: JSONArray, values: Map<String, String>) {
        for (index in 0 until array.length()) {
            when (val value = array.opt(index)) {
                is JSONObject -> replaceTokens(value, values)
                is JSONArray -> replaceTokens(value, values)
                is String -> array.put(index, replaceTokensInString(value, values))
            }
        }
    }

    private fun replaceTokensInString(value: String, values: Map<String, String>): String {
        return values.entries.fold(value) { acc, (key, replacement) ->
            acc.replace("\${$key}", replacement)
        }
    }

    private fun stripThinking(text: String): String {
        return text
            .replace(Regex("<think>[\\s\\S]*?</think>", RegexOption.IGNORE_CASE), "")
            .trim()
    }
}
