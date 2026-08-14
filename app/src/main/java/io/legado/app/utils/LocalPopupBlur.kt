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
import android.view.Window
import android.view.Menu
import android.widget.PopupWindow
import androidx.appcompat.widget.PopupMenu
import io.legado.app.lib.theme.UiCorner
import java.lang.ref.Reference
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.IdentityHashMap
import java.util.Locale
import java.util.WeakHashMap
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

private const val POPUP_BLUR_MAX_ATTEMPTS = 64
private const val POPUP_BLUR_RETRY_DELAY_MS = 16L
private const val POPUP_BACKDROP_SAMPLE = 4
private val mainHandler = Handler(Looper.getMainLooper())

/**
 * 只给浮层自身的矩形区域铺设宿主页面的模糊底图。
 *
 * 独立窗口/PopupWindow 可以直接从宿主窗口取图；同窗口菜单在取图时只暂时隐藏
 * 要取底图的背景层，避免把菜单自己再次截进去，不能隐藏整个菜单根节点。
 */
object LocalPopupBlur {

    private data class PopupBackdropSnapshot(
        val generation: Int,
        val sourceWidth: Int,
        val sourceHeight: Int,
        val bitmap: Bitmap
    )

    private val originalBackgrounds = WeakHashMap<View, Drawable?>()
    private val blurredBitmaps = WeakHashMap<View, Bitmap>()
    private val generations = WeakHashMap<View, Int>()
    private val attachListeners = WeakHashMap<View, View.OnAttachStateChangeListener>()
    private val temporaryAlphas = WeakHashMap<View, Float>()
    private val popupBackdropSnapshots = WeakHashMap<Window, PopupBackdropSnapshot>()
    private val popupBackdropGenerations = WeakHashMap<Window, Int>()
    private val popupBackdropPending = WeakHashMap<Window, Int>()

    /**
     * 在菜单真正创建前，从当前宿主页面预先生成一张低分辨率整页模糊图。
     * 后续弹窗只从这张图裁剪，不再对弹窗外壳和内部列表分别异步取图。
     */
    fun preparePopupBlur(hostWindow: Window): Int {
        val generation = (popupBackdropGenerations[hostWindow] ?: 0) + 1
        popupBackdropGenerations[hostWindow] = generation
        popupBackdropPending[hostWindow] = generation
        capturePopupBackdrop(hostWindow, generation, 0)
        return generation
    }

