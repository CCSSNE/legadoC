package io.legado.app.help.book

import io.legado.app.utils.StringUtils
import java.util.regex.Pattern

/**
 * 章节标题解析：章节号（中文/阿拉伯数字）提取。
 *
 * 纯函数、无 Android 依赖，可在 JVM 单元测试中直接使用；
 * [BookHelp.getChapterNum] 委托本解析器，音频文本融合等场景复用
 * 同一解析口径，避免各场景各写一套章节号正则。
 */
object ChapterTitle {

    private val spaceRegex by lazy { "\\s".toRegex() }

    private val NUMBER_CHARS = "[\\d零〇一二两三四五六七八九十百千万壹贰叁肆伍陆柒捌玖拾佰仟]+"

    private val pattern1 by lazy {
        Pattern.compile(".*?第($NUMBER_CHARS)[章节篇回集话]")
    }

    @Suppress("RegExpSimplifiable")
    private val pattern2 by lazy {
        Pattern.compile("^(?:$NUMBER_CHARS[,:、])*($NUMBER_CHARS)(?:[,:、]|\\.[^\\d])")
    }

    /** 解析章节名中的章节号（“第N章/回/集……”“N、标题”等形态）；解析失败返回 -1 */
    fun num(chapterName: String?): Int {
        chapterName ?: return -1
        val normalized = StringUtils.fullToHalf(chapterName).replace(spaceRegex, "")
        return StringUtils.stringToInt(
            (
                    pattern1.matcher(normalized).takeIf { it.find() }
                        ?: pattern2.matcher(normalized).takeIf { it.find() }
                    )?.group(1)
                ?: "-1"
        )
    }
}