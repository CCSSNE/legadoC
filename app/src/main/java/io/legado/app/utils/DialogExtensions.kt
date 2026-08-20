package io.legado.app.utils

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.ContextWrapper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
import androidx.core.view.children
import androidx.core.view.forEach
import androidx.fragment.app.DialogFragment
import io.legado.app.R
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.theme.Selector
import io.legado.app.lib.theme.ThemeStore
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.surface.SurfaceStyles
import io.legado.app.lib.theme.surface.SurfaceStyle
import splitties.systemservices.windowManager
import java.util.WeakHashMap

private val preparedDialogWindowAlphas = WeakHashMap<Window, Float>()
private val dialogBlurGenerations = WeakHashMap<Window, Int>()
private val movedAlertTitlePanels = WeakHashMap<View, Boolean>()

fun AlertDialog.applyTint(): AlertDialog {
    applyAlertSurface()
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

/** Uses AppCompat's documented alert panel as the single visible surface. */
fun AlertDialog.applyAlertSurface() {
    val dialogWindow = window ?: return
    dialogWindow.setBackgroundDrawableResource(android.R.color.transparent)
    val decor = dialogWindow.decorView
    // The alert hierarchy may not exist before show(). Never fall back to decor:
    // doing so creates a second, window-sized surface behind parentPanel.
    val panel = decor.findViewById<View>(androidx.appcompat.R.id.parentPanel) ?: return
    intArrayOf(
        androidx.appcompat.R.id.topPanel,
        androidx.appcompat.R.id.contentPanel,
        androidx.appcompat.R.id.buttonPanel,
        androidx.appcompat.R.id.customPanel
    ).forEach { id ->
        panel.findViewById<View>(id)?.background = null
    }
    moveAlertTitleIntoBody(panel)
    if (AppConfig.isEInkMode) {
        // The window is transparent so parentPanel must remain the sole visible surface.
        applyDialogSurfaceBlur(panel)
        panel.setBackgroundResource(R.drawable.bg_eink_border_dialog)
        return
    }
    applyDialogSurfaceBlur(panel)
}

/** Keeps an AlertDialog title semantic without leaving it in a top chrome panel. */
private fun moveAlertTitleIntoBody(panel: View) {
    val topPanel = panel.findViewById<View>(androidx.appcompat.R.id.topPanel) ?: return
    if (movedAlertTitlePanels.put(topPanel, true) == null) {
        val title = topPanel.findFirstTitleText()
        if (title != null) {
            (title.parent as? ViewGroup)?.removeView(title)
            val customPanel = panel.findViewById<ViewGroup>(androidx.appcompat.R.id.customPanel)
                ?.takeIf { it.visibility != View.GONE }
            if (customPanel == null) {
                moveAlertTitleIntoContentPanel(panel, title)
            } else {
                moveAlertTitleIntoCustomPanel(customPanel, title)
            }
        }
    }
    topPanel.visibility = View.GONE
}

private fun moveAlertTitleIntoContentPanel(panel: View, title: View) {
    val contentPanel = requireNotNull(
        panel.findViewById<ViewGroup>(androidx.appcompat.R.id.contentPanel)
    ) { "AppCompat AlertDialog title requires a content panel" }
    val originalContent = contentPanel.children.toList()
    val contentColumn = LinearLayout(panel.context).apply {
        orientation = LinearLayout.VERTICAL
        addView(
            title,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
    }
    originalContent.forEach { child ->
        contentPanel.removeView(child)
        contentColumn.addView(
            child,
            LinearLayout.LayoutParams(
                child.layoutParams.width,
                child.layoutParams.height.takeUnless {
                    it == ViewGroup.LayoutParams.MATCH_PARENT
                } ?: ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
    }
    contentPanel.visibility = View.VISIBLE
    contentPanel.addView(
        contentColumn,
        ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    )
}

private fun moveAlertTitleIntoCustomPanel(customPanel: ViewGroup, title: View) {
    val customContainer = requireNotNull(
        customPanel.findViewById<ViewGroup>(androidx.appcompat.R.id.custom)
    ) { "AppCompat custom AlertDialog title requires a custom container" }
    require(customContainer.childCount == 1) {
        "AppCompat custom AlertDialog title requires exactly one custom content view"
    }
    val customContent = customContainer.getChildAt(0)
    customContainer.removeView(customContent)
    customPanel.removeAllViews()
    customPanel.addView(
        HeaderlessAlertCustomContent(customPanel.context, title, customContent),
        ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    )
}

/** Measures a title and custom body together so AppCompat keeps one middle panel and its footer. */
private class HeaderlessAlertCustomContent(
    context: Context,
    private val title: View,
    private val content: View
) : ViewGroup(context) {

    init {
        addView(title)
        addView(content)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val widthMode = MeasureSpec.getMode(widthMeasureSpec)
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val contentWidth = (width - paddingLeft - paddingRight).coerceAtLeast(0)
        val childWidthSpec = MeasureSpec.makeMeasureSpec(
            contentWidth,
            if (widthMode == MeasureSpec.UNSPECIFIED) MeasureSpec.UNSPECIFIED else MeasureSpec.EXACTLY
        )
        title.measure(childWidthSpec, MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))

        val heightMode = MeasureSpec.getMode(heightMeasureSpec)
        val availableContentHeight = if (heightMode == MeasureSpec.UNSPECIFIED) {
            0
        } else {
            (MeasureSpec.getSize(heightMeasureSpec) - paddingTop - paddingBottom - title.measuredHeight)
                .coerceAtLeast(0)
        }
        val contentHeightSpec = MeasureSpec.makeMeasureSpec(
            availableContentHeight,
            if (heightMode == MeasureSpec.UNSPECIFIED) MeasureSpec.UNSPECIFIED else MeasureSpec.AT_MOST
        )
        content.measure(childWidthSpec, contentHeightSpec)

        val desiredWidth = paddingLeft + paddingRight + maxOf(title.measuredWidth, content.measuredWidth)
        val desiredHeight = paddingTop + paddingBottom + title.measuredHeight + content.measuredHeight
        setMeasuredDimension(
            resolveSizeAndState(desiredWidth, widthMeasureSpec, 0),
            resolveSizeAndState(desiredHeight, heightMeasureSpec, 0)
        )
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val contentLeft = paddingLeft
        val contentRight = width - paddingRight
        val titleTop = paddingTop
        val titleBottom = titleTop + title.measuredHeight
        title.layout(contentLeft, titleTop, contentRight, titleBottom)
        content.layout(contentLeft, titleBottom, contentRight, titleBottom + content.measuredHeight)
    }
}

private fun View.findFirstTitleText(): android.widget.TextView? {
    if (this is android.widget.TextView && text?.isNotBlank() == true) return this
    return (this as? ViewGroup)?.children?.firstNotNullOfOrNull { it.findFirstTitleText() }
}

/** Shared host policy for the three AndroidX preference dialog variants. */
fun Dialog.applyPreferenceDialogSurface() {
    if (AppConfig.isEInkMode) {
        val dialogWindow = window ?: return
        dialogWindow.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        dialogWindow.attributes = dialogWindow.attributes.apply {
            dimAmount = 0f
            windowAnimations = 0
        }
        dialogWindow.setBackgroundDrawableResource(R.color.transparent)
        when (dialogWindow.attributes.gravity) {
            Gravity.TOP -> dialogWindow.decorView.setBackgroundResource(R.drawable.bg_eink_border_bottom)
            Gravity.BOTTOM -> dialogWindow.decorView.setBackgroundResource(R.drawable.bg_eink_border_top)
            else -> {
                val padding = 2.dpToPx()
                dialogWindow.decorView.setPadding(padding, padding, padding, padding)
                dialogWindow.decorView.setBackgroundResource(R.drawable.bg_eink_border_dialog)
            }
        }
    } else {
        (this as? AlertDialog)?.applyAlertSurface()
    }
}

/**
 * Applies one glass surface to the exact panel owned by the dialog.
 *
 * The panel is explicit by design. A full-screen click-outside root and the visible
 * panel are not interchangeable, even when each fills its own window.
 */
fun Dialog.applyDialogSurfaceBlur(
    surface: View,
    style: SurfaceStyle = SurfaceStyles.dialog(context)
) {
    val dialogWindow = window ?: return
    val target = surface

    fun clearSystemWindowEffects() {
        dialogWindow.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        dialogWindow.attributes = dialogWindow.attributes.apply { dimAmount = 0f }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            kotlin.runCatching {
                dialogWindow.setBackgroundBlurRadius(0)
                dialogWindow.clearFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                dialogWindow.attributes = dialogWindow.attributes.apply {
                    setBlurBehindRadius(0)
                }
            }
        }
    }

    clearSystemWindowEffects()
    if (AppConfig.isEInkMode) return

    val hostWindow = context.findActivity()?.window
    val generation = (dialogBlurGenerations[dialogWindow] ?: 0) + 1
    dialogBlurGenerations[dialogWindow] = generation
    SurfaceBackdrop.installStatic(target, style)

    fun revealWindow() {
        if (dialogBlurGenerations[dialogWindow] != generation) return
        val alpha = preparedDialogWindowAlphas[dialogWindow] ?: return
        if (dialogWindow.decorView.isAttachedToWindow) {
            preparedDialogWindowAlphas.remove(dialogWindow)
            dialogWindow.attributes = dialogWindow.attributes.apply { this.alpha = alpha }
        }
    }

    // Do not hide the dialog (alpha=0) while PixelCopy runs. On transparent
    // hosts such as OnLineImportActivity this left the import sheet invisible,
    // so the first tap dismissed it and finishOnDismiss finished the activity.
    val hostTranslucent = context.isTranslucentActivity()
    if (style.blurRadiusPx <= 0 || hostWindow == null || hostTranslucent) {
        revealWindow()
        return
    }
    SurfaceBackdrop.apply(
        hostWindow = hostWindow,
        target = target,
        style = style,
        onReady = {}
    )
}

fun Dialog.applyAdaptiveDim(
    surface: View,
    style: SurfaceStyle = SurfaceStyles.dialog(context)
) {
    applyDialogSurfaceBlur(surface, style)
}

private fun Context.isTranslucentActivity(): Boolean {
    val activity = findActivity() ?: return false
    val ta = activity.theme.obtainStyledAttributes(intArrayOf(android.R.attr.windowIsTranslucent))
    val translucent = ta.getBoolean(0, false)
    ta.recycle()
    return translucent
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
