package io.github.xbeeant.batiskit.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 可配置前缀 / 结束标识 / 单行上限 / iBatis / 超长缓冲与设置快照的回归测试 */
class MyBatisLogParserConfigTest {

    @Test
    fun `自定义 Preparing 与 Parameters 前缀`() {
        val parser = MyBatisLogParser(MyBatisLogConfig(preparingPrefix = "SQL:", parametersPrefix = "ARGS:"))
        val result = parser.feed(
            "2026-11-07 DEBUG SQL: select * from t where id = ?\n" +
                "2026-11-07 DEBUG ARGS: 7(Long)\n"
        )
        assertEquals(1, result.added.size)
        assertEquals("select * from t where id = ?", result.added[0].sql)
        assertEquals("7", result.added[0].parameters[0].value)
    }

    @Test
    fun `前缀留空自动回退默认值`() {
        val parser = MyBatisLogParser(MyBatisLogConfig(preparingPrefix = "", parametersPrefix = ""))
        val result = parser.feed("==>  Preparing: select 1\n")
        assertEquals(1, result.added.size)
        assertEquals("select 1", result.added[0].sql)
    }

    @Test
    fun `结束标识会从行尾剥掉`() {
        val parser = MyBatisLogParser(MyBatisLogConfig(endMask = " | "))
        val result = parser.feed("==>  Preparing: select 1 | \n==> Parameters: 1(Integer) | \n")
        assertEquals(1, result.added.size)
        assertEquals("select 1", result.added[0].sql)
    }

    @Test
    fun `超长单行只跳过自己 后面的日志照常解析`() {
        val parser = MyBatisLogParser(MyBatisLogConfig(maxLineLength = 40))
        val longLine = "==>  Preparing: " + "x".repeat(200) + "\n"
        val result = parser.feed(longLine + "==>  Preparing: select 1\n")
        assertEquals(1, result.added.size)
        assertEquals("select 1", result.added[0].sql)
    }

    @Test
    fun `超长半行不会吞掉后续文本块里的正常日志`() {
        val parser = MyBatisLogParser(MyBatisLogConfig(maxLineLength = 40))
        val first = parser.feed("==>  Preparing: " + "x".repeat(200))
        assertTrue(first.added.isEmpty())
        val second = parser.feed("\n==>  Preparing: select 2\n")
        assertEquals(1, second.added.size)
        assertEquals("select 2", second.added[0].sql)
    }

    @Test
    fun `关闭 iBatis 时不识别 PreparedStatement 行`() {
        val parser = MyBatisLogParser(MyBatisLogConfig(iBatisEnabled = false))
        val result = parser.feed("{pstm-1} PreparedStatement: select 1 from t where a = ?\n")
        assertTrue(result.added.isEmpty())
    }

    @Test
    fun `iBatis 三行日志按 Types 还原参数`() {
        val parser = MyBatisLogParser()
        val result = parser.feed(
            "{pstm-1} PreparedStatement:    select * from t_user where name = ? and id = ?\n" +
                "{pstm-1} Parameters: [hello, 1]\n" +
                "{pstm-1} Types: [java.lang.String, java.lang.Integer]\n"
        )
        assertEquals(1, result.added.size)
        val entry = result.added[0]
        assertEquals(2, entry.parameterCount)
        assertEquals(
            "select * from t_user where name = 'hello' and id = 1",
            SqlRestorer.restore(entry.sql, entry.parameters).text
        )
    }

    @Test
    fun `iBatis 缺 Types 行时按值推断类型并补出语句`() {
        val parser = MyBatisLogParser()
        val result = parser.feed(
            "{pstm-1} PreparedStatement: select * from t where name = ?\n" +
                "{pstm-1} Parameters: [hello]\n" +
                "<==      Total: 1\n"
        )
        assertEquals(1, result.added.size)
        assertEquals(1, result.added[0].total)
        assertEquals(
            "select * from t where name = 'hello'",
            SqlRestorer.restore(result.added[0].sql, result.added[0].parameters).text
        )
    }

    @Test
    fun `updateConfig 热更新前缀 后续日志立即生效`() {
        val parser = MyBatisLogParser()
        val before = parser.feed("==>  Preparing: select 1\n")
        assertEquals(1, before.added.size)
        parser.updateConfig(MyBatisLogConfig(preparingPrefix = "SQL:", parametersPrefix = "ARGS:"))
        val result = parser.feed("SQL: select * from t where id = ?\nARGS: 9(Long)\n")
        assertEquals(1, result.added.size)
        assertEquals("select * from t where id = ?", result.added[0].sql)
        assertEquals("9", result.added[0].parameters[0].value)
    }

    @Test
    fun `关键字按行或逗号分隔并去重`() {
        assertEquals(listOf("heartbeat", "t_log"), splitFilterKeywords("heartbeat, t_log\nheartbeat ,\n"))
        assertTrue(splitFilterKeywords("  \n , ").isEmpty())
    }

    @Test
    fun `设置状态拍成解析配置`() {
        val state = MyBatisLogSettingsState().apply {
            preparingPrefix = "SQL:"
            parametersPrefix = "ARGS:"
            endMask = " | "
            maxLineLength = 500
            iBatisEnabled = false
            filterKeywords = "a\nb"
        }
        val config = state.toConfig()
        assertEquals("SQL:", config.preparingPrefix)
        assertEquals(" | ", config.endMask)
        assertEquals(500, config.effectiveLineLimit)
        assertFalse(config.iBatisEnabled)
        assertEquals(listOf("a", "b"), config.filteredKeywords)
    }

    @Test
    fun `MyBatis 参数值以方括号开头时不会被误判为 iBatis`() {
        val parser = MyBatisLogParser()
        val result = parser.feed(
            "==>  Preparing: select * from t where blob = ?\n" +
                "==> Parameters: [B@1f2e3d(byte[])\n"
        )
        assertEquals(1, result.added.size)
        assertEquals(1, result.added[0].parameterCount)
    }
}
