package io.legado.app.utils

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.Menu
import android.widget.ListView
import android.widget.PopupWindow
import androidx.appcompat.widget.PopupMenu
import io.legado.app.lib.theme.UiCorner
import java.lang.ref.Reference
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.IdentityHashMap
import java.util.Locale
import java.util.WeakHashMap
import kotlin.math.roundToInt

private const val POPUP_BLUR_MAX_ATTEMPTS = 12
private const val POPUP_BLUR_RETRY_DELAY_MS = 50L
private val mainHandler = Handler(Looper.getMainLooper())

/**
 * 只给浮层自身的矩形区域铺设宿主页面的模糊底图。
 *
 * 独立窗口/PopupWindow 可以直接从宿主窗口取图；同窗口菜单在取图时只暂时隐藏
 * 要取底图的背景层，避免把菜单自己再次截进去，不能隐藏整个菜单根节点。
 */
object LocalPopupBlur {

    private val originalBackgrounds = WeakHashMap<View, Drawable?>()
    private val originalAlphas = WeakHashMap<View, Int>()
    private val blurredBitmaps = WeakHashMap<View, Bitmap>()
    private val generations = WeakHashMap<View, Int>()
    private val attachListeners = WeakHashMap<View, View.OnAttachStateChangeListener>()
    private val temporaryAlphas = WeakHashMap<View, Float>()

    fun apply(
        hostWindow: Window,
        targets: List<View>,
        captureOwner: View? = null,
        radius: Int = UiCorner.dialogBlurRadius(),
        popupSurfaceAlpha: Float? = null,
        onReady: (() -> Unit)? = null
    ) {
        val validTargets = targets.distinct().filter {
            it.isAttachedToWindow && it.width > 0 && it.height > 0
        }
        if (validTargets.isEmpty()) {
            onReady?.invoke()
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || radius <= 0) {
            clear(validTargets)
            onReady?.invoke()
            return
        }

        val hostDecor = hostWindow.decorView
        if (!hostDecor.isAttachedToWindow || hostDecor.width <= 0 || hostDecor.height <= 0) {
            onReady?.invoke()
            return
        }

        val targetRequests = validTargets.map { target ->
            target to nextGeneration(target)
        }
        val hiddenTargets = captureOwner?.takeIf { it.isAttachedToWindow }?.let {
            // 同窗口菜单不能把整个菜单根节点隐藏一帧：那会让主菜单先消失再回来，
            // 在阅读页上表现为“抽动”。只暂时隐藏需要取底图的目标块，其他按钮和布局保持可见。
            validTargets.map { target ->
                val alpha = temporaryAlphas.getOrPut(target) { target.alpha }
                target.alpha = 0f
                target.invalidate()
                target to alpha
            }
        }

        fun restoreTargets() {
            hiddenTargets?.forEach { (view, alpha) ->
                if (view.isAttachedToWindow) {
                    temporaryAlphas.remove(view)
                    view.alpha = alpha
                    view.invalidate()
                }
            }
        }

        val requestCapture = {
            var remaining = targetRequests.size
            fun finishOne() {
                remaining -= 1
                if (remaining <= 0) {
                    restoreTargets()
                    onReady?.invoke()
                }
            }
            targetRequests.forEach { (target, generation) ->
                requestTargetBitmap(
                    hostWindow = hostWindow,
                    hostDecor = hostDecor,
                    target = target,
                    radius = radius,
                    generation = generation,
                    attempt = 0,
                    popupSurfaceAlpha = popupSurfaceAlpha,
                    onFinished = ::finishOne
                )
            }
        }

        if (hiddenTargets == null) {
            requestCapture()
        } else {
            // 等一帧让目标块的 alpha=0 真正参与宿主窗口绘制。
            hiddenTargets.first().first.post { hostDecor.post(requestCapture) }
        }
    }

    fun clear(targets: List<View>) {
        targets.distinct().forEach(::clear)
    }

    fun clear(target: View) {
        generations[target] = (generations[target] ?: 0) + 1
        temporaryAlphas.remove(target)?.let { target.alpha = it }
        if (originalBackgrounds.containsKey(target)) {
            originalAlphas.remove(target)?.let { originalBackgrounds[target]?.alpha = it }
            target.background = originalBackgrounds.remove(target)
        }
        blurredBitmaps.remove(target)?.let {
            if (!it.isRecycled) it.recycle()
        }
        attachListeners.remove(target)?.let(target::removeOnAttachStateChangeListener)
    }

