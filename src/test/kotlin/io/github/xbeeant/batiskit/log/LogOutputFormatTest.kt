package io.github.xbeeant.batiskit.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 输出格式开关与文案 Bundle 的纯逻辑测试 */
class LogOutputFormatTest {

    @Test
    fun `默认排版仍缩进 AND`() {
        val text = SqlFormatter.format("select * from t where a = 1 and b = 2")
        assertTrue(text.contains("\n  AND") || text.contains("\n  and") || text.contains("  AND"))
    }

    @Test
    fun `简单排版不拆 AND 也不缩进`() {
        val text = SqlFormatter.format("select * from t where a = 1 and b = 2", simple = true, newLineWithComment = false)
        assertFalse(text.contains("\n  AND") || text.contains("\n  and"))
        assertTrue(text.lines().any { it.contains("where", ignoreCase = true) && it.contains("and", ignoreCase = true) })
    }

    @Test
    fun `注释另起一行`() {
        val text = SqlFormatter.format("select 1 -- note\nfrom t", simple = true, newLineWithComment = true)
        assertTrue(text.contains("select 1\n-- note"))
    }

    @Test
    fun `字符串里的双横线不被当成注释`() {
        val text = SqlFormatter.format("select '-- not a comment' from t", simple = true, newLineWithComment = true)
        assertTrue(text.contains("'-- not a comment'"))
    }

    @Test
    fun `文案 bundle 占位符替换与缺失 key 兜底`() {
        assertTrue(MyBatisLogBundle.message("notify.cleared", 3).contains("3"))
        assertEquals("mybatis.log.no.such.key", MyBatisLogBundle.message("mybatis.log.no.such.key"))
    }
}
