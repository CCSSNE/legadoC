package io.legado.app.data.entities

import android.os.Parcelable
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.parcelize.Parcelize

@Parcelize
@Entity(tableName = "book_collections")
data class BookCollection(
    @PrimaryKey(autoGenerate = true)
    val collectionId: Long = 0,
    @ColumnInfo(defaultValue = "")
    var name: String = "",
    @ColumnInfo(defaultValue = "0")
    var order: Int = 0,
    @ColumnInfo(defaultValue = "0")
    val createdTime: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "0")
    var updatedTime: Long = System.currentTimeMillis()
) : Parcelable
