package io.github.xbeeant.batiskit.inspect

import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiJavaFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Mapper 参数绑定检查与两个 quickfix 的 PSI 级测试。
 *
 * 走真实高亮：enableInspections 后 doHighlighting，再从 IntentionAction 里选修复执行，
 * 因此能覆盖「语句标签定位 → 接口方法解析 → foreach 作用域 → 修复写回」整条链路。
 */
class MapperParameterInspectionTest : BasePlatformTestCase() {

    private fun addParamAnnotationStub() {
        myFixture.addFileToProject(
            "org/apache/ibatis/annotations/Param.java",
            """
            package org.apache.ibatis.annotations;

            import java.lang.annotation.ElementType;
            import java.lang.annotation.Retention;
            import java.lang.annotation.RetentionPolicy;
            import java.lang.annotation.Target;

            @Retention(RetentionPolicy.RUNTIME)
            @Target(ElementType.PARAMETER)
            public @interface Param {
                String value();
            }
            """.trimIndent()
        )
    }

    private fun addMapper(methods: String) {
        myFixture.addFileToProject(
            "com/example/mapper/UserMapper.java",
            """
            package com.example.mapper;

            import java.util.List;
            import org.apache.ibatis.annotations.Param;

            public interface UserMapper {
            $methods
            }
            """.trimIndent()
        )
    }

    private fun mapperXml(body: String): String = """
        <?xml version="1.0" encoding="UTF-8"?>
        <mapper namespace="com.example.mapper.UserMapper">
        $body
        </mapper>
        """.trimIndent()

    // ------------------------------------------------------------------ 报错

    fun `test 绑定不上的参数名报错并给出改名建议`() {
        addParamAnnotationStub()
        addMapper("    void list(@Param(\"status\") String status, String userName);")
        myFixture.configureByText(
            "UserMapper.xml",
            mapperXml(
                """
                    <select id="list" resultType="map">
                        select * from t_user where status = #{status} and name = #{user_name}
                    </select>
                """.trimIndent()
            )
        )

        myFixture.enableInspections(MapperParameterInspection())
        val errors = myFixture.doHighlighting().filter { it.description?.contains("user_name") == true }

        assertEquals("只应报一个参数名错误", 1, errors.size)
        assertTrue(errors[0].description!!.contains("可用"))
        assertTrue(
            "应给出把 user_name 改成 userName 的修复",
            myFixture.getAllQuickFixes().any { it.text.contains("userName") && it.text.contains("改成") }
        )
    }

    fun `test 未注解参数的实际名字与 foreach 变量都合法`() {
        addParamAnnotationStub()
        addMapper("    void batch(@Param(\"list\") java.util.List<String> list, String suffix);")
        myFixture.configureByText(
            "UserMapper.xml",
            mapperXml(
                """
                    <update id="batch">
                        update t_user set name = #{suffix}
                        <where>
                            <foreach collection="list" item="item" index="i" open="(" close=")" separator=",">
                                id = #{item} or seq = #{i}
                            </foreach>
                        </where>
                    </update>
                """.trimIndent()
            )
        )

        myFixture.enableInspections(MapperParameterInspection())
        assertEmpty(
            "suffix / item / i 都合法，不应报错",
            myFixture.doHighlighting().filter { it.description?.contains("绑定不上") == true }
        )
    }

    fun `test 单参数未注解时不校验属性名`() {
        addMapper("    void insert(com.example.User user);")
        myFixture.configureByText(
            "UserMapper.xml",
            mapperXml(
                """
                    <insert id="insert">
                        insert into t_user (id, name) values (#{id}, #{name})
                    </insert>
                """.trimIndent()
            )
        )

        myFixture.enableInspections(MapperParameterInspection())
        assertEmpty(
            "单参数 POJO 的 #{id} / #{name} 是取属性，不能报",
            myFixture.doHighlighting().filter { it.description?.contains("绑定不上") == true }
        )
    }

    // ------------------------------------------------------------------ 两个修复

    fun `test 修正表达式把名字改成接口参数名`() {
        addParamAnnotationStub()
        addMapper("    void list(@Param(\"status\") String status, String userName);")
        // 用临时工程里的真实文件：configureByText 建的是内存文件，文档不可写，改不进去
        myFixture.addFileToProject(
            "mybatis/UserMapper.xml",
            mapperXml("    <select id=\"list\">select * from t_user where name = #{user_name}</select>")
        )
        myFixture.configureFromTempProjectFile("mybatis/UserMapper.xml")

        myFixture.enableInspections(MapperParameterInspection())
        val rename = myFixture.getAllQuickFixes().single { it.text.contains("改成 \"userName\"") }
        myFixture.launchAction(rename)

        val text = myFixture.file.text
        assertTrue("表达式应被改写成 #{userName}", text.contains("#{userName}"))
        assertFalse("旧写法应消失", text.contains("user_name"))
    }

    fun `test 补 Param 修复会写到接口方法上`() {
        addParamAnnotationStub()
        addMapper("    void list(@Param(\"status\") String status, String userName);")
        myFixture.configureByText(
            "UserMapper.xml",
            mapperXml("    <select id=\"list\">select * from t_user where code = #{code}</select>")
        )

        myFixture.enableInspections(MapperParameterInspection())
        val addParam = myFixture.getAllQuickFixes().single { it.text.contains("添加 @Param") }
        myFixture.launchAction(addParam)

        val java = (psiManager.findFile(
            myFixture.findFileInTempDir("com/example/mapper/UserMapper.java")
        ) as PsiJavaFile).text
        assertTrue("接口参数上应出现 @Param(\"code\")", java.contains("@Param(\"code\")"))
    }

    fun `test 修复后重新高亮不再报错`() {
        addParamAnnotationStub()
        addMapper("    void list(@Param(\"status\") String status, String userName);")
        myFixture.addFileToProject(
            "mybatis/UserMapper.xml",
            mapperXml("    <select id=\"list\">select * from t_user where name = #{user_name}</select>")
        )
        myFixture.configureFromTempProjectFile("mybatis/UserMapper.xml")

        myFixture.enableInspections(MapperParameterInspection())
        myFixture.launchAction(myFixture.getAllQuickFixes().single { it.text.contains("改成 \"userName\"") })
        PsiDocumentManager.getInstance(project).commitAllDocuments()

        assertEmpty(
            "改名后应不再报错",
            myFixture.doHighlighting().filter { it.description?.contains("绑定不上") == true }
        )
    }
}
