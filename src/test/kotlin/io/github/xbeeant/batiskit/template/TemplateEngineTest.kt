package io.github.xbeeant.batiskit.template

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 模板引擎的纯逻辑测试：语法、求值、错误定位。
 *
 * 注意：Kotlin 字符串里的 `$` 必须写成 `\$`，否则会被 Kotlin 自己插值。
 */
class TemplateEngineTest {

    /** 与 TemplateRegistry 同一条路径：大括号词表 = 兜底词表 + 模型自己的顶层键 */
    private fun render(source: String, vararg model: Pair<String, Any?>): String {
        val scope = mapOf(*model)
        return Template.compile(source, names = TemplatePlaceholders.namesOf(scope)).render(scope).text
    }

    // ------------------------------------------------------------------ 引用与转义

    @Test
    fun `简单变量与属性`() {
        assertEquals(
            "User in com.example",
            render("\$entity in \$pkg.base", "entity" to "User", "pkg" to mapOf("base" to "com.example"))
        )
    }

    @Test
    fun `花括号写法可以紧贴文字`() {
        assertEquals("UserDTO", render("\${entity}DTO", "entity" to "User"))
    }

    // ------------------------------------------------------------------ 大括号占位符（{Model}）

    @Test
    fun `大括号占位符等价于美元写法`() {
        assertEquals(
            "User in com.example.demo.entity",
            render("{Model} in {pkg.entity}", "entity" to "User", "Model" to "User", "pkg" to mapOf("entity" to "com.example.demo.entity"))
        )
    }

    @Test
    fun `大括号占位符可带属性与用于函数实参`() {
        val model = mapOf<String, Any?>(
            "singlePk" to mapOf("field" to "id"),
            "hasPk" to true,
            "util" to mapOf("paste" to TemplateFunction { args -> "[" + args.joinToString(",") + "]" })
        )
        assertEquals("id", Template.compile("{singlePk.field}").render(model).text)
        assertEquals("[id]", Template.compile("\$util.paste({singlePk.field})").render(model).text)
    }

    @Test
    fun `大括号条件`() {
        assertEquals("yes", Template.compile("#if({hasPk})yes#end").render(mapOf("hasPk" to true)).text)
    }

    @Test
    fun `不认识的大括号原样输出`() {
        // Rest 路径占位符 / MyBatis 参数都不是模板变量，不能被替换
        assertEquals(
            "path /{id} param #{key} unknown {Foo}",
            Template.compile("path /{id} param #{key} unknown {Foo}").render(mapOf("key" to "Long")).text
        )
    }

    @Test
    fun `大括号可以转义与原样块内不生效`() {
        assertEquals("{Model}", Template.compile("\\{Model}").render(mapOf("Model" to "User")).text)
        assertEquals("{Model}", Template.compile("#[[{Model}]]#").render(mapOf("Model" to "User")).text)
    }

    @Test
    fun `未定义变量输出空串并记录告警`() {
        val result = Template.compile("a\${missing}b").render(emptyMap())
        assertEquals("ab", result.text)
        assertTrue(result.warnings.single().contains("missing"))
    }

    @Test
    fun `追加变量只对本次渲染可见且优先于模型`() {
        // 正文里的 {package} 就是这么注入的：模型不动，换一份追加值渲染结果才跟着变
        val model = mapOf<String, Any?>("package" to "com.old", "entity" to "User")
        val template = Template.compile("package \${package}; \${entity}", names = TemplatePlaceholders.namesOf(model))
        assertEquals("package com.new; User", template.render(model, mapOf("package" to "com.new")).text)
        assertEquals("package com.old; User", template.render(model).text)
        assertEquals("com.old", model["package"])
    }

    @Test
    fun `双引号插值里的未定义变量同样进告警`() {
        // `#set($x = "${...}")` 的插值走同一个作用域与告警列表，写错变量名不能静悄悄渲染成空串
        val result = Template.compile("#set(\$x = \"\${missing}\")[\$x]").render(emptyMap())
        assertEquals("[]", result.text)
        assertTrue(result.warnings.single().contains("missing"))
    }

    @Test
    fun `井号与美元可用反斜杠转义`() {
        assertEquals("#{id} \$x", render("\\#{id} \\\$x"))
    }

    @Test
    fun `原样块里的语法不解析`() {
        assertEquals("if (a && b) { return \$x; }", render("#[[if (a && b) { return \$x; }]]#"))
    }

    @Test
    fun `注释被丢弃`() {
        assertEquals("ab", render("a## 注释\nb"))
        assertEquals("ab", render("a#* 快注释 *#b"))
    }

    @Test
    fun `MyBatis 的井号占位符原样输出`() {
        assertEquals("where id = #{id}", render("where id = #{id}"))
    }

    // ------------------------------------------------------------------ 条件

    @Test
    fun `if elseif else 分支`() {
        val source = "#if(\$n == 1)one#elseif(\$n == 2)two#else#end"
        assertEquals("one", render(source, "n" to 1))
        assertEquals("two", render(source, "n" to 2))
        assertEquals("", render(source, "n" to 3))
    }

