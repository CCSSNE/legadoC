package io.legado.app.ui.main.bookshelf

import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookCollection

data class BookCollectionShelfItem(
    val collection: BookCollection,
    val books: List<Book>
) {
    val id: Long get() = collection.collectionId
    val name: String get() = collection.name
    val count: Int get() = books.size
    val previewBooks: List<Book> get() = books.take(4)
}
