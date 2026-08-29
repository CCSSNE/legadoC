package io.legado.app.help.ai

import android.util.Base64
import android.content.ContentValues
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import io.legado.app.data.appDb
import io.legado.app.data.entities.CreationResult
import io.legado.app.help.http.newCallResponse
import io.legado.app.help.http.okHttpClient
import io.legado.app.help.http.postJson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import splitties.init.appCtx
import java.io.File
import java.io.FileOutputStream

object AiCreationImageFile {

    private const val DIR_NAME = "creation_results"

    val dir: File
        get() = File(appCtx.filesDir, DIR_NAME).apply { mkdirs() }

    fun fileOf(fileName: String): File {
        require(!fileName.contains("..")) { "非法文件名" }
        return File(dir, fileName)
    }

    fun saveBytes(bytes: ByteArray, seq: Int): String {
        val fileName = "img_${System.currentTimeMillis()}_$seq.png"
        val target = File(dir, fileName)
        FileOutputStream(target).use { out ->
            out.write(bytes)
        }
        return fileName
    }

    fun delete(fileName: String) {
        runCatching { fileOf(fileName).delete() }
    }

    fun saveToAlbum(context: android.content.Context, fileName: String): Boolean {
        val file = fileOf(fileName)
        if (!file.exists()) return false
        return kotlin.runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, file.name)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(
                        MediaStore.Images.Media.RELATIVE_PATH,
                        "${Environment.DIRECTORY_PICTURES}/Legado"
                    )
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
                val uri = context.contentResolver.insert(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    values
                ) ?: return false
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    file.inputStream().use { it.copyTo(out) }
                } ?: return false
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                context.contentResolver.update(uri, values, null, null)
                true
            } else {
                @Suppress("DEPRECATION")
                val legacyDir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                    "Legado"
                )
                if (!legacyDir.exists() && !legacyDir.mkdirs()) return false
                val target = File(legacyDir, file.name)
                FileOutputStream(target).use { out ->
                    file.inputStream().use { it.copyTo(out) }
                }
                true
            }
        }.getOrDefault(false)
    }
}

enum class AiCreationImageSlotState {
    LOADING,
    DONE,
    FAILED
}

data class AiCreationImageSlot(
    val index: Int,
    val state: AiCreationImageSlotState = AiCreationImageSlotState.LOADING,
    val fileName: String = "",
    val resultId: Long = 0,
    val error: String = ""
)

data class AiCreationFloatingState(
    val hasTask: Boolean = false,
    val taskRunning: Boolean = false,
    val dismissed: Boolean = false,
    val uiVisible: Boolean = false
) {
    val shouldShow: Boolean
        get() = hasTask && !dismissed && !uiVisible
}

object AiCreationImageTaskHolder {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _slots = MutableStateFlow<List<AiCreationImageSlot>>(emptyList())
    val slots: StateFlow<List<AiCreationImageSlot>> = _slots.asStateFlow()

    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    private val _floatingState = MutableStateFlow(AiCreationFloatingState())
    val floatingState: StateFlow<AiCreationFloatingState> = _floatingState.asStateFlow()

    var floatingDismissed = false
        private set

    var uiVisible = false
        private set

    var running = false
        private set

    fun setUiVisible(visible: Boolean) {
        uiVisible = visible
        updateFloatingState()
    }

    fun dismissFloating() {
        floatingDismissed = true
        updateFloatingState()
    }

    fun consumeNotice(): String? {
        val message = _notice.value ?: return null
        _notice.value = null
        return message
    }

    fun start(prompt: String, count: Int, extraValues: Map<String, String>): Boolean {
        if (running) return false
        AiCreationConfig.requireImageApiReady()
        floatingDismissed = false
        _notice.value = null
        _slots.value = (0 until count).map { index -> AiCreationImageSlot(index = index) }
        running = true
        updateFloatingState()
        scope.launch {
            try {
                runGeneration(prompt, count, extraValues)
            } finally {
                running = false
                updateFloatingState()
            }
        }
        return true
    }

    private fun updateFloatingState() {
        _floatingState.value = AiCreationFloatingState(
            hasTask = _slots.value.isNotEmpty(),
            taskRunning = running,
            dismissed = floatingDismissed,
            uiVisible = uiVisible
        )
    }

    private suspend fun runGeneration(
        prompt: String,
        count: Int,
        extraValues: Map<String, String>
    ) {
        val batch = runCatching { requestImages(prompt, count, extraValues, 0) }
        var completed = 0
        batch.onSuccess { fileNames ->
            fileNames.take(count).forEach { fileName ->
                emitDone(completed, fileName)
                completed++
            }
            if (completed < count) {
                _notice.value =
                    "批量请求只返回 $completed 张，剩余 ${count - completed} 张改为逐张请求"
            }
        }.onFailure { throwable ->
            if (throwable is CancellationException) throw throwable
            _notice.value = "单次批量生成失败，已改为逐张请求：${throwable.message}"
        }
        while (completed < count) {
            val index = completed
            val single = runCatching { requestImages(prompt, 1, extraValues, index) }
            single.onSuccess { fileNames ->
                if (fileNames.isEmpty()) {
                    failSlot(index, "服务未返回图片")
                } else {
                    emitDone(index, fileNames.first())
                }
            }.onFailure { throwable ->
                if (throwable is CancellationException) throw throwable
                failSlot(index, throwable.message ?: "生成失败")
            }
            completed++
        }
    }

