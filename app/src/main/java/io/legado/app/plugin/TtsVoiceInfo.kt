package io.legado.app.plugin

/**
 * 发音人（音色）目录条目，与具体引擎解耦，供 AI 选角与播放路由使用。
 * 性别约定与 AI 选音 payload（casting.md）一致：male / female / unknown。
 */
data class TtsVoiceInfo(
    val id: String,
    val name: String,
    val desc: String? = null,
    val gender: String,
    val locale: String? = null,
)