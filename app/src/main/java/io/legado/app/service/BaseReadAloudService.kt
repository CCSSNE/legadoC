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
    internal var contentList = emptyList<String>()
    internal var nowSpeak: Int = 0
    internal var readAloudNumber: Int = 0
    internal var textChapter: TextChapter? = null
    internal var pageIndex = 0
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
    private var appFloatingActivity: Activity? = null
    private var currentAvoidanceSource: String? = null
    private var currentAvoidanceY: Int = 0
    private var rebuildFloatingJob: Job? = null
    private val isDesktopFloating: Boolean get() = floatingWindowManager != null
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
        val reader = appFloatingActivity as? ReadBookActivity
        if (reader != null && !ReadAloudUiState.readerMenuVisible) {
            removeReadAloudFloatingWindow()
            upReadAloudNotification()
            return
        }
        if (floatingView != null) {
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
            onReadAloudFloatingAttached(view)
        }.onFailure {
            AppLog.put("显示朗读悬浮窗失败\n${it.localizedMessage}", it)
        }
    }

    private fun showAppReadAloudFloatingWindow(activity: ReadBookActivity) {
        val root = activity.window?.decorView as? FrameLayout ?: return
        runCatching {
            val view = createReadAloudFloatingView()
            floatingView = view
            root.addView(
                view,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    floatingHeight
                ).apply {
                    gravity = Gravity.START or Gravity.TOP
                    leftMargin = readAloudFloatingX()
                    topMargin = readAloudFloatingYInRoot(root)
                }
            )
            onReadAloudFloatingAttached(view)
        }.onFailure {
            clearReadAloudFloatingRefs()
            AppLog.put("显示App内朗读悬浮窗失败\n${it.localizedMessage}", it)
        }
    }

    private fun onReadAloudFloatingAttached(view: View) {
        updateReadAloudFloatingCover()
        updateReadAloudFloatingPlayState()
        applyReadAloudFloatingAvoidance(currentAvoidanceY)
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

    private fun removeReadAloudFloatingWindow() {
        if (!isMainThread()) {
            lifecycleScope.launch(Main) {
                removeReadAloudFloatingWindow()
            }
            return
        }
        floatingView?.let { view ->
            runCatching {
                if (isDesktopFloating) {
                    floatingWindowManager?.removeView(view)
                } else {
                    (view.parent as? FrameLayout)?.removeView(view)
                }
            }
        }
        clearReadAloudFloatingRefs()
    }

    private fun clearReadAloudFloatingRefs() {
        floatingLoadingAnimator?.cancel()
        floatingLoadingAnimator = null
        floatingPlayPauseView?.rotation = 0f
        floatingView = null
        floatingParams = null
        floatingWindowManager = null
        floatingCoverView = null
        floatingPlayPauseView = null
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

    private fun readAloudFloatingYInRoot(root: View): Int {
        val rootLocation = IntArray(2)
        root.getLocationOnScreen(rootLocation)
        return (readAloudFloatingY() - rootLocation[1]).coerceAtLeast(0)
    }

    private fun updateReadAloudFloatingPosition(view: View, x: Int, y: Int) {
        val fixedX = x.coerceAtLeast(0)
        val params = floatingParams
        val manager = floatingWindowManager
        if (params != null && manager != null) {
            params.x = fixedX
            params.y = coerceReadAloudDesktopFloatingY(y)
            runCatching { manager.updateViewLayout(view, params) }
        } else {
            val fixedY = coerceReadAloudFloatingY(y)
            (view.layoutParams as? FrameLayout.LayoutParams)?.let {
                val root = view.parent as? View ?: return@let
                it.gravity = Gravity.START or Gravity.TOP
                it.leftMargin = fixedX
                it.topMargin = (fixedY - rootTopOnScreen(root)).coerceAtLeast(0)
                view.layoutParams = it
            }
        }
    }

    private fun saveReadAloudFloatingPosition(x: Int, y: Int) {
        appCtx.putPrefInt(PreferKey.readAloudFloatX, x.coerceAtLeast(0))
        val screenY = if (isDesktopFloating) desktopYToScreenY(y) else y
        appCtx.putPrefInt(PreferKey.readAloudFloatY, coerceReadAloudFloatingY(screenY))
    }

    private fun rootTopOnScreen(root: View): Int {
        val rootLocation = IntArray(2)
        root.getLocationOnScreen(rootLocation)
        return rootLocation[1]
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
                    val frameParams = layoutParams as? FrameLayout.LayoutParams
                    initialX = floatingParams?.x ?: frameParams?.leftMargin ?: 0
                    initialY = floatingParams?.y ?: (
                            (frameParams?.topMargin ?: 0) +
                                    ((parent as? View)?.let { rootTopOnScreen(it) } ?: 0)
                            )
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
                    val frameParams = layoutParams as? FrameLayout.LayoutParams
                    saveReadAloudFloatingPosition(
                        floatingParams?.x ?: frameParams?.leftMargin ?: initialX,
                        floatingParams?.y ?: (
                                (frameParams?.topMargin ?: initialY) +
                                        ((parent as? View)?.let { rootTopOnScreen(it) } ?: 0)
                                )
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
            newReadAloud(play, pageIndex, startPos)
        }
        observeEvent<Bundle>(EventBus.READ_ALOUD_FLOATING_AVOIDANCE) {
            val source = it.getString("source").orEmpty()
            val y = it.getInt("y")
            onReadAloudFloatingAvoidance(source, y)
        }
        observeEvent<Boolean>(EventBus.READ_BOOK_ACTIVITY_ACTIVE) {
            if (it) {
                appFloatingActivity = ReadBookActivity.activeActivity() ?: appFloatingActivity
                showReadAloudFloatingWindow()
            } else {
                currentAvoidanceSource = null
                currentAvoidanceY = 0
                applyReadAloudFloatingAvoidance(0)
            }
        }
        observeEvent<Boolean>(EventBus.READ_ALOUD_DIALOG_VISIBILITY) {
            showReadAloudFloatingWindow()
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
            }
        }
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

    private fun onReadAloudFloatingAvoidance(source: String, y: Int) {
        if (source.isBlank()) {
            return
        }
        if (y > 0) {
            currentAvoidanceSource = source
            currentAvoidanceY = y
            applyReadAloudFloatingAvoidance(y)
        } else if (source == currentAvoidanceSource) {
            currentAvoidanceSource = null
            currentAvoidanceY = 0
            applyReadAloudFloatingAvoidance(0)
        }
    }

    private fun applyReadAloudFloatingAvoidance(obstructionTop: Int) {
        val view = floatingView ?: return
        val baseY = readAloudFloatingY()
        val height = view.height.takeIf { it > 0 } ?: floatingHeight
        val gap = 10.dpToPx()
        val params = floatingParams
        val manager = floatingWindowManager
        if (params != null && manager != null) {
            val baseDesktopY = readAloudDesktopFloatingY()
            val targetY = if (obstructionTop > 0) {
                minOf(baseDesktopY, screenYToDesktopY(obstructionTop) - height - gap)
                    .coerceAtLeast(floatingMinY)
            } else {
                baseDesktopY
            }
            if (params.y != targetY) {
                params.y = targetY
                runCatching { manager.updateViewLayout(view, params) }
            }
            return
        }
        val frameParams = view.layoutParams as? FrameLayout.LayoutParams ?: return
        val activity = appFloatingActivity ?: return
        val root = activity.window?.decorView as? FrameLayout ?: return
        val rootLocation = IntArray(2)
        root.getLocationOnScreen(rootLocation)
        val targetY = if (obstructionTop > 0) {
            minOf(baseY, obstructionTop - height - gap)
                .coerceAtLeast(rootLocation[1] + floatingMinY)
        } else {
            baseY
        }
        val targetTopMargin = (targetY - rootLocation[1]).coerceAtLeast(0)
        if (frameParams.topMargin != targetTopMargin) {
            frameParams.gravity = Gravity.START or Gravity.TOP
            frameParams.topMargin = targetTopMargin
            view.layoutParams = frameParams
        }
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
            IntentAction.play -> newReadAloud(
                intent.getBooleanExtra("play", true),
                intent.getIntExtra("pageIndex", ReadBook.durPageIndex),
                intent.getIntExtra("startPos", 0)
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

    private fun newReadAloud(play: Boolean, pageIndex: Int, startPos: Int) {
        execute(executeContext = IO) {
            val chapter = ReadBook.curTextChapter ?: return@execute
            if (!prepareReadAloudChapter(chapter, pageIndex, startPos)) {
                return@execute
            }
            launch(Main) {
                if (play) play() else pageChanged = true
            }
        }.onError {
            AppLog.put("启动朗读出错\n${it.localizedMessage}", it, true)
        }
    }

    private fun prepareReadAloudChapter(
        chapter: TextChapter,
        pageIndex: Int,
        startPos: Int
    ): Boolean {
        textChapter = chapter
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
        var pos = startPos.coerceAtLeast(0)
        if (pos > 0) {
            for (paragraph in page.paragraphs) {
                val tmp = pos - paragraph.length - 1
                if (tmp < 0) break
                pos = tmp
            }
        }
        nowSpeak = chapter.getParagraphNum(readAloudNumber + 1, readAloudByPage) - 1
        nowSpeak = if (contentList.isEmpty()) {
            0
        } else {
            nowSpeak.coerceIn(0, contentList.lastIndex)
        }
        if (!readAloudByPage && startPos == 0 && !toLast && nowSpeak in chapter.paragraphs.indices) {
            pos = page.chapterPosition - chapter.paragraphs[nowSpeak].chapterPosition
        }
        if (toLast) {
            toLast = false
            readAloudNumber = chapter.getLastParagraphPosition()
            nowSpeak = contentList.lastIndex.coerceAtLeast(0)
            if (contentList.isNotEmpty() && page.paragraphs.size == 1 && nowSpeak in chapter.paragraphs.indices) {
                pos = page.chapterPosition - chapter.paragraphs[nowSpeak].chapterPosition
            }
        }
        paragraphStartPos = pos
        publishParagraphProgress()
        return true
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
        val chapterIndex = textChapter?.chapter?.index ?: ReadBook.durChapterIndex
        if (chapterIndex == ReadBook.durChapterIndex) {
            ReadBook.durChapterPos = progress
            ReadBook.book?.durChapterPos = progress
        }
        postEvent(EventBus.TTS_PROGRESS, Bundle().apply {
            putInt("chapterIndex", chapterIndex)
            putInt("chapterPos", progress)
        })
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
            ReadBook.moveToPrevPage()
        }
    }

    internal fun moveReadBookToNextPageForReadAloud() {
        if (!ReadBook.readAloudPageDetached) {
            ReadBook.moveToNextPage()
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
            toLast = true
            if (ReadBook.readAloudPageDetached) {
                switchDetachedReadAloudChapterByOffset(-1, toLast = true)
            } else {
                ReadBook.moveToPrevChapter(true, fromReadAloud = true)
            }
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
        val metadata = MediaMetadataCompat.Builder()
            .putBitmap(MediaMetadataCompat.METADATA_KEY_ART, cover)
            .putText(MediaMetadataCompat.METADATA_KEY_TITLE, currentReadAloudChapterTitle() ?: "null")
            .putText(MediaMetadataCompat.METADATA_KEY_ARTIST, nTitle)
            .putText(MediaMetadataCompat.METADATA_KEY_ALBUM, ReadBook.book?.author ?: "null")
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
        toLast = false
        resumeReadAloudInternal()
        if (ReadBook.readAloudPageDetached) {
            switchDetachedReadAloudChapterByOffset(-1, toLast = false)
        } else {
            ReadBook.moveToPrevChapter(true, toLast = false, fromReadAloud = true)
        }
    }

    open fun nextChapter() {
        ReadBook.upReadTime()
        AppLog.putDebug("${currentReadAloudChapterTitle()} 朗读结束跳转下一章并朗读")
        resumeReadAloudInternal()
        if (ReadBook.readAloudPageDetached) {
            switchDetachedReadAloudChapterByOffset(1, toLast = false)
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
        val sourceIndex = textChapter?.chapter?.index ?: run {
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
