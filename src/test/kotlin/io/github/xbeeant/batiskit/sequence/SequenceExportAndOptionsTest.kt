package io.github.xbeeant.batiskit.sequence

import io.github.xbeeant.batiskit.sequence.DatabaseTraceRules.Framework
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 三项反馈的纯逻辑回归：
 * ① 消息上限用户可配 + 按工程记忆；② Mermaid / PlantUML 导出保留完整字符串；
 * ③ 图形页签继续用缩写，不受导出改动影响。
 */
class SequenceExportAndOptionsTest {

    @Test
    fun `消息上限写进选项记忆且兼容旧配置`() {
        val options = DiagramOptions(maxMessages = 1200)
        assertEquals(options, DiagramOptions.fromName(options.toName()))
        assertEquals(1200, DiagramOptions.fromName("5,true,true,true,false,true,1200").maxMessages)
        // 旧 5 段（没有数据库层开关）与 6 段（没有消息上限）都回落到默认「不限」
        assertEquals(
            DiagramOptions.NO_MAX_MESSAGES,
            DiagramOptions.fromName("5,true,true,true,false").maxMessages,
        )
        assertEquals(
            DiagramOptions.NO_MAX_MESSAGES,
            DiagramOptions.fromName("5,true,true,true,false,true").maxMessages,
        )
        assertEquals(DiagramOptions.NO_MAX_MESSAGES, DiagramOptions.fromName(null).maxMessages)
        // 记忆串里的 0 明确表示不限，往返后仍是不限
        assertEquals(
            DiagramOptions.NO_MAX_MESSAGES,
            DiagramOptions.fromName("5,true,true,true,false,true,0").maxMessages,
        )
        assertEquals(
            DiagramOptions.NO_MAX_MESSAGES,
            DiagramOptions.fromName(DiagramOptions().toName()).maxMessages,
        )
    }

    @Test
    fun `默认取消消息上限 只有显式设正数才截断`() {
        val unlimited = DiagramOptions()
        assertEquals(DiagramOptions.NO_MAX_MESSAGES, unlimited.maxMessages)
        assertFalse(unlimited.messagesLimited)
        assertFalse("默认不限，一万条也不该触发截断", unlimited.limitReached(10000))

        val limited = DiagramOptions(maxMessages = 2)
        assertTrue(limited.messagesLimited)
        assertFalse(limited.limitReached(1))
        assertTrue(limited.limitReached(2))
        assertTrue(limited.limitReached(3))
        // 显式上限仍按工程记忆往返
        assertEquals(limited, DiagramOptions.fromName(limited.toName()))
    }

    @Test
    fun `基础类过滤开关写进选项记忆且兼容旧配置`() {
        val options = DiagramOptions(skipBasicClasses = false)
        assertEquals(options, DiagramOptions.fromName(options.toName()))
        assertFalse(DiagramOptions.fromName("5,true,true,true,false,true,300,false").skipBasicClasses)
        // 旧 5 / 6 / 7 段配置没有第 8 段时默认开启（升级后默认跳过基础类）
        assertTrue(DiagramOptions.fromName("5,true,true,true,false").skipBasicClasses)
        assertTrue(DiagramOptions.fromName("5,true,true,true,false,true,300").skipBasicClasses)
        assertTrue(DiagramOptions.fromName(null).skipBasicClasses)
        // 脏值回落默认
        assertTrue(DiagramOptions.fromName("5,true,true,true,false,true,300,nope").skipBasicClasses)
    }

    @Test
    fun `消息上限记忆串里的越界与脏值会收敛`() {
        // 只夹上限、不夹下限：spinner 的 10 是 UI 范围，记忆串里更小的历史值原样保留
        assertEquals(
            1,
            DiagramOptions.fromName("5,true,true,true,false,true,1").maxMessages,
        )
        assertEquals(
            2,
            DiagramOptions.fromName("5,true,true,true,false,true,2").maxMessages,
        )
        assertEquals(
            DiagramOptions.NO_MAX_MESSAGES,
            DiagramOptions.fromName("5,true,true,true,false,true,-3").maxMessages,
        )
        assertEquals(
            DiagramOptions.MAX_MAX_MESSAGES,
            DiagramOptions.fromName("5,true,true,true,false,true,999999").maxMessages,
        )
        assertEquals(
            DiagramOptions.NO_MAX_MESSAGES,
            DiagramOptions.fromName("5,true,true,true,false,true,nope").maxMessages,
        )
        assertEquals(DiagramOptions(), DiagramOptions.fromName("这不是配置串"))
    }

