package io.legado.app.plugin

/**
 * 已注册朗读引擎插件注册表：启动时由各 flavor 的 [AppPlugins] 填充。
 * 开源构建保持空——[io.legado.app.model.ReadAloud] 路由查不到时明示回退系统 TTS，
 * 引擎选择界面不渲染对应行，完全不触碰插件代码。
 */
object ReadAloudEngines {

    private val byId = LinkedHashMap<String, ReadAloudEnginePlugin>()

    /** 按注册顺序返回全部插件，供引擎选择界面渲染。 */
    val all: List<ReadAloudEnginePlugin>
        get() = byId.values.toList()

    fun register(plugin: ReadAloudEnginePlugin) {
        byId[plugin.engineId] = plugin
    }

    fun byId(engineId: String?): ReadAloudEnginePlugin? =
        engineId?.let { byId[it] }

    fun byServiceClass(serviceClass: Class<*>): ReadAloudEnginePlugin? =
        byId.values.firstOrNull { it.serviceClass == serviceClass }
}