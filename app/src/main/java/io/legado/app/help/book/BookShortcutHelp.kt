package io.legado.app.help.book

import io.legado.app.constant.BookType
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookGroup
import io.legado.app.data.entities.BookShortcut
import io.legado.app.data.entities.BookShortcutWithBook
import io.legado.app.data.entities.ShelfEntry
import io.legado.app.model.SourceCallBack
import io.legado.app.model.localBook.LocalBook
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

val Book.isShortcut: Boolean
    get() = shortcutId > 0L

val Book.shelfKey: String
    get() = if (isShortcut) "shortcut:$shortcutId" else bookUrl

object BookShortcutHelp {

    fun flowEntriesByGroup(groupId: Long): Flow<List<ShelfEntry>> = combine(
        appDb.bookDao.flowByGroup(groupId),
        appDb.bookDao.flowAll(),
        appDb.bookShortcutDao.flowAll()
    ) { visibleBodies, allBooks, shortcuts ->
        val booksByUrl = allBooks.filterNot { it.isNotShelf }.associateBy { it.bookUrl }
        visibleBodies.map(ShelfEntry::Body) + shortcuts.mapNotNull { item ->
            val book = booksByUrl[item.shortcut.bookUrl] ?: return@mapNotNull null
            if (matchesGroup(groupId, item.shortcut, book)) ShelfEntry.Shortcut(item.shortcut, book) else null
        }
    }

    fun create(entries: List<ShelfEntry>, groupId: Long) {
        create(entries.filterIsInstance<ShelfEntry.Body>().map { it.book }, groupId)
    }

    fun flowByGroup(groupId: Long): Flow<List<Book>> = combine(
        appDb.bookDao.flowByGroup(groupId),
        appDb.bookShortcutDao.flowAll()
    ) { bodies, shortcuts ->
        bodies + shortcuts.mapNotNull { item ->
            item.book.takeUnless { it.isNotShelf }
                ?.takeIf { matchesGroup(groupId, item.shortcut, it) }
                ?.copy(
                    group = item.shortcut.group,
                    order = item.shortcut.order,
                    shortcutId = item.shortcut.shortcutId
                )
        }
    }

    fun withShortcuts(books: List<Book>, shortcuts: List<BookShortcutWithBook>): List<Book> =
        books + shortcuts.mapNotNull { item ->
            item.book.takeUnless { it.isNotShelf }?.copy(
                group = item.shortcut.group,
                order = item.shortcut.order,
                shortcutId = item.shortcut.shortcutId
            )
        }

    fun withShortcuts(books: List<Book>, shortcuts: List<BookShortcut>): List<Book> =
        books + shortcuts.mapNotNull { shortcut ->
            appDb.bookDao.getBook(shortcut.bookUrl)?.takeUnless { it.isNotShelf }?.copy(
                group = shortcut.group,
                order = shortcut.order,
                shortcutId = shortcut.shortcutId
            )
        }

    fun create(books: List<Book>, groupId: Long) {
        val urls = books.filterNot { it.isShortcut }.map { it.bookUrl }.distinct()
        if (urls.isEmpty()) return
        val group = groupId.takeIf { it > 0L } ?: 0L
        val order = appDb.bookShortcutDao.maxOrder(group) + 1
        appDb.bookShortcutDao.insert(
            urls.mapIndexed { index, url ->
                BookShortcut(bookUrl = url, group = group, order = order + index)
            }
        )
    }

    fun update(book: Book) {
        if (!book.isShortcut) {
            appDb.bookDao.update(book)
            return
        }
        appDb.bookShortcutDao.get(book.shortcutId)?.let { shortcut ->
            appDb.bookShortcutDao.update(
                shortcut.copy(
                    bookUrl = book.bookUrl,
                    group = book.group,
                    order = book.order
                )
            )
        }
    }

    fun update(vararg books: Book) {
        books.forEach(::update)
    }

    fun delete(books: List<Book>, deleteBody: Boolean = false, deleteOriginal: Boolean = false) {
        val urls = books.mapTo(linkedSetOf()) { it.bookUrl }
        val deleteUrls = buildSet {
            books.filterNot { it.isShortcut }.forEach { add(it.bookUrl) }
            if (deleteBody || deleteOriginal) addAll(urls)
        }
        books.filter { it.isShortcut && it.bookUrl !in deleteUrls }
            .map { it.shortcutId }
            .takeIf { it.isNotEmpty() }
            ?.let(appDb.bookShortcutDao::delete)
        deleteUrls.mapNotNull(appDb.bookDao::getBook).forEach { book ->
            appDb.bookShortcutDao.deleteByBookUrl(book.bookUrl)
            if (book.isLocal) LocalBook.clearBookShelfCache(book)
            appDb.bookDao.delete(book)
            if (book.isLocal) {
                LocalBook.deleteBook(book, deleteOriginal)
            } else {
                SourceCallBack.callBackBook(
                    SourceCallBack.DEL_BOOK_SHELF,
                    appDb.bookSourceDao.getBookSource(book.origin),
                    book
                )
            }
        }
    }

    private fun matchesGroup(groupId: Long, shortcut: BookShortcut, book: Book): Boolean = when {
        groupId == BookGroup.IdAll || groupId == BookGroup.IdPrimaryAll -> true
        groupId == BookGroup.IdRoot ->
            shortcut.group == 0L && book.type and BookType.text > 0 && book.type and BookType.local == 0
        groupId == BookGroup.IdUngrouped -> shortcut.group == 0L && book.type and BookType.local == 0
        groupId == BookGroup.IdNovel -> book.type and BookType.text > 0
        groupId == BookGroup.IdLocal -> book.type and BookType.local > 0
        groupId == BookGroup.IdAudio -> book.type and BookType.audio > 0
        groupId == BookGroup.IdImage -> book.type and BookType.image > 0
        groupId == BookGroup.IdVideo -> book.type and BookType.video > 0
        groupId == BookGroup.IdError -> book.type and BookType.updateError > 0
        groupId > 0L -> shortcut.group and groupId > 0L
        else -> false
    }
}
