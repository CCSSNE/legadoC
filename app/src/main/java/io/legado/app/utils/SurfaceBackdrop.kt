package io.legado.app.utils

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.TextView
import io.legado.app.R
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.theme.surface.SurfaceDrawable
import io.legado.app.lib.theme.surface.SurfaceStyle
import java.lang.ref.WeakReference
import java.util.WeakHashMap

private const val SURFACE_STABLE_FRAME_DELAY_MS = 16L
private const val SURFACE_STABLE_FRAME_LIMIT = 24
private const val SURFACE_PIXEL_COPY_RETRIES = 2
private const val SURFACE_PIXEL_COPY_TIMEOUT_MS = 800L
private const val SURFACE_BLUR_SAMPLE = 4

/**
 * 模糊背景离屏采集的渲染状态：开启"模糊背景不含文字"时，采集窗口内容期间置位，
 * 阅读页等自绘文字路径据此跳过文字。只在同一主线程同步块内生效，真实帧不受影响。
 */
object BackdropRenderState {
    var textSuppressed: Boolean = false
        private set

    fun <T> withTextSuppressed(block: () -> T): T {
        textSuppressed = true
        try {
            return block()
        } finally {
            textSuppressed = false
        }
    }
}

/** Resolves the host Activity window without inspecting popup internals. */
fun Context.findHostWindow(): Window? {
    var current: Context = this
    while (current is ContextWrapper) {
        if (current is Activity) return current.window
        current = current.baseContext
    }
    return null
}

/**
 * 统一的玻璃表面生命周期。
 *
 * 调用方必须明确提供真正承载表面的 View；这里不遍历布局、不猜最大子节点，也不反射
 * PopupWindow 私有字段。窗口适配器只负责在显示前隐藏自己的窗口，并在 [onReady] 后显示。
 */
object SurfaceBackdrop {

    private data class State(
        val originalBackground: Drawable?,
        var generation: Int = 0,
        var bitmap: Bitmap? = null,
        var style: SurfaceStyle? = null,
        var attachListener: View.OnAttachStateChangeListener? = null
    )

    private val states = WeakHashMap<View, State>()
    private val mainHandler = Handler(Looper.getMainLooper())
    // 独立窗口表面根视图登记（弱引用）：纸模糊离屏采集时，把下层弹窗/弹出菜单的
    // 窗口按登记（显示）顺序叠画到宿主内容之上，实现"弹窗叠弹窗时下层弹窗本体参与模糊"。
    private val overlayRoots = ArrayList<WeakReference<View>>()

    fun installStatic(target: View, style: SurfaceStyle) {
        val state = stateFor(target)
        state.generation += 1
        state.style = style
        installResult(target, state, style, null)
    }

    /**
     * Updates geometry/tint without invalidating an in-flight capture.
     *
     * Dialog fragments commonly refresh their palette from onResume(), after their
     * base class has already started PixelCopy in onStart(). A style-only update is
     * not a new visual generation: the pending bitmap still belongs to this exact
     * target, and must be installed with the newest style when it arrives.
     */
    fun updateStyle(target: View, style: SurfaceStyle) {
        val state = stateFor(target)
        state.style = style
        installResult(target, state, style, state.bitmap)
    }

    fun apply(
        hostWindow: Window,
        target: View,
        style: SurfaceStyle,
        clearSameWindowSurfaceBeforeCapture: Boolean = false,
        onReady: () -> Unit = {}
    ) {
        val state = stateFor(target)
        val generation = state.generation + 1
        state.generation = generation
        state.style = style
        var completed = false

        fun finish(bitmap: Bitmap?) {
            if (completed) {
                bitmap?.recycleSafely()
                return
            }
            completed = true
            if (state.generation == generation && target.isAttachedToWindow) {
                // updateStyle() is allowed while PixelCopy is pending. It changes
                // presentation only; capture ownership stays with this generation.
                installResult(target, state, state.style ?: style, bitmap)
            } else {
                bitmap?.recycleSafely()
            }
            onReady()
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || style.blurRadiusPx <= 0) {
            finish(null)
            return
        }
        val hostDecor = hostWindow.decorView
        val sameWindow = target.rootView === hostDecor.rootView
        if (sameWindow && clearSameWindowSurfaceBeforeCapture) {
            val transparentStyle = style.copy(
                tintColor = Color.TRANSPARENT,
                strokeColor = Color.TRANSPARENT
            )
            target.background = SurfaceDrawable(null, transparentStyle)
            target.invalidate()
        }

        awaitStableBounds(
            target = target,
            hostDecor = hostDecor,
            generationValid = { state.generation == generation },
            onStable = {
                requestBackdrop(
                    hostWindow = hostWindow,
                    hostDecor = hostDecor,
                    target = target,
                    radius = style.blurRadiusPx,
                    generationValid = { state.generation == generation },
                    attempt = 0,
                    onFinished = ::finish
                )
            },
            onFailure = { finish(null) }
        )
    }

