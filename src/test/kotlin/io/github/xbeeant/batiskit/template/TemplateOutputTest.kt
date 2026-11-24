package io.github.xbeeant.batiskit.template

import io.github.xbeeant.batiskit.config.GenerationConfig
import io.github.xbeeant.batiskit.config.TargetFramework
import io.github.xbeeant.batiskit.config.frameworkEnum
import io.github.xbeeant.batiskit.generate.GenContext
import io.github.xbeeant.batiskit.generate.GeneratedMarker
import io.github.xbeeant.batiskit.model.ColumnInfo
import io.github.xbeeant.batiskit.model.JavaType
import io.github.xbeeant.batiskit.model.TableInfo
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URI
import javax.tools.Diagnostic
import javax.tools.DiagnosticCollector
import javax.tools.JavaFileObject
import javax.tools.SimpleJavaFileObject
import javax.tools.ToolProvider

/**
 * 产物级护栏：模板渲染出来的 Java 源码必须真的是能解析的 Java。
 *
 * [TemplateSetTest] 只断言「没有残留占位符、没有渲染告警」，可模板少写一个分号、
 * 多拼一个泛型、`@Generated` 注入错位，那些断言照样全绿。这里用 JDK 自带的 javac
 * **只跑 parse 阶段**（不 analyze）：语法错误会被抓出来，而模板里引用的私有基类
 * 不在测试 classpath 上也不会误报。
 */
class TemplateOutputTest {

    @Test
    fun `内置模板的 Java 产物都能通过语法解析`() {
        listOf("mybatis-pure", "mybatis-plus-pure").forEach { setId ->
            val renderedFiles = renderJava(setId)
            assertTrue("$setId 应该产出 Java 文件", renderedFiles.isNotEmpty())
            renderedFiles.forEach { (name, text) ->
                assertTrue("$setId/$name 缺少 package 行", text.contains("package "))
                assertTrue("$setId/$name 缺少类型声明", text.contains("class ") || text.contains("interface "))
                val errors = syntaxErrors(text, name)
                assertTrue("$setId/$name 有 Java 语法错误：${errors.joinToString("; ")}", errors.isEmpty())
            }
        }
    }

    /**
     * 纯模板方案的护栏：产物不能偷偷依赖内部框架基类，Service 层必须真的把全套
     * 增删改查 + 条件查询方法写出来（而不是靠某个父类「本来就有」）。
     */
    @Test
    fun `纯模板方案自包含且提供全套 CRUD 方法`() {
        val methods = listOf(
            "insert(", "insertSelective(", "batchInsert(", "deleteById(", "deleteByCondition(",
            "updateById(", "updateSelectiveById(", "updateByCondition(", "selectById(",
            "selectByCondition(", "countByCondition(", "selectPage("
        )
        listOf("mybatis-pure", "mybatis-plus-pure").forEach { setId ->
            val rendered = renderAll(setId)
            assertTrue("$setId 应该产出文件", rendered.isNotEmpty())
            rendered.forEach { (name, text) ->
                assertFalse("$setId/$name 依赖内部框架父类", text.contains("io.github.xbeeant"))
                assertFalse("$setId/$name 有未渲染的占位符", text.contains("\${"))
            }
            val service = rendered.first { it.first.endsWith("Service.java") }.second
            methods.forEach { method ->
                assertTrue("$setId 的 Service 缺少方法 $method", service.contains(method))
            }
            val impl = rendered.first { it.first.endsWith("ServiceImpl.java") }.second
            assertFalse("$setId 的 ServiceImpl 不应继承父类", impl.contains("extends "))
            assertTrue("$setId 的实现缺少条件查询", impl.contains("selectByCondition"))
        }

        val xml = renderAll("mybatis-pure").first { it.first.endsWith("Mapper.xml") }.second
        listOf(
            "insert", "insertSelective", "batchInsert", "deleteById", "deleteByCondition",
            "updateById", "updateSelectiveById", "updateByCondition", "selectById",
            "selectByCondition", "countByCondition", "selectPage"
        ).forEach { id ->
            assertTrue("纯 MyBatis XML 缺少语句 $id", xml.contains("id=\"$id\""))
        }
        assertTrue("XML 应包含动态条件片段", xml.contains("Condition_Where_Clause"))

        val plusRendered = renderAll("mybatis-plus-pure")
        val plusMapper = plusRendered.first { it.first.endsWith("Mapper.java") }.second
        assertTrue("MyBatis-Plus 纯模板的 Mapper 应继承 BaseMapper", plusMapper.contains("extends BaseMapper<"))
        val plusImpl = plusRendered.first { it.first.endsWith("ServiceImpl.java") }.second
        assertTrue("MyBatis-Plus 实现应使用 QueryWrapper 拼条件", plusImpl.contains("QueryWrapper<"))
    }

