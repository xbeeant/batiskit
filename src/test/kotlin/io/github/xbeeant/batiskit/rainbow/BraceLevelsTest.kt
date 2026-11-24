package io.github.xbeeant.batiskit.rainbow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 彩虹括号层级计算的纯逻辑测试（不需要 IDE 夹具）。
 */
class BraceLevelsTest {

    private fun colors(text: String, count: Int = RainbowColors.COUNT) =
        BraceLevels.compute(BraceLevels.fromText(text), count)

    @Test
    fun `嵌套括号按层数取色且配对同色`() {
        val text = "(outer(inner)tail)"
        val result = colors(text)
        assertEquals(0, result[0])                 // 最外层 (
        assertEquals(1, result[6])                 // 内层 (
        assertEquals(1, result[12])                // 内层 )
        assertEquals(0, result[text.length - 1])  // 最外层 )
    }

    @Test
    fun `三对括号混用时按同一个栈算深度`() {
        val text = "([{}])"
        val result = colors(text)
        assertEquals(0, result[0])
        assertEquals(1, result[1])
        assertEquals(2, result[2])
        assertEquals(2, result[3])
        assertEquals(1, result[4])
        assertEquals(0, result[5])
    }

    @Test
    fun `颜色数决定循环周期`() {
        val text = "((((("
        val result = colors(text, 3)
        assertEquals(listOf(0, 1, 2, 0, 1), (0 until text.length).map { result[it] })
    }

    @Test
    fun `颜色数为 1 时所有层同色`() {
        val result = colors("({[]})", 1)
        assertTrue(result.values.all { it == 0 })
        assertEquals(6, result.size)
    }

    @Test
    fun `未配对的闭括号不着色`() {
        val result = colors(")(")
        assertNull(result[0])
        assertEquals(0, result[1])
    }

    @Test
    fun `交错失配时丢弃中间开括号并给能配上的闭括号上色`() {
        // ( [ ) ]：闭 ) 向栈内找到最外层的 (，顺手丢掉中间的 [
        val result = colors("([)]")
        assertEquals(0, result[0])
        assertEquals(1, result[1])
        assertEquals(0, result[2])
        assertNull(result[3])
    }

    @Test
    fun `尖括号不属于通用括号集合`() {
        assertFalse(BraceLevels.isBrace('<'))
        assertFalse(BraceLevels.isBrace('>'))
        assertTrue(BraceLevels.isBrace('('))
        assertNull(BraceLevels.openerOf('('))
        assertEquals('(', BraceLevels.openerOf(')'))
    }

    @Test
    fun `fromText 保留绝对偏移`() {
        val text = "a = (b + [c]);"
        val braces = BraceLevels.fromText(text)
        assertEquals(listOf(4, 9, 11, 12), braces.map { it.offset })
        assertEquals("([])", braces.map { it.char }.joinToString(""))
    }

    @Test
    fun `非法颜色数收敛到 1 而不是除零`() {
        val result = colors("()", 0)
        assertEquals(0, result[0])
        assertEquals(0, result[1])
    }
}
