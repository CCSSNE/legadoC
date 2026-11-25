package io.legado.app.data.entities

import android.os.Parcelable
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Ignore
import androidx.room.PrimaryKey
import kotlinx.parcelize.IgnoredOnParcel
import kotlinx.parcelize.Parcelize

/**
 * 高亮规则：正则命中替换净化后的正文文字，应用显示效果。
 * 效果体系与书签一致（BookmarkStyle 位掩码 + 逐效果颜色），仅是显示效果，没有书签功能。
 */
@Parcelize
@Entity(tableName = "highlight_rules")
data class HighlightRule(
    @PrimaryKey(autoGenerate = true)
    var id: Long = System.currentTimeMillis(),
    //名称
    var name: String = "",
    //正则表达式
    var pattern: String = "",
    //作用范围，选填书名或者书源 URL
    var scope: String? = null,
    //排除范围，选填书名或者书源 URL
    var excludeScope: String? = null,
    //是否启用
    var isEnabled: Boolean = true,
    //排序
    @ColumnInfo(name = "sortOrder") var order: Int = Int.MIN_VALUE,
    //显示效果位掩码（BookmarkStyle）
    var style: Int = 0,
    //每个效果的独立颜色（JSON：效果位 -> 颜色值）
    var styleColors: String = "",
    //夜间模式逐效果颜色（JSON 同格式；空 = 跟随白天），白天/墨水屏读白天色
    var styleColorsNight: String = ""
) : Parcelable {

    override fun equals(other: Any?): Boolean {
        if (other is HighlightRule) {
            return other.id == id
        }
        return super.equals(other)
    }

    override fun hashCode(): Int {
        return id.hashCode()
    }

    @delegate:Transient
    @delegate:Ignore
    @IgnoredOnParcel
    val regex: Regex by lazy {
        pattern.toRegex()
    }

    @delegate:Transient
    @delegate:Ignore
    @IgnoredOnParcel
    val styleColorsMap: Map<Int, Int> by lazy {
        BookmarkStyle.parseStyleColors(styleColors)
    }

    @delegate:Transient
    @delegate:Ignore
    @IgnoredOnParcel
    val styleColorsNightMap: Map<Int, Int> by lazy {
        BookmarkStyle.parseStyleColors(styleColorsNight)
    }

    /**
     * 按当前阅读模式取生效颜色：夜间用夜间色，未单独设置的位回落白天色；
     * 白天（及墨水屏，由调用方保证 isNightTheme=false）直接用白天色。
     */
    fun effectiveStyleColors(isNightTheme: Boolean): Map<Int, Int> {
        if (!isNightTheme) {
            return styleColorsMap
        }
        val night = styleColorsNightMap
        if (night.isEmpty()) {
            return styleColorsMap
        }
        if (styleColorsMap.isEmpty()) {
            return night
        }
        val merged = HashMap(styleColorsMap)
        night.forEach { (bit, color) ->
            if (color != 0) {
                merged[bit] = color
            }
        }
        return merged
    }
}
