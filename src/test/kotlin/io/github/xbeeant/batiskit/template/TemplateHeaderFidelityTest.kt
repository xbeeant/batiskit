package io.github.xbeeant.batiskit.template

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 模板方案「导出 → 导入」的头部保真回归。
 *
 * 旧实现把 front-matter 解析成 Map 后按固定字段顺序重建，注释 / 空行 / group / order 全丢；
 * 现在 [TemplateSpec.header] 留原文，[TemplateSpec.rawText] 逐字还原。
 */
class TemplateHeaderFidelityTest {

    /** 仿内置模板：头部有注释、空行、group / order，正文有注释和 XML 特殊字符 */
    private fun builtinStyle(): String = buildString {
        append("---\n")
        append("# 头部注释：这套模板由团队维护\n")
        append("id: entity\n")
        append("name: 实体类\n")
        append("\n")
        append("group: 基础\n")
        append("order: 10\n")
        append("output: {basePackageDir}/model/{entity}.java\n")
        append("root: java\n")
        append("merge: java\n")
        append("framework: mybatis\n")
        append("---\n")
        append("package {package};\n")
        append("## 正文注释也要原样保留\n")
        append("public class {entity} { String s = \"<xml> & ]]>\"; }\n")
    }

    @Test
    fun `解析保留头部原文与全部字段`() {
        val text = builtinStyle()
        val parsed = TemplateSpec.parse(text, "entity.tpl")
        assertNull(parsed.error)
        assertEquals("基础", parsed.spec.group)
        assertEquals(10, parsed.spec.order)
        assertEquals("mybatis", parsed.spec.framework)
        assertTrue(parsed.spec.header.contains("# 头部注释"))
        assertTrue(parsed.spec.header.contains("\ngroup: 基础\norder: 10\n"))
    }

    @Test
    fun `rawText 逐字还原内置模板全文`() {
        val text = builtinStyle()
        assertEquals(text, TemplateSpec.parse(text, "entity.tpl").spec.rawText())
    }

    @Test
    fun `头部注释与空行不再被校验判为非法`() {
        val bundle = TemplateSetBundle.Bundle(
            "my-set", listOf(TemplateSetBundle.Item("entity", "实体类", builtinStyle()))
        )
        assertTrue(TemplateSetBundle.validate(bundle).joinToString("\n"), TemplateSetBundle.validate(bundle).isEmpty())
    }

    @Test
    fun `导出再导入头部逐字保真`() {
        val text = builtinStyle()
        val xml = TemplateSetBundle.encode("my-set", listOf(TemplateSetBundle.Item("entity", "实体类", text)))
        val restored = TemplateSetBundle.decode(xml)
        assertEquals(text, restored.items[0].text)
        assertTrue(TemplateSetBundle.validate(restored).isEmpty())
        assertEquals(10, TemplateSpec.parse(restored.items[0].text).spec.order)
    }

    @Test
    fun `withSet 只改 set 行不碰注释顺序与字段`() {
        val result = TemplateSetBundle.withSet(builtinStyle(), "imported")
        assertTrue(result.contains("# 头部注释：这套模板由团队维护\n"))
        assertTrue(result.contains("name: 实体类\n\ngroup: 基础\norder: 10\n"))
        assertTrue(result.contains("\nset: imported\n---\n"))
        assertFalse(result.contains("set: origin"))
        val reparsed = TemplateSpec.parse(result, "entity.tpl")
        assertNull(reparsed.error)
        assertEquals("imported", reparsed.spec.set)
        assertEquals("基础", reparsed.spec.group)
        assertEquals(10, reparsed.spec.order)
    }

    @Test
    fun `withSet 保留原有 set 行的缩进与行尾注释`() {
        val withSetLine = builtinStyle()
            .replace("framework: mybatis\n", "  set: origin  # 归属方案\nframework: mybatis\n")
        val result = TemplateSetBundle.withSet(withSetLine, "new-set")
        assertTrue(result.contains("  set: new-set  # 归属方案\n"))
        assertFalse(result.contains("set: origin"))
        val reparsed = TemplateSpec.parse(result, "entity.tpl")
        assertNull(reparsed.error)
        assertEquals("new-set", reparsed.spec.set)
    }

    @Test
    fun `withSet 插入新行时沿用头部缩进`() {
        val text = builtinStyle().replace(Regex("(?m)^(id|name|group|order|output|root|merge|framework):"), "  $1:")
        val result = TemplateSetBundle.withSet(text, "indent")
        assertTrue(result.contains("\n  set: indent\n"))
        assertEquals("indent", TemplateSpec.parse(result).spec.set)
    }

    @Test
    fun `行内注释不进字段值但留在头部原文里`() {
        val text = builtinStyle().replace("order: 10\n", "order: 10  # 小的在前\n")
        val spec = TemplateSpec.parse(text, "entity.tpl").spec
        assertEquals(10, spec.order)
        assertTrue(spec.header.contains("order: 10  # 小的在前"))
        assertEquals(text, spec.rawText())
    }

    @Test
    fun `replaceSetLine 只换值保留缩进与注释`() {
        assertEquals("  set: b  # note", TemplateSetBundle.replaceSetLine("  set: a  # note", "b"))
        assertEquals("set: b", TemplateSetBundle.replaceSetLine("set: a", "b"))
    }

    @Test
    fun `纯代码构造的 spec 也能重建出完整头部`() {
        val spec = TemplateSpec(
            id = "x", name = "X", output = "a.java",
            group = "基础", order = 7, set = "mine", requires = "2",
            body = "class X {}\n"
        )
        val reparsed = TemplateSpec.parse(spec.rawText(), "x.tpl")
        assertNull(reparsed.error)
        assertEquals("基础", reparsed.spec.group)
        assertEquals(7, reparsed.spec.order)
        assertEquals("mine", reparsed.spec.set)
        assertEquals("2", reparsed.spec.requires)
        assertEquals("class X {}\n", reparsed.spec.body)
    }

    @Test
    fun `未知字段仍然被拒绝`() {
        val bad = builtinStyle().replace("group: 基础\n", "groupp: 基础\n")
        assertTrue(TemplateSpec.parse(bad, "entity.tpl").error!!.contains("未知的头部字段"))
    }
}
