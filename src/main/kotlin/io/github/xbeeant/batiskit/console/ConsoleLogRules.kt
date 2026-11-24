package io.github.xbeeant.batiskit.console

import java.awt.Color

/**
 * 控制台日志的「纯逻辑」层：级别识别、关键词高亮区间、过滤判定。
 *
 * 这里刻意不 import 任何 IntelliJ 平台类 —— 着色用的是 [HighlightSegment] 里的颜色字符串，
 * 平台侧再转成 TextAttributes。好处是全部规则都能用普通 JUnit 断言，不必起 IDE 夹具。
 */

/** 级别只保留五档；FATAL / SEVERE / WARNING 这些别名在 [LogLevel.of] 里归一 */
enum class LogLevel(val label: String, val rank: Int) {
    OTHER("OTHER", 0),
    TRACE("TRACE", 10),
    DEBUG("DEBUG", 20),
    INFO("INFO", 30),
    WARN("WARN", 40),
    ERROR("ERROR", 50);

    companion object {
        fun of(token: String): LogLevel = when (token.uppercase()) {
            "TRACE", "VERBOSE", "FINEST", "FINER" -> TRACE
            "DEBUG", "FINE", "CONFIG" -> DEBUG
            "INFO", "LOG", "NOTICE" -> INFO
            "WARN", "WARNING" -> WARN
            "ERROR", "ERR", "FATAL", "SEVERE", "CRITICAL" -> ERROR
            else -> OTHER
        }

        fun fromName(name: String): LogLevel = entries.firstOrNull { it.name == name } ?: OTHER
    }
}

/** 半开区间 [start, end)，避免 IntRange 闭区间在切段时差一个字符 */
data class Span(val start: Int, val end: Int)

/** 解析出的一行。 [levelRange] 是级别词在原行里的位置，着色时可以只给级别词加深 */
data class LogLine(
    val text: String,
    val level: LogLevel = LogLevel.OTHER,
    val levelRange: Span? = null,
    /** 堆栈 / 异常续行没有自己的级别，跟着上一条有级别的日志走 */
    val continuation: Boolean = false,
)

/**
 * 级别识别：按「日志前缀」而不是全文找关键词。
 *
 * 只在行首前 160 个字符里找**全大写**的级别词，并要求它处在结构分隔符之间
 * （`[ERROR]`、`ERROR:`、`| INFO |`、时间戳后跟 INFO 等）。这样
 * `... failed with ERROR from gateway` 这种消息正文里的大写单词不会被当成级别。
 */
class LogLineParser {

    /** 上一条带级别的日志，用于给堆栈续行兜底 */
    private var lastLevel: LogLevel = LogLevel.OTHER

    fun parse(text: String): LogLine {
        val line = text.trimEnd('\r', '\n')
        val prefix = line.substring(0, minOf(line.length, MAX_PREFIX))
        val token = LEVEL_IN_PREFIX.findAll(prefix).mapNotNull { candidate ->
            val word = candidate.groups[1]?.value ?: return@mapNotNull null
            if (!isStructural(candidate.range.first, candidate.range.last + 1, prefix)) {
                null
            } else {
                word to Span(candidate.groups[1]!!.range.first, candidate.groups[1]!!.range.last + 1)
            }
        }.firstOrNull()

        if (token != null) {
            val level = LogLevel.of(token.first)
            if (level != LogLevel.OTHER) {
                lastLevel = level
                return LogLine(line, level, token.second)
            }
        }

        if (isContinuation(line) && lastLevel.rank >= LogLevel.WARN.rank) {
            // 只有上一条是 ERROR / WARN 才继承：普通缩进输出不该被染成红色，
            // 而异常堆栈几乎总是紧跟在 ERROR 后面
            return LogLine(line, lastLevel, null, continuation = true)
        }
        return LogLine(line)
    }

    fun reset() {
        lastLevel = LogLevel.OTHER
    }

