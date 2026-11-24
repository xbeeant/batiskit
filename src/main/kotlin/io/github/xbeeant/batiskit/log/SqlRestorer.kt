package io.github.xbeeant.batiskit.log

import java.text.SimpleDateFormat
import java.util.*

/**
 * 把 MyBatis 日志里的 `Preparing` SQL + `Parameters` 参数还原成可以直接执行的 SQL。
 *
 * 只做文本替换，不依赖任何数据库元数据：
 *  - 扫描 `?` 时跳过字符串字面量、引号标识符与注释，避免把 `'a?b'` 里的问号当占位符；
 *  - 参数按类型转成 SQL 字面量：字符串加单引号并转义 `'`，日期转 `yyyy-MM-dd HH:mm:ss`，null 转 `NULL`，
 *    枚举 / UUID 等未知类型按字符串处理，数值与布尔保持原样。
 */
object SqlRestorer {

    /** 还原结果：[text] 是替换后的 SQL，[replaced] 是替换掉的占位符数，[missing] 是没参数可用的占位符数 */
    data class Restored(val text: String, val replaced: Int, val missing: Int) {
        val isComplete: Boolean get() = missing == 0
    }

    /** 日志里 `值(类型)` 的拆解：贪婪匹配最后一个括号，`a, b(String)` 这种带逗号的字符串也能整段取到 */
    private val TYPED_PARAMETER = Regex("""^(.*)\(([A-Za-z_$][\w.$\[\]]*)\)$""", RegexOption.DOT_MATCHES_ALL)

    private val NUMERIC_TYPE = Regex("""(?:[a-z]*int(?:eger)?|long|short|double|float|decimal|number|biginteger|byte)$""")

    private val DATE_TO_STRING = SimpleDateFormat("EEE MMM dd HH:mm:ss zzz yyyy", Locale.ENGLISH)

