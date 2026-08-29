package io.legado.app.data.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 正式角色（角色卡）：人工维护或由临时角色转正而来。
 * workKey = 书名+作者 的稳定键。
 */
@Entity(
    tableName = "book_roles",
    indices = [Index("workKey")]
)
data class BookRole(
    @PrimaryKey(autoGenerate = true)
    val roleId: Long = 0L,
    val workKey: String = "",
    val name: String = "",
    val aliasesJson: String = "[]",
    val gender: String = Gender.UNKNOWN,
    val enabled: Boolean = true,
    val updatedAt: Long = 0L
) {
    object Gender {
        const val MALE = "male"
        const val FEMALE = "female"
        const val UNKNOWN = "unknown"
    }
}
