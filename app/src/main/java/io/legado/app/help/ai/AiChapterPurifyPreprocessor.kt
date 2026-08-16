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
            throw AiChapterPurifyException("AI chapter purification returned blank old text")
        }
        var matchStart = text.indexOf(modelText)
        if (matchStart < 0) {
            throw AiChapterPurifyException(
                "AI chapter purification old text is not an exact substring of the normalized paragraph"
            )
        }
        val secondMatchStart = text.indexOf(modelText, matchStart + 1)
        if (secondMatchStart >= 0) {
            throw AiChapterPurifyException(
                "AI chapter purification old text occurs more than once in normalized paragraph"
            )
        }
        val matchEnd = matchStart + modelText.length
        if (matchEnd > sourceSpans.size) {
            throw AiChapterPurifyException(
                "AI chapter purification normalized text mapping is incomplete"
            )
        }
        val spans = sourceSpans.subList(matchStart, matchEnd)
        val sourceStart = spans.minOf { it.start }
        val sourceEnd = spans.maxOf { it.endExclusive }
        if (sourceStart < 0 || sourceEnd > source.length || sourceStart >= sourceEnd) {
            throw AiChapterPurifyException(
                "AI chapter purification normalized text mapping points outside source paragraph"
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
                "AI input preprocessing rule ${index + 1} name is blank"
            }
            require(rule.pattern.isNotEmpty()) {
                "AI input preprocessing rule ${index + 1} pattern is blank"
            }
            val scopes = rule.effectiveScopes().map { it.lowercase() }
            require(scopes.isNotEmpty()) {
                "AI input preprocessing rule ${index + 1} has no scopes"
            }
            require(scopes.distinct().size == scopes.size) {
                "AI input preprocessing rule ${index + 1} has duplicate scopes"
            }
            require(scopes.all { it in AiChapterPurifyConfig.supportedTypes }) {
                "AI input preprocessing rule ${index + 1} has an unknown scope: $scopes"
            }
            try {
                Pattern.compile(rule.pattern)
            } catch (throwable: Throwable) {
                throw AiChapterPurifyException(
                    "AI input preprocessing rule ${index + 1} has invalid regex: ${rule.name}",
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
                "AI input preprocessing scope is unsupported: $it"
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
                        "AI input preprocessing rule ${indexedRule.index + 1} has invalid regex: ${rule.name}",
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
                            "AI input preprocessing rule matches empty text: ${rule.name}"
                        )
                    }
                    matched = true
                    val outputBefore = output.length
                    try {
                        matcher.appendReplacement(output, rule.replacement)
                    } catch (throwable: Throwable) {
                        throw AiChapterPurifyException(
                            "AI input preprocessing replacement is invalid: ${rule.name}",
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
                    "AI input preprocessing mapping length mismatch: ${rule.name}"
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
