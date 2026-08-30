package io.legado.app.help.tts

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonSyntaxException
import io.legado.app.BuildConfig
import io.legado.app.exception.NoStackTraceException
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.utils.GSON
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.fromJsonObject
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.ByteString.Companion.decodeBase64
import splitties.init.appCtx
import java.io.InputStream
import kotlinx.coroutines.CancellationException
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

object TtsScriptEngineClient {

    private const val MAX_OPTIONS_CACHE_SIZE = 16
    private val optionsCache = object : LinkedHashMap<String, List<TtsScriptOption>>(
        MAX_OPTIONS_CACHE_SIZE,
        0.75f,
        true
    ) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, List<TtsScriptOption>>?
        ): Boolean = size > MAX_OPTIONS_CACHE_SIZE
    }

    fun sampleText(voice: TtsVoice?): String {
        return voice?.previewText() ?: DEFAULT_TTS_PREVIEW_TEXT
    }

    fun sampleText(engine: TtsEngineSetting, voice: TtsVoice?): String {
        voice?.sampleText?.takeIf { it.isNotBlank() }?.let { return it }
        engine.sampleText?.takeIf { it.isNotBlank() }?.let { return it }
        return DEFAULT_TTS_PREVIEW_TEXT
    }

    fun loadOptions(engine: TtsEngineSetting): List<TtsScriptOption> {
        val cacheKey = "${engine.id}:${MD5Utils.md5Encode(engine.script)}"
        synchronized(optionsCache) {
            optionsCache[cacheKey]
        }?.let { return it }
        val result = callOptionsFunction(engine)
        val options = parseOptionsResult(result)
        synchronized(optionsCache) {
            optionsCache[cacheKey] = options
        }
        return options
    }

    internal fun parseOptionsResult(result: String?): List<TtsScriptOption> {
        val options = result?.let {
            GSON.fromJsonArray<TtsScriptOption>(it)
                .getOrThrow()
        }.orEmpty()
        if (options.any { it.safeKey.isBlank() }) {
            throw JsonSyntaxException("TTS options() contains an option without a key")
        }
        if (options.distinctBy { it.safeKey }.size != options.size) {
            throw JsonSyntaxException("TTS options() contains duplicate option keys")
        }
        return options
    }

    private fun callOptionsFunction(engine: TtsEngineSetting): String? {
        if (engine.script.isBlank()) {
            return null
        }
        val js = """
            ${engine.script}
            (function() {
                if (typeof options !== 'function') {
                    return null;
                }
                var value = options();
                if (value == null) {
                    throw new Error('options() must return an array');
                }
                if (typeof value === 'string') {
                    return value;
                }
                return JSON.stringify(value);
            })();
        """.trimIndent()
        val result = engine.evalJS(js)?.toString() ?: return null
        if (result.isBlank() || result == "null") {
            throw JsonSyntaxException("TTS options() must return an array")
        }
        return result
    }

    suspend fun fetchVoices(engine: TtsEngineSetting): List<TtsVoice> {
        val options = engine.effectiveOptionValues(loadOptions(engine))
        val result = callEngineFunction(
            engine = engine,
            functionName = "voices",
            arguments = listOf(
                GSON.toJson(options),
                GSON.toJson(extensionContext(engine))
            )
        )
            ?: return emptyList()
        return TtsHttpForwarderClient.parseVoices(result)
    }

    suspend fun getSynthesisResponse(
        engine: TtsEngineSetting,
        text: String,
        voiceId: String? = engine.activeVoiceId,
        styleId: String? = null,
        speed: Int = engine.effectiveSpeed(),
        volume: Int = engine.effectiveVolume(),
        pitch: Int = engine.effectivePitch(),
        synthesisContext: TtsSynthesisContext? = null,
        coroutineContext: CoroutineContext = EmptyCoroutineContext
    ): Response {
        return executeSynthesis(
            engine = engine,
            text = text,
            voiceId = voiceId,
            styleId = styleId,
            speed = speed,
            volume = volume,
            pitch = pitch,
            synthesisContext = synthesisContext,
            coroutineContext = coroutineContext,
            aggregateResponse = true
        ).response
    }

    private suspend fun executeSynthesis(
        engine: TtsEngineSetting,
        text: String,
        voiceId: String?,
        styleId: String?,
        speed: Int,
        volume: Int,
        pitch: Int,
        synthesisContext: TtsSynthesisContext?,
        coroutineContext: CoroutineContext,
        aggregateResponse: Boolean
    ): SynthesisExecution {
        val prepared = prepareSynthesisRequest(
            engine = engine,
            text = text,
            voiceId = voiceId,
            styleId = styleId,
            speed = speed,
            volume = volume,
            pitch = pitch,
            synthesisContext = synthesisContext
        )
        val request = prepared.request
        return SynthesisExecution(
            response = executeWithRetry(
                request = request,
                engine = engine,
                text = text,
                speed = speed,
                volume = volume,
                pitch = pitch,
                voiceId = prepared.voiceId,
                voiceName = prepared.voiceName,
                coroutineContext = coroutineContext,
                aggregateResponse = aggregateResponse
            ),
            audioContentType = request.audioContentType ?: engine.contentType
        )
    }

    internal fun buildSynthesisRequest(
        engine: TtsEngineSetting,
        text: String,
        voiceId: String? = engine.activeVoiceId,
        styleId: String? = null,
        speed: Int = engine.effectiveSpeed(),
        volume: Int = engine.effectiveVolume(),
        pitch: Int = engine.effectivePitch(),
        synthesisContext: TtsSynthesisContext? = null
    ): TtsScriptRequest {
        return prepareSynthesisRequest(
            engine = engine,
            text = text,
            voiceId = voiceId,
            styleId = styleId,
            speed = speed,
            volume = volume,
            pitch = pitch,
            synthesisContext = synthesisContext
        ).request
    }

    private fun prepareSynthesisRequest(
        engine: TtsEngineSetting,
        text: String,
        voiceId: String?,
        styleId: String?,
        speed: Int,
        volume: Int,
        pitch: Int,
        synthesisContext: TtsSynthesisContext?
    ): PreparedSynthesisRequest {
        val voice = TtsEngineStore.voice(engine.id, voiceId)
            ?: engine.effectiveVoices().firstOrNull { it.id == voiceId }
        val options = engine.effectiveOptionValues(loadOptions(engine))
        val params = mapOf(
            "speed" to speed.coerceIn(0, 100),
            "volume" to volume.coerceIn(0, 100),
            "pitch" to pitch.coerceIn(0, 100)
        )
        val synthesis = callEngineFunction(
            engine = engine,
            functionName = "synthesize",
            arguments = listOf(
                GSON.toJson(text),
                GSON.toJson(voice.toScriptVoiceArg(voice?.styleById(styleId))),
                GSON.toJson(params),
                GSON.toJson(options),
                GSON.toJson(extensionContext(engine, synthesisContext))
            )
        ) ?: throw NoStackTraceException("脚本未实现 synthesize(text, voice, params, options, ctx)")
        val request = parseSynthesisRequest(synthesis, engine)
        return PreparedSynthesisRequest(
            request = request,
            voiceId = voice?.id ?: voiceId,
            voiceName = voice?.name
        )
    }

    private suspend fun executeWithRetry(
        request: TtsScriptRequest,
        engine: TtsEngineSetting,
        text: String,
        speed: Int,
        volume: Int,
        pitch: Int,
        voiceId: String?,
        voiceName: String?,
        coroutineContext: CoroutineContext,
        aggregateResponse: Boolean
    ): Response {
        val attempts = request.retry.coerceIn(0, 3) + 1
        var lastError: Throwable? = null
        repeat(attempts) {
            try {
                val response = executeRequest(
                    request = request,
                    engine = engine,
                    text = text,
                    speed = speed,
                    volume = volume,
                    pitch = pitch,
                    voiceId = voiceId,
                    voiceName = voiceName,
                    coroutineContext = coroutineContext
                )
                if (!aggregateResponse || (!request.isWebSocket && !request.isSse)) {
                    return response
                }
                return response.use {
                    val contentType = it.body.contentType()
                    val bytes = it.body.bytes()
                    it.newBuilder()
                        .body(bytes.toResponseBody(contentType))
                        .build()
                }
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                lastError = e
            }
        }
        throw lastError ?: NoStackTraceException("脚本合成请求失败")
    }

    private suspend fun executeRequest(
        request: TtsScriptRequest,
        engine: TtsEngineSetting,
        text: String,
        speed: Int,
        volume: Int,
        pitch: Int,
        voiceId: String?,
        voiceName: String?,
        coroutineContext: CoroutineContext
    ): Response {
        if (request.isWebSocket) {
            return TtsWebSocketEngineClient.execute(
                engine = engine,
                request = request,
                coroutineContext = coroutineContext
            )
        }
        val analyzeUrl = AnalyzeUrl(
            request.toAnalyzeUrlRule(),
            speakText = text,
            speakSpeed = speed,
            speakVolume = volume,
            speakPitch = pitch,
            voiceId = voiceId,
            voiceName = voiceName,
            source = engine,
            readTimeout = request.timeoutMillis ?: 300 * 1000L,
            coroutineContext = coroutineContext
        )
        val response = analyzeUrl.getResponseAwait()
        if (request.isSse && response.isSuccessful) {
            return TtsSseEngineClient.transform(response, request)
        }
        return if (request.isJsonResponse) {
            response.toAudioResponse(
                request,
                engine,
                text,
                speed,
                volume,
                pitch,
                voiceId,
                voiceName,
                coroutineContext
            )
        } else {
            response
        }
    }

    suspend fun getSynthesisStream(
        engine: TtsEngineSetting,
        text: String,
        voiceId: String? = engine.activeVoiceId,
        styleId: String? = null,
        speed: Int = engine.effectiveSpeed(),
        volume: Int = engine.effectiveVolume(),
        pitch: Int = engine.effectivePitch(),
        synthesisContext: TtsSynthesisContext? = null,
        coroutineContext: CoroutineContext = EmptyCoroutineContext
    ): InputStream {
        val execution = executeSynthesis(
            engine = engine,
            text = text,
            voiceId = voiceId,
            styleId = styleId,
            speed = speed,
            volume = volume,
            pitch = pitch,
            synthesisContext = synthesisContext,
            coroutineContext = coroutineContext,
            aggregateResponse = false
        )
        return execution.response.requireAudioStream(execution.audioContentType)
    }

    private fun Response.requireAudioStream(expectedAudioContentType: String?): InputStream {
        headers["Content-Type"]?.let { rawContentType ->
            val contentType = rawContentType.substringBefore(';').trim()
            val expected = expectedAudioContentType.orEmpty()
            if (contentType == "application/json" || contentType.startsWith("text/")) {
                throw NoStackTraceException(body.string())
            }
            if (expected.isNotBlank() && !contentType.matches(expected.toRegex())) {
                throw NoStackTraceException("TTS服务器返回错误：${body.string()}")
            }
        }
        return body.byteStream()
    }

    fun audioCacheKey(
        engine: TtsEngineSetting,
        text: String,
        voiceId: String? = engine.activeVoiceId,
        styleId: String? = null,
        speed: Int = engine.effectiveSpeed(),
        volume: Int = engine.effectiveVolume(),
        pitch: Int = engine.effectivePitch(),
        synthesisContext: TtsSynthesisContext? = null
    ): String {
        return listOf(
            engine.id,
            MD5Utils.md5Encode16(engine.script),
            GSON.toJson(engine.optionValues),
            voiceId.orEmpty(),
            styleId.orEmpty(),
            speed.coerceIn(0, 100).toString(),
            volume.coerceIn(0, 100).toString(),
            pitch.coerceIn(0, 100).toString(),
            GSON.toJson(synthesisContext),
            text
        ).joinToString("-|-")
    }

    private fun callEngineFunction(
        engine: TtsEngineSetting,
        functionName: String,
        arguments: List<String> = emptyList()
    ): String? {
        if (engine.script.isBlank()) {
            return null
        }
        val argumentList = arguments.joinToString(", ")
        val js = """
            ${engine.script}
            (function() {
                if (typeof $functionName !== 'function') {
                    return null;
                }
                var value = $functionName($argumentList);
                if (value == null) {
                    return null;
                }
                if (typeof value === 'string') {
                    return value;
                }
                return JSON.stringify(value);
            })();
        """.trimIndent()
        return engine.evalJS(js)?.toString()?.takeIf { it.isNotBlank() && it != "null" }
    }

    private fun extensionContext(
        engine: TtsEngineSetting,
        synthesisContext: TtsSynthesisContext? = null
    ): Map<String, Any?> {
        return mapOf(
            "engine" to mapOf(
                "id" to engine.id,
                "name" to engine.name
            ),
            "app" to mapOf(
                "packageName" to appCtx.packageName,
                "versionName" to BuildConfig.VERSION_NAME
            ),
            "synthesis" to synthesisContext
        )
    }

    private fun TtsVoice?.toScriptVoiceArg(selectedStyle: TtsVoiceStyle?): Map<String, Any?> {
        return mapOf(
            "id" to this?.id.orEmpty(),
            "name" to this?.name.orEmpty(),
            "language" to this?.language,
            "gender" to this?.gender,
            "style" to (selectedStyle?.displayName ?: this?.style),
            "style_id" to selectedStyle?.id,
            "style_value" to selectedStyle?.scriptValue,
            "style_tag" to selectedStyle?.tag,
            "selected_style" to selectedStyle,
            "tags" to this?.tags.orEmpty(),
            "sample_text" to this?.sampleText,
            "extra" to this?.extra
        )
    }

    internal fun parseSynthesisRequest(result: String, engine: TtsEngineSetting): TtsScriptRequest {
        val trimmed = result.trim()
        if (!trimmed.startsWith("{")) {
            return TtsScriptRequest(url = trimmed, audioContentType = engine.contentType)
        }
        val obj = GSON.fromJsonObject<JsonObject>(trimmed).getOrNull()
            ?: JsonParser.parseString(trimmed).asJsonObject
        val legacyContentType = obj.stringValue("contentType") ?: obj.stringValue("content_type")
        return TtsScriptRequest(
            url = obj.stringValue("url")
                ?: throw NoStackTraceException("synthesize(text, voice, params, options, ctx) 缺少 url"),
            method = obj.stringValue("method"),
            headers = obj.get("headers")?.takeIf { it.isJsonObject }?.asJsonObject
                ?.entrySet()
                ?.associate { it.key to it.value.asString }
                .orEmpty(),
            body = obj.get("body")?.let {
                if (it.isJsonPrimitive) it.asString else GSON.toJson(it)
            },
            requestContentType = obj.stringValue("requestContentType")
                ?: obj.stringValue("request_content_type"),
            audioContentType = obj.stringValue("audioContentType")
                ?: obj.stringValue("audio_content_type")
                ?: obj.stringValue("audioType")
                ?: obj.stringValue("audio_type")
                ?: legacyContentType
                ?: engine.contentType,
            responseType = obj.stringValue("responseType")
                ?: obj.stringValue("response_type")
                ?: if (obj.stringValue("audioExtract") != null || obj.stringValue("audio_extract") != null) {
                    "json"
                } else {
                    "audio"
                },
            audioExtract = obj.stringValue("audioExtract")
                ?: obj.stringValue("audio_extract")
                ?: obj.stringValue("audioPath")
                ?: obj.stringValue("audio_path"),
            audioEncoding = obj.stringValue("audioEncoding")
                ?: obj.stringValue("audio_encoding"),
            timeoutSeconds = obj.intValue("timeout")
                ?: obj.intValue("timeoutSeconds")
                ?: obj.intValue("timeout_seconds"),
            retry = obj.intValue("retry") ?: 0,
            transport = obj.stringValue("transport")
                ?: when {
                    obj.stringValue("url")?.startsWith("ws", ignoreCase = true) == true -> {
                        "websocket"
                    }
                    obj.get("stream")?.isJsonObject == true -> "sse"
                    else -> "http"
                },
            webSocketConfig = obj.get("websocket")
                ?.takeIf { it.isJsonObject }
                ?.asJsonObject
                ?.toWebSocketConfig(),
            sseConfig = obj.get("stream")
                ?.takeIf { it.isJsonObject }
                ?.asJsonObject
                ?.toSseConfig()
        )
    }

    private fun JsonObject.stringValue(name: String): String? {
        return get(name)?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() }
    }

    private fun JsonObject.intValue(name: String): Int? {
        return get(name)?.takeIf { it.isJsonPrimitive }?.let {
            runCatching { it.asInt }.getOrNull()
        }
    }

    private fun JsonObject.booleanValue(name: String): Boolean? {
        return get(name)?.takeIf { it.isJsonPrimitive }?.let {
            runCatching { it.asBoolean }.getOrNull()
        }
    }

    private fun JsonObject.toWebSocketConfig(): TtsWebSocketConfig {
        return TtsWebSocketConfig(
            openMessages = get("openMessages").toMessageList(),
            textRules = get("textRules")
                ?.takeIf { it.isJsonArray }
                ?.asJsonArray
                ?.mapNotNull { element ->
                    element.takeIf { it.isJsonObject }?.asJsonObject?.toWebSocketTextRule()
                }
                .orEmpty(),
            binaryAudio = booleanValue("binaryAudio") ?: true,
            finishOnClose = booleanValue("finishOnClose") ?: false,
            connectTimeoutSeconds = intValue("connectTimeout") ?: 15,
            firstAudioTimeoutSeconds = intValue("firstAudioTimeout") ?: 15,
            idleTimeoutSeconds = intValue("idleTimeout") ?: 15,
            finishGraceMillis = intValue("finishGrace") ?: 0,
            maxAudioBytes = intValue("maxAudioBytes")
                ?: TtsWebSocketConfig.DEFAULT_MAX_AUDIO_BYTES,
            pcm = get("pcm")
                ?.takeIf { it.isJsonObject }
                ?.asJsonObject
                ?.toPcmConfig()
        )
    }

    private fun JsonObject.toSseConfig(): TtsSseConfig {
        return TtsSseConfig(
            dataPrefix = stringValue("dataPrefix") ?: "data:",
            doneData = stringValue("doneData") ?: "[DONE]",
            finishOnEof = booleanValue("finishOnEof") ?: true,
            textRules = get("textRules")
                ?.takeIf { it.isJsonArray }
                ?.asJsonArray
                ?.mapNotNull { element ->
                    element.takeIf { it.isJsonObject }?.asJsonObject?.toWebSocketTextRule()
                }
                .orEmpty(),
            maxAudioBytes = intValue("maxAudioBytes")
                ?: TtsWebSocketConfig.DEFAULT_MAX_AUDIO_BYTES,
            pcm = get("pcm")
                ?.takeIf { it.isJsonObject }
                ?.asJsonObject
                ?.toPcmConfig()
        )
    }

    private fun JsonObject.toPcmConfig(): TtsPcmConfig? {
        val sampleRate = intValue("sampleRate") ?: intValue("sample_rate") ?: return null
        return TtsPcmConfig(
            sampleRate = sampleRate,
            channels = intValue("channels") ?: 1,
            bitsPerSample = intValue("bitsPerSample") ?: intValue("bits_per_sample") ?: 16
        )
    }

    private fun JsonObject.toWebSocketTextRule(): TtsWebSocketTextRule? {
        val matchPath = stringValue("matchPath") ?: return null
        return TtsWebSocketTextRule(
            matchPath = matchPath,
            equalsValue = get("equals")?.takeIf { it.isJsonPrimitive }?.asString,
            notEqualsValue = get("notEquals")?.takeIf { it.isJsonPrimitive }?.asString,
            sendMessages = get("sendMessages").toMessageList(),
            audioPath = stringValue("audioPath"),
            audioEncoding = stringValue("audioEncoding"),
            finish = booleanValue("finish") ?: false,
            errorPath = stringValue("errorPath")
        )
    }

    private fun JsonElement?.toMessageList(): List<String> {
        return this
            ?.takeIf { it.isJsonArray }
            ?.asJsonArray
            ?.mapNotNull { element ->
                when {
                    element.isJsonPrimitive -> element.asString.takeIf { it.isNotBlank() }
                    element.isJsonObject || element.isJsonArray -> GSON.toJson(element)
                    else -> null
                }
            }
            .orEmpty()
    }

    private suspend fun Response.toAudioResponse(
        request: TtsScriptRequest,
        engine: TtsEngineSetting,
        text: String,
        speed: Int,
        volume: Int,
        pitch: Int,
        voiceId: String?,
        voiceName: String?,
        coroutineContext: CoroutineContext
    ): Response {
        val bodyText = body.string()
        val audioValue = extractAudioValue(bodyText, request.audioExtract)
            ?: throw NoStackTraceException("脚本合成响应未找到音频字段: ${request.audioExtract.orEmpty()}")
        return when (request.normalizedAudioEncoding) {
            "url" -> {
                val followRequest = request.copy(
                    url = audioValue,
                    method = "GET",
                    body = null,
                    responseType = "audio",
                    audioExtract = null,
                    audioEncoding = null,
                    retry = 0
                )
                executeRequest(
                    request = followRequest,
                    engine = engine,
                    text = text,
                    speed = speed,
                    volume = volume,
                    pitch = pitch,
                    voiceId = voiceId,
                    voiceName = voiceName,
                    coroutineContext = coroutineContext
                )
            }
            "base64", "" -> {
                val audioBytes = audioValue.decodeBase64()?.toByteArray()
                    ?: throw NoStackTraceException("脚本合成响应音频字段不是有效 Base64")
                newBuilder()
                    .body(audioBytes.toResponseBody(request.audioContentType?.toMediaTypeOrNull()))
                    .header("Content-Type", request.audioContentType ?: "application/octet-stream")
                    .build()
            }
            else -> {
                val audioBytes = audioValue.toByteArray()
                newBuilder()
                    .body(audioBytes.toResponseBody(request.audioContentType?.toMediaTypeOrNull()))
                    .header("Content-Type", request.audioContentType ?: "application/octet-stream")
                    .build()
            }
        }
    }

    internal fun extractAudioValue(body: String, path: String?): String? {
        val root = runCatching { JsonParser.parseString(body) }.getOrNull() ?: return null
        path?.takeIf { it.isNotBlank() }?.let { explicitPath ->
            return root.extractByPath(explicitPath)
        }
        return when {
            root.isJsonPrimitive -> root.asString.takeIf { it.isNotBlank() }
            root.isJsonObject -> {
                val obj = root.asJsonObject
                listOf("audio", "audioContent", "audio_content", "data", "url", "result")
                    .firstNotNullOfOrNull { key ->
                        obj.get(key)?.extractStringValue()
                    }
            }
            else -> null
        }
    }

    private fun JsonElement.extractByPath(path: String): String? {
        val tokens = path.trim()
            .removePrefix("$")
            .removePrefix(".")
            .split(".")
            .filter { it.isNotBlank() }
        var current: JsonElement = this
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
                if (index !in 0 until array.size()) {
                    return null
                }
                current = array[index]
            }
        }
        return current.extractStringValue()
    }

    private fun JsonElement.extractStringValue(): String? {
        return when {
            isJsonPrimitive -> asString.takeIf { it.isNotBlank() }
            isJsonObject -> asJsonObject.get("value")?.extractStringValue()
                ?: asJsonObject.get("audio")?.extractStringValue()
                ?: asJsonObject.get("url")?.extractStringValue()
            else -> null
        }
    }

    internal data class TtsScriptRequest(
        val url: String,
        val method: String? = null,
        val headers: Map<String, String> = emptyMap(),
        val body: String? = null,
        val requestContentType: String? = null,
        val audioContentType: String? = null,
        val responseType: String? = "audio",
        val audioExtract: String? = null,
        val audioEncoding: String? = null,
        val timeoutSeconds: Int? = null,
        val retry: Int = 0,
        val transport: String = "http",
        val webSocketConfig: TtsWebSocketConfig? = null,
        val sseConfig: TtsSseConfig? = null
    ) {
        val isJsonResponse: Boolean
            get() = responseType.orEmpty().equals("json", ignoreCase = true)

        val normalizedAudioEncoding: String
            get() = audioEncoding.orEmpty().trim().lowercase()

        val isWebSocket: Boolean
            get() = transport.equals("websocket", ignoreCase = true) ||
                    url.startsWith("ws://", ignoreCase = true) ||
                    url.startsWith("wss://", ignoreCase = true)

        val isSse: Boolean
            get() = transport.equals("sse", ignoreCase = true) ||
                    transport.equals("event-stream", ignoreCase = true)

        val timeoutMillis: Long?
            get() = timeoutSeconds
                ?.takeIf { it > 0 }
                ?.coerceAtMost(300)
                ?.times(1000L)

        fun toAnalyzeUrlRule(): String {
            val option = JsonObject()
            method?.takeIf { it.isNotBlank() }?.let { option.addProperty("method", it) }
            val requestHeaders = headers.toMutableMap()
            requestContentType?.takeIf { it.isNotBlank() }?.let { contentType ->
                val hasContentType = requestHeaders.keys.any { it.equals("Content-Type", ignoreCase = true) }
                if (!hasContentType) {
                    requestHeaders["Content-Type"] = contentType
                }
            }
            if (requestHeaders.isNotEmpty()) {
                option.add("headers", JsonParser.parseString(GSON.toJson(requestHeaders)))
            }
            body?.let { option.addProperty("body", it) }
            if (option.size() == 0) {
                return url
            }
            return "$url,${GSON.toJson(option)}"
        }
    }

    private data class SynthesisExecution(
        val response: Response,
        val audioContentType: String?
    )

    private data class PreparedSynthesisRequest(
        val request: TtsScriptRequest,
        val voiceId: String?,
        val voiceName: String?
    )
}
