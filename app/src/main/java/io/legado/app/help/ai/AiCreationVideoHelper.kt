package io.legado.app.help.ai

import io.legado.app.help.http.newCallResponse
import io.legado.app.help.http.okHttpClient
import io.legado.app.help.http.postJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 视频供应商请求：按供应商配置渲染请求模板，提交生成任务并等到结果。
 * 按提交响应自动识别两类异步协议：
 * - 智谱 CogVideoX：提交返回 {id, task_status}，轮询 GET <版本前缀>/async-result/{id}，
 *   task_status: PROCESSING / SUCCESS / FAIL，结果在 video_result[].url
 * - 硅基流动 Wan：提交返回 {requestId}，轮询 POST <版本前缀>/video/status（body {requestId}），
 *   status: InQueue / InProgress / Succeed / Failed，结果在 results.videos[].url
 * 同步型接口（响应直接携带视频链接）则跳过轮询直接下载。
 */
object AiCreationVideoHelper {

    private const val POLL_INTERVAL_MS = 5_000L

    //视频生成耗时较长：轮询上限 8 分钟，超时如实报错并附任务号，不伪装成功
    private const val POLL_TIMEOUT_MS = 8 * 60_000L

    /**
     * 生成一个视频：渲染请求模板（运行值完整传入；测试才取定义默认值）→ 提交 → 轮询 →
     * 下载 mp4 落盘（写入工作流元数据），返回文件名。真实生成与测试连接共用本入口。
     */
    suspend fun generateVideo(
        provider: AiCreationProviderConfig,
        modelId: String,
        prompt: String,
        extraValues: Map<String, String> = emptyMap(),
        llmInput: String = ""
    ): String = withContext(Dispatchers.IO) {
        check(provider.requestTemplate.isNotBlank()) {
            "当前视频供应商「${provider.name}」的视频请求模板为空"
        }
        val variables = AiCreationVariables.parse(provider.variablesJson)
        val tokens = buildMap {
            put("model", modelId)
            put("prompt", prompt)
            put("n", "1")
            variables.forEach { variable ->
                put(variable.key, extraValues[variable.key] ?: variable.effectiveValue(null))
            }
        }
        val body = AiCreationProviderStore.renderRequestTemplate(provider.requestTemplate, tokens)
        //工作流溯源快照：变量与请求体都是填好实际值的成品，不含 API Key
        val workflow = AiCreationWorkflow(
            type = AiCreationWorkflow.TYPE_VIDEO,
            providerName = provider.name,
            baseUrl = provider.baseUrl,
            model = modelId,
            variables = tokens.filterKeys { it !in setOf("model", "prompt", "n") },
            llmInput = llmInput,
            prompt = prompt,
            request = body
        )

        val submitText = postForText(provider, provider.baseUrl, body)
        val submitRoot = JSONObject(submitText)
        //同步型接口：响应直接携带视频链接
        val url = extractVideoUrl(submitRoot) ?: when {
            submitRoot.has("requestId") ->
                pollSiliconFlow(provider, submitRoot.optString("requestId"))
            submitRoot.has("id") && submitRoot.has("task_status") ->
                pollBigModel(provider, submitRoot.optString("id"))
            else -> throw IllegalStateException("视频提交响应无法识别：${submitText.take(300)}")
        }
        AiCreationImageFile.saveVideoBytes(downloadVideoBytes(url), workflow)
    }

    /** 测试连接：真实生成一个视频验证链路，验证后删除文件，不进创作库 */
    suspend fun testConnection(
        provider: AiCreationProviderConfig,
        modelId: String
    ): Unit = withContext(Dispatchers.IO) {
        val fileName = generateVideo(
            provider,
            modelId,
            AiCreationProviderStore.VIDEO_TEST_PROMPT
        )
        AiCreationImageFile.delete(fileName)
    }

    private suspend fun pollBigModel(provider: AiCreationProviderConfig, taskId: String): String {
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
                    return url
                }
                "FAIL" -> throw IllegalStateException("视频生成失败：${root.toString().take(300)}")
            }
            if (System.currentTimeMillis() > deadline) {
                throw IllegalStateException("视频生成超时（任务仍在处理中）：$taskId")
            }
        }
    }

    private suspend fun pollSiliconFlow(
        provider: AiCreationProviderConfig,
        requestId: String
    ): String {
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
                    return url
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

    /** 下载视频内容（返回字节，落盘由调用方决定） */
    private suspend fun downloadVideoBytes(url: String): ByteArray = withContext(Dispatchers.IO) {
        val response = okHttpClient.newCallResponse { url(url) }
        response.use { rawResponse ->
            require(rawResponse.isSuccessful) { "视频下载失败 HTTP ${rawResponse.code}" }
            val bytes = rawResponse.body?.bytes()
                ?: throw IllegalStateException("视频下载内容为空")
            require(bytes.isNotEmpty()) { "视频下载内容为空" }
            bytes
        }
    }
}
