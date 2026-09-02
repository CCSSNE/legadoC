package io.legado.app.help.review

import io.legado.app.constant.AppLog
import io.legado.app.ui.book.read.page.entities.ParagraphSegment
import io.legado.app.ui.book.read.page.entities.ReviewButton
import io.legado.app.ui.book.read.page.entities.TextChapter
import io.legado.app.ui.book.read.page.entities.TextParagraph
import kotlin.math.abs

/**
 * 无评论泡段落的合成段评入口。
 *
 * 书源只给有评论的段落注入评论泡（零评论泡由排版层收纳进选区菜单），
 * 完全没有泡的段落原本没有任何段评入口。而服务端已实测确认
 * （2026-09-02，番茄云段评 /idea_comment）：段评 POST 对零评论段落照常生效，
 * para 即该章正文行号（0 起算，不含章节名），para_content 为段落原文
 * 去缩进后前 [PARA_CONTENT_MAX] 字。
 *
 * 因此入口可以合成：借同章任一带可解析 click 的评论泡做锚点，把 click 的
 * 第三个参数（para）按段号差换算成目标段落的正文行号，之后走与真实泡
 * 完全一致的 click 执行链打开评论页。锚点取段号最近的泡；段落自带泡时
 * 合成结果与原泡一致（菜单与点击泡行为相同）。
 *
 * 已知边界：段号差换算要求锚点与目标之间排版段与正文行一一对应；
 * 手工插入的配图段、usehtml 结构块会使段号漂移，此时评论页引用卡
 * （回填的段落原文）与选中文本不一致，可据此发现并放弃发送。
 */
object SyntheticReviewEntry {

    /** 番茄服务端按前 100 字存储段落原文（para=14/17 实测一致），超出截断 */
    private const val PARA_CONTENT_MAX = 100

    /** 书源评论 click 形态：函数名(四个字符串参数)，第三个参数为 para */
    private val clickArgsPattern = Regex(
        "^\\s*([A-Za-z_$][\\w$]*)\\(\\s*\"([^\"]*)\"\\s*,\\s*\"([^\"]*)\"\\s*,\\s*\"([^\"]*)\"\\s*,\\s*\"([^\"]*)\"\\s*\\)\\s*;?\\s*$"
    )

    data class Entry(
        val button: ReviewButton,
        /** 合成出的 para（正文行号，0 起） */
        val para: Int,
        /** 目标段落原文（去缩进、按服务端约定截断），供评论页回填空原文 */
        val paraContent: String,
    )

    /**
     * 为 [paragraphNum]（[TextChapter] 段号，1 起）合成段评入口。
     * 目标是标题段，或本章没有可解析锚点时返回 null，不猜测。
     */
    fun resolve(chapter: TextChapter, paragraphNum: Int): Entry? {
        val paragraphs = chapter.paragraphs
        val target = paragraphs.getOrNull(paragraphNum - 1) ?: return null
        if (target.isTitle) return null
        var bestNum = 0
        var bestArgs: List<String>? = null
        var bestSrc = ""
        for (paragraph in paragraphs) {
            if (paragraph.isTitle) continue
            val candidates = paragraph.segments.filterIsInstance<ParagraphSegment.Review>()
                .map { ReviewButton(it.src, it.click) } + paragraph.hiddenReviewButtons
            for (candidate in candidates) {
                val click = candidate.click?.takeIf { it.isNotBlank() } ?: continue
                val args = clickArgsPattern.matchEntire(click)?.groupValues ?: continue
                if (args[4].toIntOrNull() == null) continue
                val distance = abs(paragraph.num - paragraphNum)
                val bestDistance = abs(bestNum - paragraphNum)
                if (bestArgs == null || distance < bestDistance) {
                    bestNum = paragraph.num
                    bestArgs = args
                    bestSrc = candidate.src
                }
            }
        }
        val anchor = bestArgs ?: return null
        val para = anchor[4].toInt() + (paragraphNum - bestNum)
        if (para < 0) return null
        val rebuiltClick =
            "${anchor[1]}(\"${anchor[2]}\",\"${anchor[3]}\",\"$para\",\"${anchor[5]}\")"
        val paraContent = paragraphContent(target)
        AppLog.putDebug(
            "[段评] 合成无泡段评入口 段号=$paragraphNum para=$para 锚点段=$bestNum " +
                "click=$rebuiltClick 原文长度=${paraContent.length}"
        )
        return Entry(
            button = ReviewButton(bestSrc, rebuiltClick),
            para = para,
            paraContent = paraContent,
        )
    }

    /**
     * 解析评论 click 携带的 para（正文行号）。仅认四字符串参数形态，
     * 其他形态返回 null，不猜测。
     */
    fun parsePara(click: String?): Int? {
        val value = click?.takeIf { it.isNotBlank() } ?: return null
        val args = clickArgsPattern.matchEntire(value)?.groupValues ?: return null
        return args[4].toIntOrNull()
    }

    /** [paragraphNum] 段落的原文（去缩进、按服务端约定截断）；空段返回 null */
    fun paragraphContent(chapter: TextChapter, paragraphNum: Int): String? {
        val target = chapter.paragraphs.getOrNull(paragraphNum - 1) ?: return null
        return paragraphContent(target).takeIf { it.isNotEmpty() }
    }

    /** 段落原文：结构化文本节点拼接，去首尾空白（含全角缩进），按服务端约定截断 */
    private fun paragraphContent(paragraph: TextParagraph): String {
        return paragraph.segments
            .filterIsInstance<ParagraphSegment.Text>()
            .joinToString("") { it.text }
            .trim('　', ' ', '\t')
            .let { if (it.length > PARA_CONTENT_MAX) it.substring(0, PARA_CONTENT_MAX) else it }
    }

}
