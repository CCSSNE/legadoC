package io.legado.app.plugin.doubao

import io.legado.app.plugin.ReadAloudEnginePlugin
import io.legado.app.service.ReadAloudEngineType

/**
 * 豆包语音原生引擎注册入口：一个引擎（engineId = doubao），内置 374 个音色，
 * 合成走 [DoubaoTtsClient]（WebSocket 直连），不经过任何插件 JS。
 */
object DoubaoEnginePlugin : ReadAloudEnginePlugin {

    override val engineId: String = DoubaoVoices.ENGINE_ID

    override val engineLabel: String = "豆包语音（内置）"

    override val serviceClass: Class<out io.legado.app.service.BaseReadAloudService> =
        DoubaoReadAloudService::class.java

    override val engineType: ReadAloudEngineType = ReadAloudEngineType.SCRIPT_TTS

    override val manageActivityClass: Class<android.app.Activity>? = null

    override val supportsMultiRoleCasting: Boolean = true

    override val cacheSynthesizer: io.legado.app.plugin.TtsCacheSynthesizer =
        DoubaoTtsCacheSynthesizer

    override val unavailableReason: String?
        get() = if (DoubaoVoices.all().isEmpty()) "内置音色表缺失" else null
}
