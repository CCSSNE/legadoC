package io.legado.app.help.ai

import com.google.gson.annotations.SerializedName
import io.legado.app.data.entities.CreationCard
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import org.json.JSONObject

const val AI_CREATION_EPHEMERAL_BOOK = "\u0000ephemeral"
const val AI_CREATION_MODE_KEY = "mode"
const val AI_CREATION_IMAGE_COUNT_KEY = "imageCount"

/** 工作流溯源：最近一次渲染后发给 LLM 的完整输入（不经 LLM 生成时为空串） */
const val AI_CREATION_LLM_INPUT_KEY = "llmInput"

data class AiCreationVariable(
    val key: String,
    val label: String,
    val format: String = AiCreationVariable.FORMAT_OPTIONS,
    val options: List<String> = emptyList(),
    val defaultValue: String = "",
    val values: List<String> = emptyList(),
    val onValue: String = "true",
    val offValue: String = "false"
) {
    companion object {
        const val FORMAT_SWITCH = "switch"
        const val FORMAT_OPTIONS = "options"
        const val FORMAT_INPUT = "input"
        val formats = listOf(FORMAT_SWITCH, FORMAT_OPTIONS, FORMAT_INPUT)
    }

    /**
     * 选项式变量的实际取值：values 与 options 一一对应时用 values（纯 API 值），
     * 否则选项显示与取值同体（options）。
     */
    fun effectiveValues(): List<String> =
        if (values.isNotEmpty() && values.size == options.size) values else options

    /** 值是否属于变量当前定义的合法取值（input 格式不设限） */
    fun accepts(value: String): Boolean = when (format) {
        FORMAT_SWITCH -> value == onValue || value == offValue
        FORMAT_OPTIONS -> effectiveValues().contains(value)
        else -> true
    }

    /** 缺省时采用定义默认值；已有值不合法直接暴露配置错误。 */
    fun effectiveValue(stored: String?): String {
        val value = stored ?: defaultValue
        require(accepts(value)) {
            "AI 创作变量 $key 的当前值无效：$value"
        }
        return value
    }
}

/** 创作页的显示分组；它不是供应商变量 JSON 的字段。 */
data class AiCreationVariableGroup(
    val key: String,
    val label: String,
    val variables: List<AiCreationVariable> = emptyList()
)

data class AiCreationRoute(
    @SerializedName("when") val conditions: Map<String, String> = emptyMap(),
    val prompt: String = ""
)

data class AiCreationVariableDoc(
    val variables: List<AiCreationVariable>? = null
)

/**
 * LLM 变量设置的一节：style 变量 + 提示词路由 + LLM 输入模板，
 * 三者共同决定发给 LLM 的内容，与图片/视频供应商无关。
 */
data class AiCreationDefinition(
    val variables: List<AiCreationVariable>,
    val routes: List<AiCreationRoute>,
    val llmInputTemplate: String
)

/**
 * 全局 LLM 变量设置：image/video 两节各自独立（style 选项、路由目标、输入模板都不同）。
 */
data class AiCreationLlmVariableDoc(
    val image: AiCreationDefinition? = null,
    val video: AiCreationDefinition? = null
)

object AiCreationVariables {

    const val GROUP_IMAGE = "image"
    const val GROUP_VIDEO = "video"

    private const val IMAGE_LLM_INPUT_TEMPLATE =
        "根据素材生成绘画提示词。\n生成要求：\n\${prompt}\n素材：\n\${素材}"
    private const val VIDEO_LLM_INPUT_TEMPLATE =
        "根据素材生成视频提示词。\n生成要求：\n\${prompt}\n素材：\n\${素材}"
    private val LLM_INPUT_TEMPLATE_VARIABLE = Regex("\\$\\{([^{}]+)\\}")
    private val DOUBLE_BRACED_PLACEHOLDER = Regex("\\{\\{[^{}]+\\}\\}")

    //图片 style：只控制提示词路由（连环画/单场景），默认单场景
    private val imageStyleVariable = AiCreationVariable(
        key = "style",
        label = "画面风格",
        format = AiCreationVariable.FORMAT_OPTIONS,
        options = listOf("连环画", "单场景"),
        defaultValue = "单场景"
    )

