package io.github.xbeeant.batiskit.log

/**
 * 把控制台日志里的 SQL 反查到 Mapper XML 语句标签（纯逻辑，无平台依赖）。
 *
 * MyBatis 的标准日志（`==> Preparing:`）**不带 statement id**，所以只能按 SQL 文本相似度找：
 *  - XML 里的 `#{id}` / `${name}` 与日志里的 `?` 归一化成同一个占位符；
 *  - `<if>` / `<where>` / `<foreach>` 等动态标签只去壳、保留内容（分支里的条件仍然参与匹配）；
 *  - 用词元 Dice 系数衡量重合度，再给「公共前缀」「相同 SQL 动词」加权；
 *  - 首动词不同（如日志是 SELECT、候选是 UPDATE）直接判 0，避免跨语句误跳。
 *
 * 只是**静态相似度**，不做 SQL 语义解析：结构相近的多个语句会一并列出，由 UI 让用户选。
 */
object SqlStatementMatcher {

    /** 一个 Mapper XML 语句候选；[sql] 传标签完整文本即可，归一化时会剥掉动态标签 */
    data class Candidate(
        val id: String,
        val namespace: String,
        val sql: String,
        val key: String = "$namespace.$id"
    )

    data class Match(val candidate: Candidate, val score: Int)

    /** 低于这个分数不认为是同一条语句（经验值：结构相似但不相关的 SQL 一般在 40 以下） */
    const val MIN_SCORE = 45

    /** 第一名领先第二名这么多分且本身足够高时，直接跳转而不用弹选择器 */
    const val AUTO_NAVIGATE_LEAD = 20
    const val AUTO_NAVIGATE_SCORE = 70

    private val VERBS = setOf("select", "insert", "update", "delete", "replace")

    /** SQL 骨架词不参与相似度：只留表名 / 列名这类「内容词」，否则所有 SELECT 都能彼此匹配 */
    private val STOPWORDS = setOf(
        "select", "from", "where", "and", "or", "insert", "into", "values", "update", "set",
        "delete", "join", "left", "right", "inner", "outer", "on", "order", "by", "group",
        "having", "limit", "offset", "as", "in", "is", "not", "null", "like", "between",
        "distinct", "count", "sum", "avg", "max", "min", "asc", "desc", "case", "when",
        "then", "else", "end", "exists", "union", "all", "with", "replace"
    )

    private val XML_TAG = Regex("<[^>]*>")
    private val BIND = Regex("(?:#|\\$)\\{[^}]*\\}")
    private val LINE_COMMENT = Regex("--[^\\n]*")
    private val BLOCK_COMMENT = Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL)
    private val WHITESPACE = Regex("\\s+")
    private val TOKEN_SPLIT = Regex("[^a-z0-9_]+")

    /** XML 语句 / 日志 SQL 统一成「小写 + 单空格 + ? 占位」形式 */
    fun normalize(sql: String): String = sql
        .lowercase()
        .replace(BIND, "?")
        .replace(XML_TAG, " ")
        .replace(LINE_COMMENT, " ")
        .replace(BLOCK_COMMENT, " ")
        .replace(WHITESPACE, " ")
        .trim()

    /** 参与相似度计算的词元：跳过单字符、纯数字、SQL 骨架词与占位符噪声 */
    fun tokens(normalized: String): Set<String> = normalized
        .split(TOKEN_SPLIT)
        .filter { it.length >= 2 && it.any(Char::isLetter) && it !in STOPWORDS }
        .toSet()

    fun verb(normalized: String): String = normalized.substringBefore(' ').trim()

    /** 0 = 完全不同；分数越高越像。上限约 128（Dice 100 + 前缀 20 + 动词 8） */
    fun score(logSql: String, candidateSql: String): Int {
        val log = normalize(logSql)
        val candidate = normalize(candidateSql)
        if (log.isBlank() || candidate.isBlank()) return 0
        val logTokens = tokens(log)
        val candidateTokens = tokens(candidate)
        if (logTokens.isEmpty() || candidateTokens.isEmpty()) return 0

        val logVerb = verb(log)
        val candidateVerb = verb(candidate)
        if (logVerb in VERBS && candidateVerb in VERBS && logVerb != candidateVerb) return 0

        val hit = logTokens.intersect(candidateTokens).size
        if (hit == 0) return 0
        val dice = 200 * hit / (logTokens.size + candidateTokens.size)
        val prefix = commonPrefix(log, candidate)
        val prefixBonus = when {
            prefix >= 30 -> 20
            prefix >= 15 -> 12
            prefix >= 8 -> 6
            else -> 0
        }
        val verbBonus = if (logVerb == candidateVerb) 8 else 0
        return dice + prefixBonus + verbBonus
    }

    /** 按分数降序返回可信候选；同分时按 key 稳定排序，避免每次弹出顺序抖动 */
    fun rank(logSql: String, candidates: List<Candidate>, limit: Int = 8): List<Match> =
        candidates.mapNotNull { candidate ->
            val value = score(logSql, candidate.sql)
            if (value < MIN_SCORE) null else Match(candidate, value)
        }
            .sortedWith(compareByDescending<Match> { it.score }.thenBy { it.candidate.key })
            .take(limit)

    /** 是否可以跳过选择器直接跳到第一名 */
    fun isClearWinner(matches: List<Match>): Boolean {
        if (matches.isEmpty()) return false
        if (matches.size == 1) return true
        return matches[0].score >= AUTO_NAVIGATE_SCORE &&
            matches[0].score - matches[1].score >= AUTO_NAVIGATE_LEAD
    }

    private fun commonPrefix(a: String, b: String): Int {
        var i = 0
        while (i < a.length && i < b.length && a[i] == b[i]) i++
        return i
    }
}
