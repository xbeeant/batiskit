package io.github.xbeeant.batiskit.sequence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * 三项反馈的纯逻辑回归（不需要 IDE 夹具）：
 * ① 对标图的层次消息编号；② 分支 / 条件语句不再丢信息（片段框 + 文本导出）；
 * ③ 留足间距的排版（表头去底色 / 标签与箭头分离）下布局常量与图形编号仍然正确。
 */
class SequenceBranchTest {

    private fun branched(): SequenceDiagram {
        val diagram = SequenceDiagram()
        diagram.addParticipant(SeqParticipant("a.A", "A"))
        diagram.addParticipant(SeqParticipant("b.B", "B"))
        diagram.addMessage(
            SeqMessage(MessageKind.CALL, "a.A", "b.B", "inIf()",
                branchKind = BranchKind.IF, guard = "if (x != null)"),
        )
        diagram.addMessage(
            SeqMessage(MessageKind.CALL, "b.B", "b.B", "inIf2()", depth = 1,
                branchKind = BranchKind.IF, guard = "if (x != null)"),
        )
        diagram.addMessage(
            SeqMessage(MessageKind.CALL, "a.A", "b.B", "inElse()",
                branchKind = BranchKind.ELSE, guard = "else"),
        )
        diagram.addMessage(
            SeqMessage(MessageKind.CALL, "a.A", "b.B", "inLoop()",
                branchKind = BranchKind.LOOP, guard = "for (i : items)"),
        )
        diagram.seal()
        return diagram
    }

    @Test
    fun `层次编号按调用树生成`() {
        val diagram = SequenceDiagram()
        diagram.addParticipant(SeqParticipant("a.A", "A"))
        diagram.addParticipant(SeqParticipant("b.B", "B"))
        val root = diagram.addMessage(SeqMessage(MessageKind.CALL, "a.A", "b.B", "foo()"))
        diagram.messages[root].endIndex = 2
        diagram.addMessage(SeqMessage(MessageKind.CALL, "b.B", "b.B", "inner()", depth = 1))
        diagram.addMessage(SeqMessage(MessageKind.CALL, "b.B", "b.B", "other()", depth = 1))
        diagram.seal()
        assertEquals(listOf("1", "1.1", "1.2"), diagram.messages.map { it.number })
    }

    @Test
    fun `连续同一分支的消息合并成片段 且 if 与 else 分开`() {
        val fragments = branched().fragments()
        assertEquals(3, fragments.size)
        assertEquals(BranchKind.IF, fragments[0].kind)
        assertEquals("if (x != null)", fragments[0].guard)
        assertEquals(0, fragments[0].startIndex)
        assertEquals(1, fragments[0].endIndex)
        assertEquals(BranchKind.ELSE, fragments[1].kind)
        assertEquals(BranchKind.LOOP, fragments[2].kind)
    }

    @Test
    fun `辅助消息不打断分支片段分组`() {
        val diagram = SequenceDiagram()
        diagram.addParticipant(SeqParticipant("a.A", "A"))
        diagram.addParticipant(SeqParticipant("b.B", "B"))
        diagram.addMessage(
            SeqMessage(MessageKind.CALL, "a.A", "b.B", "first()",
                branchKind = BranchKind.IF, guard = "if (ready)"),
        )
        // SQL / RETURN / NOTE 都不带分支标记，但同属这个分支，不该把框切断
        diagram.addMessage(SeqMessage(MessageKind.SQL, "b.B", "b.B", "SELECT …"))
        diagram.addMessage(SeqMessage(MessageKind.RETURN, "b.B", "a.A", "return", dashed = true))
        diagram.addMessage(
            SeqMessage(MessageKind.CALL, "a.A", "b.B", "second()",
                branchKind = BranchKind.IF, guard = "if (ready)"),
        )
        diagram.seal()
        val fragments = diagram.fragments()
        assertEquals(1, fragments.size)
        assertEquals(0, fragments[0].startIndex)
        assertEquals(3, fragments[0].endIndex)
    }

