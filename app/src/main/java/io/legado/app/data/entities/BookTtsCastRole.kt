package io.legado.app.data.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 临时角色（演播池）：AI 分镜发现的陌生人，程序收编后落库。
 * identityState 状态机：stable（可见可配音）/ pending（隐藏观察）/ guest（路人，不入正式视图）。
 */
@Entity(
    tableName = "book_tts_cast_roles",
    indices = [Index("workKey")]
)
data class BookTtsCastRole(
    @PrimaryKey(autoGenerate = true)
    val castRoleId: Long = 0L,
    val workKey: String = "",
    val name: String = "",
    val aliasesJson: String = "[]",
    val gender: String = BookRole.Gender.UNKNOWN,
    val identityState: String = IdentityState.STABLE,
    val occurrenceCount: Int = 0,
    val firstChapterIndex: Int = -1,
    val lastChapterIndex: Int = -1,
    val samplesJson: String = "[]",
    val evidence: String = "",
    val ignored: Boolean = false,
    val linkedRoleId: Long = 0L,
    val updatedAt: Long = 0L
) {
    object IdentityState {
        const val STABLE = "stable"
        const val PENDING = "pending"
        const val GUEST = "guest"
    }

    object NameType {
        const val PROPER_NAME = "proper_name"
        const val ALIAS = "alias"
        const val UNIQUE_TITLE = "unique_title"
        const val GENERIC_LABEL = "generic_label"
        const val UNKNOWN = "unknown"
    }

    object Evidence {
        const val EXPLICIT = "explicit"
        const val CONTEXTUAL = "contextual"
        const val INFERRED = "inferred"
        const val UNKNOWN = "unknown"
    }
}
