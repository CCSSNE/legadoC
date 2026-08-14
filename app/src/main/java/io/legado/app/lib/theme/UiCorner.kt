package io.legado.app.lib.theme

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import androidx.core.graphics.ColorUtils
import io.legado.app.R
import io.legado.app.help.config.AppConfig
import io.legado.app.utils.dpToPx
import kotlin.math.roundToInt

object UiCorner {

    fun scale(): Float {
        return AppConfig.uiCornerScale.coerceIn(0f, 3f)
    }

    fun panelRadius(context: Context): Float {
        return context.resources.getDimension(R.dimen.ui_panel_radius) * scale()
    }

    fun actionRadius(context: Context): Float {
        return context.resources.getDimension(R.dimen.ui_action_radius) * scale()
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
     * 标准底栏的表面不透明度，供需要与底栏保持同一透明规律的独立界面使用。
     * 不包含全局悬浮块组透明度，避免把两个设置混成一个值。
     */
    fun standardBarAlpha(): Float {
        return when {
            AppConfig.isEInkMode -> 1f
            AppConfig.bottomBarEffectMode == "solid" -> {
                AppConfig.liquidGlassLevel.coerceIn(0, 100) / 100f
            }
            else -> {
                val level = when (AppConfig.bottomBarEffectMode) {
                    "frosted" -> AppConfig.frostedGlassLevel
                    else -> AppConfig.liquidGlassLevel
                }.coerceIn(0, 100) / 100f
                (0.24f + level * 0.38f).coerceIn(0f, 1f)
            }
        }
    }

    /**
     * 悬浮块组的统一表面不透明度。
     * 搜索条、卡片、分组条、底部导航等浮层表面都从这里取全局系数。
     */
    fun floatingGroupAlpha(): Float {
        val configuredAlpha = AppConfig.uiLayoutAlpha.coerceIn(0, 100) / 100f
        return (configuredAlpha * standardBarAlpha()).coerceIn(0f, 1f)
    }

    fun dialogSurfaceColor(color: Int): Int {
        val transparency = AppConfig.dialogAlpha.coerceIn(0, 100)
        val alpha = (Color.alpha(color) * (100 - transparency) / 100f)
            .roundToInt()
        return ColorUtils.setAlphaComponent(color, alpha)
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
        return opaqueRounded(dialogSurfaceColor(color), radius)
    }

    fun dialogTopRounded(color: Int, radius: Float): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadii = floatArrayOf(
                radius, radius,
                radius, radius,
                0f, 0f,
                0f, 0f
            )
            setColor(dialogSurfaceColor(color))
        }
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
}
