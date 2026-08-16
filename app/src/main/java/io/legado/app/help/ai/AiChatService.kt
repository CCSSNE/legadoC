package io.legado.app.help.ai

import android.os.SystemClock
import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.help.config.AppConfig
import io.legado.app.help.http.addHeaders
import io.legado.app.help.http.newCallResponse
import io.legado.app.help.http.okHttpClient
import io.legado.app.help.http.postJson
import io.legado.app.ui.main.ai.AiChatException
import io.legado.app.ui.main.ai.AiChatMessage
import io.legado.app.ui.main.ai.AiProviderConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import okhttp3.Headers
import org.json.JSONArray
import org.json.JSONObject
import splitties.init.appCtx
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.TimeUnit

data class AiStreamProgress(
    val phase: Phase,
    val elapsedMs: Long,
    val outputTokens: Int,
    val outputTokensEstimated: Boolean,
    val tokensPerSecond: Double,
    val reasoningChars: Int,
    val contentChars: Int,
    val sseGapMs: Long = 0L
) {
    enum class Phase {
        THINKING,
        OUTPUT,
        ACTIVITY
    }
}

class AiThinkingInterruptLimitException(
    message: String,
    debugLog: String,
    cause: Throwable? = null
) : AiChatException(message, debugLog, cause)

object AiChatService {

    private const val MAX_TOOL_ROUNDS = 12
    private const val MAX_SEARCH_RESULT_CARDS = 8
    private val requestSequence = AtomicLong(0)
    private val inlineThinkingBlockRegex = Regex(
        "<(think|thinking|analysis|reasoning)>[\\s\\S]*?</\\1>",
        RegexOption.IGNORE_CASE
    )
    private val inlineThinkingOpenTagRegex = Regex(
        "<(think|thinking|analysis|reasoning)>",
        RegexOption.IGNORE_CASE
    )
    private val inlineThinkingCloseTagRegex = Regex(
        "</(think|thinking|analysis|reasoning)>",
        RegexOption.IGNORE_CASE
    )

    private data class ToolCall(
        val id: String,
        val name: String,
        val arguments: String
    )

    private data class ToolCallBuilder(
        var id: String = "",
        var name: String = "",
        val arguments: StringBuilder = StringBuilder()
    )

    private data class ToolEvent(
        val name: String,
        val stage: String,
        val content: String,
        val success: Boolean = true
    )

    private data class AssistantTurn(
        val content: String,
        val toolCalls: List<ToolCall>,
        val rawMessage: JSONObject,
        val reasoningContent: String = ""
    )

    private data class CompletionRequestOptions(
        val temperature: Double? = null,
        val responseFormat: String? = null,
        val thinkingType: String? = null,
        val reasoningEffort: String? = null,
        val requestTemplate: String? = null
    )

    suspend fun chat(messages: List<AiChatMessage>): String {
        return chatStream(messages, onPartial = {})
    }

    suspend fun fetchModels(provider: AiProviderConfig): List<String> {
        val baseUrl = provider.baseUrl.trim()
        require(baseUrl.isNotBlank()) { "Base URL is empty" }
        val response = okHttpClient.newCallResponse {
            url(resolveModelsUrl(baseUrl))
            addHeader("Accept", "application/json")
            provider.apiKey.trim().takeIf { it.isNotBlank() }?.let {
                addHeader("Authorization", "Bearer $it")
            }
            addHeaders(parseCustomHeaders(provider.headers.orEmpty()))
        }
        response.use { rawResponse ->
            val payload = rawResponse.body?.string().orEmpty()
            if (!rawResponse.isSuccessful) {
                throw AiChatException(
                    message = extractError(payload).ifBlank {
                        "${rawResponse.code} ${rawResponse.message}"
                    },
                    debugLog = "url=${resolveModelsUrl(baseUrl)}\nresponse=$payload\n"
                )
            }
            val root = JSONObject(payload)
            val data = root.optJSONArray("data") ?: return emptyList()
            return buildList {
                for (index in 0 until data.length()) {
                    val item = data.optJSONObject(index) ?: continue
                    item.optString("id").trim().takeIf { it.isNotBlank() }?.let(::add)
                }
            }.distinct()
        }
    }

    /**
     * Sends an isolated completion request. It deliberately does not inherit chat history,
     * the global AI system prompt, skills, MCP tools, or any tool loop state.
     */
    suspend fun generateStructuredText(
        provider: AiProviderConfig,
        model: String,
        systemPrompt: String,
        userContent: String,
        temperature: Double = 0.0,
        requestTemplate: String,
        onRequestAccepted: suspend () -> Unit = {},
        onStreamProgress: suspend (AiStreamProgress) -> Unit = {}
    ): String {
        val baseUrl = provider.baseUrl.trim()
        require(baseUrl.isNotBlank()) { "Base URL is empty" }
        require(model.isNotBlank()) { "Model is empty" }
        val messages = listOf(
            JSONObject().apply {
                put("role", "system")
                put("content", systemPrompt)
            },
            JSONObject().apply {
                put("role", "user")
                put("content", userContent)
            }
        )
        val requestLog = StringBuilder().apply {
            append("purpose=structured_completion").append('\n')
            append("url=${resolveChatUrl(baseUrl)}").append('\n')
            append("model=$model").append('\n')
            append("provider=${provider.name}").append('\n')
            append("messageChars=${systemPrompt.length + userContent.length}").append('\n')
        }
        return try {
            val turn = requestCompletionStream(
                baseUrl = baseUrl,
                model = model,
                providerApiKey = provider.apiKey,
                providerHeaders = provider.headers.orEmpty(),
                messages = messages,
                tools = emptyList(),
                requestLog = requestLog,
                round = 1,
                onPartial = {},
                onThinking = {},
                onRequestAccepted = onRequestAccepted,
                onStreamProgress = onStreamProgress,
                options = CompletionRequestOptions(
                    temperature = temperature,
                    responseFormat = "json_object",
                    thinkingType = "disabled",
                    reasoningEffort = "low",
                    requestTemplate = requestTemplate
                ),
                logRequestBody = true
            )
            turn.content.takeIf { it.isNotBlank() } ?: throw AiChatException(
                message = "Empty response",
                debugLog = requestLog.toString()
            )
        } catch (throwable: Throwable) {
            if (throwable is CancellationException) throw throwable
            if (throwable is AiChatException) throw throwable
            throw AiChatException(
                message = throwable.message ?: throwable.javaClass.simpleName,
                debugLog = requestLog.toString(),
                cause = throwable
            )
        }
    }

