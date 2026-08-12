package io.legado.app.ui.book.read.page

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Build
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.widget.ScrollView
import android.widget.TextView
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.constant.PreferKey
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.Bookmark
import io.legado.app.data.entities.BookmarkStyle
import io.legado.app.help.PaperInkHelper
import io.legado.app.help.book.isOnLineTxt
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.help.illustration.IllustrationHelp
import io.legado.app.help.illustration.AudioBlockPlayer
import io.legado.app.help.illustration.imageSrcsFromJson
import io.legado.app.help.illustration.pdfRectsFromJson
import io.legado.app.help.book.isPdf
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookIllustration
import io.legado.app.lib.dialogs.alert
import io.legado.app.model.ReadBook
import io.legado.app.model.localBook.EpubFile
import io.legado.app.ui.association.OpenUrlConfirmActivity
import io.legado.app.ui.book.read.page.delegate.PageDelegate
import io.legado.app.ui.book.read.page.entities.TextLine
import io.legado.app.ui.book.read.page.entities.TextPage
import io.legado.app.ui.book.read.page.entities.TextPos
import io.legado.app.ui.book.read.page.entities.column.BaseColumn
import io.legado.app.ui.book.read.page.entities.column.ButtonColumn
import io.legado.app.ui.book.read.page.entities.column.TextHtmlColumn
import io.legado.app.ui.book.read.page.entities.column.ImageColumn
import io.legado.app.ui.book.read.page.entities.column.ReviewColumn
import io.legado.app.ui.book.read.page.entities.column.TextBaseColumn
import io.legado.app.ui.book.read.page.entities.column.TextColumn
import io.legado.app.ui.book.read.page.provider.ChapterProvider
import io.legado.app.ui.book.read.page.provider.TextPageFactory
import io.legado.app.ui.widget.dialog.PhotoDialog
import io.legado.app.utils.activity
import io.legado.app.utils.dpToPx
import io.legado.app.utils.getCompatColor
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.getPrefInt
import io.legado.app.utils.setHtml
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.spToPx
import io.legado.app.utils.startActivity
import io.legado.app.utils.toastOnUi
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.backgroundColor
import splitties.init.appCtx
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

/**
 * 阅读内容视图
 */
class ContentTextView(context: Context, attrs: AttributeSet?) : View(context, attrs) {
    var selectAble = AppConfig.textSelectAble
    val selectedPaint by lazy {
        Paint().apply {
            color = context.getCompatColor(R.color.btn_bg_press_2)
            style = Paint.Style.FILL
        }
    }
    private var callBack: CallBack
    private val visibleRect = ChapterProvider.visibleRect
    val selectStart = TextPos(0, -1, -1)
    private val selectEnd = TextPos(0, -1, -1)
    val selectEndPos: TextPos
        get() = selectEnd
    var textPage: TextPage = TextPage()
        private set
    var bookmarks: List<Bookmark> = emptyList()
        private set
    private val bubbleDismissed = mutableSetOf<Long>()
    private val bubbleShown = mutableSetOf<Long>()
    private val bubbleOffsets = mutableMapOf<Long, Pair<Float, Float>>()
    private val pageBookmarkAnchorCache = HashMap<TextPage, Map<Long, RectF>>()
    private val bubbleLayoutCache = HashMap<Long, StaticLayout>()
    private var bubbleShowAllPref = true
    private var bubbleNoStyleClickPref = true
    private var bubbleBgAlpha = 80
    private var bubbleBgColor = 0
    private val bubbleBgPaint = Paint()
    private val bubbleStrokePaint = Paint()
    private val bubbleArrowPaint = Paint()
    private val bubbleTextPaint = TextPaint()
    private val bubbleArrowPath = Path()
    private val bubbleCornerRadius = 8f.dpToPx()
    private val bubblePadding = 10f.dpToPx()
    private val bubbleGap = 6f.dpToPx()
    private val bubbleMaxWidth = 280f.dpToPx()
    private val bubbleArrowSize = 6f.dpToPx()
    var isMainView = false
    var longScreenshot = false
    var reverseStartCursor = false
    var reverseEndCursor = false

    //滚动参数
    private val pageFactory get() = callBack.pageFactory
    private val pageDelegate get() = callBack.pageDelegate
    private var pageOffset = 0
    private var backgroundScrollOffset = 0
    private var scrollFollowBackgroundDrawable: ScrollFollowBackgroundDrawable? = null
    private var autoPager: AutoPager? = null
    private var isScroll = false
    private val renderRunnable by lazy { Runnable { preRenderPage() } }
    private var lastClickTime = 0L
    private var doubleClick = false
    private var nativeSelectedText: String? = null
    private var nativeSelectionRect: RectF? = null
    private val paperPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    //绘制图片的paint
    val imagePaint by lazy {
        Paint().apply {
            isAntiAlias = AppConfig.useAntiAlias
        }
    }

    init {
        callBack = activity as CallBack
        // 音频块播放状态/进度变化时重绘，保证进度条跟随
        AudioBlockPlayer.onStateChange = { postInvalidate() }
    }

    /**
     * 设置内容
     */
    fun setContent(textPage: TextPage, resetBackgroundOffset: Boolean = true) {
        if (this.textPage !== textPage) {
            nativeSelectedText = null
            nativeSelectionRect = null
        }
        this.textPage = textPage
        if (resetBackgroundOffset) {
            backgroundScrollOffset = 0
        }
        // 非滑动翻页动画需要同步重绘，不然翻页可能会出现闪烁
        if (isScroll) {
            postInvalidate()
        } else {
            invalidate()
        }
    }

    fun setBookmarks(list: List<Bookmark>) {
        bookmarks = list
        bubbleShowAllPref = context.getPrefBoolean(PreferKey.bookmarkNoteBubbleShowAll, true)
        bubbleNoStyleClickPref = context.getPrefBoolean(PreferKey.bookmarkNoteBubbleOnNoStyleClick, true)
        bubbleBgAlpha = context.getPrefInt(PreferKey.bookmarkNoteBubbleBgAlpha, 80).coerceIn(0, 100)
        bubbleBgColor = context.getPrefInt(PreferKey.bookmarkNoteBubbleColor, 0)
        bubbleLayoutCache.clear()
        invalidate()
    }

    // ==================== 书签备注气泡 ====================

    private data class BubbleData(
        val bookmark: Bookmark,
        val rect: RectF,
        val anchor: RectF
    )

    private fun shouldShowBubble(bookmark: Bookmark): Boolean {
        if (bookmark.content.isBlank()) return false
        return if (bubbleShowAllPref) {
            !bubbleDismissed.contains(bookmark.time)
        } else {
            bubbleShown.contains(bookmark.time)
        }
    }

    private fun onBookmarkBodyClick(bookmark: Bookmark) {
        if (shouldShowBubble(bookmark)) {
            bubbleDismissed.add(bookmark.time)
            bubbleShown.remove(bookmark.time)
            bubbleOffsets.remove(bookmark.time)
        } else {
            if (bookmark.style == BookmarkStyle.NONE && !bubbleNoStyleClickPref) return
            bubbleDismissed.remove(bookmark.time)
            bubbleShown.add(bookmark.time)
            bubbleOffsets.remove(bookmark.time)
        }
        invalidate()
    }

