package io.github.xbeeant.batiskit.inspect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `${...}` 占位符定位与改写的纯逻辑测试。
 */
class DollarSubstitutionTest {

    @Test
    fun `定位全部占位符与偏移`() {
        val source = "select * from t_user order by \${orderColumn} limit \${size}"
        val occurrences = DollarSubstitution.occurrences(source)
        assertEquals(2, occurrences.size)
        assertEquals("\${orderColumn}", occurrences[0].text)
        assertEquals(source.indexOf("\${orderColumn}"), occurrences[0].start)
        assertEquals(source.indexOf("\${orderColumn}") + occurrences[0].text.length, occurrences[0].end)
    }

    @Test
    fun `混合写法只取美元占位符`() {
        val source = "where name like #{name} order by \${column}"
        val occurrences = DollarSubstitution.occurrences(source)
        assertEquals(1, occurrences.size)
        assertEquals("\${column}", occurrences[0].text)
    }

    @Test
    fun `改成井号语法保留表达式`() {
        val source = "\${orderBy} and \${ew.sqlSegment}"
        val occurrences = DollarSubstitution.occurrences(source)
        assertEquals("#{orderBy}", DollarSubstitution.toHashSyntax(occurrences[0]))
        assertEquals("#{ew.sqlSegment}", DollarSubstitution.toHashSyntax(occurrences[1]))
        assertTrue(occurrences.isNotEmpty())
    }

    @Test
    fun `无占位符时返回空`() {
        assertEquals(0, DollarSubstitution.occurrences("select 1 from t").size)
    }
}
