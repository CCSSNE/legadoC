package io.legado.app.lib.theme

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.toColorInt
import io.legado.app.R
import io.legado.app.lib.theme.UiCorner
import io.legado.app.constant.PreferKey
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.ThemeConfig
import io.legado.app.help.config.TopBarConfig
import io.legado.app.utils.dpToPx
import io.legado.app.utils.getPrefInt
import io.legado.app.utils.getPrefString
import java.io.File
import kotlin.math.roundToInt

object UiCorner {

    enum class SurfaceGroup {
        UI,
        READING,
        DIALOG
    }

    fun scale(): Float {
        return AppConfig.uiCornerScale.coerceIn(0f, 3f)
    }

    fun panelRadius(context: Context): Float {
        return context.resources.getDimension(R.dimen.ui_panel_radius) * scale()
    }

    fun actionRadius(context: Context): Float {
        return context.resources.getDimension(R.dimen.ui_action_radius) * scale()
    }

    /**
     * 弹窗和原生菜单使用的小圆角上限。它与普通卡片的圆角分开，避免为了
     * 改小菜单圆角而影响书架卡片、搜索条等普通 UI。
     */
    fun compactSurfaceRadius(context: Context): Float {
        return panelRadius(context).coerceAtMost(
            context.resources.getDimension(R.dimen.popup_surface_corner_radius)
        )
    }

    fun scaledDp(value: Float): Float {
        return value.dpToPx() * scale()
    }

    fun searchRadius(value: Float): Float {
        return if (AppConfig.uiCornerSearchFollow) {
            scaledDp(value)
        } else {
            value.dpToPx()
        }
    }

    fun replyRadius(value: Float): Float {
        return if (AppConfig.uiCornerReplyFollow) {
            scaledDp(value)
        } else {
            value.dpToPx()
        }
    }

    fun effectMode(): String = AppConfig.bottomBarEffectMode

    /**
     * 全局界面透明度转换得到的物理表面不透明度。
     * 设置值 0% 表示不透明，100% 表示全透明；所有 UI 表面都必须经此入口换算。
     */
    fun uiLayoutSurfaceAlpha(): Float {
        val transparency = AppConfig.uiLayoutAlpha.coerceIn(0, 100) / 100f
        return (1f - transparency).coerceIn(0f, 1f)
    }

    /**
     * 悬浮块组的统一表面不透明度。
     * 搜索条、卡片、分组条、底部导航等浮层表面都从这里取全局系数。
     */
    fun floatingGroupAlpha(): Float {
        return uiLayoutSurfaceAlpha()
    }

    /**
     * 书架书籍与合集封面的独立不透明度。
     * 设置值 0% 表示不透明，100% 表示全透明；它不读取全局 UI 或 Dialog 透明度。
     */
    fun bookshelfCoverAlpha(): Float {
        val transparency = AppConfig.bookshelfCoverAlpha.coerceIn(0, 100) / 100f
        return (1f - transparency).coerceIn(0f, 1f)
    }

    fun bookshelfCoverSurfaceColor(color: Int): Int {
        val alpha = (Color.alpha(color) * bookshelfCoverAlpha()).roundToInt()
        return ColorUtils.setAlphaComponent(color, alpha)
    }

    /**
     * 读书界面表面组：读取菜单自己的不透明度，同时沿用全局悬浮块的玻璃规律。
     * 读书页正文背景图不经过这里，避免 UI 透明度污染图片透明度。
     */
    fun readingGroupAlpha(menuAlpha: Int): Float {
        return (menuAlpha.coerceIn(0, 100) / 100f * floatingGroupAlpha())
            .coerceIn(0f, 1f)
    }

    fun readingSurfaceColor(
        color: Int,
        menuAlpha: Int,
        pressed: Boolean = false
    ): Int {
        val alpha = Color.alpha(color) / 255f * readingGroupAlpha(menuAlpha) +
            if (pressed) 0.08f else 0f
        return ColorUtils.setAlphaComponent(color, (alpha.coerceIn(0f, 1f) * 255).roundToInt())
    }

