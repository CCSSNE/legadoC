package io.legado.app.utils

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.theme.surface.SurfaceDrawable
import io.legado.app.lib.theme.surface.SurfaceStyle
import java.io.File
import java.lang.ref.WeakReference
import java.util.WeakHashMap

private const val SURFACE_STABLE_FRAME_DELAY_MS = 16L
private const val SURFACE_STABLE_FRAME_LIMIT = 24
private const val SURFACE_PIXEL_COPY_RETRIES = 2
private const val SURFACE_PIXEL_COPY_TIMEOUT_MS = 800L
private const val SURFACE_BLUR_SAMPLE = 4
private const val PANEL_BACKDROP_MAX_DIMENSION = 1080

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
 * 调用方必须明确提供真正承载背景的 target 和完整逻辑浮层 layerOwner。展示层只在 attach
 * 后登记，并以登记顺序确定上下关系；采集只合成请求层以下的内容，不反射或猜测窗口根。
 */
object SurfaceBackdrop {

    private data class State(
        val originalBackground: Drawable?,
        var generation: Int = 0,
        var bitmap: Bitmap? = null,
        var style: SurfaceStyle? = null,
        var attachListener: View.OnAttachStateChangeListener? = null
    )

    private data class PresentedLayer(
        val owner: WeakReference<View>,
        val hostDecor: WeakReference<View>,
        val targets: ArrayList<WeakReference<View>>,
        val order: Long
    )

    private data class LayerSnapshot(
        val owner: View,
        val surfaces: List<View>,
        val order: Long
    )

    private data class CaptureComposition(
        val allLayers: List<LayerSnapshot>,
        val sameWindowOwners: List<View>,
        val lowerLayers: List<LayerSnapshot>
    )

    private val states = WeakHashMap<View, State>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val presentedLayers = WeakHashMap<View, PresentedLayer>()
    private val paperContentRoots = WeakHashMap<View, Unit>()
    private var nextPresentationOrder = 0L

    fun excludeFromPaperCapture(contentRoot: View) {
        synchronized(paperContentRoots) {
            paperContentRoots[contentRoot] = Unit
        }
    }

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
        layerOwner: View,
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

        val hostDecor = hostWindow.decorView
        val staticBackdrop = style.backdropImagePath
        val captureEnabled = staticBackdrop == null &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            style.blurRadiusPx > 0

