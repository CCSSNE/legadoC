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
 * 融合挂载的评论按钮（来自文字书）点击时按文字书上下文执行：
 * 通过当前展示章节的融合 overlay 反查来源文字书/章节/书源，
 * 点击 JS 与评论快照读写都按文字书走，不使用有声书上下文；
 * 原生按钮（无 overlay 命中）回退当前阅读上下文。
 *
 * 同一套执行逻辑同时服务用户点击与后台评论快照抓取：
 * 抓取时传入拦截宿主（click 分支替换 java 宿主；js 分支挂 AnalyzeRule 钩子），
 * 把“打开评论页”替换为“记录评论页地址”，其余环境完全一致。
 */
object BookImgClick {

    /**
     * 评论快照离线优先：该书该章该按钮已有快照时直接本地渲染，不再联网。
     * 融合按钮按文字书上下文查快照。
     * @return true 表示已用快照打开
     */
    private fun openSnapshotIfCached(
        context: AppCompatActivity,
        src: String,
        hostChapter: BookChapter?
    ): Boolean {
        val chapter = hostChapter
            ?: ReadBook.book?.let {
                appDb.bookChapterDao.getChapter(it.bookUrl, ReadBook.durChapterIndex)
            }
            ?: return false
        val execution = executionContext(chapter, src) ?: return false
        val snapshotBook = execution.first
        val snapshotSource = execution.second
        val snapshotChapter = execution.third
        val snapshot = ReviewSnapshotStore.get(snapshotBook, snapshotChapter, src.trim())
            ?: return false
        context.runOnUiThread {
            if (context.isFinishing || context.isDestroyed) return@runOnUiThread
            context.showDialogFragment(
                BottomWebViewDialog(
                    snapshotSource?.getKey().orEmpty(),
                    BookType.text,
                    snapshot.url.ifBlank { "about:blank" },
                    snapshot.html
                )
            )
        }
        return true
    }

    /**
     * 解析点击执行上下文：
     * - 当前章节的融合 overlay 未命中该 src → 原生按钮，回退当前阅读
     *   上下文（ReadBook）；
     * - 命中融合来源 → 必须解析出文字书、章节、书源，任一不存在返回
     *   null（直接失效，不回退到有声书上下文执行）。
     */
    private fun executionContext(
        chapter: BookChapter,
        src: String
    ): Triple<Book, BookSource?, BookChapter>? {
        val textContext = AudioTextFusion.findFusionTextContext(
            chapter.getVariable(AudioTextFusion.OVERLAY_KEY),
            src
        ) ?: run {
            // 未命中：原生按钮，走当前阅读上下文
            val book = ReadBook.book ?: return null
            return Triple(book, ReadBook.bookSource, chapter)
        }
        // 命中融合来源但书/章节/书源已不存在：直接失效，不回退
        val textBook = appDb.bookDao.getBook(textContext.first) ?: return null
        val textChapter = appDb.bookChapterDao.getChapterByUrl(textContext.first, textContext.second)
            ?: return null
        val textSource = appDb.bookSourceDao.getBookSource(textBook.origin) ?: return null
        return Triple(textBook, textSource, textChapter)
    }

    /**
     * 执行 src 附带的 click JS（用户点击路径）。
     * @param hostChapter 当前展示章节；融合挂载的评论按钮据此反查文字书
     * 上下文执行；为 null 时按当前阅读上下文（ReadBook）执行。
     */
    fun clickImg(
        context: AppCompatActivity,
        scope: CoroutineScope,
        click: String,
        src: String,
        hostChapter: BookChapter? = null,
    ) {
        if (openSnapshotIfCached(context, src, hostChapter)) return
        Coroutine.async(scope, Dispatchers.IO) {
            val chapter = hostChapter
                ?: ReadBook.book?.let {
                    appDb.bookChapterDao.getChapter(it.bookUrl, ReadBook.durChapterIndex)
                }
                ?: return@async
            // null = 融合来源实体缺失，直接失效不执行
            val (book, source, execChapter) = executionContext(chapter, src) ?: return@async
            val execSource = source ?: return@async
            executeClick(book, execSource, execChapter, click, src) {
                SourceLoginJsExtensions(context, execSource, BookType.text)
            }
        }.onError {
            AppLog.put("执行图片链接click键值出错\n${it.localizedMessage}", it, true)
        }
    }

    /**
     * 兼容旧源：click/js 写在 src 的 url 选项里（无独立 click 字段时走此入口）。
     * @param hostChapter 当前展示章节；融合挂载的评论按钮据此反查文字书上下文。
     * @return true 表示已处理
     */
    fun oldClickImg(
        context: AppCompatActivity,
        scope: CoroutineScope,
        src: String,
        hostChapter: BookChapter? = null,
    ): Boolean {
        val (urlNoOption, options) = parseSrcOptions(src) ?: return false
        val click = options["click"]
        val js = options["js"]
        if (click.isNullOrBlank() && js.isNullOrBlank()) return false
        if (openSnapshotIfCached(context, src, hostChapter)) return true
        Coroutine.async(scope, Dispatchers.IO) {
            val chapter = hostChapter
                ?: ReadBook.book?.let {
                    appDb.bookChapterDao.getChapter(it.bookUrl, ReadBook.durChapterIndex)
                }
                ?: return@async
            // null = 融合来源实体缺失，直接失效不执行
            val (book, source, execChapter) = executionContext(chapter, src) ?: return@async
            val execSource = source ?: return@async
            when {
                !click.isNullOrBlank() -> executeClick(book, execSource, execChapter, click, src) {
                    SourceLoginJsExtensions(context, execSource, BookType.text)
                }
                else -> executeJs(book, execSource, execChapter, js.orEmpty(), urlNoOption)
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