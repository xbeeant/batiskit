package io.github.xbeeant.batiskit.completion

import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * MyBatis 语法补全的 PSI 级测试：`#{}` 选项、`JdbcType` / `mode` 枚举值、
 * 语句属性枚举值与内置类型别名。
 */
class MapperSyntaxCompletionTest : BasePlatformTestCase() {

    private fun addUserPojo() {
        myFixture.addFileToProject(
            "com/example/User.java",
            """
            package com.example;

            public class User {
                private Long id;
                private String userName;

                public Long getId() { return id; }
                public void setId(Long id) { this.id = id; }
                public String getUserName() { return userName; }
                public void setUserName(String userName) { this.userName = userName; }
            }
            """.trimIndent(),
        )
    }

    private fun addMapper(methods: String) {
        myFixture.addFileToProject(
            "com/example/mapper/UserMapper.java",
            """
            package com.example.mapper;

            public interface UserMapper {
                $methods
            }
            """.trimIndent(),
        )
    }

    private fun complete(body: String): List<String> {
        myFixture.configureByText(
            "UserMapper.xml",
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <mapper namespace="com.example.mapper.UserMapper">
            $body
            </mapper>
            """.trimIndent(),
        )
        myFixture.completeBasic()
        return myFixture.lookupElementStrings ?: emptyList()
    }

    fun `test 参数占位符里提示选项名`() {
        val names = complete("<select id=\"list\">select * from t where a = #{name,<caret>}</select>")
        assertTrue("应提示 jdbcType=：$names", names.any { it.startsWith("jdbcType=") })
        assertTrue("应提示 mode=：$names", names.any { it.startsWith("mode=") })
        assertTrue("应提示 typeHandler=：$names", names.any { it.startsWith("typeHandler=") })
    }

    fun `test jdbcType 与 mode 提示枚举值`() {
        val jdbc = complete("<select id=\"list\">select * from t where a = #{name,jdbcType=<caret>}</select>")
        assertTrue("应提示 VARCHAR：$jdbc", "VARCHAR" in jdbc)
        assertTrue("应提示 TIMESTAMP：$jdbc", "TIMESTAMP" in jdbc)
        assertTrue("应提示 JAVA_OBJECT：$jdbc", "JAVA_OBJECT" in jdbc)

        val mode = complete("<select id=\"list\">select * from t where a = #{name,mode=<caret>}</select>")
        assertTrue("应提示 INOUT：$mode", "INOUT" in mode)
        assertFalse("INOUT 列表里不该有 JDBC 值：$mode", "VARCHAR" in mode)
    }

    fun `test 枚举值按已输入前缀过滤`() {
        val jdbc = complete("<select id=\"list\">select * from t where a = #{name,jdbcType=VA<caret>}</select>")
        assertTrue("VARCHAR 应保留：$jdbc", "VARCHAR" in jdbc)
        assertFalse("NUMERIC 应被过滤：$jdbc", "NUMERIC" in jdbc)
    }

    fun `test 语句属性枚举值`() {
        val statementType = complete("<select id=\"list\" statementType=\"<caret>\">select 1</select>")
        assertTrue("应提示 PREPARED：$statementType", "PREPARED" in statementType)

        val resultSetType = complete("<select id=\"list\" resultSetType=\"SC<caret>\">select 1</select>")
        assertTrue("应提示 SCROLL_SENSITIVE：$resultSetType", "SCROLL_SENSITIVE" in resultSetType)
        assertFalse("FORWARD_ONLY 应被过滤：$resultSetType", "FORWARD_ONLY" in resultSetType)

        val order = complete("<selectKey keyProperty=\"id\" order=\"<caret>\" resultType=\"int\">select 1</selectKey>")
        assertTrue("应提示 BEFORE：$order", "BEFORE" in order)
        assertTrue("应提示 AFTER：$order", "AFTER" in order)
    }

    fun `test 类型属性提示 MyBatis 内置别名`() {
        val names = complete("<select id=\"list\" resultType=\"<caret>\">select 1</select>")
        assertTrue("应提示 map 别名：$names", "map" in names)
        assertTrue("应提示 string 别名：$names", "string" in names)
        assertTrue("应提示 int 别名：$names", "int" in names)
    }

    fun `test result property 从 resultMap 的 type 提示字段`() {
        addUserPojo()
        val names = complete(
            """
            <resultMap id="BaseResultMap" type="com.example.User">
                <result column="user_name" property="<caret>"/>
            </resultMap>
            """.trimIndent(),
        )
        assertTrue("应提示 userName：$names", "userName" in names)
        assertTrue("应提示 id：$names", "id" in names)
    }

    fun `test keyProperty 从方法参数类型提示字段`() {
        addUserPojo()
        addMapper("    void insertUser(com.example.User user);")
        myFixture.configureByText(
            "UserMapper.xml",
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <mapper namespace="com.example.mapper.UserMapper">
            <insert id="insertUser" keyProperty="<caret>">insert into t values (1)</insert>
            </mapper>
            """.trimIndent(),
        )
        myFixture.completeBasic()
        val properties = myFixture.lookupElementStrings ?: emptyList()
        assertTrue("应提示 userName：$properties", "userName" in properties)
        assertTrue("应提示 id：$properties", "id" in properties)
    }

    fun `test typeHandler 提示 Java 类名`() {
        myFixture.addFileToProject(
            "com/example/MyTypeHandler.java",
            "package com.example; public class MyTypeHandler {}",
        )
        val names = complete(
            "<select id=\"list\">select #{name,typeHandler=com.example.<caret>} from t</select>",
        )
        assertTrue("应提示 MyTypeHandler：$names", names.any { "MyTypeHandler" in it })
    }

    fun `test 占位符里的 resultMap 提示当前文件的 resultMap id`() {
        val names = complete(
            """
            <resultMap id="BaseResultMap" type="map"/>
            <select id="list" resultMap="BaseResultMap">select #{name,resultMap=<caret>} from t</select>
            """.trimIndent(),
        )
        assertTrue("应提示 BaseResultMap：$names", "BaseResultMap" in names)
    }

    fun `test 输入小于号提示 MyBatis 动态标签`() {
        val names = complete("<select id=\"list\">select * from t <<caret> and x = 1</select>")
        assertTrue("应提示 if：$names", "if" in names)
        assertTrue("应提示 foreach：$names", "foreach" in names)
        assertTrue("应提示 where：$names", "where" in names)
        assertTrue("应提示 include：$names", "include" in names)
    }

    fun `test 选中动态标签后补全开闭标签`() {
        myFixture.configureByText(
            "UserMapper.xml",
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <mapper namespace="com.example.mapper.UserMapper">
            <select id="list">select * from t <fore<caret></select>
            </mapper>
            """.trimIndent(),
        )
        myFixture.completeBasic()
        myFixture.checkResult(
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <mapper namespace="com.example.mapper.UserMapper">
            <select id="list">select * from t <foreach><caret></foreach></select>
            </mapper>
            """.trimIndent(),
        )
    }
}