    /** Sends a minimal completion through the same endpoint used by real AI features. */
    suspend fun testConnection(
        provider: AiProviderConfig,
        model: String,
        requestTemplate: String
    ): String {
        val requestUrl = resolveChatUrl(provider.baseUrl.trim())
        AppLog.putAi(
            "CONNECTION_TEST START\n" +
                "provider=${provider.name}\n" +
                "model=$model\n" +
                "url=$requestUrl"
        )
        return try {
            val response = generateStructuredText(
                provider = provider,
                model = model,
                systemPrompt = "You are a connection test endpoint. Reply with a short JSON confirmation, such as {\"ok\":true}.",
                userContent = "ping",
                requestTemplate = requestTemplate
            )
            AppLog.putAi(
                "CONNECTION_TEST SUCCESS\n" +
                    "provider=${provider.name}\n" +
                    "model=$model\n" +
                    "response=$response"
            )
            response
        } catch (throwable: Throwable) {
            if (throwable is CancellationException) throw throwable
            AppLog.putAi(
                "CONNECTION_TEST FAILED\n" +
                    "provider=${provider.name}\n" +
                    "model=$model\n" +
                    "url=$requestUrl",
                throwable
            )
            throw throwable
        }
    }

    suspend fun chatStream(
        messages: List<AiChatMessage>,
        onPartial: (String) -> Unit,
        onThinking: (String) -> Unit = {},
        onStatus: (JSONObject) -> Unit = {},
        includeStructuredBlocks: Boolean = true
    ): String {
        val provider = AppConfig.aiCurrentProvider
        val modelConfig = AppConfig.aiCurrentModelConfig
        val baseUrl = provider?.baseUrl?.trim().orEmpty()
        val model = modelConfig?.modelId?.trim().orEmpty()
        require(baseUrl.isNotBlank()) { "Base URL is empty" }
        require(model.isNotBlank()) { "Model is empty" }

        val tools = runCatching { AiToolRegistry.resolveAvailableTools() }.getOrDefault(emptyList())
        val conversation = buildConversation(messages)
        val requestLog = StringBuilder().apply {
            append("url=${resolveChatUrl(baseUrl)}").append('\n')
            append("model=$model").append('\n')
            append("provider=${provider?.name.orEmpty()}").append('\n')
            append("tools=${tools.joinToString { it.name }}").append('\n')
        }

        return runCatching {
            executeToolLoop(
                baseUrl = baseUrl,
                model = model,
                providerApiKey = provider?.apiKey.orEmpty(),
                providerHeaders = provider?.headers.orEmpty(),
                conversation = conversation,
                tools = tools,
                requestLog = requestLog,
                onPartial = onPartial,
                onThinking = onThinking,
                onStatus = onStatus,
                includeStructuredBlocks = includeStructuredBlocks
            )
        }.getOrElse { throwable ->
            if (throwable is AiChatException) {
                throw throwable
            }
            throw AiChatException(
                message = throwable.message ?: throwable.javaClass.simpleName,
                debugLog = requestLog.toString(),
                cause = throwable
            )
        }
    }

    private suspend fun executeToolLoop(
        baseUrl: String,
        model: String,
        providerApiKey: String,
        providerHeaders: String,
        conversation: MutableList<JSONObject>,
        tools: List<AiResolvedTool>,
        requestLog: StringBuilder,
        onPartial: (String) -> Unit,
        onThinking: (String) -> Unit,
        onStatus: (JSONObject) -> Unit,
        includeStructuredBlocks: Boolean,
        onStreamProgress: suspend (AiStreamProgress) -> Unit = {}
    ): String {
        val toolMap = tools.associateBy { it.name }
        val searchResultCards = JSONArray()
        val toolEvents = JSONArray()
        repeat(MAX_TOOL_ROUNDS) { round ->
            val assistantTurn = requestCompletionStream(
                baseUrl = baseUrl,
                model = model,
                providerApiKey = providerApiKey,
                providerHeaders = providerHeaders,
                messages = conversation,
                tools = tools,
                requestLog = requestLog,
                round = round + 1,
                onPartial = onPartial,
                onThinking = onThinking,
                onStreamProgress = onStreamProgress
            )
            conversation += assistantTurn.rawMessage
            if (assistantTurn.toolCalls.isEmpty()) {
                val content = assistantTurn.content
                if (content.isBlank()) {
                    throw AiChatException(
                        message = "Empty response",
                        debugLog = requestLog.toString()
                    )
                }
                return if (includeStructuredBlocks) {
                    appendStructuredBlocks(content, searchResultCards, toolEvents)
                } else {
                    content
                }
            }
            assistantTurn.toolCalls.forEach { toolCall ->
                onStatus(
                    JSONObject().apply {
                        put("key", toolCall.id.ifBlank { toolCall.name })
                        put("kind", "tool")
                        put("name", toolCall.name)
                        put("stage", "call")
                        put("label", appCtx.getString(R.string.ai_tool_status_calling))
                        put("content", toolCall.arguments)
                        put("success", true)
                    }
                )
                toolEvents.put(
                    JSONObject().apply {
                        put("name", toolCall.name)
                        put("stage", "call")
                        put("content", toolCall.arguments)
                        put("success", true)
                    }
                )
                val result = executeToolCall(toolCall, toolMap)
                collectSearchResultCards(toolCall, result, searchResultCards)
                val resultSuccess = parseToolResultSuccess(result)
                toolEvents.put(
                    JSONObject().apply {
                        put("name", toolCall.name)
                        put("stage", "result")
                        put("content", result)
                        put("success", resultSuccess)
                    }
                )
                onStatus(
                    JSONObject().apply {
                        put("key", toolCall.id.ifBlank { toolCall.name })
                        put("kind", "tool")
                        put("name", toolCall.name)
                        put("stage", "result")
                        put(
                            "label",
                            appCtx.getString(
                                if (resultSuccess) R.string.ai_tool_status_done else R.string.ai_tool_status_failed
                            )
                        )
                        put("content", result)
                        put("success", resultSuccess)
                    }
                )
                conversation += JSONObject().apply {
                    put("role", "tool")
                    put("tool_call_id", toolCall.id)
                    put("content", result)
                }
            }
        }
        conversation += JSONObject().apply {
            put("role", "system")
            put(
                "content",
                appCtx.getString(R.string.ai_tool_round_limit_system_prompt)
            )
        }
        val finalTurn = requestCompletionStream(
            baseUrl = baseUrl,
            model = model,
            providerApiKey = providerApiKey,
            providerHeaders = providerHeaders,
            messages = conversation,
            tools = emptyList(),
            requestLog = requestLog,
            round = MAX_TOOL_ROUNDS + 1,
            onPartial = onPartial,
            onThinking = onThinking,
            onStreamProgress = onStreamProgress
        )
        if (finalTurn.content.isBlank()) {
            throw AiChatException(
                message = appCtx.getString(R.string.ai_tool_round_limit_summary),
                debugLog = requestLog.toString()
            )
        }
        return if (includeStructuredBlocks) {
            appendStructuredBlocks(finalTurn.content, searchResultCards, toolEvents)
        } else {
            finalTurn.content
        }
    }

