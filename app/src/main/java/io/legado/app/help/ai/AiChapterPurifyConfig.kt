package io.legado.app.help.ai

import io.legado.app.constant.PreferKey
import io.legado.app.help.config.AppConfig
import io.legado.app.ui.main.ai.AiProviderConfig
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.getPrefInt
import io.legado.app.utils.getPrefString
import io.legado.app.utils.putPrefBoolean
import io.legado.app.utils.putPrefInt
import io.legado.app.utils.putPrefString
import splitties.init.appCtx

data class AiChapterPurifyModelTarget(
    val provider: AiProviderConfig,
    val modelId: String
)

object AiChapterPurifyConfig {

    val supportedTypes = listOf("ad", "typo", "noise")

    const val DEFAULT_CHAPTER_COUNT = 2
    const val DEFAULT_SEGMENT_LIMIT = 10_000
    const val DEFAULT_RETRY_COUNT = 3
    const val DEFAULT_CONCURRENCY = 1

    const val MIN_CHAPTER_COUNT = 1
    const val MAX_CHAPTER_COUNT = 200
    const val MIN_SEGMENT_LIMIT = 1_000
    const val MAX_SEGMENT_LIMIT = 50_000
    const val MIN_RETRY_COUNT = 0
    const val MAX_RETRY_COUNT = 10
    const val MIN_CONCURRENCY = 1
    const val MAX_CONCURRENCY = 8

    private val defaultPreprocessRules = listOf(
        AiChapterPurifyPreprocessRule(
            name = "移除图片和展示标签",
            pattern = "<img\\b[^>]*>|<svg\\b[^>]*>[\\s\\S]*?</svg>",
            replacement = "",
            enabled = true,
            order = 10,
            scopes = supportedTypes
        )
    )

    val defaultPreprocessJson: String by lazy {
        GSON.toJson(defaultPreprocessRules)
    }

    val defaultPrompt = """
        你是中文网文净化规则生成器。

        从输入段落中找出可以写入替换净化规则的候选。只处理用户已启用的类型：
        - typo：非常明确的错别字、OCR 错字或异体字；old 和 new 至少两个字符，不改写文风。
        - noise：正文中夹杂的异常字符、数字编号、乱码或站名污染；不得补写正文。
        - ad：完整且明确与作品正文无关的广告、引流、群号、网址推广或盗版说明；new 必须为空字符串。

        段落编号由客户端在输入预处理完成后追加，必须使用编号定位原始段落。
        对 ad 只返回 id 和 type，不返回 old 或 new；程序会根据 id 删除 B 中的完整广告段落。
        对 typo 和 noise 返回 normalized 输入中的精确 old 片段以及 new 替换内容。
        不确定时不要返回规则。不得润色、扩写、缩写、改写正常正文，也不得把图片标签、作品资料、作者后记或设定说明当广告删除。
    """.trimIndent()