    private fun isStructural(start: Int, end: Int, prefix: String): Boolean {
        val before = if (start == 0) ' ' else prefix[start - 1]
        val after = if (end >= prefix.length) ' ' else prefix[end]
        val leftOk = before == '[' || before == '|' || before == ':' || before.isWhitespace()
        val head = prefix.substring(0, start)
        // log4j 的 `12:00:00.123 [main] ERROR c.e.Demo - msg` 里级别后面只有一个空格，
        // 但前缀已经有线程名方括号，足以确认这是级别标记而不是正文
        val bracketedHead = '[' in head
        val rightOk = when {
            after == ']' || after == ':' || after == '|' || after == '-' -> true
            after.isWhitespace() && bracketedHead -> true
            after.isWhitespace() -> {
                // 单空格后面跟小写单词（"ERROR from gateway"）多半是正文；
                // 时间戳 / `---` / 数字 pid / 两个以上空格这类日志骨架则放行
                val nextNonSpace = prefix.substring(end).dropWhile { it == ' ' || it == '\t' }.firstOrNull()
                nextNonSpace == null || nextNonSpace == '[' || nextNonSpace == '-' ||
                    nextNonSpace == '|' || nextNonSpace.isDigit() ||
                    prefix.getOrNull(end + 1)?.isWhitespace() == true
            }
            else -> false
        }
        if (!leftOk || !rightOk) return false
        // 前缀里出现了日志骨架（时间戳 / 方括号线程名 / 管道 / logger），级别词才算「结构化」
        return start == 0 || bracketedHead || head.any { it == ']' || it == '|' || it == ':' } ||
            TIMESTAMP_PREFIX.containsMatchIn(head)
    }

    companion object {
        private const val MAX_PREFIX = 160

        private const val WORDS = "TRACE|DEBUG|INFO|WARN|WARNING|ERROR|FATAL|SEVERE|ERR|CRITICAL|NOTICE"

        /** 大小写敏感：日志框架输出级别标记一律是大写，正文里的 error / Error 不会命中 */
        private val LEVEL_IN_PREFIX = Regex("(?<![A-Za-z0-9_])($WORDS)(?![A-Za-z0-9_])")

        private val TIMESTAMP_PREFIX = Regex("""\d{2,4}[-/]\d{2}[-/]\d{2}[T ]\d{2}:\d{2}:\d{2}|\d{2}:\d{2}:\d{2}[.,]\d+""")

        /** 堆栈续行：缩进、`at xxx`、`Caused by:`、`... N more`、`Suppressed:`、异常类名行 */
        fun isContinuation(line: String): Boolean {
            val t = line.trimStart()
            if (t.isEmpty()) return true
            if (line.first().isWhitespace() && line.length - t.length >= 2) return true
            return t.startsWith("at ") || t.startsWith("Caused by:") || t.startsWith("Suppressed:") ||
                t.startsWith("... ") && t.endsWith(" more") ||
                EXCEPTION_LINE.matches(t)
        }

        private val EXCEPTION_LINE = Regex("""[a-zA-Z_$][\w$]*(?:\.[\w$]+)+(?:Exception|Error|Throwable)(?::.*)?""")
    }
}

/** 一条自定义高亮规则。颜色是 `浅色|深色` 两个 hex，平台层拆开生成 JBColor */
data class HighlightRule(
    var pattern: String = "",
    var regex: Boolean = false,
    var caseSensitive: Boolean = false,
    var color: String = DEFAULT_COLOR,
    var background: String = "",
    var bold: Boolean = true,
    var italic: Boolean = false,
    /** 勾上后整行命中才着色，且作用范围是整行（参考原版插件的 Whole line） */
    var wholeLine: Boolean = false,
    var enabled: Boolean = true,
) {
    /** 本规则在一行里的命中区间；坏正则 / 空规则直接跳过，不能把控制台搞崩 */
    fun ranges(line: String): List<Span> {
        if (!enabled || pattern.isBlank() || line.isEmpty()) return emptyList()
        val subject = line.substring(0, minOf(line.length, MAX_LINE))
        val rx = regexFor(pattern, regex, caseSensitive).getOrElse { return emptyList() }
        val result = ArrayList<Span>()
        try {
            if (wholeLine) {
                if (rx.matches(subject)) {
                    result += Span(0, subject.length)
                }
                return result
            }
            for (match in rx.findAll(subject)) {
                val span = Span(match.range.first, match.range.last + 1)
                if (span.start < span.end) result += span
                if (result.size >= MAX_HITS) break
            }
        } catch (_: RuntimeException) {
            return result
        }
        return result
    }

    companion object {
        /** 浅色主题用深红，深色主题用亮红 */
        const val DEFAULT_COLOR = "#B3261E|#FF8A80"
        private const val MAX_LINE = 4000
        private const val MAX_HITS = 40

        /**
         * 控制台可能一次刷几万行，正则不能每行重编译。规则在设置页改完 pattern 就换 key，
         * 旧缓存条目最多攒 128 个后整体清一次（配置里的规则本来就没几条）。
         */
        private val COMPILED = java.util.concurrent.ConcurrentHashMap<String, Result<Regex>>()

        private fun regexFor(pattern: String, isRegex: Boolean, caseSensitive: Boolean): Result<Regex> {
            val key = "$isRegex|$caseSensitive|$pattern"
            COMPILED[key]?.let { return it }
            val flags = buildSet {
                if (!caseSensitive) add(RegexOption.IGNORE_CASE)
            }
            val result = runCatching {
                if (isRegex) pattern.toRegex(flags) else Regex.escape(pattern).toRegex(flags)
            }
            if (COMPILED.size > 128) COMPILED.clear()
            COMPILED[key] = result
            return result
        }
    }
}

