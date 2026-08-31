package io.legado.app.help.book

import android.content.Context
import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.help.book.isAudio
import io.legado.app.help.book.isImage
import io.legado.app.help.book.isShortcut
import io.legado.app.help.book.isVideo
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 书架选择模式「融合」动作：把文字书里已有的段落评论入口挂载到对应的有声书。
 * Audio = 主体（音频与字幕不变），Text 只提供评论元数据；
 * 书名/作者不需一致（书源别名常见），章节配对由引擎按标题/章节号匹配；
 * 仅处理双方已缓存的章节，不联网下载。融合结果单独保存为 overlay，
 * 不覆盖有声书原始字幕，支持重新融合与取消融合。
 *
 * 书架主页（BooksFragment）与合集详情页（BookCollectionActivity）的选择模式共用本入口。
 * UI 归属调用方：确认弹窗经 [confirm] 注入，完成收尾经 [onFinish] 注入（各页面自行清空选择）。
 */
class BookFusionAction(
    private val context: Context,
    private val scope: CoroutineScope,
    private val confirm: (titleRes: Int, messageRes: Int, onOk: () -> Unit) -> Unit,
    private val onFinish: () -> Unit
) {

    /** 融合按钮是否可点：只选书（无合集、无快捷方式条目）即可，具体错误在执行时提示 */
    fun available(selectedBooks: Collection<Book>, selectedCollectionCount: Int = 0): Boolean {
        if (selectedCollectionCount > 0 || selectedBooks.isEmpty()) return false
        if (selectedBooks.any { it.isShortcut }) return false
        return true
    }

    /** 融合/取消融合主入口：2 本 = 一音频一文本融合，1 本有声书 = 取消融合 */
    fun run(selectedBooks: Collection<Book>, selectedCollectionCount: Int = 0) {
        if (selectedCollectionCount > 0) {
            context.toastOnUi(R.string.fusion_need_two_books)
            return
        }
        when (selectedBooks.size) {
            2 -> {
                val books = selectedBooks.toList()
                val audioBook = books.singleOrNull { it.isAudio }
                val textBook = books.singleOrNull { !it.isAudio && !it.isVideo && !it.isImage }
                if (audioBook == null || textBook == null) {
                    val typeTag = { book: Book ->
                        when {
                            book.isAudio -> "音频"
                            book.isVideo -> "视频"
                            book.isImage -> "漫画"
                            else -> "文本"
                        }
                    }
                    context.toastOnUi(
                        context.getString(R.string.fusion_type_invalid) +
                                "\n" + books.joinToString(" / ") { "${it.name}（${typeTag(it)}）" }
                    )
                    return
                }
                confirm(
                    R.string.fusion_confirm_title,
                    R.string.fusion_confirm_message
                ) {
                    fuseAudioWithComments(audioBook, textBook)
                }
            }

            1 -> confirmCancelFusion(selectedBooks.single().takeIf { it.isAudio })

            else -> context.toastOnUi(R.string.fusion_need_two_books)
        }
    }

    private fun fuseAudioWithComments(audioBook: Book, textBook: Book) {
        scope.launch {
            val result = try {
                withContext(Dispatchers.IO) {
                    AudioTextFusion.fuseBooks(textBook, audioBook)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.put("融合评论失败 ${textBook.name} -> ${audioBook.name}", e)
                context.toastOnUi(context.getString(R.string.fusion_failed, e.localizedMessage ?: "unknown"))
                return@launch
            }
            // 一次融合在 AppLog 只落一条多行诊断（章节配对、每章匹配成功/失败、统计）
            AppLog.put(result.detail)
            if (result.migratedAnything) {
                context.toastOnUi(
                    context.getString(
                        R.string.fusion_done,
                        result.pairedChapters,
                        result.fusedChapters,
                        result.migratedEntries,
                    )
                )
            } else {
                context.toastOnUi(R.string.fusion_nothing)
            }
            onFinish()
        }
    }

    /** 单个有声书：已有融合 overlay 时确认取消；否则提示需要两本书 */
    private fun confirmCancelFusion(audioBook: Book?) {
        if (audioBook == null) {
            context.toastOnUi(R.string.fusion_need_two_books)
            return
        }
        scope.launch {
            val hasOverlay = withContext(Dispatchers.IO) {
                appDb.bookChapterDao.getChapterList(audioBook.bookUrl)
                    .any { it.getVariable(AudioTextFusion.OVERLAY_KEY).isNotBlank() }
            }
            if (!hasOverlay) {
                context.toastOnUi(R.string.fusion_need_two_books)
                return@launch
            }
            confirm(R.string.fusion_cancel_confirm_title, R.string.fusion_cancel_confirm_message) {
                scope.launch {
                    val removed = try {
                        withContext(Dispatchers.IO) {
                            AudioTextFusion.removeFusionOverlay(audioBook)
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        AppLog.put("取消融合失败 ${audioBook.name}", e)
                        context.toastOnUi(context.getString(R.string.fusion_failed, e.localizedMessage ?: "unknown"))
                        return@launch
                    }
                    context.toastOnUi(context.getString(R.string.fusion_cancel_done, removed))
                    onFinish()
                }
            }
        }
    }
}
