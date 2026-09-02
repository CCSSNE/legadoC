package io.legado.app.help.review

import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/**
 * 评论快照增量合并器。
 *
 * 增量下载拿到的是评论页第一屏（不点“加载更多”翻页，历史评论页不重新拉取）的
 * 序列化快照；本对象把其中“原快照没有的新评论条目”合入原快照 DOM，原快照始终
 * 作为基底——已有评论一律保留，绝不删除、绝不整页覆盖。
 *
 * 评论页 DOM 结构因书源而异，合并不依赖具体模板：
 * 1. 在两份文档中各自定位“评论条目容器”——子元素按（标签名+归一化 class）分组后
 *    重复数量最多、文本量最大的重复兄弟组（同一模板下两边命中同一容器形态）；
 * 2. 条目身份 = 剥离回复层后的纯字母文本 + 图片数（时间/点赞数等易变数字不参与）；
 * 3. 新身份条目按原顺序插到原条目列表首位（新评论按时间倒序排在最前）。
 *
 * 任何一步无法可靠完成（容器缺失、条目形态与原快照不一致）都返回 null，调用方
 * 保留原快照不动，绝不落盘半合并结果。
 */
object ReviewSnapshotMerger {

    data class Result(
        /** 合并后的完整快照 HTML（以原快照为基底） */
        val html: String,
        /** 实际合入的新评论条数；0 表示第一屏没有新评论，无需落盘 */
        val addedCount: Int,
    )

    /** 回复层容器/toggle：身份计算前统一剥离，两侧条目才可比 */
    private const val REPLY_STRIP_SELECTOR = ".reply-section, .reply-list, .reply-toggle"

    /** 自身文本命中回复开关的元素同样剥离（覆盖没有约定 class 的模板） */
    private val REPLY_TOGGLE_TEXT = Regex("展开.{0,8}回复|收起.{0,6}回复|更多回复|查看回复|全部回复")

    /** 分组签名中剔除的易变状态 class，避免同一模板因 active 等状态对不上号 */
    private val STATE_CLASS_TOKENS = setOf(
        "active", "selected", "current", "checked", "first", "last", "odd", "even",
        "open", "show", "hide", "expanded", "collapsed", "focus", "hover", "on", "off",
    )

    private val TRAILING_DIGITS = Regex("\\d+$")

    /**
     * 以 [baseHtml] 为基底合入 [incomingHtml] 中的新增评论。
     * @return null = 无法可靠合并（保留原快照）；[Result.addedCount] = 0 表示无新增
     */
    fun merge(baseHtml: String, incomingHtml: String): Result? {
        if (baseHtml.isBlank() || incomingHtml.isBlank()) return null
        return runCatching {
            val baseDoc = Jsoup.parse(baseHtml)
            val incomingDoc = Jsoup.parse(incomingHtml)
            val baseGroup = dominantRepeatedGroup(baseDoc.body()) ?: return@runCatching null
            val incomingGroup = dominantRepeatedGroup(incomingDoc.body()) ?: return@runCatching null
            // 条目形态不一致说明模板已变化（改版/命中了错误分组），合入只会产生脏 DOM
            if (baseGroup.itemSignature != incomingGroup.itemSignature) return@runCatching null
            val baseIdentities = baseGroup.items.mapTo(hashSetOf()) { identity(it) }
            val fresh = incomingGroup.items.filter { identity(it) !in baseIdentities }
            if (fresh.isEmpty()) return@runCatching Result(baseHtml, 0)
            val anchor = baseGroup.items.first()
            fresh.forEach { item -> anchor.before(item.clone()) }
            Result(baseDoc.outerHtml(), fresh.size)
        }.getOrNull()
    }

    private data class RepeatedGroup(
        val items: List<Element>,
        val itemSignature: String,
    )

    /**
     * 定位文档中的主导重复兄弟组：遍历全部元素作为候选容器，子元素按
     * （标签名+归一化 class）分组，取重复数 ≥2 的组；全局以（组内文本总量、条目数）
     * 取最优，平局取文档序靠前者（外层容器优先，评论列表文本量天然占优）。
     */
    private fun dominantRepeatedGroup(root: Element?): RepeatedGroup? {
        root ?: return null
        var best: RepeatedGroup? = null
        var bestTextLength = -1
        var bestSize = -1
        val candidates = sequenceOf(root) + root.select("*")
        candidates.forEach { container ->
            val groups = linkedMapOf<String, MutableList<Element>>()
            container.children().forEach { child ->
                val signature = elementSignature(child)
                groups.getOrPut(signature) { mutableListOf() }.add(child)
            }
            groups.entries.forEach { (signature, group) ->
                if (group.size < 2) return@forEach
                var textLength = 0
                group.forEach { textLength += it.text().length }
                if (textLength <= 0) return@forEach
                if (textLength > bestTextLength ||
                    (textLength == bestTextLength && group.size > bestSize)
                ) {
                    best = RepeatedGroup(group, signature)
                    bestTextLength = textLength
                    bestSize = group.size
                }
            }
        }
        return best
    }

    /** 条目分组签名：标签名 + 归一化 class（剔数字后缀与状态 token、排序去重） */
    private fun elementSignature(element: Element): String {
        val rawClass = element.className().trim()
        val normalizedClass = if (rawClass.isEmpty()) {
            ""
        } else {
            rawClass.lowercase()
                .split(Regex("\\s+"))
                .map { TRAILING_DIGITS.replace(it, "") }
                .filter { it.isNotEmpty() && it !in STATE_CLASS_TOKENS }
                .distinct()
                .sorted()
                .joinToString(" ")
        }
        return "${element.tagName()}|$normalizedClass"
    }

    /**
     * 条目身份：剥离回复层后，仅保留 Unicode 字母文本（时间、点赞数、楼层号等
     * 易变数字一律不参与比较）+ 图片元素数。宁漏不重：同身份的新条目会被跳过，
     * 但绝不会把同一评论合入两次。
     */
    private fun identity(item: Element): String {
        val clean = item.clone()
        clean.select(REPLY_STRIP_SELECTOR).remove()
        clean.allElements
            .filter { it !== clean && REPLY_TOGGLE_TEXT.containsMatchIn(it.ownText().trim()) }
            .forEach { it.remove() }
        val text = buildString {
            clean.text().forEach { ch -> if (Character.isLetter(ch)) append(ch) }
        }
        return "$text|${clean.select("img").size}"
    }
}
