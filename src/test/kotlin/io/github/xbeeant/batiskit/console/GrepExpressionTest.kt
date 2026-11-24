package io.github.xbeeant.batiskit.console

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Grep 表达式预设 / 最近历史与命中片段区间的纯逻辑测试 */
class GrepExpressionTest {

    @Test
    fun `record 把新表达式提到最前并去重`() {
        val result = GrepExpressionHistory.record(listOf("foo", "bar", "baz"), "bar")
        assertEquals(listOf("bar", "foo", "baz"), result)
    }

    @Test
    fun `record 忽略空表达式且加到最前`() {
        val existing = listOf("a", "b")
        assertEquals(existing, GrepExpressionHistory.record(existing, "   "))
        assertEquals(listOf("new", "a", "b"), GrepExpressionHistory.record(existing, " new "))
    }

    @Test
    fun `record 裁剪到上限`() {
        val existing = (1..30).map { "e$it" }
        val result = GrepExpressionHistory.record(existing, "new")
        assertEquals(GrepExpressionHistory.MAX_RECENT, result.size)
        assertEquals("new", result.first())
    }

    @Test
    fun `upsert 同名覆盖并把新项排最前`() {
        val existing = listOf(
            GrepExpressionPreset("旧", "old"),
            GrepExpressionPreset("错误", "error", regex = true),
        )
        val result = GrepExpressionHistory.upsert(
            existing, GrepExpressionPreset(" 错误 ", "ERROR", unless = "health", regex = true)
        )
        assertEquals(2, result.size)
        assertEquals("错误", result[0].name)
        assertEquals("ERROR", result[0].expression)
        assertEquals("health", result[0].unless)
        assertEquals("旧", result[1].name)
    }

    @Test
    fun `upsert 拒绝空名字与空表达式`() {
        val existing = listOf(GrepExpressionPreset("ok", "foo"))
        assertEquals(existing, GrepExpressionHistory.upsert(existing, GrepExpressionPreset("", "foo")))
        assertEquals(existing, GrepExpressionHistory.upsert(existing, GrepExpressionPreset("blank", "  ")))
    }

    @Test
    fun `normalize 清理空项与同名重复`() {
        val presets = listOf(
            GrepExpressionPreset(" a ", "foo"),
            GrepExpressionPreset("A", "foo2"),
            GrepExpressionPreset("", "orphan"),
            GrepExpressionPreset("blank", ""),
        )
        val result = GrepExpressionHistory.normalizePresets(presets)
        assertEquals(listOf("a"), result.map { it.name })

        val recent = GrepExpressionHistory.normalizeRecent(listOf(" x ", "", "x", "y"))
        assertEquals(listOf("x", "y"), recent)
    }

    @Test
    fun `state normalize 裁剪预设与最近记录`() {
        val state = GrepConsoleSettingsState()
        state.expressionPresets = (1..(GrepExpressionHistory.MAX_PRESETS + 10)).map {
            GrepExpressionPreset("p$it", "expr$it")
        }.toMutableList()
        state.recentExpressions = (1..(GrepExpressionHistory.MAX_RECENT + 10)).map { "r$it" }.toMutableList()
        state.normalize()
        assertEquals(GrepExpressionHistory.MAX_PRESETS, state.expressionPresets.size)
        assertEquals(GrepExpressionHistory.MAX_RECENT, state.recentExpressions.size)
    }

    @Test
    fun `ranges 给多关键字的每个出现位置生成区间`() {
        val line = "WARN timeout and TIMEOUT again"
        val spans = FilterQuery("warn timeout").ranges(line)
        assertEquals(3, spans.size)
        spans.forEach { span ->
            assertTrue(span.start >= 0 && span.start < span.end && span.end <= line.length)
        }
        assertEquals("WARN", line.substring(spans[0].start, spans[0].end))
        assertEquals("timeout", line.substring(spans[1].start, spans[1].end))
        assertEquals("TIMEOUT", line.substring(spans[2].start, spans[2].end))
    }

    @Test
    fun `ranges 支持正则与斜杠包裹的正则`() {
        val line = "id=12 id=345"
        assertEquals(listOf(0, 6), FilterQuery("/id=\\d+/").ranges(line).map { it.start })
        assertEquals(listOf(3, 9), FilterQuery("\\d+", forceRegex = true).ranges(line).map { it.start })
        assertEquals(2, FilterQuery("\\d+", forceRegex = true).ranges(line).size)
    }

    @Test
    fun `ranges 在 Unless 命中或没匹配时返回空`() {
        val line = "ERROR timeout in payment"
        assertTrue(FilterQuery("timeout", unless = "payment").ranges(line).isEmpty())
        assertTrue(FilterQuery("missing").ranges(line).isEmpty())
        assertTrue(FilterQuery("timeout").ranges("").isEmpty())
    }

    @Test
    fun `ranges 坏正则不抛异常`() {
        assertTrue(FilterQuery("/[/").ranges("some text").isEmpty())
        assertTrue(FilterQuery("[", forceRegex = true).ranges("some text").isEmpty())
    }

    @Test
    fun `ranges 区分大小写`() {
        val line = "Error ERROR error"
        assertEquals(1, FilterQuery("ERROR", caseSensitive = true).ranges(line).size)
        assertEquals(3, FilterQuery("ERROR").ranges(line).size)
    }

    @Test
    fun `ranges 命中过多时有上限`() {
        val line = "x".repeat(100)
        assertTrue(FilterQuery("x").ranges(line).size <= 40)
    }
}
