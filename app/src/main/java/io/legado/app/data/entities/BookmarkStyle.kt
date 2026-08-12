package io.legado.app.data.entities

/**
 * 书签正文在阅读页中的显示样式（位掩码，可多选组合）
 */
object BookmarkStyle {
    const val NONE = 0
    const val SINGLE_UNDERLINE = 1
    const val DOUBLE_UNDERLINE = 1 shl 1
    const val WAVE_UNDERLINE = 1 shl 2
    const val HIGHLIGHT = 1 shl 3
    const val TEXT_COLOR = 1 shl 4
    const val STRIKETHROUGH = 1 shl 5
}
