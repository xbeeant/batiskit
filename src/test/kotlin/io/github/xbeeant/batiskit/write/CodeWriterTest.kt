package io.github.xbeeant.batiskit.write

import com.intellij.ide.highlighter.XmlFileType
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiManager
import com.intellij.psi.xml.XmlFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.xbeeant.batiskit.config.GenerationConfig
import io.github.xbeeant.batiskit.generate.CodeGenerator
import io.github.xbeeant.batiskit.model.ColumnInfo
import io.github.xbeeant.batiskit.model.JavaType
import io.github.xbeeant.batiskit.model.TableInfo

/**
 * 在真实的 PSI 环境里验证：文件创建、二次生成的合并（不覆盖用户已有代码）。
 */
class CodeWriterTest : BasePlatformTestCase() {

    private val config = GenerationConfig().apply {
        basePackage = "com.example.demo"
        author = "tester"
        databaseName = "demo"
    }

    private val table = TableInfo(
        tableName = "t_user",
        entityName = "User",
        comment = "用户表",
        columns = listOf(
            ColumnInfo(
                columnName = "id",
                fieldName = "id",
                dbType = "bigint",
                javaType = JavaType("Long", null, "BIGINT"),
                comment = "主键",
                primaryKey = true,
                autoIncrement = true,
                nullable = false
            ),
            ColumnInfo(
                columnName = "user_name",
                fieldName = "userName",
                dbType = "varchar(64)",
                javaType = JavaType("String", null, "VARCHAR"),
                comment = "用户名",
                primaryKey = false,
                autoIncrement = false,
                nullable = true
            )
        )
    )


    fun testSecondGenerationMergesWithoutOverwriting() {
        generate()

        // 模拟用户改代码：给实体类加一个自定义方法
        val entity = findJava("com/example/demo/model/User.java") as PsiJavaFile
        WriteCommandAction.runWriteCommandAction(project) {
            val factory = com.intellij.psi.JavaPsiFacade.getElementFactory(project)
            entity.classes.first()
                .add(factory.createMethodFromText("public String describe() { return userName; }", entity))
        }

        val second = generate()
        assertEquals(0, second.errors.size)
        // 已有内容全部命中，不重复生成
        assertEquals(0, second.created)
        assertEquals(0, second.merged)

        val text = entity.text
        assertTrue("用户自定义方法被覆盖了", text.contains("describe()"))
        assertEquals("字段被重复生成", 1, Regex("private String userName;").findAll(text).count())
    }

    fun testXmlMergeReplacesSqlFragmentWithBeginEnd() {
        val oldText = "<mapper namespace=\"m.UserMapper\">\n" +
            "  <sql id=\"Example_Where_Clause\">\n" +
            "    <if test=\"id != null\">\n" +
            "      and `id` = #{id,jdbcType=BIGINT}\n" +
            "    </if>\n" +
            "  </sql>\n" +
            "  <select id=\"selectByExample\" resultMap=\"BaseResultMap\">select 1</select>\n" +
            "</mapper>"
        val newText = "<mapper namespace=\"m.UserMapper\">\n" +
            "  <sql id=\"Example_Where_Clause\">\n" +
            "    <if test=\"id != null\">\n" +
            "      and `id` = #{id,jdbcType=BIGINT}\n" +
            "    </if>\n" +
            "    <if test=\"createTimeBegin != null\">\n" +
            "      and `create_time` &gt;= #{createTime,jdbcType=TIMESTAMP}\n" +
            "    </if>\n" +
            "    <if test=\"createTimeEnd != null\">\n" +
            "      and `create_time` &lt;= #{createTime,jdbcType=TIMESTAMP}\n" +
            "    </if>\n" +
            "  </sql>\n" +
            "  <select id=\"selectByExample\" resultMap=\"BaseResultMap\">select 1</select>\n" +
            "</mapper>"

        val target = createXml("merge_target.xml", oldText)
        val outcome = XmlMerger.merge(target, newText)

        assertNull(outcome.error)
        val merged = target.text
        assertTrue("应包含新增的 Begin 区间条件", merged.contains("createTimeBegin"))
        assertTrue("应包含新增的 End 区间条件", merged.contains("createTimeEnd"))
        // 语句保留不重复
        assertEquals(1, Regex("id=\"selectByExample\"").findAll(merged).count())
    }

