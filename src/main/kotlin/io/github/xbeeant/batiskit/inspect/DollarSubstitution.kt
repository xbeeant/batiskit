package io.github.xbeeant.batiskit.inspect

/**
 * Mapper XML 里 `${…}` 占位符的定位（纯逻辑，不依赖 IDE）。
 *
 * `${…}` 是 MyBatis 的字符串替换——值直接拼进 SQL 文本，不经过预编译参数绑定，
 * 是 SQL 注入的主要来源（`#{} `才是 PreparedStatement 占位符）。
 * 静态无法判断"这个值是否来自用户输入"，所以只要出现就值得提醒，
 * 由使用者自行判断 order by / 列名拼接这类无法用 `#{}` 的合法场景。
 */
object DollarSubstitution {

    private val DOLLAR = Regex("""\$\{([^}]*)}""")

    /** 一处 `${…}`：[start]/[end] 为含 `$` 与 `}` 的整段范围 */
    data class Occurrence(val text: String, val start: Int, val end: Int)

    /** 取出一段文本里的全部 `${…}`（含空表达式） */
    fun occurrences(source: String): List<Occurrence> =
        DOLLAR.findAll(source).map { Occurrence(it.value, it.range.first, it.range.last + 1) }.toList()

    /** 把 `${x}` 改写成 `#{x}` 后的文本（body 原样保留） */
    fun toHashSyntax(occurrence: Occurrence): String = "#" + occurrence.text.substring(1)
}
