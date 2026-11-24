package io.github.xbeeant.batiskit.meta

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** MapperSqlRules 纯逻辑测试：不启动 IDE，覆盖前缀规则 / 表名推断 / CRUD SQL / SQL 上下文 */
class MapperSqlRulesTest {

    private val columns = listOf(
        MapperSqlRules.Column("id", "BIGINT", "主键", primaryKey = true),
        MapperSqlRules.Column("user_name", "VARCHAR", "用户名"),
        MapperSqlRules.Column("created_at", "TIMESTAMP", "创建时间")
    )

    // ------------------------------------------------------------------ 前缀规则

    @Test
    fun `默认前缀表解析出四种语句类型`() {
        val rules = MapperSqlRules.parsePrefixRules(MapperSqlRules.DEFAULT_PREFIX_RULES)
        assertEquals(setOf("select", "insert", "update", "delete"), rules.map { it.kind }.toSet())
        assertTrue(rules.any { it.prefix == "get" && it.kind == "select" })
        assertTrue(rules.any { it.prefix == "batch" && it.kind == "insert" })
    }

    @Test
    fun `前缀规则支持冒号 注释 与非法行丢弃`() {
        val rules = MapperSqlRules.parsePrefixRules(
            "# 说明\nfind,search:select\ncreate=insert\nfoo=unknown\n=delete\n  \nremove = delete"
        )
        assertEquals("select", MapperSqlRules.kindOf("findUser", rules))
        assertEquals("insert", MapperSqlRules.kindOf("createOrder", rules))
        assertEquals("delete", MapperSqlRules.kindOf("removeById", rules))
        assertFalse(rules.any { it.prefix == "foo" })
    }

    @Test
    fun `规范化会丢掉非法行并回落到默认前缀表`() {
        assertEquals(MapperSqlRules.DEFAULT_PREFIX_RULES, MapperSqlRules.normalizePrefixRules("bad line"))
        assertEquals(MapperSqlRules.DEFAULT_PREFIX_RULES, MapperSqlRules.normalizePrefixRules("  "))
        val normalized = MapperSqlRules.normalizePrefixRules("find:select\nsave=insert # 尾注释")
        assertTrue(normalized.contains("find=select"))
        assertTrue(normalized.contains("save=insert"))
    }

    @Test
    fun `默认分类与旧版 statementKind 一致`() {
        assertEquals("delete", MapperSqlRules.kindOf("deleteById"))
        assertEquals("delete", MapperSqlRules.kindOf("removeUser"))
        assertEquals("update", MapperSqlRules.kindOf("updateStatus"))
        assertEquals("update", MapperSqlRules.kindOf("enableAccount"))
        assertEquals("insert", MapperSqlRules.kindOf("insertBatch"))
        assertEquals("insert", MapperSqlRules.kindOf("saveOrUpdate"))
        assertEquals("select", MapperSqlRules.kindOf("getUserById"))
        assertEquals("select", MapperSqlRules.kindOf("whatever"))
    }

    @Test
    fun `自定义前缀规则可以覆盖默认分类且长前缀优先`() {
        val rules = MapperSqlRules.parsePrefixRules("get=delete\ngetUser=insert")
        assertEquals("insert", MapperSqlRules.kindOf("getUserById", rules))
        assertEquals("delete", MapperSqlRules.kindOf("getOrder", rules))
    }

    // ------------------------------------------------------------------ 命名 / 映射

    @Test
    fun `驼峰转下划线处理缩写与数字边界`() {
        assertEquals("user_name", MapperSqlRules.toSnakeCase("userName"))
        assertEquals("http_server", MapperSqlRules.toSnakeCase("HTTPServer"))
        assertEquals("user2_name", MapperSqlRules.toSnakeCase("User2Name"))
        assertEquals("user_name", MapperSqlRules.toSnakeCase("user-name"))
    }

    @Test
    fun `下划线转驼峰`() {
        assertEquals("userName", MapperSqlRules.toCamelCase("user_name"))
        assertEquals("id", MapperSqlRules.toCamelCase("ID"))
        assertEquals("createdAt", MapperSqlRules.toCamelCase("created_at"))
    }

    @Test
    fun `标识符清洗去掉 schema 反引号与非法字符`() {
        assertEquals("sys_user", MapperSqlRules.sanitizeIdentifier("`public`.`sys_user`"))
        assertEquals("user", MapperSqlRules.sanitizeIdentifier("\"user\""))
        assertEquals("user", MapperSqlRules.sanitizeIdentifier("1user"))
        assertEquals("", MapperSqlRules.sanitizeIdentifier("###"))
    }

