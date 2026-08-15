package io.legado.app.lib.theme.surface

import android.content.Context
import androidx.annotation.ColorInt
import androidx.core.content.ContextCompat
import io.legado.app.R
import io.legado.app.lib.theme.UiCorner

/**
 * 可调表面的唯一视觉描述。窗口类型、截图时机和内容布局不应进入这里。
 */
data class SurfaceStyle(
    @param:ColorInt val tintColor: Int,
    val cornerRadiusPx: Float,
    val corners: SurfaceCorners = SurfaceCorners.ALL,
    @param:ColorInt val strokeColor: Int = android.graphics.Color.TRANSPARENT,
    val strokeWidthPx: Float = 0f,
    val blurRadiusPx: Int = 0
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

    fun dialog(context: Context, corners: SurfaceCorners = SurfaceCorners.ALL): SurfaceStyle {
        return SurfaceStyle(
            tintColor = UiCorner.dialogSurfaceColor(
                ContextCompat.getColor(context, R.color.dialog_surface)
            ),
            cornerRadiusPx = UiCorner.compactSurfaceRadius(context),
            corners = corners,
            blurRadiusPx = UiCorner.dialogBlurRadius()
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
