package io.legado.app.help.ai

import org.json.JSONObject

data class AiResolvedTool(
    val name: String,
    val definition: JSONObject,
    val execute: suspend (JSONObject?) -> String
)
