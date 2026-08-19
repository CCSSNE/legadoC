package io.legado.app.ui.book.read.page.entities

@Suppress("unused", "MemberVisibilityCanBePrivate")
data class TextParagraph(
    var num: Int,
    val textLines: ArrayList<TextLine> = arrayListOf(),
) {
    val text: String get() = textLines.joinToString("") { it.text }
    val length: Int get() = text.length

    /**
     * 结构化段落节点（[ParagraphSegment.Text] / [ParagraphSegment.Review]），
     * 按段落内顺序排列；评论节点带原始 src/click，供阅读页与沉浸听书页共用。
     */
    val segments: List<ParagraphSegment> get() = ParagraphSegment.build(this)
    val firstLine: TextLine get() = textLines.first()
    val lastLine: TextLine get() = textLines.last()
    val chapterIndices: IntRange get() = firstLine.chapterPosition..lastLine.chapterPosition + lastLine.charSize
    val chapterPosition: Int get() = firstLine.chapterPosition
    val realNum: Int get() = firstLine.paragraphNum
    val isParagraphEnd: Boolean get() = lastLine.isParagraphEnd
    val isTitle: Boolean get() = textLines.isNotEmpty() && textLines.all { it.isTitle }
    val isStructuralHtml: Boolean get() = textLines.isNotEmpty() && textLines.all { it.isStructuralHtml }

}
