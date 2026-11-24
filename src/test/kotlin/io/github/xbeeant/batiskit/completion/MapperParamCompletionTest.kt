package io.github.xbeeant.batiskit.completion

import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Mapper XML `#{}` / `${}` 参数补全的 PSI 级测试。
 *
 * 用 `myFixture.completeBasic()` 走真实补全链路（contributor 注册由 plugin.xml 提供），
 * 覆盖：@Param 值、paramN / argN、内置变量、foreach 局部变量、单参数 POJO 属性、
 * `.` 之后的属性路径，以及非语句位置不出候选。
 */
class MapperParamCompletionTest : BasePlatformTestCase() {

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

            import org.apache.ibatis.annotations.Param;

            public interface UserMapper {
            $methods
            }
            """.trimIndent()
        )
    }

    /** 补全要解析参数类型的字段，先放一个 POJO 进临时工程 */
    private fun addUserPojo() {
        myFixture.addFileToProject(
            "com/example/User.java",
            """
            package com.example;

            public class User {
                private Long id;
                private String userName;

                public Long getId() {
                    return id;
                }

                public void setId(Long id) {
                    this.id = id;
                }

                public String getUserName() {
                    return userName;
                }

                public void setUserName(String userName) {
                    this.userName = userName;
                }
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

    /** 在语句文本的 `<caret>` 处触发补全，返回候选名字清单 */
    private fun complete(body: String): List<String> {
        myFixture.configureByText("UserMapper.xml", mapperXml(body))
        myFixture.completeBasic()
        return myFixture.lookupElementStrings ?: emptyList()
    }

    fun `test 提示注解参数名与 paramN 内置变量`() {
        addParamAnnotationStub()
        addMapper("    void list(@Param(\"status\") String status, String userName);")
        val names = complete(
            """
            <select id="list" resultType="map">
                select * from t_user where status = #{<caret>}
            </select>
            """.trimIndent()
        )

        assertTrue("应有 @Param 值 status：$names", "status" in names)
        assertTrue("应有参数名 userName：$names", "userName" in names)
        assertTrue("应有 param1：$names", "param1" in names)
        assertTrue("应有 arg0：$names", "arg0" in names)
        assertTrue("应有 _parameter：$names", "_parameter" in names)
        assertTrue("应有 _databaseId：$names", "_databaseId" in names)
    }

    fun `test 提示 foreach 与 bind 局部变量`() {
        addParamAnnotationStub()
        addMapper("    void batch(@Param(\"list\") java.util.List<String> list, String suffix);")
        val names = complete(
            """
            <update id="batch">
                <foreach collection="list" item="item" index="i" open="(" close=")" separator=",">
                    id = #{<caret>}
                </foreach>
            </update>
            """.trimIndent()
        )

        assertTrue("应有 foreach 的 item：$names", "item" in names)
        assertTrue("应有 foreach 的 index：$names", "i" in names)
        assertTrue("应有参数 suffix：$names", "suffix" in names)
    }

    fun `test 单参数 POJO 未注解时提示属性`() {
        addUserPojo()
        addMapper("    void insert(com.example.User user);")
        val names = complete(
            """
            <insert id="insert">
                insert into t_user (id, name) values (#{<caret>}, #{name})
            </insert>
            """.trimIndent()
        )

        assertTrue("应有 POJO 属性 id：$names", "id" in names)
        assertTrue("应有 POJO 属性 userName：$names", "userName" in names)
        assertFalse("单参数 POJO 场景不该提 value：$names", "value" in names)
    }

    fun `test 单参数简单类型提示 value`() {
        addParamAnnotationStub()
        addMapper("    void deleteById(Long id);")
        val names = complete(
            """
            <delete id="deleteById">
                delete from t_user where id = #{<caret>}
            </delete>
            """.trimIndent()
        )

        assertTrue("简单类型单参数应提 value：$names", "value" in names)
        assertTrue("也该提参数名 id：$names", "id" in names)
    }

    fun `test 点号之后提示属性路径`() {
        addUserPojo()
        addMapper("    void update(@Param(\"user\") com.example.User user);")
        val names = complete(
            """
            <update id="update">
                update t_user set name = #{user.<caret>} where id = #{user.id}
            </update>
            """.trimIndent()
        )

        assertTrue("user. 之后应有属性 userName：$names", "userName" in names)
        assertTrue("user. 之后应有属性 id：$names", "id" in names)
    }

    fun `test 已输入前缀只留匹配项`() {
        addParamAnnotationStub()
        addMapper("    void list(@Param(\"status\") String status, String userName);")
        val names = complete(
            """
            <select id="list" resultType="map">
                select * from t_user where name = #{user<caret>}
            </select>
            """.trimIndent()
        )

        assertTrue("前缀 user 应匹配 userName：$names", "userName" in names)
        assertFalse("前缀 user 不该匹配 status：$names", "status" in names)
    }

    fun `test sql 片段与非 mapper 位置不出候选`() {
        addParamAnnotationStub()
        addMapper("    void list(@Param(\"status\") String status);")
        val sqlFragment = complete(
            """
            <sql id="base">
                where status = #{<caret>}
            </sql>
            """.trimIndent()
        )
        assertTrue("<sql> 片段的参数来自 include 它的语句，不出候选：$sqlFragment", sqlFragment.isEmpty())

        myFixture.configureByText(
            "plain.xml",
            """
            <root>
                <config>#{<caret>}</config>
            </root>
            """.trimIndent()
        )
        myFixture.completeBasic()
        val plain = myFixture.lookupElementStrings ?: emptyList()
        assertTrue("非 mapper 文件不出候选：$plain", plain.isEmpty())
    }
}
