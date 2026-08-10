package io.legado.app.data.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "readRecentBooks")
data class ReadRecentBook(
    @PrimaryKey
    val bookUrl: String,
    val lastRead: Long = System.currentTimeMillis()
)