    /**
     * 解析 `==> Parameters:` 后面的参数串。
     *
     * MyBatis 用 `", "` 分隔参数，但字符串参数本身可能带逗号，所以先按逗号切开、
     * 再按「每一段是否以 `(类型)` 结尾」合并回去：`a, b(String)` 会合并成一个参数，
     * 不带类型的 `null` 单独成为一个 NULL 参数。
     */
    fun parseParameters(text: String): List<MyBatisParameter> {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) {
            return emptyList()
        }
        val parameters = mutableListOf<MyBatisParameter>()
        var carry: String? = null
        trimmed.split(Regex(""",\s*""")).forEach { piece ->
            val merged = if (carry == null) piece else "$carry, $piece"
            val typed = TYPED_PARAMETER.matchEntire(merged)
            when {
                typed != null -> {
                    parameters += MyBatisParameter(typed.groupValues[1], typed.groupValues[2])
                    carry = null
                }

                merged.equals("null", ignoreCase = true) -> {
                    parameters += MyBatisParameter(merged, null)
                    carry = null
                }

                // 没有类型后缀：可能只是带逗号的字符串被切开了，继续往后拼
                else -> carry = merged
            }
        }
        if (carry != null) {
            parameters += MyBatisParameter(carry, null)
        }
        return parameters
    }

    /** 还原：把 [sql] 里的 `?` 依次换成 [parameters] 对应的字面量 */
    fun restore(
        sql: String,
        parameters: List<MyBatisParameter>,
        normalizeDate: Boolean = true
    ): Restored {
        val out = StringBuilder(sql.length + parameters.size * 8)
        var index = 0
        var missing = 0
        var i = 0
        while (i < sql.length) {
            val c = sql[i]
            when {
                c == '\'' || c == '"' || c == '`' -> i = copyQuoted(sql, i, c, out)

                c == '-' && i + 1 < sql.length && sql[i + 1] == '-' -> i = copyLineComment(sql, i, out)

                c == '/' && i + 1 < sql.length && sql[i + 1] == '*' -> i = copyBlockComment(sql, i, out)

                c == '?' -> {
                    val parameter = parameters.getOrNull(index)
                    if (parameter == null) {
                        out.append('?')
                        missing++
                    } else {
                        out.append(literal(parameter, normalizeDate))
                    }
                    index++
                    i++
                }

                else -> {
                    out.append(c)
                    i++
                }
            }
        }
        return Restored(out.toString(), index - missing, missing)
    }

    /** 统计 SQL 里真正的占位符个数（跳过字符串、引号标识符与注释） */
    fun countPlaceholders(sql: String): Int {
        var count = 0
        var i = 0
        while (i < sql.length) {
            val c = sql[i]
            when {
                c == '\'' || c == '"' || c == '`' -> i = skipQuoted(sql, i, c)

                c == '-' && i + 1 < sql.length && sql[i + 1] == '-' -> i = skipLineComment(sql, i)

                c == '/' && i + 1 < sql.length && sql[i + 1] == '*' -> i = skipBlockComment(sql, i)

                c == '?' -> {
                    count++
                    i++
                }

                else -> i++
            }
        }
        return count
    }

    /** 单个参数转 SQL 字面量 */
    fun literal(parameter: MyBatisParameter, normalizeDate: Boolean = true): String {
        val value = parameter.value
        val type = parameter.type?.lowercase(Locale.ROOT)
            ?: return if (parameter.isNull) "NULL" else value
        return when {
            // byte[] 之类二进制参数在日志里只有 [B@1f2e3d，替换出来也没有意义，保持原样
            type.endsWith("[]") -> value

            type.endsWith("string") || type.endsWith("char") || type.contains("clob") -> quote(value)

            type.contains("date") || type.contains("time") -> quote(normalizeDateValue(value, normalizeDate))

            type.contains("boolean") -> value

            NUMERIC_TYPE.containsMatchIn(type) -> value

            // 枚举 / UUID / 自定义类型：日志里是 toString()，按字符串拼更接近能执行的形态
            else -> quote(value)
        }
    }

    /** 单引号包裹并转义 `'`（MySQL / 标准 SQL 都认） */
    fun quote(value: String): String = "'" + value.replace("'", "''") + "'"

    /**
     * `java.util.Date` 在日志里是 `Mon Nov 03 22:00:00 CST 2026` 这种 toString，
     * 转成 SQL 认识的 `yyyy-MM-dd HH:mm:ss`；解析失败就原样返回（Timestamp 本来就是标准格式）。
     */
    fun normalizeDateValue(value: String, normalizeDate: Boolean): String {
        if (!normalizeDate) {
            return value
        }
        val parsed = runCatching { DATE_TO_STRING.parse(value) }.getOrNull() ?: return value
        return SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ENGLISH).format(parsed)
    }

    // ------------------------------------------------------------------ 扫描辅助

    /** 原样复制一段引号内容（含转义的双写引号），返回下一个待处理位置 */
    private fun copyQuoted(sql: String, start: Int, quote: Char, out: StringBuilder): Int {
        out.append(sql[start])
        var i = start + 1
        while (i < sql.length) {
            val c = sql[i]
            out.append(c)
            if (c == quote) {
                // '' 是 SQL 里的转义引号，不能当结束
                if (i + 1 < sql.length && sql[i + 1] == quote) {
                    out.append(sql[i + 1])
                    i += 2
                    continue
                }
                return i + 1
            }
            i++
        }
        return i
    }

    private fun skipQuoted(sql: String, start: Int, quote: Char): Int {
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
        return i
    }

    private fun copyLineComment(sql: String, start: Int, out: StringBuilder): Int {
        var i = start
        while (i < sql.length && sql[i] != '\n') {
            out.append(sql[i])
            i++
        }
        return i
    }

    private fun skipLineComment(sql: String, start: Int): Int {
        var i = start
        while (i < sql.length && sql[i] != '\n') {
            i++
        }
        return i
    }

    private fun copyBlockComment(sql: String, start: Int, out: StringBuilder): Int {
        val end = sql.indexOf("*/", start + 2)
        val stop = if (end < 0) sql.length else end + 2
        out.append(sql, start, stop)
        return stop
    }

    private fun skipBlockComment(sql: String, start: Int): Int {
        val end = sql.indexOf("*/", start + 2)
        return if (end < 0) sql.length else end + 2
    }
}
