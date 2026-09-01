package io.legado.app.utils

import android.annotation.SuppressLint
import android.os.Build
import android.webkit.WebSettings
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import io.legado.app.help.config.AppConfig

/**
 * 配置 WebView 是否只允许离线资源。
 *
 * [WebSettings.blockNetworkImage] 不能用于离线隔离：它会在
 * WebViewClient.shouldInterceptRequest 之前拦掉 review-resource:// 图片。
 * http/https 的隔离统一交给 [WebSettings.blockNetworkLoads]，自定义 scheme
 * 仍可进入应用自己的资源解析器。
 */
fun WebSettings.configureOfflineResourceLoading(offline: Boolean) {
    blockNetworkLoads = offline
    blockNetworkImage = false
}

/**
 * 设置是否夜间模式
 */
@SuppressLint("RequiresFeature")
fun WebSettings.setDarkeningAllowed(allow: Boolean) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        kotlin.runCatching {
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(this, allow)
            return
        }
    }
    if (allow) {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK_STRATEGY)) {
            @Suppress("DEPRECATION")
            WebSettingsCompat.setForceDarkStrategy(
                this,
                WebSettingsCompat.DARK_STRATEGY_PREFER_WEB_THEME_OVER_USER_AGENT_DARKENING
            )
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK)) {
            @Suppress("DEPRECATION")
            WebSettingsCompat.setForceDark(
                this,
                WebSettingsCompat.FORCE_DARK_ON
            )
        }
    } else {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK)) {
            @Suppress("DEPRECATION")
            WebSettingsCompat.setForceDark(
                this,
                WebSettingsCompat.FORCE_DARK_OFF
            )
        }
    }
}
