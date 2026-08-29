package io.legado.app.data.entities

import androidx.room.Entity

/**
 * 音色绑定：目标 → 发音人（发音人目录条目 id，如百度TTS语音包的发音人 id）。
 * 绑定一次长期生效；bindingMode=manual 时自动选音不得覆盖。
 * narrator / dialogue_male / dialogue_female 的 targetId 固定为 0。
 */
@Entity(tableName = "book_tts_voice_bindings", primaryKeys = ["workKey", "targetType", "targetId"])
data class BookTtsVoiceBinding(
    val workKey: String = "",
    val targetType: String = TargetType.CHARACTER,
    val targetId: Long = 0L,
    val speakerId: String = "",
    val bindingMode: String = BindingMode.AUTO,
    val updatedAt: Long = 0L
) {
    object TargetType {
        const val CHARACTER = "character"
        const val CAST_ROLE = "cast_role"
        const val NARRATOR = "narrator"
        const val DIALOGUE_MALE = "dialogue_male"
        const val DIALOGUE_FEMALE = "dialogue_female"
    }

    object BindingMode {
        const val AUTO = "auto"
        const val MANUAL = "manual"
    }
}