    private fun collectSearchResultCards(
        toolCall: ToolCall,
        result: String,
        cards: JSONArray
    ) {
        if (toolCall.name != "search_book_source") return
        runCatching {
            val results = JSONObject(result).optJSONArray("results") ?: return
            for (index in 0 until results.length()) {
                if (cards.length() >= MAX_SEARCH_RESULT_CARDS) break
                val item = results.optJSONObject(index) ?: continue
                if (item.optString("bookUrl").isBlank() || item.optString("origin").isBlank()) continue
                cards.put(JSONObject().apply {
                    put("name", item.optString("name").take(80))
                    put("author", item.optString("author").take(60))
                    put("originName", item.optString("originName").take(60))
                    put("kind", item.optString("kind").take(80))
                    put("intro", item.optString("intro").replace(Regex("\\s+"), " ").trim().take(160))
                    put("latestChapterTitle", item.optString("latestChapterTitle").take(80))
                    put("coverUrl", item.optString("coverUrl"))
                    put("bookUrl", item.optString("bookUrl"))
                    put("origin", item.optString("origin"))
                    put("target", item.optString("target"))
                })
            }
        }
    }

    private fun appendStructuredBlocks(content: String, cards: JSONArray, toolEvents: JSONArray): String {
        if (cards.length() == 0 && toolEvents.length() == 0) return content
        val payload = JSONObject().apply {
            put("type", "search_book_results")
            put("results", cards)
        }
        return buildString {
            append(content.trimEnd())
            if (toolEvents.length() > 0) {
                append("\n\n```legado-tool-events\n")
                append(JSONObject().apply {
                    put("events", toolEvents)
                })
                append("\n```")
            }
            if (cards.length() > 0) {
                append("\n\n```legado-search-results\n")
                append(payload)
                append("\n```")
            }
        }
    }

    private fun parseToolResultSuccess(result: String): Boolean {
        return runCatching {
            JSONObject(result).optBoolean("ok", true)
        }.getOrDefault(true)
    }

    private suspend fun executeToolCall(
        toolCall: ToolCall,
        toolMap: Map<String, AiResolvedTool>
    ): String {
        val enabled = AppConfig.aiEnabledToolNames.ifEmpty { AiToolRegistry.defaultEnabledTools }
        if (toolCall.name !in enabled) {
            return JSONObject().apply {
                put("ok", false)
                put("error", "Tool is disabled: ${toolCall.name}")
            }.toString()
        }
        val resolvedTool = toolMap[toolCall.name]
        if (resolvedTool == null) {
            return JSONObject().apply {
                put("ok", false)
                put("error", "Unknown tool: ${toolCall.name}")
            }.toString()
        }
        return runCatching {
            val arguments = toolCall.arguments.trim().takeIf { it.isNotBlank() }?.let(::JSONObject)
            resolvedTool.execute(arguments)
        }.getOrElse { throwable ->
            JSONObject().apply {
                put("ok", false)
                put("error", throwable.message ?: throwable.javaClass.simpleName)
            }.toString()
        }
    }

    private suspend fun requestCompletionStream(
        baseUrl: String,
        model: String,
        providerApiKey: String,
        providerHeaders: String,
        messages: List<JSONObject>,
        tools: List<AiResolvedTool>,
        requestLog: StringBuilder,
        round: Int,
        onPartial: (String) -> Unit,
        onThinking: (String) -> Unit,
        onRequestAccepted: suspend () -> Unit = {},
        onStreamProgress: suspend (AiStreamProgress) -> Unit = {},
        options: CompletionRequestOptions = CompletionRequestOptions(),
        logRequestBody: Boolean = true
    ): AssistantTurn {
        var interruptCount = 0
        while (true) {
            try {
                return requestCompletionStreamOnce(
                    baseUrl = baseUrl,
                    model = model,
                    providerApiKey = providerApiKey,
                    providerHeaders = providerHeaders,
                    messages = messages,
                    tools = tools,
                    requestLog = requestLog,
                    round = round,
                    onPartial = onPartial,
                    onThinking = onThinking,
                    onRequestAccepted = onRequestAccepted,
                    onStreamProgress = onStreamProgress,
                    options = options,
                    logRequestBody = logRequestBody
                )
            } catch (interrupt: AiThinkingInterruptException) {
                interruptCount++
                val maxInterruptCount = AiRequestTimeoutConfig.thinkingInterruptMaxCount
                AppLog.putAi(
                    "AI THINKING INTERRUPTED\n" +
                        "interruptCount=$interruptCount\n" +
                        "maxInterruptCount=$maxInterruptCount\n" +
                        "thinkingInterruptSeconds=${AiRequestTimeoutConfig.thinkingInterruptSeconds}\n" +
                        "reason=${interrupt.message}"
                )
                requestLog.append("thinkingInterruptCount=")
                    .append(interruptCount)
                    .append('\n')
                if (interruptCount >= maxInterruptCount) {
                    throw AiThinkingInterruptLimitException(
                        message = "AI thinking was interrupted $interruptCount time(s); maximum reached",
                        debugLog = requestLog.toString(),
                        cause = interrupt
                    )
                }
                requestLog.append("thinkingInterruptResend=true\n")
            }
        }
    }

