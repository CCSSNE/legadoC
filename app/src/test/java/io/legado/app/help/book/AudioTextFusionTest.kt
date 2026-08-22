package io.legado.app.help.book

import io.legado.app.data.entities.BookChapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioTextFusionTest {

    // 与缓存正文一致的形态：URL 后跟选项 JSON（无 > 字符，与排版层可解析范围一致）
    private val reviewImg =
        """<img src="https://a.test/btn.png,{"style":"TEXT","click":"showReview()"}">"""

    @Test
    fun `inline TEXT style img is extracted as comment payload`() {
        val line = "　　正文段落$reviewImg"
        val (text, buttons) = AudioTextFusion.splitInlineCommentButtons(line)
        assertEquals("　　正文段落", text)
        assertEquals(listOf(reviewImg), buttons)
    }

    @Test
    fun `plain illustration img is not treated as comment button`() {
        val line = """<img src="https://a.test/pic.jpg">插图段落"""
        val (text, buttons) = AudioTextFusion.splitInlineCommentButtons(line)
        assertEquals("插图段落", text)
        assertTrue(buttons.isEmpty())
    }

    @Test
    fun `paragraph entries collect inline buttons and following usehtml block`() {
        val content = """
            <usehtml>章节级装饰</usehtml>
            　　第一段$reviewImg
            <usehtml><center>评论按钮</center></usehtml>
            　　第二段无评论
            
            　　第三段${reviewImg}尾注
            """.trimIndent()
        val entries = AudioTextFusion.parseCommentParagraphs(content)

        assertEquals(2, entries.size)
        assertEquals("第一段", entries[0].key)
        assertTrue(entries[0].payload.contains("<usehtml>$reviewImg</usehtml>"))
        assertTrue(entries[0].payload.contains("<center>评论按钮</center>"))
        assertEquals("第三段尾注", entries[1].key)
    }

    @Test
    fun `timed lyric lines receive fused blocks after matched subtitles`() {
        val textContent = """
            　　第一句$reviewImg
            　　第二句
            """.trimIndent()
        val lyric = """
            [00:01.00]第一句
            [00:03.00]第二句
            """.trimIndent()

        val fusion = AudioTextFusion.fuseLyric(textContent, lyric)!!

        assertEquals(1, fusion.migratedEntries)
        val mapping = AudioTextMapping.parse(fusion.newLyric)
        // cue 时间轴不受插入影响
        assertEquals(listOf("第一句", "第二句"), mapping.paragraphs)
        assertEquals(listOf(1_000, 3_000), mapping.cues.map { it.startMs })
        // 显示顺序：字幕行、评论块原位交错
        assertEquals(
            listOf(
                "第一句",
                "<usehtml>$reviewImg</usehtml>",
                "第二句",
            ),
            mapping.displayContents()
        )
    }

    @Test
    fun `plain subtitle lyric also receives fused blocks`() {
        val textContent = "第一段<usehtml><img src=\"https://a.test/b.png\"></usehtml>"
        val lyric = "第一段\n第二段"

        val fusion = AudioTextFusion.fuseLyric(textContent, lyric)!!

        assertEquals(
            listOf(
                "第一段",
                "<usehtml><img src=\"https://a.test/b.png\"></usehtml>",
                "第二段",
            ),
            AudioTextMapping.parse(fusion.newLyric).displayContents()
        )
    }

    @Test
    fun `matching ignores width whitespace and punctuation`() {
        val textContent = "　　第一章，开端！$reviewImg"
        val lyric = "[00:05.000][00:07.00]第一章开端"

        val fusion = AudioTextFusion.fuseLyric(textContent, lyric)!!
        assertTrue(
            fusion.newLyric.contains("[00:05.000][00:07.00]第一章开端\n<usehtml>$reviewImg</usehtml>")
        )
    }

    @Test
    fun `duplicate paragraph texts are consumed in order`() {
        val btn1 = """<img src="https://a.test/1.png,{"style":"TEXT"}">"""
        val btn2 = """<img src="https://a.test/2.png,{"style":"TEXT"}">"""
        val textContent = "重复段$btn1\n重复段$btn2"
        val lyric = "[00:01.00]重复段\n[00:02.00]重复段"

        val fusion = AudioTextFusion.fuseLyric(textContent, lyric)!!
        assertEquals(2, fusion.migratedEntries)
        val firstBlockIndex = fusion.newLyric.indexOf("1.png")
        val secondBlockIndex = fusion.newLyric.indexOf("2.png")
        assertTrue(firstBlockIndex >= 0)
        assertTrue(firstBlockIndex < secondBlockIndex)
        // 第一条块插在第一个字幕之后、第二个字幕之前
        assertTrue(fusion.newLyric.indexOf("[00:02.00]") > firstBlockIndex)
    }

    @Test
    fun `refusing an already fused lyric changes nothing`() {
        val textContent = "第一句$reviewImg"
        val lyric = "[00:01.00]第一句\n<usehtml>$reviewImg</usehtml>\n[00:03.00]第二句"

        assertNull(AudioTextFusion.fuseLyric(textContent, lyric))
    }

    @Test
    fun `existing usehtml blocks are preserved untouched`() {
        val textContent = "第二段$reviewImg"
        val lyric = "[00:01.00]第一句\n<usehtml>[12:34]原生评论区按钮</usehtml>\n[00:03.00]第二段"

        val fusion = AudioTextFusion.fuseLyric(textContent, lyric)!!
        assertTrue(fusion.newLyric.contains("<usehtml>[12:34]原生评论区按钮</usehtml>"))
        assertTrue(fusion.newLyric.contains("<usehtml>$reviewImg</usehtml>"))
    }

    @Test
    fun `lyric without matching paragraphs stays unchanged`() {
        val textContent = "完全不同的段落$reviewImg"
        val lyric = "[00:01.00]第一句\n[00:03.00]第二句"

        assertNull(AudioTextFusion.fuseLyric(textContent, lyric))
    }

    @Test
    fun `chapters pair by normalized title first then by index`() {
        fun chapter(url: String, title: String, index: Int) =
            BookChapter(url = url, title = title, index = index)

        val textChapters = listOf(
            chapter("t0", "第一章 开端", 0),
            chapter("t1", "第2章：冲突", 1),
            chapter("t2", "番外", 2),
        )
        val audioChapters = listOf(
            chapter("a0", "第一章　开端", 0),
            chapter("a1", "第二章 冲突", 1),
        )

        val pairs = AudioTextFusion.pairChapters(textChapters, audioChapters)

        assertEquals("a0", pairs.first { it.first.url == "t0" }.second.url)
        // “第2章”与“第二章”归一化后数字不同，标题不匹配 → 同序号兜底配对
        assertEquals("a1", pairs.first { it.first.url == "t1" }.second.url)
        // 音频侧没有可配对的第三章
        assertEquals(2, pairs.size)
    }
}
