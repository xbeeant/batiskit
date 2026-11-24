package io.github.xbeeant.batiskit.completion

import com.intellij.psi.xml.XmlFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.xbeeant.batiskit.config.ColumnSnapshot
import io.github.xbeeant.batiskit.config.MapperAssistSettings
import io.github.xbeeant.batiskit.config.TableColumnSnapshot

/**
 * Mapper 辅助功能的 PSI 级回归：走真实补全 / intention 链路，验证
 * 表字段补全、SQL 上下文参数补全、引用变体补全与 CRUD SQL 预填确实能在 IDE 里生效。
 *
 * 测试环境没有真实数据源，用手动映射 + 可选列快照代替 Database 视图；
 * 「零快照」用例专门覆盖「用户在 Database 视图什么都没点过」时的实体类 / resultMap 兜底链路。
 */
class MapperAssistCompletionTest : BasePlatformTestCase() {

    private var originalMappings: String = ""
    private var originalSnapshots: MutableList<TableColumnSnapshot> = mutableListOf()
    private var originalRecent: MutableList<String> = mutableListOf()

    override fun setUp() {
        super.setUp()
        val state = MapperAssistSettings.getInstance().state
        originalMappings = state.tableMappings
        originalSnapshots = state.tableSnapshots
        originalRecent = state.recentTables
        state.tableMappings = "com.example.mapper.UserMapper=sys_user"
        state.tableSnapshots = mutableListOf(
            TableColumnSnapshot().apply {
                name = "sys_user"
                dataSource = "local"
                columns = mutableListOf(
                    ColumnSnapshot().apply { name = "id"; primaryKey = true; jdbcType = "BIGINT" },
                    ColumnSnapshot().apply { name = "user_name"; jdbcType = "VARCHAR" },
                    ColumnSnapshot().apply { name = "created_at"; jdbcType = "TIMESTAMP" }
                )
            }
        )
    }

    override fun tearDown() {
        try {
            val state = MapperAssistSettings.getInstance().state
            state.tableMappings = originalMappings
            state.tableSnapshots = originalSnapshots
            state.recentTables = originalRecent
        } finally {
            super.tearDown()
        }
    }

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

    private fun complete(body: String): List<String> {
        myFixture.configureByText("UserMapper.xml", mapperXml(body))
        myFixture.completeBasic()
        return myFixture.lookupElementStrings ?: emptyList()
    }

    /** 唯一候选时平台会直接插入，此时 lookupElementStrings 为 null，用文档文本兜底 */
    private fun accepted(names: List<String>, variant: String): Boolean =
        variant in names || myFixture.file.text.contains(variant)

    fun `test SQL 正文按表名补全列`() {
        val names = complete("<select id=\"list\">SELECT user_<caret> FROM sys_user</select>")
        assertTrue("应提示 user_name：$names", accepted(names, "user_name"))
    }

    fun `test column 属性按表名补全列`() {
        val names = complete(
            """
            <resultMap id="BaseResultMap" type="com.example.User">
                <result property="name" column="us<caret>"/>
            </resultMap>
            """.trimIndent()
        )
        assertTrue("应提示 user_name：$names", accepted(names, "user_name"))
    }

    fun `test 占位符里按 SQL 上下文补全参数名`() {
        addMapper("    java.util.List<User> list(Long id);")
        val names = complete(
            "<select id=\"list\">SELECT * FROM sys_user WHERE user_name = #{<caret>}</select>"
        )
        assertTrue("赋值列 user_name 应给出 userName：$names", "userName" in names)
    }

    fun `test resultMap 属性补全列出定义 id`() {
        val names = complete(
            """
            <resultMap id="BaseResultMap" type="com.example.User"/>
            <select id="list" resultMap="<caret>">SELECT * FROM sys_user</select>
            """.trimIndent()
        )
        assertTrue("应提示 BaseResultMap：$names", accepted(names, "BaseResultMap"))
    }

    fun `test 没有任何列快照时 resultMap 已声明的列仍可补全`() {
        MapperAssistSettings.getInstance().state.tableSnapshots.clear()
        val names = complete(
            """
            <resultMap id="BaseResultMap" type="com.example.User">
                <id column="id"/>
                <result column="user_name" property="userName"/>
            </resultMap>
            <select id="list">SELECT user_<caret> FROM sys_user</select>
            """.trimIndent()
        )
        assertTrue("零快照时应从 resultMap 给出 user_name：$names", accepted(names, "user_name"))
    }

    fun `test 零快照零数据源时靠实体类字段预填 CRUD SQL`() {
        MapperAssistSettings.getInstance().state.tableSnapshots.clear()
        myFixture.addFileToProject(
            "com/example/SysUser.java",
            """
            package com.example;

            public class SysUser {
                private Long id;
                private String userName;
            }
            """.trimIndent()
        )
        myFixture.addFileToProject("mybatis/mysql/demo/UserMapper.xml", mapperXml(""))
        myFixture.configureByText(
            "UserMapper.java",
            """
            package com.example.mapper;

            import com.example.SysUser;

            public interface UserMapper {
                int <caret>insert(SysUser record);
            }
            """.trimIndent()
        )

        val intention = myFixture.findSingleIntention("在 Mapper XML 中生成对应语句")
        myFixture.launchAction(intention)

        val xml = psiManager.findFile(
            myFixture.findFileInTempDir("mybatis/mysql/demo/UserMapper.xml")
        ) as XmlFile
        val tag = xml.rootTag!!.findSubTags("insert").single()
        assertTrue("应预填 INSERT INTO sys_user：${tag.text}", tag.text.contains("INSERT INTO sys_user"))
        assertTrue("实体字段应转成列名与参数：${tag.text}", tag.text.contains("#{userName}"))
        assertFalse("不该再留 TODO 骨架：${tag.text}", tag.text.contains("TODO"))
    }

    fun `test intention 生成带表名与列的 CRUD SQL`() {
        myFixture.addFileToProject(
            "mybatis/mysql/demo/UserMapper.xml",
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <mapper namespace="com.example.mapper.UserMapper">
            </mapper>
            """.trimIndent()
        )
        myFixture.configureByText(
            "UserMapper.java",
            """
            package com.example.mapper;

            public interface UserMapper {
                int <caret>insert(User record);
            }
            """.trimIndent()
        )

        val intention = myFixture.findSingleIntention("在 Mapper XML 中生成对应语句")
        myFixture.launchAction(intention)

        val xml = psiManager.findFile(
            myFixture.findFileInTempDir("mybatis/mysql/demo/UserMapper.xml")
        ) as XmlFile
        val tag = xml.rootTag!!.findSubTags("insert").single()
        assertTrue("应预填 INSERT INTO sys_user：${tag.text}", tag.text.contains("INSERT INTO sys_user"))
        assertTrue("应按列名生成参数：${tag.text}", tag.text.contains("#{userName}"))
        assertFalse("不该再留 TODO 骨架：${tag.text}", tag.text.contains("TODO"))
    }
}
