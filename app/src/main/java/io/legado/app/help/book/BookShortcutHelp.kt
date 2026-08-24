package io.legado.app.help.book

import io.legado.app.data.appDb
import io.legado.app.constant.BookType
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookGroup
import io.legado.app.data.entities.BookShortcut
import io.legado.app.data.entities.BookShortcutWithBook
import io.legado.app.model.SourceCallBack
import io.legado.app.model.localBook.LocalBook
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

val Book.isShortcut: Boolean
    get() = shortcutId > 0L

val Book.shelfKey: String
    get() = if (isShortcut) "shortcut:$shortcutId" else bookUrl

object BookShortcutHelp {

    fun withShortcuts(
        books: List<Book>,
        shortcuts: List<BookShortcutWithBook>
    ): List<Book> {
        val bookUrls = books.mapTo(hashSetOf()) { it.bookUrl }
        return books + shortcuts.mapNotNull { shortcut ->
            shortcut.toShelfBook(bookUrls)
        }
    }

    fun flowByGroup(groupId: Long): Flow<List<Book>> = combine(
        appDb.bookDao.flowByGroup(groupId),
        appDb.bookDao.flowAll(),
        appDb.bookShortcutDao.flowAll()
    ) { visibleBooks, allBooks, shortcuts ->
        val allBooksByUrl = allBooks
            .asSequence()
            .filterNot { it.isNotShelf }
            .associateBy { it.bookUrl }
        val visibleBookUrls = visibleBooks.mapTo(hashSetOf()) { it.bookUrl }
        visibleBooks + shortcuts.mapNotNull { it.toShelfBook(groupId, visibleBookUrls, allBooksByUrl) }
    }

    fun create(books: List<Book>, groupId: Long) {
        val bookUrls = books.map { it.bookUrl }.distinct()
        if (bookUrls.isEmpty()) return
        val targetGroup = groupId.takeIf { it > 0L } ?: 0L
        val startOrder = appDb.bookShortcutDao.maxOrder(targetGroup) + 1
        appDb.bookShortcutDao.insert(
            bookUrls.mapIndexed { index, bookUrl ->
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
            shortcut.copy(
                bookUrl = book.bookUrl,
                group = book.group,
                order = book.order
            )
        )
    }

    fun update(vararg books: Book) {
        books.forEach(::update)
    }

    /** Removes selected shelf records and, when requested, their shared book bodies. */
    fun delete(
        books: List<Book>,
        deleteBody: Boolean = false,
        deleteOriginal: Boolean = false
    ) {
        val removeBody = deleteBody || deleteOriginal
        val bodyUrls = books.map { it.bookUrl }.distinct()
        val bodyBooks = bodyUrls.mapNotNull(appDb.bookDao::getBook)
        val bodyUrlsToDelete = buildSet {
            books.filterNot { it.isShortcut }.forEach { add(it.bookUrl) }
            if (removeBody) addAll(bodyUrls)
        }

        books.filter { it.isShortcut && it.bookUrl !in bodyUrlsToDelete }
            .map { it.shortcutId }
            .filter { it > 0L }
            .takeIf { it.isNotEmpty() }
            ?.let(appDb.bookShortcutDao::delete)

        bodyBooks.filter { it.bookUrl in bodyUrlsToDelete }.forEach { book ->
            appDb.bookShortcutDao.deleteByBookUrl(book.bookUrl)
            if (book.isLocal) {
                LocalBook.clearBookShelfCache(book)
            }
            appDb.bookDao.delete(book)
            if (book.isLocal) {
                LocalBook.deleteBook(book, deleteOriginal)
            } else {
                val source = appDb.bookSourceDao.getBookSource(book.origin)
                SourceCallBack.callBackBook(SourceCallBack.DEL_BOOK_SHELF, source, book)
            }
        }
    }

    private fun BookShortcutWithBook.toShelfBook(
        groupId: Long,
        visibleBookUrls: Set<String>,
        allBooksByUrl: Map<String, Book>
    ): Book? {
        val book = allBooksByUrl[shortcut.bookUrl] ?: return null
        if (book.isNotShelf || !matchesGroup(groupId, shortcut, book, visibleBookUrls)) {
            return null
        }
        return book.copy(
            group = shortcut.group,
            order = shortcut.order,
            shortcutId = shortcut.shortcutId
        )
    }

    private fun BookShortcutWithBook.toShelfBook(
        visibleBookUrls: Set<String>
    ): Book? {
        val body = this.book
        if (body.isNotShelf || body.bookUrl !in visibleBookUrls) return null
        return body.copy(
            group = shortcut.group,
            order = shortcut.order,
            shortcutId = shortcut.shortcutId
        )
    }

    private fun matchesGroup(
        groupId: Long,
        shortcut: BookShortcut,
        book: Book,
        visibleBookUrls: Set<String>
    ): Boolean {
        return when {
            groupId == BookGroup.IdAll || groupId == BookGroup.IdPrimaryAll -> true
            groupId == BookGroup.IdRoot || groupId == BookGroup.IdUngrouped ->
                shortcut.group == 0L && book.bookUrl in visibleBookUrls

            groupId == BookGroup.IdNovel -> book.type and BookType.text > 0
            groupId == BookGroup.IdLocal -> book.type and BookType.local > 0
            groupId == BookGroup.IdAudio -> book.type and BookType.audio > 0
            groupId == BookGroup.IdImage -> book.type and BookType.image > 0
            groupId == BookGroup.IdVideo -> book.type and BookType.video > 0
            groupId == BookGroup.IdError -> book.type and BookType.updateError > 0

            groupId > 0L -> shortcut.group and groupId > 0L
            else -> book.bookUrl in visibleBookUrls
        }
    }
}
