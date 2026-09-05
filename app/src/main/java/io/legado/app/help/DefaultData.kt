package io.legado.app.help

import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.DictRule
import io.legado.app.data.entities.HighlightRule
import io.legado.app.data.entities.HttpTTS
import io.legado.app.data.entities.KeyboardAssist
import io.legado.app.data.entities.RssSource
import io.legado.app.data.entities.TxtTocRule
import io.legado.app.data.entities.BookmarkStyle
import io.legado.app.help.ai.AiCreationConfig
import io.legado.app.help.ai.AiStructuredRequestTemplate
import io.legado.app.help.config.LocalConfig
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.help.config.ThemeConfig
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.model.BookCover
import io.legado.app.utils.GSON
import io.legado.app.utils.LogUtils
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.fromJsonObject
import splitties.init.appCtx
import java.io.File

object DefaultData {

    private const val REMOVED_DEFAULT_RSS_SOURCE_URL = "https://loyc.xyz/b/daoru"
    private const val HTTP_TTS_VERSION_KEY = "httpTtsVersion"
    private const val HTTP_TTS_VERSION = 6
    private const val TXT_TOC_RULE_VERSION_KEY = "txtTocRuleVersion"
    private const val TXT_TOC_RULE_VERSION = 3
    private const val RSS_SOURCE_VERSION_KEY = "rssSourceVersion"
    private const val RSS_SOURCE_VERSION = 8
    private const val DICT_RULE_VERSION_KEY = "needUpDictRule"
    private const val DICT_RULE_VERSION = 2
    private const val HIGHLIGHT_RULE_VERSION_KEY = "highlightRuleVersion"
    private const val HIGHLIGHT_RULE_VERSION = 1
    private const val MAX_HIGHLIGHT_RULE_VERSION_KEY = "maxHighlightRuleVersion"
    private const val MAX_HIGHLIGHT_RULE_VERSION = 1
    private const val AI_CREATION_CONFIG_VERSION_KEY = "aiCreationConfigVersion"
    //v4：强升级——AI 全部 JSON 配置回到出厂（只留钥匙名字地址与模型选择），
    //此后出厂再加参数/改模板，用户升级即自动对齐，不用手动恢复默认
    private const val AI_CREATION_CONFIG_VERSION = 4

    fun upVersion() {
        Coroutine.async {
            migrateDefaultData("HTTP TTS", HTTP_TTS_VERSION_KEY, HTTP_TTS_VERSION) {
                importDefaultHttpTTS()
            }
            migrateDefaultData("TXT 目录规则", TXT_TOC_RULE_VERSION_KEY, TXT_TOC_RULE_VERSION) {
                importDefaultTocRules()
            }
            migrateDefaultData("订阅源", RSS_SOURCE_VERSION_KEY, RSS_SOURCE_VERSION) {
                importDefaultRssSources()
            }
            migrateDefaultData("字典规则", DICT_RULE_VERSION_KEY, DICT_RULE_VERSION) {
                importDefaultDictRules()
            }
            migrateDefaultData("高亮规则", HIGHLIGHT_RULE_VERSION_KEY, HIGHLIGHT_RULE_VERSION) {
                importDefaultHighlightRules()
            }
            migrateDefaultData("Max高亮规则", MAX_HIGHLIGHT_RULE_VERSION_KEY, MAX_HIGHLIGHT_RULE_VERSION) {
                importDefaultMaxHighlightRules()
            }
            migrateDefaultData("AI创作配置", AI_CREATION_CONFIG_VERSION_KEY, AI_CREATION_CONFIG_VERSION) {
                //先强回出厂（只留身份与连线），再消毒存量参数值，最后做模板归属迁移
                AiCreationConfig.forceRestoreFactoryDefaults()
                AiCreationConfig.sanitizeStoredJsons()
                //v2：请求模板归属迁移——全局归聊天+创作（未定制切新干净默认），净化固化继承快照
                AiStructuredRequestTemplate.migrateTemplateOwnership()
            }
        }.onError {
            AppLog.put("启动默认数据升级任务失败\n${it.localizedMessage}", it)
        }
    }

