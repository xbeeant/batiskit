package io.github.xbeeant.batiskit.format

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 格式化器的可配置选项：缩进宽度、内联阈值、非法值收敛与默认行为等价 */
class MapperXmlFormatterOptionsTest {

    /** 用很小的 inlineMax 强制 <if> 展开成块级，缩进层级才看得出来 */
    private val xml = """
        <mapper namespace="a.b">
        <select id="x">
        <if test="a != null">SELECT 1</if>
        </select>
        </mapper>
    """.trimIndent()

    @Test
    fun `默认 Options 与不带参数的既有行为完全一致`() {
        val options = MapperXmlFormatter.Options()
        assertEquals(MapperXmlFormatter.format(xml), MapperXmlFormatter.format(xml, options))
        assertEquals(
            MapperXmlFormatter.indentAt(xml, xml.indexOf("<select")),
            MapperXmlFormatter.indentAt(xml, xml.indexOf("<select"), options)
        )
        val fragment = "<select id=\"x\">\nSELECT 1\n</select>"
        assertEquals(
            MapperXmlFormatter.formatFragment(fragment, 8),
            MapperXmlFormatter.formatFragment(fragment, 8, options)
        )
    }

    @Test
    fun `缩进宽度 2 时逐层缩进为 2 4 6 空格`() {
        val options = MapperXmlFormatter.Options(indentUnit = 2, inlineMax = 20)
        val out = MapperXmlFormatter.format(xml, options)
        val lines = out.lines()
        assertTrue("select 应为 2 空格：$out", lines.any { it == "  <select id=\"x\">" })
        assertTrue("if 应为 4 空格：$out", lines.any { it == "    <if test=\"a != null\">" })
        assertTrue("SQL 正文应为 6 空格：$out", lines.any { it == "      SELECT 1" })
    }

    @Test
    fun `缩进宽度 8 时顶层子元素为 8 空格`() {
        val options = MapperXmlFormatter.Options(indentUnit = 8, inlineMax = 20)
        val out = MapperXmlFormatter.format(xml, options)
        assertTrue(out.lines().any { it == "        <select id=\"x\">" })
        assertEquals("        ", MapperXmlFormatter.indentAt(xml, xml.indexOf("<select"), options))
    }

    @Test
    fun `inlineMax 调小后短叶子元素也展开成块`() {
        val normal = MapperXmlFormatter.format(xml)
        assertTrue("默认阈值下 <if> 保持内联", normal.lines().any { it.contains("<if test=\"a != null\">SELECT 1</if>") })

        val narrow = MapperXmlFormatter.format(xml, MapperXmlFormatter.Options(inlineMax = 20))
        assertTrue("阈值调小后 <if> 独占一行", narrow.lines().any { it.trim() == "<if test=\"a != null\">" })
        assertTrue(narrow.lines().any { it.trim() == "SELECT 1" })
    }

    @Test
    fun `非法选项值收敛到合法范围`() {
        assertEquals(1, MapperXmlFormatter.Options(indentUnit = 0).safeIndent)
        assertEquals(8, MapperXmlFormatter.Options(indentUnit = 100).safeIndent)
        assertEquals(20, MapperXmlFormatter.Options(inlineMax = 0).safeInlineMax)
        assertEquals(1000, MapperXmlFormatter.Options(inlineMax = 100000).safeInlineMax)
        assertEquals("", MapperXmlFormatter.Options(indentUnit = 2).indent(0))
        assertEquals("    ", MapperXmlFormatter.Options(indentUnit = 2).indent(2))
    }

    @Test
    fun `带选项格式化仍然幂等`() {
        val options = MapperXmlFormatter.Options(indentUnit = 3, inlineMax = 40)
        val once = MapperXmlFormatter.format(xml, options)
        assertEquals(once, MapperXmlFormatter.format(once, options))
    }
}