    private suspend fun requestCompletionStreamOnce(
        baseUrl: String,
        model: String,
        providerApiKey: String,
        providerHeaders: String,
        messages: List<JSONObject>,
        tools: List<AiResolvedTool>,
        requestLog: StringBuilder,
        round: Int,
        onPartial: (String) -> Unit,
        onThinking: (String) -> Unit,
        onRequestAccepted: suspend () -> Unit = {},
        onStreamProgress: suspend (AiStreamProgress) -> Unit = {},
        options: CompletionRequestOptions = CompletionRequestOptions(),
        logRequestBody: Boolean = true
    ): AssistantTurn {
        val requestId = requestSequence.incrementAndGet()
        val requestUrl = resolveChatUrl(baseUrl)
        val requestHeaders = formatRequestHeaders(providerApiKey, providerHeaders)
        val requestBody = try {
            options.requestTemplate?.let { template ->
                AiStructuredRequestTemplate.render(
                    template = template,
                    model = model,
                    systemPrompt = messages.firstOrNull { it.optString("role") == "system" }
                        ?.optString("content")
                        .orEmpty(),
                    userContent = messages.firstOrNull { it.optString("role") == "user" }
                        ?.optString("content")
                        .orEmpty()
                )
            } ?: buildRequestBody(messages, model, tools, stream = true, options = options)
        } catch (throwable: Throwable) {
            AppLog.putAi(
                "HTTP REQUEST BUILD FAILED\n" +
                    "requestId=$requestId\n" +
                    "url=$requestUrl\n" +
                    "model=$model\n" +
                    "headers=$requestHeaders\n" +
                    "requestLog=$requestLog",
                throwable
            )
            throw throwable
        }
        val idleTimeoutSeconds = AiRequestTimeoutConfig.sseIdleTimeoutSeconds
        val generationTimeoutSeconds = AiRequestTimeoutConfig.generationTimeoutSeconds
        val thinkingInterruptSeconds = AiRequestTimeoutConfig.thinkingInterruptSeconds
        val generationTimeoutMs = generationTimeoutSeconds * 1_000L
        requestLog.append("round=").append(round).append('\n')
        requestLog.append("sseIdleTimeoutSeconds=").append(idleTimeoutSeconds).append('\n')
        requestLog.append("generationTimeoutSeconds=").append(generationTimeoutSeconds).append('\n')
        requestLog.append("thinkingInterruptSeconds=")
            .append(thinkingInterruptSeconds ?: "<unset>")
            .append('\n')
        requestLog.append("thinkingInterruptMaxCount=")
            .append(AiRequestTimeoutConfig.thinkingInterruptMaxCount)
            .append('\n')
        if (logRequestBody) {
            requestLog.append("request=").append(requestBody).append('\n')
        }
        AppLog.putAi(
            "HTTP REQUEST\n" +
                "requestId=$requestId\n" +
                "url=$requestUrl\n" +
                "model=$model\n" +
                "headers=$requestHeaders\n" +
                "requestBody=$requestBody\n" +
                "requestLog=$requestLog"
        )
        val requestClient = okHttpClient.newBuilder()
            .readTimeout(idleTimeoutSeconds.toLong(), TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.MILLISECONDS)
            .build()
        val response = try {
            withTimeout(generationTimeoutMs) {
                requestClient.newCallResponse {
                    url(requestUrl)
                    addHeader("Accept", "text/event-stream, application/json")
                    addHeader("Content-Type", "application/json")
                    providerApiKey.trim().takeIf { it.isNotBlank() }?.let {
                        addHeader("Authorization", "Bearer $it")
                    }
                    addHeaders(parseCustomHeaders(providerHeaders))
                    postJson(requestBody)
                }
            }
        } catch (throwable: Throwable) {
            val failure = if (throwable is TimeoutCancellationException) {
                AiChatException(
                    message = "AI response timeout before headers after ${generationTimeoutSeconds}s",
                    debugLog = requestLog.toString(),
                    cause = throwable
                )
            } else {
                throwable
            }
            AppLog.putAi(
                "HTTP REQUEST FAILED\n" +
                    "requestId=$requestId\n" +
                    "url=$requestUrl\n" +
                    "headers=$requestHeaders\n" +
                    "requestBody=$requestBody\n" +
                    "requestLog=$requestLog",
                failure
            )
            throw failure
        }
        return try {
            response.use { rawResponse ->
            requestLog.append("status=${rawResponse.code} ${rawResponse.message}").append('\n')
            val responseContentType = rawResponse.header("Content-Type").orEmpty()
            val responseHeaders = formatResponseHeaders(rawResponse.headers)
            requestLog.append("contentType=$responseContentType").append('\n')
            AppLog.putAi(
                "HTTP RESPONSE HEADERS\n" +
                    "requestId=$requestId\n" +
                    "url=$requestUrl\n" +
                    "status=${rawResponse.code} ${rawResponse.message}\n" +
                    "contentType=$responseContentType\n" +
                    "sseIdleTimeoutSeconds=$idleTimeoutSeconds\n" +
                    "generationTimeoutSeconds=$generationTimeoutSeconds\n" +
                    "headers=$responseHeaders"
            )
            val body = rawResponse.body ?: throw AiChatException(
                message = "Empty response body",
                debugLog = requestLog.append("response=<empty body>\n").toString()
            )
            if (!rawResponse.isSuccessful) {
                val payload = body.string()
                AppLog.putAi(
                        "HTTP RESPONSE BODY (ERROR)\n" +
                        "requestId=$requestId\n" +
                        "status=${rawResponse.code} ${rawResponse.message}\n" +
                        "headers=$responseHeaders\n" +
                        "body=$payload"
                )
                throw AiChatException(
                    message = extractError(payload).ifBlank {
                        "${rawResponse.code} ${rawResponse.message}"
                    },
                    debugLog = buildString {
                        append(requestLog)
                        append("status=${rawResponse.code} ${rawResponse.message}").append('\n')
                        append("response=$payload").append('\n')
                    }
                )
            }
            onRequestAccepted()
            val streamStartedAt = SystemClock.elapsedRealtime()
            val rendered = StringBuilder()
            val rawRendered = StringBuilder()
            val reasoningRendered = StringBuilder()
            val rawPayload = StringBuilder()
            val toolCallBuilders = linkedMapOf<Int, ToolCallBuilder>()
            var latestProgress: AiStreamProgress? = null
            var lastProgressLogAt = Long.MIN_VALUE
            var lastStreamEventAt = streamStartedAt
            try {
                withTimeout(generationTimeoutMs) {
                    body.byteStream().bufferedReader().use { reader ->
                        var visibleOutputStarted = false
                        val thinkingDeadlineAt = thinkingInterruptSeconds?.let {
                            streamStartedAt + it * 1_000L
                        }
                        while (true) {
                            val rawLine = readSseLine(
                                reader = reader,
                                thinkingDeadlineAt = if (visibleOutputStarted) null else thinkingDeadlineAt,
                                thinkingInterruptSeconds = thinkingInterruptSeconds
                            )?.trim() ?: break
                            if (rawLine.isEmpty()) continue
                            rawPayload.append(rawLine).append('\n')
                            val payload = when {
                                rawLine.startsWith("data:") -> rawLine.removePrefix("data:").trim()
                                rawLine.startsWith("{") -> rawLine
                                else -> continue
                            }
                            if (payload == "[DONE]") break
                            consumeStreamPayload(
                                payload = payload,
                                rawRendered = rawRendered,
                                rendered = rendered,
                                reasoningRendered = reasoningRendered,
                                toolCallBuilders = toolCallBuilders,
                                onPartial = onPartial,
                                onThinking = onThinking,
                                streamStartedAt = streamStartedAt,
                                onStreamProgress = { progress ->
                                    if (progress.phase == AiStreamProgress.Phase.OUTPUT ||
                                        progress.contentChars > 0
                                    ) {
                                        visibleOutputStarted = true
                                    }
                                    val now = SystemClock.elapsedRealtime()
                                    val enrichedProgress = progress.copy(
                                        sseGapMs = (now - lastStreamEventAt).coerceAtLeast(0L)
                                    )
                                    lastStreamEventAt = now
                                    latestProgress = enrichedProgress
                                    if (lastProgressLogAt == Long.MIN_VALUE || now - lastProgressLogAt >= 1_000L) {
                                        lastProgressLogAt = now
                                        AppLog.putAi(
                                            "HTTP RESPONSE PROGRESS\n" +
                                                "requestId=$requestId\n" +
                                                "phase=${enrichedProgress.phase}\n" +
                                                "elapsedMs=${enrichedProgress.elapsedMs}\n" +
                                                "outputTokens=${enrichedProgress.outputTokens}\n" +
                                                "outputTokensEstimated=${enrichedProgress.outputTokensEstimated}\n" +
                                                "tokensPerSecond=${enrichedProgress.tokensPerSecond}\n" +
                                                "reasoningChars=${enrichedProgress.reasoningChars}\n" +
                                                "contentChars=${enrichedProgress.contentChars}\n" +
                                                "sseGapMs=${enrichedProgress.sseGapMs}"
                                        )
                                    }
                                    onStreamProgress(enrichedProgress)
                                }
                            )
                        }
                    }
                }
            } catch (throwable: Throwable) {
                val failure = when {
                    throwable is TimeoutCancellationException -> AiChatException(
                        message = "AI generation timeout after ${generationTimeoutSeconds}s",
                        debugLog = requestLog.append(
                            "streamTimeout=GENERATION\n" +
                                "generationTimeoutSeconds=$generationTimeoutSeconds\n" +
                                "sseIdleTimeoutSeconds=$idleTimeoutSeconds\n" +
                                "lastEventElapsedMs=${latestProgress?.elapsedMs ?: 0L}\n" +
                                "idleForMs=${(SystemClock.elapsedRealtime() - streamStartedAt) - (latestProgress?.elapsedMs ?: 0L)}\n" +
                                "lastProgress=$latestProgress\n"
                        ).toString(),
                        cause = throwable
                    )
                    throwable is SocketTimeoutException || throwable is InterruptedIOException ->
                        AiChatException(
                            message = "SSE idle timeout after ${idleTimeoutSeconds}s without data",
                            debugLog = requestLog.append(
                                "streamTimeout=SSE_IDLE\n" +
                                    "generationTimeoutSeconds=$generationTimeoutSeconds\n" +
                                    "sseIdleTimeoutSeconds=$idleTimeoutSeconds\n" +
                                    "lastEventElapsedMs=${latestProgress?.elapsedMs ?: 0L}\n" +
                                    "idleForMs=${(SystemClock.elapsedRealtime() - streamStartedAt) - (latestProgress?.elapsedMs ?: 0L)}\n" +
                                    "lastProgress=$latestProgress\n"
                            ).toString(),
                            cause = throwable
                        )
                    else -> throwable
                }
                throw failure
            }
            requestLog.append("response=").append(rawPayload).append('\n')
            AppLog.putAi(
                "HTTP RESPONSE STREAM\n" +
                    "requestId=$requestId\n" +
                    "status=${rawResponse.code} ${rawResponse.message}\n" +
                    "rawSse=$rawPayload\n" +
                    "rendered=${rendered}\n" +
                    "renderedChars=${rendered.length}\n" +
                    "reasoning=${reasoningRendered}\n" +
                    "reasoningChars=${reasoningRendered.length}\n" +
                    "toolCalls=${toolCallBuilders.size}"
            )
            val toolCalls = toolCallBuilders.map { (index, builder) ->
                ToolCall(
                    id = builder.id.ifBlank { "call_$index" },
                    name = builder.name,
                    arguments = builder.arguments.toString().ifBlank { "{}" }
                )
            }.filter { it.name.isNotBlank() }
            if (rendered.isBlank() && toolCalls.isEmpty()) {
                val fallback = runCatching { extractContent(rawPayload.toString()) }.getOrDefault("")
                if (fallback.isNotBlank()) {
                    val visibleFallback = stripInlineThinking(fallback, onThinking)
                    onPartial(visibleFallback)
                    return AssistantTurn(
                        visibleFallback,
                        emptyList(),
                        buildAssistantRawMessage(visibleFallback, emptyList(), reasoningRendered.toString()),
                        reasoningRendered.toString()
                    )
                }
            }
            return AssistantTurn(
                content = rendered.toString(),
                toolCalls = toolCalls,
                rawMessage = buildAssistantRawMessage(rendered.toString(), toolCalls, reasoningRendered.toString()),
                reasoningContent = reasoningRendered.toString()
            )
            }
        } catch (throwable: Throwable) {
            AppLog.putAi(
                "HTTP RESPONSE PROCESSING FAILED\n" +
                    "requestId=$requestId\n" +
                    "url=$requestUrl\n" +
                    "requestLog=$requestLog",
                throwable
            )
            throw throwable
        }
    }

