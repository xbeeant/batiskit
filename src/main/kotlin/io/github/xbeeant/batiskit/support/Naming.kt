package io.github.xbeeant.batiskit.support

import io.github.xbeeant.batiskit.config.DateKind
import io.github.xbeeant.batiskit.model.JavaType

/** 命名规则：表名 -> 类名，列名 -> 字段名 */
object Naming {

    private val JAVA_KEYWORDS = setOf(
        "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class", "const",
        "continue", "default", "do", "double", "else", "enum", "extends", "final", "finally", "float",
        "for", "goto", "if", "implements", "import", "instanceof", "int", "interface", "long", "native",
        "new", "package", "private", "protected", "public", "return", "short", "static", "strictfp",
        "super", "switch", "synchronized", "this", "throw", "throws", "transient", "try", "void",
        "volatile", "while", "true", "false", "null", "var", "record", "yield"
    )

    /** t_user / sys_user_info -> User / UserInfo */
    fun entityName(tableName: String, prefixes: String, suffix: String): String {
        val stripped = stripPrefixes(tableName, prefixes)
        val base = pascal(stripped).ifEmpty { pascal(tableName) }.ifEmpty { "Unknown" }
        return base + suffix
    }

    /**
     * 实体名重命名规则（对应原插件 Search String / Replace String，MBG DomainObjectRenamingRule）：
     * searchString 非空时，把表名中的 searchString 全部替换为 replaceString
     */
    fun applyRename(tableName: String, searchString: String, replaceString: String): String =
        if (searchString.isBlank()) tableName else tableName.replace(searchString, replaceString)

    /** create_time -> createTime */
    fun fieldName(columnName: String): String {
        val camel = camel(columnName).ifEmpty { "field" }
        val safe = if (camel.first().isDigit()) "f$camel" else camel
        return if (safe in JAVA_KEYWORDS) safe + "Value" else safe
    }

    fun pascal(raw: String): String = words(raw).joinToString("") { w ->
        if (w.isEmpty()) "" else w.replaceFirstChar { it.uppercaseChar() }
    }

    /** AuditLog -> audit-log（Rest 路径用） */
    fun kebab(raw: String): String = words(raw).joinToString("-") { it.lowercase() }

    fun camel(raw: String): String {
        val p = pascal(raw)
        return if (p.isEmpty()) p else p.replaceFirstChar { it.lowercaseChar() }
    }

    /** 去掉配置里命中的最长前缀后的原始表名：t_access_log -> access_log（模板占位符 {tableName} 用） */
    fun baseTableName(tableName: String, prefixes: String): String = stripPrefixes(tableName, prefixes)

    /** 去掉配置里命中的最长前缀（忽略大小写） */
    private fun stripPrefixes(tableName: String, prefixes: String): String {
        val lower = tableName.lowercase()
        val hit = prefixes.split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .sortedByDescending { it.length }
            .firstOrNull { lower.startsWith(it.lowercase()) }
        return if (hit != null) tableName.substring(hit.length) else tableName
    }

    private fun words(raw: String): List<String> =
        raw.split('_', '-', ' ', '.', '/', ':', '\\')
            .flatMap { splitByCase(it) }
            .filter { it.isNotBlank() }

    /** 拆 studentNO / userName 这类大小写混排的单词 */
    private fun splitByCase(token: String): List<String> {
        if (token.isEmpty()) {
            return emptyList()
        }
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        for ((i, ch) in token.withIndex()) {
            if (i > 0 && ch.isUpperCase() && token[i - 1].isLowerCase()) {
                out += sb.toString()
                sb.setLength(0)
            }
            if (ch.isLetterOrDigit()) {
                sb.append(ch)
            }
        }
        if (sb.isNotEmpty()) {
            out += sb.toString()
        }
        return out
    }
}

/** 数据库类型 -> Java 类型 */
object TypeMapping {

    fun map(dbType: String, size: Int, dateKind: DateKind): JavaType {
        // 归一化：去精度、去 signed/unsigned 修饰（bigint unsigned -> bigint），否则会落到默认 VARCHAR
        val t = dbType.lowercase()
            .substringBefore('(')
            .trim()
            .removeSuffix(" unsigned")
            .removeSuffix(" signed")
            .trim()
        return when (t) {
            // tinyint 一律映射 Boolean（状态/开关列的常规约定；size 读不到时也不落 Integer）；
            // 需要数值语义的列在表注释里用 #handler:…# 挂自定义 TypeHandler
            "tinyint" -> JavaType("Boolean", null, "BOOLEAN")
            "bit" -> if (size == 1) JavaType("Boolean", null, "BIT") else JavaType("byte[]", null, "VARBINARY")
            "smallint", "mediumint", "int", "integer", "year", "serial" ->
                JavaType("Integer", null, "INTEGER")

            "bigint", "bigserial" -> JavaType("Long", null, "BIGINT")
            "decimal", "numeric", "dec", "number", "money", "smallmoney" ->
                JavaType("BigDecimal", "java.math.BigDecimal", "DECIMAL")

            "float" -> JavaType("Float", null, "REAL")
            "double", "double precision", "real" -> JavaType("Double", null, "DOUBLE")
            "char", "character", "nchar" -> JavaType("String", null, "CHAR")
            "varchar", "varchar2", "nvarchar", "nvarchar2", "character varying" ->
                JavaType("String", null, "VARCHAR")

            "text", "tinytext", "mediumtext", "longtext", "clob", "nclob", "longvarchar", "json",
            "jsonb", "enum", "set", "uuid", "uniqueidentifier", "xml", "inet", "citext" ->
                JavaType("String", null, "LONGVARCHAR")

            "date" -> if (dateKind == DateKind.JAVA_UTIL_DATE) {
                JavaType("Date", "java.util.Date", "DATE")
            } else {
                JavaType("LocalDate", "java.time.LocalDate", "DATE")
            }

            "datetime", "timestamp", "timestamp without time zone", "timestamp with time zone",
            "smalldatetime", "datetime2", "timestamptz" -> if (dateKind == DateKind.JAVA_UTIL_DATE) {
                JavaType("Date", "java.util.Date", "TIMESTAMP")
            } else {
                JavaType("LocalDateTime", "java.time.LocalDateTime", "TIMESTAMP")
            }

            "time", "timetz" -> if (dateKind == DateKind.JAVA_UTIL_DATE) {
                JavaType("Date", "java.util.Date", "TIME")
            } else {
                JavaType("LocalTime", "java.time.LocalTime", "TIME")
            }

            "blob", "tinyblob", "mediumblob", "longblob", "binary", "varbinary", "image", "bytea",
            "raw", "longvarbinary" -> JavaType("byte[]", null, "VARBINARY")

            "bool", "boolean" -> JavaType("Boolean", null, "BOOLEAN")
            else -> JavaType("String", null, "VARCHAR")
        }
    }
}
