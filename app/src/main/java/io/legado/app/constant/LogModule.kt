package io.legado.app.constant

import androidx.annotation.StringRes
import io.legado.app.R

/**
 * 普通日志的模块归属。GENERAL 是兜底模块，始终在普通日志中显示；
 * 其余模块由用户在其他设置的“普通日志模块”弹窗中勾选是否显示。
 *
 * AI_CAST（AI分角色）约定：AI 分镜/角色收编/自动选音链路的日志必须显式传 module，
 * 不依赖类名归类——其调用点分布在 help.tts 包（类名含 tts 会被误归 READ_ALOUD）
 * 与 BdReadAloudService（已钉定为 BAIDU_TTS），两类都无法按类名得到正确归属。
 */
enum class LogModule(val labelRes: Int) {
    GENERAL(R.string.log_module_general),
    READ_ALOUD(R.string.log_module_read_aloud),
    BAIDU_TTS(R.string.log_module_baidu_tts),
    TTS_CACHE(R.string.log_module_tts_cache),
    AI_CAST(R.string.log_module_ai_cast),
    DOWNLOAD_CACHE(R.string.log_module_download_cache),
    READING(R.string.log_module_reading),
    SOURCE_NETWORK(R.string.log_module_source_network),
    PERFORMANCE(R.string.log_module_performance),
    AI(R.string.log_module_ai);

    companion object {

        /** 可勾选的模块（不含始终显示的 GENERAL），顺序即弹窗中的展示顺序 */
        val selectable: List<LogModule>
            get() = listOf(
                READ_ALOUD,
                BAIDU_TTS,
                TTS_CACHE,
                AI_CAST,
                DOWNLOAD_CACHE,
                READING,
                SOURCE_NETWORK,
                PERFORMANCE,
                AI,
            )

        val selectableNames: Set<String> = selectable.map { it.name }.toSet()

        /**
         * 显式归属表：调用方类名（小写、含包名）以其中前缀开头时，直接钉定到唯一模块，
         * 优先于关键词匹配。用于收纳同时命中多组关键词的类——每条日志只允许归属一个模块，
         * 不允许靠 when 分支顺序裁决：
         * - 关键词子串嵌套：bdtts 含 tts、bdreadaloud 含 readaloud、ttscache 含 tts；
         * - 跨组同名：localBook 内嵌 JsExtensions 同时命中 localbook 与 jsextensions。
         * 前缀按"外层类名"写，天然覆盖内部类（$）、companion 与 Kt 文件类变体。
         */
        private val pinnedByClassPrefix: List<Pair<String, LogModule>> = listOf(
            // 百度TTS：help.bdtts 包所有类与 BdReadAloudService 双命中 BAIDU_TTS + READ_ALOUD
            "io.legado.app.help.bdtts." to LogModule.BAIDU_TTS,
            "io.legado.app.service.bdreadaloudservice" to LogModule.BAIDU_TTS,
            // TTS缓存：TtsCacheLog 双命中 TTS_CACHE（ttscache）+ READ_ALOUD（tts，来自 help.tts 包）
            "io.legado.app.help.tts.ttscachelog" to LogModule.TTS_CACHE,
            // 本地书 TXT 目录规则的 JS 执行环境（TextFile$JsExtensions）双命中
            // READING（localbook）+ SOURCE_NETWORK（jsextensions），唯一归属阅读
            "io.legado.app.model.localbook.textfile\$jsextensions" to LogModule.READING,
        )

        /**
         * 按调用方类名对日志单点归类，判定规则集中在这一处，
         * 未匹配的类一律归入通用，保证不丢任何日志也不需要逐个调用点打标。
         * 命中多组关键词的类必须先在 [pinnedByClassPrefix] 钉定唯一归属。
         */
        fun classify(callerClassName: String?): LogModule {
            if (callerClassName.isNullOrBlank()) return GENERAL
            val name = callerClassName.lowercase()
            pinnedByClassPrefix.firstOrNull { name.startsWith(it.first) }?.let { return it.second }
            return when {
                containsAny(
                    name,
                    "appfreezemonitor",
                    "dispatchersmonitor",
                    "liveeventbus",
                    "eventbus",
                    "threadutils",
                ) -> PERFORMANCE

                // 必须先于 "tts"（READ_ALOUD）判定：ttscache 关键词含 tts 子串
                //（TtsCacheLog 已由钉定表唯一归属 TTS_CACHE）
                containsAny(name, "ttscache") -> TTS_CACHE

                // 必须先于 READ_ALOUD 判定：bdtts 关键词含 tts 子串；
                // help.bdtts 包与 BdReadAloudService 已由钉定表唯一归属百度 TTS
                containsAny(
                    name,
                    "bdtts",
                    "bdengine",
                    "bdreadaloud",
                ) -> BAIDU_TTS

                containsAny(
                    name,
                    "readaloud",
                    "aloudservice",
                    "tts",
                    "audioplay",
                ) -> READ_ALOUD

                containsAny(
                    name,
                    "cachebook",
                    "download",
                    "bookhelp",
                    "cachelogsink",
                    "cacheoperationdiagnostics",
                    "mediacachetaskmanager",
                ) -> DOWNLOAD_CACHE

                containsAny(
                    name,
                    "readbook",
                    "textchapterlayout",
                    "chapterprovider",
                    "contenttextview",
                    "readview",
                    "imageprovider",
                    "readmanga",
                    "localbook",
                    "epubfile",
                    "mobifile",
                    "pdffile",
                    "textfile",
                    "bookmark",
                    "readrss",
                    "rssarticle",
                ) -> READING

                containsAny(
                    name,
                    "webbook",
                    "analyzeurl",
                    "jsextensions",
                    "regexjsextensions",
                    "cronet",
                    "cookiestore",
                    "cookiemanager",
                    "networkutils",
                    "urlutil",
                    "okhttp",
                    "sourceverification",
                    "sourcelogin",
                    "basesource",
                    "booksource",
                    "rsssource",
                    "searchmodel",
                ) -> SOURCE_NETWORK

                else -> GENERAL
            }
        }

        private fun containsAny(source: String, vararg keywords: String): Boolean {
            return keywords.any { source.contains(it) }
        }
    }
}
