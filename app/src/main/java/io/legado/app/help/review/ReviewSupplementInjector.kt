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
 * - 楼中楼默认收起（抓取端为保证内容完整入库穷尽强展过，查看端统一重置），
 *   toggle 由事件委托接管，可离线展开/收起；
 * - 页面没有 tab 结构（其他书源形态）时仅章评/书评补充部分 no-op，
 *   楼中楼默认收起与 toggle 委托仍然安装。
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
    private fun extractList(snapshot: ReviewSnapshot): SupplementPayload? {
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
     * 构建注入脚本。chapter/book 为对应补充快照；两者都缺失时脚本的章评/书评
     * 补充部分 no-op，但楼中楼默认收起与 toggle 委托仍然安装——快照查看的统一
     * 行为，不依赖补充快照是否存在。返回 "tab状态,楼中楼状态" 供日志核对。
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
        var tabResult = 'no_supplement';
        if (hasChapter || hasBook) {
            if (window.__legadoReviewTabsWired) {
                tabResult = 'dup';
            } else {
                var tabs = document.querySelectorAll('.tab[data-tab]');
                var paraArea = document.getElementById('contentArea');
                if (!tabs.length) {
                    tabResult = 'no_tabs';
                } else if (!paraArea || !paraArea.parentNode) {
                    tabResult = 'no_area';
                } else {
                    window.__legadoReviewTabsWired = true;
                    tabResult = 'ok';
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
                    var activate = function(name) {
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
                    };
                    tabs.forEach(function(t) {
                        t.addEventListener('click', function(ev) {
                            ev.preventDefault();
                            ev.stopPropagation();
                            activate((t.dataset.tab || '').toLowerCase());
                        }, true);
                    });
                }
            }
        }
        var replyResult = 'dup';
        if (!window.__legadoReplyWired) {
            window.__legadoReplyWired = true;
            replyResult = 'collapsed';
            var replyListOf = function(toggle) {
                return toggle.parentNode ? toggle.parentNode.querySelector('.reply-list') : null;
            };
            document.addEventListener('click', function(ev) {
                var node = ev.target;
                while (node && node !== document.body &&
                    !(node.classList && node.classList.contains('reply-toggle'))) {
                    node = node.parentNode;
                }
                if (!node || node === document.body) return;
                var list = replyListOf(node);
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
            // 抓取端穷尽强展后的快照里楼中楼全部处于展开态；
            // 先建好补充 section 再统一重置为默认收起，点击 toggle 再展开。
            // “加载更多回复”等 reply-more-btn 位于 reply-list 内部、无嵌套
            // reply-list，经 replyListOf 查找必然落空，天然不会被误改。
            document.querySelectorAll('.reply-toggle').forEach(function(node) {
                var list = replyListOf(node);
                if (!list) return;
                var svg = node.querySelector('svg');
                var svgHtml = svg ? svg.outerHTML : '';
                var n = list.querySelectorAll('.reply-item').length;
                node.dataset.legadoCollapseLabel = node.innerHTML;
                node.dataset.legadoExpandLabel = '展开 ' + n + ' 条回复 ' + svgHtml;
                list.style.display = 'none';
                node.classList.remove('expanded');
                node.innerHTML = node.dataset.legadoExpandLabel;
            });
        }
        return tabResult + ',' + replyResult;
        })()
        """.trimIndent()
    }
}
