package io.legado.app.help.book

import androidx.appcompat.app.AppCompatActivity
import com.script.rhino.runScriptWithContext
import io.legado.app.constant.AppLog
import io.legado.app.constant.BookType
import io.legado.app.data.appDb
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.model.ReadBook
import io.legado.app.model.analyzeRule.AnalyzeRule
import io.legado.app.model.analyzeRule.AnalyzeRule.Companion.setChapter
import io.legado.app.model.analyzeRule.AnalyzeRule.Companion.setCoroutineContext
import io.legado.app.model.analyzeRule.AnalyzeUrl.Companion.paramPattern
import io.legado.app.ui.login.SourceLoginJsExtensions
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.showDialogFragment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

/**
 * 图片/评论点击统一入口。
 *
 * 阅读页与沉浸听书页共用同一套“src + click JS”执行逻辑：
 * 在书源 JS 上下文里注入 java / book / chapter / result，执行书源定义的
 * click 代码（通常由它弹出评论页），两端行为完全一致，不各自实现一套。
 */
object BookImgClick {

    /**
     * 评论快照离线优先：该书该章该按钮已有快照时直接本地渲染，不再联网。
     * @return true 表示已用快照打开
     */
    private fun openSnapshotIfCached(
        context: AppCompatActivity,
        chapterIndex: Int,
        src: String
    ): Boolean {
        val book = ReadBook.book ?: return false
        val snapshot = io.legado.app.help.review.ReviewSnapshotStore.get(
            book, chapterIndex, src.trim()
        ) ?: return false
        context.runOnUiThread {
            if (context.isFinishing || context.isDestroyed) return@runOnUiThread
            context.showDialogFragment(
                io.legado.app.ui.widget.dialog.BottomWebViewDialog(
                    ReadBook.bookSource?.getKey().orEmpty(),
                    BookType.text,
                    snapshot.url.ifBlank { "about:blank" },
                    snapshot.html
                )
            )
        }
        return true
    }

    /**
     * 执行 src 附带的 click JS。
     *
     * @param context JS 扩展（java 对象）的宿主 Activity
     * @param scope   协程宿主（调用方的 lifecycleScope）
     * @param click   书源定义的点击 JS 代码
     * @param src     图片/评论按钮原地址，作为 result 传入 JS
     */
    fun clickImg(
        context: AppCompatActivity,
        scope: CoroutineScope,
        click: String,
        src: String,
    ) {
        val book = ReadBook.book
        val durIndex = ReadBook.durChapterIndex
        if (book != null && openSnapshotIfCached(context, durIndex, src)) {
            return
        }
        Coroutine.async(scope, Dispatchers.IO) {
            val source = ReadBook.bookSource ?: return@async
            val java = SourceLoginJsExtensions(context, source, BookType.text)
            val book = ReadBook.book ?: return@async
            val chapter = appDb.bookChapterDao.getChapter(book.bookUrl, ReadBook.durChapterIndex)
                ?: throw Exception("no find chapter")
            runScriptWithContext {
                source.evalJS(click) {
                    put("java", java)
                    put("book", book)
                    put("chapter", chapter)
                    put("result", src)
                }
            }
        }.onError {
            AppLog.put("执行图片链接click键值出错\n${it.localizedMessage}", it, true)
        }
    }

    /**
     * 兼容旧源：click/js 写在 src 的 url 选项里（无独立 click 字段时走此入口）。
     *
     * @return true 表示已处理
     */
    fun oldClickImg(
        context: AppCompatActivity,
        scope: CoroutineScope,
        src: String,
    ): Boolean {
        val urlMatcher = paramPattern.matcher(src)
        if (urlMatcher.find()) {
            val urlOptionStr = src.substring(urlMatcher.end())
            val urlOptionMap = GSON.fromJsonObject<Map<String, String>>(urlOptionStr).getOrNull()
            val click = urlOptionMap?.get("click")
            if (click != null) {
                val book = ReadBook.book
                val durIndex = ReadBook.durChapterIndex
                if (book != null && openSnapshotIfCached(context, durIndex, src)) {
                    return true
                }
                Coroutine.async(scope, Dispatchers.IO) {
                    val source = ReadBook.bookSource ?: return@async
                    val java = SourceLoginJsExtensions(context, source, BookType.text)
                    val book = ReadBook.book ?: return@async
                    val chapter = appDb.bookChapterDao.getChapter(book.bookUrl, ReadBook.durChapterIndex)
                        ?: throw Exception("no find chapter")
                    runScriptWithContext {
                        source.evalJS(click) {
                            put("java", java)
                            put("book", book)
                            put("chapter", chapter)
                            put("result", src)
                        }
                    }
                }.onError {
                    AppLog.put("执行图片链接click键值出错\n${it.localizedMessage}", it, true)
                }
                return true
            }
            val jsStr = urlOptionMap?.get("js") ?: return false
            Coroutine.async(scope, Dispatchers.IO) {
                val source = ReadBook.bookSource ?: return@async
                val book = ReadBook.book ?: return@async
                val chapter = appDb.bookChapterDao.getChapter(book.bookUrl, ReadBook.durChapterIndex)
                    ?: throw Exception("no find chapter")
                val urlNoOption = src.take(urlMatcher.start())
                AnalyzeRule(book, source).apply {
                    setCoroutineContext(coroutineContext)
                    setBaseUrl(chapter.url)
                    setChapter(chapter)
                    evalJS(jsStr, urlNoOption)
                }
            }.onError {
                AppLog.put("执行图片链接js键值出错\n${it.localizedMessage}", it, true)
            }
            return true
        }
        return false
    }
}