    @Test
    fun `表名推断去掉 Mapper 后缀并支持 TableName 注解优先`() {
        assertEquals("sys_user", MapperSqlRules.inferTableName("SysUserMapper"))
        assertEquals("user", MapperSqlRules.inferTableName("UserDao"))
        assertEquals("t_order", MapperSqlRules.inferTableName("OrderMapper", "\"t_order\""))
        assertEquals("user_info", MapperSqlRules.inferTableName("Whatever", null, "UserInfo"))
    }

    @Test
    fun `映射解析支持注释与两种分隔符`() {
        val mappings = MapperSqlRules.parseMappings(
            "# 手动映射\ncom.e.UserMapper=sys_user\nOtherMapper : t_order\nbad line\n=empty"
        )
        assertEquals(2, mappings.size)
        assertEquals("sys_user", mappings["com.e.UserMapper"])
        assertEquals("t_order", mappings["OtherMapper"])
    }

    @Test
    fun `映射查找按全限定 简单名 文件名依次回落`() {
        val mappings = mapOf("UserMapper" to "sys_user", "Special.xml" to "t_log")
        assertEquals("sys_user", MapperSqlRules.mappingFor("com.e.UserMapper", "UserMapper.xml", mappings))
        assertEquals("t_log", MapperSqlRules.mappingFor("com.e.Other", "Special.xml", mappings))
        assertNull(MapperSqlRules.mappingFor("com.e.Unknown", "X.xml", emptyMap()))
    }

    @Test
    fun `记忆列表去重 限量且空值不入列`() {
        val result = MapperSqlRules.remember(listOf("user", "order"), "USER", 3)
        assertEquals(listOf("USER", "order"), result)
        assertEquals(2, MapperSqlRules.remember(result, "", 3).size)
        assertEquals(listOf("a", "b"), MapperSqlRules.remember(listOf("a", "b", "c"), "a", 2))
    }

    // ------------------------------------------------------------------ 数据源表名匹配

    @Test
    fun `表名匹配精确与忽略大小写`() {
        assertEquals(100, MapperSqlRules.tableMatchScore("sys_user", "sys_user"))
        assertEquals(95, MapperSqlRules.tableMatchScore("SYS_USER", "sys_user"))
        assertEquals(-1, MapperSqlRules.tableMatchScore("sys_user", ""))
        assertEquals(-1, MapperSqlRules.tableMatchScore("", "sys_user"))
    }

    @Test
    fun `表名匹配去掉 t tb tbl 前缀`() {
        assertEquals(70, MapperSqlRules.tableMatchScore("t_user", "user"))
        assertEquals(70, MapperSqlRules.tableMatchScore("tbl_order", "order"))
        assertEquals(70, MapperSqlRules.tableMatchScore("user", "tb_user"))
    }

    @Test
    fun `表名匹配兼容业务前缀后缀 让空 XML 也能按 namespace 找到表`() {
        assertEquals(40, MapperSqlRules.tableMatchScore("sys_user", "user"))
        assertEquals(35, MapperSqlRules.tableMatchScore("user", "sys_user"))
        assertEquals(100, MapperSqlRules.tableMatchScore("`public`.`sys_user`", "`public`.`sys_user`"))
    }

    @Test
    fun `表名匹配拒绝任意子串与短名误命中`() {
        assertEquals(-1, MapperSqlRules.tableMatchScore("user_log", "user"))
        assertEquals(-1, MapperSqlRules.tableMatchScore("order_item", "item_id"))
        assertEquals(-1, MapperSqlRules.tableMatchScore("t_log", "id"))
        assertEquals(-1, MapperSqlRules.tableMatchScore("account", "user"))
    }

    @Test
    fun `表名匹配分数严格递减 高分不会被低分覆盖`() {
        val exact = MapperSqlRules.tableMatchScore("user", "user")
        val case = MapperSqlRules.tableMatchScore("USER", "user")
        val prefixed = MapperSqlRules.tableMatchScore("t_user", "user")
        val suffixed = MapperSqlRules.tableMatchScore("sys_user", "user")
        assertTrue(exact > case && case > prefixed && prefixed > suffixed && suffixed > 0)
    }

    // ------------------------------------------------------------------ CRUD SQL

    @Test
    fun `select 预填列清单与主键条件`() {
        val sql = MapperSqlRules.sqlFor("select", "sys_user", columns)
        assertTrue(sql.startsWith("SELECT\n    id,\n    user_name"))
        assertTrue(sql.contains("FROM sys_user"))
        assertTrue(sql.contains("WHERE id = #{id}"))
    }

    @Test
    fun `insert 的列与参数名一一对应`() {
        val sql = MapperSqlRules.sqlFor("insert", "sys_user", columns)
        assertTrue(sql.contains("INSERT INTO sys_user ("))
        assertTrue(sql.contains("    user_name,"))
        assertTrue(sql.contains("    #{userName},"))
        assertTrue(sql.contains("#{createdAt}"))
    }

