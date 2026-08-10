package io.legado.app.utils

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
import androidx.core.view.forEach
import androidx.fragment.app.DialogFragment
import io.legado.app.R
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.theme.Selector
import io.legado.app.lib.theme.ThemeStore
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.dialogSurfaceBackground
import splitties.systemservices.windowManager

fun AlertDialog.applyTint(): AlertDialog {
    window?.setBackgroundDrawable(context.dialogSurfaceBackground)
    applyAdaptiveDim()
    val colorStateList = Selector.colorBuild()
        .setDefaultColor(ThemeStore.accentColor(context))
        .setPressedColor(ColorUtils.darkenColor(ThemeStore.accentColor(context)))
        .create()
    if (getButton(AlertDialog.BUTTON_NEGATIVE) != null) {
        getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(colorStateList)
    }
    if (getButton(AlertDialog.BUTTON_POSITIVE) != null) {
        getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(colorStateList)
    }
    if (getButton(AlertDialog.BUTTON_NEUTRAL) != null) {
        getButton(AlertDialog.BUTTON_NEUTRAL).setTextColor(colorStateList)
    }
    window?.decorView?.post {
        listView?.forEach {
            it.applyTint(context.accentColor)
        }
        applyMaxWidthIfFloating()
    }
    return this
}

fun Dialog.applyAdaptiveDim() {
    if (AppConfig.isEInkMode) return
    val isLightBackground = ColorUtils.isColorLight(
        ContextCompat.getColor(context, R.color.background_card)
    )
    if (isLightBackground) return
    val activity = context.findActivity() ?: return
    val activityDecor = activity.window.decorView
    val dimForeground = ColorDrawable(ColorUtils.withAlpha(Color.WHITE, NIGHT_DIALOG_DIM_ALPHA))
    val dialogDecor = window?.decorView ?: return
    fun addOverlay() {
        val dialogWindow = window ?: return
        val attr = dialogWindow.attributes
        val hasWindowDim = attr.flags and WindowManager.LayoutParams.FLAG_DIM_BEHIND != 0
        if (!hasWindowDim || attr.dimAmount <= 0f) {
            return
        }
        dialogWindow.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        attr.dimAmount = 0f
        dialogWindow.attributes = attr
        dimForeground.setBounds(0, 0, activityDecor.width, activityDecor.height)
        activityDecor.overlay.add(dimForeground)
    }
    dialogDecor.post {
        if (activityDecor.width > 0 && activityDecor.height > 0) {
            addOverlay()
        } else {
            activityDecor.post { addOverlay() }
        }
    }
    dialogDecor.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) = Unit

        override fun onViewDetachedFromWindow(v: View) {
            v.removeOnAttachStateChangeListener(this)
            activityDecor.overlay.remove(dimForeground)
        }
    })
}

private fun Context.findActivity(): Activity? {
    var ctx = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}

fun AlertDialog.requestInputMethod() {
    window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
}

fun DialogFragment.setLayout(widthMix: Float, heightMix: Float) {
    dialog?.setLayout(widthMix, heightMix)
}

fun Dialog.setLayout(widthMix: Float, heightMix: Float) {
    val dm = context.windowManager.windowSize
    val height = (dm.heightPixels * heightMix).toInt()
    window?.setLayout(
        resolveFloatingDialogWidth((dm.widthPixels * widthMix).toInt(), height),
        height
    )
}

fun DialogFragment.setLayout(width: Int, heightMix: Float) {
    dialog?.setLayout(width, heightMix)
}

fun Dialog.setLayout(width: Int, heightMix: Float) {
    val dm = context.windowManager.windowSize
    val height = (dm.heightPixels * heightMix).toInt()
    window?.setLayout(
        resolveFloatingDialogWidth(width, height),
        height
    )
}

fun DialogFragment.setLayout(widthMix: Float, height: Int) {
    dialog?.setLayout(widthMix, height)
}

fun Dialog.setLayout(widthMix: Float, height: Int) {
    val dm = context.windowManager.windowSize
    window?.setLayout(
        resolveFloatingDialogWidth((dm.widthPixels * widthMix).toInt(), height),
        height
    )
}

fun DialogFragment.setLayout(width: Int, height: Int) {
    dialog?.setLayout(width, height)
}

fun Dialog.setLayout(width: Int, height: Int) {
    window?.setLayout(resolveFloatingDialogWidth(width, height), height)
}

