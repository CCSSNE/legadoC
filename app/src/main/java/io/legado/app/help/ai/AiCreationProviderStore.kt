package io.legado.app.help.ai

import androidx.annotation.Keep
import io.legado.app.constant.PreferKey
import io.legado.app.plugin.AiBuiltinDefaults
import io.legado.app.utils.GSON
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.getPrefString
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.putPrefBoolean
import io.legado.app.utils.putPrefString
import io.legado.app.utils.removePref
import org.json.JSONObject
import splitties.init.appCtx
import java.util.UUID

/**
 * AI 创作图片/视频供应商配置：
 * 供应商管连线协议（Base URL / API Key / 请求头 / 变量定义 / 请求模板），
 * 模型挂在供应商下。
 * 变量定义 JSON 只含生图/生视频参数变量；
 * LLM 变量、提示词路由与 LLM 输入模板在全局 LLM 变量设置，与供应商无关。
 */
@Keep
data class AiCreationProviderConfig(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val baseUrl: String,
    val apiKey: String = "",
    val headers: String = "",
    val variablesJson: String = "",
    val requestTemplate: String = "",
    val apiKeyUrl: String = "",
    val builtIn: Boolean = false
)

@Keep
data class AiCreationProviderModel(
    val id: String = UUID.randomUUID().toString(),
    val providerId: String,
    val modelId: String
)

/** 一次图片/视频请求解析完成的执行目标 */
data class AiCreationProviderTarget(
    val provider: AiCreationProviderConfig,
    val modelId: String
)

object AiCreationProviderStore {

    //内置供应商使用固定 id，供初始配置与恢复默认定位。
    const val IMAGE_SILICONFLOW_ID = "builtin-img-siliconflow"
    const val IMAGE_ZHIPU_ID = "builtin-img-zhipu"
    const val VIDEO_ZHIPU_ID = "builtin-video-zhipu"

    const val API_KEY_URL_SILICONFLOW = "https://cloud.siliconflow.cn/me/account/ak"
    const val API_KEY_URL_ZHIPU = "https://bigmodel.cn/apikey/platform"

    const val IMAGE_TEST_PROMPT = "一只橘猫坐在窗台上，阳光洒落，温暖色调，高清摄影"
    const val VIDEO_TEST_PROMPT = "一只橘猫在草地上奔跑，阳光明媚，镜头平视"
    private val REQUEST_PLACEHOLDER = Regex("\\{\\{([^{}\\s]+)\\}\\}")

    //内置供应商出厂请求模板
    const val ZHIPU_IMAGE_REQUEST_TEMPLATE =
        """{"model":"{{model}}","prompt":"{{prompt}}","n":{{n}},"size":"{{size}}","quality":"{{quality}}","watermark_enabled":{{watermark_enabled}}}"""

    const val SILICONFLOW_IMAGE_REQUEST_TEMPLATE =
        """{"model":"{{model}}","prompt":"{{prompt}}","negative_prompt":"{{negative_prompt}}","image_size":"{{image_size}}","batch_size":{{n}},"num_inference_steps":{{num_inference_steps}},"guidance_scale":{{guidance_scale}},"seed":{{seed}},"image":"{{image}}"}"""

    const val ZHIPU_VIDEO_REQUEST_TEMPLATE =
        """{"model":"{{model}}","prompt":"{{prompt}}","quality":"{{video_quality}}","with_audio":{{video_with_audio}},"size":"{{video_size}}","fps":{{video_fps}},"duration":{{video_duration}},"watermark_enabled":{{watermark_enabled}},"request_id":"{{request_id}}","image_url":{{image_url}}}"""

    // ———————— 图片供应商 ————————

    var imageProviderList: List<AiCreationProviderConfig>
        get() {
            val providers = readImageProviders()
            syncImageState(providers, readImageModels(providers.map { it.id }.toSet()))
            return providers
        }
        set(value) {
            val providers = normalizeProviders(value)
            persistImageProviders(providers)
            persistImageModels(
                normalizeModels(readRawImageModels(), providers.map { it.id }.toSet())
            )
        }

    var imageModelList: List<AiCreationProviderModel>
        get() {
            val providers = readImageProviders()
            val models = normalizeModels(readRawImageModels(), providers.map { it.id }.toSet())
            syncImageState(providers, models)
            return models
        }
        set(value) {
            val providers = readImageProviders()
            persistImageModels(normalizeModels(value, providers.map { it.id }.toSet()))
        }

