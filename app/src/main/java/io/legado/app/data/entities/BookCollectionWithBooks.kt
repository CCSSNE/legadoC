package io.legado.app.data.entities

import androidx.room.Embedded
import androidx.room.Junction
import androidx.room.Relation

data class BookCollectionWithBooks(
    @Embedded val collection: BookCollection,
    @Relation(
        parentColumn = "collectionId",
        entityColumn = "bookUrl",
        associateBy = Junction(
            value = BookCollectionItem::class,
            parentColumn = "collectionId",
            entityColumn = "bookUrl"
        )
    )
    val books: List<Book>
)
