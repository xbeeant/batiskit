package io.github.xbeeant.batiskit.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [UiText.ellipsizeBy] 的纯 JVM 测试：窄宽度下列表行必须用省略号而不是硬切字符。
 * 只调与 AWT 解耦的测量函数版本，避免加载任何 IntelliJ 平台类。
 */
class UiTextTest {

    private val fixedWidth: (String) -> Int = { it.length * 10 }

    @Test
    fun `放得下时原样返回`() {
        assertEquals("GET /api/orders", UiText.ellipsizeBy("GET /api/orders", 200, fixedWidth))
    }

    @Test
    fun `刚好等于可用宽度时不截断`() {
        assertEquals("ABCDEFGHIJ", UiText.ellipsizeBy("ABCDEFGHIJ", 100, fixedWidth))
    }

    @Test
    fun `超宽时以省略号收尾且测量宽度不越界`() {
        val text = "com.example.order.controller.OrderController#listByUser"
        val maxWidth = 210
        val result = UiText.ellipsizeBy(text, maxWidth, fixedWidth)
        assertTrue(result.endsWith("…"))
        assertTrue(result.length < text.length)
        assertTrue(fixedWidth(result) <= maxWidth)
        assertTrue(text.startsWith(result.removeSuffix("…")))
    }

    @Test
    fun `二分取到最长可容纳前缀`() {
        // 每字符 10，省略号 10：可用 65 → 最多 5 个字符 + 省略号 = 60
        assertEquals("ABCDE…", UiText.ellipsizeBy("ABCDEFGHIJ", 65, fixedWidth))
    }

    @Test
    fun `空文本与非正宽度返回空串`() {
        assertEquals("", UiText.ellipsizeBy("", 100, fixedWidth))
        assertEquals("", UiText.ellipsizeBy("abc", 0, fixedWidth))
        assertEquals("", UiText.ellipsizeBy("abc", -10, fixedWidth))
    }

    @Test
    fun `连省略号都放不下时只给省略号`() {
        assertEquals("…", UiText.ellipsizeBy("abcdef", 8, fixedWidth))
    }

    @Test
    fun `按测量宽度而非字符数截断`() {
        // 中文按 20、英文按 10 计宽，验证变宽字体下也不会越界
        val cjkWidth: (String) -> Int = { text ->
            text.fold(0) { width, char -> width + if (char.code > 0x7F) 20 else 10 }
        }
        val result = UiText.ellipsizeBy("订单接口OrderController#list", 150, cjkWidth)
        assertTrue(result.endsWith("…"))
        assertTrue(cjkWidth(result) <= 150)
    }
}
