package io.legado.app.help.ai

import io.legado.app.data.entities.CreationCard
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject

data class AiCreationVariable(
    val key: String,
    val label: String,
    val format: String = FORMAT_OPTIONS,
    val options: List<String> = emptyList(),
    val defaultValue: String = "",
    val group: String = GROUP_IMAGE
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

data class AiCreationVariableGroups(
    val groups: List<AiCreationVariableGroup> = emptyList()
)

object AiCreationVariables {

    const val GROUP_IMAGE = "image"
    const val GROUP_VIDEO = "video"

    val defaultGroups = AiCreationVariableGroups(
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
        )
    )

    val defaultJson: String by lazy { GSON.toJson(defaultGroups) }

    fun parse(json: String): List<AiCreationVariable> {
        val groups = GSON.fromJsonObject<AiCreationVariableGroups>(json).getOrNull()
            ?: throw IllegalStateException("AI 创作变量定义 JSON 无效：无法解析")
        val all = groups.groups.flatMap { group ->
            group.variables.map { if (it.group.isBlank()) it.copy(group = group.key) else it }
        }
        require(all.isNotEmpty()) { "AI 创作变量定义不能为空" }
        val keys = mutableSetOf<String>()
        all.forEach { variable ->
            require(variable.key.isNotBlank()) { "AI 创作变量 key 不能为空：${variable.label}" }
            require(variable.format in AiCreationVariable.formats) {
                "AI 创作变量 ${variable.key} 的 format 无效：${variable.format}"
            }
            if (variable.format == AiCreationVariable.FORMAT_OPTIONS) {
                require(variable.options.isNotEmpty()) {
                    "AI 创作变量 ${variable.key} 为选项式但没有选项"
                }
            }
            require(keys.add(variable.key)) { "AI 创作变量 key 重复：${variable.key}" }
        }
        return all
    }

    fun groupLabelOf(group: AiCreationVariableGroup): String =
        when (group.key) {
            GROUP_IMAGE -> "图片"
            GROUP_VIDEO -> "视频"
            else -> group.label.ifBlank { group.key }
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

    fun buildParamsText(variables: List<AiCreationVariable>): String {
        return variables.mapNotNull { variable ->
            params[variable.key]?.takeIf { it.isNotBlank() }?.let {
                "${variable.label}: $it"
            }
        }.joinToString("\n")
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
