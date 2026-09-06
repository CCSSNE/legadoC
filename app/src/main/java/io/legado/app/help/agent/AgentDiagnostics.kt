package io.legado.app.help.agent

import io.legado.app.help.ai.AiLogConfig
import androidx.preference.PreferenceManager
import splitties.init.appCtx
import org.json.JSONArray
import org.json.JSONObject

object AgentDiagnostics {
    private val credentialKey = Regex("(?i).*(authorization|api[-_]?key|access[-_]?token|password|secret|cookie).*")

    fun protect(value: JSONObject): JSONObject {
        if (!AiLogConfig.apiRedactionEnabled) return JSONObject(value.toString())
        val secrets = buildList {
            val providerText = PreferenceManager.getDefaultSharedPreferences(appCtx).all["aiProviderList"] as? String
            if (providerText != null) {
                val strings = Regex("\"(apiKey|headers)\"\\s*:\\s*(\"(?:[^\"\\\\]|\\\\.)*\")")
                strings.findAll(providerText).forEach { match ->
                    val decoded = org.json.JSONTokener(match.groupValues[2]).nextValue() as String
                    if (match.groupValues[1] == "apiKey" && decoded.isNotEmpty()) add(decoded)
                    if (match.groupValues[1] == "headers") io.legado.app.help.ai.AiCreationProviderStore.parseCustomHeaders(decoded)
                        .filterKeys { credentialKey.matches(it) }.values.filterTo(this) { it.isNotEmpty() }
                }
            }
            listOf("mcp.clients", "mcp.servers", "module.settings").forEach { namespace ->
                AgentStore.dao.documents(namespace).forEach { document ->
                    val config = JSONObject(document.json)
                    config.keys().forEach { key ->
                        if (credentialKey.matches(key) && config.opt(key) is String && config.getString(key).isNotEmpty()) add(config.getString(key))
                    }
                }
            }
        }.distinct().sortedByDescending { it.length }
        fun protect(value: Any?): Any = when (value) {
            null, JSONObject.NULL -> JSONObject.NULL
            is JSONObject -> JSONObject().apply {
                value.keys().forEach { key -> put(key, if (credentialKey.matches(key)) "<redacted>" else protect(value.get(key))) }
            }
            is JSONArray -> JSONArray().apply { for (index in 0 until value.length()) put(protect(value.get(index))) }
            is String -> secrets.fold(value) { text, secret -> text.replace(secret, "<redacted>") }
            else -> value
        }
        return protect(value) as JSONObject
    }
}
