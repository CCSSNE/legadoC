@file:Suppress("DEPRECATION")

package io.legado.app.service

import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.app.Activity
import android.app.Application
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Outline
import android.graphics.PixelFormat
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.annotation.CallSuper
import androidx.core.app.NotificationCompat
import androidx.core.view.setPadding
import androidx.lifecycle.lifecycleScope
import androidx.media.AudioFocusRequestCompat
import androidx.media.AudioManagerCompat
import io.legado.app.R
import io.legado.app.base.BaseService
import io.legado.app.constant.AppConst
import io.legado.app.constant.AppLog
import io.legado.app.constant.AppPattern
import io.legado.app.constant.EventBus
import io.legado.app.constant.IntentAction
import io.legado.app.constant.NotificationId
import io.legado.app.constant.PreferKey
import io.legado.app.constant.Status
import io.legado.app.help.MediaHelp
import io.legado.app.help.config.AppConfig
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.glide.ImageLoader
import io.legado.app.lib.permission.Permissions
import io.legado.app.lib.permission.PermissionsCompat
import io.legado.app.model.ReadAloud
import io.legado.app.model.ReadAloudPosition
import io.legado.app.model.ReadAloudUiState
import io.legado.app.model.ReadBook
import io.legado.app.receiver.MediaButtonReceiver
import io.legado.app.ui.book.audio.AudioPlayActivity
import io.legado.app.ui.book.read.ReadBookActivity
import io.legado.app.ui.book.read.page.entities.TextChapter
import io.legado.app.utils.LogUtils
import io.legado.app.utils.activityPendingIntent
import io.legado.app.utils.broadcastPendingIntent
import io.legado.app.utils.dpToPx
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.getPrefInt
import io.legado.app.utils.navigationBarHeight
import io.legado.app.utils.observeEvent
import io.legado.app.utils.observeSharedPreferences
import io.legado.app.utils.postEvent
import io.legado.app.utils.putPrefInt
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import splitties.init.appCtx
import splitties.systemservices.audioManager
import splitties.systemservices.notificationManager
import splitties.systemservices.powerManager
import splitties.systemservices.telephonyManager
import splitties.systemservices.wifiManager
import kotlin.math.abs

/**
 * 朗读服务
 */
