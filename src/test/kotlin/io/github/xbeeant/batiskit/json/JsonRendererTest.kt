package io.github.xbeeant.batiskit.json

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URI
import javax.tools.Diagnostic
import javax.tools.DiagnosticCollector
import javax.tools.JavaFileObject
import javax.tools.SimpleJavaFileObject
import javax.tools.ToolProvider

/** 渲染：注解库叠加、lombok、record、拆分文件、模板覆盖 */
class JsonRendererTest {

    private val sample = """{"user_name":"张三","orders":[{"id":1}],"created_at":"2026-11-05 22:10:03"}"""

    private fun infer(json: String = sample): InferenceResult =
        JsonInferrer(JsonSettings()).infer(JsonParser.parseSamples(json).values, "Response")

    private fun render(
        result: InferenceResult = infer(),
        settings: JsonSettings = JsonSettings(),
        template: String? = null
    ): List<GeneratedJavaFile> = JsonRenderer(settings).render(result, "com.example.dto", template)

    private fun assertNoTemplateLeftovers(text: String) {
        assertFalse(text.contains("\${"))
        assertFalse(text.contains("#if"))
        assertFalse(text.contains("#foreach"))
        assertFalse(text.contains("#end"))
    }

    @Test
    fun `默认 Jackson 加 lombok 的完整产物`() {
        val text = render().single().text
        assertNoTemplateLeftovers(text)
        assertTrue(text.startsWith("package com.example.dto;"))
        assertTrue(text.contains("import com.fasterxml.jackson.annotation.JsonProperty;"))
        assertTrue(text.contains("import com.fasterxml.jackson.annotation.JsonIgnoreProperties;"))
        assertTrue(text.contains("import com.fasterxml.jackson.annotation.JsonFormat;"))
        assertTrue(text.contains("import java.time.LocalDateTime;"))
        assertTrue(text.contains("import java.util.List;"))
        assertTrue(text.contains("@Data"))
        assertTrue(text.contains("@Builder"))
        assertTrue(text.contains("@JsonIgnoreProperties(ignoreUnknown = true)"))
        assertTrue(text.contains("@JsonProperty(\"user_name\")"))
        assertTrue(text.contains("private String userName;"))
        assertTrue(text.contains("private List<Order> orders;"))
        assertTrue(text.contains("@JsonFormat(pattern = \"yyyy-MM-dd HH:mm:ss\")"))
        assertTrue(text.contains("private LocalDateTime createdAt;"))
        assertTrue(text.contains("public static class Order {"))
        assertTrue(text.contains("public class Response {"))
        assertTrue(text.trimEnd().endsWith("}"))
    }

    @Test
    fun `四个注解库叠加且 fastjson 1 与 2 不撞 import`() {
        val settings = JsonSettings().apply {
            annotationLibs = "FASTJSON,FASTJSON2,GSON,JACKSON"
            nameAnnotation = "ALWAYS"
        }
        val text = render(settings = settings).single().text
        assertTrue(text.contains("@SerializedName(\"user_name\")"))
        assertTrue(text.contains("@JsonProperty(\"user_name\")"))
        assertTrue(text.contains("@com.alibaba.fastjson.annotation.JSONField(name = \"user_name\")"))
        assertTrue(text.contains("@com.alibaba.fastjson2.annotation.JSONField(name = \"user_name\")"))
        assertFalse(text.contains("import com.alibaba.fastjson.annotation.JSONField;"))
        assertFalse(text.contains("import com.alibaba.fastjson2.annotation.JSONField;"))
        // 同一库在日期字段上的 name + format 合并成一个注解
        assertTrue(text.contains("name = \"created_at\", format = \"yyyy-MM-dd HH:mm:ss\""))
    }

    @Test
    fun `record 形态不带 lombok 且组件用逗号分隔`() {
        val settings = JsonSettings().apply { classKind = "RECORD" }
        val text = render(settings = settings).single().text
        assertTrue(text.contains("public record Response("))
        assertTrue(text.contains("@JsonProperty(\"user_name\")"))
        assertTrue(text.contains("String userName,"))
        assertFalse(text.contains("@Data"))
        assertFalse(text.contains("private "))
    }