    var imageCurrentProviderId: String?
        get() {
            val providers = readImageProviders()
            syncImageState(providers, readImageModels(providers.map { it.id }.toSet()))
            return appCtx.getPrefString(PreferKey.aiCreationImageCurrentProviderId)
        }
        set(value) {
            val providers = readImageProviders()
            val providerId = providers.firstOrNull { it.id == value }?.id
            if (providerId.isNullOrBlank()) {
                appCtx.removePref(PreferKey.aiCreationImageCurrentProviderId)
            } else {
                appCtx.putPrefString(PreferKey.aiCreationImageCurrentProviderId, providerId)
            }
        }

    var imageCurrentModelRowId: String?
        get() {
            val providers = readImageProviders()
            val models = readImageModels(providers.map { it.id }.toSet())
            syncImageState(providers, models)
            return appCtx.getPrefString(PreferKey.aiCreationImageCurrentModelId)
        }
        set(value) {
            val providers = readImageProviders()
            val models = readImageModels(providers.map { it.id }.toSet())
            val model = models.firstOrNull { it.id == value }
            if (model == null) {
                appCtx.removePref(PreferKey.aiCreationImageCurrentModelId)
            } else {
                appCtx.putPrefString(PreferKey.aiCreationImageCurrentModelId, model.id)
                appCtx.putPrefString(PreferKey.aiCreationImageCurrentProviderId, model.providerId)
            }
        }

    val imageCurrentProvider: AiCreationProviderConfig?
        get() = imageProviderList.firstOrNull { it.id == imageCurrentProviderId }

    val imageCurrentModel: AiCreationProviderModel?
        get() = imageModelList.firstOrNull { it.id == imageCurrentModelRowId }

    /** 当前选中模型的 modelId（供请求与展示使用） */
    val imageCurrentModelId: String
        get() = imageCurrentModel?.modelId.orEmpty()

    // ———————— 视频供应商 ————————

    var videoProviderList: List<AiCreationProviderConfig>
        get() {
            val providers = readVideoProviders()
            syncVideoState(providers, readVideoModels(providers.map { it.id }.toSet()))
            return providers
        }
        set(value) {
            val providers = normalizeProviders(value)
            persistVideoProviders(providers)
            persistVideoModels(
                normalizeModels(readRawVideoModels(), providers.map { it.id }.toSet())
            )
        }

    var videoModelList: List<AiCreationProviderModel>
        get() {
            val providers = readVideoProviders()
            val models = normalizeModels(readRawVideoModels(), providers.map { it.id }.toSet())
            syncVideoState(providers, models)
            return models
        }
        set(value) {
            val providers = readVideoProviders()
            persistVideoModels(normalizeModels(value, providers.map { it.id }.toSet()))
        }

    var videoCurrentProviderId: String?
        get() {
            val providers = readVideoProviders()
            syncVideoState(providers, readVideoModels(providers.map { it.id }.toSet()))
            return appCtx.getPrefString(PreferKey.aiCreationVideoCurrentProviderId)
        }
        set(value) {
            val providers = readVideoProviders()
            val providerId = providers.firstOrNull { it.id == value }?.id
            if (providerId.isNullOrBlank()) {
                appCtx.removePref(PreferKey.aiCreationVideoCurrentProviderId)
            } else {
                appCtx.putPrefString(PreferKey.aiCreationVideoCurrentProviderId, providerId)
            }
        }

    var videoCurrentModelRowId: String?
        get() {
            val providers = readVideoProviders()
            val models = readVideoModels(providers.map { it.id }.toSet())
            syncVideoState(providers, models)
            return appCtx.getPrefString(PreferKey.aiCreationVideoCurrentModelId)
        }
        set(value) {
            val providers = readVideoProviders()
            val models = readVideoModels(providers.map { it.id }.toSet())
            val model = models.firstOrNull { it.id == value }
            if (model == null) {
                appCtx.removePref(PreferKey.aiCreationVideoCurrentModelId)
            } else {
                appCtx.putPrefString(PreferKey.aiCreationVideoCurrentModelId, model.id)
                appCtx.putPrefString(PreferKey.aiCreationVideoCurrentProviderId, model.providerId)
            }
        }