    private suspend fun requestImages(
        prompt: String,
        n: Int,
        extraValues: Map<String, String>,
        seq: Int
    ): List<String> {
        val url = AiCreationConfig.imageUrl
        val retry = AiCreationConfig.imageRetryCount
        val body = renderImageRequestBody(prompt, n, extraValues)
        var lastError: Throwable? = null
        repeat(retry + 1) { attempt ->
            try {
                return fetchImages(url, body, seq, attempt)
            } catch (throwable: Throwable) {
                if (throwable is CancellationException) throw throwable
                lastError = throwable
                if (attempt < retry) delay(800)
            }
        }
        throw lastError ?: IllegalStateException("生成失败")
    }

    private suspend fun fetchImages(
        url: String,
        body: String,
        seq: Int,
        batchSeq: Int
    ): List<String> = withContext(Dispatchers.IO) {
        val response = okHttpClient.newCallResponse {
            url(url)
            addHeader("Accept", "application/json")
            addHeader("Content-Type", "application/json")
            AiCreationConfig.imageApiKey.trim().takeIf { it.isNotBlank() }?.let {
                addHeader("Authorization", "Bearer $it")
            }
            postJson(body)
        }
        response.use { rawResponse ->
            val text = rawResponse.body?.string().orEmpty()
            if (!rawResponse.isSuccessful) {
                throw IllegalStateException("HTTP ${rawResponse.code}: ${text.take(300)}")
            }
            val root = JSONObject(text)
            val data = root.optJSONArray("data")
                ?: throw IllegalStateException("响应缺少 data 字段：${text.take(200)}")
            if (data.length() == 0) {
                throw IllegalStateException("响应 data 为空")
            }
            return@withContext (0 until data.length()).mapNotNull { index ->
                val item = data.optJSONObject(index) ?: return@mapNotNull null
                val b64 = item.optString("b64_json")
                if (b64.isNotBlank()) {
                    return@mapNotNull AiCreationImageFile.saveBytes(
                        Base64.decode(b64, Base64.DEFAULT),
                        seq * 100 + batchSeq * 10 + index
                    )
                }
                val imageUrl = item.optString("url")
                if (imageUrl.isNotBlank()) {
                    return@mapNotNull downloadImage(imageUrl, seq, batchSeq, index)
                }
                null
            }
        }
    }

    private suspend fun downloadImage(
        url: String,
        seq: Int,
        batchSeq: Int,
        index: Int
    ): String = withContext(Dispatchers.IO) {
        val response = okHttpClient.newCallResponse { url(url) }
        response.use { rawResponse ->
            require(rawResponse.isSuccessful) { "图片下载失败 HTTP ${rawResponse.code}" }
            val bytes = rawResponse.body?.bytes()
                ?: throw IllegalStateException("图片下载内容为空")
            AiCreationImageFile.saveBytes(bytes, seq * 100 + batchSeq * 10 + index)
        }
    }

    private fun renderImageRequestBody(
        prompt: String,
        n: Int,
        extraValues: Map<String, String>
    ): String {
        val template = AiCreationConfig.imageRequestTemplate
        val withCount = template.replace("{{n}}", n.toString())
        val root = try {
            JSONObject(withCount)
        } catch (throwable: Throwable) {
            throw IllegalStateException(
                "图片请求模板 JSON 无效：${throwable.message}",
                throwable
            )
        }
        val tokens = buildMap {
            put("model", AiCreationConfig.imageModel)
            put("prompt", prompt)
            put("n", n.toString())
            putAll(extraValues)
        }
        replaceTokens(root, tokens)
        return root.toString()
    }

    private fun replaceTokens(json: JSONObject, tokens: Map<String, String>) {
        val keys = json.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            when (val value = json.opt(key)) {
                is JSONObject -> replaceTokens(value, tokens)
                is JSONArray -> replaceTokens(value, tokens)
                is String -> json.put(key, replaceTokensInString(value, tokens))
            }
        }
    }

    private fun replaceTokens(array: JSONArray, tokens: Map<String, String>) {
        for (index in 0 until array.length()) {
            when (val value = array.opt(index)) {
                is JSONObject -> replaceTokens(value, tokens)
                is JSONArray -> replaceTokens(value, tokens)
                is String -> array.put(index, replaceTokensInString(value, tokens))
            }
        }
    }

    private fun replaceTokensInString(value: String, tokens: Map<String, String>): String {
        return tokens.entries.fold(value) { acc, (key, replacement) ->
            acc.replace("{{$key}}", replacement).replace("\${$key}", replacement)
        }
    }

    private suspend fun emitDone(index: Int, fileName: String) {
        val resultId = appDb.creationResultDao.insert(
            CreationResult(fileName = fileName)
        )
        updateSlot(index) { it.copy(state = AiCreationImageSlotState.DONE, fileName = fileName, resultId = resultId) }
    }

    private fun failSlot(index: Int, error: String) {
        updateSlot(index) {
            it.copy(state = AiCreationImageSlotState.FAILED, error = error)
        }
    }

    private fun updateSlot(index: Int, transform: (AiCreationImageSlot) -> AiCreationImageSlot) {
        val current = _slots.value.toMutableList()
        val target = current.getOrNull(index) ?: return
        current[index] = transform(target)
        _slots.value = current
        updateFloatingState()
    }
}