    //视频 style：只控制提示词路由（多镜头/单镜头），默认单镜头
    private val videoStyleVariable = AiCreationVariable(
        key = "style",
        label = "画面风格",
        format = AiCreationVariable.FORMAT_OPTIONS,
        options = listOf("多镜头", "单镜头"),
        defaultValue = "单镜头"
    )

    //智谱 CogView 官方 size 枚举：选项带比例与横竖标注，values 存纯 API 值
    private val cogViewSizeVariable = AiCreationVariable(
        key = "size",
        label = "尺寸",
        format = AiCreationVariable.FORMAT_OPTIONS,
        options = listOf(
            "1024x1024（1:1，方）",
            "768x1344（4:7，竖）",
            "864x1152（3:4，竖）",
            "1344x768（7:4，横）",
            "1152x864（4:3，横）",
            "1440x720（2:1，横）",
            "720x1440（1:2，竖）"
        ),
        values = listOf(
            "1024x1024",
            "768x1344",
            "864x1152",
            "1344x768",
            "1152x864",
            "1440x720",
            "720x1440"
        ),
        defaultValue = "1024x1024",
    )

    //智谱 CogView 官方 quality：standard/hd，两值做成开关
    private val cogViewQualityVariable = AiCreationVariable(
        key = "quality",
        label = "画质",
        format = AiCreationVariable.FORMAT_SWITCH,
        defaultValue = "standard",
        onValue = "hd",
        offValue = "standard",
    )

    //智谱 CogView 官方水印开关
    private val cogViewWatermarkVariable = AiCreationVariable(
        key = "watermark_enabled",
        label = "水印",
        format = AiCreationVariable.FORMAT_SWITCH,
        defaultValue = "false",
        onValue = "true",
        offValue = "false",
    )

    //智谱 CogView 生图参数变量（style 属于 LLM 变量设置，不在此列）
    private val cogViewImageVariables = listOf(
        cogViewSizeVariable,
        cogViewQualityVariable,
        cogViewWatermarkVariable
    )

    //硅基流动 Kolors 官方 image_size 枚举（实测 5 档）
    private val kolorsImageSizeVariable = AiCreationVariable(
        key = "image_size",
        label = "尺寸",
        format = AiCreationVariable.FORMAT_OPTIONS,
        options = listOf(
            "1024x1024（1:1，方）",
            "960x1280（3:4，竖）",
            "768x1024（3:4，竖）",
            "720x1440（1:2，竖）",
            "720x1280（9:16，竖）"
        ),
        values = listOf(
            "1024x1024",
            "960x1280",
            "768x1024",
            "720x1440",
            "720x1280"
        ),
        defaultValue = "1024x1024",
    )

    private val kolorsNegativePromptVariable = AiCreationVariable(
        key = "negative_prompt",
        label = "负面提示",
        format = AiCreationVariable.FORMAT_INPUT,
        defaultValue = "",
    )

    private val kolorsStepsVariable = AiCreationVariable(
        key = "num_inference_steps",
        label = "推理步数",
        format = AiCreationVariable.FORMAT_INPUT,
        defaultValue = "20",
    )

    private val kolorsGuidanceVariable = AiCreationVariable(
        key = "guidance_scale",
        label = "引导系数",
        format = AiCreationVariable.FORMAT_INPUT,
        defaultValue = "7.5",
    )

    /** 硅基流动 Kolors 生图参数变量（style 属于 LLM 变量设置，不在此列） */
    val kolorsImageVariables = listOf(
        kolorsImageSizeVariable,
        kolorsNegativePromptVariable,
        kolorsStepsVariable,
        kolorsGuidanceVariable
    )