    val videoCurrentProvider: AiCreationProviderConfig?
        get() = videoProviderList.firstOrNull { it.id == videoCurrentProviderId }

    val videoCurrentModel: AiCreationProviderModel?
        get() = videoModelList.firstOrNull { it.id == videoCurrentModelRowId }

    val videoCurrentModelId: String
        get() = videoCurrentModel?.modelId.orEmpty()

    // ———————— 目标解析与校验 ————————

    fun requireImageTarget(): AiCreationProviderTarget {
        val provider = imageCurrentProvider
            ?: error("请先在「管理图片供应商」中设为当前供应商")
        check(provider.baseUrl.isNotBlank()) { "当前图片供应商「${provider.name}」的 API 地址为空" }
        parsedVariables(provider, isVideo = false)
        parseImageRequestTemplateJson(provider.requestTemplate)
        val model = imageCurrentModel
            ?: error("请先在「添加图片模型」中为当前供应商添加模型")
        check(model.modelId.isNotBlank()) { "当前图片模型不能为空" }
        return AiCreationProviderTarget(provider, model.modelId)
    }

    fun requireVideoTarget(): AiCreationProviderTarget {
        val provider = videoCurrentProvider
            ?: error("请先在「管理视频供应商」中设为当前供应商")
        check(provider.baseUrl.isNotBlank()) { "当前视频供应商「${provider.name}」的 API 地址为空" }
        parsedVariables(provider, isVideo = true)
        parseVideoRequestTemplateJson(provider.requestTemplate)
        val model = videoCurrentModel
            ?: error("请先在「添加视频模型」中为当前供应商添加模型")
        check(model.modelId.isNotBlank()) { "当前视频模型不能为空" }
        return AiCreationProviderTarget(provider, model.modelId)
    }

    /** 创作界面与提示词生成使用的变量定义：取当前图片供应商，旧格式残留自动回出厂 */
    fun parsedImageVariables(): List<AiCreationVariable> {
        val provider = imageCurrentProvider
            ?: error("请先在「管理图片供应商」中设为当前供应商")
        check(provider.variablesJson.isNotBlank()) { "当前图片供应商「${provider.name}」的变量定义为空" }
        return parsedVariables(provider, isVideo = false)
    }

    /** 创作界面与提示词生成使用的变量定义：取当前视频供应商，旧格式残留自动回出厂 */
    fun parsedVideoVariables(): List<AiCreationVariable> {
        val provider = videoCurrentProvider
            ?: error("请先在「管理视频供应商」中设为当前供应商")
        check(provider.variablesJson.isNotBlank()) { "当前视频供应商「${provider.name}」的变量定义为空" }
        return parsedVariables(provider, isVideo = true)
    }

    /**
     * 解析供应商变量定义：旧格式残留（带 routes/finalPrompt/style 指纹）自动回出厂并重读；
     * 其他解析错误一律是用户自己的问题，原样报错、不碰存储。
     * 自灭式：回出厂后指纹消失，此路以后永远走不到；自定义供应商没有出厂可写，继续报错让用户重填。
     * 读完再补缺：内置供应商出厂新增了参数（如种子、编号）时，老机器存量里没有的自动补上，
     * 存量已有的一律不动；自定义供应商没有出厂可对照，原样返回。
     */
    fun parsedVariables(
        provider: AiCreationProviderConfig,
        isVideo: Boolean
    ): List<AiCreationVariable> {
        val parsed = try {
            AiCreationVariables.parse(provider.variablesJson)
        } catch (error: RuntimeException) {
            if (!AiCreationVariables.isLegacyVariablesJson(provider.variablesJson)) throw error
            val factory = defaultVariablesJsonOf(provider) ?: throw error
            updateProviderVariablesJson(provider.id, isVideo, factory)
            dropStaleStyleKey(provider.id, isVideo)
            AiCreationVariables.parse(factory)
        }
        return mergeMissingFactoryVariables(provider, isVideo, parsed)
    }

