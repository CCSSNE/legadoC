@file:Suppress("unused")

package io.legado.app.utils

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Picture
import android.graphics.Rect
import android.os.Build
import android.text.Spannable
import android.text.Spanned
import android.text.method.ScrollingMovementMethod
import android.text.style.ImageSpan
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.View.GONE
import android.view.View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
import android.view.View.INVISIBLE
import android.view.View.VISIBLE
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EdgeEffect
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import androidx.annotation.ColorInt
import androidx.annotation.DrawableRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.record
import androidx.core.graphics.withTranslation
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.get
import androidx.core.view.marginBottom
import androidx.core.view.updateLayoutParams
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager.widget.ViewPager
import io.legado.app.help.GlideImageGetter
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.theme.TintHelper
import io.legado.app.utils.canvasrecorder.CanvasRecorder
import io.legado.app.utils.canvasrecorder.record
import splitties.systemservices.inputMethodManager
import splitties.views.bottomPadding
import splitties.views.topPadding
import androidx.core.graphics.createBitmap
import androidx.core.text.HtmlCompat
import androidx.core.view.isVisible
import androidx.core.text.parseAsHtml
import io.legado.app.R
import io.legado.app.help.TextViewTagHandler
import io.legado.app.model.analyzeRule.AnalyzeUrl.Companion.paramPattern
import io.noties.markwon.Markwon
import io.noties.markwon.image.AsyncDrawableSpan

private tailrec fun getCompatActivity(context: Context?): AppCompatActivity? {
    return when (context) {
        is AppCompatActivity -> context
        is androidx.appcompat.view.ContextThemeWrapper -> getCompatActivity(context.baseContext)
        is android.view.ContextThemeWrapper -> getCompatActivity(context.baseContext)
        else -> null
    }
}

val View.activity: AppCompatActivity?
    get() = getCompatActivity(context)

fun View.hideSoftInput() = run {
    inputMethodManager.hideSoftInputFromWindow(this.windowToken, 0)
}

fun EditText.showSoftInput() = run {
    requestFocus()
    inputMethodManager.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
}

fun View.disableAutoFill() = run {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        this.importantForAutofill = IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
    }
}

fun View.applyTint(
    @ColorInt color: Int,
    isDark: Boolean = AppConfig.isNightTheme
) {
    TintHelper.setTintAuto(this, color, false, isDark)
}

fun View.applyBackgroundTint(
    @ColorInt color: Int,
    isDark: Boolean = AppConfig.isNightTheme
) {
    if (background == null) {
        setBackgroundColor(color)
    } else {
        TintHelper.setTintAuto(this, color, true, isDark)
    }
}

fun RecyclerView.setEdgeEffectColor(@ColorInt color: Int) {
    edgeEffectFactory = object : RecyclerView.EdgeEffectFactory() {
        override fun createEdgeEffect(view: RecyclerView, direction: Int): EdgeEffect {
            val edgeEffect = super.createEdgeEffect(view, direction)
            edgeEffect.color = color
            return edgeEffect
        }
    }
}

fun ViewPager.setEdgeEffectColor(@ColorInt color: Int) {
    try {
        val clazz = ViewPager::class.java
        for (name in arrayOf("mLeftEdge", "mRightEdge")) {
            val field = clazz.getDeclaredField(name)
            field.isAccessible = true
            val edge = field.get(this)
            (edge as EdgeEffect).color = color
        }
    } catch (ignored: Exception) {
    }
}

fun EditText.disableEdit() {
    keyListener = null
}

fun View.gone() {
    if (visibility != GONE) {
        visibility = GONE
    }
}

fun View.gone(gone: Boolean) {
    if (gone) {
        gone()
    } else {
        visibility = VISIBLE
    }
}

fun View.invisible() {
    if (visibility != INVISIBLE) {
        visibility = INVISIBLE
    }
}

fun View.visible() {
    if (visibility != VISIBLE) {
        visibility = VISIBLE
    }
}

fun View.visible(visible: Boolean) {
    if (visible && visibility != VISIBLE) {
        visibility = VISIBLE
    } else if (!visible && isVisible) {
        visibility = INVISIBLE
    }
}