    /**
     * 所有可调表面统一从这里分派，避免普通 UI、读书 UI、弹窗再次各写一套透明算法。
     */
    fun groupColor(
        group: SurfaceGroup,
        color: Int,
        readingMenuAlpha: Int = 100,
        pressed: Boolean = false
    ): Int = when (group) {
        SurfaceGroup.UI -> surfaceColor(color, pressed)
        SurfaceGroup.READING -> readingSurfaceColor(color, readingMenuAlpha, pressed)
        SurfaceGroup.DIALOG -> dialogSurfaceColor(color)
    }

    fun dialogSurfaceColor(color: Int): Int {
        return ColorUtils.setAlphaComponent(
            color,
            (Color.alpha(color) * dialogSurfaceAlpha()).roundToInt()
        )
    }

    /**
     * 弹窗内部图片和嵌套卡片使用的统一表面不透明度。
     * 弹窗透明度 0% 表示不透明，100% 表示最透明。
     */
    fun dialogSurfaceAlpha(): Float {
        if (AppConfig.isEInkMode) return 1f
        return ((100 - AppConfig.dialogAlpha.coerceIn(0, 100)) / 100f)
            .coerceIn(0f, 1f)
    }

    fun dialogBlurRadius(): Int {
        val standardRadiusDp = when (AppConfig.bottomBarEffectMode) {
            "frosted" -> 10f + AppConfig.frostedGlassLevel.coerceIn(0, 100) / 100f * 24f
            "glass" -> 5f
            else -> 0f
        }
        return (standardRadiusDp * AppConfig.dialogBlur.coerceIn(0, 100) / 100f)
            .dpToPx()
            .roundToInt()
    }

    fun surfaceColor(color: Int, pressed: Boolean = false): Int {
        val sourceAlpha = Color.alpha(color) / 255f
        val alpha = (sourceAlpha * floatingGroupAlpha() + if (pressed) 0.08f else 0f)
            .coerceIn(0f, 1f)
        return ColorUtils.setAlphaComponent(color, (alpha * 255).toInt())
    }

    fun effectStrokeColor(color: Int): Int {
        val base = if (ColorUtils.calculateLuminance(color) > 0.5) Color.BLACK else Color.WHITE
        val alpha = 0.10f
        return ColorUtils.setAlphaComponent(base, (alpha.coerceIn(0f, 0.5f) * 255).toInt())
    }

