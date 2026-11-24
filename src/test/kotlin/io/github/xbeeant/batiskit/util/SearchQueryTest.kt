package io.github.xbeeant.batiskit.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.swing.table.DefaultTableModel
import javax.swing.table.TableRowSorter

/** 通用搜索表达式（工具窗口与表格共用）的纯 JVM 测试 */
class SearchQueryTest {

    @Test
    fun `空表达式匹配一切`() {
        val query = SearchQuery.parse("   ")
        assertTrue(query.isEmpty)
        assertTrue(SearchQuery.matches("anything", query))
        assertTrue(SearchQuery.matches(listOf("a", "b"), query))
    }

    @Test
    fun `空格分隔 AND 且忽略大小写`() {
        val query = SearchQuery.parse("user NAME")
        assertTrue(SearchQuery.matches("SELECT user_name FROM t_user", query))
        assertFalse(SearchQuery.matches("SELECT name FROM t_order", query))
    }

    @Test
    fun `减号前缀排除命中行`() {
        val query = SearchQuery.parse("user -password")
        assertTrue(SearchQuery.matches("user_name", query))
        assertFalse(SearchQuery.matches("user_password", query))
    }

    @Test
    fun `斜杠包裹的正则与非法正则回退`() {
        assertTrue(SearchQuery.matches("user_id_12", SearchQuery.parse("/id_\\d+/")))
        assertFalse(SearchQuery.matches("user_id_ab", SearchQuery.parse("/id_\\d+/")))
        // 括号没闭合，退化成字面量而不是让搜索框失灵
        assertTrue(SearchQuery.matches("foo(bar", SearchQuery.parse("/foo(/")))
    }

    @Test
    fun `负数字面量与 SQL 注释不当成排除项`() {
        val minusOne = SearchQuery.parse("-1")
        assertTrue(minusOne.exclude.isEmpty())
        assertTrue(SearchQuery.matches("where x = -1", minusOne))
        assertTrue(SearchQuery.parse("--").exclude.isEmpty())
    }

    @Test
    fun `多列文本拼起来匹配`() {
        val query = SearchQuery.parse("userName String")
        assertTrue(SearchQuery.matches(listOf("userName", "String", "用户名"), query))
        assertFalse(SearchQuery.matches(listOf("userName", "Long"), query))
    }

    private fun tableModel(): DefaultTableModel {
        val data = arrayOf(
            arrayOf<Any>("userName", "String", "用户名"),
            arrayOf<Any>("password", "String", "密码"),
            arrayOf<Any>("id", "Long", "主键"),
        )
        return DefaultTableModel(data, arrayOf<Any>("字段", "类型", "说明"))
    }

    @Test
    fun `rowFilter 按整行内容过滤表格`() {
        val sorter = TableRowSorter(tableModel())
        sorter.rowFilter = SearchQuery.rowFilter(SearchQuery.parse("String -password"))
        assertEquals(1, sorter.viewRowCount)
        assertEquals("userName", sorter.model.getValueAt(sorter.convertRowIndexToModel(0), 0))
    }

    @Test
    fun `多列正则逐列匹配带锚点的表达式`() {
        assertTrue(SearchQuery.matches(listOf("t_user", "User"), SearchQuery.parse("/^t_user$/")))
        assertFalse(SearchQuery.matches(listOf("x_t_user", "User"), SearchQuery.parse("/^t_user$/")))
        assertTrue(SearchQuery.matches(listOf("任意", "订单表"), SearchQuery.parse("/^订单/")))
    }

    @Test
    fun `rowFilter 空表达式放行所有行`() {
        val sorter = TableRowSorter(tableModel())
        sorter.rowFilter = SearchQuery.rowFilter(SearchQuery.parse(""))
        assertEquals(3, sorter.viewRowCount)
    }

    @Test
    fun `rowFilter 支持正则`() {
        val sorter = TableRowSorter(tableModel())
        sorter.rowFilter = SearchQuery.rowFilter(SearchQuery.parse("/^id|password/"))
        assertEquals(2, sorter.viewRowCount)
    }
}
