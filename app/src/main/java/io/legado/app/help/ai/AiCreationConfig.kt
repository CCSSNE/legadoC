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

data class AiCreationNamedTemplate(
    val name: String,
    val body: String
)

object AiCreationConfig {

    const val DEFAULT_IMAGE_RETRY_COUNT = 3
    const val MIN_IMAGE_RETRY_COUNT = 0
    const val MAX_IMAGE_RETRY_COUNT = 10

    // 默认使用智谱 CogView 生图接口（API Key 仍需用户自行配置）
    const val DEFAULT_IMAGE_URL = "https://open.bigmodel.cn/api/paas/v4/images/generations"
    const val DEFAULT_IMAGE_MODEL = "cogview-3-flash"

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

    private fun defaultTemplateBody(userContent: String): JSONObject {
        return JSONObject().apply {
            put("model", "{{model}}")
            put("stream", true)
            put(
                "messages",
                JSONArray().apply {
                    put(
                        JSONObject()
                            .put("role", "system")
                            .put("content", "{{systemPrompt}}")
                    )
                    put(
                        JSONObject()
                            .put("role", "user")
                            .put("content", userContent)
                    )
                }
            )
            put("temperature", 0.7)
        }
    }

    val defaultRequestTemplatesJson: String by lazy {
        JSONArray().apply {
            put(
                JSONObject()
                    .put("name", "连环画")
                    .put(
                        "body",
                        defaultTemplateBody(
                            "本次按连环画分镜脚本生成提示词：将素材拆分为连续分镜，" +
                                "每格包含画面描述、构图与镜头调度。\n\n素材：\n\${素材}\n\n" +
                                "风格：\${style}"
                        )
                    )
            )
            put(
                JSONObject()
                    .put("name", "单场景")
                    .put(
                        "body",
                        defaultTemplateBody(
                            "本次生成单场景精绘提示词：一个完整画面，" +
                                "涵盖主体、环境、光影与构图。\n\n素材：\n\${素材}\n\n" +
                                "风格：\${style}"
                        )
                    )
            )
            put(
                JSONObject()
                    .put("name", "视频")
                    .put(
                        "body",
                        defaultTemplateBody(
                            "本次生成视频提示词：分辨率 \${video_size}，" +
                                "帧率 \${video_fps}，时长 \${video_duration} 秒。\n\n素材：\n\${素材}"
                        )
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

    var variablesJson: String
        get() = appCtx.getPrefString(PreferKey.aiCreationVariables)
            ?.takeIf { it.isNotBlank() }
            ?: AiCreationVariables.defaultJson
        set(value) {
            val normalized = value.trim()
            appCtx.putPrefString(
                PreferKey.aiCreationVariables,
                if (normalized == AiCreationVariables.defaultJson) "" else normalized
            )
        }

    val definition: AiCreationDefinition
        get() = AiCreationVariables.parse(variablesJson)

    var requestTemplatesJson: String
        get() = appCtx.getPrefString(PreferKey.aiCreationRequestTemplate)
            ?.takeIf { it.isNotBlank() }
            ?: defaultRequestTemplatesJson
        set(value) {
            val normalized = value.trim()
            parseRequestTemplates(normalized)
            appCtx.putPrefString(PreferKey.aiCreationRequestTemplate, normalized)
        }

    val requestTemplates: List<AiCreationNamedTemplate>
        get() = parseRequestTemplates(requestTemplatesJson)

    fun parseRequestTemplates(json: String): List<AiCreationNamedTemplate> {
        val array = try {
            JSONArray(json)
        } catch (throwable: Throwable) {
            throw IllegalStateException(
                "AI 创作请求模板必须是 JSON 数组：${throwable.message}",
                throwable
            )
        }
        val list = mutableListOf<AiCreationNamedTemplate>()
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index)
                ?: throw IllegalStateException("AI 创作请求模板第 ${index + 1} 项必须是对象")
            val name = item.optString("name").trim()
            require(name.isNotEmpty()) { "AI 创作请求模板第 ${index + 1} 项缺少识别名 name" }
            val body = item.optJSONObject("body")
                ?: throw IllegalStateException("AI 创作请求模板「$name」缺少 body 或 body 不是 JSON 对象")
            list.add(AiCreationNamedTemplate(name, body.toString()))
        }
        val duplicated = list.groupBy { it.name }.filterValues { it.size > 1 }.keys
        require(duplicated.isEmpty()) { "AI 创作请求模板识别名重复：${duplicated.joinToString("，")}" }
        return list
    }

    fun resolveTemplateName(
        definition: AiCreationDefinition,
        params: Map<String, String>
    ): String {
        val matched = definition.routes.firstOrNull { route ->
            route.conditions.all { (key, value) -> params[key] == value }
        } ?: throw IllegalStateException(
            "没有命中任何请求模板路由，当前参数：" +
                params.entries.joinToString("，") { "${it.key}=${it.value}" }
        )
        return matched.template
    }

    fun requestTemplateBody(name: String): String {
        return requestTemplates.firstOrNull { it.name == name }?.body
            ?: throw IllegalStateException("路由指向的请求模板不存在：$name")
    }

    val defaultImageRequestTemplateJson: String = """
        {
          "model": "{{model}}",
          "prompt": "{{prompt}}",
          "n": {{n}},
          "size": "{{size}}",
          "quality": "{{quality}}",
          "watermark_enabled": {{watermark_enabled}}
        }
    """.trimIndent()

    var imageUrl: String
        get() = appCtx.getPrefString(PreferKey.aiCreationImageUrl)
            ?.takeIf { it.isNotBlank() }
            ?: DEFAULT_IMAGE_URL
        set(value) {
            val normalized = value.trim()
            appCtx.putPrefString(
                PreferKey.aiCreationImageUrl,
                if (normalized == DEFAULT_IMAGE_URL) "" else normalized
            )
        }

    var imageApiKey: String
        get() = appCtx.getPrefString(PreferKey.aiCreationImageApiKey).orEmpty().trim()
        set(value) = appCtx.putPrefString(PreferKey.aiCreationImageApiKey, value.trim())

    var imageModel: String
        get() = appCtx.getPrefString(PreferKey.aiCreationImageModel)
            ?.takeIf { it.isNotBlank() }
            ?: DEFAULT_IMAGE_MODEL
        set(value) {
            val normalized = value.trim()
            appCtx.putPrefString(
                PreferKey.aiCreationImageModel,
                if (normalized == DEFAULT_IMAGE_MODEL) "" else normalized
            )
        }

    var imageRetryCount: Int
        get() = appCtx.getPrefInt(PreferKey.aiCreationImageRetryCount, DEFAULT_IMAGE_RETRY_COUNT)
            .coerceIn(MIN_IMAGE_RETRY_COUNT, MAX_IMAGE_RETRY_COUNT)
        set(value) = appCtx.putPrefInt(
            PreferKey.aiCreationImageRetryCount,
            value.coerceIn(MIN_IMAGE_RETRY_COUNT, MAX_IMAGE_RETRY_COUNT)
        )

    var imageRequestTemplate: String
        get() = appCtx.getPrefString(PreferKey.aiCreationImageRequestTemplate)
            ?.takeIf { it.isNotBlank() }
            ?: defaultImageRequestTemplateJson
        set(value) {
            appCtx.putPrefString(
                PreferKey.aiCreationImageRequestTemplate,
                parseImageRequestTemplate(value)
            )
        }

    fun parseImageRequestTemplate(json: String): String {
        val normalized = json.trim()
        require(normalized.isNotEmpty()) { "图片请求模板不能为空" }
        try {
            //校验时把所有占位符换成字面 1，裸占位符（布尔/数字值位置）与带引号占位符都能通过解析
            JSONObject(normalized.replace(Regex("\\{\\{[^}]*\\}\\}"), "1"))
        } catch (throwable: Throwable) {
            throw IllegalStateException(
                "图片请求模板 JSON 无效：${throwable.message}",
                throwable
            )
        }
        return normalized
    }

    fun requireImageApiReady() {
        require(imageUrl.isNotBlank()) { "请先在 AI 设置中配置图片 API 地址" }
        require(imageModel.isNotBlank()) { "请先在 AI 设置中配置图片模型" }
    }

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