    @Test
    fun `update 排除主键并保留 WHERE`() {
        val sql = MapperSqlRules.sqlFor("update", "sys_user", columns)
        assertTrue(sql.startsWith("UPDATE sys_user\nSET\n"))
        assertTrue(sql.contains("user_name = #{userName}"))
        assertFalse(sql.contains("    id = #{id}"))
        assertTrue(sql.contains("WHERE id = #{id}"))
    }

    @Test
    fun `delete 生成带主键条件的语句`() {
        val sql = MapperSqlRules.sqlFor("delete", "sys_user", columns)
        assertEquals("DELETE FROM sys_user\nWHERE id = #{id}", sql)
    }

    @Test
    fun `没有列时退化成可继续编辑的骨架`() {
        assertTrue(MapperSqlRules.sqlFor("select", "user", emptyList()).contains("SELECT\n    *"))
        assertTrue(MapperSqlRules.sqlFor("insert", "user", emptyList()).contains("TODO"))
        assertTrue(MapperSqlRules.sqlFor("delete", "user", emptyList()).contains("TODO"))
        assertEquals("", MapperSqlRules.sqlFor("select", "", columns))
    }

    @Test
    fun `生成的 SQL 不含裸尖括号与和号 可安全放进 XML 文本`() {
        MapperSqlRules.KINDS.forEach { kind ->
            val sql = MapperSqlRules.sqlFor(kind, "sys_user", columns)
            assertFalse("$kind 含 <", sql.contains('<'))
            assertFalse("$kind 含 &", sql.contains('&'))
        }
    }

    // ------------------------------------------------------------------ SQL 上下文

    @Test
    fun `parseSql 提取动词与表名并去掉 XML 标签与占位符`() {
        val context = MapperSqlRules.parseSql(
            "<select>SELECT id, user_name FROM sys_user WHERE id = #{id} <if test=\"x\">AND user_name LIKE #{userName}</if></select>"
        )
        assertEquals("select", context.verb)
        assertEquals(listOf("sys_user"), context.tables)
        assertTrue(context.columns.contains("user_name"))
        assertFalse(context.columns.contains("select"))
        assertFalse(context.columns.contains("sys_user"))
    }

    @Test
    fun `parseSql 识别 insert into 与 update 表名`() {
        assertEquals(listOf("t_order"), MapperSqlRules.parseSql("INSERT INTO t_order (id) VALUES (?)").tables)
        assertEquals(listOf("t_user"), MapperSqlRules.parseSql("UPDATE t_user SET name = ?").tables)
    }

    @Test
    fun `光标在赋值占位符里时把对应列属性名排第一`() {
        val hints = MapperSqlRules.paramHints(
            "SELECT * FROM sys_user WHERE user_name = #{",
            columns
        )
        assertEquals("userName", hints.first())
        assertTrue(hints.contains("user_name"))
    }

    @Test
    fun `上下文候选把 SQL 里没出现过的列排在前面`() {
        val hints = MapperSqlRules.paramHints(
            "UPDATE sys_user SET user_name = #{userName} WHERE id = #{",
            columns
        )
        assertEquals("id", hints.first())
        assertTrue(hints.contains("createdAt"))
        assertTrue(hints.indexOf("createdAt") < hints.indexOf("userName"))
    }

    @Test
    fun `列排序把 SQL 已出现列放后面 主键其次`() {
        val context = MapperSqlRules.parseSql("SELECT id FROM user")
        val ordered = MapperSqlRules.columnOrder(columns, context)
        assertFalse(ordered.first().name == "id")
    }

    // ------------------------------------------------------------------ 语句标签

    @Test
    fun `statementXml 缩进 SQL 并带上 resultType`() {
        val xml = MapperSqlRules.statementXml("select", "getById", "com.e.User", "SELECT 1\nFROM user")
        assertTrue(xml.startsWith("<select id=\"getById\" resultType=\"com.e.User\">\n"))
        assertTrue(xml.contains("    SELECT 1\n    FROM user"))
        assertTrue(xml.endsWith("\n</select>"))
    }

    @Test
    fun `statementXml 无 SQL 时保留 TODO 骨架并支持附加提醒`() {
        val xml = MapperSqlRules.statementXml("select", "find", null, null, "补充 resultType")
        assertTrue(xml.contains("<!-- TODO: 补充 SQL -->"))
        assertTrue(xml.contains("<!-- TODO: 补充 resultType -->"))
        assertFalse(xml.contains("resultType="))
    }

    @Test
    fun `statementXml 转义属性里的特殊字符`() {
        val xml = MapperSqlRules.statementXml("select", "a\"b", "A&B", null)
        assertTrue(xml.contains("id=\"a&quot;b\""))
        assertTrue(xml.contains("resultType=\"A&amp;B\""))
    }
}
