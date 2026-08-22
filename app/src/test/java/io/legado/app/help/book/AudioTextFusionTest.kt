package io.legado.app.help.book

import io.legado.app.data.entities.BookChapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioTextFusionTest {

    // 与缓存正文一致的形态：URL 后跟选项 JSON（选项 JSON 内嵌引号，与排版层可解析范围一致）
    private val reviewImg =
        """<img src="https://a.test/btn.png,{"style":"TEXT","click":"showReview()"}">"""

    private fun chapter(url: String, title: String, index: Int) =
        BookChapter(url = url, title = title, index = index)

    private val fakeOverlayJson =
        """[{"anchor":"x","occurrence":1,"payload":"<usehtml>x</usehtml>"}]"""

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

    @Test
    fun `url containing comma is still recognized as review button`() {
        val line = """段落<img src="https://a.test/a,b.png,{"style":"TEXT"}">"""
        val (text, buttons) = AudioTextFusion.splitInlineCommentButtons(line)
        assertEquals("段落", text)
        assertEquals(1, buttons.size)

        val insertions = AudioTextFusion.fuseOverlay(line, "[00:01.00]段落")!!
        assertEquals(1, insertions.size)
        assertEquals("段落", insertions[0].anchor)
    }

    @Test
    fun `single quoted style option is recognized as review button`() {
        // 书源常用单引号 JSON（与阅读页渲染一致），此前手写双引号正则漏判
        val svg = "data:image/svg+xml;base64,PHN2ZyB4bWxucz0iaHR0cDovL3d3dy53My5vcmcvMjAwMC9zdmciPjx0ZXh0PjI8L3RleHQ+PC9zdmc+"
        val img = """<img src="$svg,{'click':'showCmt("a","b")','style':'TEXT'}">"""
        val line = "这一段有评论$img"
        val (text, buttons) = AudioTextFusion.splitInlineCommentButtons(line)
        assertEquals("这一段有评论", text)
        assertEquals(1, buttons.size)
        assertTrue(buttons.single().contains("showCmt"))

        // 端到端：能挂载到对应字幕行
        val insertions = AudioTextFusion.fuseOverlay(line, "[00:01.00]这一段有评论")!!
        assertEquals(1, insertions.size)
        assertEquals("这一段有评论", insertions[0].anchor)
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

        // 无载荷的第二段也参与占位
        assertEquals(3, entries.size)
        assertEquals("第一段", entries[0].key)
        assertTrue(entries[0].payload.contains("<usehtml>$reviewImg</usehtml>"))
        assertTrue(entries[0].payload.contains("<center>评论按钮</center>"))
        assertEquals("第二段无评论", entries[1].key)
        assertEquals("", entries[1].payload)
        assertEquals("第三段尾注", entries[2].key)
        assertTrue(entries[2].payload.isNotEmpty())
    }

    @Test
    fun `usehtml block separated by blank line is not attached to previous paragraph`() {
        val content = """
            　　第一段$reviewImg

            <usehtml><center>章节级装饰</center></usehtml>
            　　第二段
            """.trimIndent()
        val entries = AudioTextFusion.parseCommentParagraphs(content)

        // 装饰块与第一段之间隔了空行：不归属；第二段无载荷但保留占位
        assertEquals(2, entries.size)
        assertEquals("第一段", entries[0].key)
        assertEquals("<usehtml>$reviewImg</usehtml>", entries[0].payload)
        assertEquals("第二段", entries[1].key)
        assertEquals("", entries[1].payload)
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
    fun `paragraph without comment still occupies occurrence`() {
        val textContent = "重复段\n重复段$reviewImg"
        val lyric = "[00:01.00]重复段\n[00:02.00]重复段"

        val insertions = AudioTextFusion.fuseOverlay(textContent, lyric)!!
        // 只有第二个段落有评论：必须挂到第 2 次出现的“重复段”
        assertEquals(1, insertions.size)
        assertEquals(2, insertions[0].occurrence)
    }

    @Test
    fun `three identical paragraphs comments on first and third`() {
        val btn1 = """<img src="https://a.test/1.png,{"style":"TEXT"}">"""
        val btn3 = """<img src="https://a.test/3.png,{"style":"TEXT"}">"""
        val textContent = "重复段$btn1\n重复段\n重复段$btn3"
        val lyric = "[00:01.00]重复段\n[00:02.00]重复段\n[00:03.00]重复段"

        val insertions = AudioTextFusion.fuseOverlay(textContent, lyric)!!
        assertEquals(2, insertions.size)
        assertEquals(1, insertions[0].occurrence)
        assertEquals(3, insertions[1].occurrence)
        assertTrue(insertions[0].payload.contains("1.png"))
        assertTrue(insertions[1].payload.contains("3.png"))
    }

    @Test
    fun `overlay insertion is anchored to its occurrence index`() {
        val btn = """<img src="https://a.test/1.png,{"style":"TEXT"}">"""
        val lyric = "[00:01.00]重复段\n[00:02.00]重复段"
        val overlay = AudioTextFusion.buildOverlay(
            listOf(
                AudioTextFusion.OverlayInsertion(
                    anchor = "重复段", occurrence = 2,
                    payload = "<usehtml>$btn</usehtml>"
                )
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

        val refreshed = "[00:00.500]第一句\r\n[00:04.00]第二句"
        val fused = AudioTextFusion.applyOverlay(refreshed, overlayJson)
        assertTrue(fused.contains("[00:00.500]第一句\r\n<usehtml>$reviewImg</usehtml>"))

        val changed = "[00:01.00]第一句（修订）\n[00:03.00]第二句"
        assertEquals(changed, AudioTextFusion.applyOverlay(changed, overlayJson))
    }

    @Test
    fun `applyOverlay is idempotent for already mounted position`() {
        val textContent = "第一句$reviewImg\n第二句"
        val lyric = "[00:01.00]第一句\n[00:03.00]第二句"
        val overlayJson = AudioTextFusion.buildOverlay(
            AudioTextFusion.fuseOverlay(textContent, lyric)!!
        )

        val first = AudioTextFusion.applyOverlay(lyric, overlayJson)
        val second = AudioTextFusion.applyOverlay(first, overlayJson)
        assertEquals(first, second)
    }

    @Test
    fun `legacy inline payload in lyric does not double mount`() {
        // 模拟旧版本直写后的 lyric：锚点行后已紧跟同一 payload 块
        val lyric = "[00:01.00]第一句\n<usehtml>$reviewImg</usehtml>\n[00:03.00]第二句"
        val overlayJson = AudioTextFusion.buildOverlay(
            listOf(
                AudioTextFusion.OverlayInsertion(
                    anchor = "第一句", occurrence = 1,
                    payload = "<usehtml>$reviewImg</usehtml>"
                )
            )
        )

        assertEquals(lyric, AudioTextFusion.applyOverlay(lyric, overlayJson))
    }

    @Test
    fun `refusing produces the same overlay and never grows`() {
        val textContent = "第一句$reviewImg\n第二句"
        val lyric = "[00:01.00]第一句\n[00:03.00]第二句"

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

    // ---------- P0 整本书 reconcile ----------

    @Test
    fun `refusion clears overlay when text comments removed`() {
        val textChapters = listOf(chapter("t0", "第一章", 0))
        val audioChapters = listOf(chapter("a0", "第一章", 0))

        // 第一轮：文字书有评论 → 期望保存 overlay
        val plan1 = AudioTextFusion.planFusionWrites(
            textChapters, audioChapters,
            textBookUrl = "textBookUrl",
            hasTextContent = { true },
            getTextContent = { "第一章$reviewImg" },
            hasAudioContent = { true },
            getLyric = { "[00:01.00]第一章" },
            getCurrentOverlay = { "" },
        )
        assertEquals(1, plan1.writes.size)
        assertNotNull(plan1.writes[0].insertions)

        // 第二轮：文字书评论被删除 → 旧 overlay 必须被清除，而不是残留
        val plan2 = AudioTextFusion.planFusionWrites(
            textChapters, audioChapters,
            textBookUrl = "textBookUrl",
            hasTextContent = { true },
            getTextContent = { "第一章" },
            hasAudioContent = { true },
            getLyric = { "[00:01.00]第一章" },
            getCurrentOverlay = { fakeOverlayJson },
        )
        assertEquals(1, plan2.writes.size)
        assertNull(plan2.writes[0].insertions)
    }

    @Test
    fun `refusion clears stale overlay from chapters no longer carrying comments`() {
        val textChapters = listOf(chapter("t0", "第一章", 0), chapter("t1", "第二章", 1))
        val audioChapters = listOf(chapter("a0", "第一章", 0), chapter("a1", "第二章", 1))

        // 第一轮：两章都有评论
        val plan1 = AudioTextFusion.planFusionWrites(
            textChapters, audioChapters,
            textBookUrl = "textBookUrl",
            hasTextContent = { true },
            getTextContent = { if (it.url == "t0") "第一章$reviewImg" else "第二章$reviewImg" },
            hasAudioContent = { true },
            getLyric = { if (it.url == "a0") "[00:01.00]第一章" else "[00:01.00]第二章" },
            getCurrentOverlay = { "" },
        )
        assertEquals(2, plan1.writes.count { it.insertions != null })

        // 第二轮：第一章还有评论、第二章评论被删除 → 第二章旧 overlay 被清
        val plan2 = AudioTextFusion.planFusionWrites(
            textChapters, audioChapters,
            textBookUrl = "textBookUrl",
            hasTextContent = { true },
            getTextContent = { if (it.url == "t0") "第一章$reviewImg" else "第二章" },
            hasAudioContent = { true },
            getLyric = { if (it.url == "a0") "[00:01.00]第一章" else "[00:01.00]第二章" },
            getCurrentOverlay = { fakeOverlayJson },
        )
        assertEquals(2, plan2.writes.size)
        assertNotNull(plan2.writes.first { it.chapter.url == "a0" }.insertions)
        assertNull(plan2.writes.first { it.chapter.url == "a1" }.insertions)
    }

    @Test
    fun `chapter no longer paired keeps old overlay`() {
        val textChapters = listOf(chapter("t0", "第一章", 0))
        val audioChapters = listOf(chapter("a0", "第一章", 0))

        // 第一轮正常融合
        val plan1 = AudioTextFusion.planFusionWrites(
            textChapters, audioChapters,
            textBookUrl = "textBookUrl",
            hasTextContent = { true },
            getTextContent = { "第一章$reviewImg" },
            hasAudioContent = { true },
            getLyric = { "[00:01.00]第一章" },
            getCurrentOverlay = { "" },
        )
        assertNotNull(plan1.writes.single().insertions)

        // 第二轮：章节无法再配对（标题与章节号都对不上）→ 无法确认，
        // 旧 overlay 必须保持，不能被当作“评论已删除”清掉
        val renamedText = listOf(chapter("t0", "番外", 0))
        val plan2 = AudioTextFusion.planFusionWrites(
            renamedText, audioChapters,
            textBookUrl = "textBookUrl",
            hasTextContent = { true },
            getTextContent = { "第一章$reviewImg" },
            hasAudioContent = { true },
            getLyric = { "[00:01.00]第一章" },
            getCurrentOverlay = { fakeOverlayJson },
        )
        assertEquals(0, plan2.pairedChapters)
        assertTrue(plan2.writes.isEmpty())
    }

    @Test
    fun `refusing identical inputs produces identical plan`() {
        val textChapters = listOf(chapter("t0", "第一章", 0))
        val audioChapters = listOf(chapter("a0", "第一章", 0))
        fun plan() = AudioTextFusion.planFusionWrites(
            textChapters, audioChapters,
            textBookUrl = "textBookUrl",
            hasTextContent = { true },
            getTextContent = { "第一章$reviewImg" },
            hasAudioContent = { true },
            getLyric = { "[00:01.00]第一章" },
            getCurrentOverlay = { fakeOverlayJson },
        )
        val plan1 = plan()
        val plan2 = plan()
        assertEquals(plan1.writes, plan2.writes)
        assertEquals(1, plan1.writes.size)
        assertNotNull(plan1.writes.single().insertions)
    }

    // ---------- 章节匹配 ----------

    @Test
    fun `chapters pair by normalized title then by chapter number`() {
        val textChapters = listOf(
            chapter("t0", "第一章 开端", 0),
            chapter("t1", "第一卷 第2章：冲突", 1),
        )
        val audioChapters = listOf(
            chapter("a0", "第一章　开端", 0),
            chapter("a1", "第一卷 第二章 冲突", 1),
        )

        val pairs = AudioTextFusion.pairChapters(textChapters, audioChapters)

        assertEquals(2, pairs.size)
        assertEquals("a0", pairs.first { it.first.url == "t0" }.second.url)
        // 标题不同但卷一致、章节号相等（2 == 二）
        assertEquals("a1", pairs.first { it.first.url == "t1" }.second.url)
    }

    @Test
    fun `chapters never pair by raw index when titles and numbers mismatch`() {
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
        assertTrue(pairs.none { it.first.url == "t2" })
        assertTrue(pairs.none { it.second.url == "a0" })
    }

    @Test
    fun `chapter pairing drops inconsistent neighbor order`() {
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

    @Test
    fun `volume mismatch never pairs across volumes`() {
        // 标题写法不同（卷一 vs 第壹卷）→ 无标题锚点，只能靠卷信息约束
        val textChapters = listOf(
            chapter("t0", "卷一 第一章", 0),
            chapter("t1", "卷一 第二章", 1),
            chapter("t2", "卷二 第一章", 2),
        )
        val audioChapters = listOf(
            chapter("a0", "第壹卷 第一章", 0),
            chapter("a1", "第贰卷 第一章", 1),
            chapter("a2", "第贰卷 第二章", 2),
        )

        val pairs = AudioTextFusion.pairChapters(textChapters, audioChapters)

        assertEquals(2, pairs.size)
        assertEquals("a0", pairs.first { it.first.url == "t0" }.second.url)
        assertEquals("a1", pairs.first { it.first.url == "t2" }.second.url)
        // 卷一第二章（章节号 2）绝不能配到第贰卷第二章
        assertTrue(pairs.none { it.first.url == "t1" })
        assertTrue(pairs.none { it.second.url == "a2" })
    }

    @Test
    fun `missing chapter in a volume does not shift following volume`() {
        // 标题写法不同（卷一 vs 第一卷）但卷号解析一致；卷一缺第二章
        val textChapters = listOf(
            chapter("t0", "卷一 第一章", 0),
            chapter("t1", "卷一 第二章", 1),
            chapter("t2", "卷二 第一章", 2),
        )
        val audioChapters = listOf(
            chapter("a0", "第一卷 第一章", 0),
            chapter("a1", "第二卷 第一章", 1),
            chapter("a2", "第二卷 第二章", 2),
        )

        val pairs = AudioTextFusion.pairChapters(textChapters, audioChapters)

        assertEquals(2, pairs.size)
        assertEquals("a0", pairs.first { it.first.url == "t0" }.second.url)
        assertEquals("a1", pairs.first { it.first.url == "t2" }.second.url)
        // 卷一第二章跳过后，卷二第一章仍配卷二第一章，不被错位拉到卷二第二章
        assertTrue(pairs.none { it.first.url == "t1" })
        assertTrue(pairs.none { it.second.url == "a2" })
    }

    @Test
    fun `number fallback is restricted to local anchor window`() {
        // 音频侧章节顺序与文字不同：第二章 B2 在锚点窗口之外，即使章节号
        // 相同也只允许窗口内 fallback，不能硬配
        val textChapters = listOf(
            chapter("t0", "第一章 A", 0),
            chapter("t1", "第二章 B", 1),
            chapter("t2", "第三章 C", 2),
        )
        val audioChapters = listOf(
            chapter("a0", "第二章 B2", 0),
            chapter("a1", "第一章 A", 1),
            chapter("a2", "第三章 C", 2),
        )

        val pairs = AudioTextFusion.pairChapters(textChapters, audioChapters)

        assertEquals(2, pairs.size)
        assertEquals("a1", pairs.first { it.first.url == "t0" }.second.url)
        assertEquals("a2", pairs.first { it.first.url == "t2" }.second.url)
        assertTrue(pairs.none { it.first.url == "t1" })
    }

    @Test
    fun `chapter volume parsing`() {
        assertEquals(1, ChapterTitle.volume("卷一 第一章"))
        assertEquals(1, ChapterTitle.volume("第一卷 第一章"))
        assertEquals(2, ChapterTitle.volume("第2卷 第1章"))
        assertNull(ChapterTitle.volume("第2章：冲突"))
        assertNull(ChapterTitle.volume("序章"))
    }

    @Test
    fun `number fallback allowed when both sides lack volume`() {
        // 两边都无卷号：章节号 fallback 允许
        val textChapters = listOf(chapter("t0", "第一章 A", 0))
        val audioChapters = listOf(chapter("a0", "第一章 A2", 0))

        val pairs = AudioTextFusion.pairChapters(textChapters, audioChapters)

        assertEquals(1, pairs.size)
        assertEquals("a0", pairs.single().second.url)
    }

    @Test
    fun `number fallback skipped when only one side has volume`() {
        // 只有一边有卷号（不明确）：跳过，不做章节号硬配
        val textChapters = listOf(chapter("t0", "第一卷 第一章 A", 0))
        val audioChapters = listOf(chapter("a0", "第一章 A2", 0))

        val pairs = AudioTextFusion.pairChapters(textChapters, audioChapters)

        assertTrue(pairs.isEmpty())
    }

    // ---------- 评论按钮来源上下文 ----------

    @Test
    fun `planFusionWrites fills text book context into overlay`() {
        val textChapters = listOf(chapter("t0", "第一章", 0))
        val audioChapters = listOf(chapter("a0", "第一章", 0))

        val plan = AudioTextFusion.planFusionWrites(
            textChapters, audioChapters,
            textBookUrl = "textBookUrl",
            hasTextContent = { true },
            getTextContent = { "第一章$reviewImg" },
            hasAudioContent = { true },
            getLyric = { "[00:01.00]第一章" },
            getCurrentOverlay = { "" },
        )

        val insertion = plan.writes.single().insertions!!.single()
        assertEquals("textBookUrl", insertion.textBookUrl)
        assertEquals("t0", insertion.textChapterUrl)
    }

    @Test
    fun `findFusionTextContext resolves overlay payload to text book context`() {
        val src = """https://a.test/btn.png,{"style":"TEXT","click":"showReview()"}"""
        val insertions = listOf(
            AudioTextFusion.OverlayInsertion(
                anchor = "第一句", occurrence = 1,
                payload = "<usehtml><img src=\"$src\"></usehtml>",
                textBookUrl = "textBookUrl",
                textChapterUrl = "textChapterUrl",
            )
        )
        val json = AudioTextFusion.buildOverlay(insertions)

        assertEquals(
            "textBookUrl" to "textChapterUrl",
            AudioTextFusion.findFusionTextContext(json, src)
        )
        // 非该 overlay 的 src 命中不到
        assertNull(AudioTextFusion.findFusionTextContext(json, "https://other.test/x.png"))
        // 旧数据无来源字段：回退当前阅读上下文（返回 null）
        val legacyJson =
            """[{"anchor":"第一句","occurrence":1,"payload":"<usehtml>legacy</usehtml>"}]"""
        assertNull(AudioTextFusion.findFusionTextContext(legacyJson, src))
        // 空 overlay
        assertNull(AudioTextFusion.findFusionTextContext("", src))
    }

    @Test
    fun `overlay json roundtrip keeps text book context`() {
        val insertions = listOf(
            AudioTextFusion.OverlayInsertion(
                anchor = "第一句", occurrence = 1,
                payload = "<usehtml>x</usehtml>",
                textBookUrl = "textBookUrl",
                textChapterUrl = "textChapterUrl",
            ),
        )
        assertEquals(insertions, AudioTextFusion.parseOverlay(AudioTextFusion.buildOverlay(insertions)))
    }

    // ---------- reconcile：无法确认时保持旧 overlay ----------

    @Test
    fun `missing text cache keeps old overlay`() {
        val textChapters = listOf(chapter("t0", "第一章", 0))
        val audioChapters = listOf(chapter("a0", "第一章", 0))

        val plan = AudioTextFusion.planFusionWrites(
            textChapters, audioChapters,
            textBookUrl = "textBookUrl",
            hasTextContent = { false },   // 文字缓存没了：无法确认，不清除
            getTextContent = { "第一章" },
            hasAudioContent = { true },
            getLyric = { "[00:01.00]第一章" },
            getCurrentOverlay = { fakeOverlayJson },
        )

        assertTrue(plan.writes.isEmpty())
    }

    @Test
    fun `missing audio cache keeps old overlay`() {
        val textChapters = listOf(chapter("t0", "第一章", 0))
        val audioChapters = listOf(chapter("a0", "第一章", 0))

        val plan = AudioTextFusion.planFusionWrites(
            textChapters, audioChapters,
            textBookUrl = "textBookUrl",
            hasTextContent = { true },
            getTextContent = { "第一章$reviewImg" },
            hasAudioContent = { false },  // 音频缓存没了：无法确认，不清除
            getLyric = { "[00:01.00]第一章" },
            getCurrentOverlay = { fakeOverlayJson },
        )

        assertTrue(plan.writes.isEmpty())
    }

    @Test
    fun `blank lyric keeps old overlay`() {
        val textChapters = listOf(chapter("t0", "第一章", 0))
        val audioChapters = listOf(chapter("a0", "第一章", 0))

        val plan = AudioTextFusion.planFusionWrites(
            textChapters, audioChapters,
            textBookUrl = "textBookUrl",
            hasTextContent = { true },
            getTextContent = { "第一章$reviewImg" },
            hasAudioContent = { true },
            getLyric = { "" },           // lyric 缺失：无法确认，不清除
            getCurrentOverlay = { fakeOverlayJson },
        )

        assertTrue(plan.writes.isEmpty())
    }

    @Test
    fun `null text content keeps old overlay`() {
        val textChapters = listOf(chapter("t0", "第一章", 0))
        val audioChapters = listOf(chapter("a0", "第一章", 0))

        val plan = AudioTextFusion.planFusionWrites(
            textChapters, audioChapters,
            textBookUrl = "textBookUrl",
            hasTextContent = { true },
            getTextContent = { null },   // 正文读取失败：无法确认，不清除
            hasAudioContent = { true },
            getLyric = { "[00:01.00]第一章" },
            getCurrentOverlay = { fakeOverlayJson },
        )

        assertTrue(plan.writes.isEmpty())
    }

    @Test
    fun `confirmed no comments still clears old overlay`() {
        val textChapters = listOf(chapter("t0", "第一章", 0))
        val audioChapters = listOf(chapter("a0", "第一章", 0))

        val plan = AudioTextFusion.planFusionWrites(
            textChapters, audioChapters,
            textBookUrl = "textBookUrl",
            hasTextContent = { true },
            getTextContent = { "第一章" },   // 缓存齐全且确认无评论入口
            hasAudioContent = { true },
            getLyric = { "[00:01.00]第一章" },
            getCurrentOverlay = { fakeOverlayJson },
        )

        assertEquals(1, plan.writes.size)
        assertNull(plan.writes.single().insertions)
    }

    @Test
    fun `plan collects per chapter diagnostics without changing matching`() {
        val textChapters = listOf(chapter("t0", "第一章", 0), chapter("t1", "第二章", 1))
        val audioChapters = listOf(chapter("a0", "第一章", 0), chapter("a1", "第二章", 1))

        val plan = AudioTextFusion.planFusionWrites(
            textChapters, audioChapters,
            textBookUrl = "textBookUrl",
            hasTextContent = { it.url == "t0" },           // 第二章文字缓存缺失
            getTextContent = { "第一章$reviewImg" },
            hasAudioContent = { true },
            getLyric = { if (it.url == "a0") "[00:01.00]第一章" else "[00:01.00]第二章" },
            getCurrentOverlay = { "" },
        )

        // 匹配行为不变：只有第一章能产生挂载
        assertEquals(1, plan.writes.count { it.insertions != null })
        assertEquals(0, plan.writes.count { it.insertions == null })
        // 诊断逐章记录成功与失败原因，含逐条段落 ✓/× 与截断文字
        assertTrue(plan.details.any { it.contains("评论段落 1 段，匹配 1 个，未匹配 0 段") })
        assertTrue(plan.details.any { it.contains("✓ ") && it.contains("第一章") })
        assertTrue(plan.details.any { it.contains("第二章 ↔ 第二章") && it.contains("跳过：文字缓存缺失") })
    }

    @Test
    fun `diagnostics distinguish no comment from zero match`() {
        val textChapters = listOf(chapter("t0", "第一章", 0), chapter("t1", "第二章", 1))
        val audioChapters = listOf(chapter("a0", "第一章", 0), chapter("a1", "第二章", 1))

        // 场景一：本章无评论段落（正文全是占位文本）
        val planNoComment = AudioTextFusion.planFusionWrites(
            textChapters.take(1), audioChapters.take(1),
            textBookUrl = "textBookUrl",
            hasTextContent = { true },
            getTextContent = { "第一章横平竖直的正文" },
            hasAudioContent = { true },
            getLyric = { "[00:01.00]第一章" },
            getCurrentOverlay = { "" },
        )
        assertTrue(planNoComment.details.any { it.contains("本章无评论") })

        // 场景二：有 1 个评论但匹配 0 个（锚点文字完全不同）
        val planZeroMatch = AudioTextFusion.planFusionWrites(
            textChapters.take(1), audioChapters.take(1),
            textBookUrl = "textBookUrl",
            hasTextContent = { true },
            getTextContent = { "第一章横平竖直$reviewImg" },
            hasAudioContent = { true },
            getLyric = { "[00:01.00]完全对不上的字幕" },
            getCurrentOverlay = { "" },
        )
        assertTrue(planZeroMatch.details.any { it.contains("有 1 个评论但匹配 0 个") })
        assertTrue(planZeroMatch.details.any { it.contains("× 第一章横平竖直") })

        // 场景三：部分匹配——✓ 与 × 按原文顺序逐条列出
        val imgA = """<img src="https://a.test/1.png,{"style":"TEXT"}">"""
        val imgB = """<img src="https://a.test/2.png,{"style":"TEXT"}">"""
        val planPartial = AudioTextFusion.planFusionWrites(
            textChapters, audioChapters,
            textBookUrl = "textBookUrl",
            hasTextContent = { true },
            getTextContent = { if (it.url == "t0") "第一句原文$imgA\n第二句别的内容$imgB" else "第二章无评论" },
            hasAudioContent = { true },
            getLyric = { if (it.url == "a0") "[00:01.00]第一句原文" else "[00:01.00]第二章" },
            getCurrentOverlay = { "" },
        )
        val partialLine = planPartial.details.firstOrNull { it.contains("评论段落 2 段") }
        assertNotNull(partialLine)
        assertTrue(partialLine!!.contains("匹配 1 个，未匹配 1 段"))
        assertTrue(planPartial.details.any { it.contains("✓ 第一句原文") })
        assertTrue(planPartial.details.any { it.contains("× 第二句别的内容") })
        assertTrue(planPartial.details.indexOfFirst { it.contains("✓ 第一句原文") } <
            planPartial.details.indexOfFirst { it.contains("× 第二句别的内容") })
    }
}