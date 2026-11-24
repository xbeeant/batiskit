package io.github.xbeeant.batiskit.inspect

import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * resultType / resultMap 与接口方法返回类型匹配检查的 PSI 级测试。
 */
class MapperReturnTypeInspectionTest : BasePlatformTestCase() {

    override fun setUp() {
        super.setUp()
        myFixture.addFileToProject(
            "com/example/model/User.java",
            "package com.example.model;\n\npublic class User {\n}\n"
        )
        // Admin extends User：List<父类> 配子类 resultType 是合法写法
        myFixture.addFileToProject(
            "com/example/model/Admin.java",
            "package com.example.model;\n\npublic class Admin extends User {\n}\n"
        )
        myFixture.addFileToProject(
            "com/example/model/Other.java",
            "package com.example.model;\n\npublic class Other {\n}\n"
        )
        // 分页包装：PageHelper 风格（Collection 子类）、纯包装类、以及任意泛型包装
        myFixture.addFileToProject(
            "com/example/page/Page.java",
            "package com.example.page;\n\nimport java.util.ArrayList;\n\npublic class Page<T> extends ArrayList<T> {\n}\n"
        )
        myFixture.addFileToProject(
            "com/example/page/PageInfo.java",
            "package com.example.page;\n\npublic class PageInfo<T> {\n    private java.util.List<T> list;\n}\n"
        )
        myFixture.addFileToProject(
            "com/example/page/Result.java",
            "package com.example.page;\n\npublic class Result<T> {\n    private T data;\n}\n"
        )
        myFixture.addFileToProject(
            "com/example/mapper/UserMapper.java",
            """
            package com.example.mapper;

            import com.example.model.Other;
            import com.example.model.User;
            import com.example.page.Page;
            import com.example.page.PageInfo;
            import com.example.page.Result;
            import java.util.List;

            public interface UserMapper {
                List<User> list();

                List<Other> listOther();

                User get(Long id);

                Other getOther(Long id);

                java.util.Map<String, Object> map();

                Page<User> page();

                PageInfo<User> pageInfo();

                Page<Other> pageOther();

                Page rawPage();

                Result<List<User>> wrapped();
            }
            """.trimIndent()
        )
    }

    private fun check(xml: String): List<String> {
        myFixture.configureByText("UserMapper.xml", xml)
        myFixture.enableInspections(MapperReturnTypeInspection())
        return myFixture.doHighlighting().mapNotNull { it.description }
    }

    fun `test resultType 与元素类型一致时不报`() {
        val problems = check(
            """
            <mapper namespace="com.example.mapper.UserMapper">
                <select id="list" resultType="com.example.model.User">select * from t_user</select>
                <select id="get" resultType="com.example.model.User">select * from t_user where id = 1</select>
                <select id="map" resultType="java.util.HashMap">select * from t_user</select>
            </mapper>
            """.trimIndent()
        )

        assertEmpty(problems)
    }

    fun `test resultType 与元素类型不一致时报错`() {
        val problems = check(
            """
            <mapper namespace="com.example.mapper.UserMapper">
                <select id="listOther" resultType="com.example.model.User">select * from t_user</select>
            </mapper>
            """.trimIndent()
        ).filter { it.contains("不匹配") }

        assertEquals(1, problems.size)
        assertTrue(problems[0].contains("Other"))
    }

    fun `test List 父类配子类 resultType 不报`() {
        // List<User> 配 Admin（User 的子类）是合法写法
        myFixture.addFileToProject(
            "com/example/mapper/AdminMapper.java",
            """
            package com.example.mapper;

            import com.example.model.Admin;
            import com.example.model.User;
            import java.util.List;

            public interface AdminMapper {
                List<User> list();
            }
            """.trimIndent()
        )
        val problems = check(
            """
            <mapper namespace="com.example.mapper.AdminMapper">
                <select id="list" resultType="com.example.model.Admin">select * from t_user</select>
            </mapper>
            """.trimIndent()
        )

        assertEmpty(problems)
    }

