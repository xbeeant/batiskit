package io.github.xbeeant.batiskit.log

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.util.Computable
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/** 日志跳 XML 的候选收集：分页日志下必须把 include 片段内联进候选 SQL */
class MapperStatementNavigatorTest : BasePlatformTestCase() {

    fun `test collect 内联 sql 片段后分页日志仍能命中原语句`() {
        myFixture.addFileToProject(
            "mapper/UserMapper.xml",
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <mapper namespace="com.example.UserMapper">
                <sql id="Base_Column_List">id, user_name FROM t_user</sql>
                <select id="selectById">SELECT <include refid="Base_Column_List"/> WHERE id = #{id}</select>
                <select id="selectOrder">SELECT id, order_no FROM t_order WHERE id = #{id}</select>
            </mapper>
            """.trimIndent(),
        )

        val items = ApplicationManager.getApplication().runReadAction(
            Computable { MapperStatementNavigator.collect(project) },
        )
        val candidate = items.first { it.candidate.id == "selectById" }
        assertTrue("候选应带有片段里的列名", candidate.candidate.sql.contains("user_name"))

        val pagedLog = "SELECT id, user_name FROM t_user WHERE id = ? LIMIT ?, ?"
        val ranked = SqlStatementMatcher.rank(pagedLog, items.map { it.candidate })
        assertEquals("selectById", ranked.firstOrNull()?.candidate?.id)
        assertTrue("应能自动跳转，实际 $ranked", SqlStatementMatcher.isClearWinner(ranked))
    }
}
