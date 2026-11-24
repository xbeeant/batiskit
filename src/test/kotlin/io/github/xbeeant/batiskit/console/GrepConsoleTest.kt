package io.github.xbeeant.batiskit.console

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Grep Console 的纯逻辑测试：级别识别 / 高亮切段 / 过滤，不起 IDE 夹具。
 */
class GrepConsoleTest {

    private val colors: Map<LogLevel, String> = LogLevel.entries.associateWith {
        GrepConsoleSettingsState.defaultLevelColors()[it.name].orEmpty()
    }

    // ------------------------------------------------------------------ 级别识别

    @Test
    fun `识别 Spring Boot 默认格式`() {
        val line = LogLineParser().parse("2026-11-05 22:33:34.123  INFO 12345 --- [main] c.e.Demo : Started")
        assertEquals(LogLevel.INFO, line.level)
        assertEquals("INFO", line.text.substring(line.levelRange!!.start, line.levelRange.end))
    }

    @Test
    fun `识别 ISO 时间戳与 log4j 的线程名格式`() {
        val iso = LogLineParser().parse("2026-11-05T22:33:34.123+08:00  WARN 12 --- [nio-8080] c.e.Demo : slow")
        assertEquals(LogLevel.WARN, iso.level)
        val log4j = LogLineParser().parse("22:33:34.123 [main] ERROR c.e.Demo - boom")
        assertEquals(LogLevel.ERROR, log4j.level)
    }

    @Test
    fun `识别行首方括号级别与管道分隔级别`() {
        assertEquals(LogLevel.ERROR, LogLineParser().parse("[ERROR] failed to start").level)
        assertEquals(LogLevel.WARN, LogLineParser().parse("WARN: disk almost full").level)
        assertEquals(LogLevel.INFO, LogLineParser().parse("22:33:34 | INFO | request done").level)
    }

    @Test
    fun `级别别名归一`() {
        assertEquals(LogLevel.WARN, LogLevel.of("WARNING"))
        assertEquals(LogLevel.ERROR, LogLevel.of("FATAL"))
        assertEquals(LogLevel.ERROR, LogLevel.of("SEVERE"))
        assertEquals(LogLevel.INFO, LogLevel.of("NOTICE"))
        assertEquals(LogLevel.TRACE, LogLevel.of("VERBOSE"))
    }

    @Test
    fun `正文里的大写 ERROR 不会被当成级别`() {
        val line = LogLineParser().parse("2026-11-05 22:33:34 INFO 1 --- [main] c.e.Demo : gateway returned ERROR from upstream")
        assertEquals(LogLevel.INFO, line.level)
        assertEquals("INFO", line.text.substring(line.levelRange!!.start, line.levelRange.end))
    }

    @Test
    fun `小写 error 与无级别行不着色`() {
        assertEquals(LogLevel.OTHER, LogLineParser().parse("something went error here").level)
        assertEquals(LogLevel.OTHER, LogLineParser().parse("just a plain stdout line").level)
    }

    @Test
    fun `堆栈续行继承上一条 ERROR`() {
        val parser = LogLineParser()
        assertEquals(LogLevel.ERROR, parser.parse("2026-11-05 22:33:34 ERROR 1 --- [main] c.e.Demo : boom").level)
        val stack = parser.parse("\tat c.e.Demo.run(Demo.java:42)")
        assertEquals(LogLevel.ERROR, stack.level)
        assertTrue(stack.continuation)
        assertEquals(LogLevel.ERROR, parser.parse("Caused by: java.lang.NullPointerException: npe").level)
    }

    @Test
    fun `缩进的普通输出不继承级别`() {
        val parser = LogLineParser()
        parser.parse("[INFO] hello")
        assertEquals(LogLevel.OTHER, parser.parse("   user input without level").level)
    }

    // ------------------------------------------------------------------ 关键词高亮

    @Test
    fun `字面量规则区分大小写与忽略大小写`() {
        val strict = HighlightRule(pattern = "Exception", caseSensitive = true)
        assertTrue(strict.ranges("java.lang.Exception: boom").isNotEmpty())
        assertTrue(strict.ranges("exception swallowed").isEmpty())
        val loose = HighlightRule(pattern = "exception")
        assertEquals(2, loose.ranges("Exception and exception").size)
    }

