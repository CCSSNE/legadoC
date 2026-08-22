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

    @Test
    fun cleanSvgUrlOptionImages_removesReviewBubbleByDefault() {
        // 默认（epub 等无评论快照场景）：评论泡 svg+选项 img 依旧清除
        val html = """before<img src="data:image/svg+xml;base64,PHN2Zz4=,{'click':'showNhCmt(1)','style':'TEXT'}">after"""

        val result = ExportImageSanitizer.cleanSvgUrlOptionImages(html)

        assertEquals("beforeafter", result)
    }

    @Test
    fun cleanSvgUrlOptionImages_keepReviewButtons_keepsTextStyleReviewBubble() {
        // txt/txt_zip 导出：style=TEXT 的评论泡必须原样保留（导入后渲染评论泡、命中评论快照）
        val bubble = """<img src="data:image/svg+xml;base64,PHN2Zz4=,{'click':'showNhCmt(1)','style':'TEXT'}">"""
        val html = "正文${bubble}正文"

        val result = ExportImageSanitizer.cleanSvgUrlOptionImages(html, keepReviewButtons = true)

        assertEquals(html, result)
    }

    @Test
    fun cleanSvgUrlOptionImages_keepReviewButtons_stillRemovesNonReviewSvgWithUrlOption() {
        // 保留评论泡的同时，非评论（style 非 TEXT）的 svg+选项 img 仍被清除
        val html = """before<img src="data:image/svg+xml;base64,PHN2Zz4=,{'click':'https://a.test'}">after"""

        val result = ExportImageSanitizer.cleanSvgUrlOptionImages(html, keepReviewButtons = true)

        assertEquals("beforeafter", result)
    }
}