    private fun formatRequestHeaders(providerApiKey: String, rawHeaders: String): String {
        val headers = buildList {
            add("Accept=text/event-stream, application/json")
            add("Content-Type=application/json")
            add(
                "Authorization=" +
                    when {
                        providerApiKey.isBlank() -> "<absent>"
                        AiLogConfig.apiRedactionEnabled -> "Bearer <redacted>"
                        else -> "Bearer $providerApiKey"
                    }
            )
            parseCustomHeaders(rawHeaders).forEach { (name, value) ->
                add("$name=${redactHeaderValue(name, value)}")
            }
        }
        return headers.joinToString(", ")
    }

    private fun formatResponseHeaders(headers: Headers): String {
        return headers.names().joinToString(", ") { name ->
            "$name=${redactHeaderValue(name, headers.values(name).joinToString(","))}"
        }
    }

    private fun redactHeaderValue(name: String, value: String): String {
        if (!AiLogConfig.apiRedactionEnabled) {
            return value
        }
        val normalized = name.lowercase()
        val redacted = normalized.contains("authorization") ||
            normalized.contains("api-key") ||
            normalized.contains("apikey") ||
            normalized.contains("token") ||
            normalized.contains("secret") ||
            normalized.contains("password") ||
            normalized == "set-cookie"
        return if (redacted) "<redacted>" else value
    }

