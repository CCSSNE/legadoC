package io.legado.app.help.ai

import io.legado.app.help.http.newCallResponse
import io.legado.app.help.http.okHttpClient
import io.legado.app.help.http.postJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 视频供应商测试连接：用当前供应商全部配置真实提交一个视频生成请求并等到结果。
 * 按提交响应自动识别两类内置异步协议：
 * - 智谱 CogVideoX：提交返回 {id, task_status}，轮询 GET <版本前缀>/async-result/{id}，
 *   task_status: PROCESSING / SUCCESS / FAIL，结果在 video_result[].url
 * - 硅基流动 Wan：提交返回 {requestId}，轮询 POST <版本前缀>/video/status（body {requestId}），
 *   status: InQueue / InProgress / Succeed / Failed，结果在 results.videos[].url
 * 同步型接口（响应直接携带视频链接）则跳过轮询直接下载校验。
 */
object AiCreationVideoHelper {

    private const val POLL_INTERVAL_MS = 5_000L

    //视频生成耗时较长：轮询上限 8 分钟，超时如实报错并附任务号，不伪装成功
    private const val POLL_TIMEOUT_MS = 8 * 60_000L

    /** 成功正常返回；任何失败抛出带原因的异常 */
    suspend fun testConnection(
        provider: AiCreationProviderConfig,
        modelId: String
    ): Unit = withContext(Dispatchers.IO) {
        check(provider.requestTemplate.isNotBlank()) {
            "当前视频供应商「${provider.name}」的视频请求模板为空"
        }
        val variables = if (provider.variablesJson.isNotBlank()) {
            AiCreationVariables.parse(provider.variablesJson).variables
        } else {
            emptyList()
        }
        val tokens = buildMap {
            put("model", modelId)
            put("prompt", AiCreationProviderStore.VIDEO_TEST_PROMPT)
            put("n", "1")
            variables.forEach { variable ->
                put(variable.key, variable.effectiveValue(null))
            }
        }
        val body = AiCreationProviderStore.renderRequestTemplate(provider.requestTemplate, tokens)

        val submitText = postForText(provider, provider.baseUrl, body)
        val submitRoot = JSONObject(submitText)
        //同步型接口：响应直接携带视频链接
        extractVideoUrl(submitRoot)?.let { url ->
            downloadVideo(url)
            return@withContext
        }
        when {
            submitRoot.has("requestId") -> pollSiliconFlow(provider, submitRoot.optString("requestId"))
            submitRoot.has("id") && submitRoot.has("task_status") ->
                pollBigModel(provider, submitRoot.optString("id"))
            else -> throw IllegalStateException("视频提交响应无法识别：${submitText.take(300)}")
        }
    }

    private suspend fun pollBigModel(provider: AiCreationProviderConfig, taskId: String) {
        val pollBase = versionBaseOf(provider.baseUrl)
            ?: throw IllegalStateException("无法从 Base URL 推导视频轮询地址：${provider.baseUrl}")
        val pollUrl = pollBase + "async-result/" + taskId
        val deadline = System.currentTimeMillis() + POLL_TIMEOUT_MS
        while (true) {
            delay(POLL_INTERVAL_MS)
            val root = JSONObject(getForText(provider, pollUrl))
            when (root.optString("task_status")) {
                "SUCCESS" -> {
                    val url = extractVideoUrl(root)
                        ?: throw IllegalStateException("视频生成成功但未返回链接：${root.toString().take(300)}")
                    downloadVideo(url)
                    return
                }
                "FAIL" -> throw IllegalStateException("视频生成失败：${root.toString().take(300)}")
            }
            if (System.currentTimeMillis() > deadline) {
                throw IllegalStateException("视频生成超时（任务仍在处理中）：$taskId")
            }
        }
    }