    //视频供应商按智谱 CogVideoX 官方参数定义；key 统一加 video_ 前缀保证全局唯一
    private val zhipuVideoParameters = listOf(
        AiCreationVariable(
            key = "video_quality",
            label = "输出模式",
            format = AiCreationVariable.FORMAT_SWITCH,
            defaultValue = "speed",
            onValue = "quality",
            offValue = "speed",
        ),
        AiCreationVariable(
            key = "video_with_audio",
            label = "AI音效",
            format = AiCreationVariable.FORMAT_SWITCH,
            defaultValue = "false",
            onValue = "true",
            offValue = "false",
        ),
        AiCreationVariable(
            key = "video_size",
            label = "分辨率",
            format = AiCreationVariable.FORMAT_OPTIONS,
            options = listOf(
                "1280x720（16:9，横）",
                "720x1280（9:16，竖）",
                "1024x1024（1:1，方）",
                "1920x1080（16:9，横）",
                "1080x1920（9:16，竖）",
                "2048x1080（256:135，横）",
                "3840x2160（16:9，横）"
            ),
            values = listOf(
                "1280x720",
                "720x1280",
                "1024x1024",
                "1920x1080",
                "1080x1920",
                "2048x1080",
                "3840x2160"
            ),
            defaultValue = "1920x1080",
        ),
        AiCreationVariable(
            key = "video_fps",
            label = "帧率",
            format = AiCreationVariable.FORMAT_SWITCH,
            defaultValue = "30",
            onValue = "60",
            offValue = "30",
        ),
        AiCreationVariable(
            key = "video_duration",
            label = "时长（秒）",
            format = AiCreationVariable.FORMAT_SWITCH,
            defaultValue = "5",
            onValue = "10",
            offValue = "5",
        )
    )

    //图片路由：写在 LLM 变量设置的 image 节，按 style 选择提示词模板 key。
    private val imageRoutes = listOf(
        AiCreationRoute(
            conditions = mapOf("style" to "连环画"),
            prompt = "连环画"
        ),
        AiCreationRoute(
            conditions = mapOf("style" to "单场景"),
            prompt = "单场景"
        )
    )

    //视频路由：写在 LLM 变量设置的 video 节，按 style 选择提示词模板 key。
    private val videoRoutes = listOf(
        AiCreationRoute(
            conditions = mapOf("style" to "多镜头"),
            prompt = "多镜头"
        ),
        AiCreationRoute(
            conditions = mapOf("style" to "单镜头"),
            prompt = "单镜头"
        )
    )

    /**
     * 供应商变量定义 JSON：只装生图/生视频参数变量；
     * LLM 变量、路由与输入模板在全局 LLM 变量设置，与供应商无关。
     */
    fun buildImageJson(imageVariables: List<AiCreationVariable>): String {
        return GSON.toJson(AiCreationVariableDoc(variables = imageVariables))
    }

    /** 全局 LLM 变量设置的出厂 JSON：image/video 两节，各含 style、路由与输入模板。 */
    fun buildLlmDefaultJson(): String {
        return GSON.toJson(
            AiCreationLlmVariableDoc(
                image = AiCreationDefinition(
                    variables = listOf(imageStyleVariable),
                    routes = imageRoutes,
                    llmInputTemplate = IMAGE_LLM_INPUT_TEMPLATE
                ),
                video = AiCreationDefinition(
                    variables = listOf(videoStyleVariable),
                    routes = videoRoutes,
                    llmInputTemplate = VIDEO_LLM_INPUT_TEMPLATE
                )
            )
        )
    }

    /**
     * 智谱 CogView 图片供应商的出厂变量定义（供初始配置与恢复默认）。
     */
    val defaultJson: String by lazy { buildImageJson(cogViewImageVariables) }

    //智谱 CogVideoX 生视频参数变量（style 属于 LLM 变量设置，不在此列）
    private val zhipuVideoVariables = zhipuVideoParameters +
        AiCreationVariable(
        key = "watermark_enabled",
        label = "水印",
        format = AiCreationVariable.FORMAT_SWITCH,
        defaultValue = "false",
        onValue = "true",
        offValue = "false",
    )

    /** 智谱视频供应商的出厂变量定义（供初始配置与恢复默认）。 */
    val zhipuVideoVariablesJson: String by lazy { buildVideoVariablesJson(zhipuVideoVariables) }

    private fun buildVideoVariablesJson(variables: List<AiCreationVariable>): String {
        return GSON.toJson(AiCreationVariableDoc(variables = variables))
    }