    fun testJavaMergeReplacesChangedMethodWhenOverwriteEnabled() {
        val v1 = "package com.example;\n\npublic class Foo {\n" +
            "    /**\n     * 文档\n     */\n    public String hello() { return \"v1\"; }\n" +
            "}\n"
        val v2 = "package com.example;\n\npublic class Foo {\n" +
            "    /**\n     * 文档\n     */\n    public String hello() { return \"v2\"; }\n" +
            "}\n"

        val target = createJava("Foo.java", v1)
        JavaMerger.merge(target, v1)
        // 内容一致：不做任何改动
        val unchanged = JavaMerger.merge(target, v1)
        assertEquals(0, unchanged.added)

        // 实现有差异：默认覆盖，新实现替换旧方法（含文档整体替换，不残留旧体）
        val outcome = JavaMerger.merge(target, v2)
        assertEquals(1, outcome.added)
        val text = target.text
        assertTrue("应替换为新实现", text.contains("return \"v2\";"))
        assertFalse("旧实现应被移除", text.contains("return \"v1\";"))
        assertEquals("不应重复生成方法", 1, Regex("hello\\(\\)").findAll(text).count())
    }

    fun testJavaMergeKeepsOldMethodWhenOverwriteDisabled() {
        val v1 = "package com.example;\n\npublic class Foo {\n" +
            "    public String hello() { return \"v1\"; }\n}\n"
        val v2 = "package com.example;\n\npublic class Foo {\n" +
            "    public String hello() { return \"v2\"; }\n}\n"

        val target = createJava("Bar.java", v1)
        JavaMerger.merge(target, v1)
        JavaMerger.merge(target, v2, overwriteMethods = false)

        assertTrue("关闭覆盖时保留旧实现", target.text.contains("return \"v1\";"))
        assertFalse(target.text.contains("return \"v2\";"))
    }

    fun testJavaMergeAddsOverloadWithSameNameDifferentParams() {
        val v1 = "package com.example;\n\npublic class Foo {\n" +
            "    public String hello(String name) { return name; }\n}\n"
        val v2 = "package com.example;\n\npublic class Foo {\n" +
            "    public String hello(String name) { return name; }\n" +
            "    public String hello(Long id) { return String.valueOf(id); }\n}\n"

        val target = createJava("Baz.java", v1)
        JavaMerger.merge(target, v1)
        JavaMerger.merge(target, v2)

        // 同名不同参数类型是重载，不受覆盖策略影响，正常补缺
        assertTrue(target.text.contains("hello(Long id)"))
        assertEquals(1, Regex("hello\\(String name\\)").findAll(target.text).count())
    }

    private fun createJava(name: String, text: String): PsiJavaFile {
        var file: PsiJavaFile? = null
        WriteCommandAction.runWriteCommandAction(project) {
            file = PsiFileFactory.getInstance(project)
                .createFileFromText(name, com.intellij.ide.highlighter.JavaFileType.INSTANCE, text) as PsiJavaFile
        }
        return file!!
    }

    private fun createXml(name: String, text: String): XmlFile {
        var file: XmlFile? = null
        WriteCommandAction.runWriteCommandAction(project) {
            file = PsiFileFactory.getInstance(project)
                .createFileFromText(name, XmlFileType.INSTANCE, text) as XmlFile
        }
        return file!!
    }

    private fun generate(): GenerationStats {
        var stats: GenerationStats? = null
        WriteCommandAction.runWriteCommandAction(project) {
            stats = CodeGenerator.generate(project, module, listOf(table), config)
        }
        return stats ?: GenerationStats()
    }

    private fun findJava(relativePath: String): PsiFile? =
        ModuleRoots.javaRoot(project, module)?.virtualFile
            ?.findFileByRelativePath(relativePath)
            ?.let { PsiManager.getInstance(project).findFile(it) }

    private fun findXml(relativePath: String): PsiFile? =
        ModuleRoots.resourceRoot(project, module)?.virtualFile
            ?.findFileByRelativePath(relativePath)
            ?.let { PsiManager.getInstance(project).findFile(it) }
}