    private suspend fun pollSiliconFlow(provider: AiCreationProviderConfig, requestId: String) {
        val pollBase = versionBaseOf(provider.baseUrl)
            ?: throw IllegalStateException("无法从 Base URL 推导视频轮询地址：${provider.baseUrl}")
        val pollUrl = pollBase + "video/status"
        val body = JSONObject().put("requestId", requestId).toString()
        val deadline = System.currentTimeMillis() + POLL_TIMEOUT_MS
        while (true) {
            delay(POLL_INTERVAL_MS)
            val root = JSONObject(postForText(provider, pollUrl, body))
            when (root.optString("status")) {
                "Succeed" -> {
                    val url = extractVideoUrl(root)
                        ?: throw IllegalStateException("视频生成成功但未返回链接：${root.toString().take(300)}")
                    downloadVideo(url)
                    return
                }
                "Failed" -> throw IllegalStateException(
                    "视频生成失败：${root.optString("reason").ifBlank { root.toString().take(300) }}"
                )
            }
            if (System.currentTimeMillis() > deadline) {
                throw IllegalStateException("视频生成超时（任务仍在处理中）：$requestId")
            }
        }
    }

    /** 从结果响应中提取视频链接：依次尝试 video_result[] / videos[] / data[] 与 results.videos[] */
    private fun extractVideoUrl(root: JSONObject): String? {
        listOf("video_result", "videos", "data").forEach { key ->
            val array = root.optJSONArray(key) ?: return@forEach
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val url = item.optString("url")
                if (url.isNotBlank()) return url
            }
        }
        val results = root.optJSONObject("results") ?: return null
        val videos = results.optJSONArray("videos") ?: return null
        for (index in 0 until videos.length()) {
            val item = videos.optJSONObject(index) ?: continue
            val url = item.optString("url")
            if (url.isNotBlank()) return url
        }
        return null
    }

    /** 取 Base URL 中最后一个 /v数字/ 段（含）之前的前缀，用于拼接轮询地址 */
    private fun versionBaseOf(url: String): String? {
        val match = Regex("/v\\d+/").findAll(url).lastOrNull() ?: return null
        return url.substring(0, match.range.last + 1)
    }

    private suspend fun postForText(
        provider: AiCreationProviderConfig,
        url: String,
        body: String
    ): String = withContext(Dispatchers.IO) {
        val response = okHttpClient.newCallResponse {
            url(url)
            addHeader("Accept", "application/json")
            addHeader("Content-Type", "application/json")
            provider.apiKey.trim().takeIf { it.isNotBlank() }?.let {
                addHeader("Authorization", "Bearer $it")
            }
            AiCreationProviderStore.parseCustomHeaders(provider.headers).forEach { (key, value) ->
                header(key, value)
            }
            postJson(body)
        }
        response.use { rawResponse ->
            val text = rawResponse.body?.string().orEmpty()
            if (!rawResponse.isSuccessful) {
                throw IllegalStateException("HTTP ${rawResponse.code}: ${text.take(300)}")
            }
            text
        }
    }

    private suspend fun getForText(
        provider: AiCreationProviderConfig,
        url: String
    ): String = withContext(Dispatchers.IO) {
        val response = okHttpClient.newCallResponse {
            url(url)
            addHeader("Accept", "application/json")
            provider.apiKey.trim().takeIf { it.isNotBlank() }?.let {
                addHeader("Authorization", "Bearer $it")
            }
            AiCreationProviderStore.parseCustomHeaders(provider.headers).forEach { (key, value) ->
                header(key, value)
            }
        }
        response.use { rawResponse ->
            val text = rawResponse.body?.string().orEmpty()
            if (!rawResponse.isSuccessful) {
                throw IllegalStateException("HTTP ${rawResponse.code}: ${text.take(300)}")
            }
            text
        }
    }

    /** 下载并校验视频内容非空（测试连接不落盘，链路打通即视为成功） */
    private suspend fun downloadVideo(url: String) = withContext(Dispatchers.IO) {
        val response = okHttpClient.newCallResponse { url(url) }
        response.use { rawResponse ->
            require(rawResponse.isSuccessful) { "视频下载失败 HTTP ${rawResponse.code}" }
            val bytes = rawResponse.body?.bytes()
                ?: throw IllegalStateException("视频下载内容为空")
            require(bytes.isNotEmpty()) { "视频下载内容为空" }
        }
    }
}