fun View.screenshot(bitmap: Bitmap? = null, canvas: Canvas? = null): Bitmap? {
    return if (width > 0 && height > 0) {
        val screenshot = if (bitmap != null && bitmap.width == width && bitmap.height == height) {
            bitmap.eraseColor(Color.TRANSPARENT)
            bitmap
        } else {
            bitmap?.recycle()
            createBitmap(width, height)
        }
        val c = canvas ?: Canvas()
        c.setBitmap(screenshot)
        c.withTranslation(-scrollX.toFloat(), -scrollY.toFloat()) {
            this@screenshot.draw(this)
        }
        c.setBitmap(null)
        screenshot.prepareToDraw()
        screenshot
    } else {
        null
    }
}

fun View.screenshot(picture: Picture) {
    if (width > 0 && height > 0) {
        picture.record(width, height) {
            withTranslation(-scrollX.toFloat(), -scrollY.toFloat()) {
                draw(this)
            }
        }
    }
}

fun View.screenshot(canvasRecorder: CanvasRecorder) {
    if (width > 0 && height > 0) {
        canvasRecorder.record(width, height) {
            draw(this)
        }
    }
}

fun View.setPaddingBottom(bottom: Int) {
    setPadding(paddingLeft, paddingTop, paddingRight, bottom)
}

fun SeekBar.progressAdd(int: Int) {
    progress += int
}

fun RadioGroup.getIndexById(id: Int): Int {
    for (i in 0 until this.childCount) {
        if (id == get(i).id) {
            return i
        }
    }
    return 0
}

fun RadioGroup.getCheckedIndex(): Int {
    for (i in 0 until this.childCount) {
        if (checkedRadioButtonId == get(i).id) {
            return i
        }
    }
    return 0
}

fun RadioGroup.checkByIndex(index: Int) {
    check(get(index).id)
}

fun TextView.setHtml(html: String, imageGetter: GlideImageGetter? = null, textViewTagHandler: TextViewTagHandler? = null) {
    text = html.parseAsHtml(HtmlCompat.FROM_HTML_MODE_COMPACT, imageGetter, textViewTagHandler)
}

fun TextView.setHtml(html: String, imageGetter: GlideImageGetter? = null, textViewTagHandler: TextViewTagHandler? = null, imgOnLongClickListener: (source: String) -> Unit, imgOnClickListener: (click: String) -> Unit) {
    val spanned = html.parseAsHtml(HtmlCompat.FROM_HTML_MODE_COMPACT, imageGetter, textViewTagHandler)
    val imageSpans = spanned.getSpans(0, spanned.length, ImageSpan::class.java)
    val imageTargets = mutableListOf<InlineImageTouchTarget>()
    for (imageSpan in imageSpans) {
        val start = spanned.getSpanStart(imageSpan)
        val end = spanned.getSpanEnd(imageSpan)
        if (start >= 0 && end >= 0) {
            val source = imageSpan.source ?: continue
            var click: String? = null
            val urlMatcher = paramPattern.matcher(source)
            if (urlMatcher.find()) {
                val urlOptionStr = source.substring(urlMatcher.end())
                GSON.fromJsonObject<Map<String, String>>(urlOptionStr).getOrNull()?.let {
                    click = it["click"]
                }
            }
            imageTargets += InlineImageTouchTarget(start, end, source, click)
        }
    }
    text = spanned
    if (imageTargets.isNotEmpty()) {
        movementMethod = InlineImageMovementMethod(
            imageTargets = imageTargets,
            onImageLongClick = imgOnLongClickListener,
            onImageClick = imgOnClickListener
        )
    }
}

fun TextView.setMarkdown(markwon: Markwon, spanned: Spanned, imgOnLongClickListener: (source: String) -> Unit) {
    val imageSpans = spanned.getSpans(0, spanned.length, AsyncDrawableSpan::class.java)
    val imageTargets = mutableListOf<InlineImageTouchTarget>()
    for (imageSpan in imageSpans) {
        val start = spanned.getSpanStart(imageSpan)
        val end = spanned.getSpanEnd(imageSpan)
        if (start >= 0 && end >= 0) {
            val source = imageSpan.drawable.destination
            imageTargets += InlineImageTouchTarget(start, end, source)
        }
    }
    if (imageTargets.isNotEmpty()) {
        movementMethod = InlineImageMovementMethod(
            imageTargets = imageTargets,
            onImageLongClick = imgOnLongClickListener
        )
    }
    markwon.setParsedMarkdown(this, spanned)
}

private data class InlineImageTouchTarget(
    val start: Int,
    val end: Int,
    val source: String,
    val click: String? = null
)

