package io.github.xbeeant.batiskit.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** MyBatis Log 搜索表达式：AND 关键字 / 排除项 / 正则 / 非法正则回退 */
class LogSearchQueryTest {

    private val text = "SELECT id, name FROM t_user WHERE name = 'alice'\nSELECT id, name FROM t_user WHERE name = ?"

    @Test
    fun `空查询匹配一切`() {
        assertTrue(LogSearchQuery.parse("").isEmpty)
        assertTrue(LogSearchQuery.parse("   ").isEmpty)
        assertTrue(LogSearchQuery.matches(text, LogSearchQuery.parse("  ")))
    }

    @Test
    fun `空格分隔的关键字是 AND 且忽略大小写`() {
        assertTrue(LogSearchQuery.matches(text, LogSearchQuery.parse("select alice")))
        assertTrue(LogSearchQuery.matches(text, LogSearchQuery.parse("SELECT NAME")))
        assertFalse(LogSearchQuery.matches(text, LogSearchQuery.parse("select alice bob")))
    }

    @Test
    fun `减号前缀排除含该词的语句`() {
        val query = LogSearchQuery.parse("-alice")
        assertEquals(emptyList<String>(), query.include)
        assertEquals(listOf("alice"), query.exclude)
        assertFalse(LogSearchQuery.matches(text, query))
        assertTrue(LogSearchQuery.matches("SELECT * FROM t_order", query))
        // 排除与包含叠加
        assertTrue(LogSearchQuery.matches("select 1 from t_order", LogSearchQuery.parse("select -t_user")))
        assertFalse(LogSearchQuery.matches(text, LogSearchQuery.parse("select -user")))
    }

    @Test
    fun `斜杠包围的是忽略大小写的正则`() {
        val query = LogSearchQuery.parse("/name\\s*=\\s*'alice'/")
        assertEquals(1, query.regexes.size)
        assertTrue(LogSearchQuery.matches(text, query))
        assertTrue(LogSearchQuery.matches("NAME='ALICE'", LogSearchQuery.parse("/name='alice'/")))
        assertFalse(LogSearchQuery.matches("SELECT 1", query))
    }

    @Test
    fun `非法正则退化成字面量 不让搜索失灵`() {
        val query = LogSearchQuery.parse("/foo(/")
        assertTrue(LogSearchQuery.matches("where foo( = 1", query))
        assertFalse(LogSearchQuery.matches("where bar = 1", query))
        // 单字符正则也正常工作
        assertTrue(LogSearchQuery.matches("axb", LogSearchQuery.parse("/x/")))
    }

    @Test
    fun `单独减号与双减号按普通文本处理`() {
        assertTrue(LogSearchQuery.matches("x -- comment", LogSearchQuery.parse("--")))
        assertTrue(LogSearchQuery.matches("x - y", LogSearchQuery.parse("-")))
    }

    @Test
    fun `三类条件同时生效`() {
        val query = LogSearchQuery.parse("select -t_order /t_user/")
        assertTrue(LogSearchQuery.matches(text, query))
        assertFalse(LogSearchQuery.matches("SELECT * FROM t_user JOIN t_order", query))
        assertFalse(LogSearchQuery.matches("SELECT * FROM t_role", query))
    }
}
