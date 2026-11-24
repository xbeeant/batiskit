package io.github.xbeeant.batiskit.rainbow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 括号配对 / 作用域查询的纯逻辑回归；同时保证 analyze 没有改变老 compute 的行为 */
class BracePairsTest {

    private fun analysis(text: String, count: Int = 5): BraceLevels.Analysis =
        BraceLevels.analyze(BraceLevels.fromText(text), count)

    @Test
    fun `analyze 的颜色视图与 compute 完全一致`() {
        val text = "a(b[c{d}e]f) ([)] )"
        val braces = BraceLevels.fromText(text)
        assertEquals(BraceLevels.compute(braces, 5), analysis(text).colors)
        assertEquals(BraceLevels.compute(braces, 3), analysis(text, 3).colors)
    }

    @Test
    fun `配对关系双向可查`() {
        val a = analysis("([])")
        assertEquals(3, a.pairs[0])
        assertEquals(0, a.pairs[3])
        assertEquals(2, a.pairs[1])
        assertEquals(1, a.pairs[2])
        assertEquals(3, BracePairs.partner(a, 0))
        assertEquals(0, BracePairs.partner(a, 3))
    }

    @Test
    fun `作用域区间按开括号生成顺序`() {
        val a = analysis("([])")
        assertEquals(
            listOf(BraceLevels.Scope(0, 3, 0), BraceLevels.Scope(1, 2, 1)),
            a.scopes,
        )
    }

    @Test
    fun `失配的括号不产生配对与作用域`() {
        val a = analysis("([)] )")
        assertEquals(1, a.scopes.size)
        assertEquals(BraceLevels.Scope(0, 2, 0), a.scopes.first())
        assertNull(a.pairs[1])
        assertNull(a.pairs[3])
        assertNull(a.pairs[5])
        assertNull(BracePairs.partner(a, 5))
    }

    @Test
    fun `光标在括号内部能拿到从内到外的作用域`() {
        // 偏移: 0 f | 1 ( | 2 g | 3 ( | 4 h | 5 ( | 6 ) | 7 ) | 8 , | 9 空格 | 10 x | 11 )
        val a = analysis("f(g(h()), x)")
        val inner = BraceLevels.Scope(3, 7, 1)
        val outer = BraceLevels.Scope(1, 11, 0)
        assertEquals(listOf(inner, outer), BracePairs.enclosingAll(a, 4))
        assertEquals(inner, BracePairs.enclosing(a, 4))
        assertEquals(inner, BracePairs.scopeAt(a, 4, 0))
        assertEquals(outer, BracePairs.scopeAt(a, 4, 1))
        // 超出层数后回到最内层，供「重复触发」循环扩展
        assertEquals(inner, BracePairs.scopeAt(a, 4, 2))
    }

    @Test
    fun `光标正好在括号上时包含最内层自作用域`() {
        val a = analysis("f(g(h()), x)")
        assertEquals(
            listOf(
                BraceLevels.Scope(5, 6, 2),
                BraceLevels.Scope(3, 7, 1),
                BraceLevels.Scope(1, 11, 0),
            ),
            BracePairs.enclosingAll(a, 5),
        )
        assertEquals(BraceLevels.Scope(5, 6, 2), BracePairs.enclosing(a, 5))
        assertEquals(11, BracePairs.partner(a, 1))
    }

    @Test
    fun `光标在括号外或非括号偏移不产生作用域`() {
        val a = analysis("f(g(h()), x)")
        assertNull(BracePairs.enclosing(a, 0))
        assertTrue(BracePairs.enclosingAll(a, 0).isEmpty())
        assertNull(BracePairs.scopeAt(a, 0, 3))
    }

    @Test
    fun `没有配对时查询全部为空`() {
        val a = analysis("plain text without braces")
        assertTrue(a.scopes.isEmpty())
        assertTrue(a.pairs.isEmpty())
        assertTrue(a.colors.isEmpty())
        assertNull(BracePairs.enclosing(a, 3))
    }

    @Test
    fun `非法颜色数在 analyze 里同样收敛而不除零`() {
        val a = analysis("((()))", 0)
        assertEquals(BraceLevels.compute(BraceLevels.fromText("((()))"), 1), a.colors)
        assertEquals(3, a.scopes.size)
    }
}
