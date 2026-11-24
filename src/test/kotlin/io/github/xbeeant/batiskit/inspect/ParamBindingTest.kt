package io.github.xbeeant.batiskit.inspect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Mapper 参数绑定校验的纯逻辑测试（不依赖 IDE 运行环境）。
 */
class ParamBindingTest {

    private fun ctx(vararg params: MapperParam, eachVars: Set<String> = emptySet()) =
        ParamBinding.context(params.toList(), eachVars)

    private fun first(source: String) = ParamBinding.expressions(source).single()

    // ------------------------------------------------------------------ 表达式解析

    @Test
    fun `解析井号占位符并给出精确范围`() {
        val source = "select * from t where id = #{id}"
        val expression = first(source)
        assertEquals("id", expression.root)
        assertTrue(expression.placeholder)
        assertEquals("#{id}", expression.text)
        assertEquals("#{id}", source.substring(expression.start, expression.end))
        assertEquals("id", source.substring(expression.rootStart, expression.rootEnd))
    }

    @Test
    fun `带 jdbcType 的表达式只取参数路径`() {
        val source = "where id = #{user.id,jdbcType=BIGINT}"
        val expression = first(source)
        assertEquals("user", expression.root)
        assertEquals("user", source.substring(expression.rootStart, expression.rootEnd))
    }

    @Test
    fun `美元占位符标记为非预编译`() {
        val source = "order by \${orderBy}"
        val expression = first(source)
        assertFalse(expression.placeholder)
        assertEquals("orderBy", expression.root)
    }

    @Test
    fun `表达式内部有空格时范围仍然准确`() {
        val source = "id = #{ user.id }"
        val expression = first(source)
        assertEquals("user", expression.root)
        assertEquals("user", source.substring(expression.rootStart, expression.rootEnd))
    }

    @Test
    fun `空表达式与动态表达式跳过`() {
        assertTrue(ParamBinding.expressions("where id = #{}").isEmpty())
        assertTrue(ParamBinding.expressions("where id = #{${'$'}{name}}").isEmpty())
        assertTrue(ParamBinding.expressions("select 1").isEmpty())
    }

    @Test
    fun `一段文本里的多个占位符都能取到`() {
        val expressions = ParamBinding.expressions("set a = #{a}, b = #{b}")
        assertEquals(listOf("a", "b"), expressions.map { it.root })
    }

    // ------------------------------------------------------------------ 合法名字

    @Test
    fun `未注解参数的名字与 paramN argN 都合法`() {
        val context = ctx(MapperParam("id"), MapperParam("name"))
        assertTrue(context.checkable)
        listOf("id", "name", "param1", "param2", "arg0", "arg1", "_parameter", "_databaseId")
            .forEach { assertTrue("$it 应该合法", it in context.validNames) }
    }

    @Test
    fun `写了 @Param 之后只能用注解里的名字`() {
        val context = ctx(MapperParam("id", "userId"))
        assertTrue("userId" in context.validNames)
        assertFalse("原始参数名不再可用", "id" in context.validNames)
    }

    @Test
    fun `单参数未注解不校验`() {
        assertFalse(ctx(MapperParam("user")).checkable)
        assertFalse(ctx(MapperParam("id")).checkable)
    }

    @Test
    fun `单参数写了 Param 或没有参数时校验`() {
        assertTrue(ctx(MapperParam("user", "user")).checkable)
        assertTrue(ctx().checkable)
    }

    // ------------------------------------------------------------------ 校验

    @Test
    fun `名字对得上不报问题`() {
        val context = ctx(MapperParam("userId"), MapperParam("status"))
        assertNull(ParamBinding.validate(first("where user_id = #{userId}"), context))
        assertNull(ParamBinding.validate(first("#{status}"), context))
    }

    @Test
    fun `名字对不上时报错并给出可用名字`() {
        val context = ctx(MapperParam("userId"), MapperParam("status"))
        val problem = ParamBinding.validate(first("#{user_id}"), context)!!
        assertTrue(problem.message.contains("user_id"))
        assertTrue(problem.message.contains("userId"))
        assertEquals("userId", problem.renameTo)
    }

    @Test
    fun `没有参数的方法任何占位符都报错`() {
        val problem = ParamBinding.validate(first("#{id}"), ctx())!!
        assertTrue(problem.message.contains("没有参数"))
        assertNull(problem.renameTo)
    }

    @Test
    fun `foreach 声明的变量合法`() {
        val context = ctx(MapperParam("ids"), eachVars = setOf("item", "i"))
        assertNull(ParamBinding.validate(first("#{item}"), context))
        assertNull(ParamBinding.validate(first("#{i}"), context))
    }

    @Test
    fun `单参数注解后属性路径以外的前缀也要对得上`() {
        val context = ctx(MapperParam("user", "user"))
        assertNull(ParamBinding.validate(first("#{user.name}"), context))
        val problem = ParamBinding.validate(first("#{person.name}"), context)!!
        // 名字差太远就不猜，只在提示里列出可用名字
        assertTrue(problem.message.contains("可用：user"))
        assertNull(problem.renameTo)
    }

    @Test
    fun `单参数注解后只差大小写也能建议改名`() {
        val context = ctx(MapperParam("user", "user"))
        assertEquals("user", ParamBinding.suggestRename("User", context))
    }

    // ------------------------------------------------------------------ 建议

    @Test
    fun `下划线写法建议改成驼峰`() {
        val context = ctx(MapperParam("userId"), MapperParam("status"))
        assertEquals("userId", ParamBinding.suggestRename("user_id", context))
    }

    @Test
    fun `差得太远就不瞎猜`() {
        val context = ctx(MapperParam("userId"), MapperParam("status"))
        assertNull(ParamBinding.suggestRename("departmentCode", context))
    }

    @Test
    fun `只有一个未注解参数时建议补 Param 给它`() {
        val context = ctx(MapperParam("ids"))
        assertEquals(0, ParamBinding.suggestAddParam("idList", context))
    }

    @Test
    fun `参数都注解过就不再建议补 Param`() {
        val context = ctx(MapperParam("id", "id"), MapperParam("name", "name"))
        assertNull(ParamBinding.suggestAddParam("user_id", context))
    }

    @Test
    fun `多个未注解参数时挑名字最像的`() {
        val context = ctx(MapperParam("userId"), MapperParam("status"))
        assertEquals(0, ParamBinding.suggestAddParam("user_id", context))
    }
}