    private fun capturePopupBackdrop(hostWindow: Window, generation: Int, attempt: Int) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            UiCorner.dialogBlurRadius() <= 0 ||
            popupBackdropGenerations[hostWindow] != generation
        ) {
            if (popupBackdropPending[hostWindow] == generation) {
                popupBackdropPending.remove(hostWindow)
            }
            return
        }
        val decor = hostWindow.decorView
        if (!decor.isAttachedToWindow || decor.width <= 0 || decor.height <= 0) {
            retryPopupBackdrop(hostWindow, generation, attempt)
            return
        }
        val bitmap = try {
            Bitmap.createBitmap(
                (decor.width / POPUP_BACKDROP_SAMPLE).coerceAtLeast(1),
                (decor.height / POPUP_BACKDROP_SAMPLE).coerceAtLeast(1),
                Bitmap.Config.ARGB_8888
            )
        } catch (_: Throwable) {
            if (popupBackdropPending[hostWindow] == generation) {
                popupBackdropPending.remove(hostWindow)
            }
            return
        }
        val source = Rect(0, 0, decor.width, decor.height)
        try {
            PixelCopy.request(hostWindow, source, bitmap, { result ->
                if (popupBackdropGenerations[hostWindow] != generation) {
                    bitmap.recycle()
                    return@request
                }
                if (result == PixelCopy.SUCCESS) {
                    val blurred = runCatching {
                        blurBitmapAtResolution(bitmap, UiCorner.dialogBlurRadius())
                    }.getOrNull()
                    bitmap.recycle()
                    if (blurred != null) {
                        popupBackdropSnapshots.remove(hostWindow)?.bitmap?.let {
                            if (!it.isRecycled) it.recycle()
                        }
                        popupBackdropSnapshots[hostWindow] = PopupBackdropSnapshot(
                            generation = generation,
                            sourceWidth = decor.width,
                            sourceHeight = decor.height,
                            bitmap = blurred
                        )
                        popupBackdropPending.remove(hostWindow)
                    } else {
                        retryPopupBackdrop(hostWindow, generation, attempt)
                    }
                } else {
                    bitmap.recycle()
                    retryPopupBackdrop(hostWindow, generation, attempt)
                }
            }, mainHandler)
        } catch (_: Throwable) {
            bitmap.recycle()
            retryPopupBackdrop(hostWindow, generation, attempt)
        }
    }

    private fun retryPopupBackdrop(hostWindow: Window, generation: Int, attempt: Int) {
        if (popupBackdropGenerations[hostWindow] != generation) return
        if (attempt >= 3) {
            if (popupBackdropPending[hostWindow] == generation) {
                popupBackdropPending.remove(hostWindow)
            }
            return
        }
        mainHandler.postDelayed(
            { capturePopupBackdrop(hostWindow, generation, attempt + 1) },
            POPUP_BLUR_RETRY_DELAY_MS
        )
    }

    /**
     * 用已准备好的整页模糊图给当前唯一的 PopupWindow 背景外壳安装裁剪结果。
     * 返回 false 表示本轮整页图还没准备好，调用方必须继续等待，不能改用内容列表。
     */
    fun installPreparedPopupBlur(
        hostWindow: Window,
        target: View,
        minimumGeneration: Int,
        popupSurfaceAlpha: Float
    ): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            UiCorner.dialogBlurRadius() <= 0 ||
            !target.isAttachedToWindow || target.width <= 0 || target.height <= 0
        ) return false
        val snapshot = popupBackdropSnapshots[hostWindow] ?: return false
        val currentGeneration = popupBackdropGenerations[hostWindow] ?: return false
        if (currentGeneration != minimumGeneration ||
            snapshot.generation != minimumGeneration ||
            popupBackdropPending[hostWindow] == minimumGeneration
        ) return false
        val hostDecor = hostWindow.decorView
        val sourceRect = sourceRect(hostDecor, target) ?: return false
        val blurred = cropBackdrop(snapshot, sourceRect, target.width, target.height) ?: return false
        val generation = nextGeneration(target)
        install(target, blurred, popupSurfaceAlpha)
        return generations[target] == generation && target.isAttachedToWindow
    }

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
            val independentOriginal = original.constantState
                ?.newDrawable(target.resources)
                ?.mutate()
                ?: original.mutate()
            popupSurfaceAlpha?.let { alpha ->
                independentOriginal.alpha =
                    (independentOriginal.alpha * alpha.coerceIn(0f, 1f)).roundToInt()
            }
            LayerDrawable(arrayOf(blurredDrawable, independentOriginal)).mutate()
        } else {
            LayerDrawable(
                arrayOf<Drawable>(
                    blurredDrawable,
                    ColorDrawable(Color.TRANSPARENT)
                )
            ).mutate()
        }
        target.background = appliedBackground
    }

    private fun blurBitmap(source: Bitmap, radius: Int): Bitmap {
        val sample = POPUP_BACKDROP_SAMPLE
        val smallWidth = (source.width / sample).coerceAtLeast(1)
        val smallHeight = (source.height / sample).coerceAtLeast(1)
        val small = Bitmap.createScaledBitmap(source, smallWidth, smallHeight, true)
        val blurredSmall = blurBitmapAtResolution(small, radius)
        if (small !== source && !small.isRecycled) small.recycle()
        val result = Bitmap.createScaledBitmap(blurredSmall, source.width, source.height, true)
        if (result !== blurredSmall && !blurredSmall.isRecycled) blurredSmall.recycle()
        return result
    }

    private fun blurBitmapAtResolution(source: Bitmap, radius: Int): Bitmap {
        val width = source.width.coerceAtLeast(1)
        val height = source.height.coerceAtLeast(1)
        val pixels = IntArray(width * height)
        val buffer = IntArray(pixels.size)
        source.getPixels(pixels, 0, width, 0, 0, width, height)
        val blurRadius = (radius / POPUP_BACKDROP_SAMPLE).coerceIn(1, 24)
        repeat(3) {
            blurHorizontal(pixels, buffer, width, height, blurRadius)
            blurVertical(buffer, pixels, width, height, blurRadius)
        }
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
            it.setPixels(pixels, 0, width, 0, 0, width, height)
        }
    }

    private fun cropBackdrop(
        snapshot: PopupBackdropSnapshot,
        sourceRect: Rect,
        targetWidth: Int,
        targetHeight: Int
    ): Bitmap? {
        val bitmap = snapshot.bitmap
        if (bitmap.isRecycled || snapshot.sourceWidth <= 0 || snapshot.sourceHeight <= 0) return null
        val left = floor(sourceRect.left.toDouble() * bitmap.width / snapshot.sourceWidth)
            .toInt().coerceIn(0, bitmap.width - 1)
        val top = floor(sourceRect.top.toDouble() * bitmap.height / snapshot.sourceHeight)
            .toInt().coerceIn(0, bitmap.height - 1)
        val right = ceil(sourceRect.right.toDouble() * bitmap.width / snapshot.sourceWidth)
            .toInt().coerceIn(left + 1, bitmap.width)
        val bottom = ceil(sourceRect.bottom.toDouble() * bitmap.height / snapshot.sourceHeight)
            .toInt().coerceIn(top + 1, bitmap.height)
        val cropped = runCatching {
            Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top)
        }.getOrNull() ?: return null
        val scaled = runCatching {
            Bitmap.createScaledBitmap(cropped, targetWidth, targetHeight, true)
        }.getOrNull()
        if (scaled == null) {
            if (!cropped.isRecycled) cropped.recycle()
            return null
        }
        if (scaled !== cropped && !cropped.isRecycled) cropped.recycle()
        return scaled
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

fun Menu.applyLocalPopupBlur(hostWindow: Window, preparedGeneration: Int? = null) {
    schedulePopupBlur(hostWindow, this, preparedGeneration)
}

fun PopupMenu.applyLocalPopupBlur(hostWindow: Window, preparedGeneration: Int? = null) {
    schedulePopupBlur(hostWindow, this, preparedGeneration)
}

private fun schedulePopupBlur(
    hostWindow: Window,
    source: Any,
    preparedGeneration: Int?
) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || UiCorner.dialogBlurRadius() <= 0) return
    val requiredGeneration = preparedGeneration ?: LocalPopupBlur.preparePopupBlur(hostWindow)
    var preparedSurface: View? = null
    var preparedSurfaceAlpha = 1f
    var lastX = Int.MIN_VALUE
    var lastY = Int.MIN_VALUE
    var lastWidth = 0
    var lastHeight = 0
    var stableFrames = 0
    var finished = false

    fun restoreUnblurred() {
        if (finished) return
        finished = true
        preparedSurface?.takeIf { it.isAttachedToWindow }?.let { target ->
            target.alpha = preparedSurfaceAlpha
            target.invalidate()
        }
    }

    lateinit var attempt: (Int) -> Unit

    fun retry(count: Int) {
        if (count < POPUP_BLUR_MAX_ATTEMPTS) {
            hostWindow.decorView.postDelayed(
                { attempt(count + 1) },
                POPUP_BLUR_RETRY_DELAY_MS
            )
        } else {
            restoreUnblurred()
        }
    }

    attempt = attempt@{ count ->
        if (finished) return@attempt
        val candidate = findPopupTarget(source)
        if (candidate == null || !isPopupSurface(candidate, hostWindow.decorView)) {
            retry(count)
            return@attempt
        }
        val location = IntArray(2)
        candidate.getLocationOnScreen(location)
        val changed = candidate !== preparedSurface ||
            location[0] != lastX || location[1] != lastY ||
            candidate.width != lastWidth || candidate.height != lastHeight
        if (changed) {
            preparedSurface?.takeIf { it.isAttachedToWindow }?.alpha = preparedSurfaceAlpha
            preparedSurface = candidate
            preparedSurfaceAlpha = candidate.alpha.takeIf { it > 0f } ?: 1f
            candidate.alpha = 0f
            candidate.invalidate()
            lastX = location[0]
            lastY = location[1]
            lastWidth = candidate.width
            lastHeight = candidate.height
            stableFrames = 1
        } else {
            stableFrames += 1
        }
        if (stableFrames >= 2 && LocalPopupBlur.installPreparedPopupBlur(
                hostWindow = hostWindow,
                target = candidate,
                minimumGeneration = requiredGeneration,
                popupSurfaceAlpha = UiCorner.dialogSurfaceAlpha()
            )
        ) {
            finished = true
            candidate.alpha = preparedSurfaceAlpha
            candidate.invalidate()
            return@attempt
        }
        retry(count)
    }

    // 先找已经 attach 的新外壳；show() 前不会把 contentView 当成替代目标。
    attempt(0)
}

private fun findPopupTarget(source: Any): View? {
    val visited = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
    var latestSurface: View? = null

    fun visit(value: Any?, depth: Int) {
        if (value == null || depth > 7 || !visited.add(value)) return
        if (value is PopupWindow) {
            // 只认本轮 PopupWindow 已经创建并 attach 的 mBackgroundView。
            // contentView、ListView、mDecorView 都不能作为弹窗表面替代目标。
            val background = runCatching {
                var currentClass: Class<*>? = value.javaClass
                var result: View? = null
                while (currentClass != null && currentClass != Any::class.java) {
                    currentClass.declaredFields.firstOrNull { it.name == "mBackgroundView" }
                        ?.let { field ->
                            field.isAccessible = true
                            result = field.get(value) as? View
                        }
                    currentClass = currentClass.superclass
                }
                result
            }.getOrNull()
            if (background?.isAttachedToWindow == true && background.width > 0 && background.height > 0) {
                latestSurface = background
            }
            return
        }
        if (value is Reference<*>) {
            visit(value.get(), depth + 1)
            return
        }
        if (value is Iterable<*>) {
            value.forEach { visit(it, depth + 1) }
            return
        }
        if (value is View) return
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
                    visit(field.get(value), depth + 1)
                }
            }
            currentClass = currentClass.superclass
        }
    }

    visit(source, 0)
    return latestSurface
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
