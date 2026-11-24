package io.github.xbeeant.batiskit.meta

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** SqlSemantics 纯逻辑测试：不启动 IDE，覆盖表/别名/列归属、动态标签、防御与光标前缀 */
class SqlSemanticsTest {

    // ------------------------------------------------------------------ 表与别名

    @Test
    fun `单表 FROM 解析出主表 别名与限定列`() {
        val ctx = SqlSemantics.parse("SELECT id, user_name FROM sys_user u WHERE u.id = #{id}")
        assertEquals("sys_user", ctx.mainTable)
        assertEquals(1, ctx.tables.size)
        assertEquals("u", ctx.tables[0].alias)
        assertEquals("sys_user", ctx.aliases["u"])
        assertTrue(ctx.columnsByOwner["u"]?.contains("id") == true)
    }

    @Test
    fun `JOIN 至少识别前两张表并分别记录别名`() {
        val ctx = SqlSemantics.parse(
            "SELECT u.id, o.order_no FROM sys_user u LEFT JOIN t_order o ON u.id = o.user_id"
        )
        assertEquals(2, ctx.tables.size)
        assertEquals(listOf("sys_user", "t_order"), ctx.tables.map { it.name })
        assertEquals(mapOf("u" to "sys_user", "o" to "t_order"), ctx.aliases)
        assertTrue(ctx.columnsByOwner["o"]?.containsAll(listOf("order_no", "user_id")) == true)
    }

    @Test
    fun `AS 别名与 schema 引号都能清洗`() {
        val ctx = SqlSemantics.parse(
            "SELECT * FROM `public`.`sys_user` AS u JOIN t_order AS o ON u.id = o.user_id"
        )
        assertEquals("sys_user", ctx.mainTable)
        assertEquals("u", ctx.tables[0].alias)
        assertEquals("o", ctx.tables[1].alias)
        assertEquals("sys_user", ctx.aliases["u"])
    }

    @Test
    fun `UPDATE INSERT DELETE 都识别主表名`() {
        assertEquals("t_user", SqlSemantics.parse("UPDATE t_user SET name = ? WHERE id = ?").mainTable)
        assertEquals("t_log", SqlSemantics.parse("DELETE FROM t_log WHERE id = ?").mainTable)
        assertEquals(
            "t_order",
            SqlSemantics.parse("INSERT INTO t_order (id, user_id) VALUES (?, ?)").mainTable
        )
    }

    @Test
    fun `INSERT INTO 的括号列清单归属到该表`() {
        val ctx = SqlSemantics.parse("INSERT INTO t_order (id, user_id) VALUES (?, ?)")
        assertTrue(ctx.columnsByOwner["t_order"]?.containsAll(listOf("id", "user_id")) == true)
    }

    // ------------------------------------------------------------------ 列归属 / ownerOf

    @Test
    fun `限定列按别名或表名 owner 分组`() {
        val ctx = SqlSemantics.parse(
            "SELECT u.id, sys_user.status FROM sys_user u JOIN t_order o ON u.id = o.user_id"
        )
        assertTrue(ctx.columnsByOwner["u"]?.contains("id") == true)
        assertTrue(ctx.columnsByOwner["sys_user"]?.contains("status") == true)
        assertTrue(ctx.columnsByOwner["o"]?.contains("user_id") == true)
        assertFalse(ctx.columnsByOwner["u"]?.contains("user_id") == true)
    }

    @Test
    fun `ownerOf 把限定列前缀解析成表名`() {
        val ctx = SqlSemantics.parse(
            "SELECT u.id FROM sys_user u JOIN t_order o ON u.id = o.user_id"
        )
        assertEquals("sys_user", SqlSemantics.ownerOf("u.id", ctx))
        assertEquals("sys_user", SqlSemantics.ownerOf("u", ctx))
        assertEquals("t_order", SqlSemantics.ownerOf("O.user_id", ctx))
        assertEquals("sys_user", SqlSemantics.ownerOf("sys_user.id", ctx))
        assertNull(SqlSemantics.ownerOf("x.id", ctx))
        assertNull(SqlSemantics.ownerOf("", ctx))
    }

    // ------------------------------------------------------------------ MyBatis 标记清洗

    @Test
    fun `动态标签与 test 属性不污染表与列`() {
        val ctx = SqlSemantics.parse(
            "<select id=\"list\">SELECT u.id FROM sys_user u " +
                "<if test=\"name != null\">AND u.user_name LIKE #{name}</if>" +
                "<where>AND u.status = \${status}</where></select>"
        )
        assertEquals(listOf("sys_user"), ctx.tables.map { it.name })
        assertEquals("sys_user", ctx.mainTable)
        assertTrue(ctx.columnsByOwner["u"]?.containsAll(listOf("id", "user_name", "status")) == true)
        assertFalse(ctx.columnsByOwner.values.flatten().contains("test"))
        assertFalse(ctx.columnsByOwner.values.flatten().contains("select"))
    }

