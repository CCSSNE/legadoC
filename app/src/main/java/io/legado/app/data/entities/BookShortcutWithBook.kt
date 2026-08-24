package io.legado.app.data.entities

import androidx.room.Embedded
import androidx.room.Relation

data class BookShortcutWithBook(
    @Embedded val shortcut: BookShortcut,
    @Relation(parentColumn = "bookUrl", entityColumn = "bookUrl")
    val book: Book
)
