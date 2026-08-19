package io.legado.app.ui.book.read.page.entities

import io.legado.app.ui.book.read.page.entities.column.ImageColumn
import io.legado.app.ui.book.read.page.provider.ChapterProvider

/**
 * 段落内联结构化节点：正文文本与评论按钮。
 *
 * 阅读页与沉浸听书页共用同一数据源：排版层把 usehtml 评论/`TEXT` 样式小图
 * 落成 [ImageColumn]，这里按段落内顺序拆成 [Text] / [Review] 节点，
 * 沉浸页直接消费 [Review] 的 src/click，不再靠特殊占位符识别评论。
 */
sealed interface ParagraphSegment {

    /** 普通正文文本（含行内图片等既有占位字符，按原样显示） */
    data class Text(val text: String) : ParagraphSegment

    /**
     * 评论按钮节点。
     *
     * @param src   评论按钮原图地址，阅读页直接绘制该图片（保留原样式与评论数量）
     * @param click 书源定义的点击 JS，空表示无点击行为
     */
    data class Review(val src: String, val click: String?) : ParagraphSegment

    companion object {

        /** 富文本排版里 [android.text.style.ImageSpan] 替换的空格字符 */
        private const val OBJECT_REPLACEMENT_CHAR = '\uFFFC'

        /**
         * 把 [TextParagraph] 按行内顺序拆成结构化节点。
         *
         * 每个 [ImageColumn] 恰好对应段落文本中的 1 个占位字符；评论占位符
         * （[ChapterProvider.reviewChar]）取该列的 src/click 生成 [Review]，
         * 其余字符归入 [Text]。对列与占位符逐行对齐，互不跨行串位。
         */
        fun build(paragraph: TextParagraph): List<ParagraphSegment> {
            val segments = ArrayList<ParagraphSegment>()
            val text = StringBuilder()
            fun flushText() {
                if (text.isNotEmpty()) {
                    segments.add(Text(text.toString()))
                    text.clear()
                }
            }
            paragraph.textLines.forEach { line ->
                val imageColumns = line.columns.filterIsInstance<ImageColumn>()
                var imageIndex = 0
                val lineText = line.text
                var index = 0
                while (index < lineText.length) {
                    val char = lineText[index]
                    if (char == ChapterProvider.reviewChar ||
                        char == ChapterProvider.srcReplaceChar ||
                        char == OBJECT_REPLACEMENT_CHAR
                    ) {
                        val imageColumn = imageColumns.getOrNull(imageIndex)
                        imageIndex++
                        if (imageColumn != null && char == ChapterProvider.reviewChar) {
                            flushText()
                            segments.add(Review(imageColumn.src, imageColumn.click))
                        } else {
                            // 行内图片占位符等：保持原文本，交由显示端按现有行为展示
                            text.append(char)
                        }
                    } else {
                        text.append(char)
                    }
                    index++
                }
            }
            flushText()
            return segments
        }
    }
}