    /**
     * 旧格式残留指纹：供应商变量定义里带 routes/finalPrompt 顶层字段，
     * 或 variables 里含已搬去 LLM 变量设置的 style。
     * 命中才允许自动回出厂；其他解析错误一律是用户自己的问题，原样报错。
     * 自灭式：回出厂后指纹消失，此路以后永远走不到（编辑框校验拦着，老格式存不进来）。
     */
    fun isLegacyVariablesJson(json: String): Boolean {
        val raw = runCatching { JSONObject(json) }.getOrNull() ?: return false
        if (raw.has("routes") || raw.has("finalPrompt")) return true
        val variables = raw.optJSONArray("variables") ?: return false
        for (index in 0 until variables.length()) {
            if (variables.optJSONObject(index)?.optString("key") == "style") return true
        }
        return false
    }

    /**
     * 解析供应商变量定义 JSON：只认 variables（生图/生视频参数）。
     * 旧格式字段与 LLM 变量一律报错暴露，不做任何兼容。
     */
    fun parse(json: String): List<AiCreationVariable> {
        val raw = try {
            JSONObject(json)
        } catch (throwable: Throwable) {
            throw IllegalStateException("AI 创作变量定义 JSON 无效：${throwable.message}", throwable)
        }
        require(!raw.has("groups")) {
            "AI 创作变量定义不支持 groups；请改用 variables"
        }
        raw.optJSONArray("variables")?.let { variables ->
            for (index in 0 until variables.length()) {
                val variable = variables.optJSONObject(index) ?: continue
                require(!variable.has("group")) {
                    "AI 创作变量定义不支持 group；变量直接写入 variables"
                }
            }
        }
        listOf("routes", "finalPrompt", "llmInputTemplate", "image", "video").forEach { key ->
            require(!raw.has(key)) {
                "AI 创作变量定义不支持 $key；变量定义只含生图/生视频参数，LLM 变量在 LLM 变量设置"
            }
        }
        val doc = GSON.fromJsonObject<AiCreationVariableDoc>(json).getOrNull()
            ?: throw IllegalStateException("AI 创作变量定义 JSON 无效：无法解析")
        val variables = normalizeVariables(
            requireNotNull(doc.variables) { "AI 创作变量定义缺少 variables" }
        )
        require(variables.none { it.key == "style" }) {
            "AI 创作变量定义不支持 style；style 属于 LLM 变量设置"
        }
        return variables
    }

    /**
     * 解析全局 LLM 变量设置 JSON：image/video 两节，各含 style 变量、
     * 提示词路由与 LLM 输入模板。
     */
    fun parseLlm(json: String): AiCreationLlmVariableDoc {
        val raw = try {
            JSONObject(json)
        } catch (throwable: Throwable) {
            throw IllegalStateException("LLM 变量设置 JSON 无效：${throwable.message}", throwable)
        }
        listOf("routes", "finalPrompt").forEach { key ->
            require(!raw.has(key)) {
                "LLM 变量设置不支持顶层 $key；LLM 变量按 image/video 分节"
            }
        }
        val doc = GSON.fromJsonObject<AiCreationLlmVariableDoc>(json).getOrNull()
            ?: throw IllegalStateException("LLM 变量设置 JSON 无效：无法解析")
        val image = requireNotNull(doc.image) { "LLM 变量设置缺少 image 节" }
        val video = requireNotNull(doc.video) { "LLM 变量设置缺少 video 节" }
        validateLlmSection(image, "图片", listOf("连环画", "单场景"), "单场景")
        validateLlmSection(video, "视频", listOf("多镜头", "单镜头"), "单镜头")
        return doc
    }

