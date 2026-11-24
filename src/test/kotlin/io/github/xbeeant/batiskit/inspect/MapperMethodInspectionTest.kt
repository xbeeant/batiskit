package io.github.xbeeant.batiskit.inspect

import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.xml.XmlFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Mapper 接口侧检查（方法缺少 XML 语句）与「生成语句骨架」修复的 PSI 级测试。
 */
class MapperMethodInspectionTest : BasePlatformTestCase() {

    private fun addSelectStub() {
        myFixture.addFileToProject(
            "org/apache/ibatis/annotations/Select.java",
            """
            package org.apache.ibatis.annotations;

            import java.lang.annotation.ElementType;
            import java.lang.annotation.Retention;
            import java.lang.annotation.RetentionPolicy;
            import java.lang.annotation.Target;

            @Retention(RetentionPolicy.RUNTIME)
            @Target(ElementType.METHOD)
            public @interface Select {
                String[] value();
            }
            """.trimIndent()
        )
    }

    private fun addXml(body: String, name: String = "UserMapper.xml", namespace: String = "com.example.mapper.UserMapper") {
        myFixture.addFileToProject(
            "mybatis/$name",
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <mapper namespace="$namespace">
            $body
            </mapper>
            """.trimIndent()
        )
    }

    private fun checkMapper(methods: String): List<String> {
        myFixture.configureByText(
            "UserMapper.java",
            """
            package com.example.mapper;

            import org.apache.ibatis.annotations.Select;

            public interface UserMapper {
            $methods
            }
            """.trimIndent()
        )
        myFixture.enableInspections(MapperMethodInspection())
        return myFixture.doHighlighting()
            .mapNotNull { it.description }
            .filter { it.contains("没有对应语句") }
    }

    // ------------------------------------------------------------------ 报错

    fun `test 方法没有对应语句时报错`() {
        addXml("    <select id=\"list\">select 1</select>")
        val problems = checkMapper("    void list();\n    java.util.List<String> selectByCode(String code);")

        assertEquals(1, problems.size)
        assertTrue(problems[0].contains("selectByCode"))
        assertTrue(problems[0].contains("UserMapper.xml"))
        assertTrue(problems[0].contains("Invalid bound statement"))
    }

    fun `test 参数类型也不影响判定`() {
        addXml("    <select id=\"list\">select 1</select>")
        val problems = checkMapper("    void list();\n    java.util.Map<String, Object> selectMap(java.util.List<Long> ids);")

        assertEquals(1, problems.size)
        assertTrue(problems[0].contains("selectMap"))
    }

    // ------------------------------------------------------------------ 不该报的情况

    fun `test 注解式 SQL 不报`() {
        addSelectStub()
        addXml("    <select id=\"list\">select 1</select>")
        val problems = checkMapper(
            "    void list();\n    @Select(\"select * from t_user where code = #{code}\")\n    String selectByCode(String code);"
        )

        assertEmpty("SQL 在注解里，不该要求 XML 语句", problems)
    }

    fun `test default 方法不报`() {
        addXml("    <select id=\"list\">select 1</select>")
        val problems = checkMapper("    void list();\n    default String fallback() { return \"x\"; }")

        assertEmpty("带方法体的方法 MyBatis 不绑定", problems)
    }

    fun `test 接口没有 XML 时不检查`() {
        // 注解式 mapper、或者压根不用 XML 的工程都不该被波及
        val problems = checkMapper("    void list();\n    void selectByCode(String code);")

        assertEmpty(problems)
    }

    fun `test sql 片段的 id 不算语句`() {
        addXml("    <sql id=\"selectByCode\">id, code</sql>")
        val problems = checkMapper("    void selectByCode();")

        assertEquals("只有 <sql> 的同名 id 不能算绑定", 1, problems.size)
    }

    // ------------------------------------------------------------------ 修复

    fun `test 生成语句骨架写入绑定 XML`() {
        // 返回类型用工程内的类：结果类型要 resolve 出 FQN 才会写进 resultType
        myFixture.addFileToProject("com/example/User.java", "package com.example;\n\npublic class User {}\n")
        addXml("    <select id=\"list\">select 1</select>")
        checkMapper("    void list();\n    java.util.List<com.example.User> selectByCode(String code);")

        val fix = myFixture.getAllQuickFixes().single { it.text.contains("生成 <select") }
        myFixture.launchAction(fix)
        PsiDocumentManager.getInstance(project).commitAllDocuments()

        val root = (psiManager.findFile(myFixture.findFileInTempDir("mybatis/UserMapper.xml")) as XmlFile).rootTag!!
        val added = root.findSubTags("select").single { it.getAttributeValue("id") == "selectByCode" }
        assertEquals("selectByCode", added.getAttributeValue("id"))
        assertEquals("应该带上解析出的返回类型", "com.example.User", added.getAttributeValue("resultType"))
    }

    fun `test 返回类型解析不到时在注释里提醒补 resultType`() {
        addXml("    <select id=\"list\">select 1</select>")
        // 返回类型指向工程里不存在的类，推断不出 resultType
        checkMapper("    void list();\n    com.missing.User selectByCode(String code);")

        myFixture.launchAction(myFixture.getAllQuickFixes().single { it.text.contains("生成 <select") })
        PsiDocumentManager.getInstance(project).commitAllDocuments()

        val root = (psiManager.findFile(myFixture.findFileInTempDir("mybatis/UserMapper.xml")) as XmlFile).rootTag!!
        val added = root.findSubTags("select").single { it.getAttributeValue("id") == "selectByCode" }
        assertNull("推断不出就不写 resultType", added.getAttributeValue("resultType"))
        assertTrue("注释里要提醒补 resultType", added.text.contains("resultType"))
    }

    fun `test 生成语句后重新高亮不再报错`() {
        addXml("    <select id=\"list\">select 1</select>")
        checkMapper("    void list();\n    void deleteByCode(String code);")

        val fix = myFixture.getAllQuickFixes().single { it.text.contains("生成 <delete") }
        myFixture.launchAction(fix)
        PsiDocumentManager.getInstance(project).commitAllDocuments()

        assertEmpty(
            "生成后应不再报错",
            myFixture.doHighlighting().mapNotNull { it.description }.filter { it.contains("没有对应语句") }
        )
    }
}
