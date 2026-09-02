package io.legado.app.domain.model

import androidx.annotation.Keep
import io.legado.app.R

/** 供 Gateway 和 ViewModel 使用的不可变模块模型 */
@Keep
data class ModuleItem(
    val id: String = "",
    val sourceUrl: String = "",
    val moduleKey: String = "",
    val type: String = "",
    val title: String = "",
    val customTitle: String? = null,
    val customSetTitle: String? = null,
    val args: String? = null,
    val layoutConfig: String? = null,
    val url: String? = null,
    val isEnabled: Boolean = true,
    val customSetId: String? = null,
    val isUserCreated: Boolean = false,
    val sortOrder: Int = 0,
    val sourceJsonHash: String? = null,
    val syncedAt: Long = 0,
) {
    val displayTitle: String get() = customTitle ?: title
}

@Keep
data class CustomSetItem(
    val id: String = "",
    val name: String = "",
    val sortOrder: Int = 0,
)

/** 模块定义（来自书源 JSON 解析或用户手动添加） */
@Keep
data class ModuleDef(
    val key: String = "",
    val type: String = "",
    val title: String = "",
    val args: String? = null,
    val layoutConfig: String? = null,
    val url: String? = null,
    val sourceUrl: String = "",
) {
    val globalId: String get() = globalIdOf(sourceUrl, key)

    companion object {
        fun globalIdOf(sourceUrl: String, key: String, setId: String? = null): String {
            val targetSetId = setId ?: "src_$sourceUrl"
            return "$targetSetId::$sourceUrl::$key"
        }
    }
}

/** 首页模块类型枚举 — 定义在 Domain 层以便 Gateway 和 ViewModel 共享 */
enum class HomepageModuleType(val key: String, val titleRes: Int) {
    Banner("banner", R.string.module_type_banner),
    Ranking("ranking", R.string.module_type_ranking),
    GridRanking("gridRanking", R.string.module_type_grid_ranking),
    Grid("grid", R.string.module_type_grid),
    Card("card", R.string.module_type_card),
    InfiniteGrid("infiniteGrid", R.string.module_type_infinite_grid),
    ButtonGroup("buttonGroup", R.string.module_type_button_group),
    Waterfall("waterfall", R.string.module_type_waterfall),
    Unknown("", R.string.unknown_type);

    companion object {
        fun fromKey(key: String?): HomepageModuleType =
            entries.find { it.key == key } ?: Unknown
    }
}

/** 书籍在书架中的状态 */
enum class BookShelfState {
    IN_SHELF,
    SAME_NAME_AUTHOR,
    NOT_IN_SHELF,
}