    private fun validateLlmSection(
        section: AiCreationDefinition,
        label: String,
        styleOptions: List<String>,
        styleDefault: String
    ) {
        val variables = normalizeVariables(
            requireNotNull(section.variables) { "${label} LLM 变量缺少 variables" }
        )
        val style = variables.singleOrNull { it.key == "style" }
            ?: throw IllegalStateException("${label} LLM 变量必须且只能有一个 style")
        require(style.format == AiCreationVariable.FORMAT_OPTIONS) {
            "${label} style 必须是选项式变量"
        }
        require(style.options == styleOptions && style.effectiveValues() == styleOptions) {
            "${label} style 选项必须是：${styleOptions.joinToString("、")}"
        }
        require(style.defaultValue == styleDefault) {
            "${label} style 默认值必须是：${styleDefault}"
        }
        val routes = requireNotNull(section.routes) {
            "${label} LLM 变量缺少 routes（没有路由就无法选择提示词）"
        }
        styleOptions.forEach { styleValue ->
            val matches = routes.filter { it.conditions == mapOf("style" to styleValue) }
            require(matches.size == 1) {
                "${label} LLM 变量缺少 style=${styleValue} 的提示词路由"
            }
        }
        require(routes.size == styleOptions.size) {
            "${label} LLM 变量的提示词路由只能由 style 决定"
        }
        routes.forEach { route ->
            require(route.prompt.isNotBlank()) { "AI 创作路由缺少 prompt（提示词名字）" }
            route.conditions.forEach { (key, value) ->
                require(key == "style") {
                    "AI 创作路由（→ ${route.prompt}）when 引用了未定义的变量：$key"
                }
                require(value.isNotBlank()) {
                    "AI 创作路由（→ ${route.prompt}）when 的 $key 取值为空"
                }
            }
        }
        val template = requireNotNull(section.llmInputTemplate) {
            "${label} LLM 变量缺少 llmInputTemplate（发送给 LLM 的输入模板）"
        }
        require(template.isNotBlank()) { "${label} LLM 变量的 llmInputTemplate 不能为空" }
        val templateVariables = LLM_INPUT_TEMPLATE_VARIABLE.findAll(template)
            .map { it.groupValues[1] }
            .toList()
        require(
            templateVariables.size == 2 &&
                templateVariables.toSet() == setOf("prompt", "素材")
        ) {
            "${label} LLM 变量的 llmInputTemplate 必须且只能各包含一次 \${prompt} 和 \${素材}"
        }
        require(!DOUBLE_BRACED_PLACEHOLDER.containsMatchIn(template)) {
            "${label} LLM 变量的 llmInputTemplate 不支持 {{名字}} 占位符"
        }
    }

    /** 变量通用校验与归一化：key/format/options/switch 规则对供应商与 LLM 变量同样适用。 */
    private fun normalizeVariables(variables: List<AiCreationVariable>): List<AiCreationVariable> {
        require(variables.isNotEmpty()) { "AI 创作变量 variables 不能为空" }
        val keys = mutableSetOf<String>()
        return variables.map { variable ->
            //GSON 反射解析对缺失字段不应用 Kotlin 默认值，这里统一归一到定义默认值。
            val normalized = variable.copy(
                values = variable.values.orEmpty(),
                onValue = variable.onValue.orEmpty().ifBlank { "true" },
                offValue = variable.offValue.orEmpty().ifBlank { "false" }
            )
            require(normalized.key.isNotBlank()) {
                "AI 创作变量 key 不能为空：${normalized.label}"
            }
            require(normalized.key != AI_CREATION_MODE_KEY) {
                "AI 创作变量 key 不能使用保留字：$AI_CREATION_MODE_KEY"
            }
            require(normalized.format in AiCreationVariable.formats) {
                "AI 创作变量 ${normalized.key} 的 format 无效：${normalized.format}"
            }
            if (normalized.format == AiCreationVariable.FORMAT_OPTIONS) {
                require(normalized.options.isNotEmpty()) {
                    "AI 创作变量 ${normalized.key} 为选项式但没有选项"
                }
                if (normalized.values.isNotEmpty()) {
                    require(normalized.values.size == normalized.options.size) {
                        "AI 创作变量 ${normalized.key} 的 values 与 options 数量不一致"
                    }
                }
                if (normalized.defaultValue.isNotEmpty()) {
                    require(normalized.accepts(normalized.defaultValue)) {
                        "AI 创作变量 ${normalized.key} 的默认值无效：${normalized.defaultValue}"
                    }
                }
            }
            if (normalized.format == AiCreationVariable.FORMAT_SWITCH) {
                require(normalized.onValue != normalized.offValue) {
                    "AI 创作变量 ${normalized.key} 的 onValue 与 offValue 不能相同"
                }
                require(normalized.accepts(normalized.defaultValue)) {
                    "AI 创作变量 ${normalized.key} 的默认值无效：${normalized.defaultValue}"
                }
            }
            require(keys.add(normalized.key)) { "AI 创作变量 key 重复：${normalized.key}" }
            normalized
        }
    }
}

