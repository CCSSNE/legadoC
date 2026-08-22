package io.legado.app.help.book

import androidx.appcompat.app.AppCompatActivity
import com.script.rhino.runScriptWithContext
import io.legado.app.constant.AppLog
import io.legado.app.constant.BookType
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.review.ReviewSnapshotStore
import io.legado.app.model.ReadBook
import io.legado.app.model.analyzeRule.AnalyzeRule
import io.legado.app.model.analyzeRule.AnalyzeRule.Companion.setChapter
import io.legado.app.model.analyzeRule.AnalyzeRule.Companion.setCoroutineContext
import io.legado.app.model.analyzeRule.AnalyzeUrl.Companion.paramPattern
import io.legado.app.ui.login.SourceLoginJsExtensions
import io.legado.app.ui.widget.dialog.BottomWebViewDialog
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.showDialogFragment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.coroutines.coroutineContext

/**
 * 图片/评论点击统一入口。
 *
 * 阅读页与沉浸听书页共用同一套“src + click / 旧源 js”执行逻辑：
 * - click 分支：在书源 JS 上下文里注入 java / book / chapter / result，执行书源定义的 click 代码；
 * - 旧源 js 分支：AnalyzeRule + setBaseUrl(chapter.url) + setChapter(chapter) + evalJS，
 *   与书源规则引擎同一语义。
 *
 * 同一套执行逻辑同时服务用户点击与后台评论快照抓取：
 * 抓取时传入拦截宿主（click 分支替换 java 宿主；js 分支挂 AnalyzeRule 钩子），
 * 把“打开评论页”替换为“记录评论页地址”，其余环境完全一致。
 */
object BookImgClick {

    /**
     * 评论快照离线优先：该书该章该按钮已有快照时直接本地渲染，不再联网。
     * @return true 表示已用快照打开
     */
    private fun openSnapshotIfCached(
        context: AppCompatActivity,
        src: String
    ): Boolean {
        val book = ReadBook.book ?: return false
        val chapter = appDb.bookChapterDao.getChapter(book.bookUrl, ReadBook.durChapterIndex)
            ?: return false
        val snapshot = ReviewSnapshotStore.get(book, chapter, src.trim()) ?: return false
        context.runOnUiThread {
            if (context.isFinishing || context.isDestroyed) return@runOnUiThread
            context.showDialogFragment(
                BottomWebViewDialog(
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
     * 执行 src 附带的 click JS（用户点击路径）。
     */
    fun clickImg(
        context: AppCompatActivity,
        scope: CoroutineScope,
        click: String,
        src: String,
    ) {
        if (openSnapshotIfCached(context, src)) return
        Coroutine.async(scope, Dispatchers.IO) {
            val source = ReadBook.bookSource ?: return@async
            val book = ReadBook.book ?: return@async
            val chapter = appDb.bookChapterDao.getChapter(book.bookUrl, ReadBook.durChapterIndex)
                ?: throw Exception("no find chapter")
            executeClick(book, source, chapter, click, src) {
                SourceLoginJsExtensions(context, source, BookType.text)
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
        val (urlNoOption, options) = parseSrcOptions(src) ?: return false
        val click = options["click"]
        val js = options["js"]
        if (click.isNullOrBlank() && js.isNullOrBlank()) return false
        if (openSnapshotIfCached(context, src)) return true
        Coroutine.async(scope, Dispatchers.IO) {
            val source = ReadBook.bookSource ?: return@async
            val book = ReadBook.book ?: return@async
            val chapter = appDb.bookChapterDao.getChapter(book.bookUrl, ReadBook.durChapterIndex)
                ?: throw Exception("no find chapter")
            when {
                !click.isNullOrBlank() -> executeClick(book, source, chapter, click, src) {
                    SourceLoginJsExtensions(context, source, BookType.text)
                }
                else -> executeJs(book, source, chapter, js.orEmpty(), urlNoOption)
            }
        }.onError {
            AppLog.put("执行图片链接click/js键值出错\n${it.localizedMessage}", it, true)
        }
        return true
    }

    /**
     * 解析 src 的选项 JSON：返回 (去选项地址, 选项Map)。无选项时返回 null。
     */
    fun parseSrcOptions(src: String): Pair<String, Map<String, String>>? {
        val urlMatcher = paramPattern.matcher(src)
        if (!urlMatcher.find()) return null
        val urlNoOption = src.take(urlMatcher.start())
        val urlOptionStr = src.substring(urlMatcher.end())
        val options = GSON.fromJsonObject<Map<String, String>>(urlOptionStr).getOrNull() ?: return null
        return urlNoOption to options
    }

    /**
     * click 分支统一执行：与用户点击完全一致的环境，java 宿主可替换为拦截宿主。
     */
    suspend fun executeClick(
        book: Book,
        source: BookSource,
        chapter: BookChapter,
        click: String,
        src: String,
        javaBuilder: () -> io.legado.app.help.JsExtensions
    ) {
        runScriptWithContext {
            source.evalJS(click) {
                val java = javaBuilder()
                put("java", java)
                put("book", book)
                put("chapter", chapter)
                put("result", src)
            }
        }
    }

    /**
     * 旧源 js 分支统一执行：AnalyzeRule 规则引擎环境，与用户点击完全一致。
     * 评论快照抓取可通过 [AnalyzeRule.onBrowserOpenRequestedHook] 拦截浏览器请求。
     */
    suspend fun executeJs(
        book: Book,
        source: BookSource,
        chapter: BookChapter,
        js: String,
        urlNoOption: String,
        ruleHook: (AnalyzeRule.() -> Unit)? = null
    ) {
        AnalyzeRule(book, source).apply {
            setCoroutineContext(coroutineContext)
            setBaseUrl(chapter.url)
            setChapter(chapter)
            ruleHook?.invoke(this)
            evalJS(js, urlNoOption)
        }
    }
}