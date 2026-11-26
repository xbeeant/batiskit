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
    private val SELECT_VERBS = setOf("select", "with")

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

    /** `<include refid="..."/>`：Mapper XML 里的 SQL 片段引用，匹配前先内联 */
    private val INCLUDE_TAG = Regex(
        """<include\b[^>]*\brefid\s*=\s*(?:"([^"]*)"|'([^']*)')[^>]*>""",
        RegexOption.IGNORE_CASE,
    )

    // 分页插件改写的 SQL 外壳。日志里是改写后的，XML 里是原始语句，匹配前要把这些壳剥掉。
    private val COUNT_WRAPPER = Regex(
        """^\s*select\s+count\s*\([^)]*\)\s*(?:as\s+\w+\s*)?from\s*\(\s*(select[\s\S]*)\)\s*(?:as\s+)?[\w$]+\s*$""",
        RegexOption.IGNORE_CASE,
    )
    private val ORACLE_PAGE = Regex(
        """^\s*select\s+\*\s+from\s*\(\s*(select[\s\S]*)\)\s+where\s+rownum\s*(?:<=|<)\s*\S+\s*$""",
        RegexOption.IGNORE_CASE,
    )
    private val LIMIT_TAIL = Regex(
        """\s+limit\s+\S+(?:\s*,\s*\S+)?(?:\s+offset\s+\S+)?\s*$""",
        RegexOption.IGNORE_CASE,
    )
    private val OFFSET_FETCH_TAIL = Regex(
        """\s+offset\s+\S+\s+rows\s+fetch\s+(?:first|next)\s+\S+\s+rows\s+only\s*$""",
        RegexOption.IGNORE_CASE,
    )
    private val ROWNUM_TAIL = Regex(
        """\s+and\s+rownum\s*(?:<=|<)\s*\S+\s*$""",
        RegexOption.IGNORE_CASE,
    )

    /** XML 语句 / 日志 SQL 统一成「小写 + 单空格 + ? 占位」形式 */
    fun normalize(sql: String): String = sql
        .lowercase()
        .replace(BIND, "?")
        .replace(XML_TAG, " ")
        .replace(LINE_COMMENT, " ")
        .replace(BLOCK_COMMENT, " ")
        .replace(WHITESPACE, " ")
        .trim()

    /**
     * 把 `<include refid="..."/>` 换成对应 `<sql>` 片段，让候选语句带上真实的表名 / 列名。
     * 片段可以嵌套引用其它片段，最多展开 4 层，防止配置里出现环时无限递归。
     */
    fun inlineIncludes(sql: String, fragments: Map<String, String>, depth: Int = 0): String {
        if (depth >= MAX_INCLUDE_DEPTH) return sql
        var changed = false
        val expanded = INCLUDE_TAG.replace(sql) { match ->
            val refid = (match.groupValues[1].ifEmpty { match.groupValues[2] }).trim()
            val body = fragments[refid] ?: fragments[refid.substringAfterLast('.')]
            if (body == null) {
                match.value
            } else {
                changed = true
                inlineIncludes(body, fragments, depth + 1)
            }
        }
        return if (changed) inlineIncludes(expanded, fragments, depth + 1) else expanded
    }

    /**
     * 剥掉分页插件在日志 SQL 上加的壳：PageHelper / MyBatis-Plus 的 count 包装、
     * MySQL `LIMIT ?, ?`、SQLServer `OFFSET ... FETCH`、Oracle `ROWNUM`。
     * 只用于日志侧；XML 里本来就写了 limit 时不该被改写。
     */
    fun stripPagination(sql: String): String {
        var current = sql.trim()
        repeat(MAX_UNWRAP) {
            var next = current
            COUNT_WRAPPER.matchEntire(next)?.let { next = it.groupValues[1].trim() }
            ORACLE_PAGE.matchEntire(next)?.let { next = it.groupValues[1].trim() }
            next = LIMIT_TAIL.replace(next, " ").trim()
            next = OFFSET_FETCH_TAIL.replace(next, " ").trim()
            next = ROWNUM_TAIL.replace(next, " ").trim()
            if (next == current) return current
            current = next
        }
        return current.trim()
    }

    /** 日志侧准备两种形态：剥掉分页壳的优先，原始形态兜底（XML 自带分页时不误判） */
    private fun matchingForms(logSql: String): List<String> {
        val raw = normalize(logSql)
        val stripped = normalize(stripPagination(logSql))
        return if (stripped == raw) listOf(raw) else listOf(stripped, raw)
    }

    /** 参与相似度计算的词元：跳过单字符、纯数字、SQL 骨架词与占位符噪声 */
    fun tokens(normalized: String): Set<String> = normalized
        .split(TOKEN_SPLIT)
        .filter { it.length >= 2 && it.any(Char::isLetter) && it !in STOPWORDS }
        .toSet()

    fun verb(normalized: String): String = normalized.substringBefore(' ').trim()

    /** 0 = 完全不同；分数越高越像。上限约 128（覆盖率 100 + 前缀 20 + 动词 8） */
    fun score(logSql: String, candidateSql: String): Int =
        scorePrepared(matchingForms(logSql), normalize(candidateSql))

    private fun scorePrepared(logForms: List<String>, originalCandidate: String): Int {
        if (originalCandidate.isBlank()) return 0
        var best = 0
        for (originalLog in logForms) {
            if (originalLog.isBlank()) continue

            // MyBatis-Plus 新版会把 count 优化成 `select count(*) from 原表 where ...`：
            // 日志里没有 XML 的 select 列，两边都改成 `select ... from 之后` 再比，避免误跳。
            var log = originalLog
            var candidate = originalCandidate
            if (verb(log) == "count" && verb(candidate) in SELECT_VERBS) {
                val logFrom = log.indexOf(" from ")
                val candidateFrom = candidate.indexOf(" from ")
                if (logFrom >= 0 && candidateFrom >= 0) {
                    log = "select " + log.substring(logFrom + 1)
                    candidate = "select " + candidate.substring(candidateFrom + 1)
                }
            }

            val logTokens = tokens(log)
            val candidateTokens = tokens(candidate)
            if (logTokens.isEmpty() || candidateTokens.isEmpty()) continue
            val logVerb = verb(log)
            val candidateVerb = verb(candidate)
            if (logVerb in VERBS && candidateVerb in VERBS && logVerb != candidateVerb) continue

            val hit = logTokens.intersect(candidateTokens).size
            if (hit == 0) continue
            // 日志是运行时展开的：分页插件会加条件、XML 里可能有没走到的动态分支。
            // 用「XML 词元被日志覆盖」为主、日志冗余为辅，比对称 Dice 更能认出同一条语句。
            val coverage = (60.0 * hit / candidateTokens.size + 40.0 * hit / logTokens.size).toInt()
            val prefix = commonPrefix(log, candidate)
            val prefixBonus = when {
                prefix >= 30 -> 20
                prefix >= 15 -> 12
                prefix >= 8 -> 6
                else -> 0
            }
            val verbBonus = if (logVerb == candidateVerb) 8 else 0
            best = maxOf(best, coverage + prefixBonus + verbBonus)
        }
        return best
    }

    /** 按分数降序返回可信候选；同分时按 key 稳定排序，避免每次弹出顺序抖动 */
    fun rank(logSql: String, candidates: List<Candidate>, limit: Int = 8): List<Match> {
        val forms = matchingForms(logSql)
        return candidates.mapNotNull { candidate ->
            val value = scorePrepared(forms, normalize(candidate.sql))
            if (value < MIN_SCORE) null else Match(candidate, value)
        }
            .sortedWith(compareByDescending<Match> { it.score }.thenBy { it.candidate.key })
            .take(limit)
    }

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

    private const val MAX_INCLUDE_DEPTH = 4
    private const val MAX_UNWRAP = 3
}