    @Test
    fun `正则规则与坏正则`() {
        val rule = HighlightRule(pattern = "\\b(timeout|timed out)\\b", regex = true)
        assertEquals(1, rule.ranges("request TIMEOUT after 3s").size)
        assertTrue(HighlightRule(pattern = "[unclosed", regex = true).ranges("anything").isEmpty())
        assertTrue(HighlightRule(pattern = "", regex = true).ranges("anything").isEmpty())
        assertTrue(HighlightRule(pattern = "x", enabled = false).ranges("x").isEmpty())
    }

    @Test
    fun `切段覆盖整行且互不重叠`() {
        val line = LogLineParser().parse("2026-11-05 22:33:34 ERROR 1 --- [main] c.e.Demo : timeout while calling")
        val segments = LogHighlighter.segments(
            line, listOf(HighlightRule(pattern = "timeout", color = "#111111|#EEEEEE")), colors
        )
        assertTrue(segments.isNotEmpty())
        assertEquals(0, segments.first().start)
        assertEquals(line.text.length, segments.last().end)
        segments.zipWithNext().forEach { (a, b) -> assertEquals(a.end, b.start) }
        assertTrue(segments.any { line.text.substring(it.start, it.end) == "timeout" && it.foreground == "#111111|#EEEEEE" })
        assertTrue(segments.any { it.foreground == colors[LogLevel.ERROR] })
    }

    @Test
    fun `先声明的规则优先占区间`() {
        val line = LogLine("abc timeout")
        val first = HighlightRule(pattern = "abc timeout", color = "#111111|#111111")
        val second = HighlightRule(pattern = "timeout", color = "#222222|#222222")
        val segments = LogHighlighter.segments(line, listOf(first, second), colors, colorizeLevel = false)
        assertEquals(listOf(HighlightSegment(0, 11, "#111111|#111111", null, true)), segments)
    }

    @Test
    fun `关闭级别着色后只留关键词段`() {
        val line = LogLineParser().parse("[INFO] timeout")
        val segments = LogHighlighter.segments(
            line, listOf(HighlightRule(pattern = "timeout", color = "#111111|#111111")), colors,
            colorizeLevel = false
        )
        assertEquals(1, segments.size)
        assertEquals("timeout", line.text.substring(segments[0].start, segments[0].end))
    }

    // ------------------------------------------------------------------ 过滤

    @Test
    fun `查询词空格分隔全部命中 或按正则`() {
        assertTrue(FilterQuery("timeout order").matches("order service TIMEOUT"))
        assertFalse(FilterQuery("timeout order").matches("order service ok"))
        assertTrue(FilterQuery("/NullPointer\\w+/").matches("java.lang.NullPointerException"))
        assertFalse(FilterQuery("").matches("anything"))
    }

    @Test
    fun `FilterQuery 支持 Match Case 与显式 Regex`() {
        // Match Case：默认忽略大小写，勾上后区分
        assertTrue(FilterQuery("Boom", caseSensitive = true).matches("Boom NullPointerException"))
        assertFalse(FilterQuery("Boom", caseSensitive = true).matches("boom NullPointerException"))
        assertTrue(FilterQuery("Boom").matches("boom NullPointerException"))
        // Regex 勾选：整个表达式按正则编译，不必写 /…/
        assertTrue(FilterQuery("a\\d+", forceRegex = true).matches("line a123 tail"))
        assertFalse(FilterQuery("a\\d+", forceRegex = true).matches("line aaa tail"))
        // Match Case + Regex 组合
        assertTrue(FilterQuery("Boom\\w+", caseSensitive = true, forceRegex = true).matches("BoomPointer"))
        assertFalse(FilterQuery("Boom\\w+", caseSensitive = true, forceRegex = true).matches("boomyard"))
    }

    @Test
    fun `FilterQuery 的 Unless 排除例外行`() {
        // 命中主表达式但命中 Unless 的行被排除
        assertTrue(FilterQuery("error", unless = "swallowed").matches("error downstream"))
        assertFalse(FilterQuery("error", unless = "swallowed").matches("error swallowed upstream"))
        // Unless 不影响未命中主表达式的行
        assertFalse(FilterQuery("error", unless = "swallowed").matches("plain info line"))
        // Unless 与大小写开关联动
        assertFalse(FilterQuery("error", caseSensitive = true, unless = "Swallowed").matches("error Swallowed"))
        assertTrue(FilterQuery("error", caseSensitive = true, unless = "swallowed").matches("error Swallowed"))
    }

