package io.legado.app.ui.main.homepage

import io.legado.app.data.entities.SearchBook
import io.legado.app.data.entities.rule.ExploreKind
import io.legado.app.domain.model.BookShelfState
import io.legado.app.domain.model.HomepageModuleType

/** 聚合主页书籍条目：书籍 + 书架状态 */
data class HomepageBookItemUi(
    val book: SearchBook,
    val shelfState: BookShelfState = BookShelfState.NOT_IN_SHELF,
)

/** 排行榜多分类 Tab 数据 */
data class RankingTabData(
    val title: String,
    val exploreUrl: String? = null,
    val books: List<HomepageBookItemUi>? = null,
    val errorMessage: String? = null,
    val page: Int = 1,
    val hasMore: Boolean = true,
    val isLoadingMore: Boolean = false,
)

/** 模块内容加载状态 */
sealed interface ModuleLoadState {
    data object Loading : ModuleLoadState

    data class Loaded(
        val books: List<HomepageBookItemUi>,
        val hasMore: Boolean = false,
        val page: Int = 1,
        val isLoadingMore: Boolean = false,
    ) : ModuleLoadState

    data class Buttons(
        val kinds: List<ExploreKind>,
    ) : ModuleLoadState

    data class RankingTabs(
        val tabs: List<RankingTabData>,
        val selectedIndex: Int = 0,
    ) : ModuleLoadState

    data class Error(
        val message: String,
    ) : ModuleLoadState
}

/** 主页模块展示单元 */
data class HomepageModuleUi(
    val sourceUrl: String,
    val setName: String,
    val globalId: String,
    val type: HomepageModuleType,
    val title: String,
    val exploreUrl: String? = null,
    val customSetId: String? = null,
    val layoutConfig: String? = null,
    val state: ModuleLoadState = ModuleLoadState.Loading,
    val config: Map<String, String> = emptyMap(),
)

/** 管理界面的集条目 */
data class HomepageSourceManageUi(
    val sourceUrl: String,
    val sourceName: String,
    val sourceGroup: String? = null,
    val isSelected: Boolean = false,
    val moduleCount: Int = 0,
    val isCustomSet: Boolean = false,
    val sourceType: String? = null,
)

/** 管理界面的模块条目 */
data class HomepageModuleManageUi(
    val id: String,
    val sourceUrl: String,
    val sourceName: String,
    val moduleKey: String,
    val title: String,
    val customSetTitle: String? = null,
    val customSetId: String? = null,
    val isVisible: Boolean = true,
    val type: String,
    val url: String? = null,
    val args: String? = null,
    val layoutConfig: String? = null,
    val originalTitle: String,
    val sourceType: String = "book",
)

/** 管理界面整体状态 */
data class HomepageManageUiState(
    val sets: List<HomepageSourceManageUi> = emptyList(),
    val browseSources: List<HomepageSourceManageUi> = emptyList(),
    val rssSources: List<HomepageSourceManageUi> = emptyList(),
    val allJoinedModules: List<HomepageModuleManageUi> = emptyList(),
    val sourceNames: Map<String, String> = emptyMap(),
)

/** 主页整体 UI 状态 */
data class HomepageUiState(
    val modules: List<HomepageModuleUi> = emptyList(),
    val isRefreshing: Boolean = false,
    val manageState: HomepageManageUiState = HomepageManageUiState(),
    /** 布局模式：0 = 混合列表，1 = 分源Tab */
    val layoutMode: Int = 0,
    /** 分源Tab 模式预加载：0 = 仅当前集，1 = 当前集 + 相邻集 */
    val preloadMode: Int = 0,
)

/** 一次性 UI 事件 */
sealed interface HomepageEffect {
    data class ShowSnackbar(val message: String) : HomepageEffect
}
