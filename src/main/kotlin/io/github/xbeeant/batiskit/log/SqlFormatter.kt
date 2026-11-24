package io.github.xbeeant.batiskit.log

/**
 * 轻量 SQL 排版：按关键字换行，`AND` / `OR` 缩进一级。
 *
 * 只为了「预览 / 复制时好看一点」，不追求 AST 级正确：
 *  - 先切成 token（字符串、引号标识符、注释整体保留），再把 token 之间的空白重新编排，
 *    所以 `'a  b'` 里的空格、`--` 注释都不会被改写；
 *  - 字符串与注释里的关键字不会被当成换行点。
 */
object SqlFormatter {

    private enum class Kind { WORD, LITERAL, SYMBOL }

    private class Token(val kind: Kind, val text: String)

    private val NEWLINE_KEYWORDS = setOf(
        "SELECT", "FROM", "WHERE", "HAVING", "LIMIT", "OFFSET",
        "UNION", "VALUES", "SET", "RETURNING", "JOIN", "ON"
    )

    /** 两词组合：`GROUP BY` / `LEFT JOIN` / `INSERT INTO` 这类要在第一个词前换行 */
    private val PAIRED_PREFIX = mapOf(
        "GROUP" to "BY",
        "ORDER" to "BY",
        "INSERT" to "INTO",
        "DELETE" to "FROM",
        "LEFT" to "JOIN",
        "RIGHT" to "JOIN",
        "INNER" to "JOIN",
        "OUTER" to "JOIN",
        "CROSS" to "JOIN",
        "FULL" to "JOIN"
    )

    fun format(sql: String): String = format(sql, simple = false, newLineWithComment = false)

    /**
     * [simple] 只在主要关键字前换行、不缩进 AND / OR；
     * [newLineWithComment] 让 SQL 里的 `--` 注释另起一行，方便直接粘贴到客户端执行。
     */
    fun format(sql: String, simple: Boolean, newLineWithComment: Boolean): String {
        if (sql.isBlank()) {
            return sql
        }
        val tokens = tokenize(sql)
        val out = StringBuilder(sql.length + tokens.size / 2)
        tokens.forEachIndexed { index, token ->
            val word = if (token.kind == Kind.WORD) token.text.uppercase() else ""
            when {
                // AND / OR 缩进一级，条件多的时候比一整行好读
                !simple && (word == "AND" || word == "OR") -> {
                    if (out.isNotEmpty()) {
                        breakLine(out)
                        out.append(INDENT)
                    }
                    out.append(token.text).append(' ')
                }

                token.kind == Kind.WORD && startsLine(tokens, index, simple) -> {
                    breakLine(out)
                    out.append(token.text).append(' ')
                }

                token.text == "," -> {
                    trimTrailingSpace(out)
                    out.append(", ")
                }

                token.text == "(" -> {
                    trimTrailingSpace(out)
                    out.append('(')
                }

                token.text == ")" -> {
                    trimTrailingSpace(out)
                    out.append(')')
                }

                token.text == ";" -> {
                    trimTrailingSpace(out)
                    out.append(";\n")
                }

                token.kind == Kind.LITERAL -> {
                    out.append(token.text).append(' ')
                }

                else -> {
                    out.append(token.text).append(' ')
                }
            }
        }
        val formatted = out.toString().trim()
        return if (newLineWithComment) breakBeforeLineComments(formatted) else formatted
    }

    // ------------------------------------------------------------------ 内部

    private const val INDENT = "  "

    /** 简单模式下只保留最粗的骨架换行点 */
    private val SIMPLE_NEWLINE_KEYWORDS = setOf(
        "SELECT", "FROM", "WHERE", "GROUP", "ORDER", "HAVING", "LIMIT",
        "UNION", "VALUES", "SET", "RETURNING", "JOIN", "INSERT", "DELETE"
    )

