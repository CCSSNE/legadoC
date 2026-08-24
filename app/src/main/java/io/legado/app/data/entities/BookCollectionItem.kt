package io.legado.app.data.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "book_collection_items",
    primaryKeys = ["collectionId", "entryType", "entryId"],
    foreignKeys = [
        ForeignKey(BookCollection::class, ["collectionId"], ["collectionId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(Book::class, ["bookUrl"], ["bookUrl"], onDelete = ForeignKey.CASCADE),
        ForeignKey(BookShortcut::class, ["shortcutId"], ["shortcutId"], onDelete = ForeignKey.CASCADE)
    ],
    indices = [Index("collectionId"), Index("bookUrl"), Index("shortcutId")]
)
data class BookCollectionItem(
    val collectionId: Long,
    @ColumnInfo(defaultValue = "0") val entryType: Int,
    val entryId: String,
    val bookUrl: String? = null,
    val shortcutId: Long? = null,
    @ColumnInfo(defaultValue = "0") val order: Int = 0,
    @ColumnInfo(defaultValue = "0") val addedTime: Long = System.currentTimeMillis()
) {
    companion object {
        const val TYPE_BOOK = 0
        const val TYPE_SHORTCUT = 1

        fun book(collectionId: Long, bookUrl: String, order: Int, addedTime: Long) =
            BookCollectionItem(collectionId, TYPE_BOOK, bookUrl, bookUrl, null, order, addedTime)

        fun shortcut(collectionId: Long, shortcutId: Long, bookUrl: String, order: Int, addedTime: Long) =
            BookCollectionItem(collectionId, TYPE_SHORTCUT, shortcutId.toString(), bookUrl, shortcutId, order, addedTime)
    }
}