    @Test
    fun `拆分模式每个类一个文件`() {
        val settings = JsonSettings().apply { splitClasses = true }
        val files = render(settings = settings)
        assertEquals(setOf("Response.java", "Order.java"), files.map { it.fileName }.toSet())
        files.forEach { file ->
            assertTrue(file.text.startsWith("package com.example.dto;"))
            assertFalse(file.text.contains("static class"))
        }
    }

    @Test
    fun `name 注解的三种策略`() {
        val never = render(settings = JsonSettings().apply { nameAnnotation = "NEVER" }).single().text
        assertFalse(never.contains("@JsonProperty(\"user_name\")"))

        val always = render(settings = JsonSettings().apply { nameAnnotation = "ALWAYS" }).single().text
        assertTrue(always.contains("@JsonProperty(\"orders\")"))

        val different = render().single().text
        assertFalse(different.contains("@JsonProperty(\"orders\")"))
    }

    @Test
    fun `用户模板可以整体覆盖产物`() {
        val tpl = "#foreach(\$f in \$fields)\n\$f.type \$f.name;\n#end\n"
        val text = render(template = tpl).single().text
        assertTrue(text.contains("String userName;"))
        assertTrue(text.contains("List<Order> orders;"))
        assertFalse(text.contains("public class"))
    }

    @Test
    fun `关闭包装类型生成基本类型但泛型仍是包装`() {
        val settings = JsonSettings().apply { useWrapperTypes = false }
        val result = infer("""{"age":18,"tags":[1,2]}""")
        val text = render(result, settings).single().text
        assertTrue(text.contains("private int age;"))
        assertTrue(text.contains("private List<Integer> tags;"))
    }

    @Test
    fun `字段表里关掉的字段不进产物`() {
        val result = infer("""{"a":1,"b":2}""")
        result.root!!.fields.first { it.name == "b" }.enabled = false
        val text = render(result).single().text
        assertTrue(text.contains("private Integer a;"))
        assertFalse(text.contains("private Integer b;"))
    }

    @Test
    fun `Serializable 与 serialVersionUID`() {
        val settings = JsonSettings().apply { serializable = true }
        val text = render(settings = settings).single().text
        assertTrue(text.contains("import java.io.Serializable;"))
        assertTrue(text.contains("implements Serializable"))
        assertTrue(text.contains("private static final long serialVersionUID = 1L;"))
    }

    @Test
    fun `字段名与 JSON key 相同时不生成多余注解`() {
        val result = infer("""{"id":1,"name":"x"}""")
        val text = render(result).single().text
        assertFalse(text.contains("@JsonProperty"))
    }

    /** 只做语法解析（不解析符号）：注解库不在 classpath 上也能验证模板产物是不是合法 Java。
     *  与 TemplateOutputTest 同一套路，用反射调 parse，极简 JRE 下自动退化成空列表。 */
    @Test
    fun `class 与 record 产物都能过 javac 语法解析`() {
        listOf(JsonSettings(), JsonSettings().apply { classKind = "RECORD" }).forEach { settings ->
            val text = render(settings = settings).single().text
            assertTrue(parseErrors(text).joinToString("\n"), parseErrors(text).isEmpty())
        }
    }

    private fun parseErrors(text: String): List<String> {
        val compiler = ToolProvider.getSystemJavaCompiler() ?: return emptyList()
        val diagnostics = DiagnosticCollector<JavaFileObject>()
        val source = object : SimpleJavaFileObject(
            URI.create("string:///JsonPojo.java"), JavaFileObject.Kind.SOURCE
        ) {
            override fun getCharContent(ignoreEncodingErrors: Boolean): CharSequence = text
        }
        val task = compiler.getTask(
            null, null, diagnostics, listOf("-proc:none"), null, listOf<JavaFileObject>(source)
        )
        runCatching { task.javaClass.getMethod("parse").invoke(task) }
        return diagnostics.diagnostics
            .filter { it.kind == Diagnostic.Kind.ERROR }
            .map { it.getMessage(null) ?: "unknown" }
    }
}
