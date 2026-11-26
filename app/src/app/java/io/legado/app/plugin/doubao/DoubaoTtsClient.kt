package io.legado.app.plugin.doubao

import android.util.Base64
import io.legado.app.constant.AppLog
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

/**
 * 豆包 TTS 原生引擎（不再经过插件 JS 运行时）。
 *
 * 协议来自 Z·阅读 插件实测逆向：
 * - 连接 `wss://audio5-normal-hl.myparallelstory.com/internal/api/v1/ws?ssmix=&aid=&device_id=`
 * - 连接后先发 `StartTask`（namespace=BidirectionalTTS，payload 里含 audio_config/speaker）
 * - 收到 `TaskStarted` 后发正文 `{"payload":"{\"text\":...}"}` 与 `FinishTask`
 * - 音频帧两种：text JSON `{type:3, buffer:base64}`，或 binary 直接字节；均为 PCM 24k 单声道 16bit
 * - `TaskFinished` 后 300ms 收尾，把分片拼成 WAV 返回
 */
object DoubaoTtsClient {

    private const val WS_URL = "wss://audio5-normal-hl.myparallelstory.com/internal/api/v1/ws"
    private const val APP_KEY = "WQuVLKMGRo"
    private const val SAMPLE_RATE = 24000
    private const val CHANNELS = 1
    private const val BITS = 16
    private const val SILENCE_MS = 100
    private const val FIRST_CHUNK_TIMEOUT_MS = 15_000L
    private const val TOTAL_TIMEOUT_MS = 60_000L

    /**
     * WebSocket 必须用干净的 client：全局 okHttpClient 带缓存/Cookie/日志拦截器，
     * 会消费掉 101 握手的响应体，报 "Web Socket socket missing: bad interceptor?"。
     */
    private val wsClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(0, java.util.concurrent.TimeUnit.MILLISECONDS)
            .writeTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .pingInterval(20, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    }

    data class Result(val wav: ByteArray, val sampleRate: Int)

