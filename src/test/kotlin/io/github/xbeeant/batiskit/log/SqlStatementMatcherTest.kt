package io.github.xbeeant.batiskit.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** MyBatis 日志 SQL → Mapper XML 语句的文本相似度匹配（纯逻辑） */
class SqlStatementMatcherTest {

    private val logSql = "SELECT id, name FROM t_user WHERE id = ?"

    private fun candidate(id: String, body: String) =
        SqlStatementMatcher.Candidate(id, "com.example.UserMapper", "<select id=\"$id\">$body</select>")

    @Test
    fun `归一化把占位符 动态标签 注释与空白统一`() {
        val xml = """
            <select id="x">
              SELECT * FROM t_user -- 注释
              <where><if test="id != null"> AND id = #{id} </if></where>
              /* 块注释 */ AND name LIKE ${'$'}{keyword}
            </select>
        """.trimIndent()
        val normalized = SqlStatementMatcher.normalize(xml)
        assertTrue(normalized.startsWith("select * from t_user"))
        assertTrue(normalized.contains("and id = ?"))
        assertTrue(normalized.contains("and name like ?"))
        assertFalse(normalized.contains("<where>"))
        assertFalse(normalized.contains("注释"))
        assertFalse(normalized.contains("  "))
        assertEquals("select id, name from t_user where id = ?", SqlStatementMatcher.normalize(logSql))
    }

    @Test
    fun `词元过滤 SQL 骨架词 纯数字与单字符`() {
        val tokens = SqlStatementMatcher.tokens("select id, t_user from t_user where id = ? and a = 123")
        assertEquals(setOf("id", "t_user"), tokens)
    }

    @Test
    fun `同一个语句的 XML 与日志得分很高`() {
        val xml = "SELECT id, name FROM t_user WHERE id = #{id}"
        val score = SqlStatementMatcher.score(logSql, xml)
        assertTrue("同一条语句应得高分，实际 $score", score >= 70)
        assertEquals(score, SqlStatementMatcher.score(logSql, "SELECT id, name FROM t_user WHERE id = \${id}"))
    }

    @Test
    fun `SQL 动词不同直接判零`() {
        val update = "UPDATE t_user SET name = #{name} WHERE id = #{id}"
        assertEquals(0, SqlStatementMatcher.score(logSql, update))
        assertEquals(0, SqlStatementMatcher.score(logSql, "INSERT INTO t_user (id) VALUES (#{id})"))
    }

    @Test
    fun `不相关的表与列不进入候选`() {
        val other = candidate("selectOrders", "SELECT order_no FROM t_order WHERE user_id = #{userId}")
        val score = SqlStatementMatcher.score(logSql, other.sql)
        assertTrue("不相关 SQL 应低于阈值，实际 $score", score < SqlStatementMatcher.MIN_SCORE)
        assertTrue(SqlStatementMatcher.rank(logSql, listOf(other)).isEmpty())
        assertEquals(0, SqlStatementMatcher.score(logSql, ""))
    }

    @Test
    fun `rank 按分数降序 过滤低分并生效 limit`() {
        val exact = candidate("selectById", "SELECT id, name FROM t_user WHERE id = #{id}")
        val similar = candidate("selectByName", "SELECT id, name FROM t_user WHERE name = #{name}")
        val unrelated = candidate("selectOrders", "SELECT order_no FROM t_order WHERE user_id = #{userId}")
        val ranked = SqlStatementMatcher.rank(logSql, listOf(unrelated, similar, exact))

        assertTrue(ranked.isNotEmpty())
        assertTrue("只保留可信候选", ranked.none { it.candidate.id == "selectOrders" })
        assertTrue(ranked.all { it.score >= SqlStatementMatcher.MIN_SCORE })
        assertTrue("分数降序", ranked.zipWithNext().all { (a, b) -> a.score >= b.score })
        assertEquals(2, SqlStatementMatcher.rank(logSql, listOf(exact, similar, unrelated), limit = 2).size)
    }

    @Test
    fun `相似候选难以区分时不自动跳转 由用户选`() {
        val a = candidate("a", "SELECT id, name FROM t_user WHERE id = #{id}")
        val b = candidate("b", "SELECT id, name FROM t_user WHERE name = #{name}")
        val matches = SqlStatementMatcher.rank(logSql, listOf(a, b))
        assertTrue(matches.size >= 2)
        assertFalse("相近语句应弹选择器", SqlStatementMatcher.isClearWinner(matches))

        val single = SqlStatementMatcher.rank(logSql, listOf(a))
        assertTrue("唯一候选直接跳", SqlStatementMatcher.isClearWinner(single))
        assertFalse(SqlStatementMatcher.isClearWinner(emptyList()))
    }

