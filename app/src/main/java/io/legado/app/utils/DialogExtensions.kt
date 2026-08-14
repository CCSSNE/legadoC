package io.legado.app.utils

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
import androidx.core.view.forEach
import androidx.fragment.app.DialogFragment
import com.google.android.material.R as MaterialR
import io.legado.app.R
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.theme.Selector
import io.legado.app.lib.theme.ThemeStore
import io.legado.app.lib.theme.UiCorner
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.dialogSurfaceBackground
import io.legado.app.lib.theme.primaryColor
import io.legado.app.lib.theme.primaryColorDark
import splitties.systemservices.windowManager
import java.util.WeakHashMap

private const val DIALOG_BLUR_MAX_ATTEMPTS = 24
private const val DIALOG_BLUR_RETRY_DELAY_MS = 16L
private val preparedDialogWindowAlphas = WeakHashMap<Window, Float>()
private val dialogBlurGenerations = WeakHashMap<Window, Int>()

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
        window?.decorView?.applyDialogSurfaceChildren()
        applyMaxWidthIfFloating()
    }
    return this
}

fun Dialog.applyDialogSurfaceBlur() {
    val dialogWindow = window ?: return
    val dialogDecor = dialogWindow.decorView
    val radius = if (AppConfig.isEInkMode) 0 else UiCorner.dialogBlurRadius()
    val activityWindow = context.findActivity()?.window
    val generation = (dialogBlurGenerations[dialogWindow] ?: 0) + 1
    dialogBlurGenerations[dialogWindow] = generation

    fun clearWindowDim() {
        val attributes = dialogWindow.attributes
        if (attributes.flags and WindowManager.LayoutParams.FLAG_DIM_BEHIND == 0 &&
            attributes.dimAmount == 0f
        ) {
            return
        }
        dialogWindow.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        attributes.dimAmount = 0f
        dialogWindow.attributes = attributes
    }

    // 弹窗的玻璃效果不应再叠加系统整屏变暗层；否则弹窗一出现，底图亮度就会整体下降。
    clearWindowDim()

    fun hideWindowForPreparation() {
        if (radius <= 0 || activityWindow == null) return
        val alpha = dialogWindow.attributes.alpha
        if (alpha <= 0f) return
        if (!preparedDialogWindowAlphas.containsKey(dialogWindow)) {
            preparedDialogWindowAlphas[dialogWindow] = alpha
        }
        dialogWindow.attributes = dialogWindow.attributes.apply { this.alpha = 0f }
    }

    fun revealWindow() {
        if (dialogBlurGenerations[dialogWindow] != generation) return
        val alpha = preparedDialogWindowAlphas.remove(dialogWindow) ?: return
        if (dialogWindow.decorView.isAttachedToWindow) {
            dialogWindow.attributes = dialogWindow.attributes.apply { this.alpha = alpha }
        }
    }

    fun clearWindowBlur() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        kotlin.runCatching {
            dialogWindow.setBackgroundBlurRadius(0)
            dialogWindow.clearFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
            dialogWindow.attributes = dialogWindow.attributes.apply {
                setBlurBehindRadius(0)
            }
        }
    }

    hideWindowForPreparation()

    fun prepare(attempt: Int) {
        dialogDecor.post {
            if (dialogBlurGenerations[dialogWindow] != generation) return@post
            clearWindowDim()
            clearWindowBlur()
            dialogDecor.applyDialogSurfaceChildren()
            val blurTarget = dialogDecor.findDialogBlurTarget()
            val targetReady = blurTarget != null &&
                blurTarget.isAttachedToWindow &&
                blurTarget.width > 0 &&
                blurTarget.height > 0

            if (radius <= 0 || activityWindow == null) {
                revealWindow()
            } else if (!dialogDecor.isAttachedToWindow || !targetReady) {
                if (attempt < DIALOG_BLUR_MAX_ATTEMPTS) {
                    dialogDecor.postDelayed(
                        { prepare(attempt + 1) },
                        DIALOG_BLUR_RETRY_DELAY_MS
                    )
                } else {
                    // 不能让异常布局把弹窗永久藏住；宁可显示无模糊弹窗，也不显示全局错误模糊。
                    revealWindow()
                }
            } else {
                LocalPopupBlur.apply(
                    activityWindow,
                    listOfNotNull(blurTarget),
                    radius = radius,
                    onReady = ::revealWindow
                )
            }
        }
    }

    prepare(0)
}

