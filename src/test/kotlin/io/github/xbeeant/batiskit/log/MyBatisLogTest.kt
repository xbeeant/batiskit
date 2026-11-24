package io.github.xbeeant.batiskit.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

/**
 * MyBatis 日志解析 / SQL 还原 / 排版的纯逻辑测试（不依赖 IDE 运行环境）。
 */
class MyBatisLogTest {

    private val parser = MyBatisLogParser()

    private fun restore(
        sql: String,
        parameters: String,
        normalizeDate: Boolean = true
    ): SqlRestorer.Restored = SqlRestorer.restore(sql, SqlRestorer.parseParameters(parameters), normalizeDate)

    // ------------------------------------------------------------------ 解析

    @Test
    fun `Preparing 与 Parameters 合成一条语句`() {
        val result = parser.feed(
            "==>  Preparing: select id, name from t_user where id = ?\n" +
                "==> Parameters: 1(Integer)\n" +
                "<==      Total: 1\n"
        )
        assertEquals(1, result.added.size)
        val entry = result.added[0]
        assertEquals("select id, name from t_user where id = ?", entry.sql)
        assertEquals(1, entry.parameterCount)
        assertEquals("1", entry.parameters[0].value)
        assertEquals("Integer", entry.parameters[0].type)
        assertEquals(1, entry.total)
    }

    @Test
    fun `Total 只回填计数不重复产出条目`() {
        val first = parser.feed("==>  Preparing: select 1 from dual where x = ?\n==> Parameters: 2(Integer)\n")
        assertEquals(1, first.added.size)
        val second = parser.feed("<==      Total: 3\n")
        assertTrue(second.added.isEmpty())
        assertTrue(second.updated)
        assertEquals(3, first.added[0].total)
    }

    @Test
    fun `没有占位符的语句一读到 Preparing 就产出`() {
        val result = parser.feed("==>  Preparing: select count(*) from t_user\n")
        assertEquals(1, result.added.size)
        assertEquals(0, result.added[0].parameterCount)
    }

    @Test
    fun `缺少 Parameters 行时由 Total 兜底产出`() {
        val result = parser.feed(
            "==>  Preparing: delete from t_user where id = ?\n" +
                "<==    Updates: 1\n"
        )
        assertEquals(1, result.added.size)
        assertEquals(1, result.added[0].updates)
    }

    @Test
    fun `日志被切成任意片段也能解析`() {
        val chunks = listOf(
            "2026-11-03 22:00 [main] DEBUG c.x.UserMapper - ==>  Prep",
            "aring: select * from t_user where id = ?\n==> Param",
            "eters: 7(Long)\n<==      Total: 1\n"
        )
        val added = chunks.flatMap { parser.feed(it).added }
        assertEquals(1, added.size)
        assertEquals("select * from t_user where id = ?", added[0].sql)
        assertEquals(7L, added[0].parameters[0].value.toLong())
    }

    @Test
    fun `ANSI 颜色控制符会被去掉`() {
        val result = parser.feed("\u001B[32m==>  Preparing: select 1\u001B[0m\n")
        assertEquals(1, result.added.size)
        assertEquals("select 1", result.added[0].sql)
    }

    @Test
    fun `reset 后不残留上一条未完成的语句`() {
        parser.feed("==>  Preparing: select * from t where id = ?\n")
        parser.reset()
        val result = parser.feed("==> Parameters: 1(Integer)\n")
        assertTrue(result.added.isEmpty())
    }

    // ------------------------------------------------------------------ 参数解析

    @Test
    fun `字符串参数里的逗号不会把参数切碎`() {
        val parameters = SqlRestorer.parseParameters("1(Integer), a, b, c(String)")
        assertEquals(2, parameters.size)
        assertEquals("1", parameters[0].value)
        assertEquals("a, b, c", parameters[1].value)
        assertEquals("String", parameters[1].type)
    }

    @Test
    fun `无类型的 null 单独成为一个参数`() {
        val parameters = SqlRestorer.parseParameters("1(Integer), null, abc(String)")
        assertEquals(3, parameters.size)
        assertTrue(parameters[1].isNull)
        assertEquals("abc", parameters[2].value)
    }

    @Test
    fun `没有参数时返回空列表`() {
        assertTrue(SqlRestorer.parseParameters("").isEmpty())
        assertTrue(SqlRestorer.parseParameters("   ").isEmpty())
    }

    // ------------------------------------------------------------------ 还原

    @Test
    fun `字符串参数加引号并转义单引号`() {
        val restored = restore(
            "select * from t_user where name = ? and remark = ?",
            "O'Brien(String), it's(String)"
        )
        assertEquals("select * from t_user where name = 'O''Brien' and remark = 'it''s'", restored.text)
        assertTrue(restored.isComplete)
    }

    @Test
    fun `null 参数还原成 NULL`() {
        val restored = restore("select * from t_user where id = ? and name = ?", "1(Long), null")
        assertEquals("select * from t_user where id = 1 and name = NULL", restored.text)
    }