    @Test
    fun `动态标签里的条件也参与匹配 不因分支多而失分过多`() {
        val dynamic = """
            <select id="x">
                SELECT id, name FROM t_user
                <where>
                    <if test="id != null">AND id = #{id}</if>
                    <if test="name != null">AND name = #{name}</if>
                </where>
            </select>
        """.trimIndent()
        assertTrue(
            "动态 SQL 仍应匹配，实际 ${SqlStatementMatcher.score(logSql, dynamic)}",
            SqlStatementMatcher.score(logSql, dynamic) >= SqlStatementMatcher.MIN_SCORE
        )
    }

    @Test
    fun `分页插件追加的 LIMIT 不影响匹配`() {
        val xml = "SELECT id, name FROM t_user WHERE id = #{id} ORDER BY id DESC"
        val paged = "SELECT id, name FROM t_user WHERE id = ? ORDER BY id DESC LIMIT ?, ?"
        assertEquals(
            "SELECT id, name FROM t_user WHERE id = ? ORDER BY id DESC",
            SqlStatementMatcher.stripPagination(paged),
        )
        val score = SqlStatementMatcher.score(paged, xml)
        assertTrue("分页 SQL 应仍与 XML 匹配，实际 $score", score >= 70)
    }

    @Test
    fun `分页 count 包装还原内部 SELECT 后能准确命中`() {
        val right = candidate("selectById", "SELECT id, name FROM t_user WHERE id = #{id}")
        val wrong = candidate("selectOrder", "SELECT id, name FROM t_order WHERE id = #{id}")
        val countSql = "SELECT count(0) FROM (SELECT id, name FROM t_user WHERE id = ?) tmp_count"

        assertEquals(
            "SELECT id, name FROM t_user WHERE id = ?",
            SqlStatementMatcher.stripPagination(countSql),
        )
        val matches = SqlStatementMatcher.rank(countSql, listOf(wrong, right))
        assertTrue("应命中原语句，实际 $matches", matches.firstOrNull()?.candidate?.id == "selectById")
        assertTrue("分页 count 也应能直接跳转，实际 $matches", SqlStatementMatcher.isClearWinner(matches))
    }

    @Test
    fun `优化后的 count 查询按 from 之后的语句匹配`() {
        val right = candidate("selectById", "SELECT id, name FROM t_user WHERE id = #{id}")
        val wrong = candidate("selectOrder", "SELECT id, name FROM t_order WHERE id = #{id}")
        val optimizedCount = "SELECT COUNT(*) FROM t_user WHERE id = ?"

        val matches = SqlStatementMatcher.rank(optimizedCount, listOf(wrong, right))
        assertTrue("应命中原语句，实际 $matches", matches.firstOrNull()?.candidate?.id == "selectById")
        assertTrue("count 优化后的日志也能直接跳转，实际 $matches", SqlStatementMatcher.isClearWinner(matches))
    }

    @Test
    fun `SQLServer 与 Oracle 的分页壳也能剥掉`() {
        val base = "SELECT id, name FROM t_user WHERE id = ?"
        assertEquals(
            base,
            SqlStatementMatcher.stripPagination("$base OFFSET ? ROWS FETCH NEXT ? ROWS ONLY"),
        )
        assertEquals(
            base,
            SqlStatementMatcher.stripPagination("SELECT * FROM ($base) WHERE ROWNUM <= ?"),
        )
    }

    @Test
    fun `include 引用的 SQL 片段内联后列名参与匹配`() {
        val fragments = mapOf(
            "Base_Column_List" to "id, name FROM t_user",
            "UserMapper.Base_Column_List" to "id, name FROM t_user",
        )
        val xml = "<select id=\"x\">SELECT <include refid=\"Base_Column_List\"/> WHERE id = #{id}</select>"
        val expanded = SqlStatementMatcher.inlineIncludes(xml, fragments)
        assertFalse("include 应被展开", expanded.contains("<include"))
        assertTrue("片段内容应进入候选", expanded.contains("id, name FROM t_user"))
        assertTrue(
            "内联后应与日志匹配，实际 ${SqlStatementMatcher.score(logSql, xml)}",
            SqlStatementMatcher.score(logSql, expanded) >= 70,
        )
    }
}
