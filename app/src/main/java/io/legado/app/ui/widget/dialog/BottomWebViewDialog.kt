package io.legado.app.ui.widget.dialog

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.util.Base64
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewConfiguration
import android.view.WindowManager
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.SslErrorHandler
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.annotation.Keep
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import io.legado.app.R
import io.legado.app.constant.AppConst
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.BaseSource
import io.legado.app.data.entities.Book
import io.legado.app.databinding.DialogWebViewBinding
import io.legado.app.help.WebCacheManager
import io.legado.app.help.config.AppConfig
import io.legado.app.help.review.ReviewSnapshotResourceStore
import io.legado.app.help.review.ReviewSnapshotManager
import io.legado.app.help.webView.PooledWebView
import io.legado.app.help.webView.WebJsExtensions
import io.legado.app.help.webView.WebJsExtensions.Companion.JS_INJECTION
import io.legado.app.help.webView.WebJsExtensions.Companion.basicJs
import io.legado.app.help.webView.WebJsExtensions.Companion.nameBasic
import io.legado.app.help.webView.WebJsExtensions.Companion.nameCache
import io.legado.app.help.webView.WebJsExtensions.Companion.nameJava
import io.legado.app.help.webView.WebJsExtensions.Companion.nameSource
import io.legado.app.help.webView.WebViewPool
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.ui.association.OnLineImportActivity
import io.legado.app.utils.invisible
import io.legado.app.utils.keepScreenOn
import io.legado.app.utils.longSnackbar
import io.legado.app.utils.openUrl
import io.legado.app.utils.setLayout
import io.legado.app.utils.startActivity
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import io.legado.app.utils.visible
import kotlinx.coroutines.launch
import androidx.core.view.size
import io.legado.app.constant.AppConst.imagePathKey
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.http.newCallResponse
import io.legado.app.help.http.newCallResponseBody
import io.legado.app.help.http.okHttpClient
import io.legado.app.help.http.text
import io.legado.app.help.webView.WebJsExtensions.Companion.JS_URL
import io.legado.app.help.webView.WebJsExtensions.Companion.nameUrl
import io.legado.app.help.webView.WebViewPool.BLANK_HTML
import io.legado.app.help.webView.WebViewPool.DATA_HTML
import io.legado.app.help.webView.WebViewHtmlStore
import io.legado.app.lib.dialogs.SelectItem
import io.legado.app.lib.dialogs.selector
import io.legado.app.model.Download
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.utils.ACache
import io.legado.app.utils.GSON
import io.legado.app.utils.configureOfflineResourceLoading
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.get
import io.legado.app.utils.writeBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayInputStream
import java.lang.ref.WeakReference
import java.net.URLDecoder
import java.util.Date
import androidx.core.graphics.createBitmap
import kotlin.math.abs
import kotlin.math.min

class BottomWebViewDialog() : BottomSheetDialogFragment(R.layout.dialog_web_view), WebJsExtensions.Callback {

    /**
     * “快照优先”模式的后台网络刷新器：返回最新在线评论页 (url, html)，
     * 加载成功后覆盖当前快照；失败/超时返回 null 则继续停留快照。
     */
    private var networkRefresher: (suspend () -> Pair<String, String>?)? = null

    /**
     * “网络优先”模式的兜底快照：网络加载失败/超时时切换为本地快照。
     */
    private var fallbackHtml: String? = null
    private var htmlFileReference: String? = null
    private var fallbackHtmlFileReference: String? = null
    private var fallbackApplied = false
    private var fallbackTimeoutRunnable: Runnable? = null

    /**
     * 离线模式（仅使用快照/快照兜底已启用）：WebView 禁止一切 http/https 网络请求，
     * 残余外部资源一律拦截，只允许 data: 与 review-resource:// 本地资源离线渲染。
     */
    private var offlineMode = false

    /** Set only for review snapshots that may contain review-resource:// references. */
    private var reviewResourceBook: Book? = null

    /**
     * 当前 WebView 显示的是否为评论快照内容（初始快照或网络失败后的兜底快照）。
     * 仅此时注入章评/书评补充 section；在线页自身的 tab 可用，无需注入。
     */
    @Volatile
    private var displayingSnapshotHtml = false

    /** 离线评论入队上下文：非空时页面加载完成后注入离线评论接管脚本 */
    private var outboxContext: io.legado.app.help.review.reviewoutbox.ReviewOutboxContext? = null

    private val mHandler = android.os.Handler(android.os.Looper.getMainLooper())

    constructor(
        sourceKey: String,
        bookType: Int,
        url: String,
        html: String? = null,
        preloadJs: String? = null,
        config: String? = null,
        networkRefresher: (suspend () -> Pair<String, String>?)? = null,
        fallbackHtml: String? = null,
        offlineOnly: Boolean = false,
        reviewResourceBook: Book? = null,
        outboxContext: io.legado.app.help.review.reviewoutbox.ReviewOutboxContext? = null,
    ) : this() {
        this.networkRefresher = networkRefresher
        this.fallbackHtml = fallbackHtml
        htmlFileReference = html?.let(WebViewHtmlStore::write)
        fallbackHtmlFileReference = fallbackHtml?.let(WebViewHtmlStore::write)
        this.offlineMode = offlineOnly
        this.reviewResourceBook = reviewResourceBook
        this.outboxContext = outboxContext
        arguments = Bundle().apply {
            putString("sourceKey", sourceKey)
            putInt("bookType", bookType)
            putString("url", url)
            // Large HTML (especially snapshots with inline images) must not enter
            // Fragment arguments: Android serializes arguments into the state Bundle.
            putString(ARG_HTML_FILE, htmlFileReference)
            putString(ARG_FALLBACK_HTML_FILE, fallbackHtmlFileReference)
            putString("preloadJs", preloadJs)
            putString("config", config)
            putParcelable(ARG_REVIEW_RESOURCE_BOOK, reviewResourceBook)
            outboxContext?.putTo(this)
        }
    }

