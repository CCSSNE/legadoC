package io.legado.app.help.review

import android.net.Uri
import org.jsoup.Jsoup

/** 无气泡段落共用的本地评论页；只借用缓存入口地址，绝不借用其他段的评论。 */
object ReviewParagraphTemplate {
    fun create(anchor: ReviewSnapshot, entry: SyntheticParaContent): ReviewSnapshot {
        val uri = Uri.parse(anchor.url)
        require(uri.getQueryParameter("book_id") != null &&
            uri.getQueryParameter("item_id") != null && uri.getQueryParameter("para") != null) {
            "缓存评论地址缺少段落身份，无法生成离线段评入口"
        }
        val builder = uri.buildUpon().clearQuery()
        uri.queryParameterNames.filterNot { it == "para" }.forEach { name ->
            uri.getQueryParameters(name).forEach { value -> builder.appendQueryParameter(name, value) }
        }
        val url = builder.appendQueryParameter("para", entry.para.toString()).build().toString()
        val doc = Jsoup.parse("""
            <!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
            <style>
            :root{color-scheme:light dark}body{font:16px sans-serif;margin:16px}
            blockquote{margin:16px 0;white-space:pre-wrap}textarea{box-sizing:border-box;width:100%;min-height:10em;font:inherit}
            button{font:inherit;padding:10px;margin:8px 8px 8px 0}
            #commentModalOverlay{display:none;position:fixed;inset:0;background:Canvas;color:CanvasText;padding:16px;flex-direction:column}
            </style></head><body>
            <blockquote id="quoteCard"></blockquote>
            <div id="contentArea">此段尚未缓存在线评论，可离线撰写并稍后发送。</div>
            <button id="commentTrigger">写段评</button>
            <div id="commentModalOverlay"><button class="modal-close">关闭</button>
            <textarea id="commentTextarea" placeholder="评论内容"></textarea>
            <button id="modalSubmitBtn">保存离线评论</button></div>
            </body></html>
        """.trimIndent())
        doc.getElementById("quoteCard")!!.text(entry.text)
        return anchor.copy(url = url, html = doc.outerHtml(), buttonSrc = "", resourceKeys = emptyList())
    }
}
