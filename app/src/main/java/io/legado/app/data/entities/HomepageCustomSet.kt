package io.legado.app.data.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 主页集：聚合主页的分组单元。
 * 书源集 ID 为 src_<书源URL>、订阅源集为 rss_<订阅源URL>，由功能自动创建；
 * 用户自定义集 ID 为 cs_<时间戳>，可手动把其他集的模块复制进来。
 */
@Entity(tableName = "homepage_custom_sets")
data class HomepageCustomSet(
    @PrimaryKey
    var id: String = "",
    var name: String = "",
    var sortOrder: Int = 0,
)