    /** Re-captures a surface while keeping the style installed by its owner. */
    fun refresh(
        hostWindow: Window,
        target: View,
        clearSameWindowSurfaceBeforeCapture: Boolean = false,
        onReady: () -> Unit = {}
    ) {
        val style = states[target]?.style
        if (style == null) {
            onReady()
            return
        }
        apply(
            hostWindow = hostWindow,
            target = target,
            style = style,
            clearSameWindowSurfaceBeforeCapture = clearSameWindowSurfaceBeforeCapture,
            onReady = onReady
        )
    }

    /** Completes only after every explicitly named surface has finished preparing. */
    fun refresh(
        hostWindow: Window,
        targets: Iterable<View>,
        clearSameWindowSurfaceBeforeCapture: Boolean = false,
        onReady: () -> Unit = {}
    ) {
        val pendingTargets = targets.filter { states[it]?.style != null }
        if (pendingTargets.isEmpty()) {
            onReady()
            return
        }
        var remaining = pendingTargets.size
        pendingTargets.forEach { target ->
            refresh(
                hostWindow = hostWindow,
                target = target,
                clearSameWindowSurfaceBeforeCapture = clearSameWindowSurfaceBeforeCapture
            ) {
                remaining -= 1
                if (remaining == 0) onReady()
            }
        }
    }

    fun cancel(target: View, keepStaticStyle: Boolean = true) {
        val state = states[target] ?: return
        state.generation += 1
        if (keepStaticStyle) {
            state.style?.let { installResult(target, state, it, null) }
        } else {
            clear(target)
        }
    }

    fun cancel(targets: Iterable<View>, keepStaticStyle: Boolean = true) {
        targets.forEach { cancel(it, keepStaticStyle) }
    }

    fun clear(target: View) {
        val state = states.remove(target) ?: return
        state.generation += 1
        target.background = state.originalBackground
        target.clipToOutline = false
        state.bitmap?.recycleSafely()
        state.attachListener?.let(target::removeOnAttachStateChangeListener)
    }

    fun clear(targets: Iterable<View>) {
        targets.forEach(::clear)
    }

