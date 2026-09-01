package io.legado.app.ui.book.read

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.inputmethod.EditorInfo
import android.webkit.WebChromeClient
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.PopupMenu
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.databinding.DialogSelectionWebSearchBinding
import io.legado.app.help.http.okHttpClient
import io.legado.app.help.http.text
import io.legado.app.help.webView.PooledWebView
import io.legado.app.help.webView.WebViewPool
import io.legado.app.lib.theme.UiCorner
import io.legado.app.lib.theme.applyUiBodyTypefaceDeep
import io.legado.app.lib.theme.uiTypeface
import io.legado.app.ui.book.read.config.ReaderSheetStyle
import io.legado.app.utils.ColorUtils
import io.legado.app.utils.dpToPx
import io.legado.app.utils.gone
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import io.legado.app.utils.visible
import okhttp3.Request
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.net.URL

class SelectionWebSearchDialog() : BottomSheetDialogFragment(R.layout.dialog_selection_web_search) {

    constructor(query: String) : this() {
        arguments = Bundle().apply {
            putString("query", query)
        }
    }

    private val binding by viewBinding(DialogSelectionWebSearchBinding::bind)
    private lateinit var pooledWebView: PooledWebView
    private lateinit var webView: WebView
    private var currentEngineId: String = ""
    private var currentQuery: String = ""
    private var behavior: BottomSheetBehavior<View>? = null
    private var clearHistoryOnNextFinish = false
    private var currentSearchEngine: ContentSelectConfig.SearchEngine? = null
    private var currentSearchRootHost: String? = null
    private var webViewUserAgent: String = ""
    private var pendingShowAfterLoad = false