/** 一行里的一段着色区间。 [foreground] 为空表示保持控制台原色 */
data class HighlightSegment(
    val start: Int,
    val end: Int,
    val foreground: String?,
    val background: String? = null,
    val bold: Boolean = false,
    val italic: Boolean = false,
)

/**
 * 把「级别整行色」与「关键词片段色」合成一组**互不重叠且覆盖整行**的区间。
 *
 * 平台的高亮层不保证多个重叠 ResultItem 谁压谁，所以这里先切段再交给 Filter，
 * 效果在任何主题下都是确定的：命中规则的片段用规则色，其余用级别色。
 */
object LogHighlighter {

    fun segments(
        line: LogLine,
        rules: List<HighlightRule>,
        levelColors: Map<LogLevel, String>,
        colorizeLevel: Boolean = true,
        highlightKeywords: Boolean = true,
    ): List<HighlightSegment> {
        val text = line.text
        if (text.isEmpty()) return emptyList()
        val base = if (colorizeLevel) levelColors[line.level]?.takeIf { it.isNotBlank() } else null
        val hits = collectHits(text, if (highlightKeywords) rules else emptyList(), line.levelRange, base)
        if (base == null && hits.isEmpty()) return emptyList()

        // 所有切分点：0 / 每个命中区间的起止 / 行尾
        val points = sortedSetOf(0, text.length)
        hits.forEach { points.add(it.first.start); points.add(it.first.end) }
        val sorted = points.toList()
        val result = ArrayList<HighlightSegment>()
        for (i in 0 until sorted.size - 1) {
            val start = sorted[i]
            val end = sorted[i + 1]
            if (start >= end) continue
            val hit = hits.firstOrNull { it.first.start <= start && it.first.end >= end }
            if (hit != null) {
                result += HighlightSegment(
                    start, end,
                    hit.second.color,
                    hit.second.background.takeIf { it.isNotBlank() },
                    hit.second.bold,
                    hit.second.italic,
                )
            } else if (base != null) {
                result += HighlightSegment(start, end, base)
            }
        }
        return mergeAdjacent(result)
    }

    /** 规则按声明顺序优先：先声明的规则先占区间，后来的只补空档；级别词最后补，仅加粗 */
    private fun collectHits(
        text: String,
        rules: List<HighlightRule>,
        levelToken: Span?,
        base: String?,
    ): List<Pair<Span, HighlightRule>> {
        val taken = ArrayList<Span>()
        val result = ArrayList<Pair<Span, HighlightRule>>()
        fun overlaps(span: Span) = taken.any { it.start < span.end && span.start < it.end }
        rules.forEach { rule ->
            rule.ranges(text).forEach { span ->
                if (!overlaps(span)) {
                    taken += span
                    result += span to rule
                }
            }
        }
        if (levelToken != null && base != null) {
            val span = Span(levelToken.start, minOf(levelToken.end, text.length))
            if (span.start < span.end && !overlaps(span)) {
                result += span to HighlightRule(color = base, bold = true)
            }
        }
        return result.sortedBy { it.first.start }
    }

