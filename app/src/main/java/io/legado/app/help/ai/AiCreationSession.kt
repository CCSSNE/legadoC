package io.legado.app.help.ai

import com.google.gson.annotations.SerializedName
import io.legado.app.data.entities.CreationCard
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject

const val AI_CREATION_EPHEMERAL_BOOK = "\u0000ephemeral"
const val AI_CREATION_MODE_KEY = "mode"

data class AiCreationVariable(
    val key: String,
    val label: String,
    val format: String = AiCreationVariable.FORMAT_OPTIONS,
    val options: List<String> = emptyList(),
    val defaultValue: String = "",
    val group: String = AiCreationVariables.GROUP_IMAGE,
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

    /**
     * 参数读取的统一清洗入口：持久层里的旧值/无效值（变量定义变更后）回退到默认值，
     * 避免把已废弃的取值发进提示词或请求体。
     */
    fun effectiveValue(stored: String?): String {
        if (stored == null) return defaultValue
        return if (accepts(stored)) stored else defaultValue
    }
}

data class AiCreationVariableGroup(
    val key: String,
    val label: String,
    val variables: List<AiCreationVariable> = emptyList()
)

data class AiCreationRoute(
    @SerializedName("when") val conditions: Map<String, String> = emptyMap(),
    val template: String = ""
)

data class AiCreationVariableDoc(
    val groups: List<AiCreationVariableGroup>? = null,
    val routes: List<AiCreationRoute>? = null
)

data class AiCreationDefinition(
    val groups: List<AiCreationVariableGroup>,
    val variables: List<AiCreationVariable>,
    val routes: List<AiCreationRoute>
)

object AiCreationVariables {

    const val GROUP_IMAGE = "image"
    const val GROUP_VIDEO = "video"

    private val defaultDoc = AiCreationVariableDoc(
        groups = listOf(
            AiCreationVariableGroup(
                key = GROUP_IMAGE,
                label = "图片",
                variables = listOf(
                    //style 控制提示词走向（路由到连环画/单场景请求模板），保留
                    AiCreationVariable(
                        key = "style",
                        label = "画面风格",
                        format = AiCreationVariable.FORMAT_OPTIONS,
                        options = listOf("连环画", "单场景"),
                        defaultValue = "连环画",
                        group = GROUP_IMAGE
                    ),
                    //智谱 CogView 官方 size 枚举：选项带比例与横竖标注，values 存纯 API 值
                    AiCreationVariable(
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
                        group = GROUP_IMAGE
                    ),
                    //智谱 CogView 官方 quality：standard/hd，两值做成开关
                    AiCreationVariable(
                        key = "quality",
                        label = "画质",
                        format = AiCreationVariable.FORMAT_SWITCH,
                        defaultValue = "standard",
                        onValue = "hd",
                        offValue = "standard",
                        group = GROUP_IMAGE
                    ),
                    //智谱 CogView 官方水印开关
                    AiCreationVariable(
                        key = "watermark_enabled",
                        label = "水印",
                        format = AiCreationVariable.FORMAT_SWITCH,
                        defaultValue = "false",
                        onValue = "true",
                        offValue = "false",
                        group = GROUP_IMAGE
                    )
                )
            ),
            AiCreationVariableGroup(
                key = GROUP_VIDEO,
                label = "视频",
                variables = listOf(
                    //视频组按智谱 CogVideoX 官方参数定义；key 统一加 video_ 前缀保证全局唯一
                    AiCreationVariable(
                        key = "video_quality",
                        label = "输出模式",
                        format = AiCreationVariable.FORMAT_SWITCH,
                        defaultValue = "speed",
                        onValue = "quality",
                        offValue = "speed",
                        group = GROUP_VIDEO
                    ),
                    AiCreationVariable(
                        key = "video_with_audio",
                        label = "AI音效",
                        format = AiCreationVariable.FORMAT_SWITCH,
                        defaultValue = "false",
                        onValue = "true",
                        offValue = "false",
                        group = GROUP_VIDEO
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
                        group = GROUP_VIDEO
                    ),
                    AiCreationVariable(
                        key = "video_fps",
                        label = "帧率",
                        format = AiCreationVariable.FORMAT_SWITCH,
                        defaultValue = "30",
                        onValue = "60",
                        offValue = "30",
                        group = GROUP_VIDEO
                    ),
                    AiCreationVariable(
                        key = "video_duration",
                        label = "时长（秒）",
                        format = AiCreationVariable.FORMAT_SWITCH,
                        defaultValue = "5",
                        onValue = "10",
                        offValue = "5",
                        group = GROUP_VIDEO
                    )
                )
            )
        ),
        routes = listOf(
            AiCreationRoute(
                conditions = mapOf(AI_CREATION_MODE_KEY to GROUP_IMAGE, "style" to "连环画"),
                template = "连环画"
            ),
            AiCreationRoute(
                conditions = mapOf(AI_CREATION_MODE_KEY to GROUP_IMAGE, "style" to "单场景"),
                template = "单场景"
            ),
            AiCreationRoute(
                conditions = mapOf(AI_CREATION_MODE_KEY to GROUP_VIDEO),
                template = "视频"
            )
        )
    )

    val defaultJson: String by lazy { GSON.toJson(defaultDoc) }

    fun parse(json: String): AiCreationDefinition {
        val doc = GSON.fromJsonObject<AiCreationVariableDoc>(json).getOrNull()
            ?: throw IllegalStateException("AI 创作变量定义 JSON 无效：无法解析")
        val groups = requireNotNull(doc.groups) { "AI 创作变量定义缺少 groups" }
        val routes = requireNotNull(doc.routes) { "AI 创作变量定义缺少 routes（没有路由就无法选择请求模板）" }
        require(groups.isNotEmpty()) { "AI 创作变量定义 groups 不能为空" }
        val variables = mutableListOf<AiCreationVariable>()
        val keys = mutableSetOf<String>()
        groups.forEach { group ->
            require(group.key.isNotBlank()) { "AI 创作变量分组 key 不能为空" }
            group.variables.forEach { variable ->
                val withGroup = if (variable.group.isBlank()) {
                    variable.copy(group = group.key)
                } else {
                    variable
                }
                //GSON 反射解析对缺失字段不应用 Kotlin 默认值，这里统一归一到安全默认
                val normalized = withGroup.copy(
                    values = withGroup.values.orEmpty(),
                    onValue = withGroup.onValue.orEmpty().ifBlank { "true" },
                    offValue = withGroup.offValue.orEmpty().ifBlank { "false" }
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
                    //空默认值表示不预选，允许；非空默认值必须命中已定义选项
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
                variables.add(normalized)
            }
        }
        require(variables.isNotEmpty()) { "AI 创作变量定义不能为空" }
        val knownKeys = keys + AI_CREATION_MODE_KEY
        routes.forEach { route ->
            require(route.template.isNotBlank()) { "AI 创作路由缺少 template（请求模板识别名）" }
            require(route.conditions.isNotEmpty()) {
                "AI 创作路由（→ ${route.template}）缺少 when 条件"
            }
            route.conditions.forEach { (key, value) ->
                require(key in knownKeys) {
                    "AI 创作路由（→ ${route.template}）when 引用了未定义的变量：$key"
                }
                require(value.isNotBlank()) {
                    "AI 创作路由（→ ${route.template}）when 的 $key 取值为空"
                }
            }
        }
        return AiCreationDefinition(groups, variables, routes)
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

    fun paramValue(key: String): String? = params[key]

    /** 参数唯一写入口：写内存的同时持久化，应用重启后仍保留上次值 */
    fun setParam(key: String, value: String) {
        params[key] = value
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
