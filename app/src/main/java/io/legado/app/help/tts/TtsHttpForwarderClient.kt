package io.legado.app.help.tts

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.legado.app.model.analyzeRule.AnalyzeUrl
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Response
import java.io.InputStream

object TtsHttpForwarderClient {

    fun sampleText(voice: TtsVoice?): String {
        return voice?.sampleText?.takeIf { it.isNotBlank() } ?: DEFAULT_TTS_PREVIEW_TEXT
    }

    fun buildVoicesUrl(engine: TtsEngineSetting): HttpUrl {
        return engine.effectiveVoicesUrl().toHttpUrl()
    }

    fun buildSynthesisUrl(
        engine: TtsEngineSetting,
        text: String,
        voiceId: String? = engine.activeVoiceId,
        speed: Int = engine.effectiveSpeed(),
        volume: Int = engine.effectiveVolume(),
        pitch: Int = engine.effectivePitch()
    ): HttpUrl {
        val builder = buildUrl(engine.baseUrl, engine.synthesisPath).newBuilder()
        builder.addQueryParameter(engine.textParam.ifBlank { "text" }, text)
        if (engine.voiceParam.isNotBlank() && !voiceId.isNullOrBlank()) {
            builder.addQueryParameter(engine.voiceParam, voiceId)
        }
        if (engine.speedParam.isNotBlank()) {
            builder.addQueryParameter(engine.speedParam, speed.coerceIn(0, 100).toString())
        }
        if (engine.volumeParam.isNotBlank()) {
            builder.addQueryParameter(engine.volumeParam, volume.coerceIn(0, 100).toString())
        }
        if (engine.pitchParam.isNotBlank()) {
            builder.addQueryParameter(engine.pitchParam, pitch.coerceIn(0, 100).toString())
        }
        return builder.build()
    }

    suspend fun fetchVoices(engine: TtsEngineSetting): List<TtsVoice> {
        if (!engine.supportsVoiceFetch()) {
            return emptyList()
        }
        val body = AnalyzeUrl(
            engine.effectiveVoicesUrl(),
            source = engine
        ).getStrResponseAwait().body
        return parseVoices(body.orEmpty())
    }

    suspend fun getSynthesisResponse(
        engine: TtsEngineSetting,
        text: String,
        voiceId: String? = engine.activeVoiceId,
        speed: Int = engine.effectiveSpeed(),
        volume: Int = engine.effectiveVolume(),
        pitch: Int = engine.effectivePitch()
    ): Response {
        val voice = TtsEngineStore.voice(engine.id, voiceId)
            ?: engine.effectiveVoices().firstOrNull { it.id == voiceId }
        val analyzeUrl = AnalyzeUrl(
            engine.effectiveSynthesisUrl(),
            speakText = text,
            speakSpeed = speed,
            speakVolume = volume,
            speakPitch = pitch,
            voiceId = voiceId,
            voiceName = voice?.name,
            source = engine,
            readTimeout = 300 * 1000L
        )
        val checkJs = engine.loginCheckJs
        return runCatching {
            analyzeUrl.getResponseAwait().let { response ->
                if (!checkJs.isNullOrBlank()) {
                    analyzeUrl.evalJS(checkJs, response) as Response
                } else {
                    response
                }
            }
        }.getOrElse { throwable ->
            if (!checkJs.isNullOrBlank()) {
                val errResponse = analyzeUrl.getErrResponse(throwable)
                analyzeUrl.evalJS(checkJs, errResponse) as Response
            } else {
                throw throwable
            }
        }
    }

    suspend fun getSynthesisStream(
        engine: TtsEngineSetting,
        text: String,
        voiceId: String? = engine.activeVoiceId,
        speed: Int = engine.effectiveSpeed(),
        volume: Int = engine.effectiveVolume(),
        pitch: Int = engine.effectivePitch()
    ): InputStream {
        return getSynthesisResponse(engine, text, voiceId, speed, volume, pitch).body.byteStream()
    }

    fun parseVoices(body: String): List<TtsVoice> {
        val trimmed = body.trim()
        if (trimmed.isEmpty()) {
            return emptyList()
        }
        return runCatching {
            val element = JsonParser.parseString(trimmed)
            if (!element.isJsonArray) {
                return@runCatching emptyList()
            }
            parseVoiceArray(element.asJsonArray)
        }.getOrDefault(emptyList()).distinctBy { it.id }
    }

    fun audioCacheKey(
        engine: TtsEngineSetting,
        text: String,
        voiceId: String? = engine.activeVoiceId,
        speed: Int = engine.effectiveSpeed(),
        volume: Int = engine.effectiveVolume(),
        pitch: Int = engine.effectivePitch()
    ): String {
        return listOf(
            engine.id,
            engine.effectiveSynthesisUrl(),
            voiceId.orEmpty(),
            speed.coerceIn(0, 100).toString(),
            volume.coerceIn(0, 100).toString(),
            pitch.coerceIn(0, 100).toString(),
            text
        ).joinToString("-|-")
    }

    private fun parseVoiceArray(array: JsonArray): List<TtsVoice> {
        return array.mapNotNull { element ->
            element.takeIf { it.isJsonObject }?.asJsonObject?.let { parseVoiceObject(it) }
        }
    }

    private fun parseVoiceObject(obj: JsonObject): TtsVoice? {
        val id = obj.stringValue("id") ?: return null
        val name = obj.stringValue("name") ?: return null
        return TtsVoice(
            id = id,
            name = name,
            language = obj.stringValue("language"),
            gender = obj.stringValue("gender"),
            style = obj.stringValue("style"),
            tags = obj.tags(),
            sampleText = obj.stringValue("sample_text"),
            extra = obj.get("extra")?.takeIf { it.isJsonObject }?.asJsonObject
        )
    }

    private fun JsonObject.stringValue(name: String): String? {
        return get(name)?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() }
    }

    private fun JsonObject.tags(): List<String> {
        val tagsElement = get("tags") ?: return emptyList()
        return when {
            tagsElement.isJsonArray -> tagsElement.asJsonArray.mapNotNull {
                it.takeIf { item -> item.isJsonPrimitive }?.asString?.takeIf { value ->
                    value.isNotBlank()
                }
            }
            tagsElement.isJsonPrimitive -> tagsElement.asString
                .split(",", "，", "|", "/")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            else -> emptyList()
        }
    }

    private fun buildUrl(baseUrl: String, path: String): HttpUrl {
        val normalizedBase = baseUrl.trim().trimEnd('/').ifBlank { "http://localhost:8774" }
        val normalizedPath = path.trim().ifBlank { "/" }
        val url = if (normalizedPath.startsWith("http://") || normalizedPath.startsWith("https://")) {
            normalizedPath
        } else {
            normalizedBase + "/" + normalizedPath.trimStart('/')
        }
        return url.toHttpUrl()
    }
}
