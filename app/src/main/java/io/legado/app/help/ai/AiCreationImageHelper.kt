package io.legado.app.help.ai

import android.util.Base64
import android.content.ContentValues
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import io.legado.app.data.appDb
import io.legado.app.data.entities.CreationResult
import io.legado.app.help.config.AppConfig
import io.legado.app.help.http.newCallResponse
import io.legado.app.help.http.okHttpClient
import io.legado.app.help.http.postJson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import splitties.init.appCtx
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicInteger

object AiCreationImageFile {

    private const val DIR_NAME = "creation_results"

    val dir: File
        get() = File(appCtx.filesDir, DIR_NAME).apply { mkdirs() }

    fun fileOf(fileName: String): File {
        require(!fileName.contains("..")) { "非法文件名" }
        return File(dir, fileName)
    }

    private val nameSeq = AtomicInteger(0)

    /** 文件名单点保证唯一：时间戳 + 进程内自增序号，并发任务同时落盘也不会撞名覆盖 */
    fun saveBytes(bytes: ByteArray, workflow: AiCreationWorkflow? = null): String {
        val fileName = "img_${System.currentTimeMillis()}_${nameSeq.incrementAndGet()}.png"
        val target = File(dir, fileName)
        //工作流写入 PNG 文本块（ComfyUI 同款做法）；非真 PNG 或注入失败如实原样落盘
        val outBytes = workflow?.let { meta ->
            runCatching { AiCreationMediaMetadata.injectPngWorkflow(bytes, meta.toJsonString()) }
                .getOrNull()
        } ?: bytes
        FileOutputStream(target).use { out ->
            out.write(outBytes)
        }
        return fileName
    }

    /** 视频落盘：vid_ 前缀 mp4，预览与图库按前缀区分视频条目；工作流写入 MP4 meta box */
    fun saveVideoBytes(bytes: ByteArray, workflow: AiCreationWorkflow? = null): String {
        val fileName = "vid_${System.currentTimeMillis()}_${nameSeq.incrementAndGet()}.mp4"
        val target = File(dir, fileName)
        val outBytes = workflow?.let { meta ->
            runCatching { AiCreationMediaMetadata.injectMp4Workflow(bytes, meta.toJsonString()) }
                .getOrNull()
        } ?: bytes
        FileOutputStream(target).use { out ->
            out.write(outBytes)
        }
        return fileName
    }

    /** 读取文件内的工作流 JSON 原文（无元数据返回 null）；看/复制/导出拿到的已脱敏，原字节只住文件里 */
    fun readWorkflowJson(fileName: String): String? =
        runCatching {
            AiCreationMediaMetadata.readWorkflowJson(fileOf(fileName).readBytes())
        }.getOrNull()

