package io.legado.app.help.book

import io.legado.app.data.entities.BookChapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioTextFusionTest {

    // 与缓存正文一致的形态：URL 后跟选项 JSON（选项 JSON 内嵌引号，与排版层可解析范围一致）
    private val reviewImg =
        """<img src="https://a.test/btn.png,{"style":"TEXT","click":"showReview()"}">"""

    // ---------- 评论入口提取 ----------

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

    // ---------- usehtml 块归属（按原始 offset 邻接） ----------

    @Test
    fun `paragraph entries collect inline buttons and directly following usehtml block`() {
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
    fun `usehtml block separated by blank line is not attached to previous paragraph`() {
        val content = """
            　　第一段$reviewImg

            <usehtml><center>章节级装饰</center></usehtml>
            　　第二段
            """.trimIndent()
        val entries = AudioTextFusion.parseCommentParagraphs(content)

        // 装饰块与第一段之间隔了空行：不归属；第二段无载荷
        assertEquals(1, entries.size)
        assertEquals("第一段", entries[0].key)
        assertEquals("<usehtml>$reviewImg</usehtml>", entries[0].payload)
    }

    // ---------- overlay：融合 → 应用 → 生命周期 ----------

    @Test
    fun `timed lyric receives fused overlay after matched subtitles`() {
        val textContent = "第一句$reviewImg\n第二句"
        val lyric = "[00:01.00]第一句\n[00:03.00]第二句"

        val insertions = AudioTextFusion.fuseOverlay(textContent, lyric)!!
        assertEquals(1, insertions.size)
        assertEquals("第一句", insertions[0].anchor)
        assertEquals(1, insertions[0].occurrence)
        assertEquals("<usehtml>$reviewImg</usehtml>", insertions[0].payload)

        // overlay 不写进 lyric：原始字幕保持原样
        val fused = AudioTextFusion.applyOverlay(lyric, AudioTextFusion.buildOverlay(insertions))
        val mapping = AudioTextMapping.parse(fused)
        assertEquals(listOf("第一句", "第二句"), mapping.paragraphs)
        assertEquals(listOf(1_000, 3_000), mapping.cues.map { it.startMs })
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
    fun `plain subtitle lyric also receives fused overlay`() {
        val textContent = "第一段<usehtml><img src=\"https://a.test/b.png\"></usehtml>"
        val lyric = "第一段\n第二段"

        val insertions = AudioTextFusion.fuseOverlay(textContent, lyric)!!
        val fused = AudioTextFusion.applyOverlay(lyric, AudioTextFusion.buildOverlay(insertions))

        assertEquals(
            listOf(
                "第一段",
                "<usehtml><img src=\"https://a.test/b.png\"></usehtml>",
                "第二段",
            ),
            AudioTextMapping.parse(fused).displayContents()
        )
    }

    @Test
    fun `matching ignores width whitespace and punctuation`() {
        val textContent = "　　第一章，开端！$reviewImg"
        val lyric = "[00:05.000][00:07.00]第一章开端"

        val insertions = AudioTextFusion.fuseOverlay(textContent, lyric)!!
        val fused = AudioTextFusion.applyOverlay(lyric, AudioTextFusion.buildOverlay(insertions))
        assertTrue(
            fused.contains("[00:05.000][00:07.00]第一章开端\n<usehtml>$reviewImg</usehtml>")
        )
    }

    @Test
    fun `duplicate paragraph texts get distinct anchors in order`() {
        val btn1 = """<img src="https://a.test/1.png,{"style":"TEXT"}">"""
        val btn2 = """<img src="https://a.test/2.png,{"style":"TEXT"}">"""
        val textContent = "重复段$btn1\n重复段$btn2"
        val lyric = "[00:01.00]重复段\n[00:02.00]重复段"

        val insertions = AudioTextFusion.fuseOverlay(textContent, lyric)!!
        assertEquals(2, insertions.size)
        assertEquals(1, insertions[0].occurrence)
        assertEquals(2, insertions[1].occurrence)
        assertTrue(insertions[0].payload.contains("1.png"))
        assertTrue(insertions[1].payload.contains("2.png"))

        val fused = AudioTextFusion.applyOverlay(lyric, AudioTextFusion.buildOverlay(insertions))
        val firstBlockIndex = fused.indexOf("1.png")
        val secondBlockIndex = fused.indexOf("2.png")
        assertTrue(firstBlockIndex >= 0)
        assertTrue(firstBlockIndex < secondBlockIndex)
        assertTrue(fused.indexOf("[00:02.00]") > firstBlockIndex)
    }

    @Test
    fun `overlay insertion is anchored to its occurrence index`() {
        val btn = """<img src="https://a.test/1.png,{"style":"TEXT"}">"""
        val lyric = "[00:01.00]重复段\n[00:02.00]重复段"
        // 只挂“第 2 次出现”的字幕行
        val overlay = AudioTextFusion.buildOverlay(
            listOf(
                AudioTextFusion.OverlayInsertion(anchor = "重复段", occurrence = 2, payload = "<usehtml>$btn</usehtml>")
            )
        )
        val fused = AudioTextFusion.applyOverlay(lyric, overlay)

        assertTrue(fused.indexOf("<usehtml>") > fused.indexOf("[00:02.00]"))
        assertTrue(fused.startsWith("[00:01.00]重复段\n[00:02.00]重复段\n<usehtml>"))
    }

    @Test
    fun `overlay survives audio source lyric refresh`() {
        val textContent = "第一句$reviewImg\n第二句"
        val lyric = "[00:01.00]第一句\n[00:03.00]第二句"

        val insertions = AudioTextFusion.fuseOverlay(textContent, lyric)!!
        val overlayJson = AudioTextFusion.buildOverlay(insertions)

        // 模拟书源刷新：时间轴与格式变化，锚点文字不变
        val refreshed = "[00:00.500]第一句\r\n[00:04.00]第二句"
        val fused = AudioTextFusion.applyOverlay(refreshed, overlayJson)
        assertTrue(fused.contains("[00:00.500]第一句\r\n<usehtml>$reviewImg</usehtml>"))

        // 锚点文字变化后不再匹配：宁可少挂载也不挂错位置
        val changed = "[00:01.00]第一句（修订）\n[00:03.00]第二句"
        assertEquals(changed, AudioTextFusion.applyOverlay(changed, overlayJson))
    }

    @Test
    fun `refusing produces the same overlay and never grows`() {
        val textContent = "第一句$reviewImg\n第二句"
        val lyric = "[00:01.00]第一句\n[00:03.00]第二句"

        // 重新融合以相同输入重新生成 overlay：结果一致，不会出现二次副本
        val first = AudioTextFusion.fuseOverlay(textContent, lyric)!!
        val second = AudioTextFusion.fuseOverlay(textContent, lyric)!!
        assertEquals(first, second)

        val fused = AudioTextFusion.applyOverlay(lyric, AudioTextFusion.buildOverlay(first))
        assertEquals(fused, AudioTextFusion.applyOverlay(lyric, AudioTextFusion.buildOverlay(second)))
    }

    @Test
    fun `overlay json roundtrip keeps insertions`() {
        val insertions = listOf(
            AudioTextFusion.OverlayInsertion(anchor = "第一句", occurrence = 1, payload = "<usehtml>x</usehtml>"),
            AudioTextFusion.OverlayInsertion(anchor = "第二句", occurrence = 2, payload = "<usehtml>y</usehtml>"),
        )
        assertEquals(insertions, AudioTextFusion.parseOverlay(AudioTextFusion.buildOverlay(insertions)))
    }

    @Test
    fun `lyric without matching paragraphs produces no overlay`() {
        val textContent = "完全不同的段落$reviewImg"
        val lyric = "[00:01.00]第一句\n[00:03.00]第二句"

        assertNull(AudioTextFusion.fuseOverlay(textContent, lyric))
    }

    @Test
    fun `existing usehtml blocks are preserved untouched`() {
        val textContent = "第二段$reviewImg"
        val lyric = "[00:01.00]第一句\n<usehtml>[12:34]原生评论区按钮</usehtml>\n[00:03.00]第二段"

        val insertions = AudioTextFusion.fuseOverlay(textContent, lyric)!!
        val fused = AudioTextFusion.applyOverlay(lyric, AudioTextFusion.buildOverlay(insertions))
        assertTrue(fused.contains("<usehtml>[12:34]原生评论区按钮</usehtml>"))
        assertTrue(fused.contains("<usehtml>$reviewImg</usehtml>"))
    }

    // ---------- 章节匹配 ----------

    @Test
    fun `chapters pair by normalized title then by chapter number`() {
        fun chapter(url: String, title: String, index: Int) =
            BookChapter(url = url, title = title, index = index)

        val textChapters = listOf(
            chapter("t0", "第一章 开端", 0),
            chapter("t1", "第2章：冲突", 1),
        )
        val audioChapters = listOf(
            chapter("a0", "第一章　开端", 0),
            chapter("a1", "第二章 冲突", 1),
        )

        val pairs = AudioTextFusion.pairChapters(textChapters, audioChapters)

        assertEquals(2, pairs.size)
        // 标题归一化相等
        assertEquals("a0", pairs.first { it.first.url == "t0" }.second.url)
        // 标题不同但章节号相等（2 == 二）
        assertEquals("a1", pairs.first { it.first.url == "t1" }.second.url)
    }

    @Test
    fun `chapters never pair by raw index when titles and numbers mismatch`() {
        fun chapter(url: String, title: String, index: Int) =
            BookChapter(url = url, title = title, index = index)

        // 音频侧多了一个序章且缺最后一章：旧“同 index 兜底”会把第一章配给序章
        val textChapters = listOf(
            chapter("t0", "第一章", 0),
            chapter("t1", "第二章", 1),
            chapter("t2", "第三章", 2),
        )
        val audioChapters = listOf(
            chapter("a0", "序章", 0),
            chapter("a1", "第一章", 1),
            chapter("a2", "第二章", 2),
        )

        val pairs = AudioTextFusion.pairChapters(textChapters, audioChapters)

        assertEquals(2, pairs.size)
        assertEquals("a1", pairs.first { it.first.url == "t0" }.second.url)
        assertEquals("a2", pairs.first { it.first.url == "t1" }.second.url)
        // 第三章两侧都没有可匹配章节：不硬配
        assertTrue(pairs.none { it.first.url == "t2" })
        assertTrue(pairs.none { it.second.url == "a0" })
    }

    @Test
    fun `chapter pairing drops inconsistent neighbor order`() {
        fun chapter(url: String, title: String, index: Int) =
            BookChapter(url = url, title = title, index = index)

        // 两侧章节顺序相反：标题各能匹配，但按正文顺序 audio 序号递减，
        // 邻章一致性验证应丢弃后一个，宁可少融合
        val textChapters = listOf(
            chapter("t0", "第一卷 开端", 0),
            chapter("t1", "第二卷 开端", 1),
        )
        val audioChapters = listOf(
            chapter("a0", "第二卷 开端", 0),
            chapter("a1", "第一卷 开端", 1),
        )

        val pairs = AudioTextFusion.pairChapters(textChapters, audioChapters)

        assertEquals(1, pairs.size)
        assertEquals("a1", pairs.single().second.url)
    }
}