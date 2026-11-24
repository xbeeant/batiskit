package io.github.xbeeant.batiskit.string

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Alt+Enter 的作用范围推断：纯逻辑，不需要起 IDE。 */
class StringRangesTest {

    private fun first(line: String, caret: Int): StringSpan = StringRanges.spans(line, caret).first()

    private fun text(line: String, span: StringSpan): String = line.substring(span.start, span.end)

    @Test
    fun `光标在字段声明的标识符上只取这个词`() {
        val line = "private String user_name;"
        val caret = line.indexOf("user_name") + 3
        val span = first(line, caret)
        assertEquals("user_name", text(line, span))
        assertTrue(span.label.startsWith("当前单词"))
        assertEquals(line, text(line, StringRanges.spans(line, caret).last()))
    }

    @Test
    fun `紧贴单词右边界也算在这个词内`() {
        val line = "val fooBar = 1"
        val end = line.indexOf("fooBar") + "fooBar".length
        assertEquals("fooBar", text(line, first(line, end)))
        assertEquals("fooBar", text(line, first(line, line.indexOf("fooBar"))))
    }

    @Test
    fun `光标在空白上没有单词范围只剩整行`() {
        val line = "  int a = 1;"
        val spans = StringRanges.spans(line, 1)
        assertEquals(1, spans.size)
        assertTrue(spans[0].label.startsWith("当前行"))
        assertEquals(line, text(line, spans[0]))
    }

    @Test
    fun `空行没有可选范围`() {
        assertTrue(StringRanges.spans("", 0).isEmpty())
    }

    @Test
    fun `光标在字符串里默认取整段内容且不含引号`() {
        val line = "String s = \"hello world\";"
        val caret = line.indexOf("hello") + 2
        val spans = StringRanges.spans(line, caret)
        assertEquals("hello world", text(line, spans[0]))
        assertTrue(spans[0].label.startsWith("字符串内容"))
        assertEquals("hello", text(line, spans[1]))
    }

    @Test
    fun `字符串内部的单引号不会把内容切开`() {
        val line = "String s = \"it's ok\";"
        val caret = line.indexOf("it's") + 2
        assertEquals("it's ok", text(line, first(line, caret)))
    }

    @Test
    fun `转义后的引号不算边界`() {
        val line = "String s = \"say \\\"hi\\\" now\";"
        val caret = line.indexOf("now")
        assertEquals("say \\\"hi\\\" now", text(line, first(line, caret)))
    }

    @Test
    fun `光标在两个字符串之间不会把中间代码当成字符串内容`() {
        val line = "String s = \"a\" + \"b\";"
        val caret = line.indexOf("+")
        val spans = StringRanges.spans(line, caret)
        assertFalse(spans.any { it.label.startsWith("字符串内容") })
        // `+` 不是词字符，剩下最合理的范围就是整行
        assertTrue(spans[0].label.startsWith("当前行"))
        assertEquals(line, text(line, spans[0]))
    }

    @Test
    fun `未闭合的字符串退回单词与整行`() {
        val line = "String s = \"abc"
        val caret = line.length
        val spans = StringRanges.spans(line, caret)
        assertFalse(spans.any { it.label.startsWith("字符串内容") })
        assertEquals("abc", text(line, spans.first { it.label.startsWith("当前单词") }))
    }

    @Test
    fun `连字符点号与中文都算一个词`() {
        assertEquals("user-name", text("x = user-name;", first("x = user-name;", 7)))
        assertEquals("user.name", text("x = user.name;", first("x = user.name;", 7)))
        assertEquals("用户名称", text("x = 用户名称;", first("x = 用户名称;", 5)))
    }

    @Test
    fun `重复范围只出现一次`() {
        val line = "abc"
        val spans = StringRanges.spans(line, 1)
        assertEquals(1, spans.size) // 单词与整行都是 0..3，保留先加入的那一项
        assertTrue(spans[0].label.startsWith("当前单词"))
    }

    @Test
    fun `光标越界会被夹进这一行`() {
        val line = "abc"
        assertEquals("abc", text(line, first(line, 999)))
        assertEquals("abc", text(line, first(line, -5)))
    }
}