    /** 出厂补缺：存量缺的出厂参数追加进存储，已有的不动；模板与参数值都不碰 */
    private fun mergeMissingFactoryVariables(
        provider: AiCreationProviderConfig,
        isVideo: Boolean,
        parsed: List<AiCreationVariable>
    ): List<AiCreationVariable> {
        val factoryJson = defaultVariablesJsonOf(provider) ?: return parsed
        if (factoryJson == provider.variablesJson) return parsed
        val factory = runCatching { AiCreationVariables.parse(factoryJson) }.getOrNull()
            ?: return parsed
        val keys = parsed.mapTo(mutableSetOf()) { it.key }
        val missing = factory.filter { it.key !in keys }
        if (missing.isEmpty()) return parsed
        val merged = parsed + missing
        updateProviderVariablesJson(
            provider.id,
            isVideo,
            AiCreationVariables.buildImageJson(merged)
        )
        return merged
    }

    /** 回出厂写回：只重写内置供应商的变量定义；其他配置不动 */
    private fun updateProviderVariablesJson(providerId: String, isVideo: Boolean, factoryJson: String) {
        if (isVideo) {
            videoProviderList = videoProviderList.map {
                if (it.id == providerId) it.copy(variablesJson = factoryJson) else it
            }
        } else {
            imageProviderList = imageProviderList.map {
                if (it.id == providerId) it.copy(variablesJson = factoryJson) else it
            }
        }
    }

    /**
     * 强升级：内置供应商的变量定义与请求模板回到出厂；
     * 身份与连线信息（id、名字、地址、钥匙、自定义请求头、Key 获取地址）原样保留，
     * 自定义供应商没有出厂可对照，一律不动。
     */
    fun restoreBuiltinToFactory() {
        imageProviderList = imageProviderList.map { restoreBuiltinProvider(it) }
        videoProviderList = videoProviderList.map { restoreBuiltinProvider(it) }
    }

    private fun restoreBuiltinProvider(provider: AiCreationProviderConfig): AiCreationProviderConfig {
        val factoryVariables = defaultVariablesJsonOf(provider) ?: return provider
        val factoryTemplate = defaultRequestTemplateOf(provider) ?: return provider
        if (provider.variablesJson == factoryVariables && provider.requestTemplate == factoryTemplate) {
            return provider
        }
        return provider.copy(variablesJson = factoryVariables, requestTemplate = factoryTemplate)
    }

    /** 回出厂时顺手清掉已搬走的 style 旧存储键；mode 等正常参数不动 */
    private fun dropStaleStyleKey(providerId: String, isVideo: Boolean) {
        val mode = if (isVideo) AiCreationVariables.GROUP_VIDEO else AiCreationVariables.GROUP_IMAGE
        val params = AiCreationConfig.loadCreationParams()
        if (params.remove("provider:$mode:$providerId:style") != null) {
            AiCreationConfig.saveCreationParams(params)
        }
    }

    /** 内置供应商的出厂变量定义（供恢复默认）；自定义供应商无默认 */
    fun defaultVariablesJsonOf(provider: AiCreationProviderConfig): String? = when (provider.id) {
        IMAGE_SILICONFLOW_ID ->
            AiCreationVariables.buildImageJson(AiCreationVariables.kolorsImageVariables)
        IMAGE_ZHIPU_ID -> AiCreationVariables.defaultJson
        VIDEO_ZHIPU_ID -> AiCreationVariables.zhipuVideoVariablesJson
        else -> null
    }

    /** 内置供应商的出厂请求模板（供恢复默认）；自定义供应商无默认 */
    fun defaultRequestTemplateOf(provider: AiCreationProviderConfig): String? = when (provider.id) {
        IMAGE_SILICONFLOW_ID -> SILICONFLOW_IMAGE_REQUEST_TEMPLATE
        IMAGE_ZHIPU_ID -> ZHIPU_IMAGE_REQUEST_TEMPLATE
        VIDEO_ZHIPU_ID -> ZHIPU_VIDEO_REQUEST_TEMPLATE
        else -> null
    }

    // ———————— 请求模板渲染（图片/视频共用） ————————

