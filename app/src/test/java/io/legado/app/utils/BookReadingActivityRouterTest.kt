package io.legado.app.utils

import io.legado.app.constant.BookType
import io.legado.app.data.entities.Book
import org.junit.Assert.assertEquals
import org.junit.Test

class BookReadingActivityRouterTest {

    @Test
    fun `audio books use the standard reader`() {
        val book = Book(origin = "https://example.test", type = BookType.audio)

        assertEquals(
            BookReadingDestination.READER,
            book.defaultReadingDestination(showMangaUi = true)
        )
    }

    @Test
    fun `video books keep the video player`() {
        val book = Book(origin = "https://example.test", type = BookType.video)

        assertEquals(
            BookReadingDestination.VIDEO,
            book.defaultReadingDestination(showMangaUi = true)
        )
    }

    @Test
    fun `remote image books use manga UI when enabled`() {
        val book = Book(origin = "https://example.test", type = BookType.image)

        assertEquals(
            BookReadingDestination.MANGA,
            book.defaultReadingDestination(showMangaUi = true)
        )
    }

    @Test
    fun `local image books stay in the standard reader`() {
        val book = Book(type = BookType.image or BookType.local)

        assertEquals(
            BookReadingDestination.READER,
            book.defaultReadingDestination(showMangaUi = true)
        )
    }
}
