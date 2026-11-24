package io.github.xbeeant.batiskit.rainbow

/**
 * 彩虹括号的语言 / 文件过滤（纯 Kotlin，零平台依赖）。
 *
 * 对标 Rainbow Brackets 的「Do NOT rainbowify these languages」：条目既可以是语言 ID
 * （`JAVA`、`kotlin`），也可以是文件名（`pom.xml`）或扩展名（`*.java` / `.java` / `java`）。
 * 黑白名单都大小写不敏感；**黑名单优先**——明确排除的条目即使同时命中白名单也不生效。
 */
object RainbowFilter {

    private val SEPARATORS = charArrayOf(',', ';', ' ', '\n', '\r', '\t')

    /** 拆配置字符串：去空白、小写、剥掉通配前缀 `*.` 与 `.`，去重并保留顺序。 */
    fun parse(spec: String?): List<String> {
        if (spec.isNullOrBlank()) return emptyList()
        return spec.split(*SEPARATORS)
            .map { it.trim().lowercase() }
            .map { it.removePrefix("*.").removePrefix(".") }
            .filter { it.isNotEmpty() }
            .distinct()
    }

    /** 单个条目是否命中当前文件。token 需先经 [parse] 规范化。 */
    fun matches(token: String, languageId: String, fileName: String): Boolean {
        if (token.isEmpty()) return false
        val lang = languageId.lowercase()
        val name = fileName.lowercase()
        if (token == lang || token == name) return true
        // 扩展名：`*.java` / `.java` / 直接写 `java` 都能命中 Foo.java
        return name.endsWith(".$token") || token == name.substringAfterLast('.', "")
    }

    /** 白名单留空 = 不限制；黑名单命中即拒绝。 */
    fun accepts(languageId: String, fileName: String, whiteList: String?, blackList: String?): Boolean {
        val black = parse(blackList)
        if (black.any { matches(it, languageId, fileName) }) return false
        val white = parse(whiteList)
        return white.isEmpty() || white.any { matches(it, languageId, fileName) }
    }
}