    private fun mergeAdjacent(segments: List<HighlightSegment>): List<HighlightSegment> {
        if (segments.isEmpty()) return segments
        val result = ArrayList<HighlightSegment>()
        var current = segments.first()
        for (next in segments.drop(1)) {
            val same = current.foreground == next.foreground && current.background == next.background &&
                current.bold == next.bold && current.italic == next.italic
            current = if (same && current.end == next.start) current.copy(end = next.end) else {
                result += current
                next
            }
        }
        result += current
        return result
    }
}

/** 控制台里的过滤模式。默认 OFF —— 隐藏日志不可逆，用户主动打开才生效 */
enum class ConsoleFilterMode(val label: String) {
    OFF("不过滤"),
    ERRORS_ONLY("只显示 ERROR"),
    WARN_AND_ABOVE("只显示 WARN 及以上"),
    MATCH_ONLY("只显示命中关键词的行"),
    HIDE_MATCH("隐藏命中关键词的行"),
    ;

    companion object {
        fun fromName(name: String): ConsoleFilterMode = entries.firstOrNull { it.name == name } ?: OFF
    }
}

/**
 * 过滤规则的命中动作，语义对齐原版 Grep Console 插件的 Filtering 表：
 *
 * - [NONE]：只记一次「命中」，不改文本——常与继续匹配配合，给后面的规则当判定锚点；
 * - [REMOVE]：命中即整行移除；
 * - [REMOVE_UNLESS_MATCHED]：仅当**此前没有任何规则命中本行**时才移除，
 *   配合「先声明一条命中关键词的 NONE 规则」即可实现只保留特定行；
 * - [REPLACE]：把命中文本替换成格式化模板（`$0` 整个命中、`$1..$9` 捕获组），
 *   用于在输出落屏前把行改写得更易读。
 */
enum class FilterAction(val label: String) {
    NONE("无动作（仅命中标记）"),
    REMOVE("移除"),
    REMOVE_UNLESS_MATCHED("移除，除非前面规则已命中"),
    REPLACE("替换为格式化模板"),
    ;

    companion object {
        fun fromName(name: String): FilterAction = entries.firstOrNull { it.name == name } ?: REMOVE
    }
}

/**
 * 一条输入过滤规则（参考原版插件的 Expression / Unless / Whole line / Case insensitive / Action / Continue）。
 *
 * 规则按声明顺序逐条评估：一条规则命中后，若没有勾「继续匹配」，后面的规则不再评估这一行；
 * `unless` 是例外表达式——命中主表达式但同时命中 unless 的行，这条规则视为没命中。
 */
data class FilterRule(
    var pattern: String = "",
    var regex: Boolean = false,
    var caseSensitive: Boolean = false,
    /** 勾上后必须整行匹配才触发（`matches()` 而不是 `find()`） */
    var wholeLine: Boolean = false,
    /** 例外表达式：命中主表达式但同时也命中它时，规则不生效 */
    var unless: String = "",
    var action: String = FilterAction.REMOVE.name,
    /** REPLACE 动作的替换模板，支持 `$0` / `$1..$9`；留空表示删除命中的片段 */
    var replacement: String = "",
    /** 命中后是否继续评估后面的规则（原版插件的 Continue matching） */
    var continueMatching: Boolean = false,
    var enabled: Boolean = true,
) {

    fun actionType(): FilterAction = FilterAction.fromName(action)

    companion object {
        /**
         * 内置示例（默认停用）：一条移除 DEBUG 行的规则 + 一条「只保留含 pay/order 的行」的
         * NONE + REMOVE_UNLESS_MATCHED 组合，把原版插件最常用的两种玩法直接摆到表格里。
         */
        fun defaultRules(): MutableList<FilterRule> = mutableListOf(
            FilterRule(
                pattern = ".*DEBUG.*",
                regex = true,
                wholeLine = true,
                action = FilterAction.REMOVE.name,
                continueMatching = true,
                enabled = false,
            ),
            FilterRule(
                pattern = "\\b(?:pay|order)\\b",
                regex = true,
                action = FilterAction.NONE.name,
                continueMatching = true,
                enabled = false,
            ),
            FilterRule(
                pattern = ".*",
                regex = true,
                wholeLine = true,
                action = FilterAction.REMOVE_UNLESS_MATCHED.name,
                enabled = false,
            ),
        )
    }
}

/** 一条规则在一行文本上的匹配结果。命中 = 主表达式匹配且不落进 unless 例外 */
private data class RuleHit(val hit: Boolean, val ranges: List<Span>)

