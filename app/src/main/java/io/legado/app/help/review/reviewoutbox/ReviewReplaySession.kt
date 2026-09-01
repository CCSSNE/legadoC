package io.legado.app.help.review.reviewoutbox

import android.annotation.SuppressLint
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import io.legado.app.constant.AppConst
import io.legado.app.constant.AppLog
import io.legado.app.constant.LogModule
import io.legado.app.data.entities.PendingReviewComment
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import splitties.init.appCtx

/**
 * 离线评论回放会话：无头 WebView 打开真实评论页（带书源 cookie/UA），
 * 注入脚本填入评论内容并点击页面自身的发表按钮，由书源原有 JS 完成发送
 * （relative_id / para_content / 会话全部由页面自理），监听结果响应。
 *
 * 结果约定：包装 window.fetch 后，发评 POST 的响应 JSON code==200 视为成功，
 * 写入 window.__legadoOutboxResult 由轮询读取。
 */
class ReviewReplaySession(
    private val url: String,
    private val html: String?,
    private val headerMap: Map<String, String>,
    private val kind: Int,
    private val content: String,
) {

    data class ReplayResult(
        val ok: Boolean,
        val message: String,
        val responseSnippet: String? = null,
    )

    private val pageFinished = CompletableDeferred<Boolean>()
    private val pageFailed = CompletableDeferred<String>()

    @SuppressLint("SetJavaScriptEnabled")
    suspend fun run(): ReplayResult = withContext(Dispatchers.Main) {
        val webView = WebView(appContext())
        try {
            webView.settings.javaScriptEnabled = true
            webView.settings.domStorageEnabled = true
            headerMap[AppConst.UA_NAME]?.let { webView.settings.userAgentString = it }
            webView.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, finishedUrl: String?) {
                    AppLog.putDebug(
                        "${ReviewOutboxStore.LogTag} 回放页面加载完成 url=$finishedUrl",
                        module = LogModule.REVIEW_OFFLINE
                    )
                    pageFinished.complete(true)
                }

                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    error: WebResourceError?
                ) {
                    super.onReceivedError(view, request, error)
                    if (request?.isForMainFrame == true && !pageFailed.isCompleted) {
                        pageFailed.complete(
                            "页面加载失败 code=${error?.errorCode} ${error?.description}"
                        )
                    }
                }
            }
            if (html.isNullOrBlank()) {
                webView.loadUrl(url)
            } else {
                webView.loadDataWithBaseURL(url, html, "text/html", "utf-8", url)
            }

            val failure = withTimeoutOrNull(PAGE_LOAD_TIMEOUT_MS) {
                coroutineScope {
                    val okJob = launch { pageFinished.await() }
                    val failJob = launch { pageFailed.await() }
                    select<String?> {
                        okJob.onJoin { null }
                        failJob.onJoin { pageFailed.getCompleted() }
                    }
                }
            }
            if (failure != null) return@withContext ReplayResult(false, failure)

            if (kind == PendingReviewComment.KIND_CHAPTER) {
                val tabOpened = evaluate(webView, chapterTabJs())
                if (tabOpened != "true") {
                    return@withContext ReplayResult(false, "无法定位章评入口，无法回放章评")
                }
                delay(CHAPTER_TAB_WAIT_MS)
            }

            val submitted = evaluate(webView, fillAndSubmitJs())
            if (submitted != "true") {
                return@withContext ReplayResult(false, "无法定位评论输入框或发表按钮，页面结构可能已变化")
            }

            val deadline = System.currentTimeMillis() + RESULT_TIMEOUT_MS
            while (System.currentTimeMillis() < deadline) {
                val raw = evaluate(webView, RESULT_JS)
                if (!raw.isNullOrBlank() && raw != "null" && raw != "\"null\"") {
                    val result = parseResult(raw)
                    AppLog.putDebug(
                        "${ReviewOutboxStore.LogTag} 回放结果 ok=${result.ok} msg=${result.message}",
                        module = LogModule.REVIEW_OFFLINE
                    )
                    return@withContext result
                }
                if (pageFailed.isCompleted) {
                    return@withContext ReplayResult(false, pageFailed.getCompleted())
                }
                delay(RESULT_POLL_INTERVAL_MS)
            }
            ReplayResult(false, "发送结果未返回（超时）")
        } finally {
            webView.destroy()
        }
    }

    /**
     * 解析轮询结果。evaluateJavascript 回传的是 JSON 文本：
     * RESULT_JS 返回字符串（JSON.stringify），外层还会再包一层引号，须先解开。
     */
    private fun parseResult(raw: String): ReplayResult {
        return try {
            val body = if (raw.startsWith("\"")) {
                org.json.JSONTokener(raw).nextValue() as String
            } else {
                raw
            }
            val json = org.json.JSONObject(body)
            val ok = json.optBoolean("ok", false)
            val message = json.optString("message", "").ifBlank {
                if (ok) "发表成功" else "发送失败（响应无说明）"
            }
            ReplayResult(ok, message, json.optString("response", "").take(200))
        } catch (e: Exception) {
            ReplayResult(false, "回放结果解析失败：${e.localizedMessage}")
        }
    }

    private suspend fun evaluate(webView: WebView, script: String): String? {
        val deferred = CompletableDeferred<String?>()
        webView.evaluateJavascript(script) { deferred.complete(it) }
        return withTimeoutOrNull(EVAL_TIMEOUT_MS) { deferred.await() }
    }

    /**
     * 章评切换：点击含"章评"文案的 tab；缺失时回退调用页面 switchTab('chapter')。
     * 返回 JS 布尔值（evaluateJavascript 对布尔不再包引号）。
     */
    private fun chapterTabJs(): String = """
        (function(){
        var tabs = document.querySelectorAll('.tab');
        for (var i = 0; i < tabs.length; i++) {
            if ((tabs[i].textContent || '').indexOf('章评') >= 0) { tabs[i].click(); return true; }
        }
        if (typeof switchTab === 'function') { switchTab('chapter'); return true; }
        return false;
        })();
    """.trimIndent()

    /**
     * 包装 fetch 记录结果 → 填入内容 → 点击页面自身发表按钮。
     * 输入框/按钮先取实测精确 id，再回退通用结构匹配。
     */
    private fun fillAndSubmitJs(): String {
        val contentJson = org.json.JSONObject.quote(content)
        return """
        (function(){
        if (!window.__legadoOutboxResult) {
            var originalFetch = window.fetch;
            window.fetch = function (input, init) {
                return originalFetch.apply(window, arguments).then(function (res) {
                    try {
                        var cloned = res.clone();
                        cloned.text().then(function (t) {
                            var code = null, message = null;
                            try { var j = JSON.parse(t); code = j.code; message = j.message; } catch (e) {}
                            window.__legadoOutboxResult = {
                                ok: code === 200,
                                message: message,
                                response: t.slice(0, 200)
                            };
                        }).catch(function () {});
                    } catch (e) {}
                    return res;
                });
            };
        }
        var ta = document.getElementById('commentTextarea') || document.querySelector('textarea');
        if (!ta) return false;
        ta.value = $contentJson;
        try { ta.dispatchEvent(new Event('input', { bubbles: true })); } catch (e) {}
        var sb = document.getElementById('modalSubmitBtn')
            || document.querySelector('button[onclick*="submitComment"]');
        if (!sb) {
            var buttons = document.querySelectorAll('button');
            for (var i = 0; i < buttons.length; i++) {
                if (/(发表|发送|提交)/.test((buttons[i].textContent || '').trim())) { sb = buttons[i]; break; }
            }
        }
        if (!sb) return false;
        sb.click();
        return true;
        })();
        """.trimIndent()
    }

    private companion object {
        /** 读取回放结果：未产生结果时返回 null */
        const val RESULT_JS =
            "window.__legadoOutboxResult ? JSON.stringify(window.__legadoOutboxResult) : null"

        const val PAGE_LOAD_TIMEOUT_MS = 25_000L
        const val RESULT_TIMEOUT_MS = 30_000L
        const val RESULT_POLL_INTERVAL_MS = 700L
        const val CHAPTER_TAB_WAIT_MS = 1_500L
        const val EVAL_TIMEOUT_MS = 10_000L
    }
}