    private fun stateFor(target: View): State {
        registerOverlayRoot(target.rootView)
        return states[target] ?: State(target.background).also { state ->
            state.attachListener = object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) = Unit

                override fun onViewDetachedFromWindow(v: View) {
                    clear(v)
                }
            }.also(target::addOnAttachStateChangeListener)
            states[target] = state
        }
    }

    private fun installResult(
        target: View,
        state: State,
        style: SurfaceStyle,
        bitmap: Bitmap?
    ) {
        val oldBitmap = state.bitmap
        state.bitmap = bitmap
        state.style = style
        target.background = SurfaceDrawable(bitmap, style)
        target.clipToOutline = style.cornerRadiusPx > 0f
        target.invalidateOutline()
        target.invalidate()
        if (oldBitmap !== bitmap) oldBitmap?.recycleSafely()
    }

    private fun awaitStableBounds(
        target: View,
        hostDecor: View,
        generationValid: () -> Boolean,
        onStable: () -> Unit,
        onFailure: () -> Unit
    ) {
        var previousBounds: Rect? = null
        var stableFrames = 0

        fun inspect(attempt: Int) {
            if (!generationValid()) {
                onFailure()
                return
            }
            // AlertDialog.Builder.show() exposes parentPanel before its first
            // WindowManager attach/layout pass. A direct failure here made every
            // ordinary dialog silently fall back to tint-only. Wait for both
            // source and target windows, then require stable geometry below.
            if (!target.isAttachedToWindow || !hostDecor.isAttachedToWindow) {
                if (attempt >= SURFACE_STABLE_FRAME_LIMIT) onFailure()
                else mainHandler.postDelayed(
                    { inspect(attempt + 1) },
                    SURFACE_STABLE_FRAME_DELAY_MS
                )
                return
            }
            val bounds = target.screenBounds()
            if (bounds == null) {
                if (attempt >= SURFACE_STABLE_FRAME_LIMIT) onFailure()
                else target.postDelayed({ inspect(attempt + 1) }, SURFACE_STABLE_FRAME_DELAY_MS)
                return
            }
            if (bounds == previousBounds) stableFrames += 1 else stableFrames = 1
            previousBounds = bounds
            if (stableFrames >= 2) {
                onStable()
            } else if (attempt >= SURFACE_STABLE_FRAME_LIMIT) {
                onFailure()
            } else {
                target.postDelayed({ inspect(attempt + 1) }, SURFACE_STABLE_FRAME_DELAY_MS)
            }
        }

        mainHandler.post { inspect(0) }
    }

    private fun requestBackdrop(
        hostWindow: Window,
        hostDecor: View,
        target: View,
        radius: Int,
        generationValid: () -> Boolean,
        attempt: Int,
        onFinished: (Bitmap?) -> Unit
    ) {
        if (!generationValid() || !target.isAttachedToWindow || !hostDecor.isAttachedToWindow) {
            onFinished(null)
            return
        }
        val sourceRect = sourceRect(hostDecor, target) ?: run {
            onFinished(null)
            return
        }
        if (AppConfig.blurExcludeText) {
            captureTextless(hostDecor, target, sourceRect, radius, onFinished)
            return
        }
        val sourceBitmap = runCatching {
            Bitmap.createBitmap(sourceRect.width(), sourceRect.height(), Bitmap.Config.ARGB_8888)
        }.getOrNull() ?: run {
            onFinished(null)
            return
        }

        // PixelCopy 回调在个别设备/窗口状态下可能永不触发，导致依赖 onReady
        // 恢复可见性的界面（菜单/弹窗）永远隐形。加统一超时：未回调按失败处理。
        var settled = false
        fun fail() {
            if (settled) return
            settled = true
            sourceBitmap.recycleSafely()
            if (attempt < SURFACE_PIXEL_COPY_RETRIES) {
                mainHandler.postDelayed({
                    requestBackdrop(
                        hostWindow,
                        hostDecor,
                        target,
                        radius,
                        generationValid,
                        attempt + 1,
                        onFinished
                    )
                }, SURFACE_STABLE_FRAME_DELAY_MS)
            } else {
                onFinished(null)
            }
        }
        val timeoutGuard = Runnable { fail() }
        try {
            mainHandler.postDelayed(timeoutGuard, SURFACE_PIXEL_COPY_TIMEOUT_MS)
            PixelCopy.request(hostWindow, sourceRect, sourceBitmap, { result ->
                if (settled) {
                    // 超时兜底已触发，忽略迟到回调
                    if (!generationValid()) sourceBitmap.recycleSafely()
                    return@request
                }
                settled = true
                mainHandler.removeCallbacks(timeoutGuard)
                if (!generationValid()) {
                    sourceBitmap.recycleSafely()
                    onFinished(null)
                    return@request
                }
                if (result == PixelCopy.SUCCESS) {
                    val blurred = runCatching { blurBitmap(sourceBitmap, radius) }.getOrNull()
                    sourceBitmap.recycleSafely()
                    onFinished(blurred)
                } else {
                    fail()
                }
            }, mainHandler)
        } catch (_: Throwable) {
            mainHandler.removeCallbacks(timeoutGuard)
            fail()
        }
    }

    /** PixelCopy 的矩形始终使用源 Window 坐标。 */
    private fun sourceRect(hostDecor: View, target: View): Rect? {
        if (hostDecor.width <= 0 || hostDecor.height <= 0) return null
        val targetLocation = IntArray(2)
        val hostLocation = IntArray(2)
        val rawLeft: Int
        val rawTop: Int
        if (target.rootView === hostDecor.rootView) {
            target.getLocationInWindow(targetLocation)
            hostDecor.getLocationInWindow(hostLocation)
            rawLeft = targetLocation[0] - hostLocation[0]
            rawTop = targetLocation[1] - hostLocation[1]
        } else {
            target.getLocationOnScreen(targetLocation)
            hostDecor.getLocationOnScreen(hostLocation)
            val hostInWindow = IntArray(2)
            hostDecor.getLocationInWindow(hostInWindow)
            rawLeft = targetLocation[0] - hostLocation[0] + hostInWindow[0]
            rawTop = targetLocation[1] - hostLocation[1] + hostInWindow[1]
        }
        val left = rawLeft.coerceIn(0, hostDecor.width)
        val top = rawTop.coerceIn(0, hostDecor.height)
        val right = (rawLeft + target.width).coerceIn(0, hostDecor.width)
        val bottom = (rawTop + target.height).coerceIn(0, hostDecor.height)
        return if (right > left && bottom > top) Rect(left, top, right, bottom) else null
    }

    private fun View.screenBounds(): Rect? {
        if (!isAttachedToWindow || width <= 0 || height <= 0) return null
        val location = IntArray(2)
        return runCatching {
            getLocationOnScreen(location)
            Rect(location[0], location[1], location[0] + width, location[1] + height)
        }.getOrNull()
    }

    /**
     * 开关开启时的离屏无文字采集：直接按模糊采样率渲染宿主窗口（不含文字），
     * 并叠画已登记的下层弹窗窗口，只缩一次，模糊后放大回目标尺寸。
     * 主线程同步完成，不影响真实帧。
     */
    private fun captureTextless(
        hostDecor: View,
        target: View,
        sourceRect: Rect,
        radius: Int,
        onFinished: (Bitmap?) -> Unit
    ) {
        val sampleWidth = (sourceRect.width() / SURFACE_BLUR_SAMPLE).coerceAtLeast(1)
        val sampleHeight = (sourceRect.height() / SURFACE_BLUR_SAMPLE).coerceAtLeast(1)
        val sampled = runCatching {
            Bitmap.createBitmap(sampleWidth, sampleHeight, Bitmap.Config.ARGB_8888)
        }.getOrNull() ?: run {
            onFinished(null)
            return
        }
        val hostOrigin = IntArray(2)
        hostDecor.getLocationOnScreen(hostOrigin)
        val blurred = runCatching {
            val canvas = Canvas(sampled)
            val scale = 1f / SURFACE_BLUR_SAMPLE
            canvas.scale(scale, scale)
            canvas.translate(-sourceRect.left.toFloat(), -sourceRect.top.toFloat())
            drawTextlessWindow(hostDecor, canvas)
            drawOverlayRoots(hostDecor, target, hostOrigin, canvas)
            blurSampled(sampled, radius, sourceRect.width(), sourceRect.height())
        }.getOrNull()
        sampled.recycleSafely()
        onFinished(blurred)
    }

    /**
     * 离屏渲染宿主窗口且不包含文字：临时隐藏窗口内全部 TextView、打了
     * [R.id.tag_backdrop_hide] 标记的页内家具（如朗读小面板），
     * 并让阅读页等自绘文字路径经 [BackdropRenderState] 跳过文字绘制。
     * 全程同步执行并在返回前恢复，真实帧不受影响。
     */
    private fun drawTextlessWindow(decor: View, canvas: Canvas) {
        val hidden = ArrayList<Pair<View, Int>>()
        collectBackdropHiddenViews(decor, hidden)
        hidden.forEach { (view, _) -> view.visibility = View.INVISIBLE }
        try {
            BackdropRenderState.withTextSuppressed {
                decor.draw(canvas)
            }
        } finally {
            hidden.forEach { (view, visibility) -> view.visibility = visibility }
        }
    }

    private fun collectBackdropHiddenViews(view: View, out: ArrayList<Pair<View, Int>>) {
        if (view.visibility != View.VISIBLE) return
        if (view is TextView || view.getTag(R.id.tag_backdrop_hide) == true) {
            out.add(view to view.visibility)
            return
        }
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                collectBackdropHiddenViews(view.getChildAt(index), out)
            }
        }
    }

    private fun registerOverlayRoot(root: View) {
        synchronized(overlayRoots) {
            overlayRoots.removeAll { it.get() == null }
            if (overlayRoots.none { it.get() === root }) {
                overlayRoots.add(WeakReference(root))
            }
        }
    }

    /**
     * 叠画独立窗口表面（下层弹窗的本体）：上层表面采集时，下层表面按登记顺序
     * 叠在宿主内容之上参与模糊；各自隐藏文字，跳过请求方自身所在的窗口。
     * 下层玻璃面的 tint 与模糊底图不参与叠加，保证各层弹窗透明度一致。
     */
    private fun drawOverlayRoots(
        hostDecor: View,
        target: View,
        hostOrigin: IntArray,
        canvas: Canvas
    ) {
        val roots = synchronized(overlayRoots) {
            overlayRoots.removeAll { ref ->
                val view = ref.get()
                view == null || !view.isAttachedToWindow
            }
            overlayRoots.mapNotNull { it.get() }
        }
        roots.forEach { root ->
            if (root === hostDecor || root === target.rootView) return@forEach
            if (root.width <= 0 || root.height <= 0 || root.visibility != View.VISIBLE) {
                return@forEach
            }
            val location = IntArray(2)
            root.getLocationOnScreen(location)
            canvas.save()
            canvas.translate(
                (location[0] - hostOrigin[0]).toFloat(),
                (location[1] - hostOrigin[1]).toFloat()
            )
            drawOverlayRootContent(root, canvas)
            canvas.restore()
        }
    }

    /**
     * 采集下层窗口时剥离其中所有玻璃面的样式（tint + 已模糊底图）：
     * 玻璃面是各层自己叠加的外观，混进模糊源会让上层弹窗 tint 翻倍、
     * 亮度断层形成光晕；剥离后模糊源只含下层窗口的内容与背景。
     */
    private fun drawOverlayRootContent(root: View, canvas: Canvas) {
        val stripped = ArrayList<Pair<View, Drawable?>>()
        synchronized(states) {
            states.keys.forEach { view ->
                if (view.rootView === root) {
                    stripped += view to view.background
                }
            }
        }
        stripped.forEach { (view, _) -> view.background = null }
        try {
            drawTextlessWindow(root, canvas)
        } finally {
            stripped.forEach { (view, background) -> view.background = background }
        }
    }

    private fun blurBitmap(source: Bitmap, radius: Int): Bitmap {
        val width = (source.width / SURFACE_BLUR_SAMPLE).coerceAtLeast(1)
        val height = (source.height / SURFACE_BLUR_SAMPLE).coerceAtLeast(1)
        val small = Bitmap.createScaledBitmap(source, width, height, true)
        val result = blurSampled(small, radius, source.width, source.height)
        if (small !== source) small.recycleSafely()
        return result
    }

    /** 对已缩至采样分辨率的位图做盒模糊，再放大回目标尺寸（整条链路只缩一次）。 */
    private fun blurSampled(source: Bitmap, radius: Int, outWidth: Int, outHeight: Int): Bitmap {
        val width = source.width
        val height = source.height
        val pixels = IntArray(width * height)
        val buffer = IntArray(pixels.size)
        source.getPixels(pixels, 0, width, 0, 0, width, height)
        val blurRadius = (radius / SURFACE_BLUR_SAMPLE).coerceIn(1, 24)
        repeat(3) {
            blurHorizontal(pixels, buffer, width, height, blurRadius)
            blurVertical(buffer, pixels, width, height, blurRadius)
        }
        val blurredSmall = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
            it.setPixels(pixels, 0, width, 0, 0, width, height)
        }
        val result = Bitmap.createScaledBitmap(blurredSmall, outWidth, outHeight, true)
        if (result !== blurredSmall) blurredSmall.recycleSafely()
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
                val remove = source[y * width + (x - radius).coerceIn(0, width - 1)]
                val add = source[y * width + (x + radius + 1).coerceIn(0, width - 1)]
                alpha += Color.alpha(add) - Color.alpha(remove)
                red += Color.red(add) - Color.red(remove)
                green += Color.green(add) - Color.green(remove)
                blue += Color.blue(add) - Color.blue(remove)
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
                val remove = source[(y - radius).coerceIn(0, height - 1) * width + x]
                val add = source[(y + radius + 1).coerceIn(0, height - 1) * width + x]
                alpha += Color.alpha(add) - Color.alpha(remove)
                red += Color.red(add) - Color.red(remove)
                green += Color.green(add) - Color.green(remove)
                blue += Color.blue(add) - Color.blue(remove)
            }
        }
    }

    private fun Bitmap.recycleSafely() {
        if (!isRecycled) recycle()
    }
}