    @Test
    fun `完整调用标签不截断长字符串字面量`() {
        val long = "x".repeat(500)
        val full = SequenceRules.fullLabel("save", listOf("\"$long\""))
        assertEquals("save(\"$long\")", full)
        assertTrue(full.contains(long))
        // 图上仍然只留短标签
        assertTrue(SequenceRules.label("save", listOf("\"$long\"")).endsWith("…"))
    }

    @Test
    fun `Mermaid 与 PlantUML 导出保留完整标签 图形层仍缩写`() {
        val long = "y".repeat(300)
        val diagram = SequenceDiagram()
        diagram.addParticipant(SeqParticipant("a.A", "A"))
        diagram.addParticipant(SeqParticipant("b.B", "B"))
        diagram.addMessage(
            SeqMessage(
                MessageKind.CALL, "a.A", "b.B",
                label = "save(…)",
                fullLabel = "save(\"$long\")",
            ),
        )
        diagram.seal()

        val mermaid = SequenceTextGenerator.mermaid(diagram)
        val plantUml = SequenceTextGenerator.plantUml(diagram)
        assertTrue("Mermaid 里的长字符串被截断了", mermaid.contains(long))
        assertTrue("PlantUML 里的长字符串被截断了", plantUml.contains(long))
        assertFalse(mermaid.contains("save(…)"))
        assertFalse(plantUml.contains("save(…)"))
        assertEquals("save(…)", SequenceLayout(diagram).arrows[0].text)
    }

    @Test
    fun `Mermaid 里的分号与换行不会切断消息`() {
        val diagram = SequenceDiagram()
        diagram.addParticipant(SeqParticipant("a.A", "A"))
        diagram.addParticipant(SeqParticipant("b.B", "B"))
        diagram.addMessage(
            SeqMessage(
                MessageKind.CALL, "a.A", "b.B",
                label = "log(…)",
                fullLabel = "log(\"a;b\nc\")",
            ),
        )
        diagram.seal()
        val mermaid = SequenceTextGenerator.mermaid(diagram)
        assertTrue(mermaid.contains("a#59;b c"))
        assertFalse(mermaid.contains("\n\n"))
        // PlantUML 用换行分行，标签里的换行同样压成空格
        assertTrue(SequenceTextGenerator.plantUml(diagram).contains("a;b c"))
    }

    @Test
    fun `参与者的完整类名与表名也进文本导出`() {
        val long = "VeryLongClassName".repeat(8)
        val diagram = SequenceDiagram()
        diagram.addParticipant(
            SeqParticipant("p.$long", long.take(27) + "…", fullDisplay = long),
        )
        diagram.addParticipant(
            SeqParticipant(
                DatabaseTraceRules.participantKey("sys_user"),
                "sys_user (DB)",
                isDatabase = true,
                fullDisplay = DatabaseTraceRules.fullDisplayOf("sys_user"),
            ),
        )
        diagram.addMessage(SeqMessage(MessageKind.NOTE, "p.$long", "p.$long", "空图"))
        diagram.seal()
        assertTrue(SequenceTextGenerator.plantUml(diagram).contains(long))
        assertTrue(SequenceTextGenerator.mermaid(diagram).contains(long))
        assertFalse(SequenceLayout(diagram).headers[0].text.contains(long))
    }

    @Test
    fun `数据库 SQL 全文标签不截断 表名不省略`() {
        val sql = "<select>SELECT ${"a".repeat(200)} FROM sys_user WHERE id = #{id}</select>"
        val full = DatabaseTraceRules.fullLabel("SELECT", sql, "sys_user", Framework.MYBATIS)
        assertTrue(full.length > 200)
        assertTrue(full.contains("FROM sys_user"))
        assertFalse(full.endsWith("…"))
        assertTrue(DatabaseTraceRules.label("SELECT", sql, "sys_user", Framework.MYBATIS).endsWith("…"))
        assertEquals("sys_user (DB)", DatabaseTraceRules.fullDisplayOf("sys_user"))
    }

    @Test
    fun `手工构造的消息不带 fullLabel 时行为与从前一致`() {
        val diagram = SequenceDiagram()
        diagram.addParticipant(SeqParticipant("a.A", "A"))
        diagram.addParticipant(SeqParticipant("b.B", "B"))
        val m = SeqMessage(MessageKind.CALL, "a.A", "b.B", "foo(x)")
        assertEquals("foo(x)", m.fullLabel)
        diagram.addMessage(m)
        diagram.seal()
        assertTrue(SequenceTextGenerator.mermaid(diagram).contains("foo(x)"))
    }
}