    var reuseCurrentModel: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.aiChapterPurifyReuseCurrentModel, true)
        set(value) = appCtx.putPrefBoolean(PreferKey.aiChapterPurifyReuseCurrentModel, value)

    var independentProvider: AiProviderConfig?
        get() {
            val raw = appCtx.getPrefString(PreferKey.aiChapterPurifyProvider).orEmpty()
            return raw.takeIf { it.isNotBlank() }?.let {
                GSON.fromJson(it, AiProviderConfig::class.java)
            }
        }
        set(value) {
            if (value == null) {
                appCtx.putPrefString(PreferKey.aiChapterPurifyProvider, "")
            } else {
                appCtx.putPrefString(PreferKey.aiChapterPurifyProvider, GSON.toJson(value))
            }
        }

    var independentModelId: String
        get() = appCtx.getPrefString(PreferKey.aiChapterPurifyModel).orEmpty()
        set(value) = appCtx.putPrefString(PreferKey.aiChapterPurifyModel, value.trim())

    var prompt: String
        get() = appCtx.getPrefString(PreferKey.aiChapterPurifyPrompt)
            ?.takeIf { it.isNotBlank() }
            ?: defaultPrompt
        set(value) {
            val normalized = value.trim()
            appCtx.putPrefString(
                PreferKey.aiChapterPurifyPrompt,
                if (normalized == defaultPrompt) "" else normalized
            )
        }

    var requestTemplate: String
        get() = appCtx.getPrefString(PreferKey.aiRequestTemplate)
            ?.takeIf { it.isNotBlank() }
            ?: AiStructuredRequestTemplate.default
        set(value) = appCtx.putPrefString(PreferKey.aiRequestTemplate, value.trim())

    var preprocessJson: String
        get() = GSON.toJson(preprocessRules)
        set(value) {
            val normalized = value.trim()
            val rules = parsePreprocessJson(normalized).map {
                it.copy(scopes = it.effectiveScopes())
            }
            val normalizedJson = GSON.toJson(rules)
            appCtx.putPrefString(
                PreferKey.aiChapterPurifyPreprocess,
                if (normalizedJson == defaultPreprocessJson) "" else normalizedJson
            )
        }

    val preprocessRules: List<AiChapterPurifyPreprocessRule>
        get() = parsePreprocessJson(
            appCtx.getPrefString(PreferKey.aiChapterPurifyPreprocess)
                ?.takeIf { it.isNotBlank() }
                ?: defaultPreprocessJson
        ).map {
            it.copy(scopes = it.effectiveScopes())
        }

    fun enabledTypes(): List<String> = supportedTypes.filter { isTypeEnabled(it) }

    private fun parsePreprocessJson(json: String): List<AiChapterPurifyPreprocessRule> {
        val rules = GSON.fromJsonArray<AiChapterPurifyPreprocessRule>(json).getOrElse {
            throw AiChapterPurifyException(
                "AI input preprocessing JSON is invalid: ${it.message ?: it.javaClass.simpleName}",
                it
            )
        }
        AiChapterPurifyPreprocessor.validateRules(rules)
        return rules
    }

    var summaryEnabled: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.aiChapterPurifySummaryEnabled, true)
        set(value) = appCtx.putPrefBoolean(PreferKey.aiChapterPurifySummaryEnabled, value)

    var chapterCount: Int
        get() = appCtx.getPrefInt(
            PreferKey.aiChapterPurifyChapterCount,
            DEFAULT_CHAPTER_COUNT
        ).coerceIn(MIN_CHAPTER_COUNT, MAX_CHAPTER_COUNT)
        set(value) = appCtx.putPrefInt(
            PreferKey.aiChapterPurifyChapterCount,
            value.coerceIn(MIN_CHAPTER_COUNT, MAX_CHAPTER_COUNT)
        )

    var segmentLimit: Int
        get() = appCtx.getPrefInt(
            PreferKey.aiChapterPurifySegmentLimit,
            DEFAULT_SEGMENT_LIMIT
        ).coerceIn(MIN_SEGMENT_LIMIT, MAX_SEGMENT_LIMIT)
        set(value) = appCtx.putPrefInt(
            PreferKey.aiChapterPurifySegmentLimit,
            value.coerceIn(MIN_SEGMENT_LIMIT, MAX_SEGMENT_LIMIT)
        )

    var retryCount: Int
        get() = appCtx.getPrefInt(
            PreferKey.aiChapterPurifyRetryCount,
            DEFAULT_RETRY_COUNT
        ).coerceIn(MIN_RETRY_COUNT, MAX_RETRY_COUNT)
        set(value) = appCtx.putPrefInt(
            PreferKey.aiChapterPurifyRetryCount,
            value.coerceIn(MIN_RETRY_COUNT, MAX_RETRY_COUNT)
        )

    var concurrency: Int
        get() = appCtx.getPrefInt(
            PreferKey.aiChapterPurifyConcurrency,
            DEFAULT_CONCURRENCY
        ).coerceIn(MIN_CONCURRENCY, MAX_CONCURRENCY)
        set(value) = appCtx.putPrefInt(
            PreferKey.aiChapterPurifyConcurrency,
            value.coerceIn(MIN_CONCURRENCY, MAX_CONCURRENCY)
        )

    var typoEnabled: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.aiChapterPurifyTypoEnabled, true)
        set(value) = appCtx.putPrefBoolean(PreferKey.aiChapterPurifyTypoEnabled, value)

    var noiseEnabled: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.aiChapterPurifyNoiseEnabled, true)
        set(value) = appCtx.putPrefBoolean(PreferKey.aiChapterPurifyNoiseEnabled, value)

    var adEnabled: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.aiChapterPurifyAdEnabled, true)
        set(value) = appCtx.putPrefBoolean(PreferKey.aiChapterPurifyAdEnabled, value)

    fun isTypeEnabled(type: String): Boolean = when (type.lowercase()) {
        "typo" -> typoEnabled
        "noise" -> noiseEnabled
        "ad" -> adEnabled
        else -> false
    }

    fun requireModelTarget(): AiChapterPurifyModelTarget {
        if (reuseCurrentModel) {
            val provider = AppConfig.aiCurrentProvider
                ?: error("请先配置当前 AI 提供商，或关闭“复用当前 AI 模型”后配置章节净化模型")
            val model = AppConfig.aiCurrentModelConfig?.modelId.orEmpty()
            check(model.isNotBlank()) {
                "请先配置当前 AI 模型，或关闭“复用当前 AI 模型”后配置章节净化模型"
            }
            return AiChapterPurifyModelTarget(provider, model)
        }
        val provider = independentProvider
            ?: error("请先配置章节净化独立提供商")
        check(provider.baseUrl.isNotBlank()) { "章节净化独立提供商的 API 地址不能为空" }
        val model = independentModelId
        check(model.isNotBlank()) { "请先配置章节净化独立模型" }
        return AiChapterPurifyModelTarget(provider, model)
    }
}