    /**
     * 渲染请求模板：裸占位符（值位置不带引号）按 JSON 字面量替换，布尔/整数/小数不加引号；
     * 带引号与字符串内嵌的 {{key}} 按字符串替换。
     */
    fun renderRequestTemplate(template: String, tokens: Map<String, String>): String {
        val unresolved = REQUEST_PLACEHOLDER.findAll(template)
            .map { it.groupValues[1] }
            .filterNot { it in tokens }
            .toSet()
        require(unresolved.isEmpty()) {
            "请求模板引用了未定义的占位符：${unresolved.joinToString("、")}"
        }
        var withLiterals = template
        tokens.forEach { (key, value) ->
            val tokenRegex = Regex("([:,\\[]\\s*)" + Regex.escape("{{$key}}") + "(\\s*[,}\\]])")
            withLiterals = tokenRegex.replace(withLiterals) { match ->
                "${match.groupValues[1]}${jsonLiteralOf(value)}${match.groupValues[2]}"
            }
        }
        val root = try {
            JSONObject(withLiterals)
        } catch (throwable: Throwable) {
            throw IllegalStateException(
                "请求模板 JSON 无效：${throwable.message}",
                throwable
            )
        }
        replaceTokens(root, tokens)
        return root.toString()
    }

    /** 占位符替换为 JSON 字面量：布尔保持 true/false，整数与小数不加引号，其余按 JSON 字符串转义 */
    private fun jsonLiteralOf(value: String): String {
        val trimmed = value.trim()
        //裸占位符允许传入合法 JSON 数组/对象原文（如多图 image_url 的首尾帧数组）；
        //带引号模板里的普通文本走字符串替换分支，到不了这里，提示词等内容不受影响
        if ((trimmed.startsWith("[") && trimmed.endsWith("]")) ||
            (trimmed.startsWith("{") && trimmed.endsWith("}"))
        ) {
            if (runCatching { JSONObject(trimmed) }.getOrNull() != null) return trimmed
            if (runCatching { org.json.JSONArray(trimmed) }.getOrNull() != null) return trimmed
        }
        return when {
            value == "true" || value == "false" -> value
            value.matches(Regex("-?\\d+(\\.\\d+)?")) -> value
            else -> JSONObject.quote(value)
        }
    }