    private fun nextGeneration(target: View): Int {
        val generation = (generations[target] ?: 0) + 1
        generations[target] = generation
        if (!originalBackgrounds.containsKey(target)) {
            originalBackgrounds[target] = target.background
            attachListeners[target] = object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) = Unit

                override fun onViewDetachedFromWindow(v: View) {
                    clear(v)
                }
            }.also(target::addOnAttachStateChangeListener)
        }
        return generation
    }

    private fun requestTargetBitmap(
        hostWindow: Window,
        hostDecor: View,
        target: View,
        radius: Int,
        generation: Int,
        attempt: Int,
        popupSurfaceAlpha: Float?,
        onFinished: () -> Unit
    ) {
        if (!target.isAttachedToWindow || !hostDecor.isAttachedToWindow) {
            onFinished()
            return
        }
        val sourceRect = sourceRect(hostDecor, target) ?: run {
            onFinished()
            return
        }
        val sourceBitmap = try {
            Bitmap.createBitmap(sourceRect.width(), sourceRect.height(), Bitmap.Config.ARGB_8888)
        } catch (_: Throwable) {
            onFinished()
            return
        }
        try {
            val onCopyFinished: (Int) -> Unit = { result ->
                if (result == PixelCopy.SUCCESS) {
                    val blurred = blurBitmap(sourceBitmap, radius)
                    sourceBitmap.recycle()
                    if (generations[target] == generation && target.isAttachedToWindow) {
                        install(target, blurred, popupSurfaceAlpha)
                    } else if (!blurred.isRecycled) {
                        blurred.recycle()
                    }
                    onFinished()
                } else {
                    sourceBitmap.recycle()
                    if (attempt < 2) {
                        hostDecor.postDelayed({
                            requestTargetBitmap(
                                hostWindow,
                                hostDecor,
                                target,
                                radius,
                                generation,
                                attempt + 1,
                                popupSurfaceAlpha,
                                onFinished
                            )
                        }, 60L)
                    } else {
                        onFinished()
                    }
                }
            }
            // 本项目当前 compileSdk 的 android.view stubs 不暴露 API 34 的
            // PixelCopy.Request 构建器；Window 重载同样走系统硬件复制，且可兼容
            // 当前正式版的 minSdk，不能为了新 API 让整个 appC 无法编译。
            PixelCopy.request(
                hostWindow,
                sourceRect,
                sourceBitmap,
                onCopyFinished,
                mainHandler
            )
        } catch (_: Throwable) {
            sourceBitmap.recycle()
            onFinished()
        }
    }

    private fun sourceRect(hostDecor: View, target: View): Rect? {
        val targetLocation = IntArray(2)
        val hostLocation = IntArray(2)
        // PixelCopy 的 sourceRect 是“源 Window 坐标”，官方示例使用
        // getLocationInWindow()。同窗口直接相减；Dialog/PopupWindow 跨窗口时，
        // 先把目标的屏幕坐标换算到宿主 decor，再加回宿主 decor 在源 Window 内的偏移。
        // 这样不会把屏幕坐标误当成源 Surface 坐标，也不会因状态栏/inset 偏移一整块。
        val sameWindow = target.rootView === hostDecor.rootView
        val rawLeft: Int
        val rawTop: Int
        if (sameWindow) {
            target.getLocationInWindow(targetLocation)
            hostDecor.getLocationInWindow(hostLocation)
            rawLeft = targetLocation[0] - hostLocation[0]
            rawTop = targetLocation[1] - hostLocation[1]
        } else {
            target.getLocationOnScreen(targetLocation)
            hostDecor.getLocationOnScreen(hostLocation)
            val hostWindowLocation = IntArray(2)
            hostDecor.getLocationInWindow(hostWindowLocation)
            rawLeft = targetLocation[0] - hostLocation[0] + hostWindowLocation[0]
            rawTop = targetLocation[1] - hostLocation[1] + hostWindowLocation[1]
        }
        val rawRight = rawLeft + target.width
        val rawBottom = rawTop + target.height
        val left = rawLeft.coerceAtLeast(0).coerceAtMost(hostDecor.width)
        val top = rawTop.coerceAtLeast(0).coerceAtMost(hostDecor.height)
        val right = rawRight.coerceAtLeast(0).coerceAtMost(hostDecor.width)
        val bottom = rawBottom.coerceAtLeast(0).coerceAtMost(hostDecor.height)
        return if (right > left && bottom > top) Rect(left, top, right, bottom) else null
    }

    private fun install(target: View, blurredBitmap: Bitmap, popupSurfaceAlpha: Float?) {
        val original = originalBackgrounds[target]
        val old = blurredBitmaps.put(target, blurredBitmap)
        old?.let {
            if (!it.isRecycled) it.recycle()
        }
        val blurredDrawable = BitmapDrawable(target.resources, blurredBitmap).apply {
            gravity = android.view.Gravity.FILL
            isFilterBitmap = true
        }
        val appliedBackground = if (original != null) {
            LayerDrawable(arrayOf(blurredDrawable, original))
        } else {
            LayerDrawable(
                arrayOf<Drawable>(
                    blurredDrawable,
                    ColorDrawable(Color.TRANSPARENT)
                )
            )
        }
        popupSurfaceAlpha?.let { alpha ->
            if (original != null) {
                val baseAlpha = originalAlphas.getOrPut(target) { original.alpha }
                original.alpha = (baseAlpha * alpha.coerceIn(0f, 1f)).roundToInt()
            }
        }
        target.background = appliedBackground
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
}

