package io.legado.app.help.book

import io.legado.app.constant.BookType
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookGroup
import io.legado.app.data.entities.BookShortcut
import io.legado.app.data.entities.BookShortcutWithBook
import io.legado.app.model.SourceCallBack
import io.legado.app.model.localBook.LocalBook
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

/**
 * 快捷方式只是书架上的虚拟 Book：shortcutId 只用于区分入口，bookUrl 始终指向本体。
 */
val Book.isShortcut: Boolean
    get() = shortcutId > 0L

val Book.shelfKey: String
    get() = if (isShortcut) "shortcut:$shortcutId" else "book:$bookUrl"

object BookShortcutHelp {

    fun flowByGroup(groupId: Long): Flow<List<Book>> = combine(
        appDb.bookDao.flowByGroup(groupId),
        appDb.bookDao.flowAll(),
        appDb.bookShortcutDao.flowAll()
    ) { visibleBooks, allBooks, shortcuts ->
        val booksByUrl = allBooks
            .asSequence()
            .filterNot { it.isNotShelf }
            .associateBy { it.bookUrl }
        visibleBooks + shortcuts.mapNotNull { item ->
            if (item.shortcut.collectionId != null) return@mapNotNull null
            val body = booksByUrl[item.shortcut.bookUrl] ?: return@mapNotNull null
            if (!matchesGroup(groupId, item.shortcut, body)) return@mapNotNull null
            item.toShelfBook(body)
        }
    }

    fun flowByCollection(collectionId: Long): Flow<List<Book>> =
        appDb.bookShortcutDao.flowByCollection(collectionId).map { shortcuts ->
            shortcuts.mapNotNull { item ->
                item.book.takeUnless { it.isNotShelf }?.let(item::toShelfBook)
            }
        }

    fun flowCollectionBooks(): Flow<Map<Long, List<Book>>> =
        appDb.bookShortcutDao.flowAll().map { shortcuts ->
            shortcuts.asSequence()
                .filter { it.shortcut.collectionId != null && !it.book.isNotShelf }
                .groupBy(
                    keySelector = { it.shortcut.collectionId!! },
                    valueTransform = { it.toShelfBook(it.book) }
                )
        }

    fun create(books: List<Book>, groupId: Long) {
        val urls = books.map { it.bookUrl }.distinct()
        if (urls.isEmpty()) return
        val targetGroup = groupId.takeIf { it > 0L } ?: 0L
        val startOrder = appDb.bookShortcutDao.maxOrder(targetGroup) + 1
        appDb.bookShortcutDao.insert(
            urls.mapIndexed { index, bookUrl ->
                BookShortcut(
                    bookUrl = bookUrl,
                    group = targetGroup,
                    order = startOrder + index
                )
            }
        )
    }

    fun update(book: Book) {
        if (!book.isShortcut) {
            appDb.bookDao.update(book)
            return
        }
        val shortcut = appDb.bookShortcutDao.get(book.shortcutId) ?: return
        appDb.bookShortcutDao.update(
            shortcut.copy(group = book.group, order = book.order)
        )
    }

    fun update(vararg books: Book) {
        books.forEach(::update)
    }

    fun moveToCollection(collectionId: Long, shortcutIds: Collection<Long>) {
        appDb.bookShortcutDao.moveToCollection(collectionId, shortcutIds)
    }

    fun moveToRoot(shortcutIds: Collection<Long>) {
        appDb.bookShortcutDao.moveToRoot(shortcutIds)
    }

    fun delete(
        books: List<Book>,
        deleteBody: Boolean = false,
        deleteOriginal: Boolean = false
    ) {
        val removeBody = deleteBody || deleteOriginal
        val bodyUrlsToDelete = buildSet {
            books.filterNot { it.isShortcut }.forEach { add(it.bookUrl) }
            if (removeBody) books.forEach { add(it.bookUrl) }
        }

        books.asSequence()
            .filter { it.isShortcut && it.bookUrl !in bodyUrlsToDelete }
            .map { it.shortcutId }
            .filter { it > 0L }
            .toList()
            .takeIf { it.isNotEmpty() }
            ?.let(appDb.bookShortcutDao::delete)

        bodyUrlsToDelete.forEach { bookUrl ->
            val body = appDb.bookDao.getBook(bookUrl) ?: return@forEach
            // 明确删除映射；外键 CASCADE 同时作为数据库层保险。
            appDb.bookShortcutDao.deleteByBookUrl(bookUrl)
            if (body.isLocal) {
                LocalBook.clearBookShelfCache(body)
            }
            appDb.bookDao.delete(body)
            if (body.isLocal) {
                LocalBook.deleteBook(body, deleteOriginal)
            } else {
                val source = appDb.bookSourceDao.getBookSource(body.origin)
                SourceCallBack.callBackBook(SourceCallBack.DEL_BOOK_SHELF, source, body)
            }
        }
    }

    private fun BookShortcutWithBook.toShelfBook(body: Book): Book {
        return body.copy(
            group = shortcut.group,
            order = shortcut.order,
            shortcutId = shortcut.shortcutId
        )
    }

    private fun matchesGroup(groupId: Long, shortcut: BookShortcut, body: Book): Boolean {
        return when {
            groupId == BookGroup.IdAll || groupId == BookGroup.IdPrimaryAll -> true
            groupId == BookGroup.IdRoot ->
                shortcut.group == 0L &&
                    body.type and BookType.text > 0 &&
                    body.type and BookType.local == 0
            groupId == BookGroup.IdUngrouped -> shortcut.group == 0L
            groupId == BookGroup.IdNovel -> body.type and BookType.text > 0
            groupId == BookGroup.IdLocal -> body.type and BookType.local > 0
            groupId == BookGroup.IdAudio -> body.type and BookType.audio > 0
            groupId == BookGroup.IdImage -> body.type and BookType.image > 0
            groupId == BookGroup.IdVideo -> body.type and BookType.video > 0
            groupId == BookGroup.IdError -> body.type and BookType.updateError > 0
            groupId > 0L -> shortcut.group and groupId > 0L
            else -> false
        }
    }
}