    @Test
    fun `占位符与 XML 注释里的表名不会被误收`() {
        val ctx = SqlSemantics.parse(
            "SELECT * FROM sys_user u WHERE u.id = #{id} AND u.name = \${nm} " +
                "<!-- FROM t_order o WHERE o.id = 1 --> AND u.status = 1"
        )
        assertEquals(1, ctx.tables.size)
        assertEquals("sys_user", ctx.mainTable)
        assertFalse(ctx.tables.any { it.name.contains("t_order") })
        assertTrue(ctx.columnsByOwner["u"]?.containsAll(listOf("id", "name", "status")) == true)
        assertFalse(ctx.columnsByOwner.values.flatten().contains("nm"))
    }

    // ------------------------------------------------------------------ 大小写 / 子查询 / 光标

    @Test
    fun `SQL 关键词大小写不敏感且别名查找不区分大小写`() {
        val ctx = SqlSemantics.parse(
            "select u.id From SYS_USER U left JOIN T_ORDER O on U.id = O.user_id"
        )
        assertEquals("SYS_USER", ctx.mainTable)
        assertEquals("T_ORDER", ctx.tables[1].name)
        assertEquals("SYS_USER", SqlSemantics.ownerOf("U.id", ctx))
        assertEquals("T_ORDER", SqlSemantics.ownerOf("o.user_id", ctx))
    }

    @Test
    fun `子查询里的表与别名也会被识别`() {
        val ctx = SqlSemantics.parse(
            "SELECT u.id FROM sys_user u WHERE u.id IN " +
                "(SELECT o.user_id FROM t_order o WHERE o.status = 1)"
        )
        assertEquals("sys_user", ctx.mainTable)
        assertTrue(ctx.tables.size >= 2)
        assertEquals("t_order", ctx.aliases["o"])
        assertTrue(ctx.columnsByOwner["o"]?.contains("user_id") == true)
    }

    @Test
    fun `caretOffset 只解析光标之前的文本`() {
        val sql = "SELECT u.name FROM sys_user u JOIN t_order o ON u.id = o.user_id"
        val ctx = SqlSemantics.parse(sql, sql.indexOf("JOIN"))
        assertEquals(1, ctx.tables.size)
        assertEquals("sys_user", ctx.mainTable)
        assertNull(ctx.aliases["o"])
        assertTrue(ctx.columnsByOwner["u"]?.contains("name") == true)
    }

    // ------------------------------------------------------------------ 防御

    @Test
    fun `超长输入直接返回空上下文`() {
        val huge = "SELECT * FROM sys_user u ".repeat(10000)
        assertTrue(huge.length > SqlSemantics.MAX_SQL_LENGTH)
        val ctx = SqlSemantics.parse(huge)
        assertEquals(SqlSemantics.SqlContext(), ctx)
        assertTrue(ctx.tables.isEmpty())
        assertNull(ctx.mainTable)
    }

    @Test
    fun `空串 空白串与 caret 为 0 都安全返回空上下文`() {
        assertEquals(SqlSemantics.SqlContext(), SqlSemantics.parse(""))
        assertEquals(SqlSemantics.SqlContext(), SqlSemantics.parse("   \n\t "))
        assertEquals(SqlSemantics.SqlContext(), SqlSemantics.parse("SELECT * FROM sys_user", 0))
        assertEquals(SqlSemantics.SqlContext(), SqlSemantics.parse("SELECT * FROM sys_user", -5))
        assertEquals("sys_user", SqlSemantics.parse("SELECT * FROM sys_user", 9999).mainTable)
    }

    @Test
    fun `非法标识符不抛异常且不会生成脏表`() {
        val ctx = SqlSemantics.parse("SELECT FROM ### \$\$\$ ... <<>> 1.2.3 FROM")
        assertTrue(ctx.tables.isEmpty())
        assertNull(ctx.mainTable)
        assertNull(SqlSemantics.ownerOf("###", ctx))
    }

    // ------------------------------------------------------------------ aliasPrefixAt

    @Test
    fun `aliasPrefixAt 识别别名点前缀与部分列名`() {
        val dot = "SELECT * FROM sys_user u WHERE u."
        assertEquals("u", SqlSemantics.aliasPrefixAt(dot, dot.length))
        val partial = "SELECT * FROM sys_user u WHERE u.na"
        assertEquals("u", SqlSemantics.aliasPrefixAt(partial, partial.length))
        val tableName = "SELECT * FROM sys_user WHERE sys_user.na"
        assertEquals("sys_user", SqlSemantics.aliasPrefixAt(tableName, tableName.length))
    }

    @Test
    fun `aliasPrefixAt 在完整表达式 占位符内与空输入时返回 null`() {
        val done = "SELECT * FROM sys_user u WHERE u.name = 1"
        assertNull(SqlSemantics.aliasPrefixAt(done, done.length))
        assertNull(SqlSemantics.aliasPrefixAt("SELECT * FROM sys_user", 22))
        val inHash = "SELECT * FROM sys_user u WHERE u.name = #{u."
        assertNull(SqlSemantics.aliasPrefixAt(inHash, inHash.length))
        val inDollar = "SELECT * FROM sys_user u WHERE u.name = \${u."
        assertNull(SqlSemantics.aliasPrefixAt(inDollar, inDollar.length))
        assertNull(SqlSemantics.aliasPrefixAt("", 0))
    }
}
