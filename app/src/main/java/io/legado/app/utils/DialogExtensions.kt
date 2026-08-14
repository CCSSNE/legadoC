package io.legado.app.utils

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.PixelCopy
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

    dialogDecor.post {
        clearWindowDim()
        val blurTarget = dialogDecor.findDialogBlurTarget()
        val hostDecor = activityWindow?.decorView
        val fullWindow = activityWindow != null && hostDecor != null &&
            dialogDecor.width >= hostDecor.width * 0.98f &&
            dialogDecor.height >= hostDecor.height * 0.98f
        val targetIsFullWindow = blurTarget == dialogDecor ||
            (blurTarget.width >= dialogDecor.width * 0.98f &&
                blurTarget.height >= dialogDecor.height * 0.98f)
        val canUseNativeBlur = !fullWindow || targetIsFullWindow
        val crossWindowBlurEnabled = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            context.getSystemService(WindowManager::class.java)?.isCrossWindowBlurEnabled == true

        if (radius <= 0) {
            clearWindowBlur()
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            canUseNativeBlur && crossWindowBlurEnabled
        ) {
            // Android 的窗口模糊只作用于这个弹窗窗口的边界，不再触碰宿主 Activity。
            kotlin.runCatching {
                dialogWindow.setBackgroundBlurRadius(radius)
                val attributes = dialogWindow.attributes
                dialogWindow.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                attributes.setBlurBehindRadius(radius)
                dialogWindow.attributes = attributes
            }
        } else {
            // 设备关闭跨窗口模糊时，只复制弹窗目标区域并做局部位图模糊。
            // 绝不能再给 activityDecor 设置 RenderEffect，否则会把整页一起模糊。
            clearWindowBlur()
            activityWindow?.let { hostWindow ->
                dialogDecor.applyDialogBitmapBlur(hostWindow, blurTarget, radius)
            }
        }
        dialogDecor.applyDialogSurfaceChildren()
    }
}

private fun View.findDialogBlurTarget(): View {
    findViewById<View>(R.id.vw_bg)?.let { return it }
    findViewById<View>(MaterialR.id.design_bottom_sheet)?.let { return it }
    val content = findViewById<ViewGroup>(android.R.id.content)
    if (content != null && content.childCount == 1) {
        return content.getChildAt(0)
    }
    return content ?: this
}

private fun View.applyDialogBitmapBlur(hostWindow: Window, target: View, radius: Int) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || radius <= 0) return
    if (!isAttachedToWindow || !target.isAttachedToWindow || width <= 0 || height <= 0) return
    val hostDecor = hostWindow.decorView
    if (hostDecor.width <= 0 || hostDecor.height <= 0) return

    val targetLocation = IntArray(2)
    val hostLocation = IntArray(2)
    target.getLocationOnScreen(targetLocation)
    hostDecor.getLocationOnScreen(hostLocation)
    val left = (targetLocation[0] - hostLocation[0]).coerceIn(0, hostDecor.width - 1)
    val top = (targetLocation[1] - hostLocation[1]).coerceIn(0, hostDecor.height - 1)
    val right = (left + target.width).coerceAtMost(hostDecor.width)
    val bottom = (top + target.height).coerceAtMost(hostDecor.height)
    if (right <= left || bottom <= top) return

    fun request(attempt: Int) {
        if (attempt > 2 || !isAttachedToWindow || !target.isAttachedToWindow) return
        val sourceRect = Rect(left, top, right, bottom)
        val sourceBitmap = Bitmap.createBitmap(
            sourceRect.width(),
            sourceRect.height(),
            Bitmap.Config.ARGB_8888
        )
        try {
            PixelCopy.request(
                hostWindow,
                sourceRect,
                sourceBitmap,
                { result ->
                    if (result == PixelCopy.SUCCESS) {
                        applyDialogBlurLayer(target, blurBitmap(sourceBitmap, radius))
                        sourceBitmap.recycle()
                    } else {
                        sourceBitmap.recycle()
                        postDelayed({ request(attempt + 1) }, 60L)
                    }
                },
                Handler(Looper.getMainLooper())
            )
        } catch (_: IllegalArgumentException) {
            sourceBitmap.recycle()
        }
    }
    request(0)
}

