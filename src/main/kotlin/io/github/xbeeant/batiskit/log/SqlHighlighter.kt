package io.github.xbeeant.batiskit.log

/**
 * 还原后 SQL 的轻量着色分词器（纯逻辑，不碰平台 API，便于单测）。
 *
 * 目标是「一眼能看出关键字、参数值、注释和还没替换上的 `?`」，不是完整 SQL 语法解析：
 *  - 字符串、引号标识符、行注释、块注释整体成一个 token，里面的关键字不会被误着；
 *  - token 按顺序覆盖全文且互不重叠，调用方可以直接按区间刷字符属性。
 */
object SqlHighlighter {

    enum class Kind { KEYWORD, STRING, NUMBER, COMMENT, PLACEHOLDER }

    data class Token(val start: Int, val end: Int, val kind: Kind) {
        val length: Int get() = end - start
    }

    /** 常见 SQL 关键字；刻意不含 select/from 之外的框架私有词，避免把表名刷成蓝色 */
    private val KEYWORDS = setOf(
        "SELECT", "FROM", "WHERE", "AND", "OR", "NOT", "IN", "IS", "NULL", "LIKE", "BETWEEN", "EXISTS",
        "INSERT", "INTO", "VALUES", "UPDATE", "SET", "DELETE", "REPLACE", "MERGE", "UPSERT",
        "JOIN", "LEFT", "RIGHT", "INNER", "OUTER", "FULL", "CROSS", "NATURAL", "ON", "USING",
        "GROUP", "ORDER", "BY", "HAVING", "LIMIT", "OFFSET", "FETCH", "FIRST", "NEXT", "ROWS", "ONLY",
        "UNION", "ALL", "EXCEPT", "INTERSECT", "DISTINCT", "AS", "ASC", "DESC",
        "CASE", "WHEN", "THEN", "ELSE", "END", "WITH", "RECURSIVE", "LATERAL",
        "COUNT", "SUM", "AVG", "MIN", "MAX", "COALESCE", "CAST", "ROUND", "CONCAT", "IFNULL", "NVL",
        "EXPLAIN", "CREATE", "ALTER", "DROP", "TABLE", "INDEX", "PRIMARY", "KEY", "DEFAULT", "TRUNCATE",
        "TRUE", "FALSE", "INTERVAL", "OVER", "PARTITION", "DUAL", "RETURNING",
    )

    /** 返回需要着色的区间；未命中的字符保持默认前景色 */
    fun tokenize(sql: String): List<Token> {
        val tokens = mutableListOf<Token>()
        var i = 0
        while (i < sql.length) {
            val c = sql[i]
            when {
                c == '-' && i + 1 < sql.length && sql[i + 1] == '-' -> {
                    var end = sql.indexOf('\n', i)
                    if (end < 0) end = sql.length
                    tokens += Token(i, end, Kind.COMMENT)
                    i = end
                }

                c == '/' && i + 1 < sql.length && sql[i + 1] == '*' -> {
                    val close = sql.indexOf("*/", i + 2)
                    val end = if (close < 0) sql.length else close + 2
                    tokens += Token(i, end, Kind.COMMENT)
                    i = end
                }

                c == '\'' || c == '"' || c == '`' -> {
                    val end = endOfQuoted(sql, i, c)
                    tokens += Token(i, end, Kind.STRING)
                    i = end
                }

                c == '?' -> {
                    tokens += Token(i, i + 1, Kind.PLACEHOLDER)
                    i++
                }

                c.isDigit() -> {
                    var end = i + 1
                    while (end < sql.length && (sql[end].isDigit() || sql[end] == '.')) {
                        end++
                    }
                    tokens += Token(i, end, Kind.NUMBER)
                    i = end
                }

                c.isLetter() || c == '_' -> {
                    var end = i
                    while (end < sql.length && (sql[end].isLetterOrDigit() || sql[end] == '_')) {
                        end++
                    }
                    val word = sql.substring(i, end).uppercase()
                    if (word in KEYWORDS) {
                        tokens += Token(i, end, Kind.KEYWORD)
                    }
                    i = end
                }

                else -> i++
            }
        }
        return tokens
    }

    private fun endOfQuoted(sql: String, start: Int, quote: Char): Int {
        var i = start + 1
        while (i < sql.length) {
            if (sql[i] == quote) {
                // '' 是 SQL 里的转义引号，不是结束
                if (i + 1 < sql.length && sql[i + 1] == quote) {
                    i += 2
                    continue
                }
                return i + 1
            }
            i++
        }
        return sql.length
    }
}
