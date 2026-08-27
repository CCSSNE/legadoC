package io.legado.app.constant

import androidx.annotation.StringRes
import io.legado.app.R

/**
 * 普通日志的模块归属。GENERAL 是兜底模块，始终在普通日志中显示；
 * 其余模块由用户在其他设置的“普通日志模块”弹窗中勾选是否显示。
 */
enum class LogModule(val labelRes: Int) {
    GENERAL(R.string.log_module_general),
    READ_ALOUD(R.string.log_module_read_aloud),
    DOWNLOAD_CACHE(R.string.log_module_download_cache),
    READING(R.string.log_module_reading),
    SOURCE_NETWORK(R.string.log_module_source_network),
    PERFORMANCE(R.string.log_module_performance),
    AI(R.string.log_module_ai);

    companion object {

        /** 可勾选的模块（不含始终显示的 GENERAL），顺序即弹窗中的展示顺序 */
        val selectable: List<LogModule>
            get() = listOf(READ_ALOUD, DOWNLOAD_CACHE, READING, SOURCE_NETWORK, PERFORMANCE, AI)

        val selectableNames: Set<String> = selectable.map { it.name }.toSet()

        /**
         * 按调用方类名对日志单点归类，判定规则集中在这一处，
         * 未匹配的类一律归入通用，保证不丢任何日志也不需要逐个调用点打标。
         */
        fun classify(callerClassName: String?): LogModule {
            if (callerClassName.isNullOrBlank()) return GENERAL
            val name = callerClassName.lowercase()
            return when {
                containsAny(
                    name,
                    "appfreezemonitor",
                    "dispatchersmonitor",
                    "liveeventbus",
                    "eventbus",
                    "threadutils",
                ) -> PERFORMANCE

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
