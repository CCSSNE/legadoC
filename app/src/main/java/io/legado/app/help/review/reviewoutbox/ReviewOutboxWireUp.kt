package io.legado.app.help.review.reviewoutbox

import android.os.Bundle
import android.webkit.JavascriptInterface
import io.legado.app.R
import io.legado.app.data.entities.PendingReviewComment
import io.legado.app.utils.toastOnUi
import splitties.init.appCtx

/**
 * 离线评论入队上下文：随评论弹窗传入，记录评论所属书籍/章节/按钮信息。
 * 快照路径携带完整上下文（含 buttonSrc）；在线弹窗路径 buttonSrc 为空，
 * 回放时直接使用记录的评论页地址。
 */
data class ReviewOutboxContext(
    val bookUrl: String,
    val bookName: String,
    val chapterUrl: String,
    val chapterIndex: Int,
    val chapterTitle: String,
    val origin: String?,
    val buttonSrc: String?,
    val pageUrl: String,
) {

    fun putTo(args: Bundle) {
        args.putString(ARG_BOOK_URL, bookUrl)
        args.putString(ARG_BOOK_NAME, bookName)
        args.putString(ARG_CHAPTER_URL, chapterUrl)
        args.putInt(ARG_CHAPTER_INDEX, chapterIndex)
        args.putString(ARG_CHAPTER_TITLE, chapterTitle)
        args.putString(ARG_ORIGIN, origin)
        args.putString(ARG_BUTTON_SRC, buttonSrc)
        args.putString(ARG_PAGE_URL, pageUrl)
    }

    companion object {
        private const val ARG_BOOK_URL = "outboxBookUrl"
        private const val ARG_BOOK_NAME = "outboxBookName"
        private const val ARG_CHAPTER_URL = "outboxChapterUrl"
        private const val ARG_CHAPTER_INDEX = "outboxChapterIndex"
        private const val ARG_CHAPTER_TITLE = "outboxChapterTitle"
        private const val ARG_ORIGIN = "outboxOrigin"
        private const val ARG_BUTTON_SRC = "outboxButtonSrc"
        private const val ARG_PAGE_URL = "outboxPageUrl"

        fun fromBundle(args: Bundle?): ReviewOutboxContext? {
            args ?: return null
            val bookUrl = args.getString(ARG_BOOK_URL) ?: return null
            return ReviewOutboxContext(
                bookUrl = bookUrl,
                bookName = args.getString(ARG_BOOK_NAME).orEmpty(),
                chapterUrl = args.getString(ARG_CHAPTER_URL).orEmpty(),
                chapterIndex = args.getInt(ARG_CHAPTER_INDEX, 0),
                chapterTitle = args.getString(ARG_CHAPTER_TITLE).orEmpty(),
                origin = args.getString(ARG_ORIGIN),
                buttonSrc = args.getString(ARG_BUTTON_SRC),
                pageUrl = args.getString(ARG_PAGE_URL).orEmpty(),
            )
        }
    }
}

/**
 * 快照/在线评论弹窗的离线评论接管脚本。
 *
 * 两套形态（按页面自身 JS 是否存活自动选择，均已实测验证 DOM 指纹）：
 * - 快照（script 被剥离，onclick 指向缺失函数）：接管 trigger/发表按钮/modal 关闭/tab，
 *   自行管理段评/章评类型并经桥入队；
 * - 在线（submitComment 存活）：包一层 window.fetch，拦截发评 POST（段评 FormData
 *   post_comment=1 / 章评 JSON /post_comment），记录后返回合成成功响应，页面 UX 不变。
 */
object ReviewOutboxWireUp {

    /** JS 侧引用的桥名（随机化，与页面脚本隔离） */
    val bridgeName: String by lazy {
        val letters = "abcdefghijklmnopqrstuvwxyz"
        letters.random() +
            java.util.UUID.randomUUID().toString().replace("-", "").take(10) +
            java.util.UUID.randomUUID().toString().replace("-", "").take(10)
    }

