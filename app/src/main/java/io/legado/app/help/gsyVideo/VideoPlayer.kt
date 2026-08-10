package io.legado.app.help.gsyVideo

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.ImageView
import android.widget.TextView
import com.shuyu.gsyvideoplayer.listener.LockClickListener
import com.shuyu.gsyvideoplayer.utils.CommonUtil
import com.shuyu.gsyvideoplayer.video.StandardGSYVideoPlayer
import com.shuyu.gsyvideoplayer.video.base.GSYVideoPlayer
import io.legado.app.R
import io.legado.app.model.VideoPlay
import master.flame.danmaku.controller.DrawHandler
import master.flame.danmaku.danmaku.loader.IllegalDataException
import master.flame.danmaku.danmaku.loader.android.DanmakuLoaderFactory
import master.flame.danmaku.danmaku.model.BaseDanmaku
import master.flame.danmaku.danmaku.model.DanmakuTimer
import master.flame.danmaku.danmaku.model.IDisplayer
import master.flame.danmaku.danmaku.model.android.DanmakuContext
import master.flame.danmaku.danmaku.model.android.SpannedCacheStuffer
import master.flame.danmaku.danmaku.parser.BaseDanmakuParser
import master.flame.danmaku.ui.widget.DanmakuView
import java.io.File
import java.io.FileInputStream

private const val PLAYBACK_LOAD_TIMEOUT_MS = 25_000L

class VideoPlayer: StandardGSYVideoPlayer {
    constructor(context: Context?, fullFlag: Boolean?) : super(context, fullFlag) //必须的,全屏时依靠这个构建知道获取全屏布局
    constructor(context: Context?) : super(context)
    constructor(context: Context?, attrs: AttributeSet?) : super(context, attrs)

    private var episodeList: TextView? = null
    private var playbackSpeed: TextView? = null
    private var playSpeed: Float = 1.0f
    var onPlaySpeedChanged: ((Float) -> Unit)? = null
    private var btnNext: ImageView? = null
    private var tipView: TextView? = null
    private val playbackLoadTimeoutHandler = Handler(Looper.getMainLooper())
    private val playbackLoadTimeoutRunnable = Runnable {
        if (isWaitingForPlaybackStart()) {
            onPlaybackLoadTimeout?.invoke() ?: showPlayAddressError()
        }
    }
    var onPlaybackLoadTimeout: (() -> Unit)? = null
    private var isChanging = false
    private var isLongPressSpeed = false
    private var mChangeEpisode = false
    private var episodeGestureOffset = 0
    private var episodeGestureTranslation = 0f
    private var defaultTipY: Float? = null

    private var mParser: BaseDanmakuParser? = null //解析器对象
    private var mDanmakuView: DanmakuView? = null //弹幕view
    private var mDanmakuContext: DanmakuContext? = null
    var mToggleDanmaku: TextView? = null //弹幕开关
    private var mDanmakuStartSeekPosition: Long = -1


    override fun getLayoutId(): Int {
        return if (mIfCurrentIsFullscreen)
            R.layout.video_layout_controller_full
        else R.layout.video_layout_controller
    }

    override fun getFullWindowPlayer(): VideoPlayer? {
        val activity = CommonUtil.scanForActivity(context) ?: return null
        val vp = activity.findViewById<View?>(Window.ID_ANDROID_CONTENT) as ViewGroup
        val full = vp.findViewById<View?>(fullId)
        var gsyVideoPlayer: VideoPlayer? = null
        if (full != null) {
            gsyVideoPlayer = full as VideoPlayer
        }
        return gsyVideoPlayer
    }
    override fun getSmallWindowPlayer(): VideoPlayer? = null

    override fun getCurrentPlayer(): VideoPlayer {
        val fullVideoPlayer = getFullWindowPlayer()
        if (fullVideoPlayer != null) {
            return fullVideoPlayer
        }
        val smallVideoPlayer = getSmallWindowPlayer()
        if (smallVideoPlayer != null) {
            return smallVideoPlayer
        }
        return this
    }