    private inline fun migrateDefaultData(
        name: String,
        versionKey: String,
        targetVersion: Int,
        migration: () -> Unit,
    ) {
        val currentVersion = LocalConfig.defaultDataVersion(versionKey)
        if (currentVersion >= targetVersion) return
        LogUtils.d(
            "DefaultData",
            "开始升级$name：$currentVersion -> $targetVersion"
        )
        runCatching(migration).onSuccess {
            LocalConfig.markDefaultDataVersion(versionKey, targetVersion)
            LogUtils.d("DefaultData", "$name 升级完成：$targetVersion")
        }.onFailure {
            AppLog.put(
                "$name 升级失败：$currentVersion -> $targetVersion\n${it.localizedMessage}",
                it,
            )
        }
    }

    val httpTTS: List<HttpTTS> by lazy {
        val json =
            String(
                appCtx.assets.open("defaultData${File.separator}httpTTS.json")
                    .readBytes()
            )
        HttpTTS.fromJsonArray(json).getOrElse {
            emptyList()
        }
    }

    val readConfigs: List<ReadBookConfig.Config> by lazy {
        val json = String(
            appCtx.assets.open("defaultData${File.separator}${ReadBookConfig.configFileName}")
                .readBytes()
        )
        GSON.fromJsonArray<ReadBookConfig.Config>(json).getOrNull()
            ?: emptyList()
    }

    val txtTocRules: List<TxtTocRule> by lazy {
        val json = String(
            appCtx.assets.open("defaultData${File.separator}txtTocRule.json")
                .readBytes()
        )
        GSON.fromJsonArray<TxtTocRule>(json).getOrNull() ?: emptyList()
    }

    val themeConfigs: List<ThemeConfig.Config> by lazy {
        val json = String(
            appCtx.assets.open("defaultData${File.separator}${ThemeConfig.configFileName}")
                .readBytes()
        )
        GSON.fromJsonArray<ThemeConfig.Config>(json).getOrNull() ?: emptyList()
    }

    val rssSources: List<RssSource> by lazy {
        val json = String(
            appCtx.assets.open("defaultData${File.separator}rssSources.json")
                .readBytes()
        )
        GSON.fromJsonArray<RssSource>(json).getOrThrow()
    }

    val coverRule: BookCover.CoverRule by lazy {
        val json = String(
            appCtx.assets.open("defaultData${File.separator}coverRule.json")
                .readBytes()
        )
        GSON.fromJsonObject<BookCover.CoverRule>(json).getOrThrow()
    }

    val dictRules: List<DictRule> by lazy {
        val json = String(
            appCtx.assets.open("defaultData${File.separator}dictRules.json")
                .readBytes()
        )
        GSON.fromJsonArray<DictRule>(json).getOrThrow()
    }

    val keyboardAssists: List<KeyboardAssist> by lazy {
        val json = String(
            appCtx.assets.open("defaultData${File.separator}keyboardAssists.json")
                .readBytes()
        )
        GSON.fromJsonArray<KeyboardAssist>(json).getOrThrow()
    }

    fun importDefaultHttpTTS() {
        appDb.httpTTSDao.deleteDefault()
        appDb.httpTTSDao.insert(*httpTTS.toTypedArray())
    }

    fun importDefaultTocRules() {
        appDb.txtTocRuleDao.deleteDefault()
        appDb.txtTocRuleDao.insert(*txtTocRules.toTypedArray())
    }

    fun importDefaultRssSources() {
        appDb.runInTransaction {
            appDb.rssSourceDao.delete(REMOVED_DEFAULT_RSS_SOURCE_URL)
            appDb.rssSourceDao.deleteDefault()
            appDb.rssSourceDao.insert(*rssSources.toTypedArray())
        }
    }

    fun importDefaultDictRules() {
        appDb.dictRuleDao.insert(*dictRules.toTypedArray())
    }

    /**
     * 默认高亮规则：取自阅读 NG 默认排版包"秋山书意"的对白规则
     * （正则与颜色原样保留；NG 的夜间色本模型不区分，取日间色）。
     * 按统一口径，默认高亮规则全部为关闭状态。
     */
    private const val dialoguePattern =
        "(?:\\u201c[^\\u201d\\n]{1,1200}\\u201d|\"[^\"\\n]{1,1200}\"|「[^」\\n]{1,1200}」|『[^』\\n]{1,1200}』)"
    private val dialogueTextColor = 0xff904e0c.toInt()