/**
 * 规则链过滤的纯逻辑层：一行文本 → 保留 / 移除 / 改写。
 *
 * 与原版插件一致，规则按声明顺序评估；一条规则命中后默认停下（除非勾了继续匹配），
 * 所以「先命中的规则」永远优先——这让 NONE + REMOVE_UNLESS_MATCHED 的组合语义是确定的。
 */
class LogRuleFilter(rules: List<FilterRule>) {

    private val compiled: List<CompiledRule> = rules.filter { it.enabled && it.pattern.isNotBlank() }
        .mapNotNull { rule ->
            val main = compile(rule.pattern, rule.regex, rule.caseSensitive) ?: return@mapNotNull null
            val unless = if (rule.unless.isBlank()) {
                null
            } else {
                compile(rule.unless, rule.regex, rule.caseSensitive)
            }
            CompiledRule(rule, main, unless)
        }

    /** 一行的处理决定：[excluded] 移除、[text] 改写后的文本、[matched] 是否有任何规则命中过 */
    data class Decision(val excluded: Boolean, val text: String, val matched: Boolean, val changed: Boolean)

    fun decide(line: String): Decision {
        var text = line
        var matched = false
        var changed = false
        hasEarlierMatch = false
        for (rule in compiled) {
            val hit = evaluate(rule, text)
            if (!hit.hit) continue
            matched = true
            var excluded = false
            when (rule.source.actionType()) {
                FilterAction.NONE -> {
                    // 只做标记，不改文本
                }
                FilterAction.REMOVE -> excluded = true
                FilterAction.REMOVE_UNLESS_MATCHED -> {
                    // 「此前」指本规则之前：前面已有规则命中过就放行，否则移除
                    if (!hasEarlierMatch) {
                        excluded = true
                    }
                }
                FilterAction.REPLACE -> {
                    val rewritten = rewrite(rule, text, hit.ranges)
                    if (rewritten != text) {
                        text = rewritten
                        changed = true
                    }
                }
            }
            if (excluded) {
                return Decision(excluded = true, text = text, matched = matched, changed = changed)
            }
            hasEarlierMatch = true
            if (!rule.source.continueMatching) break
        }
        return Decision(excluded = false, text = text, matched = matched, changed = changed)
    }

    /** REMOVE_UNLESS_MATCHED 依赖「进入本规则前是否已有命中」，逐规则推进 */
    private var hasEarlierMatch: Boolean = false

    private fun evaluate(rule: CompiledRule, line: String): RuleHit {
        val subject = line.substring(0, minOf(line.length, MAX_LINE))
        val ranges: List<Span> = if (rule.source.wholeLine) {
            val matched = runCatching { rule.main.matches(subject) }.getOrDefault(false)
            if (!matched) {
                return RuleHit(false, emptyList())
            }
            listOf(Span(0, subject.length))
        } else {
            val found = runCatching { rule.main.find(subject) }.getOrNull() ?: return RuleHit(false, emptyList())
            runCatching {
                generateSequence(found) { it.next() }
                    .take(MAX_HITS)
                    .map { Span(it.range.first, it.range.last + 1) }
                    .toList()
            }.getOrDefault(emptyList())
        }
        if (rule.unless != null) {
            val excludedByUnless = runCatching { rule.unless.containsMatchIn(subject) }.getOrDefault(false)
            if (excludedByUnless) {
                return RuleHit(false, emptyList())
            }
        }
        return RuleHit(true, ranges)
    }

    /** REPLACE：字面量替换命中的第一个片段；正则用捕获组模板重写（支持 $0 / $1..$9） */
    private fun rewrite(rule: CompiledRule, line: String, ranges: List<Span>): String {
        if (!rule.source.regex) {
            val first = ranges.firstOrNull() ?: return line
            val replacement = rule.source.replacement
            return line.substring(0, first.start) + replacement + line.substring(first.end)
        }
        return runCatching {
            if (rule.source.wholeLine) {
                rule.main.replace(line, transform = { match -> applyTemplate(rule.source.replacement, match) })
            } else {
                // stdlib 的 replaceFirst 没有 transform 重载，手动拼第一处命中
                val match = rule.main.find(line) ?: return@runCatching line
                val range = match.range
                line.substring(0, range.first) + applyTemplate(rule.source.replacement, match) + line.substring(range.last + 1)
            }
        }.getOrDefault(line)
    }

