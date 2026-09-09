package io.legado.app.ui.book.cache

import android.app.Application
import androidx.lifecycle.MutableLiveData
import io.legado.app.base.BaseViewModel
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.help.book.AudioOfflineState
import io.legado.app.help.book.CacheManifestHelper
import io.legado.app.help.book.isAudio
import io.legado.app.help.book.isLocal
import io.legado.app.help.book.isVideo
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.exoplayer.ExoPlayerHelper
import io.legado.app.utils.sendValue
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
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
                    loadBookCacheFiles(book)
                }
                ensureActive()
            }
            // 先完成所有书的正文统计，单本书首次建立评论索引不阻塞后面的书。
            books.forEach { book ->
                ensureActive()
                if (!book.isLocal && !reviewChapters.contains(book.bookUrl)) {
                    loadReviewCacheFiles(book)
                }
            }
        }
    }

    fun refreshCacheFiles(book: Book) {
        if (book.isLocal) return
        execute {
            loadBookCacheFiles(book)
            loadReviewCacheFiles(book)
        }
    }

    private suspend fun loadBookCacheFiles(book: Book) {
        val taskContext = currentCoroutineContext()
        val chapterCaches = hashSetOf<String>()
        val chapters = appDb.bookChapterDao.getChapterList(book.bookUrl).also {
            book.totalChapterNum = it.size
        }
        val bodyUrls = if (!book.isAudio && !book.isVideo) {
            CacheManifestHelper.cachedChapterUrls(book, chapters)
        } else emptySet()
        chapters.forEach { chapter ->
            taskContext.ensureActive()
            val cached = when {
                chapter.isVolume -> true
                book.isAudio -> AudioOfflineState.isComplete(book, chapter)
                book.isVideo -> ExoPlayerHelper.isVideoCached(chapter.resourceUrl, book)
                else -> chapter.url in bodyUrls
            }
            if (cached) {
                chapterCaches.add(chapter.url)
            }
        }
        cacheChapters[book.bookUrl] = chapterCaches
        upAdapterLiveData.sendValue(book.bookUrl)
    }

    private suspend fun loadReviewCacheFiles(book: Book) {
        val taskContext = currentCoroutineContext()
        // 正文先显示；评论只读轻量目录索引，后续由事件增量更新。
        reviewChapters[book.bookUrl] = io.legado.app.help.review.ReviewSnapshotStore
            .chapterUrls(book) { taskContext.ensureActive() }
            .toHashSet()
        upAdapterLiveData.sendValue(book.bookUrl)
    }

}