    private fun buildRequestBody(
        messages: List<JSONObject>,
        model: String,
        tools: List<AiResolvedTool>,
        stream: Boolean,
        options: CompletionRequestOptions = CompletionRequestOptions()
    ): String {
        return JSONObject().apply {
            put("model", model)
            put("stream", stream)
            put("messages", JSONArray().apply {
                messages.forEach { put(it) }
            })
            options.temperature?.let { put("temperature", it) }
            options.responseFormat?.let {
                put("response_format", JSONObject().put("type", it))
            }
            options.thinkingType?.let {
                put("thinking", JSONObject().put("type", it))
            }
            options.reasoningEffort?.let {
                put("reasoning_effort", it)
            }
            if (tools.isNotEmpty()) {
                put("tools", JSONArray().apply {
                    tools.forEach { put(it.definition) }
                })
                put("tool_choice", "auto")
            }
        }.toString()
    }

    private suspend fun consumeStreamPayload(
        payload: String,
        rawRendered: StringBuilder,
        rendered: StringBuilder,
        reasoningRendered: StringBuilder,
        toolCallBuilders: MutableMap<Int, ToolCallBuilder>,
        onPartial: (String) -> Unit,
        onThinking: (String) -> Unit,
        streamStartedAt: Long,
        onStreamProgress: suspend (AiStreamProgress) -> Unit
    ) {
        extractError(payload).takeIf { it.isNotBlank() }?.let {
            throw IllegalStateException(it)
        }
        val root = JSONObject(payload)
        val choice = root.optJSONArray("choices")?.optJSONObject(0)
        val delta = choice?.optJSONObject("delta") ?: choice?.optJSONObject("message") ?: JSONObject()
        val reasoningText = extractContentText(delta.opt("reasoning_content"))
            .ifBlank { extractContentText(delta.opt("reasoning")) }
            .ifBlank { extractContentText(delta.opt("thinking")) }
        if (reasoningText.isNotBlank()) {
            reasoningRendered.append(reasoningText)
            onThinking(reasoningText)
        }
        val deltaText = extractContentText(delta.opt("content"))
        if (deltaText.isNotEmpty()) {
            rawRendered.append(deltaText)
            val visibleText = stripInlineThinking(rawRendered.toString(), onThinking)
            if (visibleText != rendered.toString()) {
                rendered.clear()
                rendered.append(visibleText)
                onPartial(visibleText)
            }
        }
        val toolCalls = delta.optJSONArray("tool_calls")
        if (toolCalls != null) {
            for (i in 0 until toolCalls.length()) {
                val toolCall = toolCalls.optJSONObject(i) ?: continue
                val index = toolCall.optInt("index", i)
                val builder = toolCallBuilders.getOrPut(index) { ToolCallBuilder() }
                toolCall.optString("id").takeIf { it.isNotBlank() }?.let { builder.id = it }
                val function = toolCall.optJSONObject("function") ?: continue
                function.optString("name").takeIf { it.isNotBlank() }?.let { builder.name = it }
                val args = function.opt("arguments")
                when (args) {
                    is String -> builder.arguments.append(args)
                    is JSONObject, is JSONArray -> builder.arguments.append(args.toString())
                }
            }
        }
        val reportedTokens = root.optJSONObject("usage")
            ?.optInt("completion_tokens", -1)
            ?.takeIf { it >= 0 }
        val outputChars = reasoningRendered.length + rendered.length
        val outputTokens = reportedTokens ?: estimateOutputTokens(outputChars)
        val elapsedMs = (SystemClock.elapsedRealtime() - streamStartedAt).coerceAtLeast(1L)
        val phase = when {
            reasoningText.isNotBlank() -> AiStreamProgress.Phase.THINKING
            deltaText.isNotBlank() -> AiStreamProgress.Phase.OUTPUT
            else -> AiStreamProgress.Phase.ACTIVITY
        }
        onStreamProgress(
            AiStreamProgress(
                phase = phase,
                elapsedMs = elapsedMs,
                outputTokens = outputTokens,
                outputTokensEstimated = reportedTokens == null,
                tokensPerSecond = outputTokens * 1_000.0 / elapsedMs,
                reasoningChars = reasoningRendered.length,
                contentChars = rendered.length
            )
        )
    }

