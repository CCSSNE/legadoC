package io.legado.app.help.ai

import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.constant.PreferKey
import io.legado.app.help.config.AppConfig
import io.legado.app.ui.main.ai.AiModelConfig
import io.legado.app.ui.main.ai.AiProviderConfig
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.getPrefInt
import io.legado.app.utils.getPrefLong
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

    /**
     * AI 创作发 LLM 专用请求模板：不再蹭全局通用模板，出厂即干净 default；
     * 独占的 {{userContent}} 就是图片位（数组进来时图从这里发），模板里删了它，
     * 有图直接报错不发，无图连文本都无处可去同样报错。
     */
    var requestTemplate: String
        get() = appCtx.getPrefString(PreferKey.aiCreationRequestTemplate)
            ?.takeIf { it.isNotBlank() }
            ?: AiStructuredRequestTemplate.default
        set(value) = appCtx.putPrefString(PreferKey.aiCreationRequestTemplate, value.trim())

    val promptTemplates: Map<String, String>
        get() = parsePromptTemplates(promptTemplateJson)

    /**
     * 强升级：AI 全部 JSON 配置回到出厂（内置图片/视频供应商的变量定义与请求模板、
     * 全局 LLM 变量设置、提示词模板、全局通用请求模板、创作/分镜/选角/净化请求模板）。
     * 只保留身份与连线信息：供应商 id、名字、地址、钥匙、自定义请求头、模型列表与当前选择；
     * 自定义供应商一律不动。调用方只在版本戳升级时调一次，平时不碰用户配置。
     */
    fun forceRestoreFactoryDefaults() {
        AiCreationProviderStore.restoreBuiltinToFactory()
        llmVariablesJson = defaultLlmVariablesJson
        promptTemplateJson = defaultPromptTemplateJson
        AiStructuredRequestTemplate.global = AiStructuredRequestTemplate.default
        requestTemplate = AiStructuredRequestTemplate.default
        AiStoryboardConfig.storyboardRequestTemplate = AiStructuredRequestTemplate.structuredDefault
        AiStoryboardConfig.castingRequestTemplate = AiStructuredRequestTemplate.structuredDefault
        AiChapterPurifyConfig.requestTemplate = AiStructuredRequestTemplate.structuredDefault
        AppLog.putAi(
            "AI_CREATION CONFIG FORCE RESTORED\n" +
                "scope=providerVariables,requestTemplate,llmVariables,promptTemplate,globalRequestTemplate,creationRequestTemplate,storyboardRequestTemplate,castingRequestTemplate,purifyRequestTemplate\n" +
                "kept=providerId,name,baseUrl,apiKey,headers,models,currentSelection"
        )
    }

    /**
     * 破坏性更新硬开关：true 表示本版本有破坏性更新，升级时不检测、
     * 直接把 AI 下面所有 JSON 配置全部炸回出厂（自加的供应商整家删除）。
     * 没有破坏性更新的版本把它置 false，升级时只把内置回出厂，自加的不动。
     */
    const val NUKE_CUSTOM_AI_CONFIG_ON_UPGRADE = true

    /**
     * 版本号一变就按开关处理，不另维护升级号：完整比对版本名加版本号，
     * 有一个不一样就算变（同号不同时间戳的包也能分出来）。
     * 两个记号都没有=新装，只记号不炸不弹；
     * 记号跟当前不一样=升级上来了，开关开着直接全炸，关着只把内置回出厂。
     * 老版本记的是纯数字旧记号，先读它再删，读不到才算新装；
     * 之前先删后读，把老用户全当成了新装，这是 bug，已改。
     * 每次启动都跑，便宜的几次读值比对。
     */
    fun nukeOnAppVersionChange() {
        val currentTag = runCatching { appVersionTag() }.getOrNull() ?: return
        val currentCode = currentTag.substringAfterLast('|', "").toLongOrNull()
        val lastTag = appCtx.getPrefString(PreferKey.aiNukeAppVersion, "").orEmpty()
        //老记号（纯数字版本号）：先读出来比对，再删，不跟新记号打架
        val legacyCode = appCtx.getPrefLong("aiNukeVersionCode", 0L)
        appCtx.removePref("aiNukeVersionCode")
        if (lastTag.isBlank() && legacyCode == 0L) {
            appCtx.putPrefString(PreferKey.aiNukeAppVersion, currentTag)
            return
        }
        val changed = if (lastTag.isNotBlank()) {
            lastTag != currentTag
        } else {
            currentCode == null || legacyCode != currentCode
        }
        if (!changed) {
            //记号对上了只是格式老，把新记号对齐，不炸
            appCtx.putPrefString(PreferKey.aiNukeAppVersion, currentTag)
            return
        }
        if (NUKE_CUSTOM_AI_CONFIG_ON_UPGRADE) {
            nukeAllAiJsonConfigs()
        } else {
            forceRestoreFactoryDefaults()
        }
        sanitizeStoredJsons()
        AiStructuredRequestTemplate.migrateTemplateOwnership()
        appCtx.putPrefString(PreferKey.aiNukeAppVersion, currentTag)
    }

    private fun appVersionTag(): String {
        val info = appCtx.packageManager.getPackageInfo(appCtx.packageName, 0)
        val code = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }
        return "${info.versionName.orEmpty()}|$code"
    }

    /**
     * 炸：AI 下面所有 JSON 配置全部回到出厂。
     * 名单删了下次读自动重种内置；自加的供应商整家消失（模型连带清掉）；
     * 钥匙名字地址跟着自加行一起走，认了。只在版本升级时调一次。
     */
    fun nukeAllAiJsonConfigs() {
        appCtx.removePref(PreferKey.aiCreationImageProviderList)
        appCtx.removePref(PreferKey.aiCreationImageModelList)
        appCtx.removePref(PreferKey.aiCreationVideoProviderList)
        appCtx.removePref(PreferKey.aiCreationVideoModelList)
        appCtx.removePref(PreferKey.aiProviderList)
        appCtx.removePref(PreferKey.aiModelConfigList)
        appCtx.putPrefBoolean(PreferKey.aiDefaultConfigSeeded, false)
        saveCreationParams(emptyMap())
        forceRestoreFactoryDefaults()
        val summary = "本版本含破坏性更新，AI配置已全部恢复出厂，自加供应商已删除"
        AppLog.putAi("AI_CONFIG NUKED\n$summary")
        appCtx.toastOnUi(summary)
    }

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
        sanitizeCreationParamValues()
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

    /**
     * 存量参数值全量消毒：按当前变量定义检查参数记忆里的所有已存值，
     * 取值与当前定义不符（选项/开关变更、跨体系残留）的直接重置为定义默认值。
     * 覆盖 LLM 变量与全部供应商（含非当前）的参数键；
     * 某个定义本身读不出来时跳过该组，错误由读取路径按既有规则报。
     */
    private fun sanitizeCreationParamValues() {
        val params = loadCreationParams()
        val groups = mutableListOf<Pair<String, List<AiCreationVariable>>>()
        runCatching { imageLlmDefinition }.onSuccess {
            groups.add("llm:image" to it.variables)
        }
        runCatching { videoLlmDefinition }.onSuccess {
            groups.add("llm:video" to it.variables)
        }
        AiCreationProviderStore.imageProviderList.forEach { provider ->
            runCatching { AiCreationVariables.parse(provider.variablesJson) }.onSuccess {
                groups.add("provider:image:${provider.id}" to it)
            }
        }
        AiCreationProviderStore.videoProviderList.forEach { provider ->
            runCatching { AiCreationVariables.parse(provider.variablesJson) }.onSuccess {
                groups.add("provider:video:${provider.id}" to it)
            }
        }
        var changed = false
        groups.forEach { (prefix, variables) ->
            variables.forEach { variable ->
                if (variable.format == AiCreationVariable.FORMAT_INPUT) return@forEach
                val key = "$prefix:${variable.key}"
                val stored = params[key] ?: return@forEach
                if (!variable.accepts(stored)) {
                    params[key] = variable.defaultValue
                    changed = true
                    AppLog.putAi(
                        "AI_CREATION PARAM RESET\nkey=$key value=$stored -> ${variable.defaultValue}"
                    )
                }
            }
        }
        if (changed) {
            saveCreationParams(params)
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
