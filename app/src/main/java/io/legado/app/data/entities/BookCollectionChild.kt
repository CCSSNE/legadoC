package io.legado.app.data.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "book_collection_children",
    primaryKeys = ["parentCollectionId", "childCollectionId"],
    foreignKeys = [
        ForeignKey(
            entity = BookCollection::class,
            parentColumns = ["collectionId"],
            childColumns = ["parentCollectionId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = BookCollection::class,
            parentColumns = ["collectionId"],
            childColumns = ["childCollectionId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("parentCollectionId"),
        Index("childCollectionId")
    ]
)
data class BookCollectionChild(
    val parentCollectionId: Long,
    val childCollectionId: Long,
    @ColumnInfo(defaultValue = "0")
    val order: Int = 0,
    @ColumnInfo(defaultValue = "0")
    val addedTime: Long = System.currentTimeMillis()
)
