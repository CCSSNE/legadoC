package io.legado.app.help.tts

/**
 * 生成语音时移除对白最外层的排版引号，正文显示仍保留原样。
 * 移植自阅读 NG help/tts/TtsSynthesisText.kt。
 */
fun normalizeStoryboardSynthesisText(
    text: String,
    type: StoryboardSegmentType?
): String {
    val value = text.trim()
    if (type == null || type == StoryboardSegmentType.NARRATION || value.length < 2) {
        return value
    }
    val matchingEnd = when (value.first()) {
        '“' -> '”'
        '‘' -> '’'
        '「' -> '」'
        '『' -> '』'
        '"' -> '"'
        else -> null
    }
    return if (matchingEnd != null && value.last() == matchingEnd) {
        value.substring(1, value.lastIndex).trim()
    } else {
        value
    }
}
