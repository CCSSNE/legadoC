package io.legado.app.plugin

import android.content.Context

/**
 * 开源构建（oss flavor）插件引导：不注册任何专有插件。
 * 主代码以空注册表正常运行：百度TTS不在引擎列表出现，恢复的 bdtts 配置会明示回退，
 * AI选角在发音人目录缺失时自动降级，插件代码完全不参与编译与打包。
 */
object AppPlugins {
    fun init(context: Context) = Unit
}