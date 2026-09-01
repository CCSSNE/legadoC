package io.legado.app.constant

import androidx.annotation.StringRes
import io.legado.app.R

/**
 * 普通日志的模块归属。每条日志必须归属唯一模块，且所有模块均可勾选：
 * 未勾选的模块日志不显示，全部不勾选时普通日志为空，不存在始终显示的兜底模块。
 *
 * AI_CAST（AI分角色）约定：AI 分镜/角色收编/自动选音链路的日志必须显式传 module，
 * 不依赖类名归类——其调用点分布在 help.tts 包（类名含 tts 会被误归 READ_ALOUD）
 * 与 BdReadAloudService（已钉定为 BAIDU_TTS），两类都无法按类名得到正确归属。
 *
 * APP（应用/界面）收纳崩溃、权限、配置、界面与工具类等与功能模块无关的日志，
 * 全部由关键词显式覆盖。
 *
 * UNCLASSIFIED（未分类）是显式垃圾桶：只收纳 classify 未识别的新调用方，
 * 当前调用点不应有日志落入；勾选它即可发现漏网的新日志，确认后为其建立正式归属。
 * 显示与其他模块一样完全受勾选控制。
 */
enum class LogModule(val labelRes: Int) {
    READ_ALOUD(R.string.log_module_read_aloud),
    BAIDU_TTS(R.string.log_module_baidu_tts),
    TTS_CACHE(R.string.log_module_tts_cache),
    AI_CAST(R.string.log_module_ai_cast),
    AI(R.string.log_module_ai),
    DOWNLOAD_CACHE(R.string.log_module_download_cache),
    REVIEW_OFFLINE(R.string.log_module_review_offline),
    READING(R.string.log_module_reading),
    SOURCE_NETWORK(R.string.log_module_source_network),
    BACKUP(R.string.log_module_backup),
    VIDEO(R.string.log_module_video),
    PERFORMANCE(R.string.log_module_performance),
    APP(R.string.log_module_app),
    UNCLASSIFIED(R.string.log_module_unclassified);

    companion object {

        /** 可勾选的模块（全部模块），顺序即弹窗中的展示顺序 */
        val selectable: List<LogModule>
            get() = listOf(
                READ_ALOUD,
                BAIDU_TTS,
                TTS_CACHE,
                AI_CAST,
                AI,
                DOWNLOAD_CACHE,
                REVIEW_OFFLINE,
                READING,
                SOURCE_NETWORK,
                BACKUP,
                VIDEO,
                PERFORMANCE,
                APP,
                UNCLASSIFIED,
            )

        val selectableNames: Set<String> = selectable.map { it.name }.toSet()

        /**
         * 显式归属表：调用方类名（小写、含包名）以其中前缀开头时，直接钉定到唯一模块，
         * 优先于关键词匹配。用于收纳同时命中多组关键词的类——每条日志只允许归属一个模块，
         * 不允许靠 when 分支顺序裁决：
         * - 关键词子串嵌套：bdtts 含 tts、bdreadaloud 含 readaloud、ttscache 含 tts；
         * - 跨组同名：localBook 内嵌 JsExtensions 同时命中 localbook 与 jsextensions；
         * - help.ai 与 ui.main.ai 整包属 AI，避免裸 "ai" 子串误伤（如 main 含 ai）。
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
            // 离线评论：reviewoutbox 前缀含 review 关键词（会命中 DOWNLOAD_CACHE），钉定唯一归属
            "io.legado.app.help.review.reviewoutbox" to LogModule.REVIEW_OFFLINE,
            // AI：help.ai 包与聊天界面、AI 配置页；裸 "ai" 关键词会误伤 main 等类名，必须钉定
            "io.legado.app.help.ai." to LogModule.AI,
            "io.legado.app.ui.main.ai." to LogModule.AI,
            "io.legado.app.ui.config.aiconfigfragment" to LogModule.AI,
        )

        /**
         * 按调用方类名对日志单点归类，判定规则集中在这一处，
         * 未匹配的类一律归入 UNCLASSIFIED（未分类）垃圾桶，保证不丢任何日志；
         * 现有调用点应全部被上方关键词覆盖，未分类只应接到新增的未归类调用方。
         * 命中多组关键词的类必须先在 [pinnedByClassPrefix] 钉定唯一归属。
         */
        fun classify(callerClassName: String?): LogModule {
            if (callerClassName.isNullOrBlank()) return UNCLASSIFIED
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
                    "speakengine",
                ) -> READ_ALOUD

                containsAny(
                    name,
                    "webdav",
                    "backup",
                    "restore",
                ) -> BACKUP

                containsAny(name, "video") -> VIDEO

                containsAny(
                    name,
                    "cachebook",
                    "download",
                    "bookhelp",
                    "cachelogsink",
                    "cacheoperationdiagnostics",
                    "mediacachetaskmanager",
                    "cacheactivity",
                    "exportbook",
                    "review",
                ) -> DOWNLOAD_CACHE

                // SOURCE_NETWORK 必须先于 READING 判定：importbooksource 等书源类
                // 也含 importbook 这类阅读关键词，先判书源网络才能唯一归属
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
                    "sourcecallback",
                    "changesource",
                    "changecover",
                    "explore",
                    "searchactivity",
                    "searchviewmodel",
                    "searchscopedialog",
                    "openurl",
                    "remotebook",
                    "serversdialog",
                    "sourcepicker",
                ) -> SOURCE_NETWORK

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
                    "audiotextfusion",
                    "bookextensions",
                    "bookimgclick",
                    "contentprocessor",
                    "bookchapter",
                    "replacerule",
                    "bookinfo",
                    "bookshelf",
                    "booksfragment",
                    "bookcollection",
                    "importbook",
                    "fileassociation",
                    "groupmanage",
                    "textactionmenu",
                    "bgtextconfig",
                    "searchcontent",
                    "rssfavorites",
                    "rssfragment",
                    "rulesub",
                    "toc",
                ) -> READING

                // APP（应用/界面）：崩溃、权限、配置、界面与工具类，全部显式覆盖，
                // 不留隐式兜底——未匹配的调用方落入 UNCLASSIFIED 垃圾桶
                containsAny(
                    name,
                    "baseactivity",
                    "basedialogfragment",
                    "appconfig",
                    "theme",
                    "crashhandler",
                    "defaultdata",
                    "permission",
                    "about",
                    "dict",
                    "webview",
                    "codeedit",
                    "handlefile",
                    "font",
                    "imagecrop",
                    "imageutils",
                    "mainviewmodel",
                    "keyboard",
                    "logutils",
                    "uriextensions",
                ) -> APP

                else -> UNCLASSIFIED
            }
        }

        private fun containsAny(source: String, vararg keywords: String): Boolean {
            return keywords.any { source.contains(it) }
        }
    }
}
