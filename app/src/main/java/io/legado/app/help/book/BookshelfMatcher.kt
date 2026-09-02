package io.legado.app.help.book

import io.legado.app.data.appDb
import io.legado.app.domain.model.BookShelfState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * 书架状态匹配器：内存维护书架 (name, author) 与 bookUrl 集合，供聚合主页等页面 O(1) 判断书籍是否在书架。
 *
 * 在 App.onCreate 调用 [start] 后，books 表任何变化都会重建集合并递增 [version]；
 * 消费方订阅 [refreshSignal]（View 体系）或观察 [version]（Flow 体系）即可刷新展示状态。
 */
object BookshelfMatcher {

    private val nameAuthorKeys: MutableSet<Pair<String, String>> =
        ConcurrentHashMap.newKeySet()
    private val nameOnlyKeys: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val bookUrlKeys: MutableSet<String> = ConcurrentHashMap.newKeySet()

    private val _refreshSignal = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val refreshSignal: SharedFlow<Unit> = _refreshSignal

    private val _version = MutableStateFlow(0L)
    val version: StateFlow<Long> = _version

    fun start() {
        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
            appDb.bookDao.flowShelfKeys().collect { keys ->
                synchronized(this@BookshelfMatcher) {
                    nameAuthorKeys.clear()
                    nameOnlyKeys.clear()
                    bookUrlKeys.clear()
                    for (key in keys) {
                        bookUrlKeys.add(key.bookUrl)
                        val author = key.author.orEmpty().trim()
                        if (author.isBlank()) {
                            nameOnlyKeys.add(key.name)
                        } else {
                            nameAuthorKeys.add(key.name to author)
                        }
                    }
                }
                _version.update { it + 1 }
                _refreshSignal.tryEmit(Unit)
            }
        }
    }

    fun getState(name: String, author: String?, bookUrl: String): BookShelfState {
        synchronized(this) {
            if (bookUrl in bookUrlKeys) return BookShelfState.IN_SHELF
            val trimmedAuthor = author.orEmpty().trim()
            if ((name to trimmedAuthor) in nameAuthorKeys) return BookShelfState.SAME_NAME_AUTHOR
            if (trimmedAuthor.isBlank() && name in nameOnlyKeys) return BookShelfState.SAME_NAME_AUTHOR
        }
        return BookShelfState.NOT_IN_SHELF
    }

    fun isInShelf(name: String, author: String?, bookUrl: String): Boolean =
        getState(name, author, bookUrl) != BookShelfState.NOT_IN_SHELF
}