    @Test
    fun `字符串字面量里的问号不是占位符`() {
        val sql = "select case when status = '?' then 1 end from t_user where id = ?"
        assertEquals(1, SqlRestorer.countPlaceholders(sql))
        val restored = restore(sql, "9(Integer)")
        assertTrue(restored.text.contains("'?'"))
        assertTrue(restored.text.endsWith("id = 9"))
        assertTrue(restored.isComplete)
    }

    @Test
    fun `注释里的问号不是占位符`() {
        assertEquals(1, SqlRestorer.countPlaceholders("select * from t -- 这里是? 注释\nwhere id = ?"))
    }

    @Test
    fun `参数不够时保留问号并标记不完整`() {
        val restored = restore("select * from t where a = ? and b = ?", "1(Integer)")
        assertEquals("select * from t where a = 1 and b = ?", restored.text)
        assertEquals(1, restored.missing)
        assertFalse(restored.isComplete)
    }

    @Test
    fun `枚举与 UUID 这类未知类型按字符串处理`() {
        val restored = restore(
            "insert into t_user (status, uid) values (?, ?)",
            "ACTIVE(com.xstudio.Status), 550e8400-e29b(java.util.UUID)"
        )
        assertEquals("insert into t_user (status, uid) values ('ACTIVE', '550e8400-e29b')", restored.text)
    }

    @Test
    fun `BigDecimal 与布尔保持原样`() {
        val restored = restore("update t set amount = ?, enabled = ? where id = ?", "12.50(BigDecimal), true(Boolean), 1(Long)")
        assertEquals("update t set amount = 12.50, enabled = true where id = 1", restored.text)
    }

    @Test
    fun `java util Date 的 toString 转成标准时间格式`() {
        val original = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"))
            val restored = restore("select * from t where create_time > ?", "Mon Nov 03 22:00:00 GMT+08:00 2026(java.util.Date)")
            assertEquals("select * from t where create_time > '2026-11-03 22:00:00'", restored.text)
        } finally {
            TimeZone.setDefault(original)
        }
    }

    @Test
    fun `Timestamp 已经是标准格式不额外转换`() {
        val restored = restore("select * from t where create_time = ?", "2026-11-03 22:00:00.0(Timestamp)")
        assertEquals("select * from t where create_time = '2026-11-03 22:00:00.0'", restored.text)
    }

    @Test
    fun `byte数组参数保持原样`() {
        val restored = restore("select * from t where content = ?", "[B@1f2e3d(byte[])")
        assertEquals("select * from t where content = [B@1f2e3d", restored.text)
    }

    // ------------------------------------------------------------------ 排版

    @Test
    fun `格式化按关键字换行并缩进 AND`() {
        val formatted = SqlFormatter.format("select id, name from t_user where id = 1 and name = 'x'")
        val lines = formatted.lines()
        assertEquals("select id, name", lines[0])
        assertEquals("from t_user", lines[1])
        assertEquals("where id = 1", lines[2])
        assertEquals("  and name = 'x'", lines[3])
    }

    @Test
    fun `格式化不改动字符串里的关键字与空白`() {
        val formatted = SqlFormatter.format("select * from t where remark = 'from  where  and'")
        assertTrue(formatted.contains("'from  where  and'"))
    }

    @Test
    fun `格式化处理 group by 与 left join`() {
        val formatted = SqlFormatter.format(
            "select a.id from t_a a left join t_b b on a.id = b.a_id group by a.id order by a.id"
        )
        val lines = formatted.lines()
        assertEquals("select a.id", lines[0])
        assertEquals("from t_a a", lines[1])
        assertEquals("left join t_b b", lines[2])
        assertEquals("on a.id = b.a_id", lines[3])
        assertEquals("group by a.id", lines[4])
        assertEquals("order by a.id", lines[5])
    }

    @Test
    fun `空白 SQL 原样返回`() {
        assertEquals("  ", SqlFormatter.format("  "))
    }

    @Test
    fun `完整链路 Preparing 到可执行 SQL`() {
        val result = parser.feed(
            "==>  Preparing: select id, name from t_user where id = ? and status = ? order by id desc\n" +
                "==> Parameters: 1(Long), ACTIVE(String)\n" +
                "<==      Total: 1\n"
        )
        val entry = result.added.single()
        assertEquals(2, entry.parameterCount)
        assertTrue(entry.isComplete)

        val restored = SqlRestorer.restore(entry.sql, entry.parameters)
        assertEquals("select id, name from t_user where id = 1 and status = 'ACTIVE' order by id desc", restored.text)

        val formatted = SqlFormatter.format(restored.text)
        assertTrue(formatted.startsWith("select id, name"))
        assertTrue(formatted.contains("\nfrom t_user"))
        assertTrue(formatted.contains("\nwhere id = 1"))
    }
}
