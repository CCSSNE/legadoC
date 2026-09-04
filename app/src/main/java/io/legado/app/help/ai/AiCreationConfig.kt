package io.legado.app.help.ai

import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.constant.PreferKey
import io.legado.app.help.config.AppConfig
import io.legado.app.ui.main.ai.AiModelConfig
import io.legado.app.ui.main.ai.AiProviderConfig
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.getPrefInt
import io.legado.app.utils.getPrefString
import io.legado.app.utils.putPrefBoolean
import io.legado.app.utils.putPrefInt
import io.legado.app.utils.putPrefString
import io.legado.app.utils.removePref
import io.legado.app.utils.toastOnUi
import org.json.JSONObject
import splitties.init.appCtx

data class AiCreationModelTarget(
    val provider: AiProviderConfig,
    val modelId: String
)

object AiCreationConfig {

    const val DEFAULT_IMAGE_RETRY_COUNT = 3
    const val MIN_IMAGE_RETRY_COUNT = 0
    const val MAX_IMAGE_RETRY_COUNT = 10

    const val DEFAULT_PROMPT_REGENERATE_LIMIT = 3
    const val MIN_PROMPT_REGENERATE_LIMIT = 0
    const val MAX_PROMPT_REGENERATE_LIMIT = 10

    const val SECTION_SELECTED_TEXT = "selected_text"
    const val SECTION_BACKGROUND = "background"
    const val SECTION_SCENE = "scene"
    const val SECTION_CHARACTER = "character"
    const val SECTION_NOTE = "note"

    val sectionOrder = listOf(
        SECTION_SELECTED_TEXT,
        SECTION_BACKGROUND,
        SECTION_SCENE,
        SECTION_CHARACTER,
        SECTION_NOTE
    )

    const val SCOPE_GLOBAL = "global"
    const val SCOPE_BOOK = "book"
    const val SCOPE_SESSION = "session"
    val scopeValues = listOf(SCOPE_GLOBAL, SCOPE_BOOK, SCOPE_SESSION)

    /**
     * 唯一的提示词模板：JSON 对象的 key 是名字，value 是无占位符的纯文本提示词。
     * 图片/视频供应商变量 JSON 中的路由按 key 引用此对象。
     */
    val defaultPromptTemplateJson: String by lazy {
        JSONObject().apply {
            put(
                "连环画",
                "将素材拆分为连续分镜，每格包含画面描述、构图与镜头调度。"
            )
            put(
                "单场景",
                "一个完整画面，涵盖主体、环境、光影与构图。"
            )
            put(
                "多镜头",
                "将素材拆分为连续镜头，每个镜头包含画面、动作与运镜描述。"
            )
            put(
                "单镜头",
                "一个连续镜头，涵盖主体、动作、环境与运镜。"
            )
        }.toString()
    }

