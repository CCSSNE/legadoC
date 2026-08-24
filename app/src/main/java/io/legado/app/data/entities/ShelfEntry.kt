package io.legado.app.data.entities

sealed interface ShelfEntry {
    val shelfKey: String
    val book: Book
    val bookUrl: String get() = book.bookUrl
    val name: String get() = book.name
    val author: String get() = book.author
    val type: Int get() = book.type
    val group: Long
        get() = when (this) {
            is Body -> book.group
            is Shortcut -> shortcut.group
        }
    val order: Int
        get() = when (this) {
            is Body -> book.order
            is Shortcut -> shortcut.order
        }
    val latestChapterTime: Long get() = book.latestChapterTime
    val durChapterTime: Long get() = book.durChapterTime
    val shortcutId: Long get() = (this as? Shortcut)?.shortcut?.shortcutId ?: 0L
    data class Body(override val book: Book) : ShelfEntry {
        override val shelfKey = "book:${book.bookUrl}"
    }
    data class Shortcut(val shortcut: BookShortcut, override val book: Book) : ShelfEntry {
        override val shelfKey = "shortcut:${shortcut.shortcutId}"
    }
}