    private fun applyTemplate(template: String, match: MatchResult): String {
        if (template.isEmpty()) return ""
        val builder = StringBuilder(template.length)
        var i = 0
        while (i < template.length) {
            val ch = template[i]
            if (ch == '$' && i + 1 < template.length) {
                var j = i + 1
                while (j < template.length && template[j].isDigit()) {
                    j++
                }
                if (j > i + 1) {
                    val groupIndex = template.substring(i + 1, j).toInt()
                    builder.append(runCatching { match.groups[groupIndex]?.value.orEmpty() }.getOrDefault(""))
                    i = j
                    continue
                }
            }
            builder.append(ch)
            i++
        }
        return builder.toString()
    }

    private class CompiledRule(
        val source: FilterRule,
        val main: Regex,
        val unless: Regex?,
    )

    private fun compile(pattern: String, isRegex: Boolean, caseSensitive: Boolean): Regex? {
        if (pattern.isBlank()) return null
        val effective = if (isRegex) {
            pattern
        } else {
            Regex.escape(pattern)
        }
        return runCatching {
            if (caseSensitive) effective.toRegex() else effective.toRegex(RegexOption.IGNORE_CASE)
        }.getOrNull()
    }

    companion object {
        private const val MAX_LINE = 4000
        private const val MAX_HITS = 40
    }
}


/**
 * 过滤框里的查询词（对标原版 Grep Console 的 Expression 行）：空格分隔全部命中；`/…/` 包起来时按正则。
 *
 * @param caseSensitive 对标原版 Match Case：默认忽略大小写
 * @param forceRegex    对标原版 Regex 勾选：勾上后整个表达式都按正则编译（不必再写 `/…/`）
 * @param unless        对标原版 Unless：命中主表达式的行里，再命中这个表达式的会被排除
 */
class FilterQuery(
    val pattern: String,
    val caseSensitive: Boolean = false,
    val forceRegex: Boolean = false,
    val unless: String = "",
) {

    private val regex: Regex? = buildRegex(pattern, caseSensitive, forceRegex)

    private val words: List<String> = if (regex != null) {
        emptyList()
    } else {
        val p = if (caseSensitive) pattern.trim() else pattern.trim().lowercase()
        p.split(Regex("\\s+")).filter { it.isNotEmpty() }
    }

    private val unlessQuery: FilterQuery? = if (unless.isBlank()) {
        null
    } else {
        FilterQuery(unless, caseSensitive, forceRegex)
    }

    fun matches(text: String): Boolean {
        if (!matchCore(text)) {
            return false
        }
        val u = unlessQuery
        if (u != null && u.matchCore(text)) {
            return false
        }
        return true
    }

    private fun matchCore(text: String): Boolean {
        val rx = regex
        if (rx != null) {
            return rx.containsMatchIn(text)
        }
        if (words.isEmpty()) {
            return false
        }
        val subject = if (caseSensitive) text else text.lowercase()
        return words.all { subject.contains(it) }
    }

    /**
     * 表达式在一行里的命中区间，供 Grep 子控制台把关键词片段单独着色。
     * 与 [matches] 用同一套语义：正则模式取所有匹配，多关键字模式逐词找全部出现位置；
     * Unless 命中的行不着色。坏正则 / 超长行 / 命中过多都做上限收敛，不能拖慢控制台。
     */
    fun ranges(text: String): List<Span> {
        if (text.isEmpty() || !matches(text)) return emptyList()
        val subject = text.substring(0, minOf(text.length, MAX_HIGHLIGHT_LENGTH))
        val result = ArrayList<Span>()
        val rx = regex
        if (rx != null) {
            try {
                for (match in rx.findAll(subject)) {
                    val span = Span(match.range.first, match.range.last + 1)
                    if (span.start < span.end) result += span
                    if (result.size >= MAX_HIGHLIGHT_HITS) break
                }
            } catch (_: RuntimeException) {
                return emptyList()
            }
        } else {
            val haystack = if (caseSensitive) subject else subject.lowercase()
            for (word in words) {
                if (word.isEmpty()) continue
                var from = 0
                while (result.size < MAX_HIGHLIGHT_HITS) {
                    val at = haystack.indexOf(word, from)
                    if (at < 0) break
                    result += Span(at, at + word.length)
                    from = at + word.length
                }
                if (result.size >= MAX_HIGHLIGHT_HITS) break
            }
        }
        return result
    }

    companion object {
        private const val MAX_HIGHLIGHT_LENGTH = 4000
        private const val MAX_HIGHLIGHT_HITS = 40

        private fun buildRegex(pattern: String, caseSensitive: Boolean, forceRegex: Boolean): Regex? {
            val p = pattern.trim()
            val body = if (forceRegex) {
                p
            } else if (p.length >= 3 && p.startsWith('/') && p.endsWith('/')) {
                p.substring(1, p.length - 1)
            } else {
                return null
            }
            if (body.isEmpty()) {
                return null
            }
            return runCatching {
                if (caseSensitive) body.toRegex() else body.toRegex(RegexOption.IGNORE_CASE)
            }.getOrNull()
        }
    }
}