    var reuseCurrentModel: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.aiCreationReuseCurrentModel, true)
        set(value) = appCtx.putPrefBoolean(PreferKey.aiCreationReuseCurrentModel, value)

    var independentProviderId: String
        get() = appCtx.getPrefString(PreferKey.aiCreationProvider).orEmpty().trim()
        set(value) = appCtx.putPrefString(PreferKey.aiCreationProvider, value.trim())

    val independentProvider: AiProviderConfig?
        get() = AppConfig.aiProviderList.firstOrNull { it.id == independentProviderId }

    var independentModelId: String
        get() = appCtx.getPrefString(PreferKey.aiCreationModel).orEmpty().trim()
        set(value) = appCtx.putPrefString(PreferKey.aiCreationModel, value.trim())

    val independentModel: AiModelConfig?
        get() = AppConfig.aiModelConfigList.firstOrNull {
            it.id == independentModelId && it.providerId == independentProviderId
        }

    /**
     * “提示词模板”设置本身就是完整 JSON 对象；不存在第二个提示词库或单条系统提示词。
     */
    var promptTemplateJson: String
        get() = appCtx.getPrefString(PreferKey.aiCreationPromptTemplate)
            ?: defaultPromptTemplateJson
        set(value) {
            val normalized = value.trim()
            parsePromptTemplates(normalized)
            appCtx.putPrefString(PreferKey.aiCreationPromptTemplate, normalized)
        }

    /**
     * 全局 LLM 变量设置：控制发给 LLM 的内容（style 变量、提示词路由、LLM 输入模板），
     * 与图片/视频供应商无关；供应商变量定义只含生图/生视频参数。
     */
    val defaultLlmVariablesJson: String by lazy { AiCreationVariables.buildLlmDefaultJson() }

    var llmVariablesJson: String
        get() = appCtx.getPrefString(PreferKey.aiCreationLlmVariables)
            ?: defaultLlmVariablesJson
        set(value) {
            val normalized = value.trim()
            AiCreationVariables.parseLlm(normalized)
            appCtx.putPrefString(PreferKey.aiCreationLlmVariables, normalized)
        }

    /** 图片体系的 LLM 变量定义（LLM 变量设置 image 节）。 */
    val imageLlmDefinition: AiCreationDefinition
        get() = AiCreationVariables.parseLlm(llmVariablesJson).image
            ?: error("LLM 变量设置缺少 image 节")

    /** 视频体系的 LLM 变量定义（LLM 变量设置 video 节）。 */
    val videoLlmDefinition: AiCreationDefinition
        get() = AiCreationVariables.parseLlm(llmVariablesJson).video
            ?: error("LLM 变量设置缺少 video 节")

    /** 当前图片供应商的生图参数变量（旧格式残留自动回出厂）。 */
    val imageVariables: List<AiCreationVariable>
        get() = AiCreationProviderStore.parsedImageVariables()

    /** 当前视频供应商的生视频参数变量（旧格式残留自动回出厂）。 */
    val videoVariables: List<AiCreationVariable>
        get() = AiCreationProviderStore.parsedVideoVariables()

    val promptTemplates: Map<String, String>
        get() = parsePromptTemplates(promptTemplateJson)

    /**
     * 安装/升级后一次性消毒：存量 LLM 变量设置与提示词模板非法时用出厂值覆盖，
     * 并响亮告知恢复了哪几项；版本戳由调用方（DefaultData）打标，此后用户手写
     * 错误只报错、不再碰存储。供应商变量定义不在此列：旧格式残留已在读取时
     * 自愈，其他错误本就是用户自己的问题，原样报错。
     */
    fun sanitizeStoredJsons() {
        val restored = mutableListOf<String>()
        runCatching { AiCreationVariables.parseLlm(llmVariablesJson) }.onFailure {
            llmVariablesJson = defaultLlmVariablesJson
            restored.add(appCtx.getString(R.string.ai_creation_llm_variables))
        }
        runCatching { parsePromptTemplates(promptTemplateJson) }.onFailure {
            promptTemplateJson = defaultPromptTemplateJson
            restored.add(appCtx.getString(R.string.ai_creation_prompt_template))
        }
        if (restored.isNotEmpty()) {
            AppLog.putAi(
                "AI_CREATION CONFIG SANITIZED\n" +
                    "restored=${restored.joinToString("、")}"
            )
            appCtx.toastOnUi(
                appCtx.getString(
                    R.string.ai_creation_config_sanitized,
                    restored.joinToString("、")
                )
            )
        }
    }

    fun parsePromptTemplates(json: String): Map<String, String> {
        val objectValue = try {
            JSONObject(json)
        } catch (throwable: Throwable) {
            throw IllegalStateException(
                "提示词模板必须是 JSON 对象（名字→纯文本）：${throwable.message}",
                throwable
            )
        }
        require(objectValue.length() > 0) { "提示词模板不能为空" }
        val templates = linkedMapOf<String, String>()
        val keys = objectValue.keys()
        while (keys.hasNext()) {
            val name = keys.next()
            require(name.isNotBlank() && name == name.trim()) { "提示词模板存在空白名字" }
            val text = objectValue.opt(name)
            require(text is String) { "提示词「${name}」必须是纯文本" }
            require(text.isNotBlank()) { "提示词「${name}」的内容为空" }
            require(!PROMPT_TEMPLATE_PLACEHOLDER.containsMatchIn(text)) {
                "提示词「${name}」必须是无占位符的纯文本"
            }
            templates[name] = text
        }
        return templates
    }

    fun resolvePromptName(
        definition: AiCreationDefinition,
        params: Map<String, String>
    ): String {
        val matched = definition.routes.firstOrNull { route ->
            route.conditions.all { (key, value) -> params[key] == value }
        } ?: throw IllegalStateException(
            "没有命中任何提示词路由，当前参数：" +
                params.entries.joinToString("，") { "${it.key}=${it.value}" }
        )
        return matched.prompt
    }

    /** 按供应商路由命中的名字，从唯一提示词模板 JSON 取纯文本。 */
    fun promptTextOf(name: String): String {
        return promptTemplates[name]
            ?: throw IllegalStateException("路由指向的提示词不存在：${name}")
    }

    /**
     * 图片请求链路的就绪校验：当前图片供应商与模型必须已就绪（连接信息全部来自供应商配置）。
     */
    fun requireImageApiReady() {
        AiCreationProviderStore.requireImageTarget()
    }

    var imageRetryCount: Int
        get() = appCtx.getPrefInt(PreferKey.aiCreationImageRetryCount, DEFAULT_IMAGE_RETRY_COUNT)
            .coerceIn(MIN_IMAGE_RETRY_COUNT, MAX_IMAGE_RETRY_COUNT)
        set(value) = appCtx.putPrefInt(
            PreferKey.aiCreationImageRetryCount,
            value.coerceIn(MIN_IMAGE_RETRY_COUNT, MAX_IMAGE_RETRY_COUNT)
        )

    /**
     * 提示词重新生成上限：LLM 返回的标记校验不通过时允许的额外重发次数，
     * 0 表示不重新生成，校验失败直接报错。
     */
    var promptRegenerateLimit: Int
        get() = appCtx.getPrefInt(
            PreferKey.aiCreationPromptRegenerateLimit,
            DEFAULT_PROMPT_REGENERATE_LIMIT
        ).coerceIn(MIN_PROMPT_REGENERATE_LIMIT, MAX_PROMPT_REGENERATE_LIMIT)
        set(value) = appCtx.putPrefInt(
            PreferKey.aiCreationPromptRegenerateLimit,
            value.coerceIn(MIN_PROMPT_REGENERATE_LIMIT, MAX_PROMPT_REGENERATE_LIMIT)
        )

    fun requireModelTarget(): AiCreationModelTarget {
        if (reuseCurrentModel) {
            val provider = AppConfig.aiCurrentProvider
                ?: error("请先配置当前 AI 提供商，或关闭“复用当前 AI 模型”后选择 AI 创作模型")
            val model = AppConfig.aiCurrentModelConfig?.modelId.orEmpty()
            check(model.isNotBlank()) {
                "请先配置当前 AI 模型，或关闭“复用当前 AI 模型”后选择 AI 创作模型"
            }
            return AiCreationModelTarget(provider, model)
        }
        val provider = independentProvider
            ?: error("请先在 AI 设置中选择 AI 创作供应商（或开启“复用当前 AI 模型”）")
        check(provider.baseUrl.isNotBlank()) { "AI 创作所选供应商的 API 地址不能为空" }
        val model = independentModel
            ?.takeIf { it.providerId == provider.id }
            ?: error("请先在 AI 设置中选择 AI 创作模型（或开启“复用当前 AI 模型”）")
        return AiCreationModelTarget(provider, model.modelId)
    }

    fun scopeKeyOf(section: String): String = when (section) {
        SECTION_SELECTED_TEXT -> PreferKey.aiCreationScopeSelectedText
        SECTION_BACKGROUND -> PreferKey.aiCreationScopeBackground
        SECTION_SCENE -> PreferKey.aiCreationScopeScene
        SECTION_CHARACTER -> PreferKey.aiCreationScopeCharacter
        SECTION_NOTE -> PreferKey.aiCreationScopeNote
        else -> PreferKey.aiCreationScopeBackground
    }

    fun sectionScope(section: String): String {
        val raw = appCtx.getPrefString(scopeKeyOf(section)).orEmpty()
        return if (raw in scopeValues) raw else defaultScopeOf(section)
    }

    fun setSectionScope(section: String, scope: String) {
        if (scope !in scopeValues) return
        if (scope == defaultScopeOf(section)) {
            appCtx.removePref(scopeKeyOf(section))
        } else {
            appCtx.putPrefString(scopeKeyOf(section), scope)
        }
    }

    /**
     * 创作界面第一页参数记忆的唯一持久化入口：
     * 变量值整体存为一个 JSON 对象，会话写参数时实时落盘。
     */
    fun loadCreationParams(): LinkedHashMap<String, String> {
        val json = appCtx.getPrefString(PreferKey.aiCreationParams).orEmpty()
        if (json.isBlank()) return linkedMapOf()
        val obj = JSONObject(json)
        val result = linkedMapOf<String, String>()
        for (key in obj.keys()) {
            result[key] = obj.optString(key)
        }
        return result
    }

    fun saveCreationParams(params: Map<String, String>) {
        val obj = JSONObject()
        for ((key, value) in params) {
            obj.put(key, value)
        }
        appCtx.putPrefString(PreferKey.aiCreationParams, obj.toString())
    }

    fun defaultScopeOf(section: String): String = when (section) {
        SECTION_NOTE -> SCOPE_SESSION
        SECTION_SELECTED_TEXT -> SCOPE_SESSION
        else -> SCOPE_GLOBAL
    }

    private val PROMPT_TEMPLATE_PLACEHOLDER =
        Regex("\\$\\{[^{}]+\\}|\\{\\{[^{}]+\\}\\}")
}