    @Test
    fun `只显示 ERROR 时保留异常堆栈`() {
        val text = "[INFO] starting\n[ERROR] boom\n\tat c.e.Demo.run(Demo.java:42)\n[DEBUG] retry\n"
        val out = LogBlockFilter.filter(text, ConsoleFilterMode.ERRORS_ONLY, FilterQuery(""))
        assertTrue(out.changed)
        assertTrue(out.text.contains("[ERROR] boom"))
        assertTrue(out.text.contains("\tat c.e.Demo.run"))
        assertFalse(out.text.contains("[INFO] starting"))
        assertEquals(2, out.kept)
        assertEquals(2, out.dropped)
    }

    @Test
    fun `WARN 及以上保留 WARN 与 ERROR`() {
        val text = "[INFO] a\n[WARN] b\n[ERROR] c\n[TRACE] d\n"
        val out = LogBlockFilter.filter(text, ConsoleFilterMode.WARN_AND_ABOVE, FilterQuery(""))
        assertTrue(out.text.contains("[WARN] b"))
        assertTrue(out.text.contains("[ERROR] c"))
        assertFalse(out.text.contains("[INFO] a"))
    }

    @Test
    fun `关键词模式只保留命中行 隐藏模式相反`() {
        val text = "[INFO] order ok\n[INFO] order timeout\n[INFO] user ok\n"
        val keep = LogBlockFilter.filter(text, ConsoleFilterMode.MATCH_ONLY, FilterQuery("timeout"))
        assertEquals("[INFO] order timeout\n", keep.text)
        val hide = LogBlockFilter.filter(text, ConsoleFilterMode.HIDE_MATCH, FilterQuery("timeout"))
        assertFalse(hide.text.contains("timeout"))
        assertTrue(hide.text.contains("order ok"))
    }

    @Test
    fun `关闭过滤时不干预文本`() {
        val text = "[ERROR] a\n[INFO] b\n"
        val out = LogBlockFilter.filter(text, ConsoleFilterMode.OFF, FilterQuery("a"))
        assertFalse(out.changed)
        assertEquals(text, out.text)
    }

    @Test
    fun `块末尾的半行一律保留`() {
        val text = "[INFO] noise\n[ERROR] real\n[INFO] unfinished"
        val out = LogBlockFilter.filter(text, ConsoleFilterMode.ERRORS_ONLY, FilterQuery(""))
        assertTrue(out.text.endsWith("[INFO] unfinished"))
        assertFalse(out.text.contains("noise"))
    }

    @Test
    fun `单行输入没有换行也照常过滤`() {
        val dropped = LogBlockFilter.filter("[INFO] noise", ConsoleFilterMode.ERRORS_ONLY, FilterQuery(""))
        assertTrue(dropped.changed)
        assertEquals("", dropped.text)
        val kept = LogBlockFilter.filter("[ERROR] boom", ConsoleFilterMode.ERRORS_ONLY, FilterQuery(""))
        assertFalse(kept.changed)
    }

    @Test
    fun `过滤后的文本逐字还原换行与回车`() {
        val text = "[INFO] a\r\n[ERROR] b\r\n"
        val out = LogBlockFilter.filter(text, ConsoleFilterMode.ERRORS_ONLY, FilterQuery(""))
        assertEquals("[ERROR] b\r\n", out.text)
    }

    // ------------------------------------------------------------------ 设置

    @Test
    fun `设置缺键时补齐 非法模式回退`() {
        val state = GrepConsoleSettingsState()
        state.levelColors.clear()
        state.filterMode = "NOT_A_MODE"
        state.maxEntries = 1
        state.normalize()
        assertEquals(GrepConsoleSettingsState.defaultLevelColors(), state.levelColors)
        assertEquals(ConsoleFilterMode.OFF, state.mode())
        assertEquals(100, state.maxEntries)
    }

    // ------------------------------------------------------------------ 过滤规则链（对照原版插件 Filtering 表）

    private val noRules: List<FilterRule> = emptyList()

    @Test
    fun `REMOVE 规则移除命中行`() {
        val rules = listOf(FilterRule(pattern = "DEBUG", action = FilterAction.REMOVE.name))
        val out = LogBlockFilter.filter("[INFO] a\n[DEBUG] noise\n[ERROR] b\n", rules)
        assertTrue(out.changed)
        assertTrue(out.text.contains("[INFO] a"))
        assertTrue(out.text.contains("[ERROR] b"))
        assertFalse(out.text.contains("noise"))
        assertEquals(2, out.kept)
        assertEquals(1, out.dropped)
    }