data class CreationSectionItem(
    val cardId: Long,
    val section: String
)

/**
 * 连线的对象是分区（素材类型），不是分区里的卡片：
 * 被连到一起的分区，其全部卡片在生成素材时合并为一条「背景加场景」式的条目。
 * label 为会话内稳定的展示组名（A、B、C…）：成员加入或并组时保持不变，
 * 整组撤销后组名回收，供下一组复用。
 */
data class CreationLinkGroup(
    val label: String,
    val sections: List<String>
)

class AiCreationSession {

    var bookName: String = ""

    /**
     * 第一页参数记忆：构造时载入上次持久化的参数值，
     * 写入必须经 [setParam] 单一入口实时落盘，读经 [paramValue]。
     */
    private val params = AiCreationConfig.loadCreationParams()

    val sectionItems = linkedMapOf<String, MutableList<CreationSectionItem>>()

    val linkGroups = mutableListOf<CreationLinkGroup>()

    /** 待连线的分区（长按分区名进入连线状态后记录） */
    var pendingLink: String? = null

    var prompt: String = ""

    /** 手动提示词页的「总素材」编辑快照：空表示尚未编辑过，进入手动页时按卡片重新汇总预填 */
    var manualMaterial: String = ""

    fun paramValue(key: String): String? = params[key]

    /** 参数唯一写入口：写内存的同时持久化，应用重启后仍保留上次值 */
    fun setParam(key: String, value: String) {
        params[key] = value
        AiCreationConfig.saveCreationParams(params)
    }

    /**
     * 供应商变量按“图片/视频体系 + 当前供应商 + 变量 key”独立存储。
     * 同名参数在不同供应商中绝不共享值；只存生图/生视频参数。
     */
    fun providerVariableValue(mode: String, key: String): String? =
        params[providerVariableStorageKey(mode, key)]

    fun setProviderVariable(mode: String, key: String, value: String) {
        params[providerVariableStorageKey(mode, key)] = value
        AiCreationConfig.saveCreationParams(params)
    }

    private fun providerVariableStorageKey(mode: String, key: String): String {
        val providerId = when (mode) {
            AiCreationVariables.GROUP_IMAGE ->
                AiCreationProviderStore.imageCurrentProvider?.id
            AiCreationVariables.GROUP_VIDEO ->
                AiCreationProviderStore.videoCurrentProvider?.id
            else -> error("未知 AI 创作模式：$mode")
        } ?: error("AI 创作${if (mode == AiCreationVariables.GROUP_IMAGE) "图片" else "视频"}供应商未配置")
        return "provider:$mode:$providerId:$key"
    }

    /**
     * LLM 变量按“体系 + 变量 key”存储：LLM 变量设置全局一份，不随供应商变化，
     * 与供应商变量的存储互相独立。
     */
    fun llmVariableValue(mode: String, key: String): String? =
        params["llm:$mode:$key"]

    fun setLlmVariable(mode: String, key: String, value: String) {
        params["llm:$mode:$key"] = value
        AiCreationConfig.saveCreationParams(params)
    }

    fun itemsOf(section: String): MutableList<CreationSectionItem> =
        sectionItems.getOrPut(section) { mutableListOf() }

    fun addCard(section: String, cardId: Long) {
        val items = itemsOf(section)
        if (items.none { it.cardId == cardId }) {
            items.add(CreationSectionItem(cardId, section))
        }
    }

    fun removeCard(section: String, cardId: Long) {
        itemsOf(section).removeAll { it.cardId == cardId }
    }

    fun linkGroupOf(section: String): CreationLinkGroup? =
        linkGroups.firstOrNull { group -> group.sections.contains(section) }

    fun isSectionLinked(section: String): Boolean = linkGroupOf(section) != null

