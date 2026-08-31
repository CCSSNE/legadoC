package io.legado.app.help.tts

import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.ContentProcessor
import io.legado.app.help.book.simulatedTotalChapterNum
import io.legado.app.ui.book.read.page.provider.ChapterProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * 章节朗读单元序列推导（与 BaseReadAloudService.prepareReadAloudChapter 同源）：
 * 当前阅读配置排版 + getNeedReadAloud 切分。批量缓存（TtsCacheManager）、
 * TTS 缓存归档清单共用同一推导，保证缓存 key 与实际送入引擎的单元文本一致。
 */
object TtsChapterUnits {

    sealed interface Result {
        /** 排版成功。units 为完整朗读单元序列（可能含卷标记等不可朗读行，由调用方过滤）。 */
        data class Ok(val units: List<String>) : Result

        data object ContentUnavailable : Result

        data object LayoutFailed : Result
    }

    suspend fun of(book: Book, chapter: BookChapter, scope: CoroutineScope): Result {
        val rawContent = BookHelp.getContent(book, chapter)
        if (rawContent.isNullOrBlank()) {
            return Result.ContentUnavailable
        }
        val contentProcessor = ContentProcessor.get(book.name, book.origin)
        val displayTitle = chapter.getDisplayTitle(
            contentProcessor.getTitleReplaceRules(),
            book.getUseReplaceRule(),
            replaceBook = book.toReplaceBook(),
        )
        val contents = contentProcessor.getContent(
            book,
            chapter,
            rawContent,
            includeTitle = false,
        )
        val textChapter = ChapterProvider.getTextChapterAsync(
            scope,
            book,
            chapter,
            displayTitle,
            contents,
            book.simulatedTotalChapterNum(),
        )
        textChapter.layoutChannel.receiveAsFlow().collect()
        if (!textChapter.isCompleted || textChapter.pages.isEmpty()) {
            return Result.LayoutFailed
        }
        return Result.Ok(
            // 与播放时引擎单元推导同源：按本书翻页模式判定（滚动锁定关闭），
            // 保证缓存 key 与实际送入引擎的单元文本一致。
            textChapter.getNeedReadAloud(0, book.pageSplitEnabled(), 0)
                .split("\n")
                .filter { it.isNotEmpty() }
        )
    }
}
