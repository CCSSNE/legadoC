package io.legado.app.plugin

import android.app.Activity
import io.legado.app.service.BaseReadAloudService
import io.legado.app.service.ReadAloudEngineType

/**
 * 朗读引擎插件：由内置插件（如百度TTS）在启动时经 [ReadAloudEngines] 注册。
 * 开源构建（oss flavor）不注册任何插件，主代码在空注册表下正常运行：
 * 引擎选择界面不渲染对应行，路由到未注册引擎 id 时明示后回退系统 TTS。
 */
interface ReadAloudEnginePlugin {

    /** 引擎唯一标识，持久化于 ttsEngine 配置（httpTTS 为数字 id，内置引擎为字符串 id）。 */
    val engineId: String

    /** 引擎选择界面（SpeakEngineDialog）显示的引擎名。 */
    val engineLabel: String

    val serviceClass: Class<out BaseReadAloudService>

    val engineType: ReadAloudEngineType

    /** 引擎管理页（长按引擎行进入）；null 表示无管理页。 */
    val manageActivityClass: Class<out Activity>?

    /**
     * 引擎运行时依赖就绪自检：null = 可路由到本引擎；非空 = 不可用原因说明。
     * 主路由据此明示原因并回退系统 TTS（如百度引擎未导入语音包），不静默失效。
     */
    val unavailableReason: String? get() = null
}