    override fun onAttach(context: Context) {
        super.onAttach(context)
        pooledWebView = WebViewPool.acquire(context)
        webView = pooledWebView.realWebView
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState)
        dialog.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                handleBack()
                true
            } else {
                false
            }
        }
        return dialog
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.setDimAmount(0.32f)
        val sheet = dialog?.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            ?: return
        val height = (resources.displayMetrics.heightPixels * 0.9f).toInt()
        sheet.layoutParams = sheet.layoutParams.apply {
            this.height = height
            this.width = ViewGroup.LayoutParams.MATCH_PARENT
        }
        applySheetHostStyle(sheet)
        behavior = BottomSheetBehavior.from(sheet).apply {
            state = BottomSheetBehavior.STATE_EXPANDED
            skipCollapsed = true
            isHideable = true
            peekHeight = height
        }
        updateSheetDragState()
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.root.applyUiBodyTypefaceDeep(requireContext().uiTypeface())
        applyStyle()
        binding.webViewContainer.addView(
            webView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        prepareWebView()
        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                pendingShowAfterLoad = true
                hideWebViewWhileLoading()
                binding.progressBar.visible()
                binding.progressBar.progress = 8
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                binding.progressBar.gone()
                injectHideCssRepeated()
                showWebViewAfterCss()
                updateSheetDragState()
                if (clearHistoryOnNextFinish) {
                    clearHistoryOnNextFinish = false
                    webView.post { webView.clearHistory() }
                }
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                if (request?.isForMainFrame == true) {
                    binding.progressBar.gone()
                    AppLog.putDebug(
                        "Selection web search load error " +
                            "url=${request.url} " +
                            "code=${error?.errorCode} " +
                            "desc=${error?.description}"
                    )
                }
                super.onReceivedError(view, request, error)
            }

            override fun onReceivedHttpError(
                view: WebView?,
                request: WebResourceRequest?,
                errorResponse: WebResourceResponse?
            ) {
                if (request?.isForMainFrame == true) {
                    AppLog.putDebug(
                        "Selection web search http error " +
                            "url=${request.url} " +
                            "status=${errorResponse?.statusCode}"
                    )
                }
                super.onReceivedHttpError(view, request, errorResponse)
            }

            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {
                return interceptSearchDocument(request)
                    ?: super.shouldInterceptRequest(view, request)
            }
        }
        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                binding.progressBar.progress = newProgress
                if (newProgress >= 100) {
                    binding.progressBar.gone()
                    showWebViewAfterCss()
                } else {
                    pendingShowAfterLoad = true
                    hideWebViewWhileLoading()
                    binding.progressBar.visible()
                }
            }
        }
        currentQuery = arguments?.getString("query").orEmpty().trim()
        binding.editQuery.setText(currentQuery)
        binding.editQuery.setSelection(binding.editQuery.text?.length ?: 0)
        currentEngineId = ContentSelectConfig.currentSearchEngine(requireContext()).id
        renderEngines()
        bindActions()
        loadSearch(currentQuery)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun prepareWebView() {
        webView.run {
            stopLoading()
            clearHistory()
            setBackgroundColor(Color.TRANSPARENT)
            visibility = View.VISIBLE
            alpha = 0f
            isNestedScrollingEnabled = true
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                loadsImagesAutomatically = true
                blockNetworkImage = false
                cacheMode = WebSettings.LOAD_DEFAULT
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                useWideViewPort = true
                loadWithOverviewMode = true
                textZoom = 100
                userAgentString = WebSettings.getDefaultUserAgent(requireContext())
            }
            webViewUserAgent = settings.userAgentString
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                setOnScrollChangeListener { _, _, _, _, _ ->
                    updateSheetDragState()
                }
            }
            setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN,
                    MotionEvent.ACTION_MOVE,
                    MotionEvent.ACTION_UP,
                    MotionEvent.ACTION_CANCEL -> updateSheetDragState()
                }
                false
            }
            resumeTimers()
            onResume()
        }
    }

    private fun applyStyle() = binding.run {
        val palette = ReaderSheetStyle.resolve(requireContext())
        sheetSurface.background = topSheetDrawable(
            palette.surface,
            palette.stroke,
            UiCorner.panelRadius(requireContext())
        )
        val iconTint = palette.textColor
        btnBack.setColorFilter(iconTint)
        btnMore.setColorFilter(iconTint)
        editQuery.setTextColor(palette.textColor)
        editQuery.setHintTextColor(palette.secondaryTextColor)
        progressBar.progressTintList = ColorStateList.valueOf(palette.accentColor)
        progressBar.progressBackgroundTintList = ColorStateList.valueOf(ColorUtils.adjustAlpha(palette.stroke, 0.35f))
    }

    private fun applySheetHostStyle(sheet: View) {
        val radius = UiCorner.panelRadius(requireContext())
        sheet.backgroundTintList = null
        sheet.background = topSheetDrawable(Color.TRANSPARENT, null, radius)
        sheet.clipToOutline = radius > 0f
        setTopRoundOutline(sheet, radius)
        binding.sheetSurface.clipToPadding = false
        binding.sheetSurface.clipToOutline = radius > 0f
        setTopRoundOutline(binding.sheetSurface, radius)
    }

    private fun topSheetDrawable(
        fillColor: Int,
        strokeColor: Int?,
        radius: Float
    ): GradientDrawable {
        return GradientDrawable().apply {
            cornerRadii = floatArrayOf(
                radius, radius,
                radius, radius,
                0f, 0f,
                0f, 0f
            )
            setColor(fillColor)
            strokeColor?.let { setStroke(1.dpToPx(), it) }
        }
    }

    private fun setTopRoundOutline(view: View, radius: Float) {
        if (radius <= 0f) {
            view.outlineProvider = null
            return
        }
        view.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height + radius.toInt(), radius)
            }
        }
    }

    private fun bindActions() = binding.run {
        btnBack.setOnClickListener { handleBack() }
        btnMore.setOnClickListener { showMoreMenu() }
        editQuery.setOnEditorActionListener { _, actionId, event ->
            val enterUp = event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_UP
            if (actionId == EditorInfo.IME_ACTION_SEARCH || enterUp) {
                loadSearch(editQuery.text?.toString().orEmpty())
                true
            } else {
                false
            }
        }
    }

    private fun showMoreMenu() {
        PopupMenu(requireContext(), binding.btnMore, Gravity.NO_GRAVITY).apply {
            menu.add(0, MENU_REFRESH, 0, R.string.refresh)
            menu.add(0, MENU_EDIT, 1, R.string.edit)
            setOnMenuItemClickListener {
                when (it.itemId) {
                    MENU_REFRESH -> webView.reload()
                    MENU_EDIT -> showEngineManageDialog()
                }
                true
            }
            show()
        }
    }

    private fun showEngineManageDialog() {
        SelectionSearchEngineManageDialog {
            val engines = ContentSelectConfig.searchEngines(requireContext())
            if (engines.none { it.id == currentEngineId }) {
                currentEngineId = engines.first().id
            }
            renderEngines()
            currentQuery.takeIf { it.isNotBlank() }?.let { loadSearch(it) }
        }.show(parentFragmentManager, "selectionSearchEngineManage")
    }

    private fun renderEngines() = binding.engineContainer.run {
        removeAllViews()
        val engines = ContentSelectConfig.searchEngines(requireContext())
        engines.forEach { engine ->
            addView(createEngineChip(engine))
        }
    }

    private fun createEngineChip(engine: ContentSelectConfig.SearchEngine): TextView {
        val palette = ReaderSheetStyle.resolve(requireContext())
        val selected = engine.id == currentEngineId
        return TextView(requireContext()).apply {
            text = engine.name
            textSize = 13f
            typeface = requireContext().uiTypeface()
            gravity = Gravity.CENTER
            maxLines = 1
            includeFontPadding = false
            setTextColor(if (selected) palette.accentColor else palette.secondaryTextColor)
            val fill = if (selected) palette.panelStrong else palette.panel
            background = UiCorner.actionSelector(
                fill,
                palette.panel,
                UiCorner.actionRadius(requireContext())
            )
            setPadding(14.dpToPx(), 8.dpToPx(), 14.dpToPx(), 8.dpToPx())
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                marginEnd = 8.dpToPx()
            }
            setOnClickListener {
                currentEngineId = engine.id
                ContentSelectConfig.selectSearchEngine(requireContext(), engine.id)
                renderEngines()
                loadSearch(binding.editQuery.text?.toString().orEmpty())
            }
        }
    }

    private fun loadSearch(rawQuery: String) {
        val query = rawQuery.trim()
        if (query.isBlank()) {
            requireContext().toastOnUi(R.string.cannot_empty)
            return
        }
        currentQuery = query
        if (binding.editQuery.text?.toString() != query) {
            binding.editQuery.setText(query)
            binding.editQuery.setSelection(query.length)
        }
        val engine = ContentSelectConfig.searchEngines(requireContext())
            .firstOrNull { it.id == currentEngineId }
            ?: ContentSelectConfig.currentSearchEngine(requireContext()).also {
                currentEngineId = it.id
            }
        currentSearchEngine = engine
        currentSearchRootHost = rootHostOf(engine.url)
        clearHistoryOnNextFinish = true
        pendingShowAfterLoad = true
        hideWebViewWhileLoading()
        webView.loadUrl(ContentSelectConfig.buildSearchUrl(engine, query))
    }

    private fun hideWebViewWhileLoading() {
        if (!::webView.isInitialized) return
        webView.alpha = 0f
    }

    private fun showWebViewAfterCss() {
        if (!pendingShowAfterLoad || !::webView.isInitialized) return
        pendingShowAfterLoad = false
        injectHideCss()
        webView.postDelayed({
            if (isAdded && ::webView.isInitialized) {
                webView.alpha = 1f
            }
        }, 80L)
    }

    private fun updateSheetDragState() {
        behavior?.isDraggable = !::webView.isInitialized || !webView.canScrollVertically(-1)
    }

    private fun injectHideCssRepeated() {
        injectHideCss()
        listOf(150L, 500L, 1200L).forEach { delay ->
            webView.postDelayed({
                if (isAdded && ::webView.isInitialized) {
                    injectHideCss()
                }
            }, delay)
        }
    }

    private fun injectHideCss() {
        val css = currentSearchEngine
            ?.takeIf { it.id == currentEngineId }
            ?.hideCss
            ?: ContentSelectConfig.searchEngines(requireContext())
                .firstOrNull { it.id == currentEngineId }
                ?.hideCss
            ?.takeIf { it.isNotBlank() }
            ?: return
        val js = """
            (function() {
                try {
                    var css = ${JSONObject.quote(css)};
                    var id = 'legado-selection-search-hide-css';
                    var style = document.getElementById(id);
                    if (!style) {
                        style = document.createElement('style');
                        style.id = id;
                        (document.head || document.documentElement).appendChild(style);
                    }
                    style.textContent = css;
                } catch (e) {}
            })();
        """.trimIndent()
        webView.evaluateJavascript(js) { result ->
            if (result == null) {
                AppLog.putDebug("Selection web search hideCss evaluateJavascript returned null")
            }
        }
    }

    private fun interceptSearchDocument(request: WebResourceRequest?): WebResourceResponse? {
        request ?: return null
        if (!request.isForMainFrame) return null
        if (!request.method.equals("GET", ignoreCase = true)) return null
        val engine = currentSearchEngine ?: return null
        val css = engine.hideCss?.takeIf { it.isNotBlank() } ?: return null
        val url = request.url?.toString().orEmpty()
        if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) {
            return null
        }
        if (!matchesCurrentSearchHost(url)) {
            return null
        }
        return runCatching {
            val response = okHttpClient.newBuilder()
                .followRedirects(false)
                .followSslRedirects(false)
                .build()
                .newCall(buildInterceptRequest(url, request))
                .execute()
            response.use { res ->
                if (!res.isSuccessful || res.code == 204 || res.code == 205 || res.code == 304) {
                    return null
                }
                val body = res.body
                val contentType = body.contentType()
                val mimeType = contentType?.toString()?.substringBefore(";") ?: "text/html"
                if (!mimeType.contains("html", ignoreCase = true)) {
                    return null
                }
                val html = body.text()
                val injectedHtml = injectCssIntoHtml(html, css)
                val bytes = injectedHtml.toByteArray(contentType?.charset() ?: Charsets.UTF_8)
                val headers = res.headers.toMultimap()
                    .mapValues { it.value.joinToString(",") }
                    .toMutableMap()
                    .apply {
                        remove("content-length")
                        remove("Content-Length")
                        remove("content-encoding")
                        remove("Content-Encoding")
                    }
                WebResourceResponse(
                    mimeType,
                    contentType?.charset()?.name() ?: "UTF-8",
                    res.code,
                    res.message.ifBlank { "OK" },
                    headers,
                    ByteArrayInputStream(bytes)
                )
            }
        }.onFailure {
            AppLog.putDebug("Selection web search intercept failed url=$url error=${it.localizedMessage}")
        }.getOrNull()
    }

    private fun buildInterceptRequest(url: String, request: WebResourceRequest): Request {
        return Request.Builder()
            .url(url)
            .apply {
                request.requestHeaders.forEach { (key, value) ->
                    if (!key.equals("accept-encoding", true) &&
                        !key.equals("content-length", true) &&
                        value.isNotBlank()
                    ) {
                        header(key, value)
                    }
                }
                CookieManager.getInstance().getCookie(url)
                    ?.takeIf { it.isNotBlank() }
                    ?.let { header("Cookie", it) }
                header("Accept-Encoding", "identity")
                webViewUserAgent.takeIf { it.isNotBlank() }
                    ?.let { header("User-Agent", it) }
            }
            .get()
            .build()
    }

    private fun injectCssIntoHtml(html: String, css: String): String {
        val style = """<style id="legado-selection-search-hide-css">${css.forHtmlStyle()}</style>"""
        val headOpen = Regex("<head(\\s[^>]*)?>", RegexOption.IGNORE_CASE)
        headOpen.find(html)?.let { match ->
            return html.replaceRange(match.range, "${match.value}$style")
        }
        val htmlOpen = Regex("<html(\\s[^>]*)?>", RegexOption.IGNORE_CASE)
        htmlOpen.find(html)?.let { match ->
            return html.replaceRange(match.range, "${match.value}<head>$style</head>")
        }
        return "$style$html"
    }

    private fun String.forHtmlStyle(): String {
        return replace(Regex("</style", RegexOption.IGNORE_CASE), "<\\/style")
    }

    private fun matchesCurrentSearchHost(url: String): Boolean {
        val expectedRoot = currentSearchRootHost ?: return false
        val actualRoot = rootHostOf(url) ?: return false
        return actualRoot == expectedRoot
    }

    private fun rootHostOf(url: String): String? {
        return runCatching {
            val host = URL(url).host.lowercase().trim('.')
            val parts = host.split('.').filter { it.isNotBlank() }
            if (parts.size >= 2) {
                "${parts[parts.size - 2]}.${parts.last()}"
            } else {
                host
            }
        }.getOrNull()
    }

    private fun handleBack() {
        if (!::webView.isInitialized || !webView.canGoBack()) {
            dismissAllowingStateLoss()
            return
        }
        val history = webView.copyBackForwardList()
        val previousIndex = history.currentIndex - 1
        val previousUrl = if (previousIndex >= 0) {
            history.getItemAtIndex(previousIndex)?.url.orEmpty()
        } else {
            ""
        }
        if (previousUrl.isBlank() || previousUrl == WebViewPool.BLANK_HTML) {
            dismissAllowingStateLoss()
        } else {
            webView.goBack()
        }
    }

    override fun onDestroyView() {
        behavior = null
        clearHistoryOnNextFinish = false
        pendingShowAfterLoad = false
        currentSearchEngine = null
        currentSearchRootHost = null
        webView.setOnTouchListener(null)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            webView.setOnScrollChangeListener(null)
        }
        webView.stopLoading()
        binding.webViewContainer.removeView(webView)
        WebViewPool.release(pooledWebView)
        super.onDestroyView()
    }

    private companion object {
        const val MENU_REFRESH = 1
        const val MENU_EDIT = 2
    }
}
