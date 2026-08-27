package io.legado.app.ui.book.read.page

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.RectF
import android.os.Build
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.widget.FrameLayout
import io.legado.app.R
import io.legado.app.constant.PageAnim
import io.legado.app.data.entities.BookProgress
import io.legado.app.data.entities.Bookmark
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.constant.PreferKey
import io.legado.app.model.ReadAloud
import io.legado.app.model.ReadBook
import io.legado.app.service.BaseReadAloudService
import io.legado.app.ui.book.read.ContentEditDialog
import io.legado.app.ui.book.read.page.api.DataSource
import io.legado.app.ui.book.read.page.delegate.CoverPageDelegate
import io.legado.app.ui.book.read.page.delegate.HorizontalPageDelegate
import io.legado.app.ui.book.read.page.delegate.LinkedCoverPageDelegate
import io.legado.app.ui.book.read.page.delegate.NoAnimPageDelegate
import io.legado.app.ui.book.read.page.delegate.PageDelegate
import io.legado.app.ui.book.read.page.delegate.ScrollPageDelegate
import io.legado.app.ui.book.read.page.delegate.SimulationPageDelegate
import io.legado.app.ui.book.read.page.delegate.SlidePageDelegate
import io.legado.app.ui.book.read.page.entities.PageDirection
import io.legado.app.ui.book.read.page.entities.TextChapter
import io.legado.app.ui.book.read.page.entities.TextLine
import io.legado.app.ui.book.read.page.entities.TextPage
import io.legado.app.ui.book.read.page.entities.TextPos
import io.legado.app.ui.book.read.page.entities.column.TextBaseColumn
import io.legado.app.ui.book.read.page.provider.ChapterProvider
import io.legado.app.ui.book.read.page.provider.LayoutProgressListener
import io.legado.app.ui.book.read.page.provider.TextPageFactory
import io.legado.app.utils.activity
import io.legado.app.utils.dpToPx
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.invisible
import io.legado.app.utils.longToastOnUi
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.throttle
import java.text.BreakIterator
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

/**
 * 阅读视图
 */