private fun View.findDialogBlurTarget(): View? {
    findViewById<View>(R.id.vw_bg)?.takeIf { it.isDialogSurfaceCandidate(this) }?.let { return it }
    findViewById<View>(MaterialR.id.design_bottom_sheet)
        ?.takeIf { it.isDialogSurfaceCandidate(this) }
        ?.let { return it }
    val content = findViewById<ViewGroup>(android.R.id.content)
    if (content == null) return null

    val backgroundCandidates = ArrayList<View>()
    val directCandidates = ArrayList<View>()
    fun walk(view: View, direct: Boolean) {
        if (view !== content) {
            if (view.isDialogSurfaceCandidate(this)) {
                if (view.background != null) backgroundCandidates += view
            }
            if (direct && view.isDialogSurfaceCandidate(this, requireBackground = false)) {
                directCandidates += view
            }
        }
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                walk(view.getChildAt(index), direct = view === content)
            }
        }
    }
    walk(content, direct = false)
    return (backgroundCandidates + directCandidates)
        .maxByOrNull { it.width.toLong() * it.height.toLong() }
}

private fun View.isDialogSurfaceCandidate(
    decor: View,
    requireBackground: Boolean = true
): Boolean {
    if (!isAttachedToWindow || width <= 0 || height <= 0) return false
    val fillsWindow = width >= decor.width * 0.98f && height >= decor.height * 0.98f
    if (fillsWindow) return false
    val background = background ?: return !requireBackground
    if (background is ColorDrawable && Color.alpha(background.color) == 0) return false
    return background.alpha > 0
}

/**
 * 把弹窗内部仍然写死的背景色接入统一的弹窗表面组。
 * 只处理现有主题资源和工具栏主色，不碰图标、输入框和自定义图片背景。
 */
fun View.applyDialogSurfaceChildren() {
    val root = this
    val surfaceColors = setOf(
        ContextCompat.getColor(context, R.color.background),
        ContextCompat.getColor(context, R.color.background_card),
        ContextCompat.getColor(context, R.color.background_menu),
        ContextCompat.getColor(context, R.color.dialog_surface)
    )
    val dialogMenuColor = ContextCompat.getColor(context, R.color.background_menu)
    val headerNames = setOf(
        "action_bar",
        "alertTitle",
        "header",
        "title_bar",
        "toolbar",
        "tool_bar",
        "topPanel",
        "title_template",
        "titleDivider",
        "titleDividerNoCustom"
    )
    fun resourceName(view: View): String? {
        return runCatching { view.resources.getResourceEntryName(view.id) }.getOrNull()
    }

    fun clearHeaderSurface(view: View) {
        view.background = ColorDrawable(android.graphics.Color.TRANSPARENT)
        view.backgroundTintList = null
        view.elevation = 0f
        view.stateListAnimator = null
    }

    fun rewrite(view: View) {
        val name = resourceName(view)
        val className = view.javaClass.simpleName
        val isHeaderClass = view is Toolbar ||
            className == "TitleBar" ||
            className == "AppBarLayout" ||
            className.endsWith("AppBarLayout")
        if (isHeaderClass || name in headerNames) {
            // 标题仍由原控件绘制，但不再给它单独铺一块色块。
            clearHeaderSurface(view)
            return
        }
        val drawableColor = (view.background as? ColorDrawable)?.color
        val replacement = when {
            drawableColor == context.primaryColor || drawableColor == context.primaryColorDark ->
                UiCorner.dialogSurfaceColor(dialogMenuColor)
            drawableColor != null && drawableColor in surfaceColors ->
                UiCorner.dialogSurfaceColor(drawableColor)
            else -> return
        }
        view.background = ColorDrawable(replacement)
    }
    fun walk(view: View) {
        rewrite(view)
        if (view is ViewGroup) {
            view.forEach(::walk)
        }
    }
    walk(root)
    // 部分弹窗在首帧之后才给标题栏设置主题背景；第二次扫描覆盖这些延迟赋值。
    root.post { walk(root) }
}

fun Dialog.applyAdaptiveDim() {
    applyDialogSurfaceBlur()
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
