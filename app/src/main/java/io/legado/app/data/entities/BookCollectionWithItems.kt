package io.legado.app.data.entities

import androidx.room.Embedded
import androidx.room.Junction
import androidx.room.Relation

data class BookCollectionWithItems(
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
    val books: List<Book>,
    @Relation(
        parentColumn = "collectionId",
        entityColumn = "shortcutId",
        associateBy = Junction(
            value = BookCollectionItem::class,
            parentColumn = "collectionId",
            entityColumn = "shortcutId"
        )
    )
    val shortcuts: List<BookShortcut>,
    @Relation(
        parentColumn = "collectionId",
        entityColumn = "collectionId",
        associateBy = Junction(
            value = BookCollectionChild::class,
            parentColumn = "parentCollectionId",
            entityColumn = "childCollectionId"
        )
    )
    val childCollections: List<BookCollection>
)
