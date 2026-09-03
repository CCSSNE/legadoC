package io.legado.app.lib.theme.surface

import android.content.Context
import androidx.annotation.ColorInt
import io.legado.app.lib.theme.UiCorner
import io.legado.app.utils.dpToPx

/**
 * 可调表面的唯一视觉描述。窗口类型、截图时机和内容布局不应进入这里。
 */
data class SurfaceStyle(
    @param:ColorInt val tintColor: Int,
    val cornerRadiusPx: Float,
    val corners: SurfaceCorners = SurfaceCorners.ALL,
    @param:ColorInt val strokeColor: Int = android.graphics.Color.TRANSPARENT,
    val strokeWidthPx: Float = 0f,
    val blurRadiusPx: Int = 0,
    /**
     * 主题包指定的静态底图（面板背景图）。配置后表面以该图为底，
     * 不再做 PixelCopy 模糊采集；tint 与描边仍按同一透明度体系叠加。
     */
    val backdropImagePath: String? = null,
    /** true 时底图等比完整居中；false（默认）时等比居中裁剪铺满。 */
    val backdropImageFitInside: Boolean = false
)

enum class SurfaceCorners {
    NONE,
    ALL,
    TOP
}

/**
 * 弹窗、阅读浮层和普通 UI 块从这里取得样式，避免各页面重复计算透明度与圆角。
 */
object SurfaceStyles {

    private const val PANEL_STROKE_WIDTH_DP = 1f

    fun dialog(context: Context, corners: SurfaceCorners = SurfaceCorners.ALL): SurfaceStyle {
        val themeStroke = UiCorner.themePanelBorderColor(context)
        return SurfaceStyle(
            tintColor = UiCorner.dialogSurfaceColor(
                UiCorner.themeSurfaceDialogColor(context)
            ),
            cornerRadiusPx = UiCorner.compactSurfaceRadius(context),
            corners = corners,
            strokeColor = themeStroke ?: android.graphics.Color.TRANSPARENT,
            strokeWidthPx = if (themeStroke != null) {
                PANEL_STROKE_WIDTH_DP.dpToPx()
            } else {
                0f
            },
            blurRadiusPx = UiCorner.dialogBlurRadius(),
            backdropImagePath = UiCorner.themePanelImagePath(context),
            backdropImageFitInside = UiCorner.themePanelImageFitInside(context)
        )
    }

    fun popup(context: Context): SurfaceStyle = dialog(context)

    fun reading(
        @ColorInt tintColor: Int,
        cornerRadiusPx: Float,
        corners: SurfaceCorners = SurfaceCorners.ALL,
        @ColorInt strokeColor: Int = android.graphics.Color.TRANSPARENT,
        strokeWidthPx: Float = 0f,
        blurRadiusPx: Int = UiCorner.dialogBlurRadius()
    ): SurfaceStyle {
        return SurfaceStyle(
            tintColor = tintColor,
            cornerRadiusPx = cornerRadiusPx,
            corners = corners,
            strokeColor = strokeColor,
            strokeWidthPx = strokeWidthPx,
            blurRadiusPx = blurRadiusPx
        )
    }

    fun ui(
        context: Context,
        @ColorInt color: Int,
        cornerRadiusPx: Float = UiCorner.panelRadius(context),
        corners: SurfaceCorners = SurfaceCorners.ALL,
        @ColorInt strokeColor: Int = android.graphics.Color.TRANSPARENT,
        strokeWidthPx: Float = 0f
    ): SurfaceStyle {
        return SurfaceStyle(
            tintColor = UiCorner.surfaceColor(color),
            cornerRadiusPx = cornerRadiusPx,
            corners = corners,
            strokeColor = strokeColor,
            strokeWidthPx = strokeWidthPx
        )
    }
}
