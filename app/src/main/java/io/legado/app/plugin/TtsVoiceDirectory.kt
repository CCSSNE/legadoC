package io.legado.app.plugin

/**
 * 演播选角使用的发音人目录；由 flavor 插件引导注册。
 * 开源构建无内置引擎时注册表为空（[TtsVoiceDirectories.active] 为 null），
 * AI 选音/路由按其缺失自动降级，主代码不触碰具体引擎的类型。
 */
interface TtsVoiceDirectory {

    fun listVoices(): List<TtsVoiceInfo>

    fun voiceById(id: String): TtsVoiceInfo?
}

/** 当前生效的发音人目录（单例由 flavor 注册）。 */
object TtsVoiceDirectories {

    @Volatile
    var active: TtsVoiceDirectory? = null
        private set

    fun register(directory: TtsVoiceDirectory) {
        active = directory
    }
}