    val highlightRules: List<HighlightRule> = listOf(
        HighlightRule(
            id = 1L,
            name = "对白-波浪线",
            pattern = dialoguePattern,
            isEnabled = false,
            order = 0,
            style = BookmarkStyle.WAVE_UNDERLINE or BookmarkStyle.TEXT_COLOR,
            styleColors = BookmarkStyle.toStyleColorsJson(
                mapOf(
                    BookmarkStyle.WAVE_UNDERLINE to dialogueTextColor,
                    BookmarkStyle.TEXT_COLOR to dialogueTextColor
                )
            )
        ),
        HighlightRule(
            id = 2L,
            name = "对白-高亮",
            pattern = dialoguePattern,
            isEnabled = false,
            order = 1,
            style = BookmarkStyle.TEXT_COLOR,
            styleColors = BookmarkStyle.toStyleColorsJson(
                mapOf(BookmarkStyle.TEXT_COLOR to dialogueTextColor)
            )
        )
    )

    fun importDefaultHighlightRules() {
        appDb.highlightRuleDao.insert(*highlightRules.toTypedArray())
    }

    /**
     * 默认高亮规则（Max 预置）：移植自阅读 Max 版内置预置规则（HighlightRuleDefaultRules），
     * 全部默认关闭。Max 的"对话高亮"与上面 NG 对白规则重复，未移植；
     * Max 的虚线下划线本效果体系无对应，按单下划线移植；
     * "标题强调"在 Max 中仅作用于标题行，本模型无标题范围限定，正则原样保留。
     */
    val maxHighlightRules: List<HighlightRule> = listOf(
        HighlightRule(
            id = 3L,
            name = "书名号高亮",
            pattern = "《[^》\\n]{1,80}》",
            isEnabled = false,
            order = 2,
            style = BookmarkStyle.WAVE_UNDERLINE,
            styleColors = BookmarkStyle.toStyleColorsJson(
                mapOf(BookmarkStyle.WAVE_UNDERLINE to 0xFF63C37D.toInt())
            )
        ),
        HighlightRule(
            id = 4L,
            name = "括号标注高亮",
            pattern = "（[^（）\\n]{1,80}）|\\([^()\\n]{1,80}\\)|【[^】\\n]{1,80}】|\\[[^\\]\\n]{1,80}]",
            isEnabled = false,
            order = 3,
            style = BookmarkStyle.TEXT_COLOR or BookmarkStyle.SINGLE_UNDERLINE,
            styleColors = BookmarkStyle.toStyleColorsJson(
                mapOf(
                    BookmarkStyle.TEXT_COLOR to 0xFF8F959E.toInt(),
                    BookmarkStyle.SINGLE_UNDERLINE to 0xFF5A8DEE.toInt()
                )
            )
        ),
        HighlightRule(
            id = 5L,
            name = "标题强调",
            pattern = "(?m)^\\s{0,2}(?:第[0-9零〇一二两三四五六七八九十百千万IVXLCDMivxlcdm]{1,12}[章节卷回部篇集幕]|序章|楔子|引子|终章|尾声|后记|番外)[^\\n]{0,40}$",
            isEnabled = false,
            order = 4,
            style = BookmarkStyle.TEXT_COLOR or BookmarkStyle.DOUBLE_UNDERLINE,
            styleColors = BookmarkStyle.toStyleColorsJson(
                mapOf(
                    BookmarkStyle.TEXT_COLOR to 0xFF333333.toInt(),
                    BookmarkStyle.DOUBLE_UNDERLINE to 0xFF7C5634.toInt()
                )
            )
        ),
        HighlightRule(
            id = 6L,
            name = "心理活动",
            pattern = "（[^）\\n]{0,40}(?:心想|暗道|心道|想到|寻思着|琢磨|嘀咕)[^）\\n]{0,40}）",
            isEnabled = false,
            order = 5,
            style = BookmarkStyle.TEXT_COLOR or BookmarkStyle.SINGLE_UNDERLINE,
            styleColors = BookmarkStyle.toStyleColorsJson(
                mapOf(
                    BookmarkStyle.TEXT_COLOR to 0xFF9370DB.toInt(),
                    BookmarkStyle.SINGLE_UNDERLINE to 0xFF9370DB.toInt()
                )
            )
        ),
        HighlightRule(
            id = 7L,
            name = "旁白说明",
            pattern = "(?:未完待续|待续|下文再表|按：|注：)[^\\n]{0,40}|（(?:注|旁白|作者有话说)[:：][^）\\n]{0,40}）",
            isEnabled = false,
            order = 6,
            style = BookmarkStyle.TEXT_COLOR,
            styleColors = BookmarkStyle.toStyleColorsJson(
                mapOf(BookmarkStyle.TEXT_COLOR to 0xFF708090.toInt())
            )
        ),
        HighlightRule(
            id = 8L,
            name = "重点强调",
            pattern = "(?:\\*\\*|__)[^\\n*_]{1,40}(?:\\*\\*|__)|(?:!!!|！？|\\?!)[^\\n]{0,20}",
            isEnabled = false,
            order = 7,
            style = BookmarkStyle.TEXT_COLOR or BookmarkStyle.SINGLE_UNDERLINE,
            styleColors = BookmarkStyle.toStyleColorsJson(
                mapOf(
                    BookmarkStyle.TEXT_COLOR to 0xFFDC143C.toInt(),
                    BookmarkStyle.SINGLE_UNDERLINE to 0xFFDC143C.toInt()
                )
            )
        ),
        HighlightRule(
            id = 9L,
            name = "诗词引用",
            pattern = "(?m)^[\\p{IsHan}，。！？；：、]{5,24}$",
            isEnabled = false,
            order = 8,
            style = BookmarkStyle.TEXT_COLOR or BookmarkStyle.WAVE_UNDERLINE,
            styleColors = BookmarkStyle.toStyleColorsJson(
                mapOf(
                    BookmarkStyle.TEXT_COLOR to 0xFF2F4F4F.toInt(),
                    BookmarkStyle.WAVE_UNDERLINE to 0xFF2F4F4F.toInt()
                )
            )
        ),
        HighlightRule(
            id = 10L,
            name = "省略停顿",
            pattern = "…{2,}|\\.{3,}|—{2,}|-{3,}",
            isEnabled = false,
            order = 9,
            style = BookmarkStyle.TEXT_COLOR,
            styleColors = BookmarkStyle.toStyleColorsJson(
                mapOf(BookmarkStyle.TEXT_COLOR to 0xFF8B8B8B.toInt())
            )
        ),
        HighlightRule(
            id = 11L,
            name = "数字金额",
            pattern = "(?:¥|￥)?\\d+(?:\\.\\d+)?(?:元|块|万|千|百|亿|%|％)|[零〇一二两三四五六七八九十百千万亿]+(?:元|块|万|千|百|亿)",
            isEnabled = false,
            order = 10,
            style = BookmarkStyle.TEXT_COLOR,
            styleColors = BookmarkStyle.toStyleColorsJson(
                mapOf(BookmarkStyle.TEXT_COLOR to 0xFF4169E1.toInt())
            )
        ),
        HighlightRule(
            id = 12L,
            name = "英文单词",
            pattern = "\\b[A-Za-z]{2,}[A-Za-z0-9'-]*\\b",
            isEnabled = false,
            order = 11,
            style = BookmarkStyle.TEXT_COLOR,
            styleColors = BookmarkStyle.toStyleColorsJson(
                mapOf(BookmarkStyle.TEXT_COLOR to 0xFF4169E1.toInt())
            )
        ),
        HighlightRule(
            id = 13L,
            name = "时间日期",
            pattern = "(?:\\d{2,4}|[零〇一二两三四五六七八九十]{2,4})年(?:\\d{1,2}|[正一二三四五六七八九十冬腊])月(?:\\d{1,2}|[一二三四五六七八九十廿三])?[日号]?|\\b\\d{1,2}:\\d{2}\\b|(?:[0-1]?\\d|2[0-3])点(?:[0-5]?\\d分?)?",
            isEnabled = false,
            order = 12,
            style = BookmarkStyle.TEXT_COLOR,
            styleColors = BookmarkStyle.toStyleColorsJson(
                mapOf(BookmarkStyle.TEXT_COLOR to 0xFF20B2AA.toInt())
            )
        )
    )

    fun importDefaultMaxHighlightRules() {
        appDb.highlightRuleDao.insert(*maxHighlightRules.toTypedArray())
    }

}
