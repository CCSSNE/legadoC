package io.legado.app.plugin

/**
 * 插件引擎的批量 TTS 缓存合成能力：引擎能脱离朗读服务独立合成单段音频时提供。
 * 主代码（TtsCacheManager）按引擎种类调用；插件未提供时对应引擎没有批量缓存能力，
 * 由缓存执行端按引擎明示原因，不静默回退其他引擎合成。
 */
interface TtsCacheSynthesizer {

    /** 当前生效音色 key（参与缓存 key 的音色维度）；null 表示引擎未就绪。 */
    fun activeVoiceKey(): String?

    /**
     * 合成一段文本，返回完整音频字节（含格式头，播放端按内容嗅探，不依赖后缀）。
     * 实现方必须自行串行化合成（离线引擎通常不允许并发），并自带超时看门狗；
     * 失败抛出异常，由缓存执行端按单元失败记录，不静默吞掉。
     */
    suspend fun synthesize(text: String, speed: Float): ByteArray
}
