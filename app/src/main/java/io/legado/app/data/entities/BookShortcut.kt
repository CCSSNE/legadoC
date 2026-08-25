package io.legado.app.data.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "book_shortcuts",
    foreignKeys = [
        ForeignKey(
            entity = Book::class,
            parentColumns = ["bookUrl"],
            childColumns = ["bookUrl"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = BookCollection::class,
            parentColumns = ["collectionId"],
            childColumns = ["collectionId"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [Index("bookUrl"), Index("group"), Index("collectionId")]
)
data class BookShortcut(
    @PrimaryKey(autoGenerate = true)
    val shortcutId: Long = 0L,
    val bookUrl: String,
    @ColumnInfo(defaultValue = "0")
    var group: Long = 0L,
    @ColumnInfo(defaultValue = "0")
    var order: Int = 0,
    var collectionId: Long? = null,
    @ColumnInfo(defaultValue = "0")
    val createdTime: Long = System.currentTimeMillis()
)
