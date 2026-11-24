package io.github.xbeeant.batiskit.util

import javax.swing.RowFilter
import javax.swing.table.TableModel

/**
 * 工具窗口 / 表格共用的搜索表达式（纯逻辑 + JDK Swing，零平台依赖，便于单测）。
 *
 *  - 空格分隔多个关键字 = AND，大小写不敏感；
 *  - `-foo` = 排除含 `foo` 的行；
 *  - `/regex/` = 正则匹配（忽略大小写）；正则写错时退化成按字面量匹配，不弹错、不让搜索框失灵。
 *
 * 原来是 MyBatis Log 面板里的 `LogSearchQuery`，现在上移复用；调用方提供待匹配的文本即可，
 * 表格用 [rowFilter]，各列分别参与匹配。
 */
object SearchQuery {

    data class Query(
        val include: List<String> = emptyList(),
        val exclude: List<String> = emptyList(),
        val regexes: List<Regex> = emptyList()
    ) {
        val isEmpty: Boolean get() = include.isEmpty() && exclude.isEmpty() && regexes.isEmpty()
    }

    fun parse(text: String): Query {
        val include = mutableListOf<String>()
        val exclude = mutableListOf<String>()
        val regexes = mutableListOf<Regex>()
        text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.forEach { term ->
            when {
                term.length >= 3 && term.startsWith('/') && term.endsWith('/') -> {
                    val pattern = term.substring(1, term.length - 1)
                    // 非法正则按字面量处理：输入 /foo(/ 时仍能搜到包含 foo( 的行
                    regexes += runCatching { Regex(pattern, RegexOption.IGNORE_CASE) }
                        .getOrElse { Regex(Regex.escape(pattern), RegexOption.IGNORE_CASE) }
                }

                // `-1` / `--` 这类更可能是 SQL 注释或负数字面量，只有带字母的才算排除项
                term.length > 2 && term.startsWith('-') && term.substring(1).any(Char::isLetter) ->
                    exclude += term.substring(1)

                else -> include += term
            }
        }
        return Query(include, exclude, regexes)
    }

    fun matches(text: String, query: Query): Boolean {
        if (query.isEmpty) return true
        if (query.exclude.any { text.contains(it, ignoreCase = true) }) return false
        if (query.include.any { !text.contains(it, ignoreCase = true) }) return false
        return query.regexes.all { it.containsMatchIn(text) }
    }

    /**
     * 多列文本：AND 关键字可以分布在不同列，正则**逐列**尝试。
     * 逐列是必要的 —— 拼成一整串后 `/^id$/` 这类带锚点的正则永远匹配不到任何一列。
     */
    fun matches(texts: Iterable<String>, query: Query): Boolean {
        if (query.isEmpty) return true
        val values = texts.toList()
        if (query.exclude.any { term -> values.any { it.contains(term, ignoreCase = true) } }) return false
        if (query.include.any { term -> values.none { it.contains(term, ignoreCase = true) } }) return false
        return query.regexes.all { rx -> values.any { rx.containsMatchIn(it) } }
    }

    /** 给 `TableRowSorter` 用的行过滤器；空表达式放行所有行 */
    fun rowFilter(query: Query): RowFilter<TableModel, Int> =
        object : RowFilter<TableModel, Int>() {
            override fun include(entry: RowFilter.Entry<out TableModel, out Int>): Boolean {
                if (query.isEmpty) return true
                val values = (0 until entry.valueCount).map { entry.getValue(it)?.toString().orEmpty() }
                return matches(values, query)
            }
        }
}
