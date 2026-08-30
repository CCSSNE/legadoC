package io.legado.app.help.tts

import com.google.gson.JsonElement
import com.google.gson.JsonParser
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.ConcurrentRateLimiter
import io.legado.app.help.http.okHttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Protocol
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okio.ByteString
import okio.ByteString.Companion.decodeBase64
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.CoroutineContext

internal data class TtsWebSocketConfig(
    val openMessages: List<String> = emptyList(),
    val textRules: List<TtsWebSocketTextRule> = emptyList(),
    val binaryAudio: Boolean = true,
    val finishOnClose: Boolean = false,
    val connectTimeoutSeconds: Int = 15,
    val firstAudioTimeoutSeconds: Int = 15,
    val idleTimeoutSeconds: Int = 15,
    val finishGraceMillis: Int = 0,
    val maxAudioBytes: Int = DEFAULT_MAX_AUDIO_BYTES,
    val pcm: TtsPcmConfig? = null
) {
    companion object {
        const val DEFAULT_MAX_AUDIO_BYTES = 32 * 1024 * 1024
    }
}

internal data class TtsWebSocketTextRule(
    val matchPath: String,
    val equalsValue: String? = null,
    val notEqualsValue: String? = null,
    val sendMessages: List<String> = emptyList(),
    val audioPath: String? = null,
    val audioEncoding: String? = null,
    val finish: Boolean = false,
    val errorPath: String? = null
)

internal data class TtsWebSocketTextResult(
    val sendMessages: List<String> = emptyList(),
    val audioChunks: List<ByteArray> = emptyList(),
    val finished: Boolean = false,
    val error: String? = null
)

internal class TtsWebSocketProtocol(
    private val config: TtsWebSocketConfig
) {

    fun openMessages(): List<String> = config.openMessages

    fun binaryAudio(bytes: ByteArray): ByteArray? {
        return bytes.takeIf { config.binaryAudio && it.isNotEmpty() }
    }

    fun onText(message: String): TtsWebSocketTextResult {
        val root = runCatching { JsonParser.parseString(message) }.getOrNull()
            ?: return TtsWebSocketTextResult()
        val sendMessages = mutableListOf<String>()
        val audioChunks = mutableListOf<ByteArray>()
        var finished = false
        var error: String? = null
        config.textRules.forEach { rule ->
            val matchValue = root.elementAtPath(rule.matchPath)?.primitiveText()
            val matches = when {
                rule.equalsValue != null -> matchValue == rule.equalsValue
                rule.notEqualsValue != null -> matchValue != null && matchValue != rule.notEqualsValue
                else -> matchValue != null
            }
            if (!matches) return@forEach
            sendMessages += rule.sendMessages
            rule.audioPath?.let { path ->
                root.elementAtPath(path)?.primitiveText()?.let { audioValue ->
                    val bytes = when (rule.audioEncoding.orEmpty().lowercase()) {
                        "", "base64" -> audioValue.decodeBase64()?.toByteArray()
                        "text", "utf8", "utf-8" -> audioValue.toByteArray(Charsets.UTF_8)
                        else -> null
                    }
                    if (bytes != null && bytes.isNotEmpty()) audioChunks.add(bytes)
                }
            }
            if (rule.errorPath != null) {
                error = root.elementAtPath(rule.errorPath)?.primitiveText()
                    ?.takeIf { it.isNotBlank() }
                    ?: "WebSocket TTS 服务返回错误"
            }
            finished = finished || rule.finish
        }
        return TtsWebSocketTextResult(
            sendMessages = sendMessages,
            audioChunks = audioChunks,
            finished = finished,
            error = error
        )
    }
}

internal object TtsWebSocketEngineClient {