    /**
     * 连线/取消连线两个分区：已直接相连则断开（整组解除）；否则合并两者所在的链接组
     * （各自已在别的组里则把两组并成一组），都不在组里则新建一组。
     * 组名归属跟随长按发起方：单方有组即并入该组，双方都有组则保留发起方组名；
     * 全新组合按 A、B、C 顺序取空闲组名。返回连线后所在组的组名，取消连线返回 null。
     */
    fun toggleLink(sourceSection: String, targetSection: String): String? {
        val existing = linkGroups.indexOfFirst { group ->
            group.sections.contains(sourceSection) && group.sections.contains(targetSection)
        }
        if (existing >= 0) {
            linkGroups.removeAt(existing)
            return null
        }
        val sourceGroup = linkGroups.firstOrNull { it.sections.contains(sourceSection) }
        val targetGroup = linkGroups.firstOrNull { it.sections.contains(targetSection) }
        linkGroups.removeAll { group -> group === sourceGroup || group === targetGroup }
        val merged = ((sourceGroup?.sections ?: emptyList()) +
            (targetGroup?.sections ?: emptyList()) +
            listOf(sourceSection, targetSection)).distinct()
        val label = sourceGroup?.label ?: targetGroup?.label ?: nextGroupLabel()
        linkGroups.add(CreationLinkGroup(label, merged))
        return label
    }

    /** 下一个空闲组名：A、B、…、Z、AA… 依次分配，被撤销组腾出的字母优先复用 */
    private fun nextGroupLabel(): String {
        val used = linkGroups.mapTo(mutableSetOf()) { it.label }
        var index = 0
        while (groupLabel(index) in used) {
            index++
        }
        return groupLabel(index)
    }

    private fun groupLabel(index: Int): String = buildString {
        var n = index
        do {
            insert(0, 'A' + n % 26)
            n = n / 26 - 1
        } while (n >= 0)
    }

    fun clear() {
        params.clear()
        sectionItems.clear()
        linkGroups.clear()
        pendingLink = null
        prompt = ""
        manualMaterial = ""
        //清空即恢复出厂参数记忆，持久层一并清掉
        AiCreationConfig.saveCreationParams(emptyMap())
    }

    fun sectionLabel(section: String): String = when (section) {
        AiCreationConfig.SECTION_SELECTED_TEXT -> "选中文本"
        AiCreationConfig.SECTION_BACKGROUND -> "背景"
        AiCreationConfig.SECTION_SCENE -> "场景"
        AiCreationConfig.SECTION_CHARACTER -> "人设"
        AiCreationConfig.SECTION_NOTE -> "描述与备注"
        else -> section
    }

    fun buildMaterialText(cardsById: Map<Long, CreationCard>): String {
        val builder = StringBuilder()
        val emittedSections = mutableSetOf<String>()
        AiCreationConfig.sectionOrder.forEach { section ->
            if (!emittedSections.add(section)) {
                return@forEach
            }
            val items = sectionItems[section].orEmpty()
            if (items.isEmpty()) {
                return@forEach
            }
            val group = linkGroupOf(section)
            if (group == null) {
                appendSectionContents(builder, listOf(section), cardsById)
            } else {
                //连线组：把组内所有分区的卡片合并为一条「背景加场景」式条目
                val sections = AiCreationConfig.sectionOrder.filter { group.sections.contains(it) }
                emittedSections.addAll(sections)
                appendSectionContents(builder, sections, cardsById)
            }
        }
        return builder.toString().trim()
    }

    private fun appendSectionContents(
        builder: StringBuilder,
        sections: List<String>,
        cardsById: Map<Long, CreationCard>
    ) {
        val label = sections.joinToString("加") { sectionLabel(it) }
        val contents = sections.flatMap { sectionItems[it].orEmpty() }
            .mapNotNull { cardsById[it.cardId] }
            .distinctBy { it.cardId }
            .map { it.content.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
        if (contents.isNotEmpty()) {
            appendEntry(builder, label, contents)
        }
    }

    private fun appendEntry(builder: StringBuilder, label: String, contents: List<String>) {
        if (builder.isNotEmpty()) {
            builder.append('\n')
        }
        if (contents.size == 1) {
            builder.append(label).append(": ").append(contents.first())
        } else {
            builder.append(label).append(":\n")
            builder.append(contents.joinToString("\n") { "- $it" })
        }
    }
}

object AiCreationSessionHolder {

    val session = AiCreationSession()

    fun reset() {
        session.clear()
    }
}