    private val binding by viewBinding(DialogWebViewBinding::bind)
    private val bottomSheet by lazy {
        dialog?.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
    }
    private val behavior by lazy {
        bottomSheet?.let { sheet ->
            BottomSheetBehavior.from(sheet)
        }
    }
    private val displayMetrics by lazy { resources.displayMetrics }
    private val selectImageDir = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { uri ->
            ACache.get().put(imagePathKey, uri.toString())
            saveImage(it.value, uri)
        }
    }
    private lateinit var pooledWebView: PooledWebView
    private lateinit var currentWebView: WebView
    private var source: BaseSource? = null
    private var preloadJs: String? = null
    private var isFullScreen = false
    private var customWebViewCallback: WebChromeClient.CustomViewCallback? = null
    private var originOrientation: Int? = null
    private var needClearHistory = true
    private var pullDownToDismiss = true
    private var isPullDownDragging = false
    private var isPullDownDismissing = false
    private var pullDownStartX = 0f
    private var pullDownStartY = 0f
    private var pullDownDragStartY = 0f
    private var pullDownLastDistance = 0f
    private val touchSlop by lazy {
        ViewConfiguration.get(requireContext()).scaledTouchSlop
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Migrate fragments created by older versions before another Activity
        // state save can serialize their large legacy HTML argument.
        arguments?.getString(ARG_LEGACY_HTML)?.let { legacyHtml ->
            htmlFileReference = WebViewHtmlStore.write(legacyHtml)
            arguments?.putString(ARG_HTML_FILE, htmlFileReference)
            arguments?.remove(ARG_LEGACY_HTML)
        }
        reviewResourceBook = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arguments?.getParcelable(ARG_REVIEW_RESOURCE_BOOK, Book::class.java)
        } else {
            @Suppress("DEPRECATION")
            arguments?.getParcelable(ARG_REVIEW_RESOURCE_BOOK)
        }
        outboxContext = io.legado.app.help.review.reviewoutbox.ReviewOutboxContext.fromBundle(arguments)
    }

    override fun onAttach(context: Context) {
        super.onAttach(context)
        pooledWebView = WebViewPool.acquire(context)
        currentWebView = pooledWebView.realWebView
    }

    @Suppress("DEPRECATION")
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState)
        dialog.window?.let { window ->
            window.decorView.systemUiVisibility = activity?.window?.decorView?.systemUiVisibility ?: 0
            window.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
        }
        return dialog
    }

    override fun onStart() {
        super.onStart()
        setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }

    override fun show(manager: FragmentManager, tag: String?) {
        kotlin.runCatching {
            manager.beginTransaction().remove(this).commit()
            super.show(manager, tag)
        }.onFailure {
            AppLog.put("显示对话框失败 tag:$tag", it)
        }
    }

    private fun setConfig(config: Config, first: Boolean = false) {
        if (!isAdded || context == null) {
            return
        }
        behavior?.let { behavior ->
            config.state?.let { behavior.state = it }
            config.peekHeight?.let { behavior.peekHeight = it }
            config.isHideable?.let { behavior.isHideable = it }
            config.skipCollapsed?.let { behavior.skipCollapsed = it }
            config.setHalfExpandedRatio?.let { behavior.setHalfExpandedRatio(it) }
            config.setExpandedOffset?.let { behavior.setExpandedOffset(it) }
            config.setFitToContents?.let { behavior.setFitToContents(it) }
            config.isDraggable?.let { behavior.isDraggable = it }
            config.isDraggableOnNestedScroll?.let { behavior.isDraggableOnNestedScroll = it }
            config.significantVelocityThreshold?.let { behavior.significantVelocityThreshold = it }
            config.hideFriction?.let { behavior.hideFriction = it }
            config.maxWidth?.let { behavior.maxWidth = it }
            config.maxHeight?.let { behavior.maxHeight = it }
            config.isGestureInsetBottomIgnored?.let { behavior.isGestureInsetBottomIgnored = it }
            config.setUpdateImportantForAccessibilityOnSiblings?.let {
                behavior.setUpdateImportantForAccessibilityOnSiblings(it)
            }
        }

        config.expandedCornersRadius?.let {
            val radius = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, it, displayMetrics
            )
            bottomSheet?.let { sheet ->
                if (radius > 0) {
                    sheet.backgroundTintList = null
                    val shapeDrawable =
                        android.graphics.drawable.GradientDrawable().apply {
                            cornerRadius = 0f
                            cornerRadii = floatArrayOf(
                                radius, radius,
                                radius, radius,
                                0f, 0f,
                                0f, 0f
                            )
                        }
                    sheet.background = shapeDrawable
                    sheet.clipToOutline = true
                    if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2) {
                        currentWebView.outlineProvider =
                            object : android.view.ViewOutlineProvider() {
                                override fun getOutline(
                                    view: View,
                                    outline: android.graphics.Outline
                                ) {
                                    outline.setRoundRect(0, 0, view.width, view.height, radius)
                                }
                            }
                        currentWebView.clipToOutline = true
                        binding.customWebView.outlineProvider =
                            object : android.view.ViewOutlineProvider() {
                                override fun getOutline(
                                    view: View,
                                    outline: android.graphics.Outline
                                ) {
                                    outline.setRoundRect(0, 0, view.width, view.height, radius)
                                }
                            }
                        binding.customWebView.clipToOutline = true
                    }
                } else { //取消圆角
                    sheet.backgroundTintList = null
                    sheet.background = null
                    sheet.clipToOutline = false
                    if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2) {
                        currentWebView.outlineProvider = null
                        currentWebView.clipToOutline = false
                        binding.customWebView.outlineProvider = null
                        binding.customWebView.clipToOutline = false
                    }
                }
            }
        }

        dialog?.let { dialog ->
            config.backgroundDimAmount?.let { amount ->
                dialog.window?.setDimAmount(amount)
            }
            config.shouldDimBackground?.let { shouldDim ->
                if (!shouldDim) {
                    dialog.window?.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                }
            }
            config.dismissOnTouchOutside?.let { touchOutside ->
                isCancelable = touchOutside
            }
            config.hardwareAccelerated?.let { hwAccel ->
                if (hwAccel) {
                    dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED)
                }
            }
        }

        currentWebView.let { webView ->
            config.webViewInitialScale?.let { scale ->
                webView.settings.apply {
                    loadWithOverviewMode = true
                    useWideViewPort = true
                    textZoom = scale
                }
            }
            config.webViewCacheMode?.let { cacheMode ->
                webView.settings.cacheMode = cacheMode
            }
            config.isNestedScrollingEnabled?.let { enabled ->
                webView.isNestedScrollingEnabled = enabled
            }
        }

        bottomSheet?.let { sheet ->
            val params = sheet.layoutParams
            var hasChanged = false
            config.widthPercentage?.let { percentage ->
                if (percentage in 0.0..1.0) {
                    val width = (displayMetrics.widthPixels * percentage).toInt()
                    params.width = width
                    hasChanged = true
                }
            }

            val userHeightPercentage = if (first) AppConfig.bottomWebViewDialogHeight else null
            val configHeightPercentage = userHeightPercentage ?: config.heightPercentage
            val dialogHeight = config.dialogHeight
                ?.takeIf { userHeightPercentage == null }
                ?: if (first && configHeightPercentage == null) -1 else null
            dialogHeight?.let { height ->
                params.height = height
                hasChanged = true
            }
            configHeightPercentage?.let { percentage ->
                if (percentage in 0.0..1.0) {
                    val height = (displayMetrics.heightPixels * percentage).toInt()
                    params.height = height
                    // 同时更新peekHeight和最大高度
                    if (config.peekHeight == null) {
                        behavior?.peekHeight = height
                    }
                    if (config.maxHeight == null) {
                        behavior?.maxHeight = height
                    }
                    hasChanged = true
                }
            }
            if (hasChanged) {
                sheet.layoutParams = params
            }
        }

        config.responsiveBreakpoint?.let { breakpoint ->
            val screenWidth = displayMetrics.widthPixels
            if (screenWidth < breakpoint) {
                // 移动端布局（小屏幕）设置
                behavior?.peekHeight = config.peekHeight ?: 300
                config.widthPercentage?.let { percentage ->
                    if (percentage > 0.8f) {
                        // 小屏幕上最大宽度限制
                        val maxWidth = (screenWidth * 0.9).toInt()
                        behavior?.maxWidth = maxWidth
                    }
                }
            } else {
                // 平板/大屏幕布局设置
                behavior?.peekHeight = config.peekHeight ?: 400
                config.widthPercentage?.let { percentage ->
                    if (percentage < 0.6f) {
                        // 大屏幕上居中显示
                        bottomSheet?.layoutParams?.width =
                            (screenWidth * percentage).toInt()
                        (bottomSheet?.layoutParams as? FrameLayout.LayoutParams)?.gravity =
                            Gravity.CENTER_HORIZONTAL
                    }
                }
            }
        }

        val scrollNoDraggable = config.scrollNoDraggable ?: if (first) true else null
        scrollNoDraggable?.let {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (it) {
                    currentWebView.setOnScrollChangeListener { _, _, scrollY, _, _ ->
                        behavior?.isDraggable = scrollY == 0
                    }
                } else {
                    currentWebView.setOnScrollChangeListener(null)
                }
            }
        }

        val longClickSaveImg = config.longClickSaveImg ?: if (first) true else null
        longClickSaveImg?.let {
            if (it) {
                setLongClickSaveImg()
            } else {
                currentWebView.setOnLongClickListener(null)
            }
        }

        val pullDownToDismiss = config.pullDownToDismiss ?: if (first) true else null
        pullDownToDismiss?.let {
            setPullDownToDismiss(it)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setPullDownToDismiss(enabled: Boolean) {
        pullDownToDismiss = enabled
        resetPullDownState()
        currentWebView.setOnTouchListener(if (enabled) pullDownToDismissTouchListener else null)
    }

    private val pullDownToDismissTouchListener = View.OnTouchListener { _, event ->
        val sheet = bottomSheet ?: return@OnTouchListener false
        if (!pullDownToDismiss || isFullScreen || binding.customWebView.size > 0 || isPullDownDismissing) {
            return@OnTouchListener false
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                sheet.animate().cancel()
                pullDownStartX = event.rawX
                pullDownStartY = event.rawY
                pullDownLastDistance = 0f
                isPullDownDragging = false
                currentWebView.parent?.requestDisallowInterceptTouchEvent(true)
                false
            }

            MotionEvent.ACTION_MOVE -> {
                val distanceX = event.rawX - pullDownStartX
                val distanceY = event.rawY - pullDownStartY
                val isPullingDown = distanceY > touchSlop && distanceY > abs(distanceX)
                if (!isPullDownDragging && isPullingDown && currentWebView.scrollY <= 0) {
                    isPullDownDragging = true
                    pullDownDragStartY = event.rawY
                }
                if (isPullDownDragging) {
                    val pullDistance = (event.rawY - pullDownDragStartY).coerceAtLeast(0f)
                    pullDownLastDistance = pullDistance
                    sheet.translationY = pullDistance
                    true
                } else {
                    false
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                currentWebView.parent?.requestDisallowInterceptTouchEvent(false)
                if (isPullDownDragging) {
                    finishPullDown(sheet)
                    true
                } else {
                    resetPullDownState()
                    false
                }
            }

            else -> false
        }
    }

    private fun finishPullDown(sheet: View) {
        val dismissThreshold = min(
            sheet.height * 0.25f,
            TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 180f, displayMetrics)
        )
        isPullDownDragging = false
        if (pullDownLastDistance >= dismissThreshold) {
            isPullDownDismissing = true
            sheet.animate()
                .translationY(sheet.height.toFloat())
                .setDuration(180L)
                .withEndAction {
                    if (isAdded) {
                        dismissAllowingStateLoss()
                    }
                    isPullDownDismissing = false
                }
                .start()
        } else {
            sheet.animate()
                .translationY(0f)
                .setDuration(160L)
                .withEndAction {
                    resetPullDownState()
                }
                .start()
        }
    }

    private fun resetPullDownState() {
        isPullDownDragging = false
        pullDownLastDistance = 0f
        bottomSheet?.animate()?.cancel()
        bottomSheet?.translationY = 0f
    }

    private fun setLongClickSaveImg() {
        currentWebView.setOnLongClickListener {
            val hitTestResult = currentWebView.hitTestResult
            if (hitTestResult.type == WebView.HitTestResult.IMAGE_TYPE ||
                hitTestResult.type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE) {
                hitTestResult.extra?.let { webPic ->
                    requireContext().selector(
                        arrayListOf(
                            SelectItem(getString(R.string.action_save), "save"),
                            SelectItem(getString(R.string.select_folder), "selectFolder")
                        )
                    ) { _, charSequence, _ ->
                        when (charSequence.value) {
                            "save" -> saveImage(webPic)
                            "selectFolder" -> selectSaveFolder(null)
                        }
                    }
                    return@setOnLongClickListener true
                }
            }
            return@setOnLongClickListener false
        }
        currentWebView.setDownloadListener { url, _, contentDisposition, _, _ ->
            var fileName = URLUtil.guessFileName(url, contentDisposition, null)
            fileName = URLDecoder.decode(fileName, "UTF-8")
            currentWebView.longSnackbar(fileName, getString(R.string.action_download)) {
                Download.start(requireContext(), url, fileName)
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.setBackgroundColor(0)
        binding.webViewContainer.addView(currentWebView)
        setPullDownToDismiss(true)
        lifecycleScope.launch(IO) {
            val args = arguments
            if (args == null) {
                dismiss()
                return@launch
            }
            val sourceKey = args.getString("sourceKey") ?: return@launch
            val url = args.getString("url") ?: return@launch
            kotlin.runCatching {
                val htmlArgument = args.getString(ARG_HTML_FILE)?.let { reference ->
                    htmlFileReference = reference
                    WebViewHtmlStore.read(reference)
                        ?: throw NoStackTraceException("WebView HTML file is missing: $reference")
                }
                if (htmlArgument != null) {
                    displayingSnapshotHtml = true
                }
                val fallbackReference = args.getString(ARG_FALLBACK_HTML_FILE)
                if (fallbackReference != null) {
                    fallbackHtmlFileReference = fallbackReference
                    fallbackHtml = WebViewHtmlStore.read(fallbackReference)
                        ?: throw NoStackTraceException("WebView fallback HTML file is missing: $fallbackReference")
                }
                args.getString("config")?.let { json ->
                    try {
                        GSON.fromJsonObject<Config>(json).getOrThrow().let { config ->
                            activity?.runOnUiThread {
                                setConfig(config, true)
                            }
                        }
                        true
                    } catch (e: Exception) {
                        AppLog.put("config err", e)
                        null
                    }
                } ?: run {
                    activity?.runOnUiThread {
                        setConfig(Config(), true)
                    }
                }
                val analyzeUrl =
                    AnalyzeUrl(url, source = source, coroutineContext = coroutineContext)
                // 网络优先兜底：WebView 启动前的网络获取若直接失败/超时，
                // 也必须切换到快照显示，而不是显示异常文本
                var fetchedHtml = htmlArgument
                if (fetchedHtml == null) {
                    fetchedHtml = runCatching {
                        if (fallbackHtml.isNullOrBlank()) {
                            analyzeUrl.getStrResponseAwait().body
                        } else {
                            withTimeout(ReviewSnapshotManager.NETWORK_FALLBACK_LOAD_TIMEOUT_MS) {
                                analyzeUrl.getStrResponseAwait().body
                            }
                        }
                    }.getOrElse { e ->
                        fallbackHtml?.takeIf { it.isNotBlank() }?.also {
                            // 已用快照兜底：进入离线模式，不再允许任何网络请求
                            offlineMode = true
                        } ?: throw e
                    }
                }
                val html = fetchedHtml
                if (html.isNullOrEmpty()) {
                    throw NoStackTraceException("html is NullOrEmpty")
                }
                preloadJs = args.getString("preloadJs")
                val spliceHtml = if (preloadJs.isNullOrEmpty()) {
                    html
                } else {
                    val headIndex = html.indexOf("<head", ignoreCase = true)
                    if (headIndex >= 0) {
                        val closingHeadIndex = html.indexOf('>', startIndex = headIndex)
                        if (closingHeadIndex >= 0) {
                            val insertPos = closingHeadIndex + 1
                            StringBuilder(html).insert(insertPos, JS_URL).toString()
                        } else {
                            JS_URL + html
                        }
                    } else {
                        JS_URL + html
                    }
                }
                appDb.bookSourceDao.getBookSource(sourceKey).let {
                    if (it == null && htmlArgument.isNullOrEmpty()) {
                        // 评论快照等本地 HTML 可在无书源时离线渲染，不再强制要求书源存在
                        activity?.toastOnUi("no find bookSource")
                        dismiss()
                        return@launch
                    }
                    source = it
                }
                val bookType = args.getInt("bookType", 0)
                currentWebView.post {
                    currentWebView.onResume() //缓存库拿的需要激活
                    initWebView(analyzeUrl.url, spliceHtml, analyzeUrl.headerMap, bookType)
                    currentWebView.clearHistory()
                    // “快照优先”：快照先显示，后台刷新真实网络评论页成功后覆盖。
                    // 后台抓取必须跑在 IO，耗时网络/WebView 解析不能卡主线程；
                    // 刷新成功后切回主线程更新 WebView
                    val refresher = networkRefresher
                    if (refresher != null) {
                        lifecycleScope.launch(Dispatchers.IO) {
                            val refreshed = runCatching { refresher() }.getOrNull()
                            if (refreshed != null && isAdded && !isHidden) {
                                withContext(Dispatchers.Main) {
                                    // 在线覆盖页加载后不再是快照内容，撤回注入标记
                                    displayingSnapshotHtml = false
                                    currentWebView.loadDataWithBaseURL(
                                        refreshed.first.ifBlank { analyzeUrl.url },
                                        refreshed.second,
                                        "text/html",
                                        "utf-8",
                                        refreshed.first.ifBlank { analyzeUrl.url }
                                    )
                                }
                            }
                        }
                    }
                }
            }.onFailure {
                currentWebView.post {
                    currentWebView.resumeTimers()
                    currentWebView.onResume()
                    currentWebView.loadDataWithBaseURL(
                        url,
                        it.stackTraceToString(),
                        "text/html",
                        "utf-8",
                        url
                    )
                    currentWebView.clearHistory()
                }
            }
        }
        dialog?.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                if (binding.customWebView.size > 0) { //网页全屏
                    customWebViewCallback?.onCustomViewHidden()
                    return@setOnKeyListener true
                }
                if (currentWebView.canGoBack()) {
                    val list = currentWebView.copyBackForwardList()
                    val size = list.size
                    if (size == 1) {
                        dismiss()
                        return@setOnKeyListener true
                    }
                    val currentIndex = list.currentIndex
                    val currentItem = list.currentItem
                    val currentUrl = currentItem?.originalUrl ?: BLANK_HTML
                    val currentTitle = currentItem?.title
                    var steps = 1
                    for (i in currentIndex - 1 downTo 0) {
                        val item = list.getItemAtIndex(i)
                        val itemUrl = item.originalUrl
                        if (itemUrl == BLANK_HTML) {
                            dismiss()
                            return@setOnKeyListener true
                        }
                        if (itemUrl != currentUrl || currentTitle != item.title) {
                            break
                        }
                        if (currentUrl == DATA_HTML) {
                            break
                        }
                        steps++
                    }
                    if (steps == size) {
                        dismiss()
                        return@setOnKeyListener true
                    }
                    currentWebView.goBackOrForward(-steps)
                    return@setOnKeyListener true
                }
                dismiss()
                return@setOnKeyListener true
            }
            false
        }
    }

    private fun initWebView(
        url: String,
        html: String,
        headerMap: HashMap<String, String>,
        bookType: Int
    ) {
        currentWebView.webChromeClient = CustomWebChromeClient()
        currentWebView.addJavascriptInterface(JSInterface(this), nameBasic)
        currentWebView.webViewClient = CustomWebViewClient()
        outboxContext?.let { context ->
            currentWebView.addJavascriptInterface(
                io.legado.app.help.review.reviewoutbox.ReviewOutboxBridge(context),
                io.legado.app.help.review.reviewoutbox.ReviewOutboxWireUp.bridgeName
            )
        }
        currentWebView.settings.userAgentString = headerMap.get(AppConst.UA_NAME, true)
        // 离线快照禁止 http/https，但必须让 review-resource:// 图片进入资源拦截器。
        currentWebView.settings.configureOfflineResourceLoading(offlineMode)
        source?.let { source ->
            (activity as? AppCompatActivity)?.let { currentActivity ->
                val webJsExtensions =
                    WebJsExtensions(source, currentActivity, currentWebView, bookType, callback = this)
                currentWebView.addJavascriptInterface(webJsExtensions, nameJava)
            }
            currentWebView.addJavascriptInterface(source, nameSource)
            currentWebView.addJavascriptInterface(WebCacheManager, nameCache)
        }
        currentWebView.loadDataWithBaseURL(url, html, "text/html", "utf-8", url)
    }

    /** 网络优先兜底：主框架开始加载时启动真正的超时定时任务 */
    private fun scheduleFallbackTimeout() {
        if (fallbackHtml == null) return
        fallbackTimeoutRunnable?.let { mHandler.removeCallbacks(it) }
        fallbackTimeoutRunnable = Runnable { applyFallbackSnapshot() }
        mHandler.postDelayed(
            fallbackTimeoutRunnable!!,
            ReviewSnapshotManager.NETWORK_FALLBACK_LOAD_TIMEOUT_MS
        )
    }

    private fun cancelFallbackTimeout() {
        fallbackTimeoutRunnable?.let { mHandler.removeCallbacks(it) }
        fallbackTimeoutRunnable = null
    }

    /** 网络优先兜底：网络加载失败/真正超时后切换为本地评论快照 */
    private fun applyFallbackSnapshot() {
        val html = fallbackHtml ?: return
        if (fallbackApplied) return
        fallbackApplied = true
        cancelFallbackTimeout()
        // 兜底快照属于离线内容：切换后禁止一切网络请求
        offlineMode = true
        displayingSnapshotHtml = true
        currentWebView.settings.configureOfflineResourceLoading(true)
        currentWebView.loadDataWithBaseURL(
            currentWebView.url ?: "https://localhost/",
            html,
            "text/html",
            "utf-8",
            null
        )
    }

    private companion object {
        const val ARG_HTML_FILE = "htmlFile"
        const val ARG_FALLBACK_HTML_FILE = "fallbackHtmlFile"
        const val ARG_LEGACY_HTML = "html"
        const val ARG_REVIEW_RESOURCE_BOOK = "reviewResourceBook"
    }

    private fun saveImage(webPic: String) {
        val path = ACache.get().getAsString(imagePathKey)
        if (path.isNullOrEmpty()) {
            selectSaveFolder(webPic)
        } else {
            saveImage(webPic, path.toUri())
        }
    }

    private fun selectSaveFolder(webPic: String?) {
        val default = arrayListOf<SelectItem<Int>>()
        val path = ACache.get().getAsString(imagePathKey)
        if (!path.isNullOrEmpty()) {
            default.add(SelectItem(path, -1))
        }
        selectImageDir.launch {
            otherActions = default
            value = webPic
        }
    }

    private fun saveImage(webPic: String?, uri: Uri) {
        webPic ?: return
        Coroutine.async(lifecycleScope) {
            val fileName = "${AppConst.fileNameFormat.format(Date(System.currentTimeMillis()))}.jpg"
            val byteArray = webData2bitmap(webPic) ?: throw NoStackTraceException("NULL")
            uri.writeBytes(requireContext(), fileName, byteArray)
        }.onError {
            ACache.get().remove(imagePathKey)
            context?.toastOnUi("保存图片失败:${it.localizedMessage}")
        }.onSuccess {
            context?.toastOnUi("保存成功")
        }
    }

    private suspend fun webData2bitmap(data: String): ByteArray? {
        return if (URLUtil.isValidUrl(data)) {
            okHttpClient.newCallResponseBody {
                url(data)
            }.bytes()
        } else {
            Base64.decode(data.split(",").toTypedArray()[1], Base64.DEFAULT)
        }
    }

    override fun onDestroyView() {
        customWebViewCallback?.onCustomViewHidden()
        cancelFallbackTimeout()
        WebViewPool.release(pooledWebView)
        originOrientation?.let {
            activity?.requestedOrientation = it
        }
        super.onDestroyView()
    }

    override fun onDestroy() {
        // Configuration changes recreate the Fragment from its arguments. Keep
        // the files in that case so the restored dialog can read them again.
        if (activity?.isChangingConfigurations != true) {
            WebViewHtmlStore.delete(htmlFileReference)
            WebViewHtmlStore.delete(fallbackHtmlFileReference)
        }
        super.onDestroy()
    }

    override fun upConfig(config: String) {
        try {
            lifecycleScope.launch(Dispatchers.Main) {
                GSON.fromJsonObject<Config>(config).getOrThrow().let { config ->
                    setConfig(config)
                }
            }
        } catch (e: Exception) {
            AppLog.put("config err", e)
        }
    }

    @Suppress("unused")
    private class JSInterface(dialog: BottomWebViewDialog) {
        private val dialogRef: WeakReference<BottomWebViewDialog> = WeakReference(dialog)

        @JavascriptInterface
        fun lockOrientation(orientation: String) {
            val fra = dialogRef.get() ?: return
            val ctx = fra.requireActivity()
            if (fra.isFullScreen && fra.dialog?.isShowing == true) {
                fra.lifecycleScope.launch(Dispatchers.Main) {
                    ctx.requestedOrientation = when (orientation) {
                        "portrait", "portrait-primary" -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                        "portrait-secondary" -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT
                        "landscape" -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE //横屏且受重力控制正反
                        "landscape-primary" -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE //正向横屏
                        "landscape-secondary" -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE //反向横屏
                        "any", "unspecified" -> ActivityInfo.SCREEN_ORIENTATION_SENSOR
                        else -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                    }
                }
            }
        }

        @JavascriptInterface
        fun onCloseRequested() {
            val fra = dialogRef.get() ?: return
            if (fra.dialog?.isShowing == true) {
                fra.lifecycleScope.launch(Dispatchers.Main) {
                    fra.dismiss()
                }
            }
        }
    }

    @Keep
    data class Config(
        // 底部弹窗状态相关配置
        var state: Int? = null, // 设置弹窗的初始状态： 3 STATE_EXPANDED(展开) 、 4 STATE_COLLAPSED(折叠) 、 5 STATE_HIDDEN(隐藏) 、 6 STATE_HALF_EXPANDED(半展开)
        var peekHeight: Int? = null, // 设置折叠状态下的高度（像素）
        var isHideable: Boolean? = null, // 设置弹窗是否可以通过向下拖拽隐藏
        var skipCollapsed: Boolean? = null, // 设置是否跳过折叠状态，下滑对话框时直接关闭
        var setHalfExpandedRatio: Float? = null, // 设置半展开状态的比例（0.0-1.0），相对于父容器的高度
        var setExpandedOffset: Int? = null, // 设置完全展开状态时顶部距离父容器顶部的偏移量（像素）
        var setFitToContents: Boolean? = null, // 设置展开时的高度计算方式true（默认值）自适应内容、false 固定比例

        // 交互行为相关配置
        var isDraggable: Boolean? = null, // 设置弹窗是否可以通过拖拽交互
        var isDraggableOnNestedScroll: Boolean? = null, //是否允许webview滚动控制折叠展开  默认值为true允许
        var significantVelocityThreshold: Int? = null, // 设置判定为快速滑动的速度阈值（像素/秒）
        var hideFriction: Float? = null, // 设置隐藏时的摩擦系数，影响拖拽回弹效果（0.0-1.0）

        // 视觉和布局相关配置
        var maxWidth: Int? = null, // 设置弹窗的最大宽度（像素）
        var maxHeight: Int? = null, // 设置弹窗的最大高度（像素）
        var isGestureInsetBottomIgnored: Boolean? = null, // 是否忽略系统手势区域（如下方的导航条）
        var expandedCornersRadius: Float? = null, // 展开状态的圆角半径

        // 无障碍功能相关配置
        var setUpdateImportantForAccessibilityOnSiblings: Boolean? = null, // 设置是否在弹窗展开时更新兄弟视图的无障碍重要性

        // 背景相关配置
        var backgroundDimAmount: Float? = null, // 背景遮罩透明度（0.0-1.0）
        var shouldDimBackground: Boolean? = null, // 是否显示背景遮罩

        // WebView特定配置
        var webViewInitialScale: Int? = null, // WebView初始缩放比例 默认100
        var webViewCacheMode: Int? = null, // WebView缓存模式： -1 LOAD_DEFAULT 、 1 LOAD_NO_CACHE 、 2 LOAD_CACHE_ONLY 、 3 LOAD_CACHE_ELSE_NETWORK

        // 生命周期配置
        var dismissOnTouchOutside: Boolean? = null, // 点击外部是否关闭弹窗

        // 性能优化配置
        var hardwareAccelerated: Boolean? = null, // 是否启用硬件加速
        var isNestedScrollingEnabled: Boolean? = null, // 是否启用嵌套滚动

        // 响应式设计相关配置
        var widthPercentage: Float? = null, // 弹窗宽度占屏幕宽度的百分比（0.0-1.0）
        var heightPercentage: Float? = null, // 弹窗高度占屏幕高度的百分比（0.0-1.0）
        var responsiveBreakpoint: Int? = null, // 响应式断点（像素），小于此宽度时使用移动端布局
        var dialogHeight: Int? = null, //弹窗高度（像素），默认为-1（父容器最大高度）、-2（最大内容高度）

        //阅读功能自定义配置
        var longClickSaveImg : Boolean? = null, //是否启用长按图片保存功能，默认启用
        var scrollNoDraggable : Boolean? = null, //网页有滚动时禁止对话框拖拽，默认启用
        var pullDownToDismiss : Boolean? = null, //WebView滚动到顶部后下拉关闭弹窗，默认启用
    )

    inner class CustomWebChromeClient : WebChromeClient() {
        override fun getDefaultVideoPoster(): Bitmap {
            return super.getDefaultVideoPoster() ?: createBitmap(100, 100)
        }

        override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
            originOrientation = activity?.requestedOrientation //先记录原始方向，避免被js控制的影响
            isFullScreen = true
            binding.webViewContainer.invisible()
            binding.customWebView.addView(view)
            customWebViewCallback = callback
            dialog?.keepScreenOn(true)
            behavior?.state = BottomSheetBehavior.STATE_EXPANDED
        }

        override fun onHideCustomView() {
            originOrientation?.let {
                activity?.requestedOrientation = it
                originOrientation = null
            }
            isFullScreen = false
            binding.webViewContainer.visible()
            binding.customWebView.removeAllViews()
            customWebViewCallback = null
            dialog?.keepScreenOn(false)
        }

        /* 覆盖window.close() */
        override fun onCloseWindow(window: WebView?) {
            dismiss()
        }

        /* 监听网页日志 */
        override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
            if (!AppConfig.debugLogEnabled) return false
            val source = source ?: return false
            val messageLevel = consoleMessage.messageLevel().name
            val message = consoleMessage.message()
            AppLog.put(
                "${source.getTag()}${messageLevel}: $message",
                NoStackTraceException("\n${message}\n- Line ${consoleMessage.lineNumber()} of ${consoleMessage.sourceId()}")
            )
            return true
        }
    }

    inner class CustomWebViewClient : WebViewClient() {
        override fun shouldOverrideUrlLoading(
            view: WebView?, request: WebResourceRequest?
        ): Boolean {
            request?.let {
                return shouldOverrideUrlLoading(it.url)
            }
            return true
        }

        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION", "KotlinRedundantDiagnosticSuppress")
        override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
            url?.let {
                return shouldOverrideUrlLoading(it.toUri())
            }
            return true
        }

        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
            if (needClearHistory) {
                needClearHistory = false
                currentWebView.clearHistory() //清除历史
            }
            super.onPageStarted(view, url, favicon)
            currentWebView.evaluateJavascript(basicJs, null)
            // 网络优先：主框架开始加载时启动真正的超时定时任务
            scheduleFallbackTimeout()
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            super.onPageFinished(view, url)
            // 页面加载成功：取消超时定时任务
            cancelFallbackTimeout()
            // 离线评论模式：页面加载完成后注入接管脚本（快照=全接管，在线=拦截发评请求），
            // 快照优先的在线覆盖页同样生效；注入幂等，脚本内部自带安装标记
            val context = outboxContext
            if (context != null && AppConfig.offlineReviewMode) {
                view?.evaluateJavascript(
                    io.legado.app.help.review.reviewoutbox.ReviewOutboxWireUp.buildJs(),
                    null
                )
                io.legado.app.constant.AppLog.putDebug(
                    "${io.legado.app.help.review.reviewoutbox.ReviewOutboxStore.LogTag} 接管脚本已注入 " +
                        "url=${url ?: ""} 书=${context.bookName} 章=${context.chapterTitle}",
                    module = io.legado.app.constant.LogModule.REVIEW_OFFLINE
                )
            }
            // 快照显示中：注入章评/书评补充 section 与离线 tab/楼中楼交互
            if (displayingSnapshotHtml && view != null) {
                injectReviewSupplements(view)
            }
        }

        /**
         * 读取本章章评、本书书评补充快照并注入当前快照页：
         * 章评/书评 tab 从死链变成离线可切换的 section，楼中楼可离线收起/展开。
         * 异步读取数据库与磁盘，evaluateJavascript 回到主线程执行。
         */
        private fun injectReviewSupplements(view: WebView) {
            val context = outboxContext ?: return
            val book = reviewResourceBook ?: return
            viewLifecycleOwner.lifecycleScope.launch(IO) {
                val js = runCatching {
                    val chapter = context.chapterUrl.takeIf { it.isNotBlank() }?.let { chapterUrl ->
                        appDb.bookChapterDao.getChapterByUrl(book.bookUrl, chapterUrl)
                    }
                    val chapterTab = chapter?.let {
                        io.legado.app.help.review.ReviewSnapshotStore.getChapterTab(book, it)
                    }
                    val bookTab = io.legado.app.help.review.ReviewSnapshotStore.getBookTab(book)
                    io.legado.app.help.review.ReviewSupplementInjector.buildInjectionJs(
                        chapterTab,
                        bookTab,
                    )
                }.getOrNull() ?: return@launch
                withContext(Dispatchers.Main) {
                    if (!isAdded || isHidden) return@withContext
                    view.evaluateJavascript(js) { result ->
                        AppLog.putDebug(
                            "[评论快照] 章评/书评补充注入：${result ?: "null"} " +
                                "书=${context.bookName} 章=${context.chapterTitle}",
                            module = io.legado.app.constant.LogModule.DOWNLOAD_CACHE
                        )
                    }
                }
            }
        }

        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION", "KotlinRedundantDiagnosticSuppress")
        override fun onReceivedError(
            view: WebView?,
            errorCode: Int,
            description: String?,
            failingUrl: String?
        ) {
            super.onReceivedError(view, errorCode, description, failingUrl)
            if (fallbackHtml != null && !fallbackApplied) {
                applyFallbackSnapshot()
            }
        }

        override fun onReceivedError(
            view: WebView?,
            request: WebResourceRequest?,
            error: android.webkit.WebResourceError?
        ) {
            super.onReceivedError(view, request, error)
            if (fallbackHtml != null && !fallbackApplied && request?.isForMainFrame == true) {
                applyFallbackSnapshot()
            }
        }

        private fun shouldOverrideUrlLoading(url: Uri): Boolean {
            return when (url.scheme) {
                "http", "https" -> false
                "legado", "yuedu" -> {
                    startActivity<OnLineImportActivity> {
                        data = url
                    }
                    true
                }

                else -> {
                    binding.root.longSnackbar(R.string.jump_to_another_app, R.string.confirm) {
                        activity?.openUrl(url)
                    }
                    true
                }
            }
        }

        @SuppressLint("WebViewClientOnReceivedSslError")
        override fun onReceivedSslError(
            view: WebView?, handler: SslErrorHandler?, error: SslError?
        ) {
            handler?.proceed()
        }

        private var jsInjected = false
        override fun shouldInterceptRequest(
            view: WebView, request: WebResourceRequest
        ): WebResourceResponse? {
            val url = request.url.toString()
            ReviewSnapshotResourceStore.keyFromReference(url)?.let { key ->
                val book = checkNotNull(reviewResourceBook) {
                    "评论快照资源引用缺少书籍上下文: $url"
                }
                val resource = checkNotNull(ReviewSnapshotResourceStore.open(book, key)) {
                    "评论快照资源不存在: $url"
                }
                return WebResourceResponse(resource.mimeType, null, resource.inputStream)
            }
            // 仅使用快照/快照兜底（离线模式）：http/https 请求一律拦掉，
            // 快照只允许 data:// 或 review-resource:// 本地资源离线渲染，残余外部资源不联网
            if (offlineMode &&
                (request.url.scheme == "http" || request.url.scheme == "https")
            ) {
                return WebResourceResponse(
                    "text/plain",
                    "utf-8",
                    ByteArrayInputStream(ByteArray(0))
                )
            }
            if (request.isForMainFrame) {
                if (!preloadJs.isNullOrEmpty()) {
                    jsInjected = false
                    if (url.startsWith("data:text/html;") || request.method == "POST") {
                        return super.shouldInterceptRequest(view, request)
                    }
                    return runBlocking(IO) {
                        getModifiedContentWithJs(url, request) ?: super.shouldInterceptRequest(view, request)
                    }
                }
            } else if (!jsInjected && url == nameUrl) {
                jsInjected = true
                val preloadJs = preloadJs ?: ""
                return WebResourceResponse(
                    "text/javascript",
                    "utf-8",
                    ByteArrayInputStream("(() => {$JS_INJECTION\n$preloadJs\n})();".toByteArray())
                )
            }
            return super.shouldInterceptRequest(view, request)
        }
        private val webCookieManager by lazy { android.webkit.CookieManager.getInstance() }
        private suspend fun getModifiedContentWithJs(url: String, request: WebResourceRequest): WebResourceResponse? {
            try {
                val cookie = webCookieManager.getCookie(url)
                val res = okHttpClient.newCallResponse {
                    url(url)
                    method(request.method, null)
                    if (!cookie.isNullOrEmpty()) {
                        addHeader("Cookie", cookie)
                    }
                    request.requestHeaders?.forEach { (key, value) ->
                        addHeader(key, value)
                    }
                }
                res.headers("Set-Cookie").forEach { setCookie ->
                    webCookieManager.setCookie(url, setCookie)
                }
                val body = res.body
                val contentType = body.contentType()
                val mimeType = contentType?.toString()?.substringBefore(";") ?: "text/html"
                val charset = contentType?.charset() ?: Charsets.UTF_8
                val charsetSre = charset.name()
                val bodyText = body.text().let { originalText ->
                    val headIndex = originalText.indexOf("<head", ignoreCase = true)
                    if (headIndex >= 0) {
                        val closingHeadIndex = originalText.indexOf('>', startIndex = headIndex)
                        if (closingHeadIndex >= 0) {
                            val insertPos = closingHeadIndex + 1
                            StringBuilder(originalText).insert(insertPos, JS_URL).toString()
                        } else {
                            originalText
                        }
                    } else {
                        originalText
                    }
                }
                return WebResourceResponse(
                    mimeType,
                    charsetSre,
                    ByteArrayInputStream(bodyText.toByteArray(charset))
                )
            } catch (_: Exception) {
                return null
            }
        }
    }

}
