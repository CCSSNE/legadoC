package io.legado.app.help.book

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioTextMappingTest {

    @Test
    fun `timed lyrics become plain paragraphs with millisecond cues`() {
        val mapping = AudioTextMapping.parse(
            """
            [ar:作者]
            [00:01.20]第一句
            [00:03.045][00:05.50]第二句
            """.trimIndent()
        )

        assertEquals(listOf("第一句", "第二句", "第二句"), mapping.paragraphs)
        assertEquals(listOf(1_200, 3_045, 5_500), mapping.cues.map { it.startMs })
        assertTrue(mapping.hasTimeMapping)
    }

    @Test
    fun `time and paragraph lookup use the same ordered cue list`() {
        val mapping = AudioTextMapping.parse(
            """
            [00:02.00]甲
            [00:04.00]乙
            [00:08.00]丙
            """.trimIndent()
        )

        assertEquals(0, mapping.paragraphAt(0))
        assertEquals(0, mapping.paragraphAt(3_999))
        assertEquals(1, mapping.paragraphAt(4_000))
        assertEquals(2, mapping.paragraphAt(9_000))
        assertEquals(4_000, mapping.timeForParagraph(1))
        assertNull(mapping.timeForParagraph(3))
    }

    @Test
    fun `plain subtitles remain readable without inventing time mapping`() {
        val mapping = AudioTextMapping.parse(
            """
            第一段

            第二段
            """.trimIndent()
        )

        assertEquals(listOf("第一段", "第二段"), mapping.paragraphs)
        assertFalse(mapping.hasTimeMapping)
        assertNull(mapping.paragraphAt(1_000))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `invalid lrc seconds are rejected`() {
        AudioTextMapping.parse("[00:61.00]非法字幕")
    }
}
