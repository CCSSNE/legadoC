package io.legado.app.help.ai

import org.json.JSONArray
import org.json.JSONObject

/**
 * User-editable JSON body for the structured completion path.
 * Provider authentication headers are deliberately kept outside this template.
 */
object AiStructuredRequestTemplate {

    const val MODEL_TOKEN = "{{model}}"
    const val SYSTEM_PROMPT_TOKEN = "{{systemPrompt}}"
    const val USER_CONTENT_TOKEN = "{{userContent}}"

    val default: String = """
        {
          "model": "$MODEL_TOKEN",
          "stream": true,
          "messages": [
            {
              "role": "system",
              "content": "$SYSTEM_PROMPT_TOKEN"
            },
            {
              "role": "user",
              "content": "$USER_CONTENT_TOKEN"
            }
          ],
          "temperature": 0,
          "response_format": {
            "type": "json_object"
          },
          "thinking": {
            "type": "disabled"
          },
          "reasoning_effort": "low",
          "enable_thinking": false,
          "extra_body": {
            "enable_thinking": false
          }
        }
    """.trimIndent()

    private val knownTokens = setOf(
        MODEL_TOKEN,
        SYSTEM_PROMPT_TOKEN,
        USER_CONTENT_TOKEN
    )

    fun validate(template: String) {
        val normalized = template.trim()
        require(normalized.isNotEmpty()) { "Request template is empty" }
        JSONObject(normalized)
    }

    fun render(
        template: String,
        model: String,
        systemPrompt: String,
        userContent: String
    ): String {
        validate(template)
        val root = JSONObject(template.trim())
        val replacements = mapOf(
            MODEL_TOKEN to model,
            SYSTEM_PROMPT_TOKEN to systemPrompt,
            USER_CONTENT_TOKEN to userContent
        )
        replaceObject(root, replacements)
        return root.toString()
    }

    private fun replaceObject(json: JSONObject, replacements: Map<String, String>) {
        val keys = json.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            when (val value = json.opt(key)) {
                is JSONObject -> replaceObject(value, replacements)
                is JSONArray -> replaceArray(value, replacements)
                is String -> json.put(key, replaceString(value, replacements))
            }
        }
    }

    private fun replaceArray(array: JSONArray, replacements: Map<String, String>) {
        for (index in 0 until array.length()) {
            when (val value = array.opt(index)) {
                is JSONObject -> replaceObject(value, replacements)
                is JSONArray -> replaceArray(value, replacements)
                is String -> array.put(index, replaceString(value, replacements))
            }
        }
    }

    private fun replaceString(value: String, replacements: Map<String, String>): String {
        return replacements.entries.fold(value) { current, (token, replacement) ->
            current.replace(token, replacement)
        }
    }
}
