package io.legado.app.help.ai

import java.util.regex.Pattern

data class AiChapterPurifyPreprocessRule(
    val name: String = "",
    val pattern: String = "",
    val replacement: String = "",
    val enabled: Boolean = true,
    val order: Int = 0,
    val scopes: List<String>? = null
)

fun AiChapterPurifyPreprocessRule.effectiveScopes(): List<String> =
    scopes ?: AiChapterPurifyConfig.supportedTypes

fun AiChapterPurifyPreprocessRule.appliesTo(scope: String): Boolean =
    effectiveScopes().any { it.equals(scope, ignoreCase = true) }

data class AiChapterPurifySourceSpan(
    val start: Int,
    val endExclusive: Int
)

data class AiChapterPurifyPreprocessedParagraph(
    val text: String,
    val sourceSpans: List<AiChapterPurifySourceSpan>,
    val appliedRuleNames: List<String>
) {

    fun sourceTextForModelText(modelText: String, source: String): String {
        if (modelText.isBlank()) {
            throw AiChapterPurifyException("AI 返回的净化规则无效：规则原文为空")
        }
        var matchStart = text.indexOf(modelText)
        if (matchStart < 0) {
            throw AiChapterPurifyException(
                "AI 返回的净化规则无效：规则原文与预处理后的段落不匹配"
            )
        }
        val secondMatchStart = text.indexOf(modelText, matchStart + 1)
        if (secondMatchStart >= 0) {
            throw AiChapterPurifyException(
                "AI 返回的净化规则无效：规则原文在段落中出现多次"
            )
        }
        val matchEnd = matchStart + modelText.length
        if (matchEnd > sourceSpans.size) {
            throw AiChapterPurifyException(
                "AI 返回的净化规则无效：规则原文映射不完整"
            )
        }
        val spans = sourceSpans.subList(matchStart, matchEnd)
        val sourceStart = spans.minOf { it.start }
        val sourceEnd = spans.maxOf { it.endExclusive }
        if (sourceStart < 0 || sourceEnd > source.length || sourceStart >= sourceEnd) {
            throw AiChapterPurifyException(
                "AI 返回的净化规则无效：规则原文映射超出段落范围"
            )
        }
        matchStart = sourceStart
        return source.substring(matchStart, sourceEnd)
    }
}

object AiChapterPurifyPreprocessor {

    fun validateRules(rules: List<AiChapterPurifyPreprocessRule>) {
        rules.forEachIndexed { index, rule ->
            require(rule.name.isNotBlank()) {
                "AI 输入预处理规则无效：第 ${index + 1} 条规则缺少名称"
            }
            require(rule.pattern.isNotEmpty()) {
                "AI 输入预处理规则无效：第 ${index + 1} 条规则缺少正则表达式"
            }
            val scopes = rule.effectiveScopes().map { it.lowercase() }
            require(scopes.isNotEmpty()) {
                "AI 输入预处理规则无效：第 ${index + 1} 条规则未设置作用域"
            }
            require(scopes.distinct().size == scopes.size) {
                "AI 输入预处理规则无效：第 ${index + 1} 条规则作用域重复"
            }
            require(scopes.all { it in AiChapterPurifyConfig.supportedTypes }) {
                "AI 输入预处理规则无效：第 ${index + 1} 条规则作用域无效：$scopes"
            }
            try {
                Pattern.compile(rule.pattern)
            } catch (throwable: Throwable) {
                throw AiChapterPurifyException(
                    "AI 输入预处理规则无效：第 ${index + 1} 条规则正则表达式编译失败（${rule.name}）",
                    throwable
                )
            }
        }
    }

    fun apply(
        source: String,
        rules: List<AiChapterPurifyPreprocessRule>
    ): AiChapterPurifyPreprocessedParagraph {
        return apply(source, rules, scope = null)
    }

    fun apply(
        source: String,
        rules: List<AiChapterPurifyPreprocessRule>,
        scope: String?
    ): AiChapterPurifyPreprocessedParagraph {
        scope?.let {
            require(it in AiChapterPurifyConfig.supportedTypes) {
                "AI 输入预处理规则无效：作用域不受支持：$it"
            }
        }
        var current = source
        var sourceSpans = source.indices.map {
            AiChapterPurifySourceSpan(it, it + 1)
        }
        val appliedRuleNames = mutableListOf<String>()

        rules.withIndex()
            .filter { it.value.enabled && (scope == null || it.value.appliesTo(scope)) }
            .sortedWith(compareBy({ it.value.order }, { it.index }))
            .forEach { indexedRule ->
                val rule = indexedRule.value
                val matcher = try {
                    Pattern.compile(rule.pattern).matcher(current)
                } catch (throwable: Throwable) {
                    throw AiChapterPurifyException(
                        "AI 输入预处理规则无效：第 ${indexedRule.index + 1} 条规则正则表达式编译失败（${rule.name}）",
                        throwable
                    )
                }
                val output = StringBuffer()
                val outputSpans = mutableListOf<AiChapterPurifySourceSpan>()
                var lastEnd = 0
                var matched = false
                while (matcher.find()) {
                    if (matcher.start() == matcher.end()) {
                        throw AiChapterPurifyException(
                            "AI 输入预处理规则无效：规则匹配了空文本（${rule.name}）"
                        )
                    }
                    matched = true
                    val outputBefore = output.length
                    try {
                        matcher.appendReplacement(output, rule.replacement)
                    } catch (throwable: Throwable) {
                        throw AiChapterPurifyException(
                            "AI 输入预处理规则无效：替换内容非法（${rule.name}）",
                            throwable
                        )
                    }
                    outputSpans.addAll(sourceSpans.subList(lastEnd, matcher.start()))
                    val unmatchedLength = matcher.start() - lastEnd
                    val replacementLength = output.length - outputBefore - unmatchedLength
                    repeat(replacementLength) {
                        outputSpans.add(
                            AiChapterPurifySourceSpan(matcher.start(), matcher.end())
                        )
                    }
                    lastEnd = matcher.end()
                }
                if (!matched) return@forEach
                matcher.appendTail(output)
                outputSpans.addAll(sourceSpans.subList(lastEnd, current.length))
                check(output.length == outputSpans.size) {
                    "AI 输入预处理规则无效：映射长度不一致（${rule.name}）"
                }
                current = output.toString()
                sourceSpans = outputSpans
                appliedRuleNames.add(rule.name)
            }

        return AiChapterPurifyPreprocessedParagraph(
            text = current,
            sourceSpans = sourceSpans,
            appliedRuleNames = appliedRuleNames
        )
    }
}
