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
     * （对白-波浪线启用、对白-高亮停用，正则与颜色原样保留；NG 的夜间色本模型不区分，取日间色）
     */
    private const val dialoguePattern =
        "(?:\\u201c[^\\u201d\\n]{1,1200}\\u201d|\"[^\"\\n]{1,1200}\"|「[^」\\n]{1,1200}」|『[^』\\n]{1,1200}』)"
    private val dialogueTextColor = 0xff904e0c.toInt()

    val highlightRules: List<HighlightRule> = listOf(
        HighlightRule(
            id = 1L,
            name = "对白-波浪线",
            pattern = dialoguePattern,
            isEnabled = true,
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

}