    private fun startsLine(tokens: List<Token>, index: Int, simple: Boolean): Boolean {
        val word = tokens[index].text.uppercase()
        if (simple) {
            return word in SIMPLE_NEWLINE_KEYWORDS
        }
        // LEFT + JOIN / GROUP + BY：第一个词已经换过行，第二个词跟着留在同一行
        val previous = tokens.getOrNull(index - 1)
        if (previous?.kind == Kind.WORD && PAIRED_PREFIX[previous.text.uppercase()] == word) {
            return false
        }
        if (word in NEWLINE_KEYWORDS) {
            return true
        }
        val second = PAIRED_PREFIX[word] ?: return false
        return nextWord(tokens, index) == second
    }

    private fun nextWord(tokens: List<Token>, index: Int): String? {
        val next = tokens.getOrNull(index + 1) ?: return null
        return if (next.kind == Kind.WORD) next.text.uppercase() else null
    }

    private fun breakLine(out: StringBuilder) {
        if (out.isEmpty()) {
            return
        }
        trimTrailingSpace(out)
        if (out.isNotEmpty() && out.last() != '\n') {
            out.append('\n')
        }
    }

    private fun trimTrailingSpace(out: StringBuilder) {
        while (out.isNotEmpty() && out.last() == ' ') {
            out.setLength(out.length - 1)
        }
    }

    /** 行内出现的 `--` 注释挪到下一行；字符串里的 `--` 不动 */
    private fun breakBeforeLineComments(sql: String): String {
        val out = StringBuilder(sql.length + 8)
        var i = 0
        var quote: Char? = null
        var lineHasContent = false
        while (i < sql.length) {
            val c = sql[i]
            if (quote != null) {
                out.append(c)
                if (c == quote) {
                    if (i + 1 < sql.length && sql[i + 1] == quote) {
                        out.append(sql[i + 1])
                        i += 2
                        continue
                    }
                    quote = null
                }
                i++
                continue
            }
            when {
                c == '\'' || c == '"' || c == '`' -> {
                    quote = c
                    out.append(c)
                    lineHasContent = true
                    i++
                }

                c == '\n' -> {
                    out.append(c)
                    lineHasContent = false
                    i++
                }

                c == '-' && i + 1 < sql.length && sql[i + 1] == '-' -> {
                    if (lineHasContent) {
                        trimTrailingSpace(out)
                        out.append('\n')
                        lineHasContent = false
                    }
                    out.append("--")
                    i += 2
                    while (i < sql.length && sql[i] != '\n') {
                        out.append(sql[i])
                        i++
                    }
                }

                else -> {
                    out.append(c)
                    if (!c.isWhitespace()) {
                        lineHasContent = true
                    }
                    i++
                }
            }
        }
        return out.toString()
    }

    private fun tokenize(sql: String): List<Token> {
        val tokens = mutableListOf<Token>()
        var i = 0
        while (i < sql.length) {
            val c = sql[i]
            when {
                c.isWhitespace() -> i++

                c == '\'' || c == '"' || c == '`' -> {
                    val end = endOfQuoted(sql, i, c)
                    tokens += Token(Kind.LITERAL, sql.substring(i, end))
                    i = end
                }

                c == '-' && i + 1 < sql.length && sql[i + 1] == '-' -> {
                    var end = sql.indexOf('\n', i)
                    if (end < 0) {
                        end = sql.length
                    }
                    tokens += Token(Kind.LITERAL, sql.substring(i, end))
                    i = end
                }

                c == '/' && i + 1 < sql.length && sql[i + 1] == '*' -> {
                    val close = sql.indexOf("*/", i + 2)
                    val end = if (close < 0) sql.length else close + 2
                    tokens += Token(Kind.LITERAL, sql.substring(i, end))
                    i = end
                }

                isWordChar(c) -> {
                    var end = i
                    while (end < sql.length && isWordChar(sql[end])) {
                        end++
                    }
                    tokens += Token(Kind.WORD, sql.substring(i, end))
                    i = end
                }

                else -> {
                    tokens += Token(Kind.SYMBOL, c.toString())
                    i++
                }
            }
        }
        return tokens
    }

    private fun isWordChar(c: Char): Boolean = c.isLetterOrDigit() || c == '_' || c == '.' || c == '$' || c == '@'

    private fun endOfQuoted(sql: String, start: Int, quote: Char): Int {
        var i = start + 1
        while (i < sql.length) {
            if (sql[i] == quote) {
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