    private class AiThinkingInterruptException(
        elapsedSeconds: Int
    ) : IllegalStateException(
        "AI thinking exceeded configured interrupt threshold of ${elapsedSeconds}s"
    )

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun readSseLine(
        reader: java.io.BufferedReader,
        thinkingDeadlineAt: Long?,
        thinkingInterruptSeconds: Int?
    ): String? {
        if (thinkingDeadlineAt == null) {
            return reader.readLine()
        }
        val remainingMs = thinkingDeadlineAt - SystemClock.elapsedRealtime()
        if (remainingMs <= 0L) {
            throw AiThinkingInterruptException(
                thinkingInterruptSeconds ?: error("Missing thinking interrupt threshold")
            )
        }
        return coroutineScope {
            val readJob = async(Dispatchers.IO) {
                runInterruptible { reader.readLine() }
            }
            try {
                select {
                    readJob.onAwait { it }
                    onTimeout(remainingMs) {
                        readJob.cancel()
                        reader.close()
                        throw AiThinkingInterruptException(
                            thinkingInterruptSeconds
                                ?: error("Missing thinking interrupt threshold")
                        )
                    }
                }
            } finally {
                readJob.cancel()
            }
        }
    }

    private fun estimateOutputTokens(outputChars: Int): Int {
        return ((outputChars + 3) / 4).coerceAtLeast(0)
    }

    private fun buildAssistantRawMessage(
        content: String,
        toolCalls: List<ToolCall>,
        reasoningContent: String = ""
    ): JSONObject {
        return JSONObject().apply {
            put("role", "assistant")
            put("content", if (content.isBlank()) JSONObject.NULL else content)
            if (reasoningContent.isNotBlank()) {
                put("reasoning_content", reasoningContent)
            }
            if (toolCalls.isNotEmpty()) {
                put(
                    "tool_calls",
                    JSONArray().apply {
                        toolCalls.forEach { toolCall ->
                            put(
                                JSONObject().apply {
                                    put("id", toolCall.id)
                                    put("type", "function")
                                    put(
                                        "function",
                                        JSONObject().apply {
                                            put("name", toolCall.name)
                                            put("arguments", toolCall.arguments)
                                        }
                                    )
                                }
                            )
                        }
                    }
                )
            }
        }
    }

    private fun buildConversation(messages: List<AiChatMessage>): MutableList<JSONObject> {
        val conversation = mutableListOf<JSONObject>()
        conversation += JSONObject().apply {
            put("role", "system")
            put("content", AppConfig.aiSystemPrompt.ifBlank { AppConfig.DEFAULT_AI_SYSTEM_PROMPT })
        }
        AppConfig.aiEnabledSkills.forEach { skill ->
            conversation += JSONObject().apply {
                put("role", "system")
                put(
                    "content",
                    buildString {
                        append("以下是用户启用的真实 SKILL.md，请把它作为当前 agent 的能力规范执行。")
                        append("Skill 名称：")
                        append(skill.name)
                        if (skill.description.isNotBlank()) {
                            append("\nSkill 描述：")
                            append(skill.description)
                        }
                        if (skill.sourceUrl.isNotBlank()) {
                            append("\nSkill 来源：")
                            append(skill.sourceUrl)
                        }
                        append("\n\n")
                        append(skill.content)
                    }
                )
            }
        }
        if (requiresBookshelfTool(messages)) {
            conversation += JSONObject().apply {
                put("role", "system")
                put(
                    "content",
                    "本轮用户请求涉及本地书架、书籍详情、阅读记录、分组、标签或书源搜索。回复正文前必须先调用合适的本地工具；不要只说明将要查询。需要选择书源时先调用 list_book_sources。search_book_source 的结果会由客户端自动渲染成可点击卡片，回复里不要生成链接、不要输出内部 URL、不要手写 Markdown 打开链接，只需要用自然语言简短说明搜索结果。"
                )
            }
        }
        messages.takeLast(12).forEach { message ->
            conversation += JSONObject().apply {
                put(
                    "role",
                    if (message.role == AiChatMessage.Role.USER) "user" else "assistant"
                )
                if (message.role == AiChatMessage.Role.ASSISTANT) {
                    val (visibleContent, reasoningContent) = splitInlineThinking(
                        stripSearchResultBlocks(message.content)
                    )
                    put("content", visibleContent)
                    if (reasoningContent.isNotBlank()) {
                        put("reasoning_content", reasoningContent)
                    }
                } else {
                    put("content", stripSearchResultBlocks(message.content))
                }
            }
        }
        return conversation
    }

    private fun stripSearchResultBlocks(content: String): String {
        return searchResultBlockRegex.replace(content, "").trim()
    }

    private fun requiresBookshelfTool(messages: List<AiChatMessage>): Boolean {
        val content = messages.lastOrNull { it.role == AiChatMessage.Role.USER }
            ?.content
            ?.lowercase()
            .orEmpty()
        if (content.isBlank()) return false
        return listOf(
            "书架",
            "书籍",
            "书名",
            "作者",
            "阅读记录",
            "最近读",
            "在读",
            "简介",
            "书源",
            "分组",
            "标签",
            "分类",
            "整理",
            "批量"
        ).any { content.contains(it) }
    }