    @Test
    fun `字面量规则忽略大小写 不勾则大小写敏感`() {
        val loose = LogRuleFilter(listOf(FilterRule(pattern = "debug")))
        assertTrue(loose.decide("[DEBUG] noise").excluded)
        val strict = LogRuleFilter(listOf(FilterRule(pattern = "debug", caseSensitive = true)))
        assertFalse(strict.decide("[DEBUG] noise").excluded)
    }

    @Test
    fun `REMOVE_UNLESS_MATCHED 配合 NONE 规则实现只保留目标行`() {
        val rules = listOf(
            FilterRule(pattern = "\\b(?:pay|order)\\b", regex = true, action = FilterAction.NONE.name, continueMatching = true),
            FilterRule(pattern = ".*", regex = true, wholeLine = true, action = FilterAction.REMOVE_UNLESS_MATCHED.name),
        )
        val filter = LogRuleFilter(rules)
        assertTrue(filter.decide("2026-11-06 INFO pay done").matched)
        assertFalse(filter.decide("2026-11-06 INFO pay done").excluded)
        assertTrue(filter.decide("2026-11-06 INFO heartbeat").excluded)
        val out = LogBlockFilter.filter("INFO pay done\nINFO heartbeat\nERROR order failed\n", rules)
        assertEquals("INFO pay done\nERROR order failed\n", out.text)
    }

    @Test
    fun `unless 例外表达式让规则跳过指定行`() {
        val rules = listOf(
            FilterRule(pattern = "DEBUG", action = FilterAction.REMOVE.name, unless = "keep"),
        )
        val filter = LogRuleFilter(rules)
        assertTrue(filter.decide("[DEBUG] noisy").excluded)
        assertFalse(filter.decide("[DEBUG] please keep me").excluded)
    }

    @Test
    fun `整行匹配与子串匹配的差异`() {
        val whole = LogRuleFilter(listOf(FilterRule(pattern = "DEBUG", wholeLine = true, action = FilterAction.REMOVE.name)))
        assertFalse(whole.decide("[DEBUG] whole line must match").excluded)
        val partial = LogRuleFilter(listOf(FilterRule(pattern = "DEBUG", action = FilterAction.REMOVE.name)))
        assertTrue(partial.decide("[DEBUG] whole line must match").excluded)
    }

    @Test
    fun `REPLACE 按模板改写输出`() {
        val rules = listOf(
            FilterRule(
                pattern = "user id=(\\d+)",
                regex = true,
                action = FilterAction.REPLACE.name,
                replacement = "uid=$1",
            ),
        )
        val decision = LogRuleFilter(rules).decide("2026-11-06 INFO user id=12345 logged in")
        assertTrue(decision.changed)
        assertTrue(decision.text.contains("uid=12345"))
        assertFalse(decision.text.contains("user id=12345"))
        val out = LogBlockFilter.filter("INFO user id=42 ok\n", rules)
        assertEquals("INFO uid=42 ok\n", out.text)
    }

    @Test
    fun `REPLACE 模板留空删除命中片段`() {
        val rules = listOf(
            FilterRule(
                pattern = "\\[?DEBUG\\]?",
                regex = true,
                action = FilterAction.REPLACE.name,
                replacement = "",
            ),
        )
        val decision = LogRuleFilter(rules).decide("DEBUG noisy content")
        assertEquals(" noisy content", decision.text)
    }

    @Test
    fun `继续匹配让多条规则依次生效`() {
        val rules = listOf(
            FilterRule(pattern = "DEBUG", action = FilterAction.REMOVE.name, continueMatching = true),
            FilterRule(pattern = "noise", action = FilterAction.REMOVE.name),
        )
        val filter = LogRuleFilter(rules)
        // 第一条命中且继续匹配 → 第二条仍会评估
        assertTrue(filter.decide("[DEBUG] noise").excluded)
        assertTrue(filter.decide("[DEBUG] quiet").excluded)
        // 第一条没命中 → 停在第…实际上没命中就继续评估下一条
        assertTrue(filter.decide("[INFO] noise").excluded)
        assertFalse(filter.decide("[INFO] quiet").excluded)
    }