class ReadView(context: Context, attrs: AttributeSet) :
    FrameLayout(context, attrs),
    DataSource, LayoutProgressListener {

    val callBack: CallBack get() = activity as CallBack
    var pageFactory: TextPageFactory = TextPageFactory(this)
    var pageDelegate: PageDelegate? = null
        private set(value) {
            field?.onDestroy()
            field = null
            field = value
            upContent()
        }
    override var isScroll = false
    val prevPage by lazy { PageView(context) }
    val curPage by lazy { PageView(context) }
    val nextPage by lazy { PageView(context) }
    val footerBounds: IntRange get() = curPage.footerBounds
    val defaultAnimationSpeed: Int
        get() = AppConfig.pageAnimationSpeed
    private var pressDown = false
    private var isMove = false
    private var audioDragging = false
    private var footerCenterActionTouching = false
    private var footerCenterActionPressedIndex: Int? = null

    //起始点
    var startX: Float = 0f
    var startY: Float = 0f

    //上一个触碰点
    var lastX: Float = 0f
    var lastY: Float = 0f

    //触碰点
    var touchX: Float = 0f
    var touchY: Float = 0f

    //是否停止动画动作
    var isAbortAnim = false

    //长按
    private var longPressed = false
    private val longPressTimeout = 600L
    private val longPressRunnable = Runnable {
        longPressed = true
        onLongPress()
    }
    var isTextSelected = false
    private var pressOnTextSelected = false
    private val initialTextPos = TextPos(0, 0, 0)

    //下拉添加整页书签手势（跟手位移 + 回弹动效）
    private var pullDownStartY = 0f
    private var pullDownArmed = false
    private var pullDownTriggered = false
    private var pullDownAdding = false
    private var pullDownAnimator: android.animation.ValueAnimator? = null
    // 抢占阈值：远小于翻页手势 slop，一旦出现明显向下拖动立即独占，
    // 之后不再把任何事件交给翻页 delegate（避免 delegate 截图覆盖页面导致"页面不跟手"）
    private val pullDownGrabDistance get() = 8f.dpToPx()
    private val pullDownThreshold get() = 100f.dpToPx()

    //跨页复制：手指停在右下角触发翻页，跨页后选区延续；手指持续停在角落则持续跨页（无限跨页，含跨章）
    private var crossPageArmed = false
    private var crossPageFlipped = false
    // 上次 MOVE 时手指是否仍在角落：翻页后仍在角落则直接续上下一轮计时
    private var crossPageInCorner = false
    private val crossPageTimeout = 500L
    private val crossPageRepeatTimeout = 1000L
    private val crossPageRunnable = Runnable {
        if (crossPageArmed) {
            crossPageFlipped = true
            crossPageArmed = false
            onCrossPageFlip()
        }
    }

    /**
     * 下拉松手回弹：页面位移动画复位到 0，结束后清空动效状态
     */
    private fun animatePullDownReset() {
        pullDownAnimator?.cancel()
        pullDownAnimator = android.animation.ValueAnimator.ofFloat(
            curPage.getPullDownOffset(),
            0f
        ).apply {
            duration = 200L
            addUpdateListener {
                curPage.setPullDownOffset(it.animatedValue as Float)
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    curPage.setPullDownOffset(0f)
                    curPage.setPullDownAdding(false)
                    curPage.setPullDownRemoving(false)
                    curPage.setPullDownKeepFull(false)
                }
            })
            start()
        }
    }

    /**
     * 跨页复制：直接切到下一页（无动画，避免被持续触摸打断），并把选区延续到新页顶部。
     * 翻页后手指仍停在角落则继续计时，停留 500ms 再跨下一页；手指移出角落即停（无限跨页，含跨章）
     */
    private fun onCrossPageFlip() {
        if (!isTextSelected) return
        val delegate = pageDelegate ?: return
        if (!delegate.hasNext()) return
        curPage.cancelSelect()
        isTextSelected = false
        fillPage(PageDirection.NEXT)
        // 切页后新页内容已就绪，立即在新页顶部建立选区，起点=页首，后续手指移动继续扩展
        curPage.selectStartMoveIndex(TextPos(0, 0, 0))
        isTextSelected = true
        invalidate()
        // 手指仍停在角落：续上下一轮计时，持续停留持续跨页
        if (crossPageInCorner && !crossPageArmed) {
            crossPageArmed = true
            postDelayed(crossPageRunnable, crossPageRepeatTimeout)
        }
    }

    private val slopSquare by lazy { ViewConfiguration.get(context).scaledTouchSlop }
    private val doubleTapSlop by lazy { ViewConfiguration.get(context).scaledDoubleTapSlop }
    private var pageSlopSquare: Int = slopSquare
    var pageSlopSquare2: Int = pageSlopSquare * pageSlopSquare
    private var pageTouchClick: Int = 0
    private val tlRect = RectF()
    private val tcRect = RectF()
    private val trRect = RectF()
    private val mlRect = RectF()
    private val mcRect = RectF()
    private val mrRect = RectF()
    private val blRect = RectF()
    private val bcRect = RectF()
    private val brRect = RectF()
    private val boundary by lazy { BreakIterator.getWordInstance(Locale.getDefault()) }
    private val upProgressThrottle = throttle(200) { post { upProgress() } }
    private val doubleTapTimeout: Long get() = AppConfig.readAloudDoubleTapTimeout.toLong()
    private var pendingSingleTap: Runnable? = null
    private var pendingSingleTapTime = 0L
    private var pendingSingleTapX = 0f
    private var pendingSingleTapY = 0f
    val autoPager = AutoPager(this)
    val isAutoPage get() = autoPager.isRunning

    init {
        if (!isInEditMode) {
            upBg()
            setWillNotDraw(false)
            upPageAnim()
            upPageSlopSquare()
        }
        addView(nextPage)
        addView(curPage)
        addView(prevPage)
        prevPage.invisible()
        nextPage.invisible()
        curPage.markAsMainView()
        upPageTouchClick()
    }

    private fun setRect9x() {
        tlRect.set(0f + pageTouchClick, 0f, width * 0.33f, height * 0.33f)
        tcRect.set(width * 0.33f, 0f, width * 0.66f, height * 0.33f)
        trRect.set(width * 0.36f, 0f, width.toFloat() - pageTouchClick, height * 0.33f)
        mlRect.set(0f + pageTouchClick, height * 0.33f, width * 0.33f, height * 0.66f)
        mcRect.set(width * 0.33f, height * 0.33f, width * 0.66f, height * 0.66f)
        mrRect.set(width * 0.66f, height * 0.33f, width.toFloat() - pageTouchClick, height * 0.66f)
        blRect.set(0f + pageTouchClick, height * 0.66f, width * 0.33f, height.toFloat())
        bcRect.set(width * 0.33f, height * 0.66f, width * 0.66f, height.toFloat())
        brRect.set(width * 0.66f, height * 0.66f, width.toFloat() - pageTouchClick, height.toFloat())
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        setRect9x()
        prevPage.x = -w.toFloat()
        pageDelegate?.setViewSize(w, h)
        if (w > 0 && h > 0) {
            upBg()
            if (oldw > 0 && oldh > 0 && (w != oldw || h != oldh)) {
                post {
                    upContent(resetPageOffset = false)
                    invalidate()
                }
            }
            callBack.upSystemUiVisibility()
        }
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        pageDelegate?.onDraw(canvas)
        autoPager.onDraw(canvas)
    }

    override fun computeScroll() {
        pageDelegate?.computeScroll()
        autoPager.computeOffset()
    }

    override fun onInterceptTouchEvent(ev: MotionEvent?): Boolean {
        return true
    }

    /**
     * 触摸事件
     */
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val insets = this.rootWindowInsets.getInsetsIgnoringVisibility(
                WindowInsets.Type.mandatorySystemGestures()
            )
            val height = activity?.windowManager?.currentWindowMetrics?.bounds?.height()
            if (height != null) {
                if (event.y > height.minus(insets.bottom)
                    && event.action != MotionEvent.ACTION_UP
                    && event.action != MotionEvent.ACTION_CANCEL
                ) {
                    return true
                }
            }
        }

        //在多点触控时，事件不走ACTION_DOWN分支而产生的特殊事件处理
        if (event.actionMasked == MotionEvent.ACTION_POINTER_DOWN || event.actionMasked == MotionEvent.ACTION_POINTER_UP) {
            pageDelegate?.onTouch(event)
        }
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                val footerActionIndex = footerCenterActionIndexAt(event.x, event.y)
                if (footerActionIndex != null) {
                    footerCenterActionTouching = true
                    footerCenterActionPressedIndex = footerActionIndex
                    return true
                }
                // 音频块进度条拖动/点击优先级最高：按下即 seek，不触发翻页/长按/选区
                val trackHit = curPage.hitAudioTrack(event.x, event.y)
                if (trackHit != null) {
                    audioDragging = true
                    curPage.audioTrackSeek(trackHit, event.x)
                    return true
                }
                callBack.screenOffTimerStart()
                // 记录按下时是否已处于选区：选区状态下手势一律不触发下拉标签
                val wasTextSelected = isTextSelected
                if (isTextSelected) {
                    curPage.cancelSelect()
                    isTextSelected = false
                    pressOnTextSelected = true
                } else {
                    pressOnTextSelected = false
                }
                longPressed = false
                postDelayed(longPressRunnable, longPressTimeout)
                pressDown = true
                isMove = false
                pullDownTriggered = false
                pullDownAnimator?.cancel()
                // 翻页动画进行中（页面未稳定）不允许触发下拉书签：pageDelegate?.onDown() 稍后会
                // 把 isRunning 清零，必须在此处先读取，否则"翻页翻到一半按下并下滑"会误触发
                pullDownArmed = pageDelegate?.isRunning != true &&
                    !isScroll &&
                    !wasTextSelected &&
                    !callBack.isMenuActive() &&
                    context.getPrefBoolean(PreferKey.pageBookmarkPullDown, true)
                if (pullDownArmed) {
                    pullDownStartY = event.y
                    pullDownAdding = !callBack.hasPageBookmarkOnCurrentPage()
                    curPage.setPullDownAdding(pullDownAdding)
                    curPage.setPullDownOffset(0f)
                    curPage.setPullDownKeepFull(false)
                }
                pageDelegate?.onTouch(event)
                pageDelegate?.onDown()
                setStartPoint(event.x, event.y, false)
            }

            MotionEvent.ACTION_MOVE -> {
                if (footerCenterActionTouching) {
                    if (footerCenterActionIndexAt(event.x, event.y) != footerCenterActionPressedIndex) {
                        footerCenterActionPressedIndex = null
                    }
                    return true
                }
                if (pullDownArmed) {
                    val deltaY = event.y - pullDownStartY
                    val deltaX = abs(event.x - startX)
                    if (!pullDownTriggered && !isTextSelected) {
                        // 出现明显向下拖动（且纵向占优）立即独占手势：
                        // 阈值远小于翻页 slop，抢占后再也不把事件交给翻页 delegate，
                        // 避免 delegate 截图覆盖页面导致"页面不跟手"
                        if (deltaY > pullDownGrabDistance && deltaY >= deltaX) {
                            pullDownTriggered = true
                            longPressed = false
                            removeCallbacks(longPressRunnable)
                            pageDelegate?.abortAnim()
                        }
                    }
                    if (pullDownTriggered) {
                        curPage.setPullDownOffset(deltaY.coerceAtLeast(0f))
                        return true
                    }
                    // 已出现向下趋势但尚未达到抢占阈值：拦住事件，不给翻页手势
                    if (deltaY > 0f && deltaY > deltaX) {
                        return true
                    }
                }
                if (audioDragging) {
                    curPage.hitAudioTrack(event.x, event.y)?.let {
                        curPage.audioTrackSeek(it, event.x)
                    }
                    return true
                }
                if (!pressDown) return true
                val absX = abs(startX - event.x)
                val absY = abs(startY - event.y)
                if (!isMove) {
                    isMove = absX > slopSquare || absY > slopSquare
                }
                if (isMove) {
                    longPressed = false
                    removeCallbacks(longPressRunnable)
                    if (isTextSelected) {
                        //跨页复制：手指进入右下角物理正方形区域（边长=长边/8）并停留后自动翻页（滚动模式无跨页概念）。
                        // 手指持续停在角落持续跨页，移出角落即停，再进入再次开始
                        if (!isScroll && context.getPrefBoolean(PreferKey.crossPageCopy, true)) {
                            val cornerSize = max(width, height) / 8f
                            val inCorner = event.x > width - cornerSize &&
                                event.y > height - cornerSize
                            crossPageInCorner = inCorner
                            if (inCorner) {
                                if (!crossPageArmed) {
                                    crossPageArmed = true
                                    postDelayed(crossPageRunnable, crossPageTimeout)
                                }
                            } else {
                                crossPageArmed = false
                                removeCallbacks(crossPageRunnable)
                            }
                        }
                        if (crossPageFlipped) {
                            //跨页后：起点固定新页页首，只移动终点继续扩展选择
                            curPage.selectEndMove(event.x, event.y)
                        } else {
                            selectText(event.x, event.y)
                        }
                        callBack.updateSelectionFinger(event.x, event.y)
                    } else {
                        pageDelegate?.onTouch(event)
                    }
                }
            }

            MotionEvent.ACTION_UP -> {
                if (footerCenterActionTouching) {
                    val pressedIndex = footerCenterActionPressedIndex
                    footerCenterActionTouching = false
                    footerCenterActionPressedIndex = null
                    if (pressedIndex != null && footerCenterActionIndexAt(event.x, event.y) == pressedIndex) {
                        curPage.performFooterCenterAction(pressedIndex)
                    }
                    return true
                }
                dismissSelectionMagnifier()
                crossPageArmed = false
                crossPageFlipped = false
                crossPageInCorner = false
                removeCallbacks(crossPageRunnable)
                if (pullDownArmed && pullDownTriggered) {
                    val deltaY = (event.y - pullDownStartY).coerceAtLeast(0f)
                    pullDownArmed = false
                    pullDownTriggered = false
                    if (deltaY >= pullDownThreshold) {
                        if (pullDownAdding) {
                            if (callBack.addPageBookmark()) {
                                // 添加成功：回弹期间标签保持满尺寸留在原地，直到新书签加载接管
                                curPage.setPullDownKeepFull(true)
                            }
                        } else {
                            callBack.removePageBookmark()
                            // 删除模式：回弹过程中书签消失
                            curPage.setPullDownRemoving(true)
                        }
                    }
                    animatePullDownReset()
                    return true
                }
                pullDownArmed = false
                if (audioDragging) {
                    audioDragging = false
                    curPage.hitAudioTrack(event.x, event.y)?.let {
                        curPage.audioTrackSeek(it, event.x)
                    }
                    return true
                }
                callBack.screenOffTimerStart()
                removeCallbacks(longPressRunnable)
                if (!pressDown) return true
                pressDown = false
                if (!pageDelegate!!.isMoved && !isMove) {
                    if (!longPressed && !pressOnTextSelected) {
                        handleTapUp()
                        return true
                    }
                }
                if (isTextSelected) {
                    callBack.showTextActionMenu()
                } else if (pageDelegate!!.isMoved) {
                    pageDelegate?.onTouch(event)
                }
                pressOnTextSelected = false
            }

            MotionEvent.ACTION_CANCEL -> {
                if (footerCenterActionTouching) {
                    footerCenterActionTouching = false
                    footerCenterActionPressedIndex = null
                    return true
                }
                dismissSelectionMagnifier()
                crossPageArmed = false
                crossPageFlipped = false
                crossPageInCorner = false
                removeCallbacks(crossPageRunnable)
                if (pullDownArmed && pullDownTriggered) {
                    pullDownArmed = false
                    pullDownTriggered = false
                    animatePullDownReset()
                    return true
                }
                pullDownArmed = false
                if (audioDragging) {
                    audioDragging = false
                    return true
                }
                removeCallbacks(longPressRunnable)
                if (!pressDown) return true
                pressDown = false
                if (isTextSelected) {
                    callBack.showTextActionMenu()
                } else if (pageDelegate!!.isMoved) {
                    pageDelegate?.onTouch(event)
                }
                pressOnTextSelected = false
                autoPager.resume()
            }
        }
        return true
    }

    fun cancelSelect(clearSearchResult: Boolean = false) {
        crossPageArmed = false
        crossPageFlipped = false
        crossPageInCorner = false
        removeCallbacks(crossPageRunnable)
        if (isTextSelected) {
            dismissSelectionMagnifier()
            curPage.cancelSelect(clearSearchResult)
            isTextSelected = false
        }
    }

    /**
     * 更新状态栏
     */
    fun upStatusBar() {
        curPage.upStatusBar()
        prevPage.upStatusBar()
        nextPage.upStatusBar()
    }

    /**
     * 保存开始位置
     */
    fun setStartPoint(x: Float, y: Float, invalidate: Boolean = true) {
        startX = x
        startY = y
        lastX = x
        lastY = y
        touchX = x
        touchY = y

        if (invalidate) {
            invalidate()
        }
    }

    /**
     * 保存当前位置
     */
    fun setTouchPoint(x: Float, y: Float, invalidate: Boolean = true) {
        lastX = touchX
        lastY = touchY
        touchX = x
        touchY = y
        if (invalidate) {
            invalidate()
        }
        pageDelegate?.onScroll()
        val offset = touchY - lastY
        touchY -= offset - offset.toInt()
    }

    /**
     * 长按选择
     */
    private fun onLongPress() {
        // 长按进入选区后，本次手势不再允许下拉标签
        pullDownArmed = false
        kotlin.runCatching {
            val handled = curPage.longPress(startX, startY) { textPos: TextPos ->
                isTextSelected = true
                pressOnTextSelected = true
                initialTextPos.upData(textPos)
                val startPos = textPos.copy()
                val endPos = textPos.copy()
                val page = curPage.relativePage(textPos.relativePagePos)
                val stringBuilder = StringBuilder()
                var cIndex = textPos.columnIndex
                var lineStart = textPos.lineIndex
                var lineEnd = textPos.lineIndex
                for (index in textPos.lineIndex - 1 downTo 0) {
                    val textLine = page.getLine(index)
                    if (textLine.isParagraphEnd) {
                        break
                    } else {
                        stringBuilder.insert(0, textLine.text)
                        lineStart -= 1
                        cIndex += textLine.charSize
                    }
                }
                for (index in textPos.lineIndex until page.lineSize) {
                    val textLine = page.getLine(index)
                    stringBuilder.append(textLine.text)
                    lineEnd += 1
                    if (textLine.isParagraphEnd) {
                        break
                    }
                }
                var start: Int
                var end: Int
                boundary.setText(stringBuilder.toString())
                start = boundary.first()
                end = boundary.next()
                while (end != BreakIterator.DONE) {
                    if (cIndex in start until end) {
                        break
                    }
                    start = end
                    end = boundary.next()
                }
                kotlin.run {
                    var ci = 0
                    for (index in lineStart..lineEnd) {
                        val textLine = page.getLine(index)
                        for (j in textLine.columns.indices) {
                            if (ci == start) {
                                startPos.lineIndex = index
                                startPos.columnIndex = j
                            } else if (ci == end - 1) {
                                endPos.lineIndex = index
                                endPos.columnIndex = j
                                return@run
                            }
                            val column = textLine.getColumn(j)
                            if (column is TextBaseColumn) {
                                ci += column.charData.length
                            } else {
                                ci++
                            }
                        }
                    }
                }
                curPage.selectStartMoveIndex(startPos)
                curPage.selectEndMoveIndex(endPos)
            }
            if (handled && curPage.hasNativeSelection()) {
                isTextSelected = true
                pressOnTextSelected = true
                post { callBack.showTextActionMenu() }
            }
        }
    }

    private fun dismissSelectionMagnifier() {
        callBack.dismissSelectionMagnifier()
    }

    private fun handleTapUp() {
        if (!BaseReadAloudService.isRun) {
            cancelPendingSingleTap()
            performSingleTapUp(startX, startY)
            return
        }

        val readAloudPos = curPage.getReadAloudPosByPoint(startX, startY)
        if (readAloudPos == null) {
            cancelPendingSingleTap()
            performSingleTapUp(startX, startY)
            return
        }

        val now = System.currentTimeMillis()
        val pendingTap = pendingSingleTap
        if (
            pendingTap != null &&
            now - pendingSingleTapTime <= doubleTapTimeout &&
            abs(startX - pendingSingleTapX) <= doubleTapSlop &&
            abs(startY - pendingSingleTapY) <= doubleTapSlop
        ) {
            removeCallbacks(pendingTap)
            clearPendingSingleTap()
            callBack.restartFromParagraph(readAloudPos)
            return
        }

        pendingTap?.let { removeCallbacks(it) }
        pendingSingleTapTime = now
        pendingSingleTapX = startX
        pendingSingleTapY = startY
        pendingSingleTap = Runnable {
            val tapX = pendingSingleTapX
            val tapY = pendingSingleTapY
            clearPendingSingleTap()
            performSingleTapUp(tapX, tapY)
        }.also {
            postDelayed(it, doubleTapTimeout)
        }
    }

    private fun cancelPendingSingleTap() {
        pendingSingleTap?.let { removeCallbacks(it) }
        clearPendingSingleTap()
    }

    private fun clearPendingSingleTap() {
        pendingSingleTap = null
        pendingSingleTapTime = 0L
    }

    private fun performSingleTapUp(x: Float, y: Float) {
        if (!curPage.onClick(x, y)) {
            onSingleTapUp(x, y)
        }
    }

    /**
     * 单击
     */
    private fun onSingleTapUp(x: Float, y: Float) {
        when {
            isTextSelected -> Unit
            mcRect.contains(x, y) -> if (!isAbortAnim) {
                click(AppConfig.clickActionMC)
            }

            bcRect.contains(x, y) -> {
                click(AppConfig.clickActionBC)
            }

            blRect.contains(x, y) -> {
                click(AppConfig.clickActionBL)
            }

            brRect.contains(x, y) -> {
                click(AppConfig.clickActionBR)
            }

            mlRect.contains(x, y) -> {
                click(AppConfig.clickActionML)
            }

            mrRect.contains(x, y) -> {
                click(AppConfig.clickActionMR)
            }

            tlRect.contains(x, y) -> {
                click(AppConfig.clickActionTL)
            }

            tcRect.contains(x, y) -> {
                click(AppConfig.clickActionTC)
            }

            trRect.contains(x, y) -> {
                click(AppConfig.clickActionTR)
            }
        }
    }

    /**
     * 点击
     */
    private fun click(action: Int) {
        when (action) {
            0 -> {
                pageDelegate?.dismissSnackBar()
                callBack.showActionMenu()
            }

            1 -> pageDelegate?.nextPageByAnim(defaultAnimationSpeed)
            2 -> pageDelegate?.prevPageByAnim(defaultAnimationSpeed)
            3 -> ReadBook.moveToNextChapter(true)
            4 -> ReadBook.moveToPrevChapter(upContent = true, toLast = false)
            5 -> ReadAloud.prevParagraph(context)
            6 -> ReadAloud.nextParagraph(context)
            7 -> callBack.addBookmark()
            8 -> activity?.showDialogFragment(ContentEditDialog())
            9 -> callBack.changeReplaceRuleState()
            10 -> callBack.openChapterList()
            11 -> callBack.openSearchActivity(null)
            12 -> ReadBook.syncProgress(
                { progress -> callBack.sureNewProgress(progress) },
                { context.longToastOnUi(context.getString(R.string.upload_book_success)) },
                { context.longToastOnUi(context.getString(R.string.sync_book_progress_success)) })

            13 -> {
                if (BaseReadAloudService.isPlay()) {
                    ReadAloud.pause(context)
                } else {
                    ReadAloud.resume(context)
                }
            }

            14 -> callBack.showForceMainMenu()
        }
    }

    /**
     * 选择文本
     */
    private fun selectText(x: Float, y: Float) {
        curPage.selectText(x, y) { textPos ->
            val compare = initialTextPos.compare(textPos)
            when {
                compare > 0 -> {
                    // 反向：先更新静端点（初始位置），最后更新动端点（手指位置），
                    // 保证最后一个 upSelectedX 回调是动杆，放大镜画面中心跟随动杆
                    curPage.selectEndMoveIndex(
                        initialTextPos.relativePagePos,
                        initialTextPos.lineIndex,
                        initialTextPos.columnIndex - 1
                    )
                    curPage.selectStartMoveIndex(textPos)
                }

                else -> {
                    curPage.selectStartMoveIndex(initialTextPos)
                    curPage.selectEndMoveIndex(textPos)
                }
            }
        }
    }

    /**
     * 销毁事件
     */
    fun onDestroy() {
        dismissSelectionMagnifier()
        cancelPendingSingleTap()
        pageDelegate?.onDestroy()
        curPage.cancelSelect()
        invalidateTextPage()
    }

    /**
     * 翻页动画完成后事件
     * @param direction 翻页方向
     */
    fun fillPage(direction: PageDirection): Boolean {
        return when (direction) {
            PageDirection.PREV -> {
                pageFactory.moveToPrev(true)
            }

            PageDirection.NEXT -> {
                pageFactory.moveToNext(true)
            }

            else -> false
        }
    }

    /**
     * 更新翻页动画
     */
    fun upPageAnim(upRecorder: Boolean = false) {
        isScroll = ReadBook.pageAnim() == 3
        ChapterProvider.upLayout()
        when (ReadBook.pageAnim()) {
            PageAnim.coverPageAnim -> if (pageDelegate !is CoverPageDelegate) {
                pageDelegate = CoverPageDelegate(this)
            }

            PageAnim.linkedCoverPageAnim -> if (pageDelegate !is LinkedCoverPageDelegate) {
                pageDelegate = LinkedCoverPageDelegate(this)
            }

            PageAnim.slidePageAnim -> if (pageDelegate !is SlidePageDelegate) {
                pageDelegate = SlidePageDelegate(this)
            }

            PageAnim.simulationPageAnim -> if (pageDelegate !is SimulationPageDelegate) {
                pageDelegate = SimulationPageDelegate(this)
            }

            PageAnim.scrollPageAnim -> if (pageDelegate !is ScrollPageDelegate) {
                pageDelegate = ScrollPageDelegate(this)
            }

            else -> if (pageDelegate !is NoAnimPageDelegate) {
                pageDelegate = NoAnimPageDelegate(this)
            }
        }
        (pageDelegate as? ScrollPageDelegate)?.noAnim = AppConfig.noAnimScrollPage
        if (upRecorder) {
            (pageDelegate as? HorizontalPageDelegate)?.upRecorder()
            autoPager.upRecorder()
        }
        pageDelegate?.setViewSize(width, height)
        if (isScroll) {
            curPage.setAutoPager(autoPager)
        } else {
            curPage.setAutoPager(null)
        }
        curPage.setIsScroll(isScroll)
    }

    /**
     * 更新阅读内容
     * @param relativePosition 相对位置 -1 上一页 0 当前页 1 下一页
     * @param resetPageOffset 滚动阅读是是否重置位置
     */
    override fun upContent(relativePosition: Int, resetPageOffset: Boolean) {
        post {
            curPage.setContentDescription(pageFactory.curPage.text)
        }
        if (isScroll && !isAutoPage) {
            if (relativePosition == 0) {
                curPage.setContent(pageFactory.curPage, resetPageOffset)
            } else {
                curPage.invalidateContentView()
            }
        } else {
            when (relativePosition) {
                -1 -> prevPage.setContent(pageFactory.prevPage)
                1 -> nextPage.setContent(pageFactory.nextPage)
                else -> {
                    curPage.setContent(pageFactory.curPage, resetPageOffset)
                    nextPage.setContent(pageFactory.nextPage)
                    prevPage.setContent(pageFactory.prevPage)
                }
            }
        }
        callBack.screenOffTimerStart()
    }

    private fun upProgress() {
        curPage.setProgress(pageFactory.curPage)
    }

    /**
     * 更新滑动距离
     */
    fun upPageSlopSquare() {
        val pageTouchSlop = AppConfig.pageTouchSlop
        this.pageSlopSquare = if (pageTouchSlop == 0) slopSquare else pageTouchSlop
        pageSlopSquare2 = this.pageSlopSquare * this.pageSlopSquare
    }

    /**
     * 更新边缘点击阈值
     */
    fun upPageTouchClick() {
        this.pageTouchClick = AppConfig.pageTouchClick
        setRect9x()
    }

    fun setBookmarks(list: List<Bookmark>) {
        prevPage.setBookmarks(list)
        curPage.setBookmarks(list)
        nextPage.setBookmarks(list)
    }

    /**
     * 更新样式
     */
    fun upStyle() {
        ChapterProvider.upStyle()
        curPage.upStyle()
        prevPage.upStyle()
        nextPage.upStyle()
        if (ReadBookConfig.isNineBgImg) {
            upBg()
        }
    }

    /**
     * 更新背景
     */
    fun upBg() {
        ReadBookConfig.upBg(width, height)
        curPage.upBg()
        prevPage.upBg()
        nextPage.upBg()
    }

    /**
     * 更新背景透明度
     */
    fun upBgAlpha() {
        curPage.upBgAlpha()
        prevPage.upBgAlpha()
        nextPage.upBgAlpha()
    }

    /**
     * 更新时间信息
     */
    fun upTime() {
        curPage.upTime()
        prevPage.upTime()
        nextPage.upTime()
    }

    /**
     * 更新电量信息
     */
    fun upBattery(battery: Int) {
        curPage.upBattery(battery)
        prevPage.upBattery(battery)
        nextPage.upBattery(battery)
    }

    fun setFooterCenterAction(text: CharSequence?, action: (() -> Unit)?) {
        curPage.setFooterCenterAction(text, action)
        prevPage.setFooterCenterAction(text, action)
        nextPage.setFooterCenterAction(text, action)
    }

    fun setFooterCenterActions(actions: List<FooterCenterAction>) {
        curPage.setFooterCenterActions(actions)
        prevPage.setFooterCenterActions(actions)
        nextPage.setFooterCenterActions(actions)
    }

    private fun footerCenterActionIndexAt(x: Float, y: Float): Int? {
        return curPage.footerCenterActionIndexAt(x - curPage.x, y - curPage.y)
    }

    /**
     * 从选择位置开始朗读
     */
    suspend fun aloudStartSelect() {
        val selectStartPos = curPage.selectStartPos
        ReadBook.attachReadAloudPage()
        var pagePos = selectStartPos.relativePagePos
        val line = selectStartPos.lineIndex
        val column = selectStartPos.columnIndex
        while (pagePos > 0) {
            if (!ReadBook.moveToNextPage(fromReadAloud = true)) {
                ReadBook.moveToNextChapterAwait(false, fromReadAloud = true)
            }
            pagePos--
        }
        val startPos = curPage.textPage.getPosByLineColumn(line, column)
        ReadBook.readAloud(startPos = startPos)
    }

    /**
     * @return 选择的文本
     */
    fun getSelectText(): String {
        return curPage.selectedText
    }

    fun getCurVisiblePage(): TextPage {
        return curPage.getCurVisiblePage()
    }

    fun getReadAloudPos(): Pair<Int, TextLine>? {
        return curPage.getReadAloudPos()
    }

    fun invalidateTextPage() {
        if (!AppConfig.optimizeRender) {
            return
        }
        pageFactory.run {
            prevPage.invalidateAll()
            curPage.invalidateAll()
            nextPage.invalidateAll()
            nextPlusPage.invalidateAll()
        }
    }

    /** 清绘制缓存并重绘，让朗读红字按最新 aloudPosition 投影重新现算；
     *  滚动模式可视区可能跨三页，三页缓存都要失效。 */
    fun invalidateReadAloudHighlight() {
        if (isScroll) {
            for (relativePos in 0..2) {
                curPage.relativePage(relativePos).invalidateAll()
            }
        } else {
            curPage.textPage.invalidateAll()
        }
        curPage.invalidateContentView()
    }

    fun onScrollAnimStart() {
        autoPager.pause()
    }

    fun onScrollAnimStop() {
        autoPager.resume()
    }

    fun onPageChange() {
        autoPager.reset()
        submitRenderTask()
    }

    fun submitRenderTask() {
        if (!AppConfig.optimizeRender) {
            return
        }
        curPage.submitRenderTask()
    }

    fun isLongScreenShot(): Boolean {
        return curPage.isLongScreenShot()
    }

    override fun onLayoutPageCompleted(index: Int, page: TextPage) {
        upProgressThrottle.invoke()
    }

    override val currentChapter: TextChapter?
        get() {
            return if (callBack.isInitFinish) ReadBook.textChapter(0) else null
        }

    override val nextChapter: TextChapter?
        get() {
            return if (callBack.isInitFinish) ReadBook.textChapter(1) else null
        }

    override val prevChapter: TextChapter?
        get() {
            return if (callBack.isInitFinish) ReadBook.textChapter(-1) else null
        }

    override fun hasNextChapter(): Boolean {
        return ReadBook.durChapterIndex < ReadBook.simulatedChapterSize - 1
    }

    override fun hasPrevChapter(): Boolean {
        return ReadBook.durChapterIndex > 0
    }

    interface CallBack {
        val isInitFinish: Boolean
        fun showActionMenu()
        fun showForceMainMenu()
        fun screenOffTimerStart()
        fun showTextActionMenu()
        fun autoPageStop()
        fun openChapterList()
        fun addBookmark()
        fun addPageBookmark(): Boolean
        fun removePageBookmark()
        fun hasPageBookmarkOnCurrentPage(): Boolean
        fun dismissSelectionMagnifier()
        fun updateSelectionFinger(x: Float, y: Float)
        fun isMenuActive(): Boolean
        fun changeReplaceRuleState()
        fun openSearchActivity(searchWord: String?)
        fun upSystemUiVisibility()
        fun sureNewProgress(progress: BookProgress)
        fun restartFromParagraph(position: Pair<Int, TextLine>)
    }
}