/** Grep 子控制台的一条表达式预设（IDE 级持久化，纯数据类，XmlSerializer 可序列化） */
data class GrepExpressionPreset(
    var name: String = "",
    var expression: String = "",
    var unless: String = "",
    var caseSensitive: Boolean = false,
    var regex: Boolean = false,
) {
    fun label(): String = name.trim().ifEmpty { expression.trim() }
}

/** 表达式预设与「最近使用」的纯逻辑：去重、裁剪、非法项清理，不碰平台 API */
object GrepExpressionHistory {
    const val MAX_RECENT = 20
    const val MAX_PRESETS = 50

    /** 把 expression 提到最前，重复的旧项删掉，最多留 [MAX_RECENT] 条 */
    fun record(existing: List<String>, expression: String): List<String> {
        val value = expression.trim()
        if (value.isEmpty()) return existing.toList()
        return (listOf(value) + existing.filter { it != value }).take(MAX_RECENT)
    }

    /** 同名预设覆盖（新项排最前），名字为空或表达式为空直接忽略 */
    fun upsert(
        existing: List<GrepExpressionPreset>,
        preset: GrepExpressionPreset,
    ): List<GrepExpressionPreset> {
        val name = preset.name.trim()
        if (name.isEmpty() || preset.expression.isBlank()) return existing.toList()
        val kept = existing.filterNot { it.name.equals(name, ignoreCase = true) }
        return (listOf(preset.copy(name = name)) + kept).take(MAX_PRESETS)
    }

    /** 外部改坏 xml / 旧配置缺字段时清理：去空、同名去重、限长 */
    fun normalizePresets(existing: List<GrepExpressionPreset>): List<GrepExpressionPreset> = existing
        .mapNotNull { preset ->
            val name = preset.name.trim()
            if (name.isEmpty() || preset.expression.isBlank()) null else preset.copy(name = name)
        }
        .distinctBy { it.name.lowercase() }
        .take(MAX_PRESETS)

    fun normalizeRecent(existing: List<String>): List<String> = existing
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinct()
        .take(MAX_RECENT)
}

/**
 * 块级过滤。平台给 InputFilter 的文本可能是一次 flush 的多行、也可能是半行，
 * 所以这里按行判定，末尾没有换行的那一段**一律保留**（不猜它是不是完整行），
 * 既不丢日志，也不会把跨块的堆栈行错拼。
 */
object LogBlockFilter {

    data class Outcome(val text: String, val changed: Boolean, val kept: Int, val dropped: Int)

    fun filter(text: String, mode: ConsoleFilterMode, query: FilterQuery): Outcome {
        if (mode == ConsoleFilterMode.OFF || text.isEmpty()) {
            return Outcome(text, false, 0, 0)
        }
        val lines = splitKeepingNewline(text)
        val parser = LogLineParser()
        val keptText = StringBuilder()
        var kept = 0
        var dropped = 0
        var lastKeptError = false

        lines.forEachIndexed { index, (content, newline) ->
            val unfinished = newline.isEmpty() && index == lines.lastIndex && lines.size > 1
            val line = parser.parse(content)
            val keep = when {
                unfinished -> true
                mode == ConsoleFilterMode.ERRORS_ONLY -> line.level == LogLevel.ERROR ||
                    line.continuation && lastKeptError
                mode == ConsoleFilterMode.WARN_AND_ABOVE -> line.level.rank >= LogLevel.WARN.rank ||
                    line.continuation && lastKeptError
                mode == ConsoleFilterMode.MATCH_ONLY -> query.matches(content)
                mode == ConsoleFilterMode.HIDE_MATCH -> !query.matches(content)
                else -> true
            }
            if (keep) {
                keptText.append(content).append(newline)
                kept++
                if (!line.continuation) lastKeptError = line.level == LogLevel.ERROR
            } else {
                dropped++
            }
        }

        val changed = dropped > 0
        return Outcome(if (changed) keptText.toString() else text, changed, kept, dropped)
    }

