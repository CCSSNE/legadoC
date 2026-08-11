package io.legado.app.ui.main.bookshelf

import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookCollection

data class BookCollectionShelfItem(
    val collection: BookCollection,
    val books: List<Book>,
    val childCollections: List<BookCollection> = emptyList(),
    val previewBooks: List<Book> = books.take(4),
    val count: Int = books.size + childCollections.size
) {
    val id: Long get() = collection.collectionId
    val name: String get() = collection.name
}
