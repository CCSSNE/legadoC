package io.legado.app.help.bdtts

/**
 * bdetts 发音人记录（来自语音包 config.yaml 的 Speaker 条目）。
 */
data class BdSpeakerRecord(
    var id: String = "",
    var group: String = "bdetts",
    var code: String = "",
    var name: String = "",
    var desc: String? = null,
    var avatar: String? = null,
    var gender: Int = 0,
    var type: Int = 0,
    var param: String = "",
    var sampleRate: Int = 16000,
    var speed: Float = 1.0f,
    var volume: Float = 1.0f,
    var pitch: Float = 1.0f,
    var locale: String = "zh-CN",
)
