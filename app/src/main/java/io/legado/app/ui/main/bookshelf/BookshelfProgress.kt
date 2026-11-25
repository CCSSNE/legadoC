package io.legado.app.ui.main.bookshelf

import android.widget.ProgressBar
import android.widget.TextView
import io.legado.app.data.entities.Book
import io.legado.app.help.config.AppConfig
import io.legado.app.utils.gone
import io.legado.app.utils.visible
import kotlin.math.roundToInt

/**
 * 书架列表阅读进度（适配 joestar817/legado_NG af9b2f4cc「新增书架列表阅读进度显示开关」
 * ＋ 140668801「在书架进度条右侧显示阅读百分比」）。
 * 章级估算：书架不加载正文测进度，仅用 Book 的章索引/章内偏移/总章数。
 */
fun bookshelfReadingProgress(chapterIndex: Int, chapterPos: Int, chapterCount: Int): Float {
    if (chapterCount <= 0 || chapterIndex < 0 || (chapterIndex == 0 && chapterPos <= 0)) {
        return 0f
    }
    return ((chapterIndex.toLong() + 1).toDouble() / chapterCount).toFloat().coerceIn(0f, 1f)
}

private fun percentOf(progress: Float): Int {
    return if (progress >= 1f) 100 else (progress * 100f).roundToInt().coerceAtMost(99)
}

/** 「正在读」行尾的后缀（" · 4%"）；未开启/无目录/从未阅读返回 null */
fun bookshelfProgressSuffix(book: Book): String? {
    if (!AppConfig.bookshelfShowReadingProgress) {
        return null
    }
    val progress = bookshelfReadingProgress(book.durChapterIndex, book.durChapterPos, book.totalChapterNum)
    if (progress <= 0f) {
        return null
    }
    return " · " + percentOf(progress) + "%"
}

/** 「正在读」行文本 = 章节标题 + 进度后缀（未开启时即章节标题） */
fun upBookshelfProgressText(textView: TextView, book: Book) {
    textView.text = book.durChapterTitle + (bookshelfProgressSuffix(book) ?: "")
}

/** 条目底部细进度条；未开启/无进度时隐藏 */
fun bindBookshelfProgressBar(progressBar: ProgressBar, book: Book) {
    if (!AppConfig.bookshelfShowReadingProgress) {
        progressBar.gone()
        return
    }
    val progress = bookshelfReadingProgress(book.durChapterIndex, book.durChapterPos, book.totalChapterNum)
    if (progress <= 0f) {
        progressBar.gone()
        return
    }
    progressBar.progress = percentOf(progress)
    progressBar.visible()
}
