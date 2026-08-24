package io.legado.app.data.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "book_collection_items",
    primaryKeys = ["collectionId", "bookUrl"],
    foreignKeys = [
        ForeignKey(
            entity = BookCollection::class,
            parentColumns = ["collectionId"],
            childColumns = ["collectionId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = Book::class,
            parentColumns = ["bookUrl"],
            childColumns = ["bookUrl"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("collectionId"),
        Index("bookUrl")
    ]
)
data class BookCollectionItem(
    val collectionId: Long,
    val bookUrl: String,
    @ColumnInfo(defaultValue = "0")
    val order: Int = 0,
    @ColumnInfo(defaultValue = "0")
    val addedTime: Long = System.currentTimeMillis()
)
