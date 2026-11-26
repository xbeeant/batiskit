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

    private fun addMapper(methods: String, className: String = "UserMapper") {
        myFixture.addFileToProject(
            "com/example/mapper/$className.java",
            """
            package com.example.mapper;

            import org.apache.ibatis.annotations.Param;

            public interface $className {
            $methods
            }
            """.trimIndent()
        )
    }

    /** BasePlatformTestCase 不挂 JDK，自己放最小集合接口，测 `<foreach>` 的泛型推导 */
    private fun addCollectionStubs() {
        myFixture.addFileToProject(
            "java/util/Iterable.java",
            "package java.util;\npublic interface Iterable<E> {}\n",
        )
        myFixture.addFileToProject(
            "java/util/Collection.java",
            "package java.util;\npublic interface Collection<E> extends Iterable<E> {}\n",
        )
        myFixture.addFileToProject(
            "java/util/List.java",
            "package java.util;\npublic interface List<E> extends Collection<E> {}\n",
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

    private fun mapperXml(body: String, namespace: String = "com.example.mapper.UserMapper"): String = """
        <?xml version="1.0" encoding="UTF-8"?>
        <mapper namespace="$namespace">
        $body
        </mapper>
        """.trimIndent()

    /** 在语句文本的 `<caret>` 处触发补全，返回候选名字清单 */
    private fun complete(body: String, namespace: String = "com.example.mapper.UserMapper"): List<String> {
        myFixture.configureByText("${namespace.substringAfterLast('.')}.xml", mapperXml(body, namespace))
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
        assertTrue("多参数应有 param2：$names", "param2" in names)
        assertTrue("应有 arg0：$names", "arg0" in names)
        assertTrue("多参数应有 arg1：$names", "arg1" in names)
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

    fun `test 输入 # 或 $ 就提示参数`() {
        addParamAnnotationStub()
        addMapper("    void list(@Param(\"status\") String status, String userName);", "MarkerMapper")
        val ns = "com.example.mapper.MarkerMapper"
        val hash = complete("<select id=\"list\">select * from t where name = #<caret></select>", ns)
        assertTrue("输入 # 就应提示注解参数：$hash", "status" in hash)
        assertTrue("输入 # 就应提示第二个参数：$hash", "userName" in hash)
        val dollar = complete("<select id=\"list\">select * from t where name = $<caret></select>", ns)
        assertTrue("输入 $ 就应提示参数：$dollar", "status" in dollar)
    }

    fun `test foreach item 按 collection 泛型提示属性`() {
        addParamAnnotationStub()
        addCollectionStubs()
        addUserPojo()
        addMapper("    void batch(@Param(\"users\") java.util.List<com.example.User> users);", "ForeachItemMapper")
        val names = complete(
            """
            <update id="batch">
                <foreach collection="users" item="user" separator=",">
                    name = #{user.<caret>}
                </foreach>
            </update>
            """.trimIndent(),
            "com.example.mapper.ForeachItemMapper",
        )
        assertTrue("item 应提示元素类型字段 userName：$names", "userName" in names)
        assertTrue("item 应提示元素类型字段 id：$names", "id" in names)
    }

    fun `test foreach 的 collection 与 OGNL test 属性提示参数`() {
        addParamAnnotationStub()
        addUserPojo()
        addMapper("    void batch(@Param(\"user\") com.example.User user);", "OgnlMapper")
        val ns = "com.example.mapper.OgnlMapper"
        val collection = complete(
            """
            <select id="batch">
                <foreach collection="<caret>" item="item">#{item}</foreach>
            </select>
            """.trimIndent(),
            ns,
        )
        assertTrue("collection 应提示参数名：$collection", "user" in collection)

        val test = complete(
            """
            <select id="batch">
                <if test="user.<caret>">id = #{user.id}</if>
            </select>
            """.trimIndent(),
            ns,
        )
        assertTrue("test 属性应提示参数字段：$test", "userName" in test)
    }

    fun `test bind 声明的变量随后可提示`() {
        addParamAnnotationStub()
        addMapper("    void list(@Param(\"name\") String name);", "BindMapper")
        val names = complete(
            """
            <select id="list">
                <bind name="pattern" value="'%' + name + '%'"/>
                select * from t where name like #{<caret>}
            </select>
            """.trimIndent(),
            "com.example.mapper.BindMapper",
        )
        assertTrue("bind 声明的 pattern 应可提示：$names", "pattern" in names)
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
