package io.github.xbeeant.batiskit.template

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 模板方案导入 / 导出的纯逻辑测试 */
class TemplateSetBundleTest {

    private fun tpl(id: String, setLine: String = ""): String = buildString {
        append("---\n")
        append("id: ").append(id).append('\n')
        append("name: ").append(id).append('\n')
        append("output: {basePackageDir}/model/{entity}.java\n")
        if (setLine.isNotEmpty()) append(setLine).append('\n')
        append("---\n")
        append("public class X { String s = \"<xml> & ]]>\"; }\n")
    }

    private fun item(id: String, setLine: String = "") =
        TemplateSetBundle.Item(id, id, tpl(id, setLine))

    @Test
    fun `导出再导入保留模板全文里的特殊字符`() {
        val source = item("entity")
        val xml = TemplateSetBundle.encode("my-set", listOf(source))
        val bundle = TemplateSetBundle.decode(xml)
        assertEquals("my-set", bundle.set)
        assertEquals(1, bundle.items.size)
        assertEquals(source.text, bundle.items[0].text)
        assertTrue(bundle.items[0].text.contains("<xml> & ]]>"))
        assertTrue(TemplateSetBundle.validate(bundle).isEmpty())
    }

    @Test
    fun `withSet 给没有 set 的头部注入一行`() {
        val result = TemplateSetBundle.withSet(tpl("entity"), "imported")
        assertTrue(result.contains("\nset: imported\n"))
        val parsed = TemplateSpec.parse(result, "entity.tpl")
        assertEquals(null, parsed.error)
        assertEquals("imported", parsed.spec.set)
    }

    @Test
    fun `withSet 改写已有的 set 声明`() {
        val result = TemplateSetBundle.withSet(tpl("entity", "set: origin"), "new-set")
        assertTrue(result.contains("set: new-set"))
        assertFalse(result.contains("set: origin"))
        assertEquals("new-set", TemplateSpec.parse(result, "entity.tpl").spec.set)
    }

    @Test
    fun `withSet 碰到坏头部原样返回交给校验拦截`() {
        val bad = "no front matter"
        assertEquals(bad, TemplateSetBundle.withSet(bad, "x"))
        assertTrue(TemplateSetBundle.validate(
            TemplateSetBundle.Bundle("x", listOf(TemplateSetBundle.Item("bad", "", bad)))
        ).isNotEmpty())
    }

    @Test
    fun `withSet 规范化 CRLF 并保留正文换行`() {
        val crlf = tpl("entity").replace("\n", "\r\n")
        val result = TemplateSetBundle.withSet(crlf, "crlf")
        assertFalse(result.contains("\r"))
        assertTrue(result.contains("set: crlf"))
        assertEquals(null, TemplateSpec.parse(result, "entity.tpl").error)
    }

    @Test
    fun `validate 拒绝非法方案名与重复 id`() {
        val problems = TemplateSetBundle.validate(
            TemplateSetBundle.Bundle("../bad name", listOf(item("entity"), item("entity")))
        )
        assertTrue(problems.any { it.contains("方案名不合法") })
        assertTrue(problems.any { it.contains("id 重复") })
    }

    @Test
    fun `validate 拒绝空方案与空模板`() {
        assertTrue(TemplateSetBundle.validate(TemplateSetBundle.Bundle("x", emptyList()))
            .any { it.contains("没有模板") })
        val problems = TemplateSetBundle.validate(
            TemplateSetBundle.Bundle("x", listOf(TemplateSetBundle.Item("blank", "", "")))
        )
        assertTrue(problems.any { it.contains("内容为空") })
    }

    @Test
    fun `validate 拒绝头部不合法与超大模板`() {
        val bad = TemplateSetBundle.validate(
            TemplateSetBundle.Bundle("x", listOf(TemplateSetBundle.Item("bad", "", "no header")))
        )
        assertTrue(bad.any { it.contains("缺少头部声明") })

        val huge = TemplateSetBundle.validate(
            TemplateSetBundle.Bundle(
                "x",
                listOf(TemplateSetBundle.Item("huge", "", tpl("huge") + "x".repeat(TemplateSetBundle.MAX_TEXT_LENGTH)))
            )
        )
        assertTrue(huge.any { it.contains("512 KB") })
    }

    @Test
    fun `decode 拒绝空文件坏 XML 与其它根标签`() {
        runCatching { TemplateSetBundle.decode("  ") }.also {
            assertTrue(it.exceptionOrNull()?.message!!.contains("文件为空"))
        }
        runCatching { TemplateSetBundle.decode("<broken>") }.also {
            assertTrue(it.exceptionOrNull()?.message!!.contains("XML 解析失败"))
        }
        runCatching { TemplateSetBundle.decode("<other-root/>") }.also {
            assertTrue(it.exceptionOrNull()?.message!!.contains("不是模板方案文件"))
        }
    }

    @Test
    fun `DOCTYPE 与外部实体被拒绝`() {
        val xml = """
            <?xml version="1.0"?>
            <!DOCTYPE foo [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
            <batiskit-template-set version="1" name="x"><template id="a">&xxe;</template></batiskit-template-set>
        """.trimIndent()
        assertTrue(runCatching { TemplateSetBundle.decode(xml) }.isFailure)
    }

    @Test
    fun `keyOf 带方案前缀避免覆盖内置模板`() {
        assertEquals("my-set/entity", TemplateSetBundle.keyOf("my-set", "entity"))
    }
}