    fun buildJs(): String {
        return """
        (function(){
        if (window.__legadoOutboxInstalled) return;
        window.__legadoOutboxInstalled = true;
        var bridge = window.$bridgeName;
        if (!bridge) return;
        var MAX = ${PendingReviewComment.MAX_CONTENT_LENGTH};
        var state = { kind: 0 };
        function q(name) {
            try { return new URLSearchParams(location.search).get(name) || ''; } catch (e) { return ''; }
        }
        function payload(content) {
            return JSON.stringify({
                kind: state.kind,
                content: content,
                bookId: q('book_id'),
                itemId: q('item_id'),
                para: q('para'),
                pageUrl: location.href
            });
        }
        function textarea() {
            return document.getElementById('commentTextarea') || document.querySelector('textarea');
        }
        function modal() { return document.getElementById('commentModalOverlay'); }
        function openModal() { var m = modal(); if (m) m.style.display = 'flex'; }
        function closeModal() { var m = modal(); if (m) m.style.display = 'none'; }
        function send() {
            var ta = textarea();
            var content = ta ? (ta.value || '').trim() : '';
            if (!content) { bridge.toast('empty'); return; }
            if (content.length > MAX) { bridge.toast('too_long'); return; }
            var res;
            try { res = JSON.parse(bridge.enqueue(payload(content))); } catch (e) { res = { ok: false }; }
            if (res && res.ok) {
                if (ta) ta.value = '';
                closeModal();
            }
        }
        function markTab(el) {
            var tabs = document.querySelectorAll('.tab');
            for (var i = 0; i < tabs.length; i++) tabs[i].classList.remove('active');
            if (el) el.classList.add('active');
        }
        function updateCharCount() {
            var cc = document.getElementById('charCount');
            if (cc) cc.textContent = ((textarea() || {}).value || '').length + '/' + MAX;
        }
        function takeover() {
            var trigger = document.getElementById('commentTrigger');
            if (trigger) trigger.addEventListener('click', openModal);
            var bar = document.querySelector('#bottomBar button');
            if (bar) bar.addEventListener('click', openModal);
            var close = document.querySelector('#commentModalOverlay .modal-close');
            if (close) close.addEventListener('click', closeModal);
            var submit = document.getElementById('modalSubmitBtn');
            if (submit) submit.addEventListener('click', send);
            var taLive = textarea();
            if (taLive) taLive.addEventListener('input', updateCharCount);
            var emojiItems = document.querySelectorAll('.emoji-btn-item');
            for (var i = 0; i < emojiItems.length; i++) {
                (function (item) {
                    item.addEventListener('click', function () {
                        var code = item.getAttribute('data-code')
                            || (item.querySelector('img') && item.querySelector('img').getAttribute('alt'))
                            || '';
                        if (!code) return;
                        var ta = textarea();
                        if (!ta) return;
                        ta.value = (ta.value || '') + code;
                        try { ta.dispatchEvent(new Event('input', { bubbles: true })); } catch (e) {}
                        updateCharCount();
                    });
                })(emojiItems[i]);
            }
            var tabs = document.querySelectorAll('.tab');
            for (var i = 0; i < tabs.length; i++) {
                (function (tab) {
                    tab.addEventListener('click', function () {
                        var text = (tab.textContent || '').trim();
                        if (text.indexOf('书评') >= 0) { bridge.toast('book_unsupported'); return; }
                        state.kind = text.indexOf('章评') >= 0 ? 1 : 0;
                        markTab(tab);
                    });
                })(tabs[i]);
            }
        }
        function wrapOnline() {
            var original = window.fetch;
            window.fetch = function (input, init) {
                var url, method, body;
                try {
                    url = (typeof input === 'string') ? input : (input && input.url) || '';
                    method = ((init && init.method) || (input && input.method) || 'GET').toUpperCase();
                    body = init && init.body;
                } catch (e) { return original.apply(window, arguments); }
                var isPara = false, isChapter = false, fields = null, jsonBody = null;
                try {
                    if (body instanceof FormData) {
                        fields = {};
                        body.forEach(function (v, k) { if (typeof v === 'string') fields[k] = v; });
                        isPara = method === 'POST' && fields.post_comment === '1';
                    } else if (typeof body === 'string') {
                        isChapter = method === 'POST' && url.indexOf('post_comment') >= 0;
                        if (isChapter) { try { jsonBody = JSON.parse(body); } catch (e2) {} }
                    }
                } catch (e3) { return original.apply(window, arguments); }
                if (!isPara && !isChapter) return original.apply(window, arguments);
                var content = isPara ? (fields && fields.content) : (jsonBody && jsonBody.content);
                var bookId = isPara ? q('book_id') : ((jsonBody && jsonBody.forum_book_id) || q('book_id'));
                var itemId = isPara ? q('item_id') : ((jsonBody && jsonBody.item_id) || q('item_id'));
                var record = JSON.stringify({
                    kind: isPara ? 0 : 1,
                    content: content || '',
                    bookId: bookId,
                    itemId: itemId,
                    para: isPara ? q('para') : '',
                    pageUrl: location.href
                });
                var res;
                try { res = JSON.parse(bridge.enqueue(record)); } catch (e4) { res = { ok: false, message: 'enqueue error' }; }
                var reply = res && res.ok
                    ? { code: 200, message: '已记录为离线评论，稍后通过发送离线评论发出', data: null }
                    : { code: 500, message: (res && res.message) || '记录失败', data: null };
                return Promise.resolve(new Response(JSON.stringify(reply), {
                    status: 200, headers: { 'Content-Type': 'application/json' }
                }));
            };
        }
        if (typeof window.submitComment === 'function') { wrapOnline(); } else { takeover(); }
        })();
        """.trimIndent()
    }
}

/**
 * 评论弹窗注入的离线评论入队桥。不依赖书源（快照可在书源缺失时打开），
 * enqueue 在桥线程同步落库（小事务），结果 JSON 返回给页面脚本。
 */
class ReviewOutboxBridge(private val context: ReviewOutboxContext) {

    @JavascriptInterface
    fun enqueue(json: String): String {
        return ReviewOutboxStore.enqueueFromJs(context, json)
    }

    @JavascriptInterface
    fun toast(msg: String?) {
        val text = when (msg) {
            "empty" -> appCtx.getString(R.string.offline_review_content_empty)
            "too_long" -> appCtx.getString(
                R.string.offline_review_content_too_long,
                PendingReviewComment.MAX_CONTENT_LENGTH
            )
            "book_unsupported" -> appCtx.getString(R.string.offline_review_kind_book_unsupported)
            else -> msg.orEmpty()
        }
        appCtx.toastOnUi(text)
    }
}