    @Test
    fun `纯模板关闭 Lombok 后手写访问器仍是合法 Java`() {
        val ctx = GenContext(
            GenerationConfig().apply {
                basePackage = "com.example.demo"
                useLombok = false
                frameworkEnum = TargetFramework.MYBATIS
            }
        )
        val model = TemplateModel.build(sampleTable, ctx)
        TemplateRegistry.forSet("mybatis-pure").forEach { spec ->
            val rendered = TemplateRegistry.render(spec, model) ?: return@forEach
            val name = rendered.path.substringAfterLast('/')
            if (!name.endsWith(".java")) return@forEach
            assertTrue("$name 关闭 Lombok 后有语法错误", syntaxErrors(rendered.text, name).isEmpty())
            assertFalse("$name 关闭 Lombok 后仍带 Lombok import", rendered.text.contains("lombok."))
        }
        val entity = TemplateRegistry.render(
            TemplateRegistry.forSet("mybatis-pure").first { it.id == "pureEntity" }, model
        )!!.text
        assertTrue("关闭 Lombok 后应生成手写 getter", entity.contains("public Long getId()"))
    }

    @Test
    fun `加了来源标记后依然是合法 Java`() {
        renderJava("mybatis").forEach { (name, text) ->
            val marked = GeneratedMarker.annotate(text, TemplateRegistry.forSet("mybatis").first(), "mybatis", name)
            val errors = syntaxErrors(marked, name)
            assertTrue("$name 注入标记后有语法错误：${errors.joinToString("; ")}", errors.isEmpty())
        }
    }

    private fun renderAll(setId: String): List<Pair<String, String>> {
        val ctx = GenContext(
            GenerationConfig().apply {
                basePackage = "com.example.demo"
                frameworkEnum = TemplateRegistry.frameworkOf(setId) ?: TargetFramework.MYBATIS
            }
        )
        val model = TemplateModel.build(sampleTable, ctx)
        return TemplateRegistry.forSet(setId).mapNotNull { spec ->
            val rendered = TemplateRegistry.render(spec, model) ?: return@mapNotNull null
            rendered.path.substringAfterLast('/') to rendered.text
        }
    }

    private fun renderJava(setId: String): List<Pair<String, String>> =
        renderAll(setId).filter { it.first.endsWith(".java") }

    /**
     * 通过反射调用 JavacTask.parse，避免测试代码在编译期依赖 jdk.compiler 的内部 API；
     * 拿不到系统编译器（极简 JRE）时返回空列表，静态结构断言仍然生效。
     */
    private fun syntaxErrors(source: String, name: String): List<String> {
        val compiler = ToolProvider.getSystemJavaCompiler() ?: return emptyList()
        val diagnostics = DiagnosticCollector<JavaFileObject>()
        val safeName = name.replace(Regex("[^A-Za-z0-9_]"), "")
        val file = object : SimpleJavaFileObject(
            URI.create("string:///$safeName.java"), JavaFileObject.Kind.SOURCE
        ) {
            override fun getCharContent(ignoreEncodingErrors: Boolean): CharSequence = source
        }
        val task = compiler.getTask(
            null, null, diagnostics, listOf("-proc:none"), null, listOf<JavaFileObject>(file)
        )
        runCatching { task.javaClass.getMethod("parse").invoke(task) }
        return diagnostics.diagnostics
            .filter { it.kind == Diagnostic.Kind.ERROR }
            .map { it.getMessage(null) ?: "unknown" }
    }

    private val sampleTable = TableInfo(
        tableName = "t_user",
        entityName = "User",
        comment = "用户表",
        baseName = "User",
        columns = listOf(
            ColumnInfo("id", "id", "bigint", JavaType("Long", null, "BIGINT"), "主键", true, true, false),
            ColumnInfo("user_name", "userName", "varchar(64)", JavaType("String", null, "VARCHAR"), "用户名", false, false, false),
            ColumnInfo(
                "create_at", "createAt", "datetime",
                JavaType("LocalDateTime", "java.time.LocalDateTime", "TIMESTAMP"), "创建时间", false, false, true
            )
        )
    )
}