fun View.applyLocalPopupBlur(
    hostWindow: Window,
    captureOwner: View? = null,
    radius: Int = UiCorner.dialogBlurRadius()
) = LocalPopupBlur.apply(hostWindow, listOf(this), captureOwner, radius)

fun Menu.applyLocalPopupBlur(hostWindow: Window) {
    schedulePopupBlur(hostWindow, this)
}

fun PopupMenu.applyLocalPopupBlur(hostWindow: Window) {
    schedulePopupBlur(hostWindow, this)
}

private fun schedulePopupBlur(hostWindow: Window, source: Any) {
    var preparedSurface: View? = null
    var preparedTargets: List<View> = emptyList()
    val originalAlphas = IdentityHashMap<View, Float>()
    var finished = false

    fun restoreUnblurred() {
        if (finished) return
        finished = true
        preparedTargets.forEach { target ->
            if (target.isAttachedToWindow) {
                originalAlphas[target]?.let { target.alpha = it }
            }
        }
    }

    fun attempt(count: Int) {
        if (finished) return
        val candidate = findPopupTarget(source)
        if (preparedSurface == null && candidate != null) {
            // PopupWindow 的 contentView 在 show() 前通常已经存在，但外壳还没有
            // attach/layout。此时先把它设为不可见，避免系统先画出一帧未模糊的菜单。
            preparedSurface = candidate
            preparedTargets = popupSurfaceTargets(candidate)
            preparedTargets.forEach { target ->
                originalAlphas[target] = target.alpha.takeIf { it > 0f } ?: 1f
                target.alpha = 0f
            }
        }
        val targets = preparedTargets.filter { isPopupSurface(it, hostWindow.decorView) }
        if (targets.isNotEmpty()) {
            LocalPopupBlur.apply(
                hostWindow,
                targets,
                popupSurfaceAlpha = UiCorner.dialogSurfaceAlpha(),
                onReady = {
                    if (finished) return@apply
                    finished = true
                    targets.forEach { target ->
                        if (target.isAttachedToWindow) {
                            originalAlphas[target]?.let { target.alpha = it }
                        }
                    }
                }
            )
        } else if (count < POPUP_BLUR_MAX_ATTEMPTS) {
            hostWindow.decorView.postDelayed(
                { attempt(count + 1) },
                POPUP_BLUR_RETRY_DELAY_MS
            )
        } else {
            // 目标窗口异常时不能一直保持透明，否则会留下不可见菜单。
            restoreUnblurred()
        }
    }
    // show() 返回后立即尝试一次，确保在下一个绘制帧前进入预备态。
    attempt(0)
}

