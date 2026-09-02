package io.legado.app.help.review

import android.os.Bundle

/**
 * 合成段评入口（无泡段落）携带的目标段落信息。
 *
 * 评论页发评弹窗经 `?api=1` 拉取段落原文时，零评论段服务端返回空，
 * 页面随后回退用评论内容充当 para_content 发出，导致该段评论的引用卡
 * 永久错文。此上下文随 [io.legado.app.ui.widget.dialog.BottomWebViewDialog]
 * 传入，由 [ReviewParaContentInjector] 的 fetch 包装把空原文回填为真实段落原文。
 *
 * [snapshotFallbackAllowed] 标记评论页快照兜底是否安全：零评论收纳泡自带
 * 原始 src（快照即本段评论页）时为 true；合成入口借用同章锚点泡的 src，
 * 其快照属于其他段落，必须禁止兜底展示时为 false。
 */
data class SyntheticParaContent(
    val para: Int,
    val text: String,
    val snapshotFallbackAllowed: Boolean = true,
) {

    fun putTo(args: Bundle) {
        args.putInt(ARG_PARA, para)
        args.putString(ARG_TEXT, text)
        args.putBoolean(ARG_SNAPSHOT_ALLOWED, snapshotFallbackAllowed)
    }

    companion object {
        private const val ARG_PARA = "syntheticReviewPara"
        private const val ARG_TEXT = "syntheticReviewParaContent"
        private const val ARG_SNAPSHOT_ALLOWED = "syntheticReviewSnapshotAllowed"

        fun fromBundle(args: Bundle?): SyntheticParaContent? {
            args ?: return null
            val para = args.getInt(ARG_PARA, -1)
            val text = args.getString(ARG_TEXT) ?: return null
            if (para < 0 || text.isBlank()) return null
            return SyntheticParaContent(
                para,
                text,
                args.getBoolean(ARG_SNAPSHOT_ALLOWED, true),
            )
        }
    }
}

/**
 * 段落原文回填脚本：包一层 window.fetch，仅拦截当前段落的 `?api=1` 响应，
 * para_src_content 为空时回填目标段落原文；其余请求原样透传。
 * 幂等（自带安装标记）；有评论的段服务端原文非空，不会被覆盖。
 */
object ReviewParaContentInjector {

    fun buildJs(entry: SyntheticParaContent): String {
        val text = entry.text
            .replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace("\r", "")
            .replace("\n", "\\n")
            .replace("<", "\\u003c")
        return """
        (function(){
        if (window.__legadoParaFillInstalled) return;
        window.__legadoParaFillInstalled = true;
        var TARGET_PARA = '${entry.para}';
        var PARA_TEXT = '$text';
        var originalFetch = window.fetch;
        if (!originalFetch) return;
        window.fetch = function (input, init) {
            var url;
            try {
                url = (typeof input === 'string') ? input : (input && input.url) || '';
            } catch (e) { return originalFetch.apply(window, arguments); }
            var m = url.match(/[?&]para=(\d+)/);
            if (!m || m[1] !== TARGET_PARA || url.indexOf('api=1') < 0) {
                return originalFetch.apply(window, arguments);
            }
            return originalFetch.apply(window, arguments).then(function (res) {
                if (!res || !res.clone) return res;
                try {
                    return res.clone().json().then(function (data) {
                        try {
                            if (data && data.data && !data.data.para_src_content) {
                                data.data.para_src_content = PARA_TEXT;
                            }
                            return new Response(JSON.stringify(data), {
                                status: res.status,
                                headers: { 'Content-Type': 'application/json' }
                            });
                        } catch (e2) { return res; }
                    }).catch(function () { return res; });
                } catch (e3) { return res; }
            });
        };
        })();
        """.trimIndent()
    }

}