    /**
     * 规则链过滤（对照原版插件的 Filtering 表）：逐行跑 [FilterRule]，
     * REMOVE / REMOVE_UNLESS_MATCHED 丢行，REPLACE 改写文本。
     * 与按模式过滤一样，末尾没有换行的半行一律保留。
     */
    fun filter(text: String, rules: List<FilterRule>): Outcome {
        if (text.isEmpty() || rules.none { it.enabled && it.pattern.isNotBlank() }) {
            return Outcome(text, false, 0, 0)
        }
        val ruleFilter = LogRuleFilter(rules)
        val lines = splitKeepingNewline(text)
        val keptText = StringBuilder()
        var kept = 0
        var dropped = 0
        var changed = false
        lines.forEachIndexed { index, (content, newline) ->
            val unfinished = newline.isEmpty() && index == lines.lastIndex && lines.size > 1
            if (unfinished) {
                keptText.append(content)
                kept++
                return@forEachIndexed
            }
            val decision = ruleFilter.decide(content)
            if (decision.excluded) {
                dropped++
            } else {
                keptText.append(decision.text).append(newline)
                kept++
                if (decision.changed) {
                    changed = true
                }
            }
        }
        if (dropped == 0 && !changed) {
            return Outcome(text, false, kept, 0)
        }
        return Outcome(keptText.toString(), true, kept, dropped)
    }

    /** 只按 \n 切，\r 留在内容里，拼回去时逐字还原 */
    private fun splitKeepingNewline(text: String): List<Pair<String, String>> {
        val result = ArrayList<Pair<String, String>>()
        var from = 0
        while (from <= text.length) {
            val nl = text.indexOf('\n', from)
            if (nl < 0) {
                if (from < text.length) result += text.substring(from) to ""
                break
            }
            result += text.substring(from, nl) to "\n"
            from = nl + 1
        }
        return result
    }
}

/**
 * Grep 子控制台命中片段底色的配置值（纯逻辑，方便离线校验与单测）。
 * 沿用设置页的 `浅色hex|深色hex` 约定；非法 / 空值一律回退默认色，避免手滑填错导致高亮静默消失。
 */
object GrepHighlightColor {
    const val DEFAULT = "#FFE9A8|#5A4700"

    private val HEX = Regex("#?[0-9a-fA-F]{6}")

    fun normalize(spec: String?): String {
        if (spec.isNullOrBlank()) return DEFAULT
        val parts = spec.split('|').map { it.trim() }
        val light = parts.getOrNull(0)?.takeIf { HEX.matches(it) } ?: return DEFAULT
        // 没写第二段 = 两主题共用；写了但写错 = 整项回退默认，不静默变成单色
        val second = parts.getOrNull(1)
        val dark = when {
            second == null -> light
            HEX.matches(second) -> second
            else -> return DEFAULT
        }
        return "${canonical(light)}|${canonical(dark)}"
    }

    /**
     * 色板选出的颜色转成 `浅色|深色`。深色主题用亮度压到 30% 的一份，
     * 亮色主题选了浅黄底时深色主题不会晃眼；与控制台「添加高亮」规则共用同一套换算。
     */
    fun fromColor(color: Color): String {
        fun hex(c: Color) = String.format("#%02X%02X%02X", c.red, c.green, c.blue)
        fun dim(value: Int) = (value * 0.30).toInt().coerceIn(0, 255)
        val dark = Color(dim(color.red), dim(color.green), dim(color.blue))
        return "${hex(color)}|${hex(dark)}"
    }

    private fun canonical(hex: String): String = "#" + hex.removePrefix("#").uppercase()
}