    private fun onBubbleClick(bookmark: Bookmark) {
        bubbleDismissed.add(bookmark.time)
        bubbleShown.remove(bookmark.time)
        bubbleOffsets.remove(bookmark.time)
        invalidate()
    }

    private fun drawBookmarkBubbles(canvas: Canvas) {
        if (bookmarks.isEmpty()) return
        val bubbles = collectBubbles()
        bubbles.forEach { drawBubble(canvas, it) }
    }

    private fun collectBubbles(): List<BubbleData> {
        val result = arrayListOf<BubbleData>()
        if (bookmarks.isEmpty()) return result
        val last = if (callBack.isScroll) 2 else 0
        for (rel in 0..last) {
            val offset = relativeOffset(rel)
            if (rel > 0 && offset >= ChapterProvider.visibleHeight) break
            val page = relativePage(rel)
            for (bookmark in bookmarks) {
                if (!shouldShowBubble(bookmark)) continue
                val anchor = findBookmarkAnchorRect(page, bookmark.time, offset) ?: continue
                val rect = findBubbleRect(page, anchor, bookmark, offset, result)
                result.add(BubbleData(bookmark, rect, anchor))
            }
        }
        return result
    }

    private fun findBookmarkAnchorRect(page: TextPage, time: Long, offset: Float): RectF? {
        val anchors = pageBookmarkAnchorCache.getOrPut(page) {
            val map = HashMap<Long, RectF>()
            for (line in page.lines) {
                for (column in line.columns) {
                    if (column is TextColumn && column.bookmarkTime != 0L) {
                        map.putIfAbsent(
                            column.bookmarkTime,
                            RectF(column.start, line.lineTop, column.end, line.lineBottom)
                        )
                    }
                }
            }
            map
        }
        return anchors[time]?.let {
            RectF(it.left, it.top + offset, it.right, it.bottom + offset)
        }
    }

    private fun findBubbleRect(
        page: TextPage,
        anchor: RectF,
        bookmark: Bookmark,
        offset: Float,
        bubbles: List<BubbleData>
    ): RectF {
        val leftBound = visibleRect.left
        val topBound = visibleRect.top
        val rightBound = visibleRect.right
        val bottomBound = visibleRect.bottom
        val pageWidth = rightBound - leftBound
        val maxBubbleWidth = min(
            bubbleMaxWidth,
            (pageWidth - bubblePadding * 2 - 2 * bubbleGap).coerceAtLeast(1f)
        )
        // 缓存气泡布局与尺寸，滚动时避免每帧重建 StaticLayout/测量文本
        val bubbleW: Float
        val bubbleH: Float
        val cachedLayout = bubbleLayoutCache[bookmark.time]
        if (cachedLayout != null) {
            bubbleW = (cachedLayout.width + bubblePadding * 2)
                .coerceAtMost(maxBubbleWidth)
            bubbleH = cachedLayout.height + bubblePadding * 2
        } else {
            bubbleTextPaint.textSize = 13f.spToPx()
            bubbleTextPaint.color = context.getCompatColor(R.color.primaryText)
            bubbleTextPaint.isAntiAlias = true
            val idealTextWidth =
                bookmark.content.lines().maxOfOrNull { bubbleTextPaint.measureText(it) } ?: 0f
            val w = (idealTextWidth + bubblePadding * 2).coerceAtMost(maxBubbleWidth)
            val layout = buildBubbleLayout(
                bookmark.content,
                (w - bubblePadding * 2).toInt().coerceAtLeast(1)
            )
            bubbleLayoutCache[bookmark.time] = layout
            bubbleW = (layout.width + bubblePadding * 2).coerceAtMost(maxBubbleWidth)
            bubbleH = layout.height + bubblePadding * 2
        }

        // 已确定过位置的书签，保持其与正文的相对偏移，滚动/翻页时仅随正文平移
        bubbleOffsets[bookmark.time]?.let { (dx, dy) ->
            return RectF(
                anchor.left + dx,
                anchor.top + dy,
                anchor.left + dx + bubbleW,
                anchor.top + dy + bubbleH
            )
        }

        fun clamp(rect: RectF): RectF {
            val x = rect.left.coerceIn(leftBound, (rightBound - rect.width()).coerceAtLeast(leftBound))
            val y = rect.top.coerceIn(topBound, (bottomBound - rect.height()).coerceAtLeast(topBound))
            return RectF(x, y, x + rect.width(), y + rect.height())
        }

        fun free(rect: RectF): Boolean {
            if (rect.left < leftBound || rect.top < topBound ||
                rect.right > rightBound || rect.bottom > bottomBound
            ) {
                return false
            }
            if (overlapsText(page, rect, offset)) return false
            bubbles.forEach {
                if (RectF.intersects(it.rect, rect)) return false
            }
            return true
        }

        val candidates = arrayListOf<RectF>()
        // 上方：左、中、右
        candidates.add(
            RectF(
                anchor.left,
                anchor.top - bubbleH - bubbleGap,
                anchor.left + bubbleW,
                anchor.top - bubbleGap
            )
        )
        candidates.add(
            RectF(
                anchor.centerX() - bubbleW / 2,
                anchor.top - bubbleH - bubbleGap,
                anchor.centerX() + bubbleW / 2,
                anchor.top - bubbleGap
            )
        )
        candidates.add(
            RectF(
                anchor.right - bubbleW,
                anchor.top - bubbleH - bubbleGap,
                anchor.right,
                anchor.top - bubbleGap
            )
        )
        // 下方：左、中、右
        candidates.add(
            RectF(
                anchor.left,
                anchor.bottom + bubbleGap,
                anchor.left + bubbleW,
                anchor.bottom + bubbleGap + bubbleH
            )
        )
        candidates.add(
            RectF(
                anchor.centerX() - bubbleW / 2,
                anchor.bottom + bubbleGap,
                anchor.centerX() + bubbleW / 2,
                anchor.bottom + bubbleGap + bubbleH
            )
        )
        candidates.add(
            RectF(
                anchor.right - bubbleW,
                anchor.bottom + bubbleGap,
                anchor.right,
                anchor.bottom + bubbleGap + bubbleH
            )
        )
        // 左侧、右侧（垂直居中）
        candidates.add(
            RectF(
                anchor.left - bubbleW - bubbleGap,
                anchor.centerY() - bubbleH / 2,
                anchor.left - bubbleGap,
                anchor.centerY() + bubbleH / 2
            )
        )
        candidates.add(
            RectF(
                anchor.right + bubbleGap,
                anchor.centerY() - bubbleH / 2,
                anchor.right + bubbleGap + bubbleW,
                anchor.centerY() + bubbleH / 2
            )
        )

        for (candidate in candidates) {
            val clamped = clamp(candidate)
            if (free(clamped)) {
                bubbleOffsets[bookmark.time] = (clamped.left - anchor.left) to (clamped.top - anchor.top)
                return clamped
            }
        }
        // 找不到空位：允许重叠，放在锚点正上方
        val fallback = clamp(
            RectF(
                anchor.centerX() - bubbleW / 2,
                anchor.top - bubbleH - bubbleGap,
                anchor.centerX() + bubbleW / 2,
                anchor.top - bubbleGap
            )
        )
        bubbleOffsets[bookmark.time] = (fallback.left - anchor.left) to (fallback.top - anchor.top)
        return fallback
    }