private fun applyDialogBlurLayer(target: View, blurredBitmap: Bitmap) {
    val originalBackground = target.background
    val blurredDrawable = BitmapDrawable(target.resources, blurredBitmap).apply {
        gravity = Gravity.FILL
        isFilterBitmap = true
    }
    val layers = if (originalBackground != null) {
        arrayOf<Drawable>(blurredDrawable, originalBackground)
    } else {
        arrayOf<Drawable>(blurredDrawable)
    }
    val appliedBackground = LayerDrawable(layers)
    target.background = appliedBackground
    target.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) = Unit

        override fun onViewDetachedFromWindow(v: View) {
            v.removeOnAttachStateChangeListener(this)
            if (v.background === appliedBackground) {
                v.background = originalBackground
            }
            if (!blurredBitmap.isRecycled) blurredBitmap.recycle()
        }
    })
}

private fun blurBitmap(source: Bitmap, radius: Int): Bitmap {
    val sample = 4
    val smallWidth = (source.width / sample).coerceAtLeast(1)
    val smallHeight = (source.height / sample).coerceAtLeast(1)
    val small = Bitmap.createScaledBitmap(source, smallWidth, smallHeight, true)
    val pixels = IntArray(smallWidth * smallHeight)
    val buffer = IntArray(pixels.size)
    small.getPixels(pixels, 0, smallWidth, 0, 0, smallWidth, smallHeight)
    val blurRadius = (radius / sample).coerceIn(1, 24)
    repeat(3) {
        blurHorizontal(pixels, buffer, smallWidth, smallHeight, blurRadius)
        blurVertical(buffer, pixels, smallWidth, smallHeight, blurRadius)
    }
    val blurredSmall = Bitmap.createBitmap(smallWidth, smallHeight, Bitmap.Config.ARGB_8888)
    blurredSmall.setPixels(pixels, 0, smallWidth, 0, 0, smallWidth, smallHeight)
    if (small !== source && !small.isRecycled) small.recycle()
    val result = Bitmap.createScaledBitmap(blurredSmall, source.width, source.height, true)
    if (result !== blurredSmall && !blurredSmall.isRecycled) blurredSmall.recycle()
    return result
}

private fun blurHorizontal(
    source: IntArray,
    target: IntArray,
    width: Int,
    height: Int,
    radius: Int
) {
    val window = radius * 2 + 1
    for (y in 0 until height) {
        var alpha = 0
        var red = 0
        var green = 0
        var blue = 0
        for (offset in -radius..radius) {
            val color = source[y * width + offset.coerceIn(0, width - 1)]
            alpha += Color.alpha(color)
            red += Color.red(color)
            green += Color.green(color)
            blue += Color.blue(color)
        }
        for (x in 0 until width) {
            target[y * width + x] = Color.argb(
                alpha / window,
                red / window,
                green / window,
                blue / window
            )
            val removeColor = source[y * width + (x - radius).coerceIn(0, width - 1)]
            val addColor = source[y * width + (x + radius + 1).coerceIn(0, width - 1)]
            alpha += Color.alpha(addColor) - Color.alpha(removeColor)
            red += Color.red(addColor) - Color.red(removeColor)
            green += Color.green(addColor) - Color.green(removeColor)
            blue += Color.blue(addColor) - Color.blue(removeColor)
        }
    }
}

private fun blurVertical(
    source: IntArray,
    target: IntArray,
    width: Int,
    height: Int,
    radius: Int
) {
    val window = radius * 2 + 1
    for (x in 0 until width) {
        var alpha = 0
        var red = 0
        var green = 0
        var blue = 0
        for (offset in -radius..radius) {
            val color = source[offset.coerceIn(0, height - 1) * width + x]
            alpha += Color.alpha(color)
            red += Color.red(color)
            green += Color.green(color)
            blue += Color.blue(color)
        }
        for (y in 0 until height) {
            target[y * width + x] = Color.argb(
                alpha / window,
                red / window,
                green / window,
                blue / window
            )
            val removeColor = source[(y - radius).coerceIn(0, height - 1) * width + x]
            val addColor = source[(y + radius + 1).coerceIn(0, height - 1) * width + x]
            alpha += Color.alpha(addColor) - Color.alpha(removeColor)
            red += Color.red(addColor) - Color.red(removeColor)
            green += Color.green(addColor) - Color.green(removeColor)
            blue += Color.blue(addColor) - Color.blue(removeColor)
        }
    }
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
