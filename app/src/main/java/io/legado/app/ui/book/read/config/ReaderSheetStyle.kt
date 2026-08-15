package io.legado.app.ui.book.read.config

import android.content.Context
import android.graphics.drawable.GradientDrawable
import io.legado.app.R
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.bottomBackground
import io.legado.app.lib.theme.getPrimaryTextColor
import io.legado.app.lib.theme.primaryColor
import io.legado.app.lib.theme.UiCorner
import io.legado.app.lib.theme.surface.SurfaceCorners
import io.legado.app.lib.theme.surface.SurfaceStyle
import io.legado.app.lib.theme.surface.SurfaceStyles
import io.legado.app.utils.ColorUtils
import io.legado.app.utils.dpToPx
import io.legado.app.utils.getCompatColor

object ReaderSheetStyle {

    data class Palette(
        val surface: Int,
        val panel: Int,
        val panelStrong: Int,
        val stroke: Int,
        val textColor: Int,
        val secondaryTextColor: Int,
        val primaryColor: Int,
        val accentColor: Int
    )

    fun resolve(context: Context, baseColor: Int = context.bottomBackground): Palette {
        val base = baseColor
        val isLight = ColorUtils.isColorLight(base)
        val textColor = context.getPrimaryTextColor(isLight)
        val accentColor = context.accentColor
        val primaryColor = context.primaryColor
        val cardColor = context.getCompatColor(R.color.background_card)
        val surfaceBase = ColorUtils.blendColors(base, cardColor, if (isLight) 0.86f else 0.64f)
        val surface = ColorUtils.blendColors(
            surfaceBase,
            primaryColor,
            if (isLight) 0.08f else 0.16f
        )
        val panel = ColorUtils.blendColors(
            surface,
            accentColor,
            if (isLight) 0.06f else 0.12f
        )
        val panelStrong = ColorUtils.blendColors(
            surface,
            primaryColor,
            if (isLight) 0.14f else 0.22f
        )
        val strokeBase = ColorUtils.blendColors(primaryColor, textColor, 0.3f)
        val stroke = ColorUtils.adjustAlpha(strokeBase, if (isLight) 0.2f else 0.28f)
        val secondaryTextColor = ColorUtils.withAlpha(textColor, if (isLight) 0.72f else 0.78f)
        return Palette(
            surface = surface,
            panel = panel,
            panelStrong = panelStrong,
            stroke = stroke,
            textColor = textColor,
            secondaryTextColor = secondaryTextColor,
            primaryColor = primaryColor,
            accentColor = accentColor
        )
    }

    fun topSheetSurfaceStyle(
        context: Context,
        baseColor: Int = context.bottomBackground
    ): SurfaceStyle {
        val palette = resolve(context, baseColor)
        return SurfaceStyles.reading(
            tintColor = UiCorner.dialogSurfaceColor(palette.surface),
            cornerRadiusPx = UiCorner.scaledDp(10f),
            corners = SurfaceCorners.TOP,
            strokeColor = UiCorner.dialogSurfaceColor(palette.stroke),
            strokeWidthPx = 1.dpToPx().toFloat()
        )
    }

    fun blockDrawable(fillColor: Int, strokeColor: Int, radiusDp: Float = 10f): GradientDrawable {
        return GradientDrawable().apply {
            cornerRadius = UiCorner.scaledDp(radiusDp)
            setColor(fillColor)
            setStroke(1.dpToPx(), strokeColor)
        }
    }
}