    fun `test 别名解析不到时放弃`() {
        // resultType="map" 是 MyBatis 别名，静态解析不到，不能当作不匹配
        val problems = check(
            """
            <mapper namespace="com.example.mapper.UserMapper">
                <select id="map" resultType="map">select * from t_user</select>
            </mapper>
            """.trimIndent()
        )

        assertEmpty(problems)
    }

    fun `test resultMap 的 type 不匹配时报错`() {
        // list() 返回 List<User>，resultMap 却映射到 Other：不匹配
        val problems = check(
            """
            <mapper namespace="com.example.mapper.UserMapper">
                <resultMap id="rm" type="com.example.model.Other">
                    <result column="id" property="id"/>
                </resultMap>
                <select id="list" resultMap="rm">select * from t_user</select>
            </mapper>
            """.trimIndent()
        ).filter { it.contains("不匹配") }

        assertEquals(1, problems.size)
    }

    fun `test 解析不到接口时整体放弃`() {
        val problems = check(
            """
            <mapper namespace="com.dependency.mapper.MissingMapper">
                <select id="list" resultType="com.example.model.Other">select 1</select>
            </mapper>
            """.trimIndent()
        )

        assertEmpty(problems)
    }

    fun `test 非 mapper 根标签不检查`() {
        val problems = check(
            """
            <beans>
                <bean id="a" class="A"/>
            </beans>
            """.trimIndent()
        )

        assertEmpty(problems)
    }

    fun `test 分页包装按泛型元素比对不误报`() {
        // Page<User>（Collection 子类）/ PageInfo<User>（纯包装）/ 裸 Page / Result<List<User>>
        // 行类型都是 User，不能把包装类本身当行类型
        val problems = check(
            """
            <mapper namespace="com.example.mapper.UserMapper">
                <resultMap id="rm" type="com.example.model.User">
                    <id column="id" property="id"/>
                </resultMap>
                <select id="page" resultMap="rm">select * from t_user</select>
                <select id="pageInfo" resultType="com.example.model.User">select * from t_user</select>
                <select id="rawPage" resultType="com.example.model.User">select * from t_user</select>
                <select id="wrapped" resultType="com.example.model.User">select * from t_user</select>
            </mapper>
            """.trimIndent()
        )

        assertEmpty(problems)
    }

    fun `test 分页包装元素类型不一致时仍报错`() {
        // Page<Other> 配 resultType=User 是真不匹配，别因为「认识 Page」就整个放弃检查
        val problems = check(
            """
            <mapper namespace="com.example.mapper.UserMapper">
                <select id="pageOther" resultType="com.example.model.User">select * from t_user</select>
            </mapper>
            """.trimIndent()
        ).filter { it.contains("不匹配") }

        assertEquals(1, problems.size)
        assertTrue(problems[0].contains("Other"))
    }

    fun `test resultMap 定义在同 namespace 的另一个 XML 也能取到 type`() {
        // MBG 生成的基础 XML + 手写扩展 XML 共用 namespace 是常见拆分
        myFixture.addFileToProject(
            "mybatis/UserMapperBase.xml",
            """
            <mapper namespace="com.example.mapper.UserMapper">
                <resultMap id="BaseResultMap" type="com.example.model.Other">
                    <id column="id" property="id"/>
                </resultMap>
            </mapper>
            """.trimIndent()
        )
        val problems = check(
            """
            <mapper namespace="com.example.mapper.UserMapper">
                <select id="list" resultMap="BaseResultMap">select * from t_user</select>
            </mapper>
            """.trimIndent()
        ).filter { it.contains("不匹配") }

        assertEquals(1, problems.size)
        assertTrue(problems[0].contains("Other"))
    }

    fun `test 解析不到的 resultMap 不报 Cannot resolve symbol`() {
        // 定义可能在兄弟 XML / 依赖 jar / 未索引文件里：软引用，解析不到也不该有平台红字
        myFixture.configureByText(
            "UserMapper.xml",
            """
            <mapper namespace="com.example.mapper.UserMapper">
                <select id="page" resultMap="NoSuchMap">select * from t_user</select>
            </mapper>
            """.trimIndent()
        )
        val unresolved = myFixture.doHighlighting()
            .filter { it.description?.contains("Cannot resolve symbol") == true }

        assertEmpty(unresolved)
    }
}
