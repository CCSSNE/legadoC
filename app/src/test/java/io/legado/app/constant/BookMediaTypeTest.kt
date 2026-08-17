package io.legado.app.constant

import org.junit.Assert.assertEquals
import org.junit.Test

class BookMediaTypeTest {

    @Test
    fun mutableBookFlagsDoNotChangeMediaType() {
        val bookType = BookType.audio or BookType.updateError or BookType.notShelf

        assertEquals(BookMediaType.audio, BookMediaType.fromBookType(bookType))
    }

    @Test
    fun primaryContentFlagsMapToDistinctMediaTypes() {
        assertEquals(BookMediaType.text, BookMediaType.fromBookType(BookType.text))
        assertEquals(BookMediaType.audio, BookMediaType.fromBookType(BookType.audio))
        assertEquals(BookMediaType.image, BookMediaType.fromBookType(BookType.image))
        assertEquals(BookMediaType.video, BookMediaType.fromBookType(BookType.video))
        assertEquals(BookMediaType.webFile, BookMediaType.fromBookType(BookType.webFile))
    }
}
