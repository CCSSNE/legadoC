package io.legado.app.data.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 主页模块：聚合主页中一个可展示单元的持久化配置。
 *
 * 模块定义可来自书源 JSON 字段 homepageModules 的自动同步，也可由用户手动创建；
 * customSetId 决定模块归属的集：src_<书源URL> / rss_<订阅源URL> 为源集，cs_ 前缀 ID 为用户自定义集。
 */
@Entity(tableName = "homepage_modules")
data class HomepageModule(
    @PrimaryKey
    var id: String = "",
    var sourceUrl: String = "",
    var moduleKey: String = "",
    var type: String = "",
    var title: String = "",
    var args: String? = null,
    var layoutConfig: String? = null,
    var url: String? = null,
    var isEnabled: Boolean = true,
    var sortOrder: Int = 0,
    var customSetId: String? = null,
    var isUserCreated: Boolean = false,
    var customTitle: String? = null,
    var customSetTitle: String? = null,
    var sourceJsonHash: String? = null,
    var syncedAt: Long = 0,
)
