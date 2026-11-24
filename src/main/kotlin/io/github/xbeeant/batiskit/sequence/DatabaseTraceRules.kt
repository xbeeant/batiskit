package io.github.xbeeant.batiskit.sequence

import io.github.xbeeant.batiskit.meta.MapperSqlRules

/**
 * 时序图「追踪到数据库层」的纯逻辑规则：零 IntelliJ 平台依赖，可直接单测。
 *
 * 只做四件事：把 Mapper XML 标签 / SQL 注解 / 方法前缀归一成 CRUD 动词、从 SQL 文本里取表名、
 * 把带动态标签的 SQL 压成一行短标签、给数据库表参与者生成不与 Java 类 FQN 冲突的身份键。
 * 平台侧的解析（PSI / XML 索引 / Database 视图）在 [DatabaseTrace] 里，两者刻意分开。
 */
object DatabaseTraceRules {

    /** 工程里识别到的持久层框架；NONE 时只有 Mapper XML / SQL 注解这类显式证据才画数据库层 */
    enum class Framework { NONE, MYBATIS, MYBATIS_PLUS }

    const val MAX_TABLES = 3
    const val MAX_SQL_CHARS = 60

    private val VERBS = mapOf(
        "select" to "SELECT",
        "insert" to "INSERT",
        "update" to "UPDATE",
        "delete" to "DELETE",
    )
    private val SQL_VERBS = setOf("SELECT", "INSERT", "UPDATE", "DELETE")
    private val FIRST_WORD = Regex("""^\s*([A-Za-z]+)""")
    private val WHITESPACE = Regex("""\s+""")

    /**
     * 动词优先级：XML 语句标签 > SQL 注解 > SQL 正文首词 > 方法名前缀。
     * 方法名前缀复用 [MapperSqlRules.kindOf] 的默认 CRUD 前缀表，避免两套规则漂移。
     */
    fun verbOf(tag: String? = null, annotation: String? = null, methodName: String = "", sql: String? = null): String {
        val fromTag = tag?.trim()?.lowercase()?.let { VERBS[it] }
        if (fromTag != null) return fromTag
        val fromAnnotation = annotation?.substringAfterLast('.')?.removeSuffix("Provider")?.lowercase()
            ?.let { VERBS[it] }
        if (fromAnnotation != null) return fromAnnotation
        if (!sql.isNullOrBlank()) {
            val fromSql = FIRST_WORD.find(MapperSqlRules.stripMarkup(sql))?.groupValues?.get(1)
                ?.uppercase()?.takeIf { it in SQL_VERBS }
            if (fromSql != null) return fromSql
        }
        return MapperSqlRules.kindOf(methodName).uppercase()
    }

    /** 一条语句涉及的表（`FROM / JOIN / INTO / UPDATE`），去重后最多 3 张 */
    fun tablesOf(sql: String?): List<String> {
        if (sql.isNullOrBlank()) return emptyList()
        return MapperSqlRules.parseSql(sql).tables
            .map { MapperSqlRules.sanitizeIdentifier(it) }
            .filter { it.isNotEmpty() }
            .distinct()
            .take(MAX_TABLES)
    }

    /** 剥掉 MyBatis 动态标签 / 占位符 / 注释，压成单行并截断 */
    fun compactSql(raw: String?, max: Int = MAX_SQL_CHARS): String {
        if (raw.isNullOrBlank() || max <= 1) return ""
        return truncate(WHITESPACE.replace(MapperSqlRules.stripMarkup(raw), " ").trim(), max)
    }

    /**
     * SQL 箭头标签：有真实 SQL 就用压缩后的原文；MyBatis-Plus 的通用 CRUD 没有 SQL 文本，
     * 按动词 + 表名合成一条等价描述并标注来源。
     */
    fun label(verb: String, sql: String?, table: String, framework: Framework,
              max: Int = MAX_SQL_CHARS): String {
        val compact = compactSql(sql, max)
        if (compact.isNotEmpty()) return compact
        val synthetic = when (verb.uppercase()) {
            "INSERT" -> "INSERT INTO $table"
            "UPDATE" -> "UPDATE $table SET ..."
            "DELETE" -> "DELETE FROM $table"
            else -> "SELECT * FROM $table"
        }
        val suffix = if (framework == Framework.MYBATIS_PLUS) " (MyBatis-Plus)" else ""
        return truncate(synthetic + suffix, max)
    }

    /**
     * 文本导出用的完整 SQL 标签：同样剥掉动态标签 / 压成一行，但**不截断**。
     * `max = Int.MAX_VALUE` 时 [truncate] 原样返回，长 SQL 在 Mermaid / PlantUML 里得以保留。
     */
    fun fullLabel(verb: String, sql: String?, table: String, framework: Framework): String =
        label(verb, sql, table, framework, Int.MAX_VALUE)

    /** 数据库表参与者的身份键：`database:` 前缀保证不会与 Java 类全限定名撞车 */
    fun participantKey(table: String): String = "database:$table"

    fun displayOf(table: String): String = "${truncate(table, 22)} (DB)"

    /** 文本导出里的表名不加省略号，长表名在图里缩写即可 */
    fun fullDisplayOf(table: String): String = "$table (DB)"

    fun truncate(value: String, max: Int): String =
        if (value.length <= max) value else value.take(max - 1) + "…"
}