/**
 * PopupWindow 的背景外壳和菜单列表可能分别绘制背景。
 * 只处理外壳以及其中的 ListView，避免列表自己的背景把外壳模糊层盖掉；
 * 不向外扩展到宿主页面，也不处理菜单文字和按钮本身。
 */
private fun popupSurfaceTargets(surface: View): List<View> {
    val targets = ArrayList<View>(2)
    targets += surface

    fun collect(view: View, depth: Int) {
        if (depth > 8 || view !is ViewGroup) return
        for (index in 0 until view.childCount) {
            val child = view.getChildAt(index)
            if (child is ListView) {
                targets += child
            } else {
                collect(child, depth + 1)
            }
        }
    }

    collect(surface, 0)
    return targets.distinct()
}

private fun findPopupTarget(source: Any): View? {
    val visited = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
    var listViewFallback: View? = null

    fun visit(value: Any?, depth: Int): View? {
        if (value == null || depth > 7 || !visited.add(value)) return null
        if (value is PopupWindow) {
            // PopupWindow 的 mDecorView 只负责事件分发和承载子树，真正绘制
            // 背景、圆角和内边距的是 mBackgroundView。把 mDecorView 当目标时，
            // 模糊层会被它里面不透明的 mBackgroundView 完全盖住，结果就是
            // “弹窗范围对了，但弹窗中间没有模糊”。先取背景外壳，再兼容
            // AppCompat/厂商实现可能存在的 mPopupView，最后才用 decor 兜底。
            val popupSurface = listOf("mBackgroundView", "mPopupView", "mContentView", "mDecorView")
                .asSequence()
                .mapNotNull { fieldName ->
                    runCatching {
                        var currentClass: Class<*>? = value.javaClass
                        while (currentClass != null && currentClass != Any::class.java) {
                            currentClass.declaredFields.firstOrNull { it.name == fieldName }?.let { field ->
                                field.isAccessible = true
                                return@runCatching field.get(value) as? View
                            }
                            currentClass = currentClass.superclass
                        }
                        null
                    }.getOrNull()
                }
                .firstOrNull()
            return popupSurface ?: value.contentView
        }
        if (value is Reference<*>) return visit(value.get(), depth + 1)
        if (value is Iterable<*>) {
            for (item in value) {
                val target = visit(item, depth + 1)
                if (target != null) return target
            }
            return null
        }
        if (value is View) {
            // 不能把任意 View 当成弹窗目标，否则找不到 PopupWindow 时会把
            // 右上角的锚点按钮本身套上模糊背景。只允许菜单列表作为保底目标。
            if (value is ListView) {
                listViewFallback = listViewFallback ?: value
            }
            return null
        }
        runCatching {
            value.javaClass.methods.firstOrNull {
                it.name == "getListView" && it.parameterTypes.isEmpty()
            }?.let { method ->
                (method.invoke(value) as? View)?.let { listViewFallback = listViewFallback ?: it }
            }
        }
        var currentClass: Class<*>? = value.javaClass
        while (currentClass != null && currentClass != Any::class.java) {
            currentClass.declaredFields.forEach { field ->
                if (Modifier.isStatic(field.modifiers)) return@forEach
                val name = field.name.lowercase(Locale.ROOT)
                if (!name.contains("popup") &&
                    !name.contains("overflow") &&
                    !name.contains("presenter") &&
                    !name.contains("submenu") &&
                    !name.contains("anchor")
                ) return@forEach
                runCatching {
                    field.isAccessible = true
                    visit(field.get(value), depth + 1)?.let { return it }
                }
            }
            currentClass = currentClass.superclass
        }
        return null
    }

    return visit(source, 0) ?: listViewFallback
}

private fun isPopupSurface(target: View, hostDecor: View): Boolean {
    if (!target.isAttachedToWindow || target.width <= 0 || target.height <= 0) return false
    // 原生菜单内容不应是宿主整页；找不到真实 PopupWindow 内容时直接放弃，
    // 不把宿主根布局或锚点按钮误套成模糊层。
    return !(target.width >= hostDecor.width * 0.98f &&
        target.height >= hostDecor.height * 0.98f)
}

fun Context.findHostWindow(): Window? {
    var current = this
    while (current is ContextWrapper) {
        if (current is Activity) return current.window
        current = current.baseContext
    }
    return null
}
