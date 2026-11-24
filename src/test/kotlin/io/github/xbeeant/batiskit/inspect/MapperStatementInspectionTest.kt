package io.github.xbeeant.batiskit.inspect

import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Mapper XML 侧检查（重复 id / 孤立语句）的 PSI 级测试。
 *
 * 走真实高亮：先放接口与 XML，再 enableInspections + doHighlighting，按描述文案筛问题。
 */
class MapperStatementInspectionTest : BasePlatformTestCase() {

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

    private fun mapperXml(body: String, namespace: String = "com.example.mapper.UserMapper"): String = """
        <?xml version="1.0" encoding="UTF-8"?>
        <mapper namespace="$namespace">
        $body
        </mapper>
        """.trimIndent()

    private fun check(xml: String): List<String> {
        myFixture.configureByText("UserMapper.xml", xml)
        myFixture.enableInspections(MapperStatementInspection())
        return myFixture.doHighlighting().mapNotNull { it.description }
    }

    // ------------------------------------------------------------------ 重复 id

    fun `test 重复的语句 id 每一处都报`() {
        addMapper("    void list();")
        val problems = check(
            mapperXml(
                """
                    <select id="list">select 1</select>
                    <select id="list">select 2</select>
                """.trimIndent()
            )
        ).filter { it.contains("重复") }

        assertEquals("两处都应报出来", 2, problems.size)
        assertTrue(problems[0].contains("Mapped Statements collection"))
    }

    fun `test 重名但类型不同也算重复`() {
        addMapper("    void list();")
        val problems = check(
            mapperXml(
                """
                    <select id="list">select 1</select>
                    <update id="list">update t set a = 1</update>
                """.trimIndent()
            )
        ).filter { it.contains("重复") }

        assertEquals(2, problems.size)
    }

    fun `test 不重复时不报`() {
        addMapper("    void list();\n    void insert();")
        val problems = check(
            mapperXml(
                """
                    <select id="list">select 1</select>
                    <insert id="insert">insert into t values (1)</insert>
                """.trimIndent()
            )
        ).filter { it.contains("重复").or(it.contains("同名方法")) }

        assertEmpty(problems)
    }

    // ------------------------------------------------------------------ 孤立语句

    fun `test 接口里找不到同名方法时报孤立语句`() {
        addMapper("    void list();")
        val problems = check(
            mapperXml(
                """
                    <select id="list">select 1</select>
                    <select id="selectByCode">select * from t_user where code = 1</select>
                """.trimIndent()
            )
        ).filter { it.contains("没有同名方法") }

        assertEquals(1, problems.size)
        assertTrue(problems[0].contains("selectByCode"))
        assertTrue(problems[0].contains("com.example.mapper.UserMapper"))
    }

    fun `test sql 片段的 id 不当作语句检查`() {
        // <sql id> 只是片段，不需要接口方法对应
        addMapper("    void list();")
        val problems = check(
            mapperXml(
                """
                    <sql id="Base_Column_List">id, name</sql>
                    <select id="list">select <include refid="Base_Column_List"/> from t</select>
                """.trimIndent()
            )
        ).filter { it.contains("没有同名方法") }

        assertEmpty(problems)
    }

    fun `test 继承来的方法名也算有主`() {
        myFixture.addFileToProject(
            "com/example/mapper/BaseMapper.java",
            """
            package com.example.mapper;

            public interface BaseMapper<T> {
                T selectById(Long id);
            }
            """.trimIndent()
        )
        myFixture.addFileToProject(
            "com/example/mapper/UserMapper.java",
            """
            package com.example.mapper;

            public interface UserMapper extends BaseMapper<String> {
            }
            """.trimIndent()
        )
        // XML 覆盖父接口的 selectById 是合法写法（MyBatis-Plus 项目里很常见）
        val problems = check(
            mapperXml("    <select id=\"selectById\">select * from t_user where id = #{id}</select>")
        ).filter { it.contains("没有同名方法") }

        assertEmpty(problems)
    }

    fun `test 解析不到接口时整体放弃检查`() {
        // namespace 指向不存在的类：依赖包里带过来的 mapper 也是这种情况，
        // 这时一条都不能报，否则整个工程满屏红
        val problems = check(
            mapperXml(
                """
                    <select id="whatever">select 1</select>
                """.trimIndent(),
                namespace = "com.dependency.mapper.MissingMapper"
            )
        ).filter { it.contains("没有同名方法") }

        assertEmpty(problems)
    }

    fun `test 非 mapper 根标签不检查`() {
        val problems = check(
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <beans>
                <bean id="a" class="A"/>
                <bean id="a" class="B"/>
            </beans>
            """.trimIndent()
        )
        assertEmpty(problems)
    }
}