abstract class BaseReadAloudService : BaseService(),
    AudioManager.OnAudioFocusChangeListener {

    companion object {
        @JvmStatic
        var isRun = false
            private set

        @JvmStatic
        var pause = true
            private set

        @JvmStatic
        var loading = false
            private set

        @JvmStatic
        var timeMinute: Int = 0
            private set

        @JvmStatic
        var runningClass: Class<*>? = null
            private set

        @Volatile
        var readAloudProgress: ReadAloudProgress? = null
            private set

        fun publishReadAloudProgress(progress: ReadAloudProgress) {
            readAloudProgress = progress
            postEvent(EventBus.READ_ALOUD_PROGRESS, progress)
        }

        fun isPlay(): Boolean {
            return isRun && !pause
        }

        private const val TAG = "BaseReadAloudService"
        private const val MIN_READ_ALOUD_PRELOAD_LENGTH = 300
    }

    private val useWakeLock = appCtx.getPrefBoolean(PreferKey.readAloudWakeLock, false)
    private val wakeLock by lazy {
        powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "legado:ReadAloudService")
            .apply {
                this.setReferenceCounted(false)
            }
    }
    private val wifiLock by lazy {
        @Suppress("DEPRECATION")
        wifiManager?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "legado:ReadAloudService")
            ?.apply {
                setReferenceCounted(false)
            }
    }
    private val mFocusRequest: AudioFocusRequestCompat by lazy {
        MediaHelp.buildAudioFocusRequestCompat(this)
    }
    private val mediaSessionCompat by lazy {
        MediaSessionCompat(this, "readAloud")
    }
    private val phoneStateListener by lazy {
        ReadAloudPhoneStateListener()
    }
    /**
     * 朗读引擎光标：contentList（读出单元序列）、nowSpeak（当前单元下标）、
     * readAloudNumber（单元内位置映射的章节绝对位置）、textChapter、pageIndex、
     * currentChapterIndex、paragraphStartPos（当前单元内部偏移）。
     * 所有权契约：这组状态是引擎私有的位置光标，只能由引擎自己的推进方法
     * （prepareReadAloudChapter / moveToNextParagraph / prevP / nextP / seek 系列）读写，
     * 对外唯一出口是 publishAloudPosition / publishParagraphProgress 两个发布点。
     * UI 与阅读模型不得直接读写其中任何一个字段。
     */
    internal var contentList = emptyList<String>()
    internal var nowSpeak: Int = 0
    internal var readAloudNumber: Int = 0
    internal var textChapter: TextChapter? = null
    internal var pageIndex = 0

    /**
     * 统一朗读会话的当前章节身份：BookChapter.index。
     * TTS 与书源音频都以它为共同章节身份；正文 TextChapter 只负责显示与字幕映射，
     * 不再决定音频当前正在播放哪一章。
     */
    internal var currentChapterIndex: Int = -1

    /**
     * 会话章节身份是否可以在正文 TextChapter 未就绪时建立。
     * 书源音频（SourceAudio）覆盖为 true：当前章节只认 BookChapter.index，
     * 正文未加载 / 未完成排版 / 无正文时也能启动播放；
     * TTS / HTTP TTS 必须等正文排版完成才能朗读，保持原前置条件。
     */
    protected open val sessionChapterCanStartWithoutText: Boolean get() = false
    private var needResumeOnAudioFocusGain = false
    private var needResumeOnCallStateIdle = false
    private var registeredPhoneStateListener = false
    private var dsJob: Job? = null
    private var upNotificationJob: Coroutine<*>? = null
    private var cover: Bitmap =
        BitmapFactory.decodeResource(appCtx.resources, R.drawable.icon_read_book)
    private var floatingWindowManager: WindowManager? = null
    private var floatingParams: WindowManager.LayoutParams? = null
    private var floatingView: View? = null
    private var floatingCoverView: ImageView? = null
    private var floatingPlayPauseView: ImageView? = null
    private var floatingLoadingAnimator: ObjectAnimator? = null
    private var floatingCoverAnimator: ObjectAnimator? = null
    private var appFloatingActivity: Activity? = null
    private var readAloudDialogFloatingHost: ReadAloudFloatingHost? = null
    private enum class FloatingHostMode {
        NONE,
        APPLICATION_PANEL,
        DESKTOP_OVERLAY,
    }

    private var floatingHostMode = FloatingHostMode.NONE
    private data class FloatingAvoidanceBounds(
        val topOnScreen: Int,
        val bottomOnScreen: Int,
    )

    private val avoidanceBounds = mutableMapOf<String, FloatingAvoidanceBounds>()
    private var dragBaseYOnScreen: Int? = null
    private var rebuildFloatingJob: Job? = null
    private val isDesktopFloating: Boolean
        get() = floatingHostMode == FloatingHostMode.DESKTOP_OVERLAY
    private val floatingHeight get() = 50.dpToPx()
    private val floatingMinY get() = 24.dpToPx()
    private val appFloatingLifecycleCallbacks = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
        override fun onActivityStarted(activity: Activity) = Unit
        override fun onActivityResumed(activity: Activity) {
            appFloatingActivity = activity
            if (activity is AudioPlayActivity) {
                removeReadAloudFloatingWindow()
                upReadAloudNotification()
                return
            }
            if (activity is ReadBookActivity && !ReadAloudUiState.readerMenuVisible) {
                removeReadAloudFloatingWindow()
                upReadAloudNotification()
                return
            }
            if (AppConfig.readAloudHideFloatingWindow) {
                removeReadAloudFloatingWindow()
                upReadAloudNotification()
                return
            }
            if (AppConfig.readAloudFloatOnDesktop && canDrawFloatingWindow()) {
                if (!isDesktopFloating) {
                    removeAppReadAloudFloatingWindow()
                    showReadAloudFloatingWindow()
                }
            } else {
                showReadAloudFloatingWindow()
            }
        }
        override fun onActivityPaused(activity: Activity) {
            if (appFloatingActivity === activity) {
                removeAppReadAloudFloatingWindow()
                appFloatingActivity = null
            }
        }
        override fun onActivityStopped(activity: Activity) {
            if (isRun &&
                (activity is AudioPlayActivity || activity is ReadBookActivity) &&
                canDrawFloatingWindow()
            ) {
                showReadAloudFloatingWindow()
            }
        }
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) {
            if (appFloatingActivity === activity) {
                removeAppReadAloudFloatingWindow()
                appFloatingActivity = null
            }
        }
    }
    var pageChanged = false
    private var toLast = false
    var paragraphStartPos = 0
    var readAloudByPage = false
        private set
    @Volatile
    private var readAloudStartRequest = 0L

    @Volatile
    private var preparedReadAloudStartRequest = -1L

    internal fun minReadAloudPreloadLength(): Int {
        return MIN_READ_ALOUD_PRELOAD_LENGTH
    }

    private val broadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (AudioManager.ACTION_AUDIO_BECOMING_NOISY == intent.action) {
                pauseReadAloud()
            }
        }
    }

    private fun canDrawFloatingWindow(): Boolean {
        return !AppConfig.readAloudHideFloatingWindow &&
                AppConfig.readAloudFloatOnDesktop &&
                (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this))
    }

    private fun showReadAloudFloatingWindow() {
        if (!isMainThread()) {
            lifecycleScope.launch(Main) {
                showReadAloudFloatingWindow()
            }
            return
        }
        if (AppConfig.readAloudHideFloatingWindow) {
            removeReadAloudFloatingWindow()
            upReadAloudNotification()
            return
        }
        if (appFloatingActivity is AudioPlayActivity) {
            removeReadAloudFloatingWindow()
            upReadAloudNotification()
            return
        }
        val activeReader = ReadBookActivity.activeActivity()
        val reader = appFloatingActivity as? ReadBookActivity ?: activeReader
        if (activeReader != null && !ReadAloudUiState.readerMenuVisible) {
            removeReadAloudFloatingWindow()
            upReadAloudNotification()
            return
        }
        if (floatingView != null) {
            if (!isDesktopFloating && reader == null) {
                removeReadAloudFloatingWindow()
                upReadAloudNotification()
            } else if (!isDesktopFloating && reader != null) {
                ensureAppReadAloudFloatingHost(resolveAppReadAloudFloatingHost(reader))
            }
            return
        }
        if (canDrawFloatingWindow()) {
            showDesktopReadAloudFloatingWindow()
        } else if (reader != null) {
            showAppReadAloudFloatingWindow(reader)
        } else {
            removeReadAloudFloatingWindow()
            upReadAloudNotification()
        }
    }

    private fun showDesktopReadAloudFloatingWindow() {
        runCatching {
            val windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
            val view = createReadAloudFloatingView()
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                floatingHeight,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                } else {
                    WindowManager.LayoutParams.TYPE_PHONE
                },
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.START or Gravity.TOP
                x = readAloudFloatingX()
                y = readAloudDesktopFloatingY()
            }
            windowManager.addView(view, params)
            floatingWindowManager = windowManager
            floatingParams = params
            floatingView = view
            floatingHostMode = FloatingHostMode.DESKTOP_OVERLAY
            onReadAloudFloatingAttached(view)
        }.onFailure {
            AppLog.put("显示朗读悬浮窗失败\n${it.localizedMessage}", it)
        }
    }

    private fun showAppReadAloudFloatingWindow(activity: ReadBookActivity) {
        runCatching {
            val view = createReadAloudFloatingView()
            addAppReadAloudFloatingWindow(
                resolveAppReadAloudFloatingHost(activity),
                view,
            )
            onReadAloudFloatingAttached(view)
        }.onFailure {
            clearReadAloudFloatingRefs()
            AppLog.put("显示App内朗读悬浮窗失败\n${it.localizedMessage}", it)
        }
    }

    private fun resolveAppReadAloudFloatingHost(
        activity: ReadBookActivity,
    ): ReadAloudFloatingHost {
        readAloudDialogFloatingHost?.let { return it }
        val token = activity.window?.decorView?.windowToken
            ?: error("ReadBookActivity window token is unavailable for the read-aloud panel")
        return ReadAloudFloatingHost(activity.windowManager, token)
    }

    private fun addAppReadAloudFloatingWindow(
        host: ReadAloudFloatingHost,
        view: View,
    ) {
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            floatingHeight,
            WindowManager.LayoutParams.TYPE_APPLICATION_PANEL,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.START or Gravity.TOP
            x = readAloudFloatingX()
            y = readAloudFloatingY()
            token = host.token
            title = "ReadAloudFloating"
        }
        host.windowManager.addView(view, params)
        floatingWindowManager = host.windowManager
        floatingParams = params
        floatingView = view
        floatingHostMode = FloatingHostMode.APPLICATION_PANEL
    }

    private fun ensureAppReadAloudFloatingHost(host: ReadAloudFloatingHost) {
        val view = floatingView ?: return
        if (floatingParams?.token == host.token) return
        detachFloatingView(view)
        try {
            addAppReadAloudFloatingWindow(host, view)
            onReadAloudFloatingAttached(view)
        } catch (error: Throwable) {
            clearReadAloudFloatingRefs()
            throw error
        }
    }

    private fun detachFloatingView(view: View) {
        val windowManager = checkNotNull(floatingWindowManager) {
            "Floating WindowManager is missing while a read-aloud panel is attached"
        }
        windowManager.removeView(view)
        floatingWindowManager = null
        floatingParams = null
        floatingHostMode = FloatingHostMode.NONE
    }

    private fun onReadAloudFloatingAttached(view: View) {
        updateReadAloudFloatingVisibility(true)
        updateReadAloudFloatingCover()
        updateReadAloudFloatingPlayState()
        applyReadAloudFloatingAvoidance()
    }

    private fun createReadAloudFloatingView(): View {
        val height = floatingHeight
        val coverSize = 40.dpToPx()
        val iconSize = 36.dpToPx()
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(5.dpToPx(), 0, 8.dpToPx(), 0)
            background = GradientDrawable().apply {
                cornerRadius = height / 2f
                setColor(Color.argb(214, 92, 128, 130))
                setStroke(1.dpToPx(), Color.argb(72, 255, 255, 255))
            }
        }
        floatingCoverView = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.TRANSPARENT)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: Outline) {
                        outline.setOval(0, 0, view.width, view.height)
                    }
                }
                clipToOutline = true
            }
            contentDescription = getString(R.string.continue_read)
            setOnClickListener { openReadAloudBook() }
        }
        container.addView(floatingCoverView, LinearLayout.LayoutParams(coverSize, coverSize))
        floatingPlayPauseView = ImageView(this).apply {
            setPadding(8.dpToPx())
            setColorFilter(Color.WHITE)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.argb(58, 255, 255, 255))
                setStroke(2.dpToPx(), Color.argb(84, 255, 255, 255))
            }
            contentDescription = getString(R.string.read_aloud_pause_resume)
            setOnClickListener {
                if (pause) {
                    ReadAloud.resume(this@BaseReadAloudService)
                } else {
                    ReadAloud.pause(this@BaseReadAloudService)
                }
            }
        }
        container.addView(
            floatingPlayPauseView,
            LinearLayout.LayoutParams(iconSize, iconSize).apply {
                marginStart = 10.dpToPx()
                marginEnd = 8.dpToPx()
            }
        )
        val closeView = ImageView(this).apply {
            setImageResource(R.drawable.ic_close_x)
            setColorFilter(Color.WHITE)
            setPadding(8.dpToPx())
            contentDescription = getString(R.string.stop)
            setOnClickListener {
                postEvent(EventBus.CLOSE_READ_ALOUD_DIALOG, true)
                ReadAloud.stop(this@BaseReadAloudService)
            }
        }
        container.addView(closeView, LinearLayout.LayoutParams(iconSize, iconSize))
        return ReadAloudFloatingLayout(this).apply {
            addView(
                container,
                FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, height)
            )
            if (!canDrawFloatingWindow()) {
                translationZ = 32.dpToPx().toFloat()
            }
        }
    }

    private fun updateReadAloudFloatingCover() {
        if (!isMainThread()) {
            lifecycleScope.launch(Main) {
                updateReadAloudFloatingCover()
            }
            return
        }
        floatingCoverView?.setImageDrawable(BitmapDrawable(resources, cover))
    }

    private fun updateReadAloudFloatingPlayState() {
        if (!isMainThread()) {
            lifecycleScope.launch(Main) {
                updateReadAloudFloatingPlayState()
            }
            return
        }
        floatingPlayPauseView?.setImageResource(
            when {
                loading -> R.drawable.ic_refresh_black_24dp
                pause -> R.drawable.ic_play_24dp
                else -> R.drawable.ic_pause_24dp
            }
        )
        updateFloatingLoadingAnimation()
        updateFloatingCoverAnimation()
    }

    private fun updateFloatingLoadingAnimation() {
        val view = floatingPlayPauseView ?: return
        if (loading) {
            if (floatingLoadingAnimator?.isStarted == true) return
            floatingLoadingAnimator?.cancel()
            floatingLoadingAnimator = ObjectAnimator.ofFloat(view, View.ROTATION, 0f, 360f).apply {
                duration = 900
                repeatCount = ObjectAnimator.INFINITE
                interpolator = LinearInterpolator()
                start()
            }
        } else {
            floatingLoadingAnimator?.cancel()
            floatingLoadingAnimator = null
            view.rotation = 0f
        }
    }

    private fun updateFloatingCoverAnimation(restart: Boolean = false) {
        val view = floatingCoverView ?: return
        if (AppConfig.readAloudCoverRotation && isPlay() && !loading) {
            if (!restart && floatingCoverAnimator?.isStarted == true) return
            floatingCoverAnimator?.cancel()
            val startRotation = view.rotation
            floatingCoverAnimator = ObjectAnimator.ofFloat(
                view,
                View.ROTATION,
                startRotation,
                startRotation + 360f
            ).apply {
                duration = AppConfig.readAloudCoverRotationDuration.toLong()
                repeatCount = ObjectAnimator.INFINITE
                interpolator = LinearInterpolator()
                start()
            }
        } else {
            floatingCoverAnimator?.cancel()
            floatingCoverAnimator = null
            view.rotation = 0f
        }
    }

    private fun removeReadAloudFloatingWindow() {
        if (!isMainThread()) {
            lifecycleScope.launch(Main) {
                removeReadAloudFloatingWindow()
            }
            return
        }
        floatingView?.let(::detachFloatingView)
        clearReadAloudFloatingRefs()
    }

    private fun clearReadAloudFloatingRefs() {
        floatingLoadingAnimator?.cancel()
        floatingLoadingAnimator = null
        floatingCoverAnimator?.cancel()
        floatingCoverAnimator = null
        floatingPlayPauseView?.rotation = 0f
        floatingCoverView?.rotation = 0f
        floatingView = null
        floatingParams = null
        floatingWindowManager = null
        floatingHostMode = FloatingHostMode.NONE
        avoidanceBounds.clear()
        dragBaseYOnScreen = null
        floatingCoverView = null
        floatingPlayPauseView = null
        updateReadAloudFloatingVisibility(false)
    }

    private fun updateReadAloudFloatingVisibility(visible: Boolean) {
        if (ReadAloudUiState.readAloudFloatingVisible == visible) return
        ReadAloudUiState.setReadAloudFloatingVisible(visible)
        postEvent(EventBus.READ_ALOUD_FLOATING_VISIBILITY, visible)
    }

    private fun removeAppReadAloudFloatingWindow() {
        if (isDesktopFloating) {
            return
        }
        removeReadAloudFloatingWindow()
    }

    private fun openReadAloudBook() {
        ReadAloud.openAudioPlayActivity(this)
    }

    private fun isMainThread(): Boolean {
        return Looper.myLooper() == Looper.getMainLooper()
    }

    private fun defaultReadAloudFloatingX() = 18.dpToPx()

    private fun defaultReadAloudFloatingY(): Int {
        return (floatingUsableHeight() - 220.dpToPx()).coerceAtLeast(72.dpToPx())
    }

    private fun readAloudFloatingX(): Int {
        return appCtx.getPrefInt(PreferKey.readAloudFloatX, defaultReadAloudFloatingX())
            .coerceAtLeast(0)
    }

    private fun readAloudFloatingY(): Int {
        return coerceReadAloudFloatingY(
            appCtx.getPrefInt(PreferKey.readAloudFloatY, defaultReadAloudFloatingY())
        )
    }

    private fun readAloudDesktopFloatingY(): Int {
        return coerceReadAloudDesktopFloatingY(screenYToDesktopY(readAloudFloatingY()))
    }

    private fun floatingUsableHeight(): Int {
        return resources.displayMetrics.heightPixels.coerceAtLeast(120.dpToPx())
    }

    private fun coerceReadAloudFloatingY(y: Int): Int {
        val maxY = (floatingUsableHeight() - floatingHeight - 10.dpToPx())
            .coerceAtLeast(floatingMinY)
        return y.coerceIn(floatingMinY, maxY)
    }

    private fun coerceReadAloudDesktopFloatingY(y: Int): Int {
        val maxY = screenYToDesktopY(floatingUsableHeight() - floatingHeight - 10.dpToPx())
            .coerceAtLeast(floatingMinY)
        return y.coerceIn(floatingMinY, maxY)
    }

    private fun screenYToDesktopY(y: Int): Int {
        return y - navigationBarHeight
    }

    private fun desktopYToScreenY(y: Int): Int {
        return y + navigationBarHeight
    }

    private fun updateReadAloudFloatingPosition(view: View, x: Int, y: Int) {
        val fixedX = x.coerceAtLeast(0)
        val params = checkNotNull(floatingParams) {
            "Floating layout params are missing while moving the read-aloud panel"
        }
        val manager = checkNotNull(floatingWindowManager) {
            "Floating WindowManager is missing while moving the read-aloud panel"
        }
        val requestedScreenY = if (isDesktopFloating) desktopYToScreenY(y) else y
        dragBaseYOnScreen = coerceReadAloudFloatingY(requestedScreenY)
        params.x = fixedX
        params.y = resolveFloatingWindowY(view)
        manager.updateViewLayout(view, params)
    }

    private fun saveReadAloudFloatingPosition(x: Int, y: Int) {
        appCtx.putPrefInt(PreferKey.readAloudFloatX, x.coerceAtLeast(0))
        val fallbackScreenY = if (isDesktopFloating) desktopYToScreenY(y) else y
        appCtx.putPrefInt(
            PreferKey.readAloudFloatY,
            dragBaseYOnScreen ?: coerceReadAloudFloatingY(fallbackScreenY),
        )
        dragBaseYOnScreen = null
        applyReadAloudFloatingAvoidance()
    }

    private inner class ReadAloudFloatingLayout(context: Context) : FrameLayout(context) {
        private var initialX = 0
        private var initialY = 0
        private var initialTouchX = 0f
        private var initialTouchY = 0f
        private var dragging = false

        init {
            isClickable = true
        }

        override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    val params = checkNotNull(floatingParams) {
                        "Floating layout params are missing when dragging starts"
                    }
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    dragging = false
                    return false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (kotlin.math.abs(dx) > 6.dpToPx() || kotlin.math.abs(dy) > 6.dpToPx()) {
                        dragging = true
                        return true
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    return dragging
                }
            }
            return dragging
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (!dragging) return super.onTouchEvent(event)
            when (event.action) {
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    updateReadAloudFloatingPosition(this, initialX + dx, initialY + dy)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val params = checkNotNull(floatingParams) {
                        "Floating layout params are missing when dragging ends"
                    }
                    saveReadAloudFloatingPosition(
                        params.x,
                        params.y,
                    )
                    dragging = false
                }
            }
            return true
        }
    }

    @SuppressLint("WakelockTimeout")
    override fun onCreate() {
        super.onCreate()
        readAloudProgress = null
        isRun = true
        pause = false
        runningClass = this::class.java
        observeLiveBus()
        initMediaSession()
        initBroadcastReceiver()
        initPhoneStateListener()
        application.registerActivityLifecycleCallbacks(appFloatingLifecycleCallbacks)
        appFloatingActivity = ReadBookActivity.activeActivity()
        upMediaSessionPlaybackState(PlaybackStateCompat.STATE_PLAYING)
        setTimer(AppConfig.ttsTimer)
        showReadAloudFloatingWindow()
        if (AppConfig.ttsTimer > 0) {
            toastOnUi("朗读定时 ${AppConfig.ttsTimer} 分钟")
        }
        execute {
            ImageLoader
                .loadBitmap(this@BaseReadAloudService, ReadBook.book?.getDisplayCover())
                .submit()
                .get()
        }.onSuccess {
            if (it.width > 16 && it.height > 16) {
                cover = it
                updateReadAloudFloatingCover()
                upReadAloudNotification()
            }
        }
    }

    fun observeLiveBus() {
        observeEvent<Bundle>(EventBus.READ_ALOUD_PLAY) {
            val play = it.getBoolean("play")
            val pageIndex = it.getInt("pageIndex")
            val startPos = it.getInt("startPos")
            requestReadAloud(play, pageIndex, startPos)
        }
        observeEvent<ReadAloudFloatingObstruction>(EventBus.READ_ALOUD_FLOATING_AVOIDANCE) {
            onReadAloudFloatingAvoidance(it)
        }
        observeEvent<ReadAloudDialogFloatingPresentation>(
            EventBus.READ_ALOUD_DIALOG_FLOATING_PRESENTATION
        ) {
            readAloudDialogFloatingHost = it.host
            showReadAloudFloatingWindow()
        }
        observeEvent<Boolean>(EventBus.READ_BOOK_ACTIVITY_ACTIVE) {
            if (it) {
                appFloatingActivity = ReadBookActivity.activeActivity() ?: appFloatingActivity
                showReadAloudFloatingWindow()
            } else {
                avoidanceBounds.clear()
                dragBaseYOnScreen = null
                applyReadAloudFloatingAvoidance()
            }
        }
        observeEvent<Boolean>(EventBus.READ_MAIN_MENU_VISIBILITY) {
            showReadAloudFloatingWindow()
        }
        observeSharedPreferences { _, key ->
            when (key) {
                PreferKey.ignoreAudioFocus,
                PreferKey.pauseReadAloudWhilePhoneCalls -> {
                    initPhoneStateListener()
                }
                PreferKey.readAloudFloatOnDesktop -> {
                    rebuildReadAloudFloatingWindow()
                    postEvent(PreferKey.readAloudFloatOnDesktop, "")
                }
                PreferKey.readAloudHideFloatingWindow -> {
                    rebuildReadAloudFloatingWindow()
                    upReadAloudNotification()
                    postEvent(PreferKey.readAloudHideFloatingWindow, "")
                }
                PreferKey.readAloudCoverRotation -> {
                    updateFloatingCoverAnimation()
                    postEvent(PreferKey.readAloudCoverRotation, "")
                }
                PreferKey.readAloudCoverRotationDuration -> {
                    updateFloatingCoverAnimation(restart = true)
                    postEvent(PreferKey.readAloudCoverRotationDuration, "")
                }
            }
        }
    }

    private fun requestReadAloud(play: Boolean, pageIndex: Int, startPos: Int) {
        val request = ++readAloudStartRequest
        preparedReadAloudStartRequest = -1L
        // Stop callbacks and playback owned by the previous target before preparing the new one.
        playStop()
        newReadAloud(request, play, pageIndex, startPos)
    }

    private fun rebuildReadAloudFloatingWindow() {
        removeReadAloudFloatingWindow()
        showReadAloudFloatingWindow()
    }

    private fun rebuildReadAloudFloatingWindowDelay() {
        rebuildFloatingJob?.cancel()
        rebuildFloatingJob = lifecycleScope.launch(Main) {
            delay(300)
            rebuildReadAloudFloatingWindow()
        }
    }

    @CallSuper
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        rebuildReadAloudFloatingWindowDelay()
    }

    private fun onReadAloudFloatingAvoidance(obstruction: ReadAloudFloatingObstruction) {
        if (obstruction.source.isBlank()) {
            return
        }
        if (obstruction.active) {
            check(
                obstruction.topOnScreen >= 0 &&
                    obstruction.bottomOnScreen > obstruction.topOnScreen
            ) {
                "Read-aloud floating obstruction has invalid bounds: " +
                    "[${obstruction.topOnScreen}, ${obstruction.bottomOnScreen}]"
            }
            avoidanceBounds[obstruction.source] = FloatingAvoidanceBounds(
                obstruction.topOnScreen,
                obstruction.bottomOnScreen,
            )
        } else {
            avoidanceBounds.remove(obstruction.source)
        }
        applyReadAloudFloatingAvoidance()
    }

    private fun applyReadAloudFloatingAvoidance() {
        val view = floatingView ?: return
        val params = checkNotNull(floatingParams) {
            "Floating layout params are missing while applying read-aloud avoidance"
        }
        val manager = checkNotNull(floatingWindowManager) {
            "Floating WindowManager is missing while applying read-aloud avoidance"
        }
        val targetY = resolveFloatingWindowY(view)
        if (params.y != targetY) {
            params.y = targetY
            manager.updateViewLayout(view, params)
        }
    }

    private fun resolveFloatingWindowY(view: View): Int {
        val height = view.height.takeIf { it > 0 } ?: floatingHeight
        val targetScreenY = resolveFloatingScreenY(height)
        return if (isDesktopFloating) screenYToDesktopY(targetScreenY) else targetScreenY
    }

    private fun resolveFloatingScreenY(height: Int): Int {
        val gap = 10.dpToPx()
        val minY = floatingMinY
        val maxY = (floatingUsableHeight() - height - gap).coerceAtLeast(minY)
        val baseY = (dragBaseYOnScreen ?: readAloudFloatingY()).coerceIn(minY, maxY)
        val candidates = linkedSetOf(baseY)
        avoidanceBounds.values.forEach { bounds ->
            candidates += (bounds.topOnScreen - height - gap).coerceIn(minY, maxY)
            candidates += (bounds.bottomOnScreen + gap).coerceIn(minY, maxY)
        }
        return candidates
            .asSequence()
            .filter { candidate ->
                avoidanceBounds.values.none { bounds ->
                    candidate + height + gap > bounds.topOnScreen &&
                        candidate < bounds.bottomOnScreen + gap
                }
            }
            .minByOrNull { candidate -> abs(candidate - baseY) }
            ?: error("No usable position remains for the read-aloud floating window")
    }

    override fun onDestroy() {
        super.onDestroy()
        releaseWakeLocks()
        isRun = false
        pause = true
        loading = false
        if (runningClass == this::class.java) {
            runningClass = null
            readAloudProgress = null
            ReadAloud.clearAloudPosition()
        }
        abandonFocus()
        unregisterReceiver(broadcastReceiver)
        postEvent(EventBus.ALOUD_STATE, Status.STOP)
        removeReadAloudFloatingWindow()
        notificationManager.cancel(NotificationId.ReadAloudService)
        application.unregisterActivityLifecycleCallbacks(appFloatingLifecycleCallbacks)
        upMediaSessionPlaybackState(PlaybackStateCompat.STATE_STOPPED)
        mediaSessionCompat.release()
        ReadBook.uploadProgress()
        unregisterPhoneStateListener(phoneStateListener)
        upNotificationJob?.invokeOnCompletion {
            notificationManager.cancel(NotificationId.ReadAloudService)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            IntentAction.play -> requestReadAloud(
                play = intent.getBooleanExtra("play", true),
                pageIndex = intent.getIntExtra("pageIndex", ReadBook.durPageIndex),
                startPos = intent.getIntExtra("startPos", 0),
            )

            IntentAction.pause -> pauseReadAloud()
            IntentAction.resume -> resumeReadAloud()
            IntentAction.upTtsSpeechRate -> upSpeechRate(true)
            IntentAction.prevParagraph -> prevP()
            IntentAction.nextParagraph -> nextP()
            IntentAction.seekReadAloudProgress -> seekToReadAloudProgress(
                intent.getIntExtra("chapterIndex", -1),
                intent.getIntExtra("position", -1)
            )
            IntentAction.seekReadAloudTextPosition -> seekToReadAloudTextPosition(
                intent.getIntExtra("chapterIndex", -1),
                intent.getIntExtra("chapterPosition", -1)
            )
            IntentAction.setSpeed -> setPlaybackSpeed(intent.getFloatExtra("speed", Float.NaN))
            IntentAction.prev -> prevChapter()
            IntentAction.next -> nextChapter()
            IntentAction.addTimer -> addTimer()
            IntentAction.setTimer -> setTimer(intent.getIntExtra("minute", 0))
            IntentAction.stop -> stopSelf()
        }
        return super.onStartCommand(intent, flags, startId)
    }

    private fun newReadAloud(
        request: Long,
        play: Boolean,
        pageIndex: Int,
        startPos: Int,
    ) {
        execute(executeContext = IO) {
            if (request != readAloudStartRequest) return@execute
            val textChapter = ReadBook.curTextChapter
            if (!sessionChapterCanStartWithoutText) {
                val chapter = textChapter ?: run {
                    cancelReadAloudStart(request)
                    return@execute
                }
                if (!prepareReadAloudChapter(chapter, pageIndex, startPos)) {
                    cancelReadAloudStart(request)
                    return@execute
                }
                preparedReadAloudStartRequest = request
                publishPreparedAloudPosition()
                launch(Main) {
                    if (request != readAloudStartRequest) return@launch
                    if (play) play() else pageChanged = true
                }
                return@execute
            }
            // 书源音频：会话章节身份先由统一阅读目标（ReadBook.durChapterIndex）确定，
            // 正文 TextChapter 只在 index 与该目标相同时用于段落/LRC 准备，绝不反向决定当前章节。
            currentChapterIndex = ReadBook.durChapterIndex
            textChapter?.takeIf { tc ->
                tc.chapter.index == currentChapterIndex &&
                    tc.isCompleted && tc.pageSize > 0
            }?.let { tc ->
                if (!prepareReadAloudChapter(tc, pageIndex, startPos)) {
                    cancelReadAloudStart(request)
                    return@execute
                }
            }
            preparedReadAloudStartRequest = request
            publishPreparedAloudPosition()
            launch(Main) {
                if (request != readAloudStartRequest) return@launch
                if (play) play() else pageChanged = true
            }
        }.onError {
            cancelReadAloudStart(request)
            AppLog.put("启动朗读出错\n${it.localizedMessage}", it, true)
        }
    }

    private fun cancelReadAloudStart(request: Long) {
        if (request != readAloudStartRequest) return
        preparedReadAloudStartRequest = -1L
        ReadAloud.cancelPositionSwitch()
    }

    private fun prepareReadAloudChapter(
        chapter: TextChapter,
        pageIndex: Int,
        startPos: Int
    ): Boolean {
        textChapter = chapter
        currentChapterIndex = chapter.chapter.index
        if (!chapter.isCompleted) {
            return false
        }
        if (chapter.pageSize <= 0) {
            stopReadAloudOnInvalidPosition("Read aloud chapter has no page")
            return false
        }
        val safePageIndex = pageIndex.coerceIn(0, chapter.pageSize - 1)
        this@BaseReadAloudService.pageIndex = safePageIndex
        val page = chapter.getPage(safePageIndex)
        if (page == null) {
            stopReadAloudOnInvalidPosition("Read aloud page is null, pageIndex=$safePageIndex")
            return false
        }
        readAloudNumber = chapter.getReadLength(safePageIndex) + startPos.coerceAtLeast(0)
        readAloudByPage = getPrefBoolean(PreferKey.readAloudByPage)
        contentList = chapter.getNeedReadAloud(0, readAloudByPage, 0)
            .split("\n")
            .filter { it.isNotEmpty() }
        nowSpeak = chapter.getParagraphNum(readAloudNumber + 1, readAloudByPage) - 1
        nowSpeak = if (contentList.isEmpty()) {
            0
        } else {
            nowSpeak.coerceIn(0, contentList.lastIndex)
        }
        // The reader resolves the absolute page-start position; the engine must preserve it.
        if (toLast) {
            toLast = false
            readAloudNumber = chapter.getLastParagraphPosition()
            nowSpeak = contentList.lastIndex.coerceAtLeast(0)
        }
        paragraphStartPos = resolveParagraphStartPos(chapter)
        publishParagraphProgress()
        return true
    }

    /**
     * [paragraphStartPos] 必须始终是 contentList[nowSpeak] 的内部偏移，绝不能是页内偏移。
     * 朗读单元只由 readAloudByPage 决定：按段=整段段落 getParagraphs(false)，
     * 按页=页内段落片段 getParagraphs(true)（与 getNeedReadAloud 的 split("\n") 单元一致）。
     * 两种模式共用同一公式：readAloudNumber 相对所在朗读单元起点的偏移。
     * 跨页段落的页首续读、页中起读都由此保持单元内偏移语义，不会被吞回单元开头。
     */
    private fun resolveParagraphStartPos(chapter: TextChapter): Int {
        val paragraphNum = chapter.getParagraphNum(readAloudNumber + 1, readAloudByPage)
        val paragraph = chapter.getParagraphs(readAloudByPage).getOrNull(paragraphNum - 1)
            ?: return 0
        return (readAloudNumber - paragraph.chapterPosition).coerceAtLeast(0)
    }

    private fun publishPreparedAloudPosition() {
        val chapterIndex = currentChapterIndex.takeIf { it >= 0 } ?: return
        val chapterPosition = textChapter?.takeIf { it.chapter.index == chapterIndex }?.let {
            readAloudNumber
        } ?: ReadBook.durChapterPos
        ReadAloud.publishAloudPosition(ReadAloudPosition(chapterIndex, chapterPosition))
    }

    @SuppressLint("WakelockTimeout")
    open fun play() {
        acquireWakeLocks()
        isRun = true
        pause = false
        loading = false
        needResumeOnAudioFocusGain = false
        needResumeOnCallStateIdle = false
        upReadAloudNotification()
        showReadAloudFloatingWindow()
        updateReadAloudFloatingPlayState()
        upMediaSessionPlaybackState(PlaybackStateCompat.STATE_PLAYING)
        postEvent(EventBus.ALOUD_STATE, Status.PLAY)
    }

    abstract fun playStop()

    @CallSuper
    open fun pauseReadAloud(abandonFocus: Boolean = true) {
        releaseWakeLocks()
        pause = true
        loading = false
        if (abandonFocus) {
            abandonFocus()
        }
        upReadAloudNotification()
        updateReadAloudFloatingPlayState()
        upMediaSessionPlaybackState(PlaybackStateCompat.STATE_PAUSED)
        postEvent(EventBus.ALOUD_STATE, Status.PAUSE)
        ReadBook.uploadProgress()
        doDs()
    }

    @SuppressLint("WakelockTimeout")
    private fun acquireWakeLocks() {
        if (!useWakeLock) return
        if (!wakeLock.isHeld) {
            wakeLock.acquire()
        }
        wifiLock?.let {
            if (!it.isHeld) {
                it.acquire()
            }
        }
    }

    private fun releaseWakeLocks() {
        if (!useWakeLock) return
        if (wakeLock.isHeld) {
            wakeLock.release()
        }
        wifiLock?.let {
            if (it.isHeld) {
                it.release()
            }
        }
    }

    protected fun stopReadAloudOnInvalidPosition(message: String) {
        AppLog.putDebug(message)
        lifecycleScope.launch(Main) {
            stopSelf()
        }
    }

    @SuppressLint("WakelockTimeout")
    @CallSuper
    open fun resumeReadAloud() {
        resumeReadAloudInternal()
    }

    private fun resumeReadAloudInternal() {
        pause = false
        loading = false
        needResumeOnAudioFocusGain = false
        needResumeOnCallStateIdle = false
        upReadAloudNotification()
        showReadAloudFloatingWindow()
        updateReadAloudFloatingPlayState()
        upMediaSessionPlaybackState(PlaybackStateCompat.STATE_PLAYING)
        postEvent(EventBus.ALOUD_STATE, Status.PLAY)
    }

    abstract fun upSpeechRate(reset: Boolean = false)

    fun upTtsProgress(progress: Int) {
        publishParagraphProgress()
        postReadAloudTextPosition(progress)
    }

    protected fun postReadAloudTextPosition(progress: Int) {
        if (preparedReadAloudStartRequest != readAloudStartRequest) return
        val chapterIndex = currentChapterIndex.takeIf { it >= 0 } ?: ReadBook.durChapterIndex
        val position = ReadAloudPosition(chapterIndex, progress)
        ReadAloud.publishAloudPosition(position)
        // 阅读页与朗读页脱离（detach）时，阅读位置保持用户当前翻到的页，
        // 不得被朗读进度覆盖——否则“从本页听”通过 durPageIndex 取起点时
        // 会被污染成原进度页，表现成点了“原进度”。
        if (chapterIndex == ReadBook.durChapterIndex && !ReadBook.readAloudPageDetached) {
            ReadBook.durChapterPos = progress
            ReadBook.book?.durChapterPos = progress
        }
    }

    private fun publishParagraphProgress() {
        val chapter = textChapter ?: return
        if (contentList.isEmpty() || nowSpeak !in contentList.indices) {
            return
        }
        publishReadAloudProgress(
            ReadAloudProgress(
                chapterIndex = chapter.chapter.index,
                position = nowSpeak,
                total = contentList.size,
                kind = ReadAloudProgress.Kind.PARAGRAPH,
            )
        )
    }

    protected open fun seekToReadAloudProgress(chapterIndex: Int, position: Int) {
        val chapter = textChapter ?: run {
            stopReadAloudOnInvalidPosition("Read aloud seek failed: chapter is missing")
            return
        }
        if (chapter.chapter.index != chapterIndex) {
            AppLog.putDebug(
                "Ignore stale read aloud seek: requestedChapter=$chapterIndex, " +
                        "currentChapter=${chapter.chapter.index}"
            )
            publishParagraphProgress()
            return
        }
        if (position !in contentList.indices) {
            stopReadAloudOnInvalidPosition(
                "Read aloud seek paragraph is out of range: " +
                        "paragraph=$position, total=${contentList.size}"
            )
            return
        }
        val paragraphs = chapter.getParagraphs(readAloudByPage)
        val paragraph = paragraphs.getOrNull(position) ?: run {
            stopReadAloudOnInvalidPosition(
                "Read aloud paragraph mapping is inconsistent: " +
                        "content=${contentList.size}, layout=${paragraphs.size}, " +
                        "paragraph=$position"
            )
            return
        }
        val targetPageIndex = chapter.getPageIndexByCharIndex(paragraph.chapterPosition)
        if (targetPageIndex !in 0 until chapter.pageSize) {
            stopReadAloudOnInvalidPosition(
                "Read aloud seek page is invalid: page=$targetPageIndex, " +
                        "paragraph=$position"
            )
            return
        }

        val resumeAfterSeek = !pause
        playStop()
        nowSpeak = position
        readAloudNumber = paragraph.chapterPosition
        paragraphStartPos = 0
        pageIndex = targetPageIndex
        AppLog.putDebug(
            "Read aloud seek: chapter=$chapterIndex, paragraph=$position, " +
                    "chapterPosition=$readAloudNumber, page=$pageIndex"
        )
        upTtsProgress(readAloudNumber + 1)
        if (resumeAfterSeek) {
            play()
        }
    }

    protected open fun seekToReadAloudTextPosition(
        chapterIndex: Int,
        chapterPosition: Int
    ) {
        val chapter = textChapter ?: run {
            stopReadAloudOnInvalidPosition("Read aloud text seek failed: chapter is missing")
            return
        }
        if (chapter.chapter.index != chapterIndex) {
            AppLog.putDebug(
                "Ignore stale read aloud text seek: requestedChapter=$chapterIndex, " +
                        "currentChapter=${chapter.chapter.index}"
            )
            publishParagraphProgress()
            return
        }
        val paragraphs = chapter.getParagraphs(readAloudByPage)
        val position = paragraphs.indexOfFirst { chapterPosition in it.chapterIndices }
        if (position !in contentList.indices) {
            stopReadAloudOnInvalidPosition(
                "Read aloud text seek mapping is inconsistent: chapterPosition=$chapterPosition, " +
                        "paragraph=$position, content=${contentList.size}, layout=${paragraphs.size}"
            )
            return
        }
        seekToReadAloudProgress(chapterIndex, position)
    }

    protected open fun setPlaybackSpeed(speed: Float) {
        stopReadAloudOnInvalidPosition(
            "Read aloud engine does not support direct playback speed: ${this::class.java.name}"
        )
    }

    protected fun upReadAloudLoading(loading: Boolean) {
        if (!isRun || pause || BaseReadAloudService.loading == loading) {
            return
        }
        BaseReadAloudService.loading = loading
        upReadAloudNotification()
        updateReadAloudFloatingPlayState()
        postEvent(EventBus.ALOUD_STATE, if (loading) Status.LOADING else Status.PLAY)
    }

    internal fun moveReadBookToPrevPageForReadAloud() {
        if (!ReadBook.readAloudPageDetached) {
            ReadBook.moveToPrevPage(fromReadAloud = true)
        }
    }

    internal fun moveReadBookToNextPageForReadAloud() {
        if (!ReadBook.readAloudPageDetached) {
            ReadBook.moveToNextPage(fromReadAloud = true)
        }
    }

    private fun prevP() {
        if (nowSpeak > 0) {
            playStop()
            do {
                nowSpeak--
                readAloudNumber -= contentList[nowSpeak].length + 1 + paragraphStartPos
                paragraphStartPos = 0
            } while (nowSpeak > 0 && contentList[nowSpeak].matches(AppPattern.notReadAloudRegex))
            textChapter?.let {
                if (readAloudByPage) {
                    val paragraphs = it.getParagraphs(true)
                    if (!paragraphs[nowSpeak].isParagraphEnd) readAloudNumber++
                }
                if (readAloudNumber < it.getReadLength(pageIndex)) {
                    pageIndex--
                    moveReadBookToPrevPageForReadAloud()
                }
            }
            upTtsProgress(readAloudNumber + 1)
            play()
        } else {
            advanceToPrevChapter(toLast = true)
        }
    }

    private fun nextP() {
        if (nowSpeak < contentList.size - 1) {
            playStop()
            readAloudNumber += contentList[nowSpeak].length.plus(1) - paragraphStartPos
            paragraphStartPos = 0
            nowSpeak++
            textChapter?.let {
                if (readAloudByPage) {
                    val paragraphs = it.getParagraphs(true)
                    if (!paragraphs[nowSpeak].isParagraphEnd) readAloudNumber--
                }
                if (pageIndex + 1 < it.pageSize
                    && readAloudNumber >= it.getReadLength(pageIndex + 1)
                ) {
                    pageIndex++
                    moveReadBookToNextPageForReadAloud()
                }
            }
            upTtsProgress(readAloudNumber + 1)
            play()
        } else {
            nextChapter()
        }
    }

    private fun setTimer(minute: Int) {
        timeMinute = minute
        doDs()
    }

    private fun addTimer() {
        if (timeMinute == 180) {
            timeMinute = 0
        } else {
            timeMinute += 10
            if (timeMinute > 180) timeMinute = 180
        }
        doDs()
    }

    /**
     * 定时
     */
    @Synchronized
    private fun doDs() {
        postEvent(EventBus.READ_ALOUD_DS, timeMinute)
        upReadAloudNotification()
        dsJob?.cancel()
        if (timeMinute <= 0) {
            return
        }
        dsJob = lifecycleScope.launch {
            while (isActive) {
                delay(60000)
                if (!pause) {
                    if (timeMinute > 0) {
                        timeMinute--
                    }
                    if (timeMinute == 0) {
                        ReadAloud.stop(this@BaseReadAloudService)
                        postEvent(EventBus.READ_ALOUD_DS, timeMinute)
                        break
                    }
                }
                postEvent(EventBus.READ_ALOUD_DS, timeMinute)
                upReadAloudNotification()
            }
        }
    }

    /**
     * 请求音频焦点
     * @return 音频焦点
     */
    fun requestFocus(): Boolean {
        if (AppConfig.ignoreAudioFocus) {
            return true
        }
        val requestFocus = MediaHelp.requestFocus(mFocusRequest)
        if (!requestFocus) {
            pauseReadAloud(false)
            toastOnUi("未获取到音频焦点")
        }
        return requestFocus
    }

    /**
     * 放弃音频焦点
     */
    private fun abandonFocus() {
        AudioManagerCompat.abandonAudioFocusRequest(audioManager, mFocusRequest)
    }

    /**
     * 更新媒体状态
     */
    private fun upMediaSessionPlaybackState(state: Int) {
        mediaSessionCompat.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(MediaHelp.MEDIA_SESSION_ACTIONS)
                .setState(state, nowSpeak.toLong(), 1f)
                // 为系统媒体控件添加定时按钮
                .addCustomAction(
                    "ACTION_ADD_TIMER",
                    getString(R.string.set_timer),
                    R.drawable.ic_time_add_24dp
                )
                .build()
        )
    }

    /**
     * 初始化MediaSession, 注册多媒体按钮
     */
    @SuppressLint("UnspecifiedImmutableFlag")
    private fun initMediaSession() {
        mediaSessionCompat.setFlags(
            MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or
                    MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS
        )
        mediaSessionCompat.setCallback(object : MediaSessionCompat.Callback() {
            override fun onPlay() {
                resumeReadAloud()
            }

            override fun onPause() {
                pauseReadAloud()
            }

            override fun onSkipToNext() {
                if (getPrefBoolean("mediaButtonPerNext", false)) {
                    nextChapter()
                } else {
                    nextP()
                }
            }

            override fun onSkipToPrevious() {
                if (getPrefBoolean("mediaButtonPerNext", false)) {
                    prevChapter()
                } else {
                    prevP()
                }
            }

            override fun onStop() {
                stopSelf()
            }

            override fun onCustomAction(action: String, extras: Bundle?) {
                if (action == "ACTION_ADD_TIMER") addTimer()
            }

            override fun onMediaButtonEvent(mediaButtonEvent: Intent): Boolean {
                return MediaButtonReceiver.handleIntent(
                    this@BaseReadAloudService, mediaButtonEvent
                )
            }
        })
        mediaSessionCompat.setMediaButtonReceiver(
            broadcastPendingIntent<MediaButtonReceiver>(Intent.ACTION_MEDIA_BUTTON)
        )
        mediaSessionCompat.isActive = true
    }

    private fun upMediaMetadata() {
        var nTitle: String = when {
            loading -> getString(R.string.loading)
            pause -> getString(R.string.read_aloud_pause)
            timeMinute > 0 -> getString(
                R.string.read_aloud_timer,
                timeMinute
            )

            else -> getString(R.string.read_aloud_t)
        }
        nTitle += ": ${ReadBook.book?.name}"
        // 章节标题可能已随暂停被卸载，回退到书名，避免系统媒体通知出现字面 "null" (issue #3)
        val metadataTitle = currentReadAloudChapterTitle()?.takeIf { it.isNotBlank() }
            ?: ReadBook.book?.name?.takeIf { it.isNotBlank() }
            ?: getString(R.string.read_aloud_s)
        val metadata = MediaMetadataCompat.Builder()
            .putBitmap(MediaMetadataCompat.METADATA_KEY_ART, cover)
            .putText(MediaMetadataCompat.METADATA_KEY_TITLE, metadataTitle)
            .putText(MediaMetadataCompat.METADATA_KEY_ARTIST, nTitle)
            .putText(
                MediaMetadataCompat.METADATA_KEY_ALBUM,
                ReadBook.book?.author?.takeIf { it.isNotBlank() } ?: ""
            )
//            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, nowSpeak.toLong())
            .build()
        mediaSessionCompat.setMetadata(metadata)
    }

    /**
     * 注册多媒体按钮监听
     */
    private fun initBroadcastReceiver() {
        val intentFilter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        registerReceiver(broadcastReceiver, intentFilter)
    }

    /**
     * 音频焦点变化
     */
    override fun onAudioFocusChange(focusChange: Int) {
        if (AppConfig.ignoreAudioFocus) {
            AppLog.put("忽略音频焦点处理(TTS)")
            return
        }
        when (focusChange) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                if (needResumeOnAudioFocusGain) {
                    AppLog.put("音频焦点获得,继续朗读")
                    resumeReadAloud()
                } else {
                    AppLog.put("音频焦点获得")
                }
            }

            AudioManager.AUDIOFOCUS_LOSS -> {
                AppLog.put("音频焦点丢失,暂停朗读")
                pauseReadAloud()
            }

            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                AppLog.put("音频焦点暂时丢失并会很快再次获得,暂停朗读")
                if (!pause) {
                    needResumeOnAudioFocusGain = true
                    pauseReadAloud(false)
                }
            }

            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                // 短暂丢失焦点，这种情况是被其他应用申请了短暂的焦点希望其他声音能压低音量（或者关闭声音）凸显这个声音（比如短信提示音），
                AppLog.put("音频焦点短暂丢失,不做处理")
            }
        }
    }

    private fun upReadAloudNotification() {
        upNotificationJob = execute {
            try {
                upMediaMetadata()
                val notification = createNotification()
                notificationManager.notify(NotificationId.ReadAloudService, notification.build())
            } catch (e: Exception) {
                AppLog.put("创建朗读通知出错,${e.localizedMessage}", e, true)
            }
        }
    }

    private fun createNotification(): NotificationCompat.Builder {
        var nTitle: String = when {
            loading -> getString(R.string.loading)
            pause -> getString(R.string.read_aloud_pause)
            timeMinute > 0 -> getString(
                R.string.read_aloud_timer,
                timeMinute
            )

            else -> getString(R.string.read_aloud_t)
        }
        nTitle += ": ${ReadBook.book?.name}"
        var nSubtitle = currentReadAloudChapterTitle()
        if (nSubtitle.isNullOrBlank())
            nSubtitle = getString(R.string.read_aloud_s)
        val builder = NotificationCompat
            .Builder(this, AppConst.channelIdReadAloud)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setSmallIcon(R.drawable.ic_status_bar_r)
            .setSubText(getString(R.string.read_aloud))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentTitle(nTitle)
            .setContentText(nSubtitle)
            .setContentIntent(
                activityPendingIntent<AudioPlayActivity>("readAloudPlayer") {
                    putExtra("bookUrl", ReadBook.book?.bookUrl)
                    putExtra("readAloudSession", true)
                }
            )
            .setVibrate(null)
            .setSound(null)
            .setLights(0, 0, 0)
        builder.setLargeIcon(cover)
        // 按钮定义：上一章、播放、停止、下一章、定时
        builder.addAction(
            R.drawable.ic_skip_previous,
            getString(R.string.previous_chapter),
            aloudServicePendingIntent(IntentAction.prev)
        )
        if (pause) {
            builder.addAction(
                R.drawable.ic_play_24dp,
                getString(R.string.resume),
                aloudServicePendingIntent(IntentAction.resume)
            )
        } else {
            builder.addAction(
                R.drawable.ic_pause_24dp,
                getString(R.string.pause),
                aloudServicePendingIntent(IntentAction.pause)
            )
        }
        builder.addAction(
            R.drawable.ic_skip_next,
            getString(R.string.next_chapter),
            aloudServicePendingIntent(IntentAction.next)
        )
        builder.addAction(
            R.drawable.ic_stop_black_24dp,
            getString(R.string.stop),
            aloudServicePendingIntent(IntentAction.stop)
        )
        builder.addAction(
            R.drawable.ic_time_add_24dp,
            getString(R.string.set_timer),
            aloudServicePendingIntent(IntentAction.addTimer)
        )
        builder.setStyle(androidx.media.app.NotificationCompat.MediaStyle()
            .setShowActionsInCompactView(0, 1, 2)
            .setMediaSession(mediaSessionCompat.sessionToken)
        )
        return builder
    }

    /**
     * 更新通知
     */
    override fun startForegroundNotification() {
        execute {
            try {
                upMediaMetadata()
                val notification = createNotification()
                startForeground(NotificationId.ReadAloudService, notification.build())
            } catch (e: Exception) {
                AppLog.put("创建朗读通知出错,${e.localizedMessage}", e, true)
                //创建通知出错不结束服务就会崩溃,服务必须绑定通知
                stopSelf()
            }
        }
    }

    abstract fun aloudServicePendingIntent(actionStr: String): PendingIntent?

    open fun prevChapter() {
        resumeReadAloudInternal()
        advanceToPrevChapter(toLast = false)
    }

    open fun nextChapter() {
        ReadBook.upReadTime()
        AppLog.putDebug("${currentReadAloudChapterTitle()} 朗读结束跳转下一章并朗读")
        resumeReadAloudInternal()
        advanceToNextChapter()
    }

    /**
     * 统一推进到上一章。
     * 书源音频（sessionChapterCanStartWithoutText）：以 BookChapter.index 立即推进，不等待正文 TextChapter。
     * 上一章正文已预加载完成时，moveToPrevChapter 的正文链路会经 curPageChanged 接管启动（附带段落进度）；
     * 预加载未完成时正文链路不会启动，音频必须立即以统一阅读目标（ReadBook.durChapterIndex）推进并播放，
     * 阅读页正文有则随后同步，无则音频继续。TTS / HTTP TTS 保持原有正文前置逻辑。
     */
    private fun advanceToPrevChapter(toLast: Boolean) {
        this.toLast = toLast
        if (ReadBook.readAloudPageDetached) {
            switchDetachedReadAloudChapterByOffset(-1, toLast)
        } else if (sessionChapterCanStartWithoutText) {
            val prevTextReady = ReadBook.prevTextChapter?.isCompleted == true
            if (!ReadBook.moveToPrevChapter(true, toLast = toLast, fromReadAloud = true)) {
                return
            }
            if (!prevTextReady) {
                currentChapterIndex = ReadBook.durChapterIndex
                nowSpeak = 0
                play()
            }
        } else {
            ReadBook.moveToPrevChapter(true, toLast = toLast, fromReadAloud = true)
        }
    }

    /**
     * 统一推进到下一章，语义同 [advanceToPrevChapter]。
     */
    private fun advanceToNextChapter() {
        if (ReadBook.readAloudPageDetached) {
            switchDetachedReadAloudChapterByOffset(1, toLast = false)
        } else if (sessionChapterCanStartWithoutText) {
            val nextTextReady = ReadBook.nextTextChapter?.isCompleted == true
            if (!ReadBook.moveToNextChapter(true, fromReadAloud = true)) {
                stopSelf()
                return
            }
            if (!nextTextReady) {
                currentChapterIndex = ReadBook.durChapterIndex
                nowSpeak = 0
                play()
            }
        } else {
            if (!ReadBook.moveToNextChapter(true, fromReadAloud = true)) {
                stopSelf()
            }
        }
    }

    private fun currentReadAloudChapterTitle(): String? {
        return textChapter?.title ?: ReadBook.curTextChapter?.title
    }

    private fun switchDetachedReadAloudChapterByOffset(offset: Int, toLast: Boolean) {
        val sourceIndex = currentChapterIndex.takeIf { it >= 0 } ?: run {
            stopReadAloudOnInvalidPosition("Detached read aloud chapter is missing")
            return
        }
        switchDetachedReadAloudChapter(sourceIndex + offset, toLast)
    }

    private fun switchDetachedReadAloudChapter(targetIndex: Int, toLast: Boolean) {
        if (targetIndex !in 0 until ReadBook.simulatedChapterSize) {
            this.toLast = false
            stopSelf()
            return
        }
        playStop()
        upReadAloudLoading(true)
        if (sessionChapterCanStartWithoutText) {
            // 书源音频：切章只认 BookChapter.index，不依赖正文 TextChapter，
            // 直接以目标章节建立会话身份并播放；段落进度随之复位，避免旧章节映射串位。
            currentChapterIndex = targetIndex
            this@BaseReadAloudService.toLast = toLast
            nowSpeak = 0
            lifecycleScope.launch(Main) {
                play()
            }
            return
        }
        execute(executeContext = IO) {
            val chapter = ReadBook.loadTextChapterForReadAloud(targetIndex, lifecycleScope)
                ?: run {
                    stopReadAloudOnInvalidPosition("Read aloud chapter not found, index=$targetIndex")
                    return@execute
                }
            this@BaseReadAloudService.toLast = toLast
            val startPageIndex = if (toLast) chapter.lastIndex else 0
            if (!prepareReadAloudChapter(chapter, startPageIndex, 0)) {
                return@execute
            }
            upTtsProgress(readAloudNumber + 1)
            launch(Main) {
                play()
            }
        }.onError {
            AppLog.put("切换朗读章节出错\n${it.localizedMessage}", it, true)
            stopReadAloudOnInvalidPosition("Switch read aloud chapter failed, index=$targetIndex")
        }
    }

    private fun initPhoneStateListener() {
        val needRegister = AppConfig.pauseReadAloudWhilePhoneCalls
        if (needRegister && registeredPhoneStateListener) {
            return
        }
        if (needRegister) {
            registerPhoneStateListener(phoneStateListener)
        } else {
            unregisterPhoneStateListener(phoneStateListener)
        }
    }

    private fun unregisterPhoneStateListener(l: PhoneStateListener) {
        if (registeredPhoneStateListener) {
            withReadPhoneStatePermission {
                telephonyManager.listen(l, PhoneStateListener.LISTEN_NONE)
                registeredPhoneStateListener = false
            }
        }
    }

    private fun registerPhoneStateListener(l: PhoneStateListener) {
        withReadPhoneStatePermission {
            telephonyManager.listen(l, PhoneStateListener.LISTEN_CALL_STATE)
            registeredPhoneStateListener = true
        }
    }

    private fun withReadPhoneStatePermission(block: () -> Unit) {
        try {
            block.invoke()
        } catch (_: SecurityException) {
            PermissionsCompat.Builder()
                .addPermissions(Permissions.READ_PHONE_STATE)
                .rationale(R.string.read_aloud_read_phone_state_permission_rationale)
                .onGranted {
                    try {
                        block.invoke()
                    } catch (_: SecurityException) {
                        LogUtils.d(TAG, "Grant read phone state permission fail.")
                    }
                }
                .request()
        }
    }

    @Suppress("OVERRIDE_DEPRECATION")
    inner class ReadAloudPhoneStateListener : PhoneStateListener() {
        override fun onCallStateChanged(state: Int, phoneNumber: String?) {
            super.onCallStateChanged(state, phoneNumber)
            when (state) {
                TelephonyManager.CALL_STATE_IDLE -> {
                    if (needResumeOnCallStateIdle) {
                        AppLog.put("来电结束,继续朗读")
                        resumeReadAloud()
                    } else {
                        AppLog.put("来电结束")
                    }
                }

                TelephonyManager.CALL_STATE_RINGING -> {
                    if (!pause) {
                        AppLog.put("来电响铃,暂停朗读")
                        needResumeOnCallStateIdle = true
                        pauseReadAloud()
                    } else {
                        AppLog.put("来电响铃")
                    }
                }

                TelephonyManager.CALL_STATE_OFFHOOK -> {
                    AppLog.put("来电接听,不做处理")
                }
            }
        }
    }

}