    fun synthesize(
        text: String,
        voiceId: String,
        speechRate: Float = 1f,
        pitchOffset: Int = 0,
        loudnessRate: Int = 0,
        timeoutMs: Long = TOTAL_TIMEOUT_MS
    ): Result {
        io.legado.app.help.tts.ReadAloudDebugLog.add("豆包", "开始合成 voice=${DoubaoVoices.describe(voiceId)} text=${text.take(20)}…")
        val chunks = java.io.ByteArrayOutputStream()
        val done = CountDownLatch(1)
        val finished = AtomicBoolean(false)
        val failure = java.util.concurrent.atomic.AtomicReference<String?>(null)
        var taskId: String? = null
        val lock = Any()

        fun fail(reason: String) {
            if (finished.compareAndSet(false, true)) {
                failure.set(reason)
                done.countDown()
            }
        }

        val url = buildString {
            append(WS_URL)
            append("?ssmix=&aid=").append(randomId())
            append("&device_id=").append(randomId())
        }
        val request = Request.Builder().url(url).build()
        val socket = wsClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                runCatching { webSocket.send(startTaskJson(voiceId, speechRate, pitchOffset, loudnessRate)) }
                    .onFailure { fail("发送 StartTask 失败：${it.localizedMessage}") }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                runCatching { handleText(text, webSocket) }.onFailure {
                    AppLog.put("豆包 TTS 文本帧处理失败\n${it.localizedMessage}")
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                synchronized(lock) { chunks.write(bytes.toByteArray()) }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                fail("连接失败：${t.localizedMessage}")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (!finished.get()) {
                    // 服务端主动关闭但已收到音频：视为完成
                    if (chunks.size() > 0) {
                        if (finished.compareAndSet(false, true)) done.countDown()
                    } else {
                        fail("连接被关闭（code=$code）且未收到音频")
                    }
                }
            }

            private fun handleText(message: String, ws: WebSocket) {
                val json = runCatching { JSONObject(message) }.getOrNull() ?: return
                val event = json.optString("event")
                when {
                    event == "TaskStarted" -> {
                        synchronized(lock) { taskId = json.optString("task_id").takeIf { it.isNotBlank() } }
                        synchronized(lock) { taskId }?.let { }
                        ws.send(JSONObject().put("payload", JSONObject().put("text", text).toString()).toString())
                        ws.send(JSONObject().put("appkey", APP_KEY).put("event", "FinishTask")
                            .put("namespace", "BidirectionalTTS").toString())
                    }
                    event == "TaskFinished" -> {
                        Thread {
                            runCatching { Thread.sleep(300) }
                            if (finished.compareAndSet(false, true)) done.countDown()
                        }.start()
                    }
                    json.optInt("type", -1) == 3 -> {
                        val buffer = json.optString("buffer")
                        if (buffer.isNotBlank()) {
                            runCatching { Base64.decode(buffer, Base64.DEFAULT) }
                                .onSuccess { if (it.isNotEmpty()) synchronized(lock) { chunks.write(it) } }
                        }
                    }
                    else -> {
                        val statusCode = json.optInt("status_code", 20000000)
                        if (statusCode != 20000000) {
                            fail("服务端错误：${json.optString("status_text").ifBlank { message }}")
                        }
                    }
                }
            }
        })

            io.legado.app.help.tts.ReadAloudDebugLog.add("豆包", "等待完成…")
    val finishedInTime = done.await(timeoutMs, TimeUnit.MILLISECONDS)
        socket.close(1000, null)
        failure.get()?.let { error ->
            io.legado.app.help.tts.ReadAloudDebugLog.add("豆包", "失败（${DoubaoVoices.describe(voiceId)}）：$error")
            throw IllegalStateException(error)
        }
        if (!finishedInTime) {
            throw IllegalStateException("豆包 TTS 超时（${timeoutMs}ms 未完成）")
        }
        val pcm = synchronized(lock) { chunks.toByteArray() }
        if (pcm.isEmpty()) {
            throw IllegalStateException("豆包 TTS 未返回音频")
        }
        io.legado.app.help.tts.ReadAloudDebugLog.add("豆包", "完成：PCM ${pcm.size} 字节")
        return Result(wrapWav(pcm, SAMPLE_RATE), SAMPLE_RATE)
    }

    private fun startTaskJson(voiceId: String, speechRate: Float, pitchOffset: Int, loudnessRate: Int): String {
        val audioConfig = JSONObject()
            .put("format", "pcm")
            .put("sample_rate", SAMPLE_RATE)
            .put("loudness_rate", loudnessRate)
        val extra = JSONObject()
            .put("post_process", JSONObject().put("pitch", pitchOffset).put("speech_rate", speechRate.toDouble()))
            .put("max_length_to_filter_parenthesis", 0)
        val payload = JSONObject()
            .put("audio_config", audioConfig)
            .put("extra", extra)
            .put("speaker", voiceId)
        return JSONObject()
            .put("appkey", APP_KEY)
            .put("event", "StartTask")
            .put("namespace", "BidirectionalTTS")
            .put("payload", payload.toString())
            .toString()
    }

    /** PCM 裸流补 WAV 头，并在首尾各加 100ms 静音（与原插件一致，避免首尾爆音）。 */
    fun wrapWav(pcm: ByteArray, sampleRate: Int): ByteArray {
        val silenceBytes = sampleRate * CHANNELS * BITS / 8 / 1000 * SILENCE_MS
        val total = 44 + silenceBytes * 2 + pcm.size
        val out = java.io.ByteArrayOutputStream(total)
        writeWavHeader(out, pcm.size + silenceBytes * 2, sampleRate)
        val zeros = ByteArray(silenceBytes)
        out.write(zeros)
        out.write(pcm)
        out.write(zeros)
        out.flush()
        return out.toByteArray()
    }

    private fun writeWavHeader(out: java.io.ByteArrayOutputStream, dataLen: Int, sampleRate: Int) {
        val byteRate = sampleRate * CHANNELS * BITS / 8
        val blockAlign = CHANNELS * BITS / 8
        fun writeAscii(value: String) = out.write(value.toByteArray(Charsets.US_ASCII))
        fun writeIntLE(value: Int) {
            out.write(value and 0xFF)
            out.write((value shr 8) and 0xFF)
            out.write((value shr 16) and 0xFF)
            out.write((value shr 24) and 0xFF)
        }
        fun writeShortLE(value: Int) {
            out.write(value and 0xFF)
            out.write((value shr 8) and 0xFF)
        }
        writeAscii("RIFF")
        writeIntLE(36 + dataLen)
        writeAscii("WAVE")
        writeAscii("fmt ")
        writeIntLE(16)
        writeShortLE(1)
        writeShortLE(CHANNELS)
        writeIntLE(sampleRate)
        writeIntLE(byteRate)
        writeShortLE(blockAlign)
        writeShortLE(BITS)
        writeAscii("data")
        writeIntLE(dataLen)
    }

    private fun randomId(): String = (1_000_000_000_000L + Random.nextLong(9_000_000_000_000L)).toString()
}
