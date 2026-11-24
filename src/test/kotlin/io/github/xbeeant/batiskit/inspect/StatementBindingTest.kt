package io.github.xbeeant.batiskit.inspect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 语句 ↔ 接口方法对应关系的纯逻辑测试（不依赖 IDE 运行环境）。
 */
class StatementBindingTest {

    private fun method(
        name: String,
        vararg annotations: String,
        hasBody: Boolean = false,
        inherited: Boolean = false
    ) = MapperMethod(name, annotations.toSet(), hasBody, inherited)

    // ------------------------------------------------------------------ 是否需要语句

    @Test
    fun `普通抽象方法需要语句`() {
        assertTrue(StatementBinding.needsStatement(method("selectById")))
    }

    @Test
    fun `带方法体的 default 方法不需要语句`() {
        assertFalse(StatementBinding.needsStatement(method("selectById", hasBody = true)))
    }

    @Test
    fun `继承来的方法不归本接口管`() {
        assertFalse(StatementBinding.needsStatement(method("selectById", inherited = true)))
    }

    @Test
    fun `注解式 SQL 不需要语句`() {
        listOf(
            "org.apache.ibatis.annotations.Select",
            "org.apache.ibatis.annotations.Insert",
            "org.apache.ibatis.annotations.Update",
            "org.apache.ibatis.annotations.Delete",
            "org.apache.ibatis.annotations.SelectProvider",
            "org.apache.ibatis.annotations.InsertProvider",
            "org.apache.ibatis.annotations.UpdateProvider",
            "org.apache.ibatis.annotations.DeleteProvider"
        ).forEach { fqn ->
            assertFalse("@$fqn 不应要求 XML 语句", StatementBinding.needsStatement(method("any", fqn)))
        }
    }

    @Test
    fun `无关注解不影响判定`() {
        assertTrue(
            StatementBinding.needsStatement(method("selectById", "org.apache.ibatis.annotations.Param"))
        )
    }

    @Test
    fun `注解简单名取最后一段`() {
        assertEquals("Select", StatementBinding.annotationName("org.apache.ibatis.annotations.Select"))
        assertEquals("Select", StatementBinding.annotationName("Select"))
        assertEquals("Select", StatementBinding.annotationName("annotations.Select"))
    }

    // ------------------------------------------------------------------ 缺失语句

    @Test
    fun `id 存在时不算缺失`() {
        assertEmpty(StatementBinding.missingStatements(listOf(method("list")), setOf("list", "insert")))
    }

    @Test
    fun `id 不存在时算缺失`() {
        val missing = StatementBinding.missingStatements(
            listOf(method("list"), method("selectByCode")),
            setOf("list")
        )
        assertEquals(listOf("selectByCode"), missing.map { it.name })
    }

    @Test
    fun `重载方法共用一个 id`() {
        // MyBatis 不支持重载：接口里同名方法只要有一条 id 就算绑定上了
        val missing = StatementBinding.missingStatements(
            listOf(method("find"), method("find")),
            setOf("find")
        )
        assertTrue(missing.isEmpty())
    }

    @Test
    fun `缺失判定沿用需要语句的口径`() {
        val missing = StatementBinding.missingStatements(
            listOf(
                method("annotated", "org.apache.ibatis.annotations.Select"),
                method("withBody", hasBody = true),
                method("inherited", inherited = true),
                method("real")
            ),
            emptySet()
        )
        assertEquals(listOf("real"), missing.map { it.name })
    }

    // ------------------------------------------------------------------ 孤立语句

    @Test
    fun `接口里有同名方法时不算孤立`() {
        assertEmpty(StatementBinding.orphanStatements(listOf("list", "insert"), setOf("list", "insert", "find")))
    }

    @Test
    fun `接口里没有同名方法时算孤立`() {
        val orphan = StatementBinding.orphanStatements(
            listOf("list", "selectByCode", "selectByCode"),
            setOf("list")
        )
        assertEquals("重复 id 只报一次", listOf("selectByCode"), orphan)
    }

    @Test
    fun `继承来的方法名也算有主`() {
        // BaseMapper 的 selectById 被 XML 覆盖是合法写法，不能报孤立
        assertEmpty(StatementBinding.orphanStatements(listOf("selectById"), setOf("selectById", "insert")))
    }

    // ------------------------------------------------------------------ 重复 id

    @Test
    fun `重复 id 统计次数`() {
        val duplicated = StatementBinding.duplicatedIds(listOf("a", "b", "a", "a", "c"))
        assertEquals(mapOf("a" to 3), duplicated)
    }

    @Test
    fun `全不重复时返回空`() {
        assertTrue(StatementBinding.duplicatedIds(listOf("a", "b", "c")).isEmpty())
    }

    private fun assertEmpty(actual: List<*>) {
        assertTrue("应为空，实际：$actual", actual.isEmpty())
    }
}
