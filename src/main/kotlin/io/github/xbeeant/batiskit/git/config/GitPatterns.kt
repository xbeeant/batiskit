package io.github.xbeeant.batiskit.git.config

/**
 * 设置页里以「逗号分隔文本」保存的列表，以及分支清理用的 glob 匹配。
 *
 * 都是纯函数，方便单测（不依赖 IDE 环境）。
 */
object GitPatterns {

    /** 逗号（含中文逗号）/换行 / 分号分隔的文本 -> 去空去重的列表 */
    fun parseCsv(text: String): List<String> =
        text.split(',', '，', ';', '\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()

    /**
     * glob 匹配：`*` 任意字符、`?` 单个字符，其余字符按字面量处理。
     * 分支名不带路径语义，`release/v1*` 可以匹配 `release/v1.2`。
     */
    fun matchesGlob(pattern: String, name: String): Boolean = globToRegex(pattern).matches(name)

    fun anyMatches(patterns: List<String>, name: String): Boolean = patterns.any { matchesGlob(it, name) }

    private fun globToRegex(pattern: String): Regex {
        val sb = StringBuilder()
        for (ch in pattern) {
            when (ch) {
                '*' -> sb.append(".*")
                '?' -> sb.append('.')
                else -> sb.append(Regex.escape(ch.toString()))
            }
        }
        return Regex(sb.toString())
    }
}
