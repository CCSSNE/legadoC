package io.legado.app.help.ai

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
import org.json.JSONArray
import org.json.JSONObject
import splitties.init.appCtx

data class AiCreationModelTarget(
    val provider: AiProviderConfig,
    val modelId: String
)

data class AiCreationPromptTemplate(
    val name: String,
    val text: String
)

object AiCreationConfig {

    const val DEFAULT_IMAGE_RETRY_COUNT = 3
    const val MIN_IMAGE_RETRY_COUNT = 0
    const val MAX_IMAGE_RETRY_COUNT = 10

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

    val defaultPromptTemplate = """
        你是专业的 AI 绘画与视频提示词生成器。
        请根据用户消息中的素材与要求，生成一段高质量的提示词。

        要求：
        1. 只输出最终提示词正文，不要任何解释、前言或标题。
        2. 使用中文，画面描述具体可执行，充分利用素材中的场景、人设与参考信息。
        3. 素材未覆盖的部分按用户消息中的要求补全，不要虚构与素材冲突的设定。
    """.trimIndent()

    /**
     * 提示词库默认 4 条：名字被变量定义里的路由按名字引用。
     * 图片路由引用连环画/单场景，视频路由引用多镜头/单镜头。
     */
    val defaultPromptLibraryJson: String by lazy {
        JSONArray().apply {
            put(
                JSONObject()
                    .put("name", "连环画")
                    .put(
                        "text",
                        "本次按连环画分镜脚本生成提示词：将素材拆分为连续分镜，" +
                            "每格包含画面描述、构图与镜头调度。\n\n素材：\n\${素材}\n\n" +
                            "风格：\${style}"
                    )
            )
            put(
                JSONObject()
                    .put("name", "单场景")
                    .put(
                        "text",
                        "本次生成单场景精绘提示词：一个完整画面，" +
                            "涵盖主体、环境、光影与构图。\n\n素材：\n\${素材}\n\n" +
                            "风格：\${style}"
                    )
            )
            put(
                JSONObject()
                    .put("name", "多镜头")
                    .put(
                        "text",
                        "本次生成多镜头视频提示词：将素材拆分为连续镜头，" +
                            "每个镜头包含画面、动作与运镜描述。\n\n素材：\n\${素材}\n\n" +
                            "风格：\${style}"
                    )
            )
            put(
                JSONObject()
                    .put("name", "单镜头")
                    .put(
                        "text",
                        "本次生成单镜头视频提示词：一个连续镜头，" +
                            "涵盖主体、动作、环境与运镜。\n\n素材：\n\${素材}\n\n" +
                            "风格：\${style}"
                    )
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

    var promptTemplate: String
        get() = appCtx.getPrefString(PreferKey.aiCreationPromptTemplate)
            ?.takeIf { it.isNotBlank() }
            ?: defaultPromptTemplate
        set(value) {
            val normalized = value.trim()
            appCtx.putPrefString(
                PreferKey.aiCreationPromptTemplate,
                if (normalized == defaultPromptTemplate) "" else normalized
            )
        }

    /**
     * 图片体系的变量定义：来自当前图片供应商的「图片变量定义」JSON，只取图片组。
     */
    val imageDefinition: AiCreationDefinition
        get() = AiCreationVariables.parse(AiCreationProviderStore.requireImageVariablesJson())
            .keepGroups(AiCreationVariables.GROUP_IMAGE, "图片")

    /**
     * 视频体系的变量定义：来自当前视频供应商的「视频变量定义」JSON，只取视频组。
     * 与图片体系完全独立，互不引用。
     */
    val videoDefinition: AiCreationDefinition
        get() = AiCreationVariables.parse(AiCreationProviderStore.requireVideoVariablesJson())
            .keepGroups(AiCreationVariables.GROUP_VIDEO, "视频")

    var promptLibraryJson: String
        get() = appCtx.getPrefString(PreferKey.aiCreationPromptLibrary)
            ?.takeIf { it.isNotBlank() }
            ?: defaultPromptLibraryJson
        set(value) {
            val normalized = value.trim()
            parsePromptLibrary(normalized)
            appCtx.putPrefString(PreferKey.aiCreationPromptLibrary, normalized)
        }

    val promptLibrary: List<AiCreationPromptTemplate>
        get() = parsePromptLibrary(promptLibraryJson)

    fun parsePromptLibrary(json: String): List<AiCreationPromptTemplate> {
        val array = try {
            JSONArray(json)
        } catch (throwable: Throwable) {
            throw IllegalStateException(
                "提示词库必须是 JSON 数组：${throwable.message}",
                throwable
            )
        }
        val list = mutableListOf<AiCreationPromptTemplate>()
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index)
                ?: throw IllegalStateException("提示词库第 ${index + 1} 项必须是对象")
            val name = item.optString("name").trim()
            require(name.isNotEmpty()) { "提示词库第 ${index + 1} 项缺少名字 name" }
            val text = item.optString("text")
            require(text.isNotBlank()) { "提示词「$name」的内容为空" }
            list.add(AiCreationPromptTemplate(name, text))
        }
        val duplicated = list.groupBy { it.name }.filterValues { it.size > 1 }.keys
        require(duplicated.isEmpty()) { "提示词库名字重复：${duplicated.joinToString("，")}" }
        return list
    }

    fun resolveTemplateName(
        definition: AiCreationDefinition,
        params: Map<String, String>
    ): String {
        val matched = definition.routes.firstOrNull { route ->
            route.conditions.all { (key, value) -> params[key] == value }
        } ?: throw IllegalStateException(
            "没有命中任何提示词路由，当前参数：" +
                params.entries.joinToString("，") { "${it.key}=${it.value}" }
        )
        return matched.template
    }

    /** 按路由命中的名字从提示词库取提示词文本 */
    fun promptTextOf(name: String): String {
        return promptLibrary.firstOrNull { it.name == name }?.text
            ?: throw IllegalStateException("路由指向的提示词不存在：$name")
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

    fun requireModelTarget(): AiCreationModelTarget {        if (reuseCurrentModel) {
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
     * 变量值（含 mode 保留键）整体存为一个 JSON 对象，会话写参数时实时落盘，
     * 应用重启后由 AiCreationSession 初始化载入，实现"上次是啥下次还是啥"。
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
        // 选中文本卡片默认一次性：随创作界面关闭或清空动作销毁，长按条暂存可连续累积多张
        SECTION_SELECTED_TEXT -> SCOPE_SESSION
        else -> SCOPE_GLOBAL
    }
}