    @Test
    fun `布局留足间距 文字与线条不再相互覆盖`() {
        assertTrue("表头内边距应留足", SequenceLayout.HEADER_PAD >= 14.0)
        assertTrue("最小列宽应留足，类名不再挤成省略号", SequenceLayout.MIN_COL_W >= 120.0)
        assertTrue("相邻参与者之间要有额外留白", SequenceLayout.COL_GAP > 0.0)
        assertTrue("行高应留足，一条消息独占一行", SequenceLayout.ROW_H >= 48.0)
        assertTrue("表头高度应留足", SequenceLayout.HEADER_H >= 32.0)
        assertTrue("标签与箭头线之间要留空隙", SequenceLayout.LABEL_GAP >= 10.0)
        assertTrue("标签宽度要封顶，避免横贯整图", SequenceLayout.LABEL_MAX_W > 0.0)
        assertTrue("激活条应更醒目", SequenceLayout.ACTIVATION_W >= 12.0)

        val diagram = SequenceDiagram()
        diagram.addParticipant(SeqParticipant("a.A", "A"))
        diagram.addParticipant(SeqParticipant("b.B", "B"))
        diagram.addMessage(SeqMessage(MessageKind.CALL, "a.A", "b.B", "foo()"))
        diagram.seal()
        val layout = SequenceLayout(diagram)
        val arrow = layout.arrows[0]
        assertEquals(
            "标签框底 + 预留空隙 = 箭头线，文字完全画在线上方",
            arrow.y, arrow.labelY + SequenceLayout.LABEL_H + SequenceLayout.LABEL_GAP, 0.001,
        )
        assertTrue("标签宽度不超过封顶", arrow.labelW <= SequenceLayout.LABEL_MAX_W + 1)

        // 表头不再铺底色块：SVG 里只有彩色表头文字，没有米黄填充
        val svg = SvgRenderer.render(layout, diagram)
        assertFalse("表头不应再有米黄底色框", svg.contains("fill=\"#FFF4D6\""))
        assertTrue("表头文字要有颜色", svg.contains("fill=\"#2B5C8A\""))

        // 有分支的图：标题画在框上边界之外，且每条消息标签都与箭头线拉开
        val branchedLayout = SequenceLayout(branched())
        assertTrue(branchedLayout.fragmentBoxes.isNotEmpty())
        for (box in branchedLayout.fragmentBoxes) {
            assertTrue("片段标题应画在框上边界之外，避免与消息标签叠字", box.titleY <= box.y)
        }
        for (a in branchedLayout.arrows) {
            val gap = a.y - (a.labelY + SequenceLayout.LABEL_H)
            assertTrue("每条消息的标签都与箭头线留出空隙：$gap", gap >= SequenceLayout.LABEL_GAP - 0.001)
        }
    }

    @Test
    fun `图形页签带层次编号与分支片段框`() {
        val diagram = branched()
        val layout = SequenceLayout(diagram)
        assertEquals(3, layout.fragmentBoxes.size)
        assertTrue(
            "片段框标题要带条件原文：" + layout.fragmentBoxes.map { it.title },
            layout.fragmentBoxes[0].title.contains("if (x != null)"),
        )
        assertTrue(layout.fragmentBoxes[1].title == "else")
        assertTrue(layout.fragmentBoxes[2].title.startsWith("loop"))
        assertEquals("1", layout.arrows[0].number)
        assertEquals("inIf()", layout.arrows[0].text) // text 保持原值，编号单独挂字段

        val svg = SvgRenderer.render(layout, diagram)
        assertTrue("SVG 里应出现编号前缀", svg.contains("1 inIf()"))
        assertTrue("SVG 里应出现条件原文", svg.contains("if (x != null)"))
    }

    @Test
    fun `文本导出带 alt else 与 loop 且无分支时逐字不变`() {
        val puml = SequenceTextGenerator.plantUml(branched())
        assertTrue(puml.contains("alt [if (x != null)]"))
        assertTrue(puml.contains("\nelse\n"))
        assertTrue(puml.contains("loop [for (i : items)]"))
        assertTrue(puml.contains("\nend\n"))

        val mermaid = SequenceTextGenerator.mermaid(branched())
        assertTrue(mermaid.contains("    alt [if (x != null)]"))
        assertTrue(mermaid.contains("    else"))
        assertTrue(mermaid.contains("    loop [for (i : items)]"))

        // 没有分支的图不应凭空多出片段框，保持既有导出格式
        val plain = SequenceDiagram()
        plain.addParticipant(SeqParticipant("a.A", "A"))
        plain.addParticipant(SeqParticipant("b.B", "B"))
        plain.addMessage(SeqMessage(MessageKind.CALL, "a.A", "b.B", "foo()"))
        plain.seal()
        val plainPuml = SequenceTextGenerator.plantUml(plain)
        assertFalse(plainPuml.contains("alt ["))
        assertFalse(plainPuml.contains("loop ["))
        assertTrue(plainPuml.contains("P0 -> P1 : foo()"))
    }

