package io.github.xbeeant.batiskit.sequence

import io.github.xbeeant.batiskit.sequence.DatabaseTraceRules.Framework
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 数据库层追踪的纯逻辑规则：动词归一、SQL 压缩、标签与参与者身份键 */
class DatabaseTraceRulesTest {

    @Test
    fun `语句标签与注解优先于方法名`() {
        assertEquals("SELECT", DatabaseTraceRules.verbOf(tag = "select", methodName = "deleteAll"))
        assertEquals("UPDATE", DatabaseTraceRules.verbOf(annotation = "UpdateProvider", methodName = "selectAll"))
        assertEquals("INSERT", DatabaseTraceRules.verbOf(annotation = "org.apache.ibatis.annotations.Insert"))
        assertEquals("DELETE", DatabaseTraceRules.verbOf(methodName = "removeById"))
        assertEquals("SELECT", DatabaseTraceRules.verbOf(methodName = "whatever"))
    }

    @Test
    fun `SQL 正文首词能兜住动词`() {
        assertEquals(
            "INSERT",
            DatabaseTraceRules.verbOf(methodName = "whatever", sql = "  INSERT INTO sys_user (id) VALUES (1)"),
        )
    }

    @Test
    fun `压缩 SQL 会剥掉动态标签与占位符`() {
        val raw = """
            <select id="list">
              SELECT * FROM sys_user u
              <where>
                <if test="name != null"> AND u.name = #{name} </if>
              </where>
            </select>
        """.trimIndent()
        val compact = DatabaseTraceRules.compactSql(raw)
        assertFalse(compact.contains("<"))
        assertFalse(compact.contains("\n"))
        assertTrue(compact.contains("SELECT * FROM sys_user u"))
        assertTrue(compact.contains("?"))
    }

    @Test
    fun `从 SQL 里取表并限制在 3 张以内`() {
        val tables = DatabaseTraceRules.tablesOf(
            "SELECT * FROM sys_user u JOIN sys_role r ON u.role_id = r.id " +
                "JOIN sys_dept d ON 1 = 1 JOIN sys_log l ON 1 = 1",
        )
        assertEquals(listOf("sys_user", "sys_role", "sys_dept"), tables)
    }

    @Test
    fun `MyBatis-Plus 通用 CRUD 没有 SQL 时合成标签`() {
        val label = DatabaseTraceRules.label("INSERT", null, "sys_user", Framework.MYBATIS_PLUS)
        assertEquals("INSERT INTO sys_user (MyBatis-Plus)", label)
        assertEquals("UPDATE sys_user SET ...", DatabaseTraceRules.label("UPDATE", null, "sys_user", Framework.MYBATIS))
        assertEquals("DELETE FROM sys_user", DatabaseTraceRules.label("DELETE", null, "sys_user", Framework.MYBATIS))
        assertEquals("SELECT * FROM sys_user", DatabaseTraceRules.label("SELECT", null, "sys_user", Framework.MYBATIS))
    }

    @Test
    fun `有真实 SQL 时标签就用原文`() {
        val label = DatabaseTraceRules.label(
            "SELECT", "<select>SELECT * FROM sys_user WHERE id = #{id}</select>", "unused", Framework.MYBATIS_PLUS,
        )
        assertTrue(label.contains("SELECT * FROM sys_user"))
        assertFalse(label.contains("MyBatis-Plus"))
    }

    @Test
    fun `数据库表参与者身份键不与 Java 类冲突且长名截断`() {
        assertEquals("database:sys_user", DatabaseTraceRules.participantKey("sys_user"))
        assertEquals("sys_user (DB)", DatabaseTraceRules.displayOf("sys_user"))
        val long = "a".repeat(40)
        assertEquals("a".repeat(21) + "… (DB)", DatabaseTraceRules.displayOf(long))
        assertEquals("ab…", DatabaseTraceRules.truncate("abcdef", 3))
    }

    @Test
    fun `数据库层开关参与选项记忆且兼容旧的五段配置`() {
        val options = DiagramOptions(traceDatabase = false)
        assertEquals(options, DiagramOptions.fromName(options.toName()))
        assertTrue("旧配置没有第 6 段时默认开启", DiagramOptions.fromName("5,true,true,true,false").traceDatabase)
        assertFalse(DiagramOptions.fromName("5,true,true,true,false,false").traceDatabase)
        assertEquals(DiagramOptions(), DiagramOptions.fromName(null))
    }
}
