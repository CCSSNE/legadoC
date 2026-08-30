package io.legado.app.data.entities

import androidx.room.Entity

/**
 * 角色绑定：目标 → （引擎, 音色）。
 * engineId 空串 = 内置语音包引擎（百度等插件注册的发音人目录）；
 * speakerId 为该引擎内的音色/发音人 id（内置目录即发音人 id）。
 * 首次生成时生效；bindingMode=manual 时自动选音不会覆盖该绑定。
 * narrator / dialogue_male / dialogue_female 的 targetId 固定为 0。
 */
@Entity(tableName = "book_tts_voice_bindings", primaryKeys = ["workKey", "targetType", "targetId"])
data class BookTtsVoiceBinding(
    val workKey: String = "",
    val targetType: String = TargetType.CHARACTER,
    val targetId: Long = 0L,
    val engineId: String = "",
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
