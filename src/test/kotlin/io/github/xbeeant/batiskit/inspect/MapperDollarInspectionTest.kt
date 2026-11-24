package io.github.xbeeant.batiskit.inspect

import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * `${...}` SQL 注入风险检查的 PSI 级测试。
 */
class MapperDollarInspectionTest : BasePlatformTestCase() {

    private fun addMapper(methods: String) {
        myFixture.addFileToProject(
            "com/example/mapper/UserMapper.java",
            """
            package com.example.mapper;

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

    private fun check(xml: String): List<String> {
        myFixture.configureByText("UserMapper.xml", xml)
        myFixture.enableInspections(MapperDollarInspection())
        return myFixture.doHighlighting().mapNotNull { it.description }
    }

    fun `test 语句内的美元占位符报 SQL 注入风险`() {
        addMapper("    List<String> list(@Param(\"orderColumn\") String orderColumn);")
        val problems = check(
            mapperXml("    <select id=\"list\" resultType=\"string\">select * from t order by ${'$'}{orderColumn}</select>")
        ).filter { it.contains("SQL 注入风险") }

        assertEquals(1, problems.size)
        assertTrue(problems[0].contains("\${orderColumn}"))
    }

    fun `test 井号占位符不报`() {
        addMapper("    List<String> list(@Param(\"name\") String name);")
        val problems = check(
            mapperXml("    <select id=\"list\" resultType=\"string\">select * from t where name = #{name}</select>")
        ).filter { it.contains("SQL 注入风险") }

        assertEmpty(problems)
    }

    fun `test sql 片段里的美元占位符也报`() {
        addMapper("    List<String> list();")
        val problems = check(
            mapperXml(
                """
                    <sql id="order">${'$'}{orderColumn}</sql>
                    <select id="list" resultType="string">select * from t order by <include refid="order"/></select>
                """.trimIndent()
            )
        ).filter { it.contains("SQL 注入风险") }

        assertEquals(1, problems.size)
    }

    fun `test resultMap 与属性里的动态表达式不报`() {
        addMapper("    List<String> list();")
        val problems = check(
            mapperXml(
                """
                    <resultMap id="m" type="string">
                        <result column="name" property="name"/>
                    </resultMap>
                    <select id="list" resultMap="m">select * from t</select>
                """.trimIndent()
            )
        ).filter { it.contains("SQL 注入风险") }

        assertEmpty(problems)
    }

    fun `test 非 mapper 根标签不检查`() {
        val problems = check(
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <beans>
                <property name="sql" value="${'$'}{jdbc.url}"/>
            </beans>
            """.trimIndent()
        )

        assertEmpty(problems)
    }
}
