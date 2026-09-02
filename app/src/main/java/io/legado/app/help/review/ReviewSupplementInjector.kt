package io.legado.app.help.review

import io.legado.app.utils.GSON
import org.jsoup.Jsoup

/**
 * 评论快照查看端的章评/书评补充注入器。
 *
 * 抓取端为每章/每本书各存一份章评/书评 tab 补充快照
 * （[ReviewSnapshotStore.CHAPTER_TAB_SRC] / [ReviewSnapshotStore.BOOK_TAB_SRC]）。
 * 查看端打开段评快照时，把补充快照的评论列表提取出来注入当前页面：
 * - 点击“章评/书评” tab 离线切换对应 section（无网络，纯 DOM）；
 * - 楼中楼 toggle 由事件委托接管，可离线收起/展开；
 * - 页面没有 tab 结构（其他书源形态）时注入脚本自动 no-op，不改变原页面。
 *
 * 仅适用于 BottomWebViewDialog 的快照显示路径（持有 book/chapter 上下文与
 * review-resource:// 拦截器）；旧 startBrowser → WebViewActivity 兜底路径没有
 * 章节身份，不做注入。
 */
object ReviewSupplementInjector {

    /** 注入 JS 前从补充快照 HTML 中提取的评论列表内容 */
    private data class SupplementPayload(
        val html: String,
        val count: Int,
    )

    /**
     * 从补充快照 HTML 提取评论列表容器内容与评论卡片数。
     * 优先取 id=contentArea（书山聚合等评论页形态），否则回退到
     * 第一张评论卡片的父容器；提取失败/无卡片返回 null，该 tab 不注入。
     */
    fun extractList(snapshot: ReviewSnapshot): SupplementPayload? {
        if (snapshot.html.isBlank()) return null
        return runCatching {
            val doc = Jsoup.parse(snapshot.html)
            val area = doc.selectFirst("#contentArea")
                ?: doc.selectFirst(".comment-card")?.parent()
                ?: return null
            val count = doc.select(".comment-card").size
            if (count == 0) return null
            SupplementPayload(html = area.html(), count = count)
        }.getOrNull()
    }

    /**
     * 构建注入脚本。chapter/book 为对应补充快照；两者都缺失时返回的脚本
     * 仍会安全 no-op（安装标记不落，避免缺一半内容时误装）。
     */
    fun buildInjectionJs(chapter: ReviewSnapshot?, book: ReviewSnapshot?): String {
        val chapterPayload = chapter?.let(::extractList)
        val bookPayload = book?.let(::extractList)
        val data = GSON.toJson(
            linkedMapOf<String, Any?>().apply {
                chapterPayload?.let { put("chapter", it) }
                bookPayload?.let { put("book", it) }
            }
        )
        return """
        (function(){
        var data = $data;
        var hasChapter = !!(data && data.chapter);
        var hasBook = !!(data && data.book);
        if (!hasChapter && !hasBook) return 'no_supplement';
        if (window.__legadoReviewTabsWired) return 'dup';
        var tabs = document.querySelectorAll('.tab[data-tab]');
        if (!tabs.length) return 'no_tabs';
        var paraArea = document.getElementById('contentArea');
        if (!paraArea || !paraArea.parentNode) return 'no_area';
        window.__legadoReviewTabsWired = true;
        var sections = {};
        ['chapter', 'book'].forEach(function(name) {
            var d = data[name];
            if (!d) return;
            var sec = document.createElement('div');
            sec.id = 'legadoSupplementSection' + name;
            sec.style.display = 'none';
            sec.innerHTML = d.html;
            paraArea.parentNode.insertBefore(sec, paraArea.nextSibling);
            sections[name] = sec;
            var countEl = document.getElementById(name + 'Count');
            if (countEl && d.count) countEl.textContent = String(d.count);
        });
        function activate(name) {
            var isPara = name === 'paragraph';
            document.querySelectorAll('.tab[data-tab]').forEach(function(t) {
                t.classList.toggle('active', (t.dataset.tab || '') === name);
            });
            ['quoteCard', 'sortWrap', 'paraToggle'].forEach(function(id) {
                var el = document.getElementById(id);
                if (el) el.style.display = isPara ? '' : 'none';
            });
            paraArea.style.display = isPara ? '' : 'none';
            Object.keys(sections).forEach(function(key) {
                sections[key].style.display = (key === name) ? '' : 'none';
            });
        }
        tabs.forEach(function(t) {
            t.addEventListener('click', function(ev) {
                ev.preventDefault();
                ev.stopPropagation();
                activate((t.dataset.tab || '').toLowerCase());
            }, true);
        });
        document.addEventListener('click', function(ev) {
            var node = ev.target;
            while (node && node !== document.body &&
                !(node.classList && node.classList.contains('reply-toggle'))) {
                node = node.parentNode;
            }
            if (!node || node === document.body) return;
            var list = node.parentNode ? node.parentNode.querySelector('.reply-list') : null;
            if (!list) return;
            if (!node.dataset.legadoCollapseLabel) {
                var svg = node.querySelector('svg');
                var svgHtml = svg ? svg.outerHTML : '';
                var n = list.querySelectorAll('.reply-item').length;
                node.dataset.legadoCollapseLabel = node.innerHTML;
                node.dataset.legadoExpandLabel = '展开 ' + n + ' 条回复 ' + svgHtml;
            }
            if (list.style.display === 'none') {
                list.style.display = 'block';
                node.classList.add('expanded');
                node.innerHTML = node.dataset.legadoCollapseLabel;
            } else {
                list.style.display = 'none';
                node.classList.remove('expanded');
                node.innerHTML = node.dataset.legadoExpandLabel;
            }
        }, true);
        return 'ok';
        })()
        """.trimIndent()
    }
}