/**
 * 全宽显示，高度随内容收缩，且不超过屏幕高度的 [maxHeightMix] 比例。
 * 超出时限制 [scrollView] 高度以便内部滚动。
 */
fun DialogFragment.setLayoutWrapMaxHeight(
    maxHeightMix: Float = 0.85f,
    panelView: ViewGroup,
    scrollView: View
) {
    dialog?.setLayoutWrapMaxHeight(maxHeightMix, panelView, scrollView)
}

fun Dialog.setLayoutWrapMaxHeight(
    maxHeightMix: Float = 0.85f,
    panelView: ViewGroup,
    scrollView: View
) {
    val dm = context.windowManager.windowSize
    val maxPanelHeight = (dm.heightPixels * maxHeightMix).toInt()
    val root = panelView.parent as? View
    fun apply() {
        val rootPadV = root?.let { it.paddingTop + it.paddingBottom } ?: 0
        val rootPadH = root?.let { it.paddingLeft + it.paddingRight } ?: 0
        val panelWidth = (root?.width?.takeIf { it > 0 } ?: dm.widthPixels) - rootPadH
        val widthSpec = View.MeasureSpec.makeMeasureSpec(panelWidth, View.MeasureSpec.EXACTLY)
        val scrollLp = scrollView.layoutParams as ViewGroup.MarginLayoutParams
        scrollLp.height = ViewGroup.LayoutParams.WRAP_CONTENT
        scrollView.layoutParams = scrollLp
        panelView.measure(
            widthSpec,
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val naturalPanelHeight = panelView.measuredHeight
        val maxContentHeight = maxPanelHeight - rootPadV
        if (naturalPanelHeight > maxContentHeight) {
            val toolbar = panelView.getChildAt(0)
            toolbar?.measure(
                widthSpec,
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            )
            val toolbarHeight = toolbar?.measuredHeight ?: 0
            scrollLp.height = (maxContentHeight - toolbarHeight).coerceAtLeast(0)
            scrollView.layoutParams = scrollLp
            panelView.measure(
                widthSpec,
                View.MeasureSpec.makeMeasureSpec(maxContentHeight, View.MeasureSpec.EXACTLY)
            )
        }
        val dialogHeight = panelView.measuredHeight.coerceAtMost(maxContentHeight) + rootPadV
        window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, dialogHeight)
        window?.attributes = window?.attributes?.apply {
            gravity = Gravity.CENTER
        }
    }
    if (panelView.width > 0) {
        apply()
    } else {
        panelView.post { apply() }
    }
}

private fun Dialog.applyMaxWidthIfFloating() {
    val attrs = window?.attributes ?: return
    val width = attrs.width
    val height = attrs.height
    if (width > 0 || width == WindowManager.LayoutParams.MATCH_PARENT) {
        window?.setLayout(resolveFloatingDialogWidth(width, height), height)
    }
}

private fun Dialog.resolveFloatingDialogWidth(width: Int, height: Int): Int {
    val attrs = window?.attributes ?: return width
    val isSheet = attrs.gravity and Gravity.BOTTOM == Gravity.BOTTOM ||
            attrs.gravity and Gravity.TOP == Gravity.TOP
    val isFullScreen = height == WindowManager.LayoutParams.MATCH_PARENT
    if (isSheet || isFullScreen) return width
    val dm = context.windowManager.windowSize
    val maxWidth = minOf((dm.widthPixels * 0.88f).toInt(), 520.dpToPx())
    return when {
        width == WindowManager.LayoutParams.MATCH_PARENT -> maxWidth
        width > maxWidth -> maxWidth
        else -> width
    }
}

fun Dialog.toggleSystemBar(show: Boolean) {
    window?.let { window ->
        WindowCompat.getInsetsController(window, window.decorView).run {
            if (show) {
                show(WindowInsetsCompat.Type.systemBars())
                window.clearFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
            } else {
                hide(WindowInsetsCompat.Type.systemBars())
                systemBarsBehavior = BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                window.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
            }
        }
    }
}

fun Dialog.keepScreenOn(on: Boolean) {
    window?.let { window ->
        val isScreenOn =
            (window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) != 0
        if (on == isScreenOn) return
        if (on) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
}

private const val NIGHT_DIALOG_DIM_ALPHA = 0.12f
