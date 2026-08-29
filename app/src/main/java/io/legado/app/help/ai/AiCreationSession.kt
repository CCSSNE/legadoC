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
    val group: String = AiCreationVariables.GROUP_IMAGE
) {
    companion object {
        const val FORMAT_SWITCH = "switch"
        const val FORMAT_OPTIONS = "options"
        const val FORMAT_INPUT = "input"
        val formats = listOf(FORMAT_SWITCH, FORMAT_OPTIONS, FORMAT_INPUT)
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
                    AiCreationVariable(
                        key = "style",
                        label = "画面风格",
                        format = AiCreationVariable.FORMAT_OPTIONS,
                        options = listOf("连环画", "单场景"),
                        defaultValue = "连环画",
                        group = GROUP_IMAGE
                    ),
                    AiCreationVariable(
                        key = "ratio",
                        label = "画面比例",
                        format = AiCreationVariable.FORMAT_OPTIONS,
                        options = listOf("1:1", "4:3", "3:4", "16:9", "9:16"),
                        defaultValue = "16:9",
                        group = GROUP_IMAGE
                    ),
                    AiCreationVariable(
                        key = "quality",
                        label = "画质",
                        format = AiCreationVariable.FORMAT_OPTIONS,
                        options = listOf("标准", "高清", "超清"),
                        defaultValue = "高清",
                        group = GROUP_IMAGE
                    )
                )
            ),
            AiCreationVariableGroup(
                key = GROUP_VIDEO,
                label = "视频",
                variables = listOf(
                    AiCreationVariable(
                        key = "shot",
                        label = "镜头",
                        format = AiCreationVariable.FORMAT_OPTIONS,
                        options = listOf("多镜头", "单镜头"),
                        defaultValue = "单镜头",
                        group = GROUP_VIDEO
                    ),
                    AiCreationVariable(
                        key = "resolution",
                        label = "分辨率",
                        format = AiCreationVariable.FORMAT_OPTIONS,
                        options = listOf("720p", "1080p", "4K"),
                        defaultValue = "1080p",
                        group = GROUP_VIDEO
                    ),
                    AiCreationVariable(
                        key = "duration",
                        label = "时长",
                        format = AiCreationVariable.FORMAT_INPUT,
                        defaultValue = "5秒",
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
                require(withGroup.key.isNotBlank()) {
                    "AI 创作变量 key 不能为空：${withGroup.label}"
                }
                require(withGroup.key != AI_CREATION_MODE_KEY) {
                    "AI 创作变量 key 不能使用保留字：$AI_CREATION_MODE_KEY"
                }
                require(withGroup.format in AiCreationVariable.formats) {
                    "AI 创作变量 ${withGroup.key} 的 format 无效：${withGroup.format}"
                }
                if (withGroup.format == AiCreationVariable.FORMAT_OPTIONS) {
                    require(withGroup.options.isNotEmpty()) {
                        "AI 创作变量 ${withGroup.key} 为选项式但没有选项"
                    }
                }
                require(keys.add(withGroup.key)) { "AI 创作变量 key 重复：${withGroup.key}" }
                variables.add(withGroup)
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

data class CreationLinkGroup(
    val refs: List<CreationSectionItem>
)

class AiCreationSession {

    var bookName: String = ""

    val params = linkedMapOf<String, String>()

    val sectionItems = linkedMapOf<String, MutableList<CreationSectionItem>>()

    val linkGroups = mutableListOf<CreationLinkGroup>()

    var pendingLink: CreationSectionItem? = null

    var prompt: String = ""

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
        linkGroups.removeAll { group ->
            group.refs.any { it.section == section && it.cardId == cardId }
        }
        if (pendingLink?.cardId == cardId && pendingLink?.section == section) {
            pendingLink = null
        }
    }

    fun toggleLink(source: CreationSectionItem, target: CreationSectionItem): Boolean {
        val existing = linkGroups.indexOfFirst { group ->
            group.refs.contains(source) && group.refs.contains(target)
        }
        if (existing >= 0) {
            linkGroups.removeAt(existing)
            return false
        }
        linkGroups.removeAll { group ->
            group.refs.contains(source) || group.refs.contains(target)
        }
        linkGroups.add(CreationLinkGroup(listOf(source, target)))
        return true
    }

    fun isLinked(section: String, cardId: Long): Boolean =
        linkGroups.any { group ->
            group.refs.contains(CreationSectionItem(cardId, section))
        }

    fun clear() {
        params.clear()
        sectionItems.clear()
        linkGroups.clear()
        pendingLink = null
        prompt = ""
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
        val emittedGroups = mutableSetOf<Int>()
        AiCreationConfig.sectionOrder.forEach { section ->
            val items = sectionItems[section].orEmpty()
            val plain = mutableListOf<CreationSectionItem>()
            items.forEach { item ->
                val groupIndex = linkGroups.indexOfFirst { it.refs.contains(item) }
                if (groupIndex >= 0) {
                    if (emittedGroups.add(groupIndex)) {
                        val group = linkGroups[groupIndex]
                        val label = group.refs.distinctBy { it.section }
                            .joinToString("加") { sectionLabel(it.section) }
                        val contents = group.refs.mapNotNull { cardsById[it.cardId] }
                            .distinctBy { it.cardId }
                            .map { it.content.trim() }
                            .filter { it.isNotEmpty() }
                            .distinct()
                        if (contents.isNotEmpty()) {
                            appendEntry(builder, label, contents)
                        }
                    }
                } else {
                    plain.add(item)
                }
            }
            val plainContents = plain.mapNotNull { cardsById[it.cardId] }
                .map { it.content.trim() }
                .filter { it.isNotEmpty() }
            if (plainContents.isNotEmpty()) {
                appendEntry(builder, sectionLabel(section), plainContents)
            }
        }
        return builder.toString().trim()
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
