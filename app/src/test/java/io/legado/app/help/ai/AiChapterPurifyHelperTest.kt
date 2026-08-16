package io.legado.app.help.ai

import org.junit.Assert.assertEquals
import org.junit.Test

class AiChapterPurifyHelperTest {

    @Test
    fun sanitizeParagraphForModel_removesEmbeddedImageMarkup() {
        val source =
            "广告正文<img src=\"data:image/svg+xml;base64,PHN2Zz4=,{\"click\":\"showCmt(1)\"}\">"

        assertEquals("广告正文", AiChapterPurifyHelper.sanitizeParagraphForModel(source))
    }

    @Test
    fun preprocessor_keepsParagraphLocalTextAndMapsBackToB() {
        val source = "📣 广告！加 群：123"
        val rules = listOf(
            AiChapterPurifyPreprocessRule(
                name = "删除所有空格和符号",
                pattern = "[\\s\\p{P}\\p{S}]+",
                replacement = ""
            )
        )

        val prepared = AiChapterPurifyPreprocessor.apply(source, rules)

        assertEquals("广告加群123", prepared.text)
        assertEquals("广告！加 群", prepared.sourceTextForModelText("广告加群", source))
    }
}