    /** 导出工作流 JSON 到公共 Download/Legado 目录，命名 <原文件名>_workflow.json */
    fun saveWorkflowToDownloads(
        context: android.content.Context,
        fileName: String,
        workflowJson: String
    ): Boolean =
        kotlin.runCatching {
            val exportName = fileName.substringBeforeLast('.') + "_workflow.json"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, exportName)
                    put(MediaStore.Downloads.MIME_TYPE, "application/json")
                    put(
                        MediaStore.Downloads.RELATIVE_PATH,
                        "${Environment.DIRECTORY_DOWNLOADS}/Legado"
                    )
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val uri = context.contentResolver.insert(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    values
                ) ?: return false
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(workflowJson.toByteArray(Charsets.UTF_8))
                } ?: return false
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                context.contentResolver.update(uri, values, null, null)
                true
            } else {
                @Suppress("DEPRECATION")
                val legacyDir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    "Legado"
                )
                if (!legacyDir.exists() && !legacyDir.mkdirs()) return false
                File(legacyDir, exportName).writeText(workflowJson, Charsets.UTF_8)
                true
            }
        }.getOrDefault(false)

    fun delete(fileName: String) {
        runCatching { fileOf(fileName).delete() }
    }

    fun saveToAlbum(context: android.content.Context, fileName: String): Boolean {
        val file = fileOf(fileName)
        if (!file.exists()) return false
        return if (fileName.startsWith("vid_")) {
            saveVideoToAlbum(context, file)
        } else {
            saveImageToAlbum(context, file)
        }
    }

    private fun saveImageToAlbum(context: android.content.Context, file: File): Boolean =
        kotlin.runCatching {
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

    private fun saveVideoToAlbum(context: android.content.Context, file: File): Boolean =
        kotlin.runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, file.name)
                    put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                    put(
                        MediaStore.Video.Media.RELATIVE_PATH,
                        "${Environment.DIRECTORY_MOVIES}/Legado"
                    )
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }
                val uri = context.contentResolver.insert(
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                    values
                ) ?: return false
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    file.inputStream().use { it.copyTo(out) }
                } ?: return false
                values.clear()
                values.put(MediaStore.Video.Media.IS_PENDING, 0)
                context.contentResolver.update(uri, values, null, null)
                true
            } else {
                @Suppress("DEPRECATION")
                val legacyDir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
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

/**
 * 生成任务悬浮窗状态：任务存在即应用内所有界面常驻显示，预览页除外。
 * 各 Activity 由 BaseActivity 统一挂载，创作对话框由其自身挂载在窗口顶层；
 * [taskRunning] 只反映最新任务，[previewBlocking] 表示预览页在前台（全部宿主隐藏）。
 */
data class AiCreationFloatingState(
    val hasTask: Boolean = false,
    val taskRunning: Boolean = false,
    val dismissed: Boolean = false,
    val previewBlocking: Boolean = false
) {
    val shouldShow: Boolean
        get() = hasTask && !dismissed && !previewBlocking
}

object AiCreationImageTaskHolder {

    // 智谱等生图服务的实测并发上限：3 路稳定，4 路会触发 429 限流
    private const val IMAGE_CONCURRENCY = 3

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * 一次生图请求一个任务。展示权唯一归属最新任务（以新的为准）：
     * 新请求不阻塞也不打断老任务，老任务继续后台跑完，
     * 返回的图照常落盘入库，报错与过程提示静默不再打扰。
     */
    private class GenerationTask(initial: List<AiCreationImageSlot>) {
        val slots = initial.toMutableList()
    }

    private val displayLock = Any()
    private var latestTask: GenerationTask? = null

    private val _slots = MutableStateFlow<List<AiCreationImageSlot>>(emptyList())
    val slots: StateFlow<List<AiCreationImageSlot>> = _slots.asStateFlow()

    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    private val _floatingState = MutableStateFlow(AiCreationFloatingState())
    val floatingState: StateFlow<AiCreationFloatingState> = _floatingState.asStateFlow()

    var floatingDismissed = false
        private set

    private var previewBlocking = false

    /** 回退设置定时关闭的计时任务：状态变化/新任务开始时取消重排 */
    private var autoCloseJob: Job? = null

    fun setPreviewBlocking(blocking: Boolean) {
        previewBlocking = blocking
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

    fun start(
        prompt: String,
        count: Int,
        extraValues: Map<String, String>,
        llmInput: String = "",
        imageRefs: List<String> = emptyList()
    ) {
        val target = AiCreationProviderStore.requireImageTarget()
        val task = GenerationTask((0 until count).map { index -> AiCreationImageSlot(index = index) })
        synchronized(displayLock) {
            //直接以新的为准：展示与提示立即切到新任务，老任务不取消不阻塞
            latestTask = task
            _slots.value = task.slots.toList()
            _notice.value = null
        }
        floatingDismissed = false
        updateFloatingState()
        scope.launch {
            runGeneration(task, target, prompt, count, extraValues, llmInput, imageRefs.toList())
        }
    }

    /**
     * 视频生成任务：数量与图片一致由用户填写，走视频供应商全部配置
     * （变量值由调用方按视频体系解析传入）。展示与提示复用图片任务的槽位机制，
     * 槽位文件名以 vid_ 前缀区分视频。视频一次请求只产出一个视频，按并发上限分批提交。
     * imageRefs 为提示词页图片集合快照，按下框提示词标记解析后随请求发出。
     */
    fun startVideo(
        prompt: String,
        count: Int,
        extraValues: Map<String, String>,
        llmInput: String = "",
        imageRefs: List<String> = emptyList()
    ) {
        val target = AiCreationProviderStore.requireVideoTarget()
        val task = GenerationTask((0 until count).map { index -> AiCreationImageSlot(index = index) })
        synchronized(displayLock) {
            latestTask = task
            _slots.value = task.slots.toList()
            _notice.value = null
        }
        floatingDismissed = false
        updateFloatingState()
        scope.launch {
            runVideoGeneration(task, target, prompt, count, extraValues, llmInput, imageRefs.toList())
        }
    }

    private suspend fun runVideoGeneration(
        task: GenerationTask,
        target: AiCreationProviderTarget,
        prompt: String,
        count: Int,
        extraValues: Map<String, String>,
        llmInput: String,
        imageRefs: List<String>
    ) {
        //图片一次解析复用：标记与集合不一致或文件缺失直接全槽失败，不静默丢图；
        //llmImages 为 LLM 输入份图片（按上框标记解析），只要涉及图片就 100% 记入溯源
        val (imageDataUrls, llmImages) = try {
            AiCreationHelper.resolvePromptImageDataUrls(prompt, imageRefs) to
                AiCreationHelper.resolveLlmInputImageDataUrls(llmInput, imageRefs)
        } catch (throwable: Throwable) {
            if (throwable is CancellationException) throw throwable
            for (index in 0 until count) {
                failSlot(task, index, throwable.message ?: "图片读取失败")
            }
            return
        }
        val retry = AiCreationConfig.imageRetryCount
        val failedIndexes = mutableListOf<Int>()
        for (chunk in (0 until count).chunked(IMAGE_CONCURRENCY)) {
            val results = coroutineScope {
                chunk.map { index ->
                    async {
                        index to runCatching {
                            requestVideo(target, prompt, extraValues, retry, llmInput, imageDataUrls, llmImages)
                        }
                    }
                }.awaitAll()
            }
            for ((index, single) in results) {
                single.onSuccess { fileName ->
                    acceptImage(task, index, fileName)
                }.onFailure { throwable ->
                    if (throwable is CancellationException) throw throwable
                    failedIndexes.add(index)
                }
            }
        }
        //首轮仍失败的槽位串行重试（带完整重试与退避），两轮全败才如实标失败
        for (index in failedIndexes) {
            val single = runCatching {
                requestVideo(target, prompt, extraValues, retry, llmInput, imageDataUrls, llmImages)
            }
            single.onSuccess { fileName ->
                acceptImage(task, index, fileName)
            }.onFailure { throwable ->
                if (throwable is CancellationException) throw throwable
                failSlot(task, index, throwable.message ?: "视频生成失败")
            }
        }
    }

    private suspend fun requestVideo(
        target: AiCreationProviderTarget,
        prompt: String,
        extraValues: Map<String, String>,
        retry: Int,
        llmInput: String,
        imageDataUrls: List<String> = emptyList(),
        llmImages: List<String> = emptyList()
    ): String {
        var lastError: Throwable? = null
        repeat(retry + 1) { attempt ->
            try {
                return AiCreationVideoHelper.generateVideo(
                    target.provider,
                    target.modelId,
                    prompt,
                    extraValues,
                    llmInput,
                    imageDataUrls,
                    llmImages
                )
            } catch (throwable: Throwable) {
                if (throwable is CancellationException) throw throwable
                lastError = throwable
                if (attempt < retry) delay(800)
            }
        }
        throw lastError ?: IllegalStateException("视频生成失败")
    }

    //悬浮窗转圈只反映最新任务是否还有未完成槽位；被接管的老任务跑完不再影响状态图标
    private fun updateFloatingState() {
        val slots = _slots.value
        _floatingState.value = AiCreationFloatingState(
            hasTask = slots.isNotEmpty(),
            taskRunning = slots.any { it.state == AiCreationImageSlotState.LOADING },
            dismissed = floatingDismissed,
            previewBlocking = previewBlocking
        )
        scheduleAutoCloseIfNeeded()
    }

    /**
     * 回退设置：AI 创作悬浮窗定时关闭。任务完成（转圈结束、悬浮窗仍在显示）后
     * 按设定秒数自动关闭，效果同手动点叉叉；新任务开始/状态变化时取消重排；
     * 秒数为空=不自动关闭。
     */
    private fun scheduleAutoCloseIfNeeded() {
        autoCloseJob?.cancel()
        autoCloseJob = null
        val state = _floatingState.value
        if (!state.shouldShow || state.taskRunning) return
        val seconds = AppConfig.aiCreationFloatingAutoCloseSeconds ?: return
        autoCloseJob = scope.launch {
            delay(seconds * 1000L)
            dismissFloating()
        }
    }

    /** 过程提示只归最新任务；被接管的老任务静默，报错也不管 */
    private fun postNotice(task: GenerationTask, message: String) {
        synchronized(displayLock) {
            if (latestTask !== task) return
            _notice.value = message
        }
    }

    /** 槽位展示更新只作用于最新任务；与 start 的任务切换同锁，避免切换瞬间被老任务的发布覆盖 */
    private fun publishSlots(task: GenerationTask, transform: (MutableList<AiCreationImageSlot>) -> Unit) {
        synchronized(displayLock) {
            if (latestTask !== task) return
            transform(task.slots)
            _slots.value = task.slots.toList()
        }
        updateFloatingState()
    }

    private suspend fun runGeneration(
        task: GenerationTask,
        target: AiCreationProviderTarget,
        prompt: String,
        count: Int,
        extraValues: Map<String, String>,
        llmInput: String,
        imageRefs: List<String>
    ) {
        //图片一次解析复用：标记与集合不一致或文件缺失直接全槽失败，不静默丢图；
        //llmImages 为 LLM 输入份图片（按上框标记解析），只要涉及图片就 100% 记入溯源
        val (imageDataUrls, llmImages) = try {
            AiCreationHelper.resolvePromptImageDataUrls(prompt, imageRefs) to
                AiCreationHelper.resolveLlmInputImageDataUrls(llmInput, imageRefs)
        } catch (throwable: Throwable) {
            if (throwable is CancellationException) throw throwable
            for (index in 0 until count) {
                failSlot(task, index, throwable.message ?: "图片读取失败")
            }
            return
        }
        // 第一级：单次批量请求 n 张（智谱等忽略 n 的服务只会返回 1 张，按实际返回数记账）
        val batch = runCatching {
            requestImages(target, prompt, count, extraValues, llmInput = llmInput, imageDataUrls = imageDataUrls, llmImages = llmImages)
        }
        var completed = 0
        batch.onSuccess { fileNames ->
            fileNames.take(count).forEach { fileName ->
                acceptImage(task, completed, fileName)
                completed++
            }
            if (completed in 1 until count) {
                postNotice(task, "批量请求只返回 $completed 张，剩余 ${count - completed} 张改为并发请求")
            }
        }.onFailure { throwable ->
            if (throwable is CancellationException) throw throwable
            postNotice(task, "单次批量生成失败，已改为并发请求：${throwable.message}")
        }
        // 第二级：剩余槽位直接并发请求（每批 IMAGE_CONCURRENCY 路，不带重试）
        val failedIndexes = mutableListOf<Int>()
        val remaining = (completed until count).toList()
        for (chunk in remaining.chunked(IMAGE_CONCURRENCY)) {
            val results = coroutineScope {
                    chunk.map { index ->
                        async {
                            index to runCatching {
                                requestImages(
                                    target,
                                    prompt,
                                    1,
                                    extraValues,
                                    retryEnabled = false,
                                    llmInput = llmInput,
                                    imageDataUrls = imageDataUrls,
                                    llmImages = llmImages
                                )
                            }
                        }
                    }.awaitAll()
            }
            for ((index, single) in results) {
                single.onSuccess { fileNames ->
                    if (fileNames.isEmpty()) {
                        failedIndexes.add(index)
                    } else {
                        acceptImage(task, index, fileNames.first())
                    }
                }.onFailure { throwable ->
                    if (throwable is CancellationException) throw throwable
                    failedIndexes.add(index)
                }
            }
        }
        if (failedIndexes.isNotEmpty()) {
            postNotice(task, "并发请求仍有 ${failedIndexes.size} 张失败，改为串行重试")
        }
        // 第三级：仍失败的槽位串行逐张重试（带完整重试与退避）
        for (index in failedIndexes) {
            val single = runCatching {
                requestImages(target, prompt, 1, extraValues, llmInput = llmInput, imageDataUrls = imageDataUrls, llmImages = llmImages)
            }
            single.onSuccess { fileNames ->
                if (fileNames.isEmpty()) {
                    failSlot(task, index, "服务未返回图片")
                } else {
                    acceptImage(task, index, fileNames.first())
                }
            }.onFailure { throwable ->
                if (throwable is CancellationException) throw throwable
                failSlot(task, index, throwable.message ?: "生成失败")
            }
        }
    }

    private suspend fun requestImages(
        target: AiCreationProviderTarget,
        prompt: String,
        n: Int,
        extraValues: Map<String, String>,
        retryEnabled: Boolean = true,
        llmInput: String = "",
        imageDataUrls: List<String> = emptyList(),
        llmImages: List<String> = emptyList()
    ): List<String> {
        val retry = AiCreationConfig.imageRetryCount
        //种子空着=每次随机：模板下不了"省略字段"，空串发过去会被打回来，所以这里填真随机数
        val resolvedSeed = extraValues["seed"]?.takeIf { it.isNotBlank() }
            ?: kotlin.random.Random.nextLong(0, 10_000_000_000L).toString()
        val body = renderImageRequestBody(target, prompt, n, extraValues, imageDataUrls, resolvedSeed)
        val workflow = buildWorkflow(
            type = AiCreationWorkflow.TYPE_IMAGE,
            target = target,
            prompt = prompt,
            variables = extraValues,
            llmInput = llmInput,
            requestBody = body,
            imageDataUrls = imageDataUrls,
            llmImages = llmImages
        )
        var lastError: Throwable? = null
        val attempts = if (retryEnabled) retry + 1 else 1
        repeat(attempts) { attempt ->
            try {
                return fetchImages(target.provider, body, workflow)
            } catch (throwable: Throwable) {
                if (throwable is CancellationException) throw throwable
                lastError = throwable
                if (attempt < attempts - 1) delay(800)
            }
        }
        throw lastError ?: IllegalStateException("生成失败")
    }

    /** 工作流溯源快照：变量与请求体都是填好实际值的成品，不含 API Key；images 为随请求发出的图片 data URL，llmImages 为 LLM 输入份图片 */
    private fun buildWorkflow(
        type: String,
        target: AiCreationProviderTarget,
        prompt: String,
        variables: Map<String, String>,
        llmInput: String,
        requestBody: String,
        imageDataUrls: List<String> = emptyList(),
        llmImages: List<String> = emptyList()
    ): AiCreationWorkflow {
        return AiCreationWorkflow(
            type = type,
            providerName = target.provider.name,
            baseUrl = target.provider.baseUrl,
            model = target.modelId,
            variables = variables,
            llmInput = llmInput,
            prompt = prompt,
            request = requestBody,
            images = imageDataUrls,
            llmImages = llmImages
        )
    }

    private suspend fun fetchImages(
        provider: AiCreationProviderConfig,
        body: String,
        workflow: AiCreationWorkflow? = null
    ): List<String> = withContext(Dispatchers.IO) {
        val response = okHttpClient.newCallResponse {
            url(provider.baseUrl)
            addHeader("Accept", "application/json")
            addHeader("Content-Type", "application/json")
            provider.apiKey.trim().takeIf { it.isNotBlank() }?.let {
                addHeader("Authorization", "Bearer $it")
            }
            AiCreationProviderStore.parseCustomHeaders(provider.headers).forEach { (key, value) ->
                //header() 覆盖同名默认头，自定义 Authorization 等以供应商配置为准
                header(key, value)
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
                        workflow
                    )
                }
                val imageUrl = item.optString("url")
                if (imageUrl.isNotBlank()) {
                    return@mapNotNull downloadImage(imageUrl, workflow)
                }
                null
            }
        }
    }

    private suspend fun downloadImage(
        url: String,
        workflow: AiCreationWorkflow? = null
    ): String = withContext(Dispatchers.IO) {
        val response = okHttpClient.newCallResponse { url(url) }
        response.use { rawResponse ->
            require(rawResponse.isSuccessful) { "图片下载失败 HTTP ${rawResponse.code}" }
            val bytes = rawResponse.body?.bytes()
                ?: throw IllegalStateException("图片下载内容为空")
            AiCreationImageFile.saveBytes(bytes, workflow)
        }
    }

    private fun renderImageRequestBody(
        target: AiCreationProviderTarget,
        prompt: String,
        n: Int,
        extraValues: Map<String, String>,
        imageDataUrls: List<String> = emptyList(),
        resolvedSeed: String = ""
    ): String {
        val tokens = buildMap {
            put("model", target.modelId)
            put("prompt", prompt)
            put("n", n.toString())
            //图生图占位：模板不引用则忽略（纯文生不受影响）；
            //引用 {{image}} 的模板（如硅基图生图模型）自动带上首图，最多三图对应 image/image2/image3
            put("image", imageDataUrls.getOrElse(0) { "" })
            put("image2", imageDataUrls.getOrElse(1) { "" })
            put("image3", imageDataUrls.getOrElse(2) { "" })
            putAll(extraValues)
            //种子后放：用户填了用填的，没填用本次随机数；旧模板没这个位置则忽略
            if (resolvedSeed.isNotBlank()) put("seed", resolvedSeed)
        }
        return AiCreationProviderStore.renderRequestTemplate(target.provider.requestTemplate, tokens)
    }

    /**
     * 图片测试连接：用当前供应商全部配置真实请求一次（变量取默认值，出 1 张），
     * 图片落盘并计入创作缓存，返回文件名。
     */
    suspend fun testConnection(
        provider: AiCreationProviderConfig,
        modelId: String
    ): String = withContext(Dispatchers.IO) {
        check(provider.requestTemplate.isNotBlank()) { "当前图片供应商「${provider.name}」的图片请求模板为空" }
        val variables = AiCreationProviderStore.parsedVariables(provider, isVideo = false)
        val tokens = buildMap {
            put("model", modelId)
            put("prompt", AiCreationProviderStore.IMAGE_TEST_PROMPT)
            put("n", "1")
            //测试不带图：图占位填空串，保证引用 {{image}} 的自定义模板也能渲染发出（服务端按无图校验）
            put("image", "")
            put("image2", "")
            put("image3", "")
            variables.forEach { variable ->
                put(variable.key, variable.effectiveValue(null))
            }
            //种子没有省略写法：测试也填真随机数，不发空串
            put("seed", get("seed")?.takeIf { it.isNotBlank() }
                ?: kotlin.random.Random.nextLong(0, 10_000_000_000L).toString())
        }
        val body = AiCreationProviderStore.renderRequestTemplate(provider.requestTemplate, tokens)
        val workflow = AiCreationWorkflow(
            type = AiCreationWorkflow.TYPE_IMAGE,
            providerName = provider.name,
            baseUrl = provider.baseUrl,
            model = modelId,
            variables = tokens.filterKeys { it !in setOf("model", "prompt", "n", "image", "image2", "image3", "seed") },
            llmInput = "",
            prompt = AiCreationProviderStore.IMAGE_TEST_PROMPT,
            request = body
        )
        val fileNames = fetchImages(provider, body, workflow)
        val fileName = fileNames.firstOrNull()
            ?: throw IllegalStateException("服务未返回图片")
        appDb.creationResultDao.insert(CreationResult(fileName = fileName))
        fileName
    }

    private suspend fun acceptImage(task: GenerationTask, index: Int, fileName: String) {
        //返回来的图一律接受：不管该任务是否已被更新的请求接管展示，都落库可查
        val resultId = appDb.creationResultDao.insert(
            CreationResult(fileName = fileName)
        )
        publishSlots(task) { slots ->
            val current = slots.getOrNull(index) ?: return@publishSlots
            slots[index] = current.copy(
                state = AiCreationImageSlotState.DONE,
                fileName = fileName,
                resultId = resultId
            )
        }
    }

    private fun failSlot(task: GenerationTask, index: Int, error: String) {
        publishSlots(task) { slots ->
            val current = slots.getOrNull(index) ?: return@publishSlots
            slots[index] = current.copy(state = AiCreationImageSlotState.FAILED, error = error)
        }
    }
}
