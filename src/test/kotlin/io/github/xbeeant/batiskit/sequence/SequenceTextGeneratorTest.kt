package io.github.xbeeant.batiskit.sequence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/** 文本交换格式：PlantUML / Mermaid 的箭头与激活条输出（纯逻辑，手工构造图） */
class SequenceTextGeneratorTest {

    /** A.foo() → B.bar() → B.inner()（B 上有激活条），外加一个运行中创建的 C */
    private fun sample(): SequenceDiagram {
        val diagram = SequenceDiagram()
        diagram.addParticipant(SeqParticipant("com.example.A", "A"))
        diagram.addParticipant(SeqParticipant("com.example.B", "B", isInterface = true))
        diagram.addMessage(SeqMessage(MessageKind.CALL, "com.example.A", "com.example.B", "foo(x)", depth = 0))
        diagram.messages[0].endIndex = 2
        diagram.addMessage(SeqMessage(MessageKind.CALL, "com.example.B", "com.example.B", "inner()", depth = 1, recursive = false))
        diagram.messages[1].endIndex = 1
        diagram.addParticipant(SeqParticipant("com.example.C", "C"))
        diagram.addMessage(SeqMessage(MessageKind.CREATE, "com.example.A", "com.example.C", "new C()", depth = 1))
        diagram.messages[2].endIndex = 2
        diagram.seal()
        return diagram
    }

    fun `test plantuml 输出参与者 激活与创建`() {
        val text = SequenceTextGenerator.plantUml(sample())
        assertTrue(text.startsWith("@startuml"))
        assertTrue(text.contains("participant \"A\" as P0"))
        assertTrue(text.contains("participant \"B\" as P1"))
        assertTrue(text.contains("autonumber"))
        // 第一条消息 + activate；递归自调用；create 前置声明
        assertTrue(text.contains("P0 -> P1 : foo(x)\nactivate P1"))
        assertTrue(text.contains("P1 -> P1 : inner()"))
        assertTrue(text.contains("create P2"))
        assertTrue(text.contains("P0 -> P2 : new C()"))
        // 激活条在区间结束的消息后 deactivate
        assertTrue(text.contains("deactivate P1"))
        assertTrue(text.endsWith("@enduml\n"))
    }

    fun `test mermaid 输出与虚线`() {
        val diagram = sample().apply {
            addMessage(SeqMessage(MessageKind.RETURN, "com.example.B", "com.example.A", "return", dashed = true))
        }
        diagram.seal()
        val text = SequenceTextGenerator.mermaid(diagram)
        assertTrue(text.startsWith("sequenceDiagram"))
        assertTrue(text.contains("participant P0 as A"))
        assertTrue(text.contains("P0->>P1: foo(x)"))
        assertTrue(text.contains("activate P1"))
        assertTrue(text.contains("deactivate P1"))
        assertTrue(text.contains("P1-->P0: return"))
        assertTrue(text.contains("create participant P2"))
    }

    fun `test 递归标记与备注行`() {
        val diagram = SequenceDiagram()
        diagram.addParticipant(SeqParticipant("com.example.X", "X"))
        diagram.addMessage(SeqMessage(MessageKind.CALL, "com.example.X", "com.example.X", "run()", recursive = true))
        diagram.messages[0].endIndex = 0
        diagram.addMessage(SeqMessage(MessageKind.NOTE, "com.example.X", "com.example.X", "已达到 300 条消息上限，剩余调用被截断"))
        diagram.seal()
        val puml = SequenceTextGenerator.plantUml(diagram)
        assertTrue(puml.contains("run() ⟳"))
        assertTrue(puml.contains("note over P0"))
        assertTrue(puml.contains("截断"))
        val mermaid = SequenceTextGenerator.mermaid(diagram)
        assertTrue(mermaid.contains("run() ⟳"))
        assertTrue(mermaid.contains("note over P0"))
    }

    fun `test 引号转义`() {
        val diagram = SequenceDiagram()
        diagram.addParticipant(SeqParticipant("com.example.A", "A\"B"))
        diagram.seal()
        val puml = SequenceTextGenerator.plantUml(diagram)
        assertTrue(puml.contains("participant 'A'B' as P0"))
        val mermaid = SequenceTextGenerator.mermaid(diagram)
        assertTrue(mermaid.contains("as A'B"))
    }

    fun `test 布局与 svg 渲染`() {
        val diagram = sample()
        val layout = SequenceLayout(diagram)
        assertEquals(3, layout.headers.size)
        assertEquals(3, layout.arrows.size)
        assertEquals(1, layout.activations.size) // B 上一条激活条（foo 的区间），inner 是自调用不产生
        // 顶部为片段标题预留了一行（MESSAGE_TOP），高度按「预留带 + 每行 ROW_H + 底部留白」算
        assertEquals(
            diagram.messages.size,
            ((layout.height - SequenceLayout.MESSAGE_TOP - SequenceLayout.BOTTOM_PAD) /
                SequenceLayout.ROW_H).toInt(),
        )

        val svg = SvgRenderer.render(layout, diagram)
        assertTrue(svg.startsWith("<svg"))
        assertTrue(svg.contains("</svg>"))
        // 三个表头框、一根虚线箭头都不强制校验颜色，只校验结构
        assertTrue(svg.contains("<rect"))
        assertTrue(svg.contains("<text"))
        assertTrue(svg.contains("foo(x)"))
        assertTrue(svg.contains("new C()"))
    }

    fun `test 布局自调用与备注`() {
        val diagram = SequenceDiagram()
        diagram.addParticipant(SeqParticipant("com.example.X", "X"))
        diagram.addMessage(SeqMessage(MessageKind.CALL, "com.example.X", "com.example.X", "run()", recursive = true))
        diagram.messages[0].endIndex = 0
        diagram.addMessage(SeqMessage(MessageKind.NOTE, "com.example.X", "com.example.X", "备注"))
        diagram.seal()
        val layout = SequenceLayout(diagram)
        assertTrue(layout.arrows[0].self)
        assertTrue(layout.arrows[0].recursive)
        assertEquals(1, layout.notes.size)
        val svg = SvgRenderer.render(layout, diagram)
        assertTrue(svg.contains("⟳") || svg.contains("run()"))
    }
}
