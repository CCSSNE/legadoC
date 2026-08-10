package io.legado.app.model.analyzeRule

import org.junit.Assert.assertEquals
import org.junit.Test

class AnalyzeRuleRegressionTest {

    @Test
    fun sourceRuleMakeUpDoesNotMutateCachedTemplate() {
        val analyzeRule = AnalyzeRule()
        val sourceRule = analyzeRule.SourceRule("value##{{result}}##x")

        val first = sourceRule.makeUpRule("a")
        val second = sourceRule.makeUpRule("b")

        assertEquals("value", first.rule)
        assertEquals("a", first.replaceRegex)
        assertEquals("value", second.rule)
        assertEquals("b", second.replaceRegex)
    }

    @Test
    fun splitSourceRuleKeepsJsAndWebJsSourceOrder() {
        val analyzeRule = AnalyzeRule()
        val rules = analyzeRule.splitSourceRule("tag.p@text<js>'js'</js>@webjs:return result")

        assertEquals(listOf(AnalyzeRule.Mode.Default, AnalyzeRule.Mode.Js, AnalyzeRule.Mode.WebJs), rules.map { it.mode })
        assertEquals("tag.p@text", rules[0].rule)
        assertEquals("'js'", rules[1].rule)
        assertEquals("return result", rules[2].rule)
    }

    @Test
    fun ruleAnalyzerTrimHandlesEmptyAndBlankRules() {
        RuleAnalyzer("").trim()
        RuleAnalyzer("@").trim()
        RuleAnalyzer("   ").trim()
    }

    @Test
    fun cssRuleWithoutLastValueDefaultsToText() {
        val analyzeByJSoup = AnalyzeByJSoup("<div>alpha</div><div>beta</div>")

        assertEquals(listOf("alpha", "beta"), analyzeByJSoup.getStringList("@CSS:div"))
        assertEquals(listOf("alpha", "beta"), analyzeByJSoup.getStringList("@CSS:div@text"))
    }

    @Test
    fun htmlRuleDoesNotMutateElementsUsedByLaterRules() {
        val analyzeByJSoup = AnalyzeByJSoup("<div><script>bad()</script><span>ok</span></div>")

        assertEquals(
            "<div><span>ok</span></div>",
            analyzeByJSoup.getString("div@html")?.replace(Regex(">\\s+<"), "><")
        )
        assertEquals("<script>bad()</script>", analyzeByJSoup.getString("script@html"))
    }

    @Test
    fun analyzeUrlPagePlaceholderClampsLowAndHighPages() {
        assertEquals("https://example.com/a", AnalyzeUrl("https://example.com/<a,b,c>", page = 0).url)
        assertEquals("https://example.com/a", AnalyzeUrl("https://example.com/<a,b,c>", page = 1).url)
        assertEquals("https://example.com/c", AnalyzeUrl("https://example.com/<a,b,c>", page = 3).url)
        assertEquals("https://example.com/c", AnalyzeUrl("https://example.com/<a,b,c>", page = 10).url)
    }
}
