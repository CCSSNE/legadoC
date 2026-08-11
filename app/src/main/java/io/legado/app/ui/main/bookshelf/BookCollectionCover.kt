package io.legado.app.ui.main.bookshelf

import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import io.legado.app.data.entities.Book
import io.legado.app.ui.widget.image.CoverImageView

fun List<CoverImageView>.loadCollectionCovers(
    books: List<Book>,
    fragment: Fragment? = null,
    lifecycle: Lifecycle? = null
) {
    forEachIndexed { index, imageView ->
        val book = books.getOrNull(index)
        if (book == null) {
            imageView.clearCoverToDefault()
        } else {
            imageView.loadThumb(book, false, fragment, lifecycle)
        }
    }
}