    private fun overlapsText(page: TextPage, rect: RectF, offset: Float): Boolean {
        for (line in page.lines) {
            val lineRect = RectF(
                line.lineStart,
                line.lineTop + offset,
                line.lineEnd,
                line.lineBottom + offset
            )
            if (!RectF.intersects(lineRect, rect)) continue
            for (column in line.columns) {
                val columnRect = RectF(
                    column.start,
                    line.lineTop + offset,
                    column.end,
                    line.lineBottom + offset
                )
                if (RectF.intersects(columnRect, rect)) {
                    return true
                }
            }
        }
        return false
    }

    private fun buildBubbleLayout(text: String, maxWidth: Int): StaticLayout {
        bubbleTextPaint.textSize = 13f.spToPx()
        bubbleTextPaint.color = context.getCompatColor(R.color.primaryText)
        bubbleTextPaint.isAntiAlias = true
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            StaticLayout.Builder.obtain(text, 0, text.length, bubbleTextPaint, maxWidth)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(0f, 1.05f)
                .build()
        } else {
            @Suppress("DEPRECATION")
            StaticLayout(text, bubbleTextPaint, maxWidth, Layout.Alignment.ALIGN_NORMAL, 1.05f, 0f, false)
        }
    }

    private fun drawBubble(canvas: Canvas, data: BubbleData) {
        val rect = data.rect
        val bgColor = if (bubbleBgColor != 0) {
            bubbleBgColor
        } else {
            // 自动：取阅读页背景主色（背景图片平均色），无背景时用主题背景色
            ReadBookConfig.bgMeanColor.takeIf { it != 0 } ?: appCtx.backgroundColor
        }
        bubbleBgPaint.color = Color.argb(
            Color.alpha(bgColor) * bubbleBgAlpha / 100,
            Color.red(bgColor),
            Color.green(bgColor),
            Color.blue(bgColor)
        )
        bubbleBgPaint.style = Paint.Style.FILL
        bubbleBgPaint.isAntiAlias = true
        canvas.drawRoundRect(rect, bubbleCornerRadius, bubbleCornerRadius, bubbleBgPaint)

        bubbleStrokePaint.color = appCtx.accentColor
        bubbleStrokePaint.style = Paint.Style.STROKE
        bubbleStrokePaint.strokeWidth = 1f.dpToPx()
        bubbleStrokePaint.isAntiAlias = true
        canvas.drawRoundRect(rect, bubbleCornerRadius, bubbleCornerRadius, bubbleStrokePaint)

        val layout = bubbleLayoutCache[data.bookmark.time]
            ?: buildBubbleLayout(
                data.bookmark.content,
                (rect.width() - bubblePadding * 2).toInt().coerceAtLeast(1)
            )
        canvas.save()
        canvas.translate(rect.left + bubblePadding, rect.top + bubblePadding)
        layout.draw(canvas)
        canvas.restore()

        drawBubbleArrow(canvas, rect, data.anchor)
    }

    private fun drawBubbleArrow(canvas: Canvas, bubble: RectF, anchor: RectF) {
        val startX: Float
        val startY: Float
        val endX = anchor.centerX()
        val endY: Float
        if (bubble.bottom <= anchor.top) {
            startX = bubble.centerX()
            startY = bubble.bottom
            endY = anchor.top
        } else if (bubble.top >= anchor.bottom) {
            startX = bubble.centerX()
            startY = bubble.top
            endY = anchor.bottom
        } else if (bubble.right <= anchor.left) {
            startX = bubble.right
            startY = bubble.centerY()
            endY = anchor.left
        } else {
            startX = bubble.left
            startY = bubble.centerY()
            endY = anchor.right
        }
        bubbleArrowPaint.color = appCtx.accentColor
        bubbleArrowPaint.style = Paint.Style.STROKE
        bubbleArrowPaint.strokeWidth = 1.5f.dpToPx()
        bubbleArrowPaint.pathEffect = DashPathEffect(floatArrayOf(5f.dpToPx(), 4f.dpToPx()), 0f)
        bubbleArrowPaint.isAntiAlias = true
        canvas.drawLine(startX, startY, endX, endY, bubbleArrowPaint)
        // 箭头小三角
        val angle = Math.atan2((endY - startY).toDouble(), (endX - startX).toDouble())
        val size = bubbleArrowSize
        bubbleArrowPath.reset()
        bubbleArrowPath.moveTo(endX, endY)
        bubbleArrowPath.lineTo(
            (endX - size * Math.cos(angle - Math.PI / 6)).toFloat(),
            (endY - size * Math.sin(angle - Math.PI / 6)).toFloat()
        )
        bubbleArrowPath.lineTo(
            (endX - size * Math.cos(angle + Math.PI / 6)).toFloat(),
            (endY - size * Math.sin(angle + Math.PI / 6)).toFloat()
        )
        bubbleArrowPath.close()
        bubbleArrowPaint.pathEffect = null
        bubbleArrowPaint.style = Paint.Style.FILL
        canvas.drawPath(bubbleArrowPath, bubbleArrowPaint)
    }

    private fun findBubbleAt(x: Float, y: Float): Bookmark? {
        val bubbles = collectBubbles()
        bubbles.forEach {
            if (it.rect.contains(x, y)) return it.bookmark
        }
        return null
    }

    private fun findBookmarkAt(x: Float, y: Float): Bookmark? {
        var result: Bookmark? = null
        touch(x, y) { _, _, _, _, column ->
            if (column is TextColumn && column.bookmarkTime != 0L) {
                result = bookmarks.firstOrNull { it.time == column.bookmarkTime }
            }
        }
        return result
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (!isMainView) return
        ChapterProvider.upViewSize(w, h)
        if (!textPage.isNativeEpubPage()) {
            textPage.format()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        autoPager?.onDraw(canvas)
        if (longScreenshot) {
            canvas.translate(0f, scrollY.toFloat())
        }
        drawScrollFollowBackground(canvas)
        drawPaperEffect(canvas)
        check(!visibleRect.isEmpty) { "visibleRect 为空" }
        if (!textPage.hasEpubBackground()) {
            canvas.clipRect(visibleRect)
        }
        drawPage(canvas)
        drawBookmarkBubbles(canvas)
    }

    /**
     * 绘制页面
     */
    private fun drawPage(canvas: Canvas) {
        var relativeOffset = relativeOffset(0)
        textPage.draw(this, canvas, relativeOffset)
        if (callBack.isScroll) {
            if (!pageFactory.hasNext()) {
                nativeSelectionRect?.let { rect ->
                    canvas.drawRect(rect, selectedPaint)
                }
                return
            }
            val textPage1 = relativePage(1)
            relativeOffset += textPage.height
            textPage1.draw(this, canvas, relativeOffset)
            if (pageFactory.hasNextPlus()) {
                relativeOffset += textPage1.height
                if (relativeOffset < ChapterProvider.visibleHeight) {
                    val textPage2 = relativePage(2)
                    textPage2.draw(this, canvas, relativeOffset)
                }
            }
        }
        nativeSelectionRect?.let { rect ->
            canvas.drawRect(rect, selectedPaint)
        }
    }

    override fun computeScroll() {
        pageDelegate?.computeScroll()
        autoPager?.computeOffset()
    }

    /**
     * 滚动事件
     * pageOffset 向上滚动 减小 向下滚动 增大
     * pageOffset 范围 0 ~ -textPage.height 大于0为上一页，小于-textPage.height为下一页
     * 以内容显示区域顶端为界，pageOffset的绝对值为textPage上方的高度
     * pageOffset + textPage.height 为 textPage 下方的高度
     */
    fun scroll(mOffset: Int) {
        val startPageOffset = pageOffset
        var backgroundDelta = mOffset
        pageOffset += mOffset
        if (longScreenshot) {
            scrollY += -mOffset
        }
        if (!pageFactory.hasPrev() && pageOffset > 0) {
            pageOffset = 0
            backgroundDelta = pageOffset - startPageOffset
            pageDelegate?.abortAnim()
        } else if (!pageFactory.hasNext()
            && pageOffset < 0
            && pageOffset + textPage.height < ChapterProvider.visibleHeight
        ) {
            val offset = (ChapterProvider.visibleHeight - textPage.height).toInt()
            pageOffset = min(0, offset)
            backgroundDelta = pageOffset - startPageOffset
            pageDelegate?.abortAnim()
        } else if (pageOffset > 0) {
            if (pageFactory.moveToPrev(true)) {
                pageOffset -= textPage.height.toInt()
            } else {
                pageOffset = 0
                backgroundDelta = pageOffset - startPageOffset
                pageDelegate?.abortAnim()
            }
        } else if (pageOffset < -textPage.height) {
            val height = textPage.height
            if (pageFactory.moveToNext(upContent = true)) {
                pageOffset += height.toInt()
            } else {
                pageOffset = -height.toInt()
                backgroundDelta = pageOffset - startPageOffset
                pageDelegate?.abortAnim()
            }
        }
        backgroundScrollOffset += backgroundDelta
        postInvalidate()
    }

    fun submitRenderTask() {
        renderThread.submit(renderRunnable)
    }

    private fun preRenderPage() {
        val view = this
        var invalidate = false
        pageFactory.run {
            if (hasPrev() && prevPage.render(view)) {
                invalidate = true
            }
            if (curPage.render(view)) {
                invalidate = true
            }
            if (hasNext() && nextPage.render(view) && callBack.isScroll) {
                invalidate = true
            }
            if (hasNextPlus() && nextPlusPage.render(view) && callBack.isScroll
                && relativeOffset(2) < ChapterProvider.visibleHeight
            ) {
                invalidate = true
            }
            if (invalidate) {
                postInvalidate()
                pageDelegate?.postInvalidate()
            }
        }
    }

    /**
     * 重置滚动位置
     */
    fun resetPageOffset() {
        pageOffset = 0
        backgroundScrollOffset = 0
        invalidateBackgroundHost()
    }

    fun getBackgroundOffset(): Int {
        return backgroundScrollOffset
    }

    fun setScrollFollowBackground(bitmap: Bitmap?, alpha: Int) {
        scrollFollowBackgroundDrawable = bitmap?.let {
            ScrollFollowBackgroundDrawable(it) { getBackgroundOffset() }.apply {
                setAlpha(alpha)
            }
        }
        postInvalidate()
    }

    fun setScrollFollowBackgroundAlpha(alpha: Int) {
        scrollFollowBackgroundDrawable?.setAlpha(alpha)
        postInvalidate()
    }

    private fun invalidateBackgroundHost() {
        postInvalidateOnAnimation()
    }

    private fun drawScrollFollowBackground(canvas: Canvas) {
        scrollFollowBackgroundDrawable?.let {
            it.setBounds(0, 0, width, height)
            it.draw(canvas)
        }
    }

    private fun drawPaperEffect(canvas: Canvas) {
        PaperInkHelper.drawBackground(canvas, width, height, paperPaint)
    }

    fun drawTextWithPaperInk(
        canvas: Canvas,
        text: String,
        start: Int,
        end: Int,
        x: Float,
        y: Float,
        paint: Paint,
        enableBlend: Boolean = true
    ) {
        PaperInkHelper.drawText(canvas, text, start, end, x, y, paint, enableBlend)
    }

    fun drawTextWithPaperInk(
        canvas: Canvas,
        text: String,
        x: Float,
        y: Float,
        paint: Paint,
        enableBlend: Boolean = true
    ) {
        drawTextWithPaperInk(canvas, text, 0, text.length, x, y, paint, enableBlend)
    }

    /**
     * 长按
     */
    fun longPress(
        x: Float,
        y: Float,
        select: (textPos: TextPos) -> Unit,
    ): Boolean {
        if (isNativeEpubHit(x, y)) {
            return true
        }
        var handled = false
        touch(x, y) { _, textPos, _, textLine, column ->
            when (column) {
                is ImageColumn -> {
                    val pdfHit = hitPdfIllustration(x, y, textLine, column)
                    callBack.onImageLongPress(x, y, pdfHit?.second ?: column.src)
                }
                is TextColumn -> {
                    if (!selectAble) return@touch
                    column.selected = true
                    select(textPos)
                    handled = true
                }
                is TextHtmlColumn -> {
                    if (!selectAble) return@touch
                    column.selected = true
                    select(textPos)
                    handled = true
                }
            }
        }
        return handled
    }

    /**
     * PDF 阅读页热区命中：整页位图内按归一化坐标匹配配图记录，
     * 返回 (配图记录, 命中的配图 src)。
     */
    private fun hitPdfIllustration(
        x: Float,
        y: Float,
        textLine: TextLine,
        column: ImageColumn
    ): Pair<BookIllustration, String>? {
        val book = ReadBook.book ?: return null
        if (!book.isPdf) return null
        val page = column.src.toIntOrNull() ?: return null
        val width = (column.end - column.start).coerceAtLeast(1f)
        val height = (textLine.lineBottom - textLine.lineTop).coerceAtLeast(1f)
        val relX = (x - column.start) / width
        val relY = (y - textLine.lineTop) / height
        val records = appDb.bookIllustrationDao.getByBook(book.bookUrl)
            .filter { it.pdfPage == page }
        records.forEach { record ->
            val rects = record.pdfRectsFromJson()
            val srcs = record.imageSrcsFromJson()
            rects.forEachIndexed { index, rect ->
                val parts = rect.split(",").mapNotNull { it.trim().toFloatOrNull() }
                if (parts.size == 4) {
                    val (rx, ry, rw, rh) = parts
                    if (relX >= rx && relX <= rx + rw && relY >= ry && relY <= ry + rh) {
                        val src = srcs.getOrNull(index) ?: srcs.firstOrNull()
                        if (src != null) return record to src
                    }
                }
            }
        }
        return null
    }

    /** 配图所属记录的全部图片 src（同组多图全屏可左右滑动），找不到时退回单图 */
    private fun illustrationGroupSrcs(src: String): List<String> {
        val book = ReadBook.book ?: return listOf(src)
        return appDb.bookIllustrationDao.getByBook(book.bookUrl)
            .firstOrNull { it.imageSrcsFromJson().contains(src) }
            ?.imageSrcsFromJson()
            ?.takeIf { it.isNotEmpty() }
            ?: listOf(src)
    }

    /**
     * 单击
     * @return true:已处理, false:未处理
     */
    @Suppress("UNUSED_ANONYMOUS_PARAMETER")
    fun click(x: Float, y: Float): Boolean {
        val currentTime = System.currentTimeMillis()
        val debounceClick = currentTime - lastClickTime < 300L //300毫秒防抖和双击
        lastClickTime = currentTime
        doubleClick = if (debounceClick) {
            !doubleClick
        } else {
            false
        }
        // 书签备注气泡与书签正文的点击优先级最高
        findBubbleAt(x, y)?.let {
            onBubbleClick(it)
            return true
        }
        findBookmarkAt(x, y)?.let {
            // 无备注的书签不劫持点击，保持原有的点击行为（翻页/区域点击等）
            if (it.content.isNotBlank()) {
                onBookmarkBodyClick(it)
                return true
            }
        }
        handleEpubNoteClick(x, y)?.let { return it }
        var handled = false
        touch(x, y) { _, textPos, textPage, textLine, column ->
            when (column) {
                is ButtonColumn -> {
                    context.toastOnUi(R.string.epub_button_pressed)
                    handled = true
                }

                is ReviewColumn -> {
                    context.toastOnUi(R.string.epub_button_pressed)
                    handled = true
                }

                is ImageColumn -> {
                    if (column.mediaType == "audio") {
                        // 音频块：点击播放/暂停（不放大、不弹窗）
                        if (column.src.startsWith(IllustrationHelp.SRC_PREFIX)) {
                            val book = ReadBook.book
                            if (book != null) {
                                AudioBlockPlayer.toggle(context, book, column.src)
                                handled = true
                            }
                        }
                    } else if (column.mediaType == "video") {
                        // 视频：点击全屏播放，同组多图可左右滑动
                        if (column.src.startsWith(IllustrationHelp.SRC_PREFIX)) {
                            val groupSrcs = illustrationGroupSrcs(column.src)
                            val groupPos = groupSrcs.indexOf(column.src).coerceAtLeast(0)
                            activity?.showDialogFragment(
                                PhotoDialog(groupSrcs, groupPos, isBook = true)
                            )
                            handled = true
                        }
                    } else {
                    val pdfHit = hitPdfIllustration(x, y, textLine, column)
                    if (pdfHit != null) {
                        // PDF 页内配图热区：点击全屏查看，同组多图可左右滑动
                        val pdfSrcs = pdfHit.first.imageSrcsFromJson()
                        val pdfPos = pdfSrcs.indexOf(pdfHit.second).coerceAtLeast(0)
                        activity?.showDialogFragment(
                            PhotoDialog(pdfSrcs, pdfPos, isBook = true)
                        )
                        handled = true
                    } else if (column.src.startsWith(IllustrationHelp.SRC_PREFIX)) {
                        // 配图：点击直接全屏查看，同组多图可左右滑动
                        val groupSrcs = illustrationGroupSrcs(column.src)
                        val groupPos = groupSrcs.indexOf(column.src).coerceAtLeast(0)
                        activity?.showDialogFragment(
                            PhotoDialog(groupSrcs, groupPos, isBook = true)
                        )
                        handled = true
                    } else when (AppConfig.clickImgWay) {
                    "1" -> { //预览图片
                        activity?.showDialogFragment(PhotoDialog(column.src, isBook = true))
                        handled = true
                    }
                    "2" -> { //兼容处理
                        if (!debounceClick) {
                            if (ReadBook.book?.isOnLineTxt == true) {
                                val click = column.click
                                val src = column.src
                                if (!click.isNullOrBlank()) {
                                    callBack.clickImg(click, src)
                                    handled = true
                                } else {
                                    handled = callBack.oldClickImg(src)
                                }
                            }
                        }
                    }
                    "3" -> { //关闭
                        handled = false
                    }
                    "4" -> { //双击
                        val click = column.click
                        if (doubleClick) {
                            if (!click.isNullOrBlank()) {
                                callBack.clickImg(click, column.src)
                                handled = true
                            }
                        } else if (!click.isNullOrBlank()) {
                            handled = true
                        }
                    }
                    else -> { //默认点击
                        if (!debounceClick) {
                            val click = column.click
                            if (!click.isNullOrBlank()) {
                                callBack.clickImg(click, column.src)
                                handled = true
                            }
                        }
                    }
                }
                    }
                }
                is TextHtmlColumn -> {
                    column.linkUrl?.let {
                        if (it.startsWith(EPUB_MEDIA_LINK_PREFIX)) {
                            context.toastOnUi(R.string.epub_media_not_supported)
                        } else {
                            activity?.startActivity<OpenUrlConfirmActivity> {
                                putExtra("uri", it)
                            }
                        }
                        handled = true
                    }
                }
            }
        }
        return handled
    }

    private fun handleEpubNoteClick(x: Float, y: Float): Boolean? {
        val book = ReadBook.book ?: return null
        for (relativePos in 0..2) {
            if (relativePos > 0 && !callBack.isScroll) break
            val offset = relativeOffset(relativePos)
            if (relativePos > 0 && offset >= ChapterProvider.visibleHeight) break
            val page = relativePage(relativePos)
            val href = page.findEpubLinkAt(x, y - offset) ?: continue
            AppLog.put("EPUB Footnote click hit: href=$href, x=$x, y=${y - offset}, pageLinks=${page.epubLinkDiagnostics()}")
            if (!href.contains("#")) return null
            showEpubFootnote(book, href)
            return true
        }
        val page = relativePage(0)
        if (page.isNativeEpubPage()) {
            AppLog.put("EPUB Footnote click miss: x=$x, y=$y, pageLinks=${page.epubLinkDiagnostics()}")
        }
        return null
    }

    private fun showEpubFootnote(book: Book, href: String) {
        footnoteThread.execute {
            val note = runCatching {
                EpubFile.getFootnote(book, href)
            }.getOrNull()
            post {
                if (note == null) {
                    AppLog.put("EPUB Footnote resolve failed: href=$href")
                    context.toastOnUi(R.string.epub_footnote_load_failed)
                } else {
                    val textView = TextView(context).apply {
                        textSize = 15f
                        setTextColor(context.getCompatColor(R.color.primaryText))
                        setPadding(20.dpToPx(), 14.dpToPx(), 20.dpToPx(), 14.dpToPx())
                        setHtml(note.html)
                    }
                    val scrollView = ScrollView(context).apply {
                        addView(textView)
                        minimumHeight = 96.dpToPx()
                    }
                    context.alert(title = note.title) {
                        customView { scrollView }
                        okButton()
                    }
                }
            }
        }
    }

    /**
     * 选择文字
     */
    fun selectText(
        x: Float,
        y: Float,
        select: (textPos: TextPos) -> Unit,
    ) {
        touchRough(x, y) { _, textPos, _, _, column ->
            if (column is TextBaseColumn) {
                column.selected = true
                select(textPos)
            }
        }
    }

    /**
     * 开始选择符移动
     */
    fun selectStartMove(x: Float, y: Float) {
        touchRough(x, y) { _, textPos, _, _, _ ->
            if (selectStart.compare(textPos) == 0) {
                return@touchRough
            }
            if (textPos.compare(selectEnd) <= 0) {
                selectStartMoveIndex(textPos)
            } else {
                touchRough(x - 2 * cursorWidth, y) { _, textPos, _, _, _ ->
                    if (textPos.compare(selectEnd) > 0) {
                        reverseStartCursor = true
                        reverseEndCursor = false
                        selectEnd.columnIndex++
                        selectStartMoveIndex(selectEnd)
                        selectEndMoveIndex(textPos)
                    }
                }
            }
        }
    }

    /**
     * 结束选择符移动
     */
    fun selectEndMove(x: Float, y: Float) {
        touchRough(x, y) { _, textPos, _, _, _ ->
            if (textPos.compare(selectEnd) == 0) {
                return@touchRough
            }
            if (textPos.compare(selectStart) >= 0) {
                selectEndMoveIndex(textPos)
            } else {
                touchRough(x + 2 * cursorWidth, y) { _, textPos, _, _, _ ->
                    if (textPos.compare(selectStart) < 0) {
                        reverseEndCursor = true
                        reverseStartCursor = false
                        selectStart.columnIndex--
                        selectEndMoveIndex(selectStart)
                        selectStartMoveIndex(textPos)
                    }
                }
            }
        }
    }

    /**
     * 触碰位置信息
     * @param touched 回调
     */
    private fun touch(
        x: Float,
        y: Float,
        touched: (
            relativeOffset: Float,
            textPos: TextPos,
            textPage: TextPage,
            textLine: TextLine,
            column: BaseColumn
        ) -> Unit
    ) {
        if (!visibleRect.contains(x, y)) return
        var relativeOffset: Float
        for (relativePos in 0..2) {
            relativeOffset = relativeOffset(relativePos)
            if (relativePos > 0) {
                //滚动翻页
                if (!callBack.isScroll) return
                if (relativeOffset >= ChapterProvider.visibleHeight) return
            }
            val textPage = relativePage(relativePos)
            for ((lineIndex, textLine) in textPage.lines.withIndex()) {
                if (textLine.isTouch(x, y, relativeOffset)) {
                    for ((charIndex, textColumn) in textLine.columns.withIndex()) {
                        if (textColumn.isTouch(x)) {
                            touched.invoke(
                                relativeOffset,
                                TextPos(relativePos, lineIndex, charIndex),
                                textPage, textLine, textColumn
                            )
                            return
                        }
                    }
                    return
                }
            }
        }
    }

    /**
     * 触碰位置信息
     * 文本选择专用
     * @param touched 回调
     */
    private fun touchRough(
        x: Float,
        y: Float,
        touched: (
            relativeOffset: Float,
            textPos: TextPos,
            textPage: TextPage,
            textLine: TextLine,
            column: BaseColumn
        ) -> Unit
    ) {
        var relativeOffset: Float
        for (relativePos in 0..2) {
            relativeOffset = relativeOffset(relativePos)
            if (relativePos > 0) {
                //滚动翻页
                if (!callBack.isScroll) return
                if (relativeOffset >= ChapterProvider.visibleHeight) return
            }
            val textPage = relativePage(relativePos)
            for (lineIndex in textPage.lines.indices) {
                val textLine = textPage.getLine(lineIndex)
                if (textLine.isTouchY(y, relativeOffset)) {
                    if (textPage.doublePage) {
                        val halfWidth = width / 2
                        if (textLine.isLeftLine && x > halfWidth) {
                            continue
                        }
                        if (!textLine.isLeftLine && x < halfWidth) {
                            continue
                        }
                    }
                    val columns = textLine.columns
                    for (charIndex in columns.indices) {
                        val textColumn = columns[charIndex]
                        if (textColumn.isTouch(x)) {
                            touched.invoke(
                                relativeOffset,
                                TextPos(relativePos, lineIndex, charIndex),
                                textPage, textLine, textColumn
                            )
                            return
                        }
                    }
                    val isLast = columns.first().start < x
                    val charIndex = if (isLast) columns.lastIndex + 1 else -1
                    val textColumn = if (isLast) columns.last() else columns.first()
                    touched.invoke(
                        relativeOffset,
                        TextPos(relativePos, lineIndex, charIndex),
                        textPage, textLine, textColumn
                    )
                    return
                }
            }
        }
    }

    fun getCurVisiblePage(): TextPage {
        val visiblePage = TextPage()
        var relativeOffset: Float
        for (relativePos in 0..2) {
            relativeOffset = relativeOffset(relativePos)
            if (relativePos > 0) {
                //滚动翻页
                if (!callBack.isScroll) break
                if (relativeOffset >= ChapterProvider.visibleHeight) break
            }
            val textPage = relativePage(relativePos)
            val lines = textPage.lines
            for (i in lines.indices) {
                val textLine = lines[i]
                if (textLine.isVisible(relativeOffset)) {
                    val visibleLine = textLine.copy().apply {
                        lineTop += relativeOffset
                        lineBottom += relativeOffset
                    }
                    visiblePage.addLine(visibleLine)
                }
            }
        }
        return visiblePage
    }

    fun getReadAloudPos(): Pair<Int, TextLine>? {
        var relativeOffset: Float
        for (relativePos in 0..2) {
            relativeOffset = relativeOffset(relativePos)
            if (relativePos > 0) {
                //滚动翻页
                if (!callBack.isScroll) break
                if (relativeOffset >= ChapterProvider.visibleHeight) break
            }
            val textPage = relativePage(relativePos)
            val lines = textPage.lines
            for (i in lines.indices) {
                val textLine = lines[i]
                if (textLine.isVisible(relativeOffset)) {
                    val visibleLine = textLine.copy().apply {
                        lineTop += relativeOffset
                        lineBottom += relativeOffset
                    }
                    return textPage.chapterIndex to visibleLine
                }
            }
        }
        return null
    }

    fun getReadAloudPosByPoint(x: Float, y: Float): Pair<Int, TextLine>? {
        if (!visibleRect.contains(x, y)) return null
        var relativeOffset: Float
        for (relativePos in 0..2) {
            relativeOffset = relativeOffset(relativePos)
            if (relativePos > 0) {
                if (!callBack.isScroll) break
                if (relativeOffset >= ChapterProvider.visibleHeight) break
            }
            val textPage = relativePage(relativePos)
            for (lineIndex in textPage.lines.indices) {
                val textLine = textPage.getLine(lineIndex)
                if (!textLine.isTouchY(y, relativeOffset)) continue
                if (textPage.doublePage) {
                    val halfWidth = width / 2
                    if (textLine.isLeftLine && x > halfWidth) continue
                    if (!textLine.isLeftLine && x < halfWidth) continue
                }
                val paragraphStartLine = findParagraphStartLine(textPage, lineIndex)
                return textPage.chapterIndex to paragraphStartLine.copy().apply {
                    lineTop += relativeOffset
                    lineBottom += relativeOffset
                }
            }
        }
        return null
    }

    private fun findParagraphStartLine(textPage: TextPage, lineIndex: Int): TextLine {
        var startIndex = lineIndex
        while (startIndex > 0 && !textPage.getLine(startIndex - 1).isParagraphEnd) {
            startIndex--
        }
        return textPage.getLine(startIndex)
    }

    /**
     * 选择开始文字
     */
    fun selectStartMoveIndex(
        relativePagePos: Int,
        lineIndex: Int,
        charIndex: Int,
    ) {
        selectStart.relativePagePos = relativePagePos
        selectStart.lineIndex = lineIndex
        selectStart.columnIndex = max(0, charIndex)
        val textLine = relativePage(relativePagePos).getLine(lineIndex)
        val textColumn = textLine.getColumn(charIndex)
        upSelectedStart(
            if (charIndex < textLine.columns.size) textColumn.start else textColumn.end,
            textLine.lineBottom + relativeOffset(relativePagePos),
            textLine.lineTop + relativeOffset(relativePagePos)
        )
        upSelectChars()
    }

    fun selectStartMoveIndex(textPos: TextPos) = textPos.run {
        selectStartMoveIndex(relativePagePos, lineIndex, columnIndex)
    }

    /**
     * 选择结束文字
     */
    fun selectEndMoveIndex(
        relativePage: Int,
        lineIndex: Int,
        charIndex: Int,
    ) {
        selectEnd.relativePagePos = relativePage
        selectEnd.lineIndex = lineIndex
        val textLine = relativePage(relativePage).getLine(lineIndex)
        selectEnd.columnIndex = min(charIndex, textLine.columns.lastIndex)
        val textColumn = textLine.getColumn(charIndex)
        upSelectedEnd(
            if (charIndex > -1) textColumn.end else textColumn.start,
            textLine.lineBottom + relativeOffset(relativePage)
        )
        upSelectChars()
    }

    fun selectEndMoveIndex(textPos: TextPos) = textPos.run {
        selectEndMoveIndex(relativePagePos, lineIndex, columnIndex)
    }

    private fun upSelectChars() {
        if (!selectStart.isSelected() && !selectEnd.isSelected()) {
            return
        }
        val last = if (callBack.isScroll) 2 else 0
        val textPos = TextPos(0, 0, 0)
        for (relativePos in 0..last) {
            textPos.relativePagePos = relativePos
            val textPage = relativePage(relativePos)
            for ((lineIndex, textLine) in textPage.lines.withIndex()) {
                textPos.lineIndex = lineIndex
                for ((charIndex, column) in textLine.columns.withIndex()) {
                    textPos.columnIndex = charIndex
                    if (column is TextBaseColumn) {
                        val compareStart = textPos.compare(selectStart)
                        val compareEnd = textPos.compare(selectEnd)
                        column.selected = compareStart >= 0 && compareEnd <= 0
                        column.isSearchResult =
                            column.selected && callBack.isSelectingSearchResult
                        if (column.isSearchResult) {
                            textPage.searchResult.add(column)
                        }
                    }
                }
            }
        }
        postInvalidate()
    }

    private fun upSelectedStart(x: Float, y: Float, top: Float) {
        callBack.run {
            upSelectedStart(x + imgBgPaddingStart, y + headerHeight, top + headerHeight)
        }
    }

    private fun upSelectedEnd(x: Float, y: Float) {
        callBack.run {
            upSelectedEnd(x + imgBgPaddingStart, y + headerHeight)
        }
    }

    fun resetReverseCursor() {
        reverseStartCursor = false
        reverseEndCursor = false
    }

    fun cancelSelect(clearSearchResult: Boolean = false) {
        nativeSelectedText = null
        nativeSelectionRect = null
        val last = if (callBack.isScroll) 2 else 0
        for (relativePos in 0..last) {
            val textPage = relativePage(relativePos)
            textPage.lines.forEach { textLine ->
                textLine.columns.forEach {
                    if (it is TextBaseColumn) {
                        it.selected = false
                        if (clearSearchResult) {
                            it.isSearchResult = false
                            textPage.searchResult.remove(it)
                        }
                    }
                }
            }
        }
        selectStart.reset()
        selectEnd.reset()
        postInvalidate()
        callBack.onCancelSelect()
    }

    fun getSelectedText(): String {
        nativeSelectedText?.takeIf { it.isNotBlank() }?.let { return it }
        val textPos = TextPos(0, 0, 0)
        val builder = StringBuilder()
        for (relativePos in selectStart.relativePagePos..selectEnd.relativePagePos) {
            val textPage = relativePage(relativePos)
            textPos.relativePagePos = relativePos
            textPage.lines.forEachIndexed { lineIndex, textLine ->
                textPos.lineIndex = lineIndex
                textLine.columns.forEachIndexed { charIndex, column ->
                    textPos.columnIndex = charIndex
                    val compareStart = textPos.compare(selectStart)
                    val compareEnd = textPos.compare(selectEnd)
                    if (column is TextBaseColumn) {
                        when {
                            compareStart == -1 -> if (
                                selectStart.columnIndex == textLine.columns.size
                                && charIndex == textLine.columns.lastIndex
                            ) {
                                builder.append("\n")
                            }

                            compareEnd == 1 -> if (selectEnd.columnIndex == -1 && charIndex == 0) {
                                builder.append("\n")
                            }

                            compareStart >= 0 && compareEnd <= 0 -> {
                                builder.append(column.charData)
                                if (
                                    textLine.isParagraphEnd
                                    && charIndex == textLine.columns.lastIndex
                                    && compareEnd != 0
                                ) {
                                    builder.append("\n")
                                }
                            }
                        }
                    }
                }
            }
        }
        return builder.toString()
    }

    fun hasSelection(): Boolean {
        return !nativeSelectedText.isNullOrBlank() || (selectStart.isSelected() && selectEnd.isSelected())
    }

    fun hasNativeSelection(): Boolean = !nativeSelectedText.isNullOrBlank()

    private fun isNativeEpubHit(x: Float, y: Float): Boolean {
        val last = if (callBack.isScroll) 2 else 0
        for (relativePos in 0..last) {
            val page = relativePage(relativePos)
            if (!page.isNativeEpubPage()) continue
            val offset = relativeOffset(relativePos)
            val localY = y - offset
            val href = page.findEpubLinkAt(x, localY)
            if (href != null) {
                return false
            }
            if (page.findNativeTextSelectionAt(x, localY) != null) {
                nativeSelectedText = null
                nativeSelectionRect = null
                postInvalidate()
                return true
            }
        }
        return false
    }

    private fun selectNativeText(x: Float, y: Float): String? {
        val last = if (callBack.isScroll) 2 else 0
        for (relativePos in 0..last) {
            val page = relativePage(relativePos)
            if (!page.isNativeEpubPage()) continue
            val offset = relativeOffset(relativePos)
            val localY = y - offset
            val selection = page.findNativeTextSelectionAt(x, localY) ?: continue
            val hitRect = RectF(
                selection.rect.left + page.epubDrawOffsetX,
                selection.rect.top + page.epubDrawOffsetY + offset,
                selection.rect.right + page.epubDrawOffsetX,
                selection.rect.bottom + page.epubDrawOffsetY + offset
            )
            nativeSelectedText = selection.expandedText ?: selection.text
            nativeSelectionRect = hitRect
            postInvalidate()
            upSelectedStart(hitRect.left, hitRect.bottom, hitRect.top)
            upSelectedEnd(hitRect.right, hitRect.bottom)
            return selection.text
        }
        return null
    }

    fun createBookmark(): Bookmark? {
        val page = relativePage(selectStart.relativePagePos)
        page.getTextChapter().let { chapter ->
            ReadBook.book?.let { book ->
                val startPos = chapter.getReadLength(page.index) +
                    page.getPosByLineColumn(selectStart.lineIndex, selectStart.columnIndex)
                val text = getSelectedText()
                // 选中的文字命中已有书签时，直接进入该书签的设置页面
                findHitBookmark(startPos, startPos + text.length)?.let { return it }
                return book.createBookMark().apply {
                    chapterIndex = page.chapterIndex
                    chapterPos = startPos
                    chapterName = chapter.title
                    bookText = text
                }
            }
        }
        return null
    }

    /**
     * 选中的文字与已有书签重叠时，按“重叠更多 > 书签更短 > 创建更晚”的优先级
     * 选出一个书签，用于直接进入其设置页面；未命中返回 null。
     */
    private fun findHitBookmark(selStart: Int, selEnd: Int): Bookmark? {
        return bookmarks
            .mapNotNull { existing ->
                val oldStart = existing.chapterPos
                val oldEnd = existing.chapterPos + existing.bookText.length
                val overlap = min(selEnd, oldEnd) - max(selStart, oldStart)
                if (overlap <= 0) null else existing to overlap
            }
            .sortedWith(
                compareByDescending<Pair<Bookmark, Int>> { it.second }
                    .thenBy { it.first.bookText.length }
                    .thenByDescending { it.first.time }
            )
            .firstOrNull()?.first
    }

    /**
     * 创建段落书签：以完整段落为最小单位扩展选区，段落长度上限 1000 字。
     */
    fun createParagraphBookmark(): Bookmark? {
        if (!selectStart.isSelected() || !selectEnd.isSelected()) return null
        val startPage = relativePage(selectStart.relativePagePos)
        val endPage = relativePage(selectEnd.relativePagePos)
        if (startPage.textChapter !== endPage.textChapter) return null
        startPage.getTextChapter().let { chapter ->
            ReadBook.book?.let { book ->
                val selStart = chapter.getReadLength(startPage.index) +
                    startPage.getPosByLineColumn(selectStart.lineIndex, selectStart.columnIndex)
                val selEnd = chapter.getReadLength(endPage.index) +
                    endPage.getPosByLineColumn(selectEnd.lineIndex, selectEnd.columnIndex)
                // 选中的文字命中已有书签时，直接进入该书签的设置页面
                findHitBookmark(selStart, selEnd)?.let { return it }
                val paragraphs = chapter.paragraphs
                val startParagraph = paragraphs.firstOrNull { selStart in it.chapterIndices }
                val endParagraph = paragraphs.firstOrNull { selEnd in it.chapterIndices }
                if (startParagraph == null || endParagraph == null) {
                    return null
                }
                val paraStart = startParagraph.chapterPosition
                val paraEnd = endParagraph.lastLine.chapterPosition +
                    endParagraph.lastLine.charSize +
                    if (endParagraph.isParagraphEnd) 1 else 0
                val (bmStart, bmEnd) = expandParagraphToLimit(paraStart, paraEnd, selStart, selEnd)
                if (paraEnd - paraStart > bmEnd - bmStart) {
                    context.toastOnUi(R.string.paragraph_bookmark_trimmed)
                }
                val text = chapter.getContent().substring(bmStart, bmEnd)
                val bookmark = book.createBookMark().apply {
                    chapterIndex = startPage.chapterIndex
                    chapterPos = bmStart
                    chapterName = chapter.title
                    bookText = text
                }
                return bookmark
            }
        }
        return null
    }

    private fun expandParagraphToLimit(
        paraStart: Int,
        paraEnd: Int,
        selStart: Int,
        selEnd: Int
    ): Pair<Int, Int> {
        val total = paraEnd - paraStart
        if (total <= 1000) return paraStart to paraEnd
        val selLen = selEnd - selStart
        if (selLen >= 1000) return selStart to selStart + 1000
        var before = min(selStart - paraStart, (1000 - selLen) / 2)
        var after = 1000 - selLen - before
        var start = selStart - before
        var end = selEnd + after
        if (end > paraEnd) {
            end = paraEnd
            start = maxOf(paraStart, end - 1000)
        }
        if (start < paraStart) {
            start = paraStart
            end = start + 1000
        }
        return start to end
    }

    private fun relativeOffset(relativePos: Int): Float {
        return when (relativePos) {
            0 -> pageOffset.toFloat()
            1 -> pageOffset + textPage.height
            else -> pageOffset + textPage.height + pageFactory.nextPage.height
        }
    }

    fun relativePage(relativePos: Int): TextPage {
        return when (relativePos) {
            0 -> textPage
            1 -> pageFactory.nextPage
            else -> pageFactory.nextPlusPage
        }
    }

    fun setAutoPager(autoPager: AutoPager?) {
        this.autoPager = autoPager
    }

    fun setIsScroll(value: Boolean) {
        val changed = isScroll != value
        isScroll = value
        if (changed) {
            backgroundScrollOffset = 0
            invalidateBackgroundHost()
        }
    }

    override fun canScrollVertically(direction: Int): Boolean {
        return callBack.isScroll && pageFactory.hasNext()
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                longScreenshot = true
                scrollY = 0
            }

            MotionEvent.ACTION_UP -> {
                longScreenshot = false
                scrollY = 0
            }
        }
        return callBack.onLongScreenshotTouchEvent(event)
    }

    companion object {
        private val renderThread by lazy {
            Executors.newSingleThreadExecutor {
                Thread(it, "TextPageRender")
            }
        }
        private val footnoteThread by lazy {
            Executors.newSingleThreadExecutor {
                Thread(it, "EpubFootnote")
            }
        }
        private val cursorWidth = 24.dpToPx()
        private const val EPUB_MEDIA_LINK_PREFIX = "legado-epub-media:"
    }

    interface CallBack {
        val headerHeight: Int
        val imgBgPaddingStart: Int
        val pageFactory: TextPageFactory
        val pageDelegate: PageDelegate?
        val isScroll: Boolean
        var isSelectingSearchResult: Boolean
        fun upSelectedStart(x: Float, y: Float, top: Float)
        fun upSelectedEnd(x: Float, y: Float)
        fun onImageLongPress(x: Float, y: Float, src: String)
        fun onCancelSelect()
        fun onLongScreenshotTouchEvent(event: MotionEvent): Boolean
        fun oldClickImg(src: String): Boolean
        fun clickImg(click: String, src: String)
    }
}