    suspend fun execute(
        engine: TtsEngineSetting,
        request: TtsScriptEngineClient.TtsScriptRequest,
        coroutineContext: CoroutineContext
    ): Response = withContext(coroutineContext) {
        ConcurrentRateLimiter(engine).getConcurrentRecord()
        val config = request.webSocketConfig
            ?: throw NoStackTraceException("WebSocket 合成请求缺少 websocket 配置")
        val httpRequest = request.toHttpRequest()
        val webSocketRef = AtomicReference<WebSocket?>()
        var sessionJob: Job? = null
        val input = TtsChunkedInputStream(config.pcm?.wavHeader() ?: byteArrayOf()) {
            webSocketRef.get()?.cancel()
            sessionJob?.cancel()
        }
        val launchedJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                runSession(
                    request = request,
                    config = config,
                    httpRequest = httpRequest,
                    onAudio = input::offer,
                    onWebSocket = webSocketRef::set
                )
                input.finish()
            } catch (e: Throwable) {
                input.fail(e)
                if (e is CancellationException) throw e
            }
        }
        sessionJob = launchedJob
        val parentJob = currentCoroutineContext()[Job]
        val parentCompletion = parentJob?.invokeOnCompletion { cause ->
            if (cause is CancellationException) {
                input.fail(cause)
                launchedJob.cancel(cause)
            }
        }
        launchedJob.invokeOnCompletion { parentCompletion?.dispose() }
        Response.Builder()
            .request(httpRequest)
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .header("Content-Type", request.streamingContentType(config.pcm))
            .body(TtsStreamingResponseBody(request.streamingMediaType(config.pcm), input))
            .build()
    }

    private suspend fun runSession(
        request: TtsScriptEngineClient.TtsScriptRequest,
        config: TtsWebSocketConfig,
        httpRequest: Request,
        onAudio: (ByteArray) -> Unit,
        onWebSocket: (WebSocket) -> Unit = {}
    ) {
        val protocol = TtsWebSocketProtocol(config)
        val client = createWebSocketClient(okHttpClient, config.connectTimeoutSeconds)
        val events = Channel<WebSocketEvent>(Channel.UNLIMITED)
        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                events.trySend(WebSocketEvent.Open)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                events.trySend(WebSocketEvent.Text(text))
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                events.trySend(WebSocketEvent.Binary(bytes.toByteArray()))
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                events.trySend(WebSocketEvent.Closed(code, reason))
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                events.trySend(WebSocketEvent.Failure(t, response?.code))
            }
        }
        val webSocket = client.newWebSocket(httpRequest, listener)
        onWebSocket(webSocket)
        var audioBytes = 0L
        var opened = false
        var receivedAudio = false
        var finished = false
        val startedAt = System.nanoTime()
        var openedAt = 0L
        var lastAudioAt = 0L

        fun appendAudio(bytes: ByteArray) {
            if (bytes.isEmpty()) return
            audioBytes += bytes.size
            if (audioBytes > config.maxAudioBytes.safeMaxAudioBytes()) {
                throw NoStackTraceException("WebSocket TTS 音频超过大小限制")
            }
            onAudio(bytes)
            receivedAudio = true
            lastAudioAt = System.nanoTime()
        }

        fun sendMessages(messages: List<String>) {
            messages.forEach { message ->
                if (!webSocket.send(message)) {
                    throw NoStackTraceException("WebSocket TTS 消息发送失败")
                }
            }
        }

        fun processText(text: String): Boolean {
            val result = protocol.onText(text)
            result.error?.let { throw NoStackTraceException(it) }
            sendMessages(result.sendMessages)
            result.audioChunks.forEach(::appendAudio)
            return result.finished
        }

        suspend fun receiveEvent(timeoutMillis: Long, timeoutMessage: String): WebSocketEvent {
            return withTimeoutOrNull(timeoutMillis.coerceAtLeast(1L)) { events.receive() }
                ?: throw NoStackTraceException(timeoutMessage)
        }

        try {
            while (!finished) {
                val now = System.nanoTime()
                val elapsedMillis = (now - startedAt) / 1_000_000L
                val totalRemaining = request.timeoutMillis?.minus(elapsedMillis)
                    ?: DEFAULT_TOTAL_TIMEOUT_MILLIS - elapsedMillis
                if (totalRemaining <= 0L) {
                    throw NoStackTraceException("WebSocket TTS 请求总超时")
                }
                val phaseRemaining = when {
                    !opened -> config.connectTimeoutSeconds.safeSeconds(15) * 1000L - elapsedMillis
                    !receivedAudio -> config.firstAudioTimeoutSeconds.safeSeconds(15) * 1000L -
                            (now - openedAt) / 1_000_000L
                    else -> config.idleTimeoutSeconds.safeSeconds(15) * 1000L -
                            (now - lastAudioAt) / 1_000_000L
                }
                val timeoutMessage = when {
                    !opened -> "WebSocket TTS 连接超时"
                    !receivedAudio -> "WebSocket TTS 首个音频分片超时"
                    else -> "WebSocket TTS 音频分片等待超时"
                }
                if (phaseRemaining <= 0L) throw NoStackTraceException(timeoutMessage)
                when (val event = receiveEvent(minOf(totalRemaining, phaseRemaining), timeoutMessage)) {
                    WebSocketEvent.Open -> {
                        opened = true
                        openedAt = System.nanoTime()
                        sendMessages(protocol.openMessages())
                    }
                    is WebSocketEvent.Text -> finished = processText(event.value)
                    is WebSocketEvent.Binary -> protocol.binaryAudio(event.value)?.let(::appendAudio)
                    is WebSocketEvent.Closed -> {
                        if (config.finishOnClose && audioBytes > 0) {
                            finished = true
                        } else {
                            throw NoStackTraceException(
                                "WebSocket TTS 连接提前关闭: ${event.code} ${event.reason}".trim()
                            )
                        }
                    }
                    is WebSocketEvent.Failure -> {
                        val status = event.responseCode?.let { " (HTTP $it)" }.orEmpty()
                        throw NoStackTraceException("WebSocket TTS 连接失败$status: ${event.error.message}")
                    }
                }
            }

            val graceMillis = config.finishGraceMillis.coerceIn(0, 2_000).toLong()
            val graceDeadline = System.nanoTime() + graceMillis * 1_000_000L
            while (graceMillis > 0) {
                val remaining = (graceDeadline - System.nanoTime()) / 1_000_000L
                if (remaining <= 0L) break
                when (val event = withTimeoutOrNull(remaining) { events.receive() } ?: break) {
                    is WebSocketEvent.Binary -> protocol.binaryAudio(event.value)?.let(::appendAudio)
                    is WebSocketEvent.Text -> {
                        val result = protocol.onText(event.value)
                        result.error?.let { throw NoStackTraceException(it) }
                        result.audioChunks.forEach(::appendAudio)
                    }
                    is WebSocketEvent.Failure -> throw NoStackTraceException(
                        "WebSocket TTS 连接失败: ${event.error.message}"
                    )
                    else -> Unit
                }
            }
            if (audioBytes == 0L) {
                throw NoStackTraceException("WebSocket TTS 未返回音频数据")
            }
            webSocket.close(1000, null)
        } finally {
            webSocket.cancel()
            events.close()
        }
    }

    private fun TtsScriptEngineClient.TtsScriptRequest.toHttpRequest(): Request {
        return Request.Builder()
            .url(url)
            .apply { headers.forEach { (name, value) -> header(name, value) } }
            .build()
    }

    private fun TtsScriptEngineClient.TtsScriptRequest.streamingContentType(
        pcm: TtsPcmConfig?
    ): String = if (pcm != null) "audio/wav" else audioContentType ?: "application/octet-stream"

    private fun TtsScriptEngineClient.TtsScriptRequest.streamingMediaType(
        pcm: TtsPcmConfig?
    ) = streamingContentType(pcm).toMediaTypeOrNull()

    private const val DEFAULT_TOTAL_TIMEOUT_MILLIS = 60_000L

    internal fun createWebSocketClient(
        baseClient: OkHttpClient,
        connectTimeoutSeconds: Int
    ): OkHttpClient {
        return baseClient.newBuilder().apply {
            // Cronet 等 HTTP 拦截器可能返回脱离 OkHttp Exchange 的 101 响应，
            // RealWebSocket 此时无法取得升级后的 socket。WebSocket 必须直接走 OkHttp。
            interceptors().clear()
            networkInterceptors().clear()
            connectTimeout(connectTimeoutSeconds.safeSeconds(15), TimeUnit.SECONDS)
            readTimeout(0, TimeUnit.MILLISECONDS)
        }.build()
    }

    private fun Int.safeSeconds(defaultValue: Int): Long {
        return takeIf { it > 0 }?.coerceAtMost(300)?.toLong() ?: defaultValue.toLong()
    }

}

private sealed interface WebSocketEvent {
    data object Open : WebSocketEvent
    data class Text(val value: String) : WebSocketEvent
    data class Binary(val value: ByteArray) : WebSocketEvent
    data class Closed(val code: Int, val reason: String) : WebSocketEvent
    data class Failure(val error: Throwable, val responseCode: Int?) : WebSocketEvent
}

private fun JsonElement.elementAtPath(path: String): JsonElement? {
    val tokens = path.trim()
        .removePrefix("$")
        .removePrefix(".")
        .split(".")
        .filter { it.isNotBlank() }
    var current = this
    tokens.forEach { token ->
        val name = token.substringBefore("[")
        if (name.isNotBlank()) {
            current = current.takeIf { it.isJsonObject }
                ?.asJsonObject
                ?.get(name)
                ?: return null
        }
        Regex("""\[(\d+)]""").findAll(token).forEach { match ->
            val index = match.groupValues[1].toIntOrNull() ?: return null
            val array = current.takeIf { it.isJsonArray }?.asJsonArray ?: return null
            if (index !in 0 until array.size()) return null
            current = array[index]
        }
    }
    return current
}

private fun JsonElement.primitiveText(): String? {
    return takeIf { it.isJsonPrimitive }?.asString
}