    @Test
    fun `真值判定与 Velocity 一致`() {
        val source = "#if(\$v)yes#else no#end"
        assertEquals("yes", render(source, "v" to "x"))
        assertEquals(" no", render(source, "v" to ""))
        assertEquals(" no", render(source, "v" to emptyList<String>()))
        assertEquals(" no", render(source, "v" to 0))
        assertEquals(" no", render(source, "v" to null))
    }

    @Test
    fun `布尔与比较运算`() {
        assertEquals("y", render("#if(\$a && !\$b)y#end", "a" to true, "b" to false))
        assertEquals("y", render("#if(\$a == 'x' || \$n >= 3)y#end", "a" to "x", "n" to 3))
        assertEquals("y", render("#if(\$n < 5 && \$n > 1)y#end", "n" to 3))
    }

    @Test
    fun `加号在字符串侧做拼接`() {
        assertEquals("a1", render("\$a\$b", "a" to "a", "b" to 1))
        assertEquals("3", render("#set(\$c = 1 + 2)\$c"))
    }

    // ------------------------------------------------------------------ 循环

    @Test
    fun `foreach 遍历与内置序号`() {
        val source = "#foreach(\$c in \$columns)[\$foreach.index:\$c]#end"
        assertEquals("[0:id][1:name]", render(source, "columns" to listOf("id", "name")))
    }

    @Test
    fun `foreach 的最后一项判断`() {
        val source = "#foreach(\$c in \$columns)\$c#if(!\$foreach.last),#end#end"
        assertEquals("id,name,age", render(source, "columns" to listOf("id", "name", "age")))
    }

    @Test
    fun `循环变量不泄漏到循环外`() {
        val result = Template.compile("#foreach(\$c in \$columns)#end\$c").render(mapOf("columns" to listOf("a")))
        assertEquals("", result.text)
        assertTrue("循环外的 \$c 应被当成未定义", result.warnings.single().contains("c"))
    }

    @Test
    fun `set 定义的变量可在后续使用`() {
        assertEquals(
            "com.example.User",
            render(
                "#set(\$fqn = \"\$pkg.base.\${entity}\")\$fqn",
                "pkg" to mapOf("base" to "com.example"),
                "entity" to "User"
            )
        )
    }

    // ------------------------------------------------------------------ 函数

    @Test
    fun `模型注入的函数可调用`() {
        val util = mapOf<String, Any?>(
            "param" to TemplateFunction { args -> "#{${args[0]},jdbcType=${args.getOrNull(1) ?: "VARCHAR"}}" }
        )
        assertEquals("#{id,jdbcType=BIGINT}", render("\$util.param('id', 'BIGINT')", "util" to util))
        assertEquals("#{name,jdbcType=VARCHAR}", render("\$util.param('name')", "util" to util))
    }

    @Test
    fun `字符串方法可直接用`() {
        assertEquals("USER", render("\$name.toUpperCase()", "name" to "user"))
        assertEquals("3", render("\$name.length()", "name" to "abc"))
    }

    @Test
    fun `集合方法与下标`() {
        assertEquals("2", render("\$list.size()", "list" to listOf(1, 2)))
        assertEquals("b", render("\$list[1]", "list" to listOf("a", "b")))
        assertEquals("a-b", render("\$list.join('-')", "list" to listOf("a", "b")))
    }

    @Test
    fun `独占一行的指令不会留下空行`() {
        assertEquals("a\nb\nc", render("a\n#if(true)\nb\n#end\nc"))
        assertEquals("a\nc", render("a\n#if(false)\nb\n#end\nc"))
    }

    @Test
    fun `独占一行的指令连同行首缩进一起去掉`() {
        val source = "class X {\n    #if(true)\n    int a;\n    #end\n}\n"
        assertEquals("class X {\n    int a;\n}\n", render(source))
    }

    @Test
    fun `行内指令不受行级修剪影响`() {
        assertEquals("a,b", render("a#if(true),b#end"))
    }

    // ------------------------------------------------------------------ 错误定位

    @Test
    fun `缺 end 时报出行列`() {
        val error = runCatching { Template.compile("#if(\$a)\nx", "test.tpl").render(mapOf("a" to true)) }
            .exceptionOrNull() as TemplateException
        assertEquals(1, error.line)
        assertTrue(error.message!!.contains("test.tpl:1:"))
        assertTrue(error.message!!.contains("#end"))
    }

    @Test
    fun `多余的 end 报错`() {
        val error = runCatching { Template.compile("a#end", "t.tpl") }.exceptionOrNull() as TemplateException
        assertTrue(error.reason.contains("#end"))
    }

    @Test
    fun `花括号没闭合报错`() {
        val error = runCatching { Template.compile("\${a", "t.tpl") }.exceptionOrNull() as TemplateException
        assertTrue(error.reason.contains("}"))
    }
}
