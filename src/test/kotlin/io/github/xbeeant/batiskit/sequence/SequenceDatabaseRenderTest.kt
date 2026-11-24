package io.github.xbeeant.batiskit.sequence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 数据库表参与者与 SQL 箭头在四种输出里的表现（布局 / SVG / PlantUML / Mermaid） */
class SequenceDatabaseRenderTest {

    private fun diagram(): SequenceDiagram {
        val diagram = SequenceDiagram()
        diagram.addParticipant(SeqParticipant("com.example.Demo", "Demo"))
        diagram.addParticipant(SeqParticipant("com.example.UserMapper", "UserMapper", isInterface = true))
        diagram.addParticipant(
            SeqParticipant(
                DatabaseTraceRules.participantKey("sys_user"),
                DatabaseTraceRules.displayOf("sys_user"),
                isDatabase = true,
            ),
        )
        val call = diagram.addMessage(
            SeqMessage(MessageKind.CALL, "com.example.Demo", "com.example.UserMapper", "selectById(1)"),
        )
        diagram.messages[call].endIndex = 1
        diagram.addMessage(
            SeqMessage(
                MessageKind.SQL, "com.example.UserMapper", "database:sys_user",
                "SELECT * FROM sys_user WHERE id = ?", depth = 1,
            ),
        )
        diagram.seal()
        return diagram
    }

    @Test
    fun `布局里标记数据库表头与 SQL 箭头`() {
        val diagram = diagram()
        val layout = SequenceLayout(diagram)
        assertEquals(2, layout.arrows.size)
        assertFalse(layout.arrows[0].sql)
        assertTrue(layout.arrows[1].sql)
        assertTrue(layout.headers[2].database)
        assertFalse(layout.headers[1].database)
    }

    @Test
    fun `PlantUML 用 database 造型且不声明 create`() {
        val text = SequenceTextGenerator.plantUml(diagram())
        assertTrue(text.contains("database \"sys_user (DB)\" as P2"))
        assertTrue(text.contains("P1 -> P2 : SELECT * FROM sys_user WHERE id = ?"))
        assertFalse("数据库表不是运行中创建的参与者", text.contains("create P2"))
        assertTrue(text.contains("@enduml"))
    }

    @Test
    fun `Mermaid 输出 SQL 箭头与表参与者`() {
        val text = SequenceTextGenerator.mermaid(diagram())
        assertTrue(text.contains("participant P2 as sys_user (DB)"))
        assertTrue(text.contains("P1 -> P2: SELECT * FROM sys_user WHERE id = ?"))
        assertFalse(text.contains("create participant P2"))
    }

    @Test
    fun `SVG 用专属配色画 SQL 箭头与数据库表头`() {
        val diagram = diagram()
        val svg = SvgRenderer.render(SequenceLayout(diagram), diagram)
        assertTrue(svg.contains("#0F6E86"))
        // 表头不再铺底色块，改用青色文字区分数据库表；完整 SQL 仍在 <title> 里
        assertFalse("表头不应再有米蓝底色块", svg.contains("#DDEFF5"))
        assertTrue(svg.contains("sys_user (DB)"))
        assertTrue(svg.contains("SELECT * FROM sys_user WHERE id = ?"))
    }
}
