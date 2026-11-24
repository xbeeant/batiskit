package io.github.xbeeant.batiskit.template

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * 项目级模板（`.batiskit/templates/`）的扫描与合并规则。
 *
 * 这些用例不需要 IntelliJ 平台：模板仓库在无应用容器时走兜底实例，
 * 所以直接拿临时目录当「项目根」就能验证整套扫描逻辑。
 */
class ProjectTemplatesTest {

    private fun projectRoot(): File = Files.createTempDirectory("batiskit-tpl").toFile()

    private fun write(root: File, set: String, name: String, text: String): File =
        File(root, "${ProjectTemplates.DIR}/$set/$name").apply {
            parentFile.mkdirs()
            writeText(text)
        }

    /** 一份能通过头部校验的最小模板 */
    private fun tpl(id: String, order: Int = 100, extra: String = ""): String = """
        ---
        id: $id
        name: $id
        order: $order
        output: out/$id.java
        $extra
        ---
        body of $id
    """.trimIndent()

    @Test
    fun `每个文件夹是一套方案，方案名取文件夹名`() {
        val root = projectRoot()
        write(root, "acme", "entity.tpl", tpl("acmeEntity"))
        write(root, "acme", "mapper.tpl", tpl("acmeMapper"))
        write(root, "other", "entity.tpl", tpl("otherEntity"))

        val scan = ProjectTemplates.scan(root.absolutePath)

        assertEquals(setOf("acme", "other"), scan.sets.keys)
        assertEquals(2, scan.sets.getValue("acme").size)
        assertEquals(3, scan.count)
        assertTrue("set 由文件夹决定", scan.sets.getValue("acme").all { it.set == "acme" })
        assertTrue("解析问题应为空", scan.errors.isEmpty())
    }

    @Test
    fun `模板按 order 排序`() {
        val root = projectRoot()
        write(root, "acme", "b.tpl", tpl("second", order = 20))
        write(root, "acme", "a.tpl", tpl("first", order = 10))

        val scan = ProjectTemplates.scan(root.absolutePath)

        assertEquals(listOf("first", "second"), scan.sets.getValue("acme").map { it.id })
    }

    @Test
    fun `头部写坏的模板只影响自己`() {
        val root = projectRoot()
        write(root, "acme", "good.tpl", tpl("good"))
        write(root, "acme", "bad.tpl", "没有头部的模板")

        val scan = ProjectTemplates.scan(root.absolutePath)

        assertEquals(1, scan.sets.getValue("acme").size)
        assertEquals("good", scan.sets.getValue("acme").single().id)
        val error = scan.errors.entries.single()
        assertTrue("错误定位到文件：${error.key}", error.key.endsWith("acme/bad.tpl"))
        assertNotNull(error.value)
    }

    @Test
    fun `同一方案里 id 重复时后一份被忽略并报错`() {
        val root = projectRoot()
        write(root, "acme", "a.tpl", tpl("dup"))
        write(root, "acme", "b.tpl", tpl("dup"))

        val scan = ProjectTemplates.scan(root.absolutePath)

        assertEquals(1, scan.sets.getValue("acme").size)
        assertTrue(scan.errors.keys.single().endsWith("acme/b.tpl"))
        assertTrue(scan.errors.values.single().contains("dup"))
    }

    @Test
    fun `模板目录里没有特殊目录名`() {
        val root = projectRoot()
        // 排版块在 resources/blocks/，模板目录里不再有 blocks 这种例外：放进来就是一套普通方案
        write(root, "blocks", "fields.tpl", tpl("fields"))
        write(root, "acme", "entity.tpl", tpl("acmeEntity"))

        val scan = ProjectTemplates.scan(root.absolutePath)

        assertEquals(setOf("acme", "blocks"), scan.sets.keys)
    }

    @Test
    fun `目录不存在时返回空结果且不报错`() {
        val root = projectRoot()

        val scan = ProjectTemplates.scan(root.absolutePath)

        assertTrue(scan.sets.isEmpty())
        assertTrue(scan.errors.isEmpty())
        assertTrue(
            "没有项目上下文同样返回空",
            ProjectTemplates.scan(null).sets.isEmpty() && ProjectTemplates.scan("").sets.isEmpty()
        )
    }

    @Test
    fun `内容没变时复用解析结果，改文件后重新扫描`() {
        val root = projectRoot()
        val file = write(root, "acme", "entity.tpl", tpl("acmeEntity"))

        val first = ProjectTemplates.scan(root.absolutePath)
        assertSame("指纹没变应命中缓存", first, ProjectTemplates.scan(root.absolutePath))

        // 写文件后同一毫秒内 mtime 可能不变，显式往前推一秒保证指纹变化
        file.writeText(tpl("renamed"))
        file.setLastModified(file.lastModified() + 1_000)

        val second = ProjectTemplates.scan(root.absolutePath)
        assertEquals("renamed", second.sets.getValue("acme").single().id)
        assertFalse("内容变了不该命中缓存", first === second)
    }

    @Test
    fun `没有项目上下文时行为与之前一致`() {
        assertEquals(
            "内置两套方案",
            listOf("mybatis-plus-pure", "mybatis-pure"),
            TemplateRegistry.sets(null).map { it.id }
        )
    }

    @Test
    fun `新建文件会替换掉旧的缓存条目`() {
        val root = projectRoot()
        write(root, "acme", "entity.tpl", tpl("acmeEntity"))
        assertEquals(1, ProjectTemplates.scan(root.absolutePath).sets.size)

        write(root, "second", "mapper.tpl", tpl("secondMapper"))
        // 目录里多了文件，即使已有文件 mtime 没变，指纹也要变化
        assertEquals(2, ProjectTemplates.scan(root.absolutePath).sets.size)
    }

    @Test
    fun `隐藏目录不算方案`() {
        val root = projectRoot()
        write(root, ".git", "hooks.tpl", tpl("hook"))
        write(root, "acme", "entity.tpl", tpl("acmeEntity"))

        assertEquals(setOf("acme"), ProjectTemplates.scan(root.absolutePath).sets.keys)
    }
}
