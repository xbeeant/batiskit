package io.github.xbeeant.batiskit.log

import io.github.xbeeant.batiskit.log.SqlHighlighter.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** SQL 着色分词的纯逻辑断言：区间合法、关键字不被字符串 / 注释误伤 */
class SqlHighlighterTest {

    private fun pieces(sql: String, kind: Kind): List<String> =
        SqlHighlighter.tokenize(sql).filter { it.kind == kind }.map { sql.substring(it.start, it.end) }

    @Test
    fun `关键字 字符串 数值分别成段`() {
        val sql = "select * from t_user where name = 'O''Brien' and id = 12"
        assertTrue(pieces(sql, Kind.KEYWORD).contains("select"))
        assertTrue(pieces(sql, Kind.KEYWORD).contains("where"))
        assertEquals(listOf("'O''Brien'"), pieces(sql, Kind.STRING))
        assertEquals(listOf("12"), pieces(sql, Kind.NUMBER))
    }

    @Test
    fun `字符串与注释里的关键字不着色`() {
        val sql = "select 'select from', \"where\" from t -- and ? 注释\nwhere id = ?"
        val keywords = pieces(sql, Kind.KEYWORD)
        assertFalse(keywords.contains("and"))
        assertEquals(listOf("'select from'", "\"where\""), pieces(sql, Kind.STRING))
        assertEquals(1, pieces(sql, Kind.COMMENT).size)
        assertTrue(keywords.contains("where"))
    }

    @Test
    fun `没替换上的占位符单独标出`() {
        val sql = "select * from t where a = 1 and b = ?"
        assertEquals(listOf("?"), pieces(sql, Kind.PLACEHOLDER))
    }

    @Test
    fun `块注释与引号标识符整体保留`() {
        val sql = "select `from` from t /* select ? */ where id = ?"
        assertTrue(pieces(sql, Kind.STRING).contains("`from`"))
        assertEquals(listOf("/* select ? */"), pieces(sql, Kind.COMMENT))
        assertEquals(1, pieces(sql, Kind.PLACEHOLDER).size)
    }

    @Test
    fun `token 按序覆盖且互不重叠 不越界`() {
        val sql = "update t set a = 'x', b = 2 -- note\nwhere c = ?"
        val tokens = SqlHighlighter.tokenize(sql)
        var cursor = 0
        tokens.forEach { token ->
            assertTrue(token.start >= cursor)
            assertTrue(token.length > 0)
            assertTrue(token.end <= sql.length)
            cursor = token.end
        }
    }
}