private class InlineImageMovementMethod(
    private val imageTargets: List<InlineImageTouchTarget>,
    private val onImageLongClick: (String) -> Unit,
    private val onImageClick: ((String) -> Unit)? = null
) : android.text.method.LinkMovementMethod() {

    private var activeTarget: InlineImageTouchTarget? = null
    private var longClickRunnable: Runnable? = null
    private var longClicked = false
    private var imageDragging = false
    private var downX = 0f
    private var downY = 0f
    private var lastClickTime = 0L
    private val scrollingMovementMethod = ScrollingMovementMethod.getInstance()

    override fun onTouchEvent(
        widget: TextView,
        buffer: android.text.Spannable,
        event: MotionEvent
    ): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                cancelImageGesture(widget)
                val target = imageTargetAt(widget, event) ?: return super.onTouchEvent(widget, buffer, event)
                activeTarget = target
                downX = event.x
                downY = event.y
                imageDragging = false
                widget.isLongClickable = false
                scrollingMovementMethod.onTouchEvent(widget, buffer, event)
                longClickRunnable = Runnable {
                    if (activeTarget == target) {
                        longClicked = true
                        onImageLongClick(target.source)
                    }
                }.also { widget.postDelayed(it, 600) }
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (activeTarget == null) return super.onTouchEvent(widget, buffer, event)
                val touchSlop = ViewConfiguration.get(widget.context).scaledTouchSlop
                val deltaX = event.x - downX
                val deltaY = event.y - downY
                if (deltaX * deltaX + deltaY * deltaY > touchSlop * touchSlop) {
                    cancelLongClick(widget)
                    imageDragging = true
                }
                scrollingMovementMethod.onTouchEvent(widget, buffer, event)
                return true
            }

            MotionEvent.ACTION_UP -> {
                val target = activeTarget ?: return super.onTouchEvent(widget, buffer, event)
                val releaseTarget = imageTargetAt(widget, event)
                val wasLongClicked = longClicked
                val wasDragged = imageDragging
                endScrollingGesture(widget, buffer, event)
                cancelImageGesture(widget)
                if (!wasDragged && !wasLongClicked && releaseTarget == target) {
                    target.click?.let { click ->
                        val now = System.currentTimeMillis()
                        if (now - lastClickTime > 200) {
                            lastClickTime = now
                            onImageClick?.invoke(click)
                        }
                        return true
                    }
                }
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                if (activeTarget != null) {
                    endScrollingGesture(widget, buffer, event)
                    cancelImageGesture(widget)
                    return true
                }
            }
        }
        return super.onTouchEvent(widget, buffer, event)
    }

    private fun imageTargetAt(widget: TextView, event: MotionEvent): InlineImageTouchTarget? {
        val layout = widget.layout ?: return null
        val x = event.x - widget.totalPaddingLeft + widget.scrollX
        val y = event.y - widget.totalPaddingTop + widget.scrollY
        if (x < 0 || y < 0 || y >= layout.height) return null
        val line = layout.getLineForVertical(y.toInt())
        if (x < layout.getLineLeft(line) || x > layout.getLineRight(line)) return null
        val offset = layout.getOffsetForHorizontal(line, x)
        return imageTargets.firstOrNull { target -> offset >= target.start && offset < target.end }
    }

    private fun cancelImageGesture(widget: TextView) {
        cancelLongClick(widget)
        activeTarget = null
        imageDragging = false
        longClicked = false
        widget.isLongClickable = true
    }

    private fun cancelLongClick(widget: TextView) {
        longClickRunnable?.let(widget::removeCallbacks)
        longClickRunnable = null
    }

    private fun endScrollingGesture(
        widget: TextView,
        buffer: Spannable,
        event: MotionEvent
    ) {
        if (event.actionMasked != MotionEvent.ACTION_CANCEL) {
            scrollingMovementMethod.onTouchEvent(widget, buffer, event)
            return
        }
        val endEvent = MotionEvent.obtain(event)
        endEvent.action = MotionEvent.ACTION_UP
        try {
            scrollingMovementMethod.onTouchEvent(widget, buffer, endEvent)
        } finally {
            endEvent.recycle()
        }
    }
}

fun TextView.setTextIfNotEqual(charSequence: CharSequence?) {
    if (text != charSequence) {
        text = charSequence
    }
}

fun View.shouldHideSoftInput(event: MotionEvent): Boolean {
    if (this is EditText) {
        val l = intArrayOf(0, 0)
        getLocationInWindow(l)
        val left = l[0]
        val top = l[1]
        val bottom = top + getHeight()
        val right = left + getWidth()
        return !(event.x > left && event.x < right && event.y > top && event.y < bottom)
    }
    return false
}

