package io.legado.app.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportImageSanitizerTest {

    @Test
    fun cleanSvgUrlOptionImages_removesSvgDataImageWithUrlOption() {
        val html = """before<img src="data:image/svg+xml;base64,PHN2Zz4=,{"click":"https://a.test"}">after"""

        val result = ExportImageSanitizer.cleanSvgUrlOptionImages(html)

        assertEquals("beforeafter", result)
    }

    @Test
    fun normalizeSrc_stripsUrlOptionFromPngDataImage() {
        val src = """data:image/png;base64,iVBORw0KGgo=,{"click":"https://a.test"}"""

        val result = ExportImageSanitizer.normalizeSrc(src)

        assertEquals("data:image/png;base64,iVBORw0KGgo=", result.src)
        assertTrue(result.hasUrlOption)
        assertFalse(result.removeTag)
    }

    @Test
    fun cleanSvgUrlOptionImages_keepsNormalImagesAndSvgWithoutUrlOption() {
        val html = """
            <img src="https://example.com/a.jpg">
            <img src="data:image/png;base64,iVBORw0KGgo=">
            <img src="data:image/svg+xml;base64,PHN2Zz4=">
        """.trimIndent()

        val result = ExportImageSanitizer.cleanSvgUrlOptionImages(html)

        assertEquals(html, result)
    }
}