    fun getLockCurScreen() = mLockCurScreen

    public override fun lockTouchLogic() = super.lockTouchLogic()

    override fun init(context: Context) {
        super.init(context)
        initView()
        post {
            gestureDetector = GestureDetector(
                getContext().applicationContext,
                object : GestureDetector.SimpleOnGestureListener() {
                    override fun onDoubleTap(e: MotionEvent): Boolean {
                        val player = getCurrentPlayer()
                        val centerSafeWidth = player.width / 5f
                        val centerStart = (player.width - centerSafeWidth) / 2f
                        val centerEnd = centerStart + centerSafeWidth
                        if (e.x in centerStart..centerEnd) {
                            return true
                        }
                        val offset = if (e.x < player.width / 2f) -SEEK_STEP_MS else SEEK_STEP_MS
                        player.seekByOffset(offset)
                        return true
                    }

                    override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                        if (!mChangePosition && !mChangeVolume && !mBrightness && mCurrentState != CURRENT_STATE_ERROR
                        ) {
                            onClickUiToggle(e)
                        }
                        return super.onSingleTapConfirmed(e)
                    }

                    override fun onLongPress(e: MotionEvent) {
                        if (mCurrentState == CURRENT_STATE_PLAYING) {
                            val speed = VideoPlay.longPressSpeed / 10.0f
                            setVideoSpeed(speed)
                            showOverlayTip("${speed}倍速播放中")
                            isLongPressSpeed = true
                        }
                        super.onLongPress(e)
                    }
                }
            )
            mLockClickListener = LockClickListener { view, lock ->
                VideoPlay.lockCurScreen = lock
            }
        }
    }

    fun seekByOffset(offsetMs: Long) {
        if (mCurrentState == CURRENT_STATE_NORMAL || mCurrentState == CURRENT_STATE_ERROR) {
            return
        }
        val duration = getDuration().takeIf { it > 0 } ?: 0L
        val current = getCurrentPositionWhenPlaying()
        val target = (current + offsetMs).coerceIn(0L, duration.takeIf { it > 0 } ?: Long.MAX_VALUE)
        seekTo(target)
        resolveDanmakuSeek(target)
        showOverlayTip(if (offsetMs < 0) "-10s" else "+10s", 800)
    }

    override fun touchSurfaceDown(x: Float, y: Float) {
        super.touchSurfaceDown(x, y)
        cancelPlaybackContentAnimation()
        mChangeEpisode = false
        episodeGestureOffset = 0
        episodeGestureTranslation = 0f
    }

    override fun touchSurfaceMoveFullLogic(absDeltaX: Float, absDeltaY: Float) {
        if (mChangeEpisode) {
            return
        }
        val activity = getActivityContext() as? Activity
        val curWidth = if (activity != null) {
            if (CommonUtil.getCurrentScreenLand(activity)) mScreenHeight else mScreenWidth
        } else {
            width
        }
        if (absDeltaX <= mThreshold && absDeltaY <= mThreshold) {
            return
        }
        cancelProgressTimer()
        if (absDeltaX >= mThreshold) {
            val screenWidth = CommonUtil.getScreenWidth(context)
            if (Math.abs(screenWidth - mDownX) > mSeekEndOffset) {
                mChangePosition = true
                mDownPosition = getCurrentPositionWhenPlaying()
            } else {
                mShowVKey = true
            }
            return
        }
        val screenHeight = CommonUtil.getScreenHeight(context)
        val noEnd = Math.abs(screenHeight - mDownY) > mSeekEndOffset
        if (mFirstTouch) {
            mFirstTouch = false
        }
        if (noEnd && curWidth > 0) {
            mChangeEpisode = true
        } else {
            mShowVKey = true
        }
    }

    override fun touchSurfaceMove(deltaX: Float, deltaY: Float, y: Float) {
        if (mChangeEpisode) {
            val offset = resolveEpisodeGestureOffset(deltaY)
            if (offset != episodeGestureOffset) {
                episodeGestureOffset = offset
                if (offset == 0) {
                    showOverlayTip()
                } else {
                    showEpisodeGestureTip(offset)
                }
            }
            updatePlaybackContentTranslation(deltaY)
            return
        }
        super.touchSurfaceMove(deltaX, deltaY, y)
    }

    override fun touchSurfaceUp(){
        if (isLongPressSpeed) {
            isLongPressSpeed = false
            setVideoSpeed(playSpeed)
            showOverlayTip()
            val time = getCurrentPositionWhenPlaying()
            resolveDanmakuStart(time)
        }
        super.touchSurfaceUp()
        if (mChangeEpisode && episodeGestureOffset != 0) {
            finishEpisodeGesture(episodeGestureOffset)
            showOverlayTip()
        } else {
            resetPlaybackContentTranslation(animated = true)
        }
        mChangeEpisode = false
        episodeGestureOffset = 0
        episodeGestureTranslation = 0f
    }

    private fun setVideoSpeed(speed: Float) {
        setSpeed(speed, true)
        if (mDanmakuView != null&& !mDanmakuView!!.isPaused) {
            mDanmakuContext!!.setScrollSpeedFactor(VideoPlay.danmakuSpeed - (speed - 1f) / 6f)
            mDanmakuView!!.invalidate()
        }
    }

    override fun onPrepared() {
        super.onPrepared()
        cancelPlaybackLoadTimeout()
        hidePlayAddressError()
        onPrepareDanmaku(this)
        VideoPlay.queuePreparedNextEpisode()
    }
    private fun onPrepareDanmaku(gsyVideoPlayer: VideoPlayer) {
        val view = gsyVideoPlayer.mDanmakuView
        val par = gsyVideoPlayer.mParser
        val con = gsyVideoPlayer.mDanmakuContext
        if ( view != null && !view.isPrepared && par != null) {
            view.prepare(par, con)
        }
    }

    override fun onVideoPause() {
        super.onVideoPause()
        danmakuOnPause()
    }
    fun danmakuOnPause() {
        if (mDanmakuView != null && mDanmakuView!!.isPrepared) {
            mDanmakuView!!.pause()
        }
    }

    override fun onVideoResume(isResume: Boolean) {
        super.onVideoResume(isResume)
        cancelPlaybackLoadTimeout()
        danmakuOnResume()
        ensureVideoSurfaceBound()
    }
    fun danmakuOnResume() {
        if (mDanmakuView != null && mDanmakuView!!.isPrepared && mDanmakuView!!.isPaused) {
            mDanmakuView!!.resume()
        }
    }

    override fun clickStartIcon() {
        super.clickStartIcon()
        if (mCurrentState == CURRENT_STATE_PLAYING) {
            danmakuOnResume()
        } else if (mCurrentState == CURRENT_STATE_PAUSE) {
            danmakuOnPause()
        }
    }

    override fun onAutoCompletion() { //播放完成
        super.onAutoCompletion()
        if (VideoPlay.videoManager.hasNext()) {
            return
        }
        VideoPlay.upDurIndex(1, this)
    }

    override fun onCompletion() {
        super.onCompletion()
        releaseDanmaku(this)
    }
    fun releaseDanmaku(gsyVideoPlayer: VideoPlayer) {
        gsyVideoPlayer.mDanmakuView?.release()
    }


    override fun onSeekComplete() {
        super.onSeekComplete()
        val time = mProgressBar.progress * getDuration() / 100
        //如果已经初始化过的，直接seek到对于位置
        if (mHadPlay && mDanmakuView != null && mDanmakuView!!.isPrepared) {
            resolveDanmakuSeek(time)
        } else if (mHadPlay && mDanmakuView != null && !mDanmakuView!!.isPrepared) {
            //如果没有初始化过的，记录位置等待
            mDanmakuStartSeekPosition = time
        }
    }


    fun showOverlayTip(message: String? = null, delay: Long = 0) {
        tipView?.apply {
            animate().cancel()
            message?.also {
                defaultTipY?.let { y = it }
                text = it
                visibility = VISIBLE
                alpha = 1f
                if (delay > 0) {
                    postDelayed({
                        alpha = 0f
                    }, delay)
                }
            } ?: run {
                defaultTipY?.let { y = it }
                visibility = INVISIBLE
                alpha = 0f
                translationY = 0f
            }
        }
    }

    private fun showEpisodeGestureTip(offset: Int) {
        val message = context.getString(
            if (offset > 0) R.string.next_chapter else R.string.previous_chapter
        )
        tipView?.apply {
            animate().cancel()
            val targetTranslationY = episodeGestureTipTranslationY(offset)
            text = message
            visibility = VISIBLE
            alpha = 0f
            translationY = targetTranslationY + if (offset > 0) 18f else -18f
            animate()
                .alpha(1f)
                .translationY(targetTranslationY)
                .setDuration(120)
                .start()
        }
    }

    private fun resolveEpisodeGestureOffset(deltaY: Float): Int {
        val threshold = episodeGestureThreshold()
        return when {
            deltaY <= -threshold -> 1
            deltaY >= threshold -> -1
            else -> 0
        }
    }

    private fun episodeGestureThreshold(): Float {
        val height = height.takeIf { it > 0 } ?: CommonUtil.getScreenHeight(context)
        return height / 5f
    }

    private fun TextView.episodeGestureTipTranslationY(offset: Int): Float {
        if (defaultTipY == null) {
            defaultTipY = y
        }
        val containerHeight = this@VideoPlayer.height.takeIf { it > 0 }
            ?: CommonUtil.getScreenHeight(context)
        val tipHeight = measuredHeight.takeIf { it > 0 } ?: height.takeIf { it > 0 } ?: 0
        val centerY = containerHeight * if (offset > 0) 0.88f else 0.12f
        val targetY = centerY - tipHeight / 2f
        return targetY - (defaultTipY ?: y)
    }

    private fun playbackContentViews(): List<View> {
        val renderView = mTextureView?.getShowView()
        return listOfNotNull(
            renderView?.takeUnless { it is SurfaceView },
            findViewById(R.id.danmaku_view)
        ).filter { it.visibility == VISIBLE }
    }

    private fun updatePlaybackContentTranslation(deltaY: Float) {
        val maxDistance = episodeGestureThreshold() * 1.15f
        episodeGestureTranslation = deltaY.coerceIn(-maxDistance, maxDistance)
        playbackContentViews().forEach { view ->
            view.translationY = episodeGestureTranslation
        }
    }

    private fun finishEpisodeGesture(offset: Int) {
        if (!canChangeEpisode(offset)) {
            VideoPlay.upDurIndex(offset, getCurrentPlayer())
            resetPlaybackContentTranslation(animated = true)
            return
        }
        val distance = (height.takeIf { it > 0 } ?: CommonUtil.getScreenHeight(context)).toFloat()
        val outTranslation = if (offset > 0) -distance else distance
        val inTranslation = -outTranslation
        val views = playbackContentViews()
        if (views.isEmpty()) {
            VideoPlay.upDurIndex(offset, getCurrentPlayer())
            return
        }
        views.forEachIndexed { index, view ->
            val animator = view.animate()
                .translationY(outTranslation)
                .setDuration(140)
            if (index == 0) {
                animator.withEndAction {
                    views.forEach { it.translationY = inTranslation }
                    VideoPlay.upDurIndex(offset, getCurrentPlayer())
                    views.forEach {
                        it.animate()
                            .translationY(0f)
                            .setDuration(180)
                            .start()
                    }
                }
            }
            animator.start()
        }
    }

    private fun canChangeEpisode(offset: Int): Boolean {
        val episodes = VideoPlay.episodes ?: return false
        val target = VideoPlay.chapterInVolumeIndex + offset
        return target >= 0 && target < episodes.size
    }

    private fun resetPlaybackContentTranslation(animated: Boolean) {
        playbackContentViews().forEach { view ->
            view.animate().cancel()
            if (animated) {
                view.animate()
                    .translationY(0f)
                    .setDuration(160)
                    .start()
            } else {
                view.translationY = 0f
            }
        }
    }

    private fun cancelPlaybackContentAnimation() {
        playbackContentViews().forEach {
            it.animate().cancel()
            it.translationY = 0f
        }
    }

    companion object {
        private const val SEEK_STEP_MS = 10_000L
    }

    private fun initView() {
        isNeedLockFull = true //使用锁定按钮
        playbackSpeed = findViewById(R.id.playback_speed)
        playbackSpeed?.setOnClickListener {
            if (mHadPlay && !isChanging) {
                showSpeedDialog()
            }
        }
        tipView = findViewById(R.id.tip_view)
        tipView?.post {
            defaultTipY = tipView?.y
        }
        if (mIfCurrentIsFullscreen && !VideoPlay.fullBottomProgressBar) {
            mBottomProgressBar = null
        }
        //切换选集
        episodeList = findViewById(R.id.episode_list)
        btnNext = findViewById(R.id.next)
        if (VideoPlay.episodes == null) {
            episodeList?.visibility = GONE
            btnNext?.visibility = GONE
            return
        }
        episodeList?.setOnClickListener {
            if (mHadPlay && !isChanging) {
                showEpisodeDialog()
            }
        }
        btnNext?.setOnClickListener {
            VideoPlay.upDurIndex(1,this)
        }
    }


    override fun setUp(url: String?, cacheWithPlay: Boolean, cachePath: File?, title: String?): Boolean {
        cancelPlaybackLoadTimeout()
        initDanmaku()
        return super.setUp(url, cacheWithPlay, cachePath, title)
    }

    override fun startPlayLogic() {
        hidePlayAddressError()
        startPlaybackLoadTimeout()
        super.startPlayLogic()
    }

    private fun initDanmaku() {
        val danmakuFile = VideoPlay.danmakuFile
        val danmakuStr = VideoPlay.danmakuStr
        if (danmakuFile == null && danmakuStr.isNullOrBlank()) {
            mToggleDanmaku?.visibility = GONE
            return
        }
        mDanmakuView = findViewById<DanmakuView>(R.id.danmaku_view)?.also {
            it.visibility = VISIBLE
        }
        //弹幕开关
        mToggleDanmaku = findViewById<TextView>(R.id.toggle_danmaku)?.also {
            it.visibility = VISIBLE
            it.setOnClickListener { //按钮事件
                VideoPlay.danmakuShow = !VideoPlay.danmakuShow
                resolveDanmakuShow()
            }
        }
        if (mDanmakuView != null) {
            // 设置最大显示行数
            val maxLinesPair = HashMap<Int?, Int?>()
            maxLinesPair[BaseDanmaku.TYPE_SCROLL_RL] = 5 // 滚动弹幕最大显示5行
            // 设置是否禁止重叠
            val overlappingEnablePair = HashMap<Int?, Boolean?>()
            overlappingEnablePair[BaseDanmaku.TYPE_SCROLL_RL] = true
            overlappingEnablePair[BaseDanmaku.TYPE_FIX_TOP] = true
            val danmakuAdapter = DanmakuAdapter(mDanmakuView)
            mDanmakuContext = DanmakuContext.create() //初始化上下文
            mDanmakuContext!!.setDanmakuStyle(IDisplayer.DANMAKU_STYLE_STROKEN, 3f) //设置弹幕类型
                .setDuplicateMergingEnabled(false) //设置是否合并重复弹幕
                .setScrollSpeedFactor(VideoPlay.danmakuSpeed) //设置弹幕滚动速度
                .setScaleTextSize(1.0f) //设置弹幕字体大小
                .setCacheStuffer(SpannedCacheStuffer(), danmakuAdapter) //设置缓存绘制填充器 图文混排使用SpannedCacheStuffer
                .setMaximumLines(maxLinesPair) //设置最大行数
                .preventOverlapping(overlappingEnablePair) //设置是否禁止重叠
            mParser = createParser(danmakuFile, danmakuStr) //加载弹幕资源文件
            mDanmakuView!!.setCallback(object : DrawHandler.Callback {
                override fun updateTimer(timer: DanmakuTimer?) {}
                override fun drawingFinished() {}
                override fun danmakuShown(danmaku: BaseDanmaku?) {}
                override fun prepared() {
                    if (mDanmakuView != null) {
                        mDanmakuView!!.start()
                        if (mDanmakuStartSeekPosition != -1L) {
                            resolveDanmakuSeek(mDanmakuStartSeekPosition)
                            mDanmakuStartSeekPosition = -1L
                        }
                        resolveDanmakuShow()
                    }
                }
            })
            mDanmakuView!!.enableDanmakuDrawingCache(true)
        }
    }

    /**
     * 弹幕偏移
     */
    private fun resolveDanmakuSeek(time: Long) {
        if (mHadPlay && mDanmakuView != null && mDanmakuView!!.isPrepared) {
            mDanmakuView!!.seekTo(time)
        }
    }

    private fun resolveDanmakuStart(time: Long) {
        if (mHadPlay && mDanmakuView != null && mDanmakuView!!.isPrepared) {
            mDanmakuView!!.seekTo(time)
        }
    }


    private fun resolveDanmakuShow() {
        post {
            if (VideoPlay.danmakuShow) {
                if (!mDanmakuView!!.isShown) mDanmakuView!!.show()
                mToggleDanmaku?.text = "关弹幕"
            } else {
                if (mDanmakuView!!.isShown) mDanmakuView!!.hide()
                mToggleDanmaku?.text = "开弹幕"
            }
        }
    }

    /**
     * 创建解析器对象，解析输入流
     *
     * @param stream
     * @return
     */
    private fun createParser(danmakuFile: File?, danmakuStr: String?): BaseDanmakuParser {
        val loader = DanmakuLoaderFactory.create(DanmakuLoaderFactory.TAG_BILI)
        try {
            if (danmakuFile != null) {
                loader.load(FileInputStream(danmakuFile))
            } else if (danmakuStr != null) {
                if (danmakuStr.startsWith("http",true)) {
                    loader.load(danmakuStr)
                } else {
                    loader.load(danmakuStr.byteInputStream())
                }
            }
        } catch (e: IllegalDataException) {
            e.printStackTrace()
        }
        val parser: BaseDanmakuParser = BiliDanmukuParser()
        val dataSource = loader.dataSource
        parser.load(dataSource)
        return parser
    }

    private fun showEpisodeDialog() {
        if (!mHadPlay || VideoPlay.episodes.isNullOrEmpty()) {
            return
        }
        isChanging = true
        val choiceEpisodeDialog = ChoiceEpisodeDialog(mContext)
        choiceEpisodeDialog.initList(VideoPlay.episodes!!, object :
            ChoiceEpisodeDialog.OnListItemClickListener {
            override fun onItemClick(position: Int) {
                VideoPlay.chapterInVolumeIndex = position
                VideoPlay.saveRead(0)
                VideoPlay.startPlay(this@VideoPlayer)
            }

            override fun finishDialog() {
                isChanging = false
            }
        }, VideoPlay.chapterInVolumeIndex)
        choiceEpisodeDialog.show()
    }

    fun showPlaybackSpeedDialog() {
        if (mHadPlay && !isChanging) {
            showSpeedDialog()
        }
    }

    fun getPlaySpeed(): Float {
        return playSpeed
    }

    fun isPlayingForRestore(): Boolean {
        return mCurrentState == CURRENT_STATE_PLAYING
    }

    fun updateTitle(title: String?) {
        findViewById<TextView?>(R.id.title)?.text = title.orEmpty()
    }

    fun showResolvingLoading(show: Boolean) {
        getCurrentPlayer().post {
            val player = getCurrentPlayer()
            if (show) {
                player.findViewById<View?>(R.id.play_error)?.visibility = GONE
                player.findViewById<View?>(R.id.start)?.visibility = INVISIBLE
            }
            player.findViewById<View?>(R.id.resolving_loading)?.visibility =
                if (show) VISIBLE else GONE
        }
    }

    fun showPlayAddressError() {
        getCurrentPlayer().post {
            val player = getCurrentPlayer()
            cancelPlaybackLoadTimeout()
            player.findViewById<View?>(R.id.resolving_loading)?.visibility = GONE
            player.findViewById<View?>(R.id.loading)?.visibility = INVISIBLE
            player.findViewById<View?>(R.id.start)?.visibility = INVISIBLE
            player.findViewById<View?>(R.id.play_error)?.visibility = VISIBLE
        }
    }

    fun hidePlayAddressError() {
        getCurrentPlayer().post {
            getCurrentPlayer().findViewById<View?>(R.id.play_error)?.visibility = GONE
        }
    }

    private fun startPlaybackLoadTimeout() {
        cancelPlaybackLoadTimeout()
        playbackLoadTimeoutHandler.postDelayed(
            playbackLoadTimeoutRunnable,
            PLAYBACK_LOAD_TIMEOUT_MS
        )
    }

    private fun cancelPlaybackLoadTimeout() {
        playbackLoadTimeoutHandler.removeCallbacks(playbackLoadTimeoutRunnable)
    }

    private fun isWaitingForPlaybackStart(): Boolean {
        return mCurrentState == CURRENT_STATE_PREPAREING ||
                mCurrentState == CURRENT_STATE_PLAYING_BUFFERING_START
    }

    fun onSeamlessEpisodeChanged(title: String?) {
        hidePlayAddressError()
        nextUI()
        updateTitle(title)
        resetDanmaku()
        ensureVideoSurfaceBound()
    }

    private fun resetDanmaku() {
        releaseDanmaku(this)
        mDanmakuView = null
        mDanmakuContext = null
        mParser = null
        mDanmakuStartSeekPosition = 0
        initDanmaku()
        onPrepareDanmaku(this)
    }

    private fun showSpeedDialog() {
        if (!mHadPlay) {
            return
        }
        isChanging = true
        val choiceSpeedDialog = ChoiceSpeedDialog(mContext)
        choiceSpeedDialog.initList(listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f, 2.5f, 3.0f).reversed(), object :
            ChoiceSpeedDialog.OnListItemClickListener {
            @SuppressLint("SetTextI18n")
            override fun onItemClick(value: Float) {
                playSpeed = value
                setSpeed(playSpeed, true)
                if (playSpeed != 1.0f) {
                    playbackSpeed?.text = "${playSpeed}X"
                    showOverlayTip("${playSpeed}倍播放中", 2000)
                } else {
                    playbackSpeed?.text = "倍速"
                }
                onPlaySpeedChanged?.invoke(playSpeed)
            }

            override fun finishDialog() {
                isChanging = false
            }
        })
        choiceSpeedDialog.show()
    }

    override fun updateStartImage() {
        if (mIfCurrentIsFullscreen) {
            if (mStartButton is ImageView) {
                val imageView = mStartButton as ImageView
                when (mCurrentState) {
                    CURRENT_STATE_PLAYING -> {
                        imageView.setImageResource(R.drawable.ic_pause_24dp)
                    }
                    CURRENT_STATE_ERROR -> {
                        imageView.setImageResource(R.drawable.ic_pause_outline_24dp)
                    }
                    else -> {
                        imageView.setImageResource(R.drawable.ic_play_24dp)
                    }
                }
            }
        } else {
            super.updateStartImage()
        }
    }

    override fun onError(what: Int, extra: Int) {
        cancelPlaybackLoadTimeout()
        super.onError(what, extra)
        VideoPlay.saveRead()
        mSeekOnStart = VideoPlay.durChapterPos.toLong()
    }


    /**
     * 处理播放器在全屏切换时，弹幕显示的逻辑
     * 需要格外注意的是，因为全屏和小屏，是切换了播放器，所以需要同步之间的弹幕状态
     */
    override fun startWindowFullscreen(
        context: Context?,
        actionBar: Boolean,
        statusBar: Boolean
    ): VideoPlayer? {
        val gsyBaseVideoPlayer = super.startWindowFullscreen(context, actionBar, statusBar)
        if (gsyBaseVideoPlayer != null) {
            val gsyVideoPlayer = gsyBaseVideoPlayer as VideoPlayer
            //对弹幕设置偏移记录
//            gsyVideoPlayer.mDanmakuView = this.mDanmakuView
            gsyVideoPlayer.onPlaybackLoadTimeout = this.onPlaybackLoadTimeout
            gsyVideoPlayer.mDanmakuStartSeekPosition = this.getCurrentPositionWhenPlaying()
            onPrepareDanmaku(gsyVideoPlayer)
        }
        return gsyBaseVideoPlayer
    }

    /**
     * 处理播放器在退出全屏时，弹幕显示的逻辑
     * 需要格外注意的是，因为全屏和小屏，是切换了播放器，所以需要同步之间的弹幕状态
     */
    override fun resolveNormalVideoShow(
        oldF: View?,
        vp: ViewGroup?,
        gsyVideoPlayer: GSYVideoPlayer?
    ) {
        super.resolveNormalVideoShow(oldF, vp, gsyVideoPlayer)
        if (gsyVideoPlayer != null) {
            val videoPlayer = gsyVideoPlayer as VideoPlayer
            if (mDanmakuView != null && mDanmakuView!!.isPrepared) {
                resolveDanmakuSeek(videoPlayer.getCurrentPositionWhenPlaying())
                resolveDanmakuShow()
                releaseDanmaku(videoPlayer)
            }
        }
    }

    override fun release() {
        cancelPlaybackLoadTimeout()
        super.release()
        releaseDanmaku(this)
    }

    /**********以下重载GSYVideoPlayer的GSYVideoViewBridge相关实现***********/
    override fun getGSYVideoManager(): ExoVideoManager {
        return VideoPlay.videoManager.apply { initContext(context.applicationContext) }
    }
    public override fun backFromFull(context: Context?): Boolean {
        return VideoPlay.backFromWindowFull(context)
    }
    override fun releaseVideos() {
        VideoPlay.releaseAllVideos()
    }

    override fun getFullId(): Int {
        return ExoVideoManager.FULLSCREEN_ID
    }

    override fun getSmallId(): Int {
        return ExoVideoManager.SMALL_ID
    }
    override fun setDisplay(surface: Surface?) {
        val showView = mTextureView?.getShowView()
        if (surface != null && showView is SurfaceView) {
            val surfaceView = showView as SurfaceView?
            gsyVideoManager.setDisplayNew(surfaceView)
        } else if (surface != null) {
            gsyVideoManager.setDisplay(surface)
        } else {
            gsyVideoManager.setDisplayNew(null)
        }
    }

    override fun onVideoResume() {
        super.onVideoResume()
        cancelPlaybackLoadTimeout()
        ensureVideoSurfaceBound()
    }

    fun ensureVideoSurfaceBound() {
        post {
            rebindVideoSurface()
        }
        postDelayed({
            rebindVideoSurface()
        }, 120)
    }

    private fun rebindVideoSurface() {
        if (mSurface?.isValid == true) {
            setDisplay(mSurface)
        }
        mTextureView?.requestLayout()
        mTextureView?.invalidate()
    }

    fun nextUI() { resetProgressAndTime() }


    //播放器转移
    fun setSurfaceToPlay() {
        addTextureView()
        gsyVideoManager.setListener(this)
        checkoutState()
    }

    var needDestroy: Boolean = true
    override fun onSurfaceDestroyed(surface: Surface?): Boolean {
        if (needDestroy) {
            return super.onSurfaceDestroyed(surface)
        } else {
            releaseSurface(surface)
            needDestroy = true
            return true
        }
    }

    fun saveState(): VideoPlayer {
        return this
    }

    fun cloneState(switchVideo: StandardGSYVideoPlayer) {
        cloneParams(switchVideo, this)
    }
}