fun View.applyStatusBarPadding(withInitialPadding: Boolean = false) {
    val initialPadding = if (withInitialPadding) topPadding else 0
    setOnApplyWindowInsetsListenerCompat { _, windowInsets ->
        val insets = windowInsets.getInsets(WindowInsetsCompat.Type.statusBars())
        topPadding = initialPadding + insets.top
        windowInsets
    }
}

fun View.applyNavigationBarPadding(withInitialPadding: Boolean = false) {
    val initialPadding = if (withInitialPadding) bottomPadding else 0
    setOnApplyWindowInsetsListenerCompat { _, windowInsets ->
        bottomPadding = initialPadding + windowInsets.navigationBarHeight
        windowInsets
    }
}

fun View.applyMainBottomBarPadding(
    withInitialPadding: Boolean = false,
    usePaddingForRecyclerView: Boolean = false
) {
    val initialPadding = if (withInitialPadding) bottomPadding else 0
    setOnApplyWindowInsetsListenerCompat { _, windowInsets ->
        val bottomSpace = windowInsets.navigationBarHeight +
                resources.getDimensionPixelSize(R.dimen.main_content_bottom_bar_padding)
        if (this is RecyclerView) {
            if (usePaddingForRecyclerView) {
                removeMainBottomBarSpaceDecoration()
                bottomPadding = initialPadding + bottomSpace
            } else {
                bottomPadding = initialPadding
                updateMainBottomBarSpaceDecoration(bottomSpace)
            }
        } else {
            bottomPadding = initialPadding + bottomSpace
        }
        windowInsets
    }
}

private fun RecyclerView.updateMainBottomBarSpaceDecoration(bottomSpace: Int) {
    (getTag(R.id.main_bottom_bar_space_decoration) as? MainBottomBarSpaceDecoration)?.let {
        if (it.bottomSpace != bottomSpace) {
            it.bottomSpace = bottomSpace
            invalidateItemDecorations()
        }
        return
    }
    val decoration = MainBottomBarSpaceDecoration(bottomSpace)
    addItemDecoration(decoration)
    setTag(R.id.main_bottom_bar_space_decoration, decoration)
}

private fun RecyclerView.removeMainBottomBarSpaceDecoration() {
    (getTag(R.id.main_bottom_bar_space_decoration) as? MainBottomBarSpaceDecoration)?.let {
        removeItemDecoration(it)
        setTag(R.id.main_bottom_bar_space_decoration, null)
    }
}

private class MainBottomBarSpaceDecoration(var bottomSpace: Int) : RecyclerView.ItemDecoration() {
    override fun getItemOffsets(
        outRect: Rect,
        view: View,
        parent: RecyclerView,
        state: RecyclerView.State
    ) {
        val position = parent.getChildAdapterPosition(view)
        if (position != RecyclerView.NO_POSITION && position == state.itemCount - 1) {
            outRect.bottom = bottomSpace
        }
    }
}

fun View.applyNavigationBarMargin(withInitialMargin: Boolean = false) {
    val initialMargin = if (withInitialMargin) marginBottom else 0
    setOnApplyWindowInsetsListenerCompat { _, windowInsets ->
        updateLayoutParams<ViewGroup.MarginLayoutParams> {
            bottomMargin = initialMargin + windowInsets.navigationBarHeight
        }
        windowInsets
    }
}

fun View.setBackgroundKeepPadding(@DrawableRes backgroundResId: Int) {
    val paddingLeft = paddingLeft
    val paddingTop = paddingTop
    val paddingRight = paddingRight
    val paddingBottom = paddingBottom
    setBackgroundResource(backgroundResId)
    setPadding(paddingLeft, paddingTop, paddingRight, paddingBottom)
}

fun View.canScroll(direction: Int): Boolean {
    return canScrollVertically(direction) || canScrollHorizontally(direction)
}

private val requestLayoutBroken = Build.VERSION.SDK_INT <= Build.VERSION_CODES.M
        || Build.VERSION.SDK_INT in Build.VERSION_CODES.O..Build.VERSION_CODES.Q

fun View.setOnApplyWindowInsetsListenerCompat(listener: (View, WindowInsetsCompat) -> WindowInsetsCompat) {
    ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
        val windowInsets = listener(view, insets)
        if (requestLayoutBroken && isLayoutRequested) {
            post {
                requestLayout()
            }
        }
        windowInsets
    }
}

fun Spinner.setSelectionSafely(position: Int) {
    val count = adapter?.count ?: 0
    if (count > 0) {
        setSelection(position.coerceIn(0, count - 1))
    }
}
