package io.legado.app.help.agent

import io.legado.app.help.ai.AiCreationProviderStore
import io.legado.app.help.ai.AiRequestTimeoutConfig
import io.legado.app.help.http.okHttpClient
import io.legado.app.ui.main.ai.AiProviderConfig
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object AgentHttp {
    fun providerRequest(provider: AiProviderConfig, route: String, body: JSONObject): JSONObject {
        val base = provider.baseUrl.trim().trimEnd('/')
        val url = when {
            base.endsWith("/$route") -> base
            base.endsWith("/chat/completions") -> base.removeSuffix("/chat/completions") + "/$route"
            base.endsWith("/v1") || base.endsWith("/v4") -> "$base/$route"
            else -> "$base/v1/$route"
        }
        val headers = JSONObject(AiCreationProviderStore.parseCustomHeaders(provider.headers.orEmpty()))
        if (provider.apiKey.isNotBlank()) headers.put("Authorization", "Bearer ${provider.apiKey.trim()}")
        return JSONObject().put("url", url).put("method", "POST").put("headers", headers).put("body", body.toString())
    }

    fun exchange(request: JSONObject, control: AgentControl, onEvent: (String, String) -> Boolean = { _, _ -> false }): JSONObject {
        control.check()
        val client = okHttpClient.newBuilder().retryOnConnectionFailure(false).followRedirects(false)
            .followSslRedirects(false)
            .readTimeout(AiRequestTimeoutConfig.sseIdleTimeoutSeconds.toLong(), TimeUnit.SECONDS)
            .callTimeout(AiRequestTimeoutConfig.generationTimeoutSeconds.toLong(), TimeUnit.SECONDS).build()
        val builder = Request.Builder().url(request.getString("url"))
            .header("Accept", "application/json, text/event-stream")
        request.optJSONObject("headers")?.let { headers ->
            headers.keys().forEach { name -> builder.header(name, headers.getString(name)) }
        }
        val body = if (request.has("body")) request.getString("body")
            .toRequestBody(request.optString("contentType", "application/json").toMediaType()) else null
        builder.method(request.optString("method", "POST"), body)
        val call = client.newCall(builder.build())
        control.onCancel(call) { call.cancel() }
        try {
            return call.execute().use { response ->
                val result = JSONObject().put("status", response.code)
                    .put("headers", JSONObject(response.headers.toMultimap()))
                val responseBody = response.body ?: error("HTTP ${response.code} 缺少响应体")
                if (response.header("Content-Type").orEmpty().contains("text/event-stream", true) && response.isSuccessful) {
                    val source = responseBody.source()
                    val data = StringBuilder()
                    var event = "message"
                    var complete = false
                    while (!complete) {
                        control.check()
                        val line = source.readUtf8Line()
                        if (line == null || line.isEmpty()) {
                            if (data.isNotEmpty()) {
                                complete = onEvent(event, data.toString().removeSuffix("\n"))
                                data.clear()
                            }
                            event = "message"
                            if (line == null) break
                        } else if (line.startsWith("data:")) {
                            data.append(line.substring(5).removePrefix(" ")).append('\n')
                        } else if (line.startsWith("event:")) {
                            event = line.substring(6).trim()
                        }
                    }
                    result.put("stream", true).put("complete", complete)
                } else {
                    result.put("stream", false).put("body", responseBody.string())
                }
                control.check()
                result
            }
        } catch (error: Exception) {
            control.check()
            throw IllegalStateException("HTTP ${request.getString("url")} 失败；请求未自动重发，写入结果可能未知：${error.message}", error)
        } finally {
            control.removeCancel(call)
        }
    }
}