    private fun roundedColor(color: Int, radius: Float, pressed: Boolean, transparent: Boolean): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius
            setColor(if (transparent) surfaceColor(color, pressed) else color)
        }
    }

    fun rounded(color: Int, radius: Float): GradientDrawable {
        return roundedColor(color, radius, false, true)
    }

    fun opaqueRounded(color: Int, radius: Float): GradientDrawable {
        return roundedColor(color, radius, false, false)
    }

    fun dialogRounded(color: Int, radius: Float): GradientDrawable {
        return opaqueRounded(dialogSurfaceColor(color), compactDialogRadius(radius))
    }

    fun dialogTopRounded(color: Int, radius: Float): GradientDrawable {
        val compactRadius = compactDialogRadius(radius)
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadii = floatArrayOf(
                compactRadius, compactRadius,
                compactRadius, compactRadius,
                0f, 0f,
                0f, 0f
            )
            setColor(dialogSurfaceColor(color))
        }
    }

    private fun compactDialogRadius(radius: Float): Float {
        return radius.coerceAtLeast(0f).coerceAtMost(4f.dpToPx())
    }

    fun roundedStroke(color: Int, radius: Float, strokeWidth: Int, strokeColor: Int): GradientDrawable {
        return rounded(color, radius).apply {
            setStroke(strokeWidth, strokeColor)
        }
    }

    fun opaqueRoundedStroke(color: Int, radius: Float, strokeWidth: Int, strokeColor: Int): GradientDrawable {
        return opaqueRounded(color, radius).apply {
            setStroke(strokeWidth, strokeColor)
        }
    }

    fun actionSelector(defaultColor: Int, pressedColor: Int, radius: Float): StateListDrawable {
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), roundedColor(pressedColor, radius, true, false))
            addState(intArrayOf(android.R.attr.state_selected), roundedColor(pressedColor, radius, true, false))
            addState(intArrayOf(), opaqueRounded(defaultColor, radius))
        }
    }

    fun dialogActionSelector(defaultColor: Int, pressedColor: Int, radius: Float): StateListDrawable {
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), opaqueRounded(dialogSurfaceColor(pressedColor), radius))
            addState(intArrayOf(android.R.attr.state_selected), opaqueRounded(dialogSurfaceColor(pressedColor), radius))
            addState(intArrayOf(), opaqueRounded(dialogSurfaceColor(defaultColor), radius))
        }
    }

    fun softActionSelector(defaultColor: Int, pressedColor: Int, radius: Float): StateListDrawable {
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), roundedColor(pressedColor, radius, true, true))
            addState(intArrayOf(android.R.attr.state_selected), roundedColor(pressedColor, radius, true, true))
            addState(intArrayOf(), roundedColor(defaultColor, radius, false, true))
        }
    }

    fun actionStrokeSelector(
        defaultColor: Int,
        pressedColor: Int,
        radius: Float,
        strokeWidth: Int,
        strokeColor: Int
    ): StateListDrawable {
        return StateListDrawable().apply {
            addState(
                intArrayOf(android.R.attr.state_pressed),
                roundedColor(pressedColor, radius, true, false).apply {
                    setStroke(strokeWidth, strokeColor)
                }
            )
            addState(
                intArrayOf(android.R.attr.state_selected),
                roundedColor(pressedColor, radius, true, false).apply {
                    setStroke(strokeWidth, strokeColor)
                }
            )
            addState(intArrayOf(), opaqueRoundedStroke(defaultColor, radius, strokeWidth, strokeColor))
        }
    }

    /**
     * 主题包配置的扩展表面色：主题设置的自定义色优先，未配置回落既有资源色，
     * 未应用扩展主题时所有取值与历史视觉完全一致。返回的是未经透明度换算的基色。
     */
    fun themeSurfaceCardColor(context: Context): Int {
        return themeSurfaceColorOverride(
            context,
            PreferKey.themeCardColor,
            PreferKey.themeCardColorN
        ) ?: ContextCompat.getColor(context, R.color.background_card)
    }

    fun themeSurfaceMutedColor(context: Context): Int {
        return themeSurfaceColorOverride(
            context,
            PreferKey.themeMutedColor,
            PreferKey.themeMutedColorN
        ) ?: ContextCompat.getColor(context, R.color.background_menu)
    }

    /**
     * 弹窗玻璃 tint 的基色。主题配置了 cardColor 时弹窗随主题着色，
     * 未配置时保持既有 dialog_surface 资源色。
     */
    fun themeSurfaceDialogColor(context: Context): Int {
        return themeSurfaceColorOverride(
            context,
            PreferKey.themeCardColor,
            PreferKey.themeCardColorN
        ) ?: ContextCompat.getColor(context, R.color.dialog_surface)
    }

    fun themeSurfaceSearchFieldColor(context: Context): Int {
        return themeSurfaceColorOverride(
            context,
            PreferKey.themeSearchFieldBackgroundColor,
            PreferKey.themeSearchFieldBackgroundColorN
        ) ?: ContextCompat.getColor(context, R.color.background_card)
    }

    fun themeSurfaceTabColor(context: Context): Int {
        return themeSurfaceColorOverride(
            context,
            PreferKey.themeTabBackgroundColor,
            PreferKey.themeTabBackgroundColorN
        ) ?: ContextCompat.getColor(context, R.color.background_card)
    }

    /**
     * 书架底色：主题未配置时回落主题背景色（与上游语义一致）。
     */
    fun themeShelfColor(context: Context): Int {
        return themeSurfaceColorOverride(
            context,
            PreferKey.themeShelfColor,
            PreferKey.themeShelfColorN
        ) ?: ThemeStore.backgroundColor(context)
    }

    /**
     * 主题包配置的面板描边色；已按主题 panelBorderAlpha 折算透明度。
     * 未配置返回 null，调用方保持各自的默认描边。
     */
    fun themePanelBorderColor(context: Context): Int? {
        val key = if (AppConfig.isNightTheme) {
            PreferKey.panelBorderColorN
        } else {
            PreferKey.panelBorderColor
        }
        val value = context.getPrefString(key)?.takeIf { it.isNotBlank() } ?: return null
        return runCatching { value.toColorInt() }.getOrNull()?.let { applyPanelBorderAlpha(context, it) }
    }

    fun themePanelBorderAlpha(context: Context): Int {
        val key = if (AppConfig.isNightTheme) {
            PreferKey.panelBorderAlphaN
        } else {
            PreferKey.panelBorderAlpha
        }
        return context.getPrefInt(key, 100).coerceIn(0, 100)
    }

    private fun applyPanelBorderAlpha(context: Context, color: Int): Int {
        val alpha = themePanelBorderAlpha(context) * 255 / 100
        return ColorUtils.setAlphaComponent(color, alpha.coerceIn(0, 255))
    }

    /**
     * 主题包配置的面板背景图。未配置或文件不存在返回 null，表面走默认模糊底图。
     */
    fun themePanelImagePath(context: Context): String? {
        val key = if (AppConfig.isNightTheme) {
            PreferKey.panelBgImageN
        } else {
            PreferKey.panelBgImage
        }
        val path = context.getPrefString(key)?.takeIf { it.isNotBlank() } ?: return null
        return File(path).takeIf { it.isFile }?.absolutePath
    }

    fun themePanelImageFitInside(context: Context): Boolean {
        val key = if (AppConfig.isNightTheme) {
            PreferKey.panelBgScaleTypeN
        } else {
            PreferKey.panelBgScaleType
        }
        return context.getPrefString(key) == ThemeConfig.PANEL_BG_FIT
    }

    /**
     * 顶栏包配置的标签条底色（已折算配置透明度）。
     * 仅在激活了非默认顶栏包时生效；未激活或未配置返回 null，保持主题默认行为。
     */
    fun themeSurfaceTagBarColor(context: Context): Int? {
        val config = activeTopBarConfigOrNull(context) ?: return null
        val color = config.tagBarColor ?: return null
        return TopBarConfig.withOpacity(color, config.tagBarAlpha)
    }

    /**
     * 顶栏包配置的标签选中色（已折算配置透明度），优先级同上。
     */
    fun themeSurfaceTagSelectedColor(context: Context): Int? {
        val config = activeTopBarConfigOrNull(context) ?: return null
        val color = config.tagSelectedColor ?: return null
        return TopBarConfig.withOpacity(color, config.tagSelectedAlpha)
    }

    private fun activeTopBarConfigOrNull(context: Context): TopBarConfig.Config? {
        if (TopBarConfig.activeDirName(AppConfig.isNightTheme) == TopBarConfig.DEFAULT_DIR_NAME) {
            return null
        }
        return TopBarConfig.currentConfig(context)
    }

    private fun themeSurfaceColorOverride(context: Context, dayKey: String, nightKey: String): Int? {
        val key = if (AppConfig.isNightTheme) nightKey else dayKey
        val raw = context.getPrefString(key)?.takeIf { it.isNotBlank() } ?: return null
        return runCatching { raw.toColorInt() }.getOrNull()
    }
}
