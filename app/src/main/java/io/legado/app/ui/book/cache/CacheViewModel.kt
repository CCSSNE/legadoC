package io.legado.app.ui.book.cache

import android.app.Application
import androidx.lifecycle.MutableLiveData
import io.legado.app.base.BaseViewModel
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.isLocal
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.utils.sendValue
import kotlinx.coroutines.ensureActive
import kotlin.collections.set


class CacheViewModel(application: Application) : BaseViewModel(application) {
    val upAdapterLiveData = MutableLiveData<String>()

    private var loadChapterCoroutine: Coroutine<Unit>? = null
    val cacheChapters = hashMapOf<String, HashSet<String>>()

    /** 有评论页快照的章节（bookUrl -> 章 url 集合），用于缓存页“正文 x/y · 评论 a/b” */
    val reviewChapters = hashMapOf<String, HashSet<String>>()

    fun loadCacheFiles(books: List<Book>) {
        loadChapterCoroutine?.cancel()
        loadChapterCoroutine = execute {
            books.forEach { book ->
                if (!book.isLocal && !cacheChapters.contains(book.bookUrl)) {
                    val chapterCaches = hashSetOf<String>()
                    val cacheNames = BookHelp.getChapterFiles(book)
                    if (cacheNames.isNotEmpty()) {
                        appDb.bookChapterDao.getChapterList(book.bookUrl).also {
                            book.totalChapterNum = it.size
                        }.forEach { chapter ->
                            if (
                                chapter.isVolume ||
                                BookHelp.getChapterCacheFileNames(book, chapter)
                                    .any(cacheNames::contains)
                            ) {
                                chapterCaches.add(chapter.url)
                            }
                        }
                    }
                    cacheChapters[book.bookUrl] = chapterCaches
                    // 评论快照章数：统计该书快照文件（按章去重），后续由事件增量更新
                    reviewChapters[book.bookUrl] = io.legado.app.help.review.ReviewSnapshotStore
                        .chapterUrls(book)
                        .toHashSet()
                    upAdapterLiveData.sendValue(book.bookUrl)
                }
                ensureActive()
            }
        }
    }

}
