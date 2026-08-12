package io.legado.app.help.illustration

/**
 * 选区段落的配图插入锚点。
 *
 * @param anchorType between_paragraphs：插入 front/back 两段之间；
 *                    chapter_end：插入该章末尾。
 * @param anchorPos 段落边界在章节文本（排版后）中的字符偏移；chapter_end 时忽略。
 */
data class IllustrationAnchor(
    val anchorType: String,
    val anchorPos: Int,
    val frontParagraph: String,
    val backParagraph: String
)