    private fun replaceTokens(json: JSONObject, tokens: Map<String, String>) {
        val keys = json.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            when (val value = json.opt(key)) {
                is JSONObject -> replaceTokens(value, tokens)
                is org.json.JSONArray -> replaceTokens(value, tokens)
                is String -> json.put(key, replaceTokensInString(value, tokens))
            }
        }
    }

    private fun replaceTokens(array: org.json.JSONArray, tokens: Map<String, String>) {
        for (index in 0 until array.length()) {
            when (val value = array.opt(index)) {
                is JSONObject -> replaceTokens(value, tokens)
                is org.json.JSONArray -> replaceTokens(value, tokens)
                is String -> array.put(index, replaceTokensInString(value, tokens))
            }
        }
    }

    private fun replaceTokensInString(value: String, tokens: Map<String, String>): String {
        return tokens.entries.fold(value) { acc, (key, replacement) ->
            acc.replace("{{$key}}", replacement)
        }
    }

    /**
     * 解析自定义请求头：优先 JSON 对象，其次逐行 "K: V" / "K=V"（与 LLM 供应商同格式）。
     */
    fun parseCustomHeaders(rawHeaders: String): Map<String, String> {
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

    /** 请求模板 JSON 校验（所有占位符换成字面 1 后必须可解析） */
    fun parseRequestTemplateJson(json: String): String {
        val normalized = json.trim()
        require(normalized.isNotEmpty()) { "请求模板不能为空" }
        try {
            JSONObject(normalized.replace(Regex("\\{\\{[^}]*\\}\\}"), "1"))
        } catch (throwable: Throwable) {
            throw IllegalStateException(
                "请求模板 JSON 无效：${throwable.message}",
                throwable
            )
        }
        return normalized
    }

    fun parseImageRequestTemplateJson(json: String): String =
        parseRequiredRequestTemplate(json, setOf("model", "prompt", "n"))

    fun parseVideoRequestTemplateJson(json: String): String =
        parseRequiredRequestTemplate(json, setOf("model", "prompt"))

    private fun parseRequiredRequestTemplate(json: String, required: Set<String>): String {
        val normalized = parseRequestTemplateJson(json)
        val placeholders = REQUEST_PLACEHOLDER.findAll(normalized)
            .map { it.groupValues[1] }
            .toSet()
        val missing = required - placeholders
        require(missing.isEmpty()) {
            "请求模板缺少占位符：${missing.joinToString("、")}"
        }
        return normalized
    }

    // ———————— 内置初始配置 ————————

    private fun builtinImageProviders(): List<AiCreationProviderConfig> = listOf(
        AiCreationProviderConfig(
            id = IMAGE_SILICONFLOW_ID,
            name = "硅基流动",
            baseUrl = "https://api.siliconflow.cn/v1/images/generations",
            apiKey = AiBuiltinDefaults.siliconFlowApiKey(),
            apiKeyUrl = API_KEY_URL_SILICONFLOW,
            variablesJson = AiCreationVariables.buildImageJson(
                AiCreationVariables.kolorsImageVariables
            ),
            requestTemplate = SILICONFLOW_IMAGE_REQUEST_TEMPLATE,
            builtIn = true
        ),
        AiCreationProviderConfig(
            id = IMAGE_ZHIPU_ID,
            name = "智谱",
            baseUrl = "https://open.bigmodel.cn/api/paas/v4/images/generations",
            apiKey = AiBuiltinDefaults.zhipuApiKey(),
            apiKeyUrl = API_KEY_URL_ZHIPU,
            variablesJson = AiCreationVariables.defaultJson,
            requestTemplate = ZHIPU_IMAGE_REQUEST_TEMPLATE,
            builtIn = true
        )
    )

    private fun builtinImageModels(): List<AiCreationProviderModel> = listOf(
        AiCreationProviderModel(
            id = "builtin-img-model-kolors",
            providerId = IMAGE_SILICONFLOW_ID,
            modelId = "Kwai-Kolors/Kolors"
        ),
        AiCreationProviderModel(
            id = "builtin-img-model-cogview3flash",
            providerId = IMAGE_ZHIPU_ID,
            modelId = "cogview-3-flash"
        )
    )

    private fun builtinVideoProviders(): List<AiCreationProviderConfig> = listOf(
        AiCreationProviderConfig(
            id = VIDEO_ZHIPU_ID,
            name = "智谱",
            baseUrl = "https://open.bigmodel.cn/api/paas/v4/videos/generations",
            apiKey = AiBuiltinDefaults.zhipuApiKey(),
            apiKeyUrl = API_KEY_URL_ZHIPU,
            variablesJson = AiCreationVariables.zhipuVideoVariablesJson,
            requestTemplate = ZHIPU_VIDEO_REQUEST_TEMPLATE,
            builtIn = true
        )
    )

    private fun builtinVideoModels(): List<AiCreationProviderModel> = listOf(
        AiCreationProviderModel(
            id = "builtin-video-model-cogvideoxflash",
            providerId = VIDEO_ZHIPU_ID,
            modelId = "cogvideox-flash"
        )
    )

    private fun readImageProviders(): List<AiCreationProviderConfig> {
        ensureImageConfigIfNeeded()
        return normalizeProviders(fromJsonProviders(PreferKey.aiCreationImageProviderList))
    }

    private fun readVideoProviders(): List<AiCreationProviderConfig> {
        ensureVideoConfigIfNeeded()
        return normalizeProviders(fromJsonProviders(PreferKey.aiCreationVideoProviderList))
    }

    private fun readImageModels(validProviderIds: Set<String>): List<AiCreationProviderModel> {
        ensureImageConfigIfNeeded()
        return normalizeModels(readRawImageModels(), validProviderIds)
    }

    private fun readVideoModels(validProviderIds: Set<String>): List<AiCreationProviderModel> {
        ensureVideoConfigIfNeeded()
        return normalizeModels(readRawVideoModels(), validProviderIds)
    }

    private fun readRawImageModels(): List<AiCreationProviderModel> =
        fromJsonModels(PreferKey.aiCreationImageModelList)

    private fun readRawVideoModels(): List<AiCreationProviderModel> =
        fromJsonModels(PreferKey.aiCreationVideoModelList)

    private fun fromJsonProviders(key: String): List<AiCreationProviderConfig> =
        GSON.fromJsonArray<AiCreationProviderConfig>(appCtx.getPrefString(key))
            .getOrDefault(emptyList())

    private fun fromJsonModels(key: String): List<AiCreationProviderModel> =
        GSON.fromJsonArray<AiCreationProviderModel>(appCtx.getPrefString(key))
            .getOrDefault(emptyList())

    private fun normalizeProviders(value: List<AiCreationProviderConfig>): List<AiCreationProviderConfig> {
        return value.mapNotNull { provider ->
            val name = provider.name.trim()
            val id = provider.id.trim()
            if (name.isEmpty() || id.isEmpty()) {
                null
            } else {
                provider.copy(
                    id = id,
                    name = name,
                    baseUrl = provider.baseUrl.trim(),
                    apiKey = provider.apiKey.trim(),
                    headers = provider.headers.trim(),
                    variablesJson = provider.variablesJson.trim(),
                    requestTemplate = provider.requestTemplate.trim(),
                    apiKeyUrl = provider.apiKeyUrl.trim()
                )
            }
        }.distinctBy { it.id }
    }

    private fun normalizeModels(
        value: List<AiCreationProviderModel>,
        validProviderIds: Set<String>
    ): List<AiCreationProviderModel> {
        return value.mapNotNull { model ->
            val id = model.id.trim()
            val providerId = model.providerId.trim()
            val modelId = model.modelId.trim()
            if (id.isEmpty() || providerId !in validProviderIds || modelId.isEmpty()) {
                null
            } else {
                model.copy(id = id, providerId = providerId, modelId = modelId)
            }
        }.distinctBy { "${it.providerId}|${it.modelId}" }
    }

    //列表永远整体写入（空列表写 "[]" 而不是删除键），
    //保证“已初始化”状态不被误判，用户删光供应商后不会重新种入内置项。
    private fun persistImageProviders(providers: List<AiCreationProviderConfig>) {
        appCtx.putPrefString(PreferKey.aiCreationImageProviderList, GSON.toJson(providers))
    }

    private fun persistImageModels(models: List<AiCreationProviderModel>) {
        appCtx.putPrefString(PreferKey.aiCreationImageModelList, GSON.toJson(models))
    }

    private fun persistVideoProviders(providers: List<AiCreationProviderConfig>) {
        appCtx.putPrefString(PreferKey.aiCreationVideoProviderList, GSON.toJson(providers))
    }

    private fun persistVideoModels(models: List<AiCreationProviderModel>) {
        appCtx.putPrefString(PreferKey.aiCreationVideoModelList, GSON.toJson(models))
    }

    private fun syncImageState(
        providers: List<AiCreationProviderConfig>,
        models: List<AiCreationProviderModel>
    ) {
        val providerId = providers.firstOrNull {
            it.id == appCtx.getPrefString(PreferKey.aiCreationImageCurrentProviderId)
        }?.id ?: providers.firstOrNull()?.id

        if (providerId.isNullOrBlank()) {
            appCtx.removePref(PreferKey.aiCreationImageCurrentProviderId)
            appCtx.removePref(PreferKey.aiCreationImageCurrentModelId)
            return
        }

        if (providerId != appCtx.getPrefString(PreferKey.aiCreationImageCurrentProviderId)) {
            appCtx.putPrefString(PreferKey.aiCreationImageCurrentProviderId, providerId)
        }

        val providerModels = models.filter { it.providerId == providerId }
        val currentModelId = providerModels.firstOrNull {
            it.id == appCtx.getPrefString(PreferKey.aiCreationImageCurrentModelId)
        }?.id ?: providerModels.firstOrNull()?.id

        if (currentModelId.isNullOrBlank()) {
            appCtx.removePref(PreferKey.aiCreationImageCurrentModelId)
        } else if (currentModelId != appCtx.getPrefString(PreferKey.aiCreationImageCurrentModelId)) {
            appCtx.putPrefString(PreferKey.aiCreationImageCurrentModelId, currentModelId)
        }
    }

    private fun syncVideoState(
        providers: List<AiCreationProviderConfig>,
        models: List<AiCreationProviderModel>
    ) {
        val providerId = providers.firstOrNull {
            it.id == appCtx.getPrefString(PreferKey.aiCreationVideoCurrentProviderId)
        }?.id ?: providers.firstOrNull()?.id

        if (providerId.isNullOrBlank()) {
            appCtx.removePref(PreferKey.aiCreationVideoCurrentProviderId)
            appCtx.removePref(PreferKey.aiCreationVideoCurrentModelId)
            return
        }

        if (providerId != appCtx.getPrefString(PreferKey.aiCreationVideoCurrentProviderId)) {
            appCtx.putPrefString(PreferKey.aiCreationVideoCurrentProviderId, providerId)
        }

        val providerModels = models.filter { it.providerId == providerId }
        val currentModelId = providerModels.firstOrNull {
            it.id == appCtx.getPrefString(PreferKey.aiCreationVideoCurrentModelId)
        }?.id ?: providerModels.firstOrNull()?.id

        if (currentModelId.isNullOrBlank()) {
            appCtx.removePref(PreferKey.aiCreationVideoCurrentModelId)
        } else if (currentModelId != appCtx.getPrefString(PreferKey.aiCreationVideoCurrentModelId)) {
            appCtx.putPrefString(PreferKey.aiCreationVideoCurrentModelId, currentModelId)
        }
    }

    /**
     * 首次访问时种入内置图片供应商；键已存在则做一次出厂 apiKey 补齐后不再改写。
     */
    private fun ensureImageConfigIfNeeded() {
        if (appCtx.getPrefString(PreferKey.aiCreationImageProviderList) != null) {
            fillBuiltinApiKeysIfNeeded()
            return
        }
        val providers = builtinImageProviders()
        val models = builtinImageModels()
        persistImageProviders(providers)
        persistImageModels(models)
        appCtx.putPrefString(PreferKey.aiCreationImageCurrentProviderId, IMAGE_SILICONFLOW_ID)
        appCtx.putPrefString(
            PreferKey.aiCreationImageCurrentModelId,
            models.first { it.providerId == IMAGE_SILICONFLOW_ID }.id
        )
        appCtx.putPrefBoolean(PreferKey.aiCreationBuiltinApiKeysFilled, true)
    }

    private fun ensureVideoConfigIfNeeded() {
        if (appCtx.getPrefString(PreferKey.aiCreationVideoProviderList) != null) {
            fillBuiltinApiKeysIfNeeded()
            return
        }
        val providers = builtinVideoProviders()
        val models = builtinVideoModels()
        persistVideoProviders(providers)
        persistVideoModels(models)
        appCtx.putPrefString(PreferKey.aiCreationVideoCurrentProviderId, VIDEO_ZHIPU_ID)
        appCtx.putPrefString(
            PreferKey.aiCreationVideoCurrentModelId,
            models.first { it.providerId == VIDEO_ZHIPU_ID }.id
        )
        appCtx.putPrefBoolean(PreferKey.aiCreationBuiltinApiKeysFilled, true)
    }

    /**
     * 升级补齐：老安装已种入空 apiKey 的内置供应商时，按注册表出厂值补一次。
     * 只补"内置供应商且 apiKey 为空"的项；注册表无出厂值（开源构建）时为无操作。
     * 标志打过后不再改写，用户之后清空 key 保持清空。
     */
    private fun fillBuiltinApiKeysIfNeeded() {
        if (appCtx.getPrefBoolean(PreferKey.aiCreationBuiltinApiKeysFilled)) return
        appCtx.putPrefBoolean(PreferKey.aiCreationBuiltinApiKeysFilled, true)
        fillBlankApiKeys(
            PreferKey.aiCreationImageProviderList,
            mapOf(
                IMAGE_SILICONFLOW_ID to AiBuiltinDefaults.siliconFlowApiKey(),
                IMAGE_ZHIPU_ID to AiBuiltinDefaults.zhipuApiKey()
            )
        )
        fillBlankApiKeys(
            PreferKey.aiCreationVideoProviderList,
            mapOf(VIDEO_ZHIPU_ID to AiBuiltinDefaults.zhipuApiKey())
        )
    }

    private fun fillBlankApiKeys(prefKey: String, factoryKeys: Map<String, String>) {
        val providers = fromJsonProviders(prefKey)
        val filled = providers.map { provider ->
            val factoryKey = factoryKeys[provider.id]
            if (factoryKey.isNullOrBlank() || provider.apiKey.isNotBlank()) {
                provider
            } else {
                provider.copy(apiKey = factoryKey)
            }
        }
        if (filled != providers) {
            appCtx.putPrefString(prefKey, GSON.toJson(filled))
        }
    }
}