        awaitStableBounds(
            target = target,
            hostDecor = hostDecor,
            generationValid = { state.generation == generation },
            onStable = {
                presentLayer(hostDecor, target, layerOwner)
                if (staticBackdrop != null) {
                    // 主题面板底图：解码在后台线程完成，完成后按当前代次安装；
                    // 代次已过期或目标已脱离时由 finish 统一回收。
                    Thread {
                        val bitmap = decodeStaticBackdrop(staticBackdrop)
                        mainHandler.post { finish(bitmap) }
                    }.start()
                } else if (captureEnabled) {
                    requestBackdrop(
                        hostWindow = hostWindow,
                        hostDecor = hostDecor,
                        target = target,
                        layerOwner = layerOwner,
                        radius = style.blurRadiusPx,
                        generationValid = { state.generation == generation },
                        attempt = 0,
                        onFinished = ::finish
                    )
                } else {
                    finish(null)
                }
            },
            onFailure = { finish(null) }
        )
    }

    /** Re-captures a surface while keeping the style installed by its owner. */
    fun refresh(
        hostWindow: Window,
        target: View,
        layerOwner: View,
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
            layerOwner = layerOwner,
            onReady = onReady
        )
    }

    /** Completes only after every explicitly named surface has finished preparing. */
    fun refresh(
        hostWindow: Window,
        targets: Iterable<View>,
        layerOwner: View,
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
                layerOwner = layerOwner
            ) {
                remaining -= 1
                if (remaining == 0) onReady()
            }
        }
    }

    fun cancel(target: View, keepStaticStyle: Boolean = true) {
        removePresentedTarget(target)
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
        removePresentedTarget(target)
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

    private fun presentLayer(hostDecor: View, target: View, layerOwner: View) {
        require(hostDecor.isAttachedToWindow) {
            "Surface backdrop host must be attached before presentation"
        }
        require(target.isAttachedToWindow) {
            "Surface backdrop target must be attached before presentation"
        }
        require(layerOwner.isAttachedToWindow) {
            "Surface backdrop layer owner must be attached before presentation"
        }
        require(target.isSelfOrDescendantOf(layerOwner)) {
            "Surface backdrop target must belong to its declared layer owner"
        }
        require(target.rootView === layerOwner.rootView) {
            "Surface backdrop target and layer owner must share one window"
        }

        synchronized(presentedLayers) {
            removeInvalidPresentedLayers()
            val existing = presentedLayers[layerOwner]
            if (existing != null && existing.hostDecor.get() === hostDecor) {
                existing.targets.removeAll { it.get() == null }
                if (existing.targets.none { it.get() === target }) {
                    existing.targets += WeakReference(target)
                }
                return
            }
            nextPresentationOrder += 1
            presentedLayers[layerOwner] = PresentedLayer(
                owner = WeakReference(layerOwner),
                hostDecor = WeakReference(hostDecor),
                targets = arrayListOf(WeakReference(target)),
                order = nextPresentationOrder
            )
        }
    }

    private fun removePresentedTarget(target: View) {
        synchronized(presentedLayers) {
            val iterator = presentedLayers.entries.iterator()
            while (iterator.hasNext()) {
                val layer = iterator.next().value
                layer.targets.removeAll { reference ->
                    val registeredTarget = reference.get()
                    registeredTarget == null || registeredTarget === target
                }
                if (layer.targets.isEmpty()) iterator.remove()
            }
        }
    }

    private fun removeInvalidPresentedLayers() {
        val iterator = presentedLayers.entries.iterator()
        while (iterator.hasNext()) {
            val layer = iterator.next().value
            val owner = layer.owner.get()
            val hostDecor = layer.hostDecor.get()
            layer.targets.removeAll { it.get() == null }
            if (
                owner == null || hostDecor == null ||
                !owner.isAttachedToWindow || !hostDecor.isAttachedToWindow ||
                layer.targets.isEmpty()
            ) {
                iterator.remove()
            }
        }
    }

    private fun captureComposition(hostDecor: View, requesterOwner: View): CaptureComposition {
        return synchronized(presentedLayers) {
            removeInvalidPresentedLayers()
            val requester = requireNotNull(presentedLayers[requesterOwner]) {
                "Surface backdrop requester has no active presentation layer"
            }
            require(requester.hostDecor.get() === hostDecor) {
                "Surface backdrop requester belongs to a different host window"
            }
            val layers = presentedLayers.values.mapNotNull { layer ->
                val owner = layer.owner.get() ?: return@mapNotNull null
                val surfaces = layer.targets.mapNotNull { it.get() }.filter {
                    it.isAttachedToWindow && it.visibility == View.VISIBLE
                }
                if (
                    layer.hostDecor.get() !== hostDecor ||
                    owner.visibility != View.VISIBLE ||
                    !owner.isAttachedToWindow ||
                    surfaces.isEmpty()
                ) {
                    return@mapNotNull null
                }
                LayerSnapshot(owner, surfaces, layer.order)
            }.sortedBy { it.order }
            CaptureComposition(
                allLayers = layers,
                sameWindowOwners = layers.mapNotNull { layer ->
                    layer.owner.takeIf { it.rootView === hostDecor.rootView }
                },
                lowerLayers = layers.filter { it.order < requester.order }
            )
        }
    }

    private fun View.isSelfOrDescendantOf(ancestor: View): Boolean {
        var current: View? = this
        while (current != null) {
            if (current === ancestor) return true
            current = current.parent as? View
        }
        return false
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
        layerOwner: View,
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
            capturePaperBackdrop(hostDecor, layerOwner, sourceRect, radius, onFinished)
            return
        }
        require(
            layerOwner.rootView !== hostDecor.rootView || layerOwner.alpha == 0f
        ) {
            "Same-window surface owner must be hidden before PixelCopy"
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
                        layerOwner,
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
                mainHandler.removeCallbacks(timeoutGuard)
                if (!generationValid()) {
                    settled = true
                    sourceBitmap.recycleSafely()
                    onFinished(null)
                    return@request
                }
                if (result == PixelCopy.SUCCESS) {
                    settled = true
                    drawIndependentLowerLayers(
                        bitmap = sourceBitmap,
                        hostDecor = hostDecor,
                        requesterOwner = layerOwner,
                        sourceRect = sourceRect
                    )
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

    /**
     * 主题面板底图解码。按上限采样控制内存；文件缺失或解码失败返回 null，
     * 调用方将按无静态底图的默认路径继续（面板图本来就是可选输入）。
     * 仅在后台线程调用。
     */
    private fun decodeStaticBackdrop(path: String): Bitmap? {
        val file = File(path)
        if (!file.isFile) return null
        return runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
            var sampleSize = 1
            while (
                bounds.outWidth / (sampleSize * 2) >= PANEL_BACKDROP_MAX_DIMENSION &&
                bounds.outHeight / (sampleSize * 2) >= PANEL_BACKDROP_MAX_DIMENSION
            ) {
                sampleSize *= 2
            }
            BitmapFactory.decodeFile(
                file.absolutePath,
                BitmapFactory.Options().apply { inSampleSize = sampleSize }
            )
        }.getOrNull()
    }

    /** PixelCopy 的矩形始终使用源 Window 坐标。 */
    private fun sourceRect(hostDecor: View, target: View): Rect? {        if (hostDecor.width <= 0 || hostDecor.height <= 0) return null
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
     * 开关开启时的纸面采集：先移除宿主窗口中的全部已登记浮层，再按展示顺序
     * 只叠画请求层以下的玻璃表面，不重绘这些浮层的控件树。请求层自身与其上方内容
     * 不会进入模糊源。
     * 主线程同步完成，不影响真实帧。
     */
    private fun capturePaperBackdrop(
        hostDecor: View,
        requesterOwner: View,
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
        val composition = captureComposition(hostDecor, requesterOwner)
        val excludedHostContent = synchronized(paperContentRoots) {
            paperContentRoots.keys.filter {
                it.isAttachedToWindow && it.rootView === hostDecor.rootView
            }
        }
        val blurred = runCatching {
            val canvas = Canvas(sampled)
            val scale = 1f / SURFACE_BLUR_SAMPLE
            canvas.scale(scale, scale)
            canvas.translate(-sourceRect.left.toFloat(), -sourceRect.top.toFloat())
            drawWindow(
                view = hostDecor,
                canvas = canvas,
                suppressText = true,
                excludedOwners = (composition.sameWindowOwners + excludedHostContent).toSet()
            )
            composition.lowerLayers.forEach { layer ->
                drawPresentedLayer(
                    layer = layer,
                    allLayers = composition.allLayers,
                    hostOrigin = hostOrigin,
                    canvas = canvas,
                    surfaceOnly = true
                )
            }
            blurSampled(sampled, radius, sourceRect.width(), sourceRect.height())
        }.getOrNull()
        sampled.recycleSafely()
        onFinished(blurred)
    }

    /**
     * 离屏渲染窗口：按采集模式临时隐藏文字，并让阅读页等自绘内容路径经
     * [BackdropRenderState] 只绘制纸面；明确传入的其他逻辑浮层 owner 同步排除。
     * 全程同步执行并在返回前恢复，真实帧不受影响。
     */
    private fun drawWindow(
        view: View,
        canvas: Canvas,
        suppressText: Boolean,
        excludedOwners: Set<View> = emptySet()
    ) {
        val hidden = ArrayList<Pair<View, Int>>()
        collectBackdropHiddenViews(view, suppressText, excludedOwners, hidden)
        hidden.forEach { (view, _) -> view.visibility = View.INVISIBLE }
        try {
            if (suppressText) {
                BackdropRenderState.withTextSuppressed {
                    view.draw(canvas)
                }
            } else {
                view.draw(canvas)
            }
        } finally {
            hidden.forEach { (view, visibility) -> view.visibility = visibility }
        }
    }

    private fun collectBackdropHiddenViews(
        view: View,
        suppressText: Boolean,
        excludedOwners: Set<View>,
        out: ArrayList<Pair<View, Int>>
    ) {
        if (view.visibility != View.VISIBLE) return
        if (view in excludedOwners || suppressText && view is TextView) {
            out.add(view to view.visibility)
            return
        }
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                collectBackdropHiddenViews(
                    view.getChildAt(index),
                    suppressText,
                    excludedOwners,
                    out
                )
            }
        }
    }

    private fun drawPresentedLayer(
        layer: LayerSnapshot,
        allLayers: List<LayerSnapshot>,
        hostOrigin: IntArray,
        canvas: Canvas,
        surfaceOnly: Boolean
    ) {
        if (surfaceOnly) {
            layer.surfaces.forEach { surface ->
                drawSurfaceBackground(surface, hostOrigin, canvas)
            }
            return
        }
        val owner = layer.owner
        val nestedOwners = allLayers.mapNotNull { other ->
            other.owner.takeIf {
                it !== owner && it.isSelfOrDescendantOf(owner)
            }
        }.toSet()
        val location = IntArray(2)
        owner.getLocationOnScreen(location)
        canvas.save()
        canvas.translate(
            (location[0] - hostOrigin[0]).toFloat(),
            (location[1] - hostOrigin[1]).toFloat()
        )
        drawWindow(
            view = owner,
            canvas = canvas,
            suppressText = false,
            excludedOwners = nestedOwners
        )
        canvas.restore()
    }

    private fun drawSurfaceBackground(surface: View, hostOrigin: IntArray, canvas: Canvas) {
        val background = surface.background
        require(background is SurfaceDrawable) {
            "Presented surface target must own a SurfaceDrawable"
        }
        val location = IntArray(2)
        surface.getLocationOnScreen(location)
        canvas.save()
        canvas.translate(
            (location[0] - hostOrigin[0]).toFloat(),
            (location[1] - hostOrigin[1]).toFloat()
        )
        background.draw(canvas)
        canvas.restore()
    }

    private fun drawIndependentLowerLayers(
        bitmap: Bitmap,
        hostDecor: View,
        requesterOwner: View,
        sourceRect: Rect
    ) {
        val composition = captureComposition(hostDecor, requesterOwner)
        val independentLayers = composition.lowerLayers.filter {
            it.owner.rootView !== hostDecor.rootView
        }
        if (independentLayers.isEmpty()) return
        val hostOrigin = IntArray(2)
        hostDecor.getLocationOnScreen(hostOrigin)
        val canvas = Canvas(bitmap)
        canvas.translate(-sourceRect.left.toFloat(), -sourceRect.top.toFloat())
        independentLayers.forEach { layer ->
            drawPresentedLayer(
                layer = layer,
                allLayers = composition.allLayers,
                hostOrigin = hostOrigin,
                canvas = canvas,
                surfaceOnly = false
            )
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