    @Test
    fun `命中即停 默认不评估后面的规则`() {
        val rules = listOf(
            FilterRule(pattern = "first", action = FilterAction.NONE.name),
            FilterRule(pattern = "second", action = FilterAction.REMOVE.name),
        )
        val filter = LogRuleFilter(rules)
        assertTrue(filter.decide("first second").matched)
        assertFalse(filter.decide("first second").excluded)
    }

    @Test
    fun `停用与空规则不干预 坏正则安全跳过`() {
        val out = LogBlockFilter.filter("[ERROR] a\n[INFO] b\n", noRules)
        assertFalse(out.changed)
        val bad = LogRuleFilter(listOf(FilterRule(pattern = "[unclosed", regex = true, action = FilterAction.REMOVE.name)))
        assertFalse(bad.decide("anything").excluded)
    }

    @Test
    fun `非法动作名回退到 REMOVE`() {
        val rules = listOf(FilterRule(pattern = "x", action = "NOT_AN_ACTION"))
        assertEquals(FilterAction.REMOVE, rules[0].actionType())
    }

    // ------------------------------------------------------------------ 高亮增强：整行与斜体

    @Test
    fun `整行规则要求整行匹配并给整行着色`() {
        val rule = HighlightRule(pattern = ".*timeout.*", regex = true, wholeLine = true, color = "#111111|#222222")
        assertTrue(rule.ranges("request timeout after 3s").size == 1)
        assertTrue(rule.ranges("timeout").size == 1)
        val noMatch = HighlightRule(pattern = "timeout", wholeLine = true)
        assertTrue(noMatch.ranges("request timeout").isEmpty())
    }

    @Test
    fun `斜体传递到着色段`() {
        val line = LogLine("timeout here")
        val segments = LogHighlighter.segments(
            line,
            listOf(HighlightRule(pattern = "timeout", color = "#111111|#111111", italic = true)),
            colors,
            colorizeLevel = false,
        )
        assertEquals(1, segments.size)
        assertTrue(segments[0].italic)
    }

    // ------------------------------------------------------------------ 自动链接 / 快速导航扫描

    @Test
    fun `识别 URL`() {
        val links = LogLinkScanner.scan("call ok see https://example.com/docs?x=1 for details")
        assertEquals(LogLinkScanner.Kind.URL, links.first().kind)
        assertEquals("https://example.com/docs?x=1", links.first().text)
    }

    @Test
    fun `识别项目相对路径与行号`() {
        val links = LogLinkScanner.scan("loaded src/main/kotlin/io/github/x/Demo.kt:42 ok")
        val link = links.first { it.kind == LogLinkScanner.Kind.FILE_PATH }
        assertTrue(link.text.startsWith("src/main/kotlin"))
        assertTrue(link.text.endsWith("Demo.kt:42"))
    }

    @Test
    fun `识别正文里的 FQN 类引用`() {
        val links = LogLinkScanner.scan("configured io.github.xbeeant.batiskit.console.GrepConsoleService")
        val link = links.first()
        assertEquals(LogLinkScanner.Kind.JAVA_CLASS, link.kind)
        assertEquals("io.github.xbeeant.batiskit.console.GrepConsoleService", link.text)
    }

    @Test
    fun `识别 FQN 方法引用`() {
        val links = LogLinkScanner.scan("check io.github.xbeeant.batiskit.console.LogRuleFilter.decide result")
        assertEquals(LogLinkScanner.Kind.JAVA_METHOD, links.first().kind)
        assertEquals("io.github.xbeeant.batiskit.console.LogRuleFilter.decide", links.first().text)
    }

    @Test
    fun `堆栈续行不做链接`() {
        assertTrue(LogLinkScanner.scan("\tat c.e.Demo.run(Demo.java:42)").isEmpty())
        assertTrue(LogLinkScanner.scan("Caused by: java.lang.NullPointerException").isEmpty())
    }

    @Test
    fun `链接区间互不重叠且路径优先`() {
        val links = LogLinkScanner.scan("see src/main/kotlin/Demo.kt and https://example.com/docs")
        links.zipWithNext().forEach { (a, b) -> assertTrue(a.end <= b.start) }
        assertEquals(LogLinkScanner.Kind.FILE_PATH, links[0].kind)
        assertEquals(LogLinkScanner.Kind.URL, links[1].kind)
    }

    // ------------------------------------------------------------------ 内置规则示例

    @Test
    fun `内置过滤规则示例默认停用`() {
        assertTrue(FilterRule.defaultRules().none { it.enabled })
    }
}
