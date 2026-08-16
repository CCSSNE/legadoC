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
}
