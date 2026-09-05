package io.legado.app.ui.widget

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.net.Uri
import android.util.AttributeSet
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebView.HitTestResult
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader
import io.legado.app.utils.toastOnUi

/**
 * Markdown 只读浏览器：WebView 内嵌 Vditor 静态预览，与卡片编辑器同一套渲染，
 * 表格宫格等排版两边一致。调用方只调 [setMarkdown]，高度按内容自适应；
 * 图片长按经 [onImageLongPress] 回调（原生命中判断，不走 JS），链接走外部浏览器。
 * 用完调 [destroyPreview]（先离树再销毁）。
 */
class MarkdownPreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : WebView(context, attrs, defStyle) {

    companion object {
        private const val PAGE_URL =
            "https://appassets.androidplatform.net/assets/mdeditor/preview.html"

        /** 高度上报兜底：JS 没回话就给个固定高度，保证内容可见 */
        private const val FALLBACK_HEIGHT_DP = 320
        private const val FALLBACK_DELAY_MS = 2500L
    }

    private var markdown = ""
    private var measured = false

    /** 内容高度变化（CSS 像素转好的物理像素）：调用方可用于折叠等二次布局 */
    var onContentHeightChanged: ((Int) -> Unit)? = null

    /** 图片长按回调，参数为图片地址 */
    var onImageLongPress: ((String) -> Unit)? = null

    private val assetLoader = WebViewAssetLoader.Builder()
        .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
        .build()

    private val fallbackRunnable = Runnable {
        if (!measured) applyHeightPx((FALLBACK_HEIGHT_DP * resources.displayMetrics.density).toInt())
    }

    init {
        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
        }
        setBackgroundColor(Color.TRANSPARENT)
        addJavascriptInterface(Bridge(), "AndroidBridge")
        webViewClient = PreviewClient()
        setOnLongClickListener {
            val result = hitTestResult
            if (result?.type === HitTestResult.IMAGE_TYPE) {
                val src = result.extra?.trim().orEmpty()
                if (src.isNotBlank()) {
                    onImageLongPress?.invoke(src)
                    return@setOnLongClickListener true
                }
            }
            false
        }
    }

    /** 显示一段 Markdown：每次都重新载入预览页，高度重算 */
    fun setMarkdown(md: String) {
        markdown = md
        measured = false
        removeCallbacks(fallbackRunnable)
        postDelayed(fallbackRunnable, FALLBACK_DELAY_MS)
        loadUrl(PAGE_URL)
    }

    fun destroyPreview() {
        removeCallbacks(fallbackRunnable)
        (parent as? ViewGroup)?.removeView(this)
        destroy()
    }

    private fun applyHeightPx(px: Int) {
        if (px <= 0) return
        measured = true
        removeCallbacks(fallbackRunnable)
        val params = layoutParams
        if (params != null) {
            params.height = px
            layoutParams = params
        }
        onContentHeightChanged?.invoke(px)
    }

    private inner class Bridge {

        @JavascriptInterface
        fun loadContent(): String = markdown

        @JavascriptInterface
        fun isDark(): Boolean =
            (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES

        @JavascriptInterface
        fun onReady(cssHeight: Int) {
            post {
                if (cssHeight > 0) {
                    applyHeightPx((cssHeight * resources.displayMetrics.density).toInt())
                }
            }
        }
    }

    private inner class PreviewClient : WebViewClient() {

        override fun shouldInterceptRequest(
            view: WebView,
            request: WebResourceRequest
        ) = assetLoader.shouldInterceptRequest(request.url)

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val url = request.url?.toString().orEmpty()
            if (url.isBlank()) return true
            runCatching {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                )
            }.onFailure {
                context.toastOnUi(it.message ?: it.javaClass.simpleName)
            }
            return true
        }
    }
}