    private fun parseAssistantTurn(response: JSONObject): AssistantTurn {
        val message = response.optJSONArray("choices")
            ?.optJSONObject(0)
            ?.optJSONObject("message")
            ?: JSONObject()
        val content = extractContentText(message.opt("content"))
        val reasoningContent = extractContentText(message.opt("reasoning_content"))
            .ifBlank { extractContentText(message.opt("reasoning")) }
            .ifBlank { extractContentText(message.opt("thinking")) }
        val toolCalls = buildList {
            val array = message.optJSONArray("tool_calls") ?: JSONArray()
            for (index in 0 until array.length()) {
                val toolCall = array.optJSONObject(index) ?: continue
                val function = toolCall.optJSONObject("function") ?: continue
                add(
                    ToolCall(
                        id = toolCall.optString("id").ifBlank { "call_$index" },
                        name = function.optString("name"),
                        arguments = extractToolArguments(function.opt("arguments"))
                    )
                )
            }
        }
        return AssistantTurn(
            content = content,
            toolCalls = toolCalls,
            rawMessage = JSONObject().apply {
                put("role", "assistant")
                put("content", if (content.isBlank()) JSONObject.NULL else content)
                if (reasoningContent.isNotBlank()) {
                    put("reasoning_content", reasoningContent)
                }
                if (toolCalls.isNotEmpty()) {
                    put(
                        "tool_calls",
                        JSONArray().apply {
                            toolCalls.forEach { toolCall ->
                                put(
                                    JSONObject().apply {
                                        put("id", toolCall.id)
                                        put("type", "function")
                                        put(
                                            "function",
                                            JSONObject().apply {
                                                put("name", toolCall.name)
                                                put("arguments", toolCall.arguments)
                                            }
                                        )
                                    }
                                )
                            }
                        }
                    )
                }
            },
            reasoningContent = reasoningContent
        )
    }

    private fun parseCustomHeaders(rawHeaders: String): Map<String, String> {
        val text = rawHeaders.trim()
        if (text.isBlank()) return emptyMap()
        runCatching {
            val json = JSONObject(text)
            return buildMap {
                json.keys().forEach { key ->
                    val value = json.optString(key)
                    if (key.isNotBlank() && value.isNotBlank()) put(key, value)
                }
            }
        }
        return text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .mapNotNull { line ->
                val separator = line.indexOf(':').takeIf { it > 0 } ?: line.indexOf('=').takeIf { it > 0 }
                separator?.let {
                    line.substring(0, it).trim() to line.substring(it + 1).trim()
                }
            }
            .filter { it.first.isNotBlank() && it.second.isNotBlank() }
            .toMap()
    }

    private fun resolveChatUrl(baseUrl: String): String {
        val normalized = baseUrl.trim().trimEnd('/')
        return when {
            normalized.endsWith("/chat/completions") -> normalized
            normalized.endsWith("/v1") -> "$normalized/chat/completions"
            else -> "$normalized/v1/chat/completions"
        }
    }

    private fun resolveModelsUrl(baseUrl: String): String {
        val normalized = baseUrl.trim().trimEnd('/')
        return when {
            normalized.endsWith("/models") -> normalized
            normalized.endsWith("/chat/completions") -> normalized.removeSuffix("/chat/completions") + "/models"
            normalized.endsWith("/v1") -> "$normalized/models"
            else -> "$normalized/v1/models"
        }
    }

    private fun extractError(body: String): String {
        if (body.isBlank()) return ""
        return runCatching {
            val root = JSONObject(body)
            root.optJSONObject("error")?.optString("message")
                ?: root.optString("message")
        }.getOrNull().orEmpty()
    }

    private fun extractContent(body: String): String {
        val root = JSONObject(body)
        val choices = root.optJSONArray("choices") ?: return root.optString("response")
        val first = choices.optJSONObject(0) ?: return ""
        val message = first.optJSONObject("message")
        return extractContentText(message?.opt("content"))
            .ifBlank { first.optString("text") }
    }

    private fun extractContentText(content: Any?): String {
        return when (content) {
            is String -> content
            is JSONArray -> contentArrayToText(content)
            is JSONObject -> content.optString("text")
            else -> ""
        }
    }

    private fun stripInlineThinking(
        text: String,
        onThinking: (String) -> Unit
    ): String {
        val (visible, reasoning) = splitInlineThinking(text)
        reasoning.takeIf { it.isNotBlank() }?.let(onThinking)
        return visible.trimStart()
    }

    private fun splitInlineThinking(text: String): Pair<String, String> {
        var visible = text
        val reasoningParts = mutableListOf<String>()
        inlineThinkingBlockRegex.findAll(text).forEach { match ->
            match.groups[2]?.value
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?.let(reasoningParts::add)
        }
        visible = inlineThinkingBlockRegex.replace(visible, "")
        val openMatch = inlineThinkingOpenTagRegex.find(visible)
        if (openMatch != null) {
            val thinking = visible.substring(openMatch.range.last + 1)
                .replace(inlineThinkingCloseTagRegex, "")
                .trim()
            if (thinking.isNotBlank()) {
                reasoningParts += thinking
            }
            visible = visible.substring(0, openMatch.range.first)
        }
        return visible.trimStart() to reasoningParts.joinToString("\n\n")
    }

    private fun extractToolArguments(arguments: Any?): String {
        return when (arguments) {
            is String -> arguments.ifBlank { "{}" }
            is JSONObject -> arguments.toString()
            is JSONArray -> arguments.toString()
            else -> "{}"
        }
    }

    private fun contentArrayToText(content: JSONArray): String {
        return buildString {
            for (index in 0 until content.length()) {
                val part = content.opt(index)
                if (part is JSONObject) {
                    append(part.optString("text"))
                } else if (part is String) {
                    append(part)
                }
            }
        }
    }

    private val searchResultBlockRegex = Regex(
        "```legado-search-results\\s*\\n([\\s\\S]*?)\\n```",
        setOf(RegexOption.MULTILINE)
    )
}