    /** 同一接口两个实现：各自的箭头 + 方法体调用带同一 IMPL guard，SQL 夹在中间作 filler */
    private fun implDiagram(): SequenceDiagram {
        val diagram = SequenceDiagram()
        diagram.addParticipant(SeqParticipant("a.A", "A"))
        diagram.addParticipant(SeqParticipant("b.ImplA", "ImplA"))
        diagram.addParticipant(SeqParticipant("b.ImplB", "ImplB"))
        diagram.addMessage(SeqMessage(MessageKind.CALL, "a.A", "b.ImplA", "fuzzy()",
            branchKind = BranchKind.IMPL, guard = "ImplA"))
        diagram.addMessage(SeqMessage(MessageKind.SQL, "b.ImplA", "b.ImplA", "SELECT … FROM table_a"))
        diagram.addMessage(SeqMessage(MessageKind.CALL, "b.ImplA", "b.ImplA", "mapperA()", depth = 1,
            branchKind = BranchKind.IMPL, guard = "ImplA"))
        diagram.addMessage(SeqMessage(MessageKind.CALL, "a.A", "b.ImplB", "fuzzy()",
            branchKind = BranchKind.IMPL, guard = "ImplB"))
        diagram.addMessage(SeqMessage(MessageKind.SQL, "b.ImplB", "b.ImplB", "SELECT … FROM table_b"))
        diagram.addMessage(SeqMessage(MessageKind.CALL, "b.ImplB", "b.ImplB", "mapperB()", depth = 1,
            branchKind = BranchKind.IMPL, guard = "ImplB"))
        diagram.seal()
        return diagram
    }

    @Test
    fun `多实现片段配成 alt 与 else 且各自 SQL 不被切断`() {
        val fragments = implDiagram().fragments()
        assertEquals(2, fragments.size)
        assertEquals(BranchKind.IMPL, fragments[0].kind)
        assertEquals("ImplA", fragments[0].guard)
        assertEquals("ImplB", fragments[1].guard)
        // SQL 是 filler，留在各自实现的片段里，不会被切成孤立的框
        assertEquals(2, fragments[0].endIndex)
        assertEquals(5, fragments[1].endIndex)

        val puml = SequenceTextGenerator.plantUml(implDiagram())
        assertTrue("第一个实现开 alt：" + puml, puml.contains("alt [ImplA]"))
        assertTrue("第二个实现接 else：" + puml, puml.contains("else [ImplB]"))
        assertEquals("两个互斥实现只收一次口", 1, puml.lines().count { it.trim() == "end" })

        val mermaid = SequenceTextGenerator.mermaid(implDiagram())
        assertTrue(mermaid.contains("    alt [ImplA]"))
        assertTrue(mermaid.contains("    else [ImplB]"))
    }

    @Test
    fun `多实现片段标题与箭头标签错开一行且按像素裁剪`() {
        val layout = SequenceLayout(implDiagram())
        assertEquals(2, layout.fragmentBoxes.size)
        assertTrue("第一个实现画 alt：" + layout.fragmentBoxes[0].title,
            layout.fragmentBoxes[0].title.startsWith("alt"))
        assertTrue("第二个实现画 else：" + layout.fragmentBoxes[1].title,
            layout.fragmentBoxes[1].title.startsWith("else"))
        for (box in layout.fragmentBoxes) {
            assertTrue("片段标题应画在框上边界之外，避免与消息标签叠字", box.titleY <= box.y)
        }
        for (arrow in layout.arrows) {
            assertTrue("箭头标签要有不透明背景框宽度", arrow.labelW > 0.0)
            assertTrue(
                "标签文字不能溢出背景框",
                arrow.displayText.sumOf { SequenceLayout.charWidth(it) } <= arrow.labelW + 1,
            )
            assertTrue(
                "标签中心应夹在画布内",
                arrow.labelX - arrow.labelW / 2 >= 0 &&
                    arrow.labelX + arrow.labelW / 2 <= layout.width + 1,
            )
        }
        // 按可用像素裁剪，而不是只按字符数截断（CJK 双宽）
        val long = "这是一个非常长的方法名" + "x".repeat(80)
        val fitted = SequenceLayout.fitText(long, 100.0)
        assertTrue("像素裁剪后应明显短于原文：$fitted", fitted.length < long.length)
        assertTrue("裁剪要带省略号", fitted.endsWith("…"))
        assertTrue("CJK 按双倍宽估算", SequenceLayout.charWidth('中') > SequenceLayout.charWidth('x'))
    }
}
