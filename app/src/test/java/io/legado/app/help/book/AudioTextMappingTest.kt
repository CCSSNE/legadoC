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

    @Test
    fun `plain subtitles keep usehtml blocks out of paragraphs and into display order`() {
        val mapping = AudioTextMapping.parse(
            """
            第一段正文
            <usehtml>评论按钮</usehtml>
            第二段正文
            """.trimIndent()
        )

        assertEquals(listOf("第一段正文", "第二段正文"), mapping.paragraphs)
        assertFalse(mapping.hasTimeMapping)
        assertEquals(
            listOf("第一段正文", "<usehtml>评论按钮</usehtml>", "第二段正文"),
            mapping.displayContents()
        )
    }

    @Test
    fun `multiline usehtml block is preserved as one structure`() {
        val block = "<usehtml>\n<center>评论区按钮</center>\n</usehtml>"
        val mapping = AudioTextMapping.parse("第一段\n$block\n第二段")

        assertEquals(listOf("第一段", "第二段"), mapping.paragraphs)
        assertEquals(listOf("第一段", block, "第二段"), mapping.displayContents())
    }

    @Test
    fun `timed lyrics ignore time-like text inside usehtml blocks`() {
        val mapping = AudioTextMapping.parse(
            """
            [00:01.00]第一句
            <usehtml>[12:34]评论按钮</usehtml>
            [00:03.00]第二句
            """.trimIndent()
        )

        assertEquals(listOf("第一句", "第二句"), mapping.paragraphs)
        assertEquals(listOf(1_000, 3_000), mapping.cues.map { it.startMs })
        assertEquals(
            listOf("第一句", "<usehtml>[12:34]评论按钮</usehtml>", "第二句"),
            mapping.displayContents()
        )
    }

    @Test
    fun `inline usehtml inside a timed line does not pollute cues`() {
        val mapping = AudioTextMapping.parse(
            "[00:01.00]第一句<usehtml>按钮</usehtml>\n[00:03.00]第二句"
        )

        assertEquals(listOf("第一句", "第二句"), mapping.paragraphs)
        assertEquals(listOf(1_000, 3_000), mapping.cues.map { it.startMs })
        assertEquals(
            listOf("第一句", "<usehtml>按钮</usehtml>", "第二句"),
            mapping.displayContents()
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `invalid lrc seconds are rejected`() {
        AudioTextMapping.parse("[00:61.00]非法字幕")
    }

    @Test
    fun `timed lyrics bind around a structural usehtml paragraph`() {
        val mapping = AudioTextMapping.parse(
            """
            [00:01.00]First line
            <usehtml>Comment button</usehtml>
            [00:03.00]Second line
            """.trimIndent()
        )

        val binding = mapping.bindLayout(
            listOf(
                AudioTextMapping.LayoutParagraph(0, "Chapter title", isStructural = true),
                AudioTextMapping.LayoutParagraph(1, "First line", isStructural = false),
                AudioTextMapping.LayoutParagraph(2, "Comment button", isStructural = true),
                AudioTextMapping.LayoutParagraph(3, "Second line", isStructural = false),
            )
        )

        assertEquals(2, binding.paragraphCount)
        assertEquals(1_000, binding.timeForLayoutParagraph(0))
        assertEquals(1_000, binding.timeForLayoutParagraph(1))
        assertEquals(3_000, binding.timeForLayoutParagraph(3))
        assertEquals(1, binding.layoutParagraphAt(1_000))
        assertEquals(3, binding.layoutParagraphAt(3_000))
        assertEquals(1, binding.layoutParagraphAt(2_000))
    }

    @Test
    fun `plain subtitles with usehtml bind only to real body paragraphs`() {
        val mapping = AudioTextMapping.parse(
            """
            第一段正文
            <usehtml>评论按钮</usehtml>
            第二段正文
            """.trimIndent()
        )

        val binding = mapping.bindLayout(
            listOf(
                AudioTextMapping.LayoutParagraph(0, "Chapter title", isStructural = true),
                AudioTextMapping.LayoutParagraph(1, "第一段正文", isStructural = false),
                AudioTextMapping.LayoutParagraph(2, "评论按钮", isStructural = true),
                AudioTextMapping.LayoutParagraph(3, "第二段正文", isStructural = false),
            )
        )

        assertEquals(2, binding.paragraphCount)
        assertNull(binding.layoutParagraphAt(1_000))
        assertNull(binding.timeForLayoutParagraph(1))
    }

    @Test
    fun `layout binding excludes structural title and preserves real paragraph indexes`() {
        val mapping = AudioTextMapping.parse(
            """
            [00:01.00]First line
            [00:03.00]Second line
            """.trimIndent()
        )

        val binding = mapping.bindLayout(
            listOf(
                AudioTextMapping.LayoutParagraph(0, "Chapter title", isStructural = true),
                AudioTextMapping.LayoutParagraph(1, "\u3000\u3000First line", isStructural = false),
                AudioTextMapping.LayoutParagraph(2, "\u3000\u3000Second line", isStructural = false),
            )
        )

        assertEquals(1_000, binding.timeForLayoutParagraph(0))
        assertEquals(1_000, binding.timeForLayoutParagraph(1))
        assertEquals(3_000, binding.timeForLayoutParagraph(2))
        assertEquals(1, binding.layoutParagraphAt(1_000))
        assertEquals(2, binding.layoutParagraphAt(3_000))
    }

    @Test
    fun `timed lyrics with out-of-order timestamps keep display and mapping bindable`() {
        val mapping = AudioTextMapping.parse(
            """
            [00:10]第二句
            <usehtml>评论按钮</usehtml>
            [00:01]第一句
            """.trimIndent()
        )

        // 显示顺序尊重原文：usehtml 原位保留，不按 cue 时间重排
        assertEquals(
            listOf("第二句", "<usehtml>评论按钮</usehtml>", "第一句"),
            mapping.displayContents()
        )
        // usehtml 不生成 cue；cues/paragraphs 按时间排序（legacy 时间维度语义不变）
        assertEquals(listOf("第一句", "第二句"), mapping.cues.map { it.text })
        assertEquals(listOf(1_000, 10_000), mapping.cues.map { it.startMs })
        assertEquals(listOf("第一句", "第二句"), mapping.paragraphs)

        // 页面真实正文段（TextChapter 渲染后）：标题(structural)、第二句、按钮(structural)、第一句
        val binding = mapping.bindLayout(
            listOf(
                AudioTextMapping.LayoutParagraph(0, "Chapter title", isStructural = true),
                AudioTextMapping.LayoutParagraph(1, "第二句", isStructural = false),
                AudioTextMapping.LayoutParagraph(2, "评论按钮", isStructural = true),
                AudioTextMapping.LayoutParagraph(3, "第一句", isStructural = false),
            )
        )
        assertEquals(2, binding.paragraphCount)

        // 时间 → 页面段落（反向定位）：00:01 第一句、00:10 第二句
        assertEquals(3, binding.layoutParagraphAt(1_000))
        assertEquals(1, binding.layoutParagraphAt(10_000))

        // 页面段落 → 时间（指哪听哪）：第二句段 → 10000ms、第一句段 → 1000ms
        assertEquals(10_000, binding.timeForLayoutParagraph(1))
        assertEquals(1_000, binding.timeForLayoutParagraph(3))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `layout binding rejects content mismatches`() {
        val mapping = AudioTextMapping.parse("[00:01.00]Expected")

        mapping.bindLayout(
            listOf(
                AudioTextMapping.LayoutParagraph(0, "Different", isStructural = false)
            )
        )
    }
}
