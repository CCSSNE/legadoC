package io.legado.app.plugin

/**
 * 内置 AI 出厂配置注册表：启动时由各 flavor 的 [AppPlugins] 填充。
 * 开源构建保持空——内置供应商种入/补齐的 apiKey 与默认 LLM 请求头全部为空串，
 * 行为与未引入本注册表前完全一致；出厂值明文只存在于自有构建源集。
 */
object AiBuiltinDefaults {

    interface Plugin {
        /** 内置图片供应商「硅基流动」出厂 apiKey */
        fun builtinSiliconFlowApiKey(): String

        /** 内置图片/视频供应商「智谱」出厂 apiKey（同一控制台的同一把 key） */
        fun builtinZhipuApiKey(): String

        /** 默认 LLM 供应商出厂请求头（逐行 "K: V"） */
        fun defaultLlmHeaders(): String
    }

    private var plugin: Plugin? = null

    fun register(plugin: Plugin) {
        this.plugin = plugin
    }

    fun siliconFlowApiKey(): String = plugin?.builtinSiliconFlowApiKey().orEmpty()

    fun zhipuApiKey(): String = plugin?.builtinZhipuApiKey().orEmpty()

    fun llmHeaders(): String = plugin?.defaultLlmHeaders().orEmpty()
}
