package io.github.xbeeant.batiskit.rainbow

/**
 * 彩虹括号的层级计算（纯 Kotlin，不碰平台 API，可直接用 JUnit 断言）。
 *
 * 规则：
 * - 只认 `()` `[]` `{}` 三对；字符串与注释里的括号由调用方在收集 token 时排除（它们不是独立的 PSI 叶子）；
 * - 开括号的颜色下标 = 当前未闭合的括号层数 % 颜色数，配对的两个括号拿同一个下标；
 * - 遇到闭括号时向栈内找最近的同类型开括号，中间失配的开括号顺手丢掉（宁可少着色，也不把后面整片的层级算错）；
 * - 完全没有匹配开括号的闭括号不着色。
 *
 * [analyze] 在算颜色的同时留下配对关系与作用域区间，供「当前作用域高亮」使用；
 * [compute] 只是它的颜色视图，行为与早期版本逐字一致。
 */
object BraceLevels {

    data class Brace(val offset: Int, val char: Char)

    /** 一对成功配上的括号；[open] / [close] 都是文件内绝对偏移，[depth] 是开括号入栈时的层数。 */
    data class Scope(val open: Int, val close: Int, val depth: Int) {
        val length: Int get() = close - open + 1
        fun contains(offset: Int): Boolean = offset in open..close
    }

    data class Analysis(
        /** 绝对偏移 -> 颜色下标 */
        val colors: Map<Int, Int>,
        /** 括号偏移 -> 与它配对的另一个括号偏移（双向） */
        val pairs: Map<Int, Int>,
        /** 所有成功配对的区间，按开括号在文档中的顺序 */
        val scopes: List<Scope>,
    )

    fun isOpener(char: Char): Boolean = char == '(' || char == '[' || char == '{'

    /** 返回该闭括号对应的开括号；不是闭括号时返回 null。 */
    fun openerOf(closer: Char): Char? = when (closer) {
        ')' -> '('
        ']' -> '['
        '}' -> '{'
        else -> null
    }

    fun isBrace(char: Char): Boolean = isOpener(char) || openerOf(char) != null

    /** 从纯文本里抽出括号（单测与文档示例用；真实文件由 PSI 叶子收集）。 */
    fun fromText(text: String): List<Brace> = buildList {
        for ((offset, char) in text.withIndex()) {
            if (isBrace(char)) add(Brace(offset, char))
        }
    }

    /** 返回 `文件内绝对偏移 -> 颜色下标`。 */
    fun compute(braces: List<Brace>, colorCount: Int): Map<Int, Int> =
        analyze(braces, colorCount).colors

    /** 一次遍历同时算出颜色、配对关系与作用域区间。 */
    fun analyze(braces: List<Brace>, colorCount: Int): Analysis {
        val count = colorCount.coerceAtLeast(1)
        val colors = HashMap<Int, Int>(braces.size * 2)
        val pairs = HashMap<Int, Int>(braces.size * 2)
        val scopes = ArrayList<Scope>(braces.size / 2)
        // 栈顶是最近一个未闭合的开括号；Frame 记下颜色下标与入栈深度，配上时直接复用
        val stack = ArrayDeque<Frame>()

        for (brace in braces) {
            val char = brace.char
            if (isOpener(char)) {
                val depth = stack.size
                val color = depth % count
                stack.addLast(Frame(char, brace.offset, color, depth))
                colors[brace.offset] = color
                continue
            }

            val opener = openerOf(char) ?: continue
            var matched: Frame? = null
            while (stack.isNotEmpty()) {
                val top = stack.removeLast()
                if (top.first == opener) {
                    matched = top
                    break
                }
            }
            val frame = matched ?: continue
            colors[brace.offset] = frame.color
            pairs[frame.offset] = brace.offset
            pairs[brace.offset] = frame.offset
            scopes += Scope(frame.offset, brace.offset, frame.depth)
        }
        // 配对是在遇到闭括号时完成的（内层先闭合），这里按开括号位置重排，
        // 让 scopes 保持文档顺序，方便调用方直接用它做遍历 / 二分
        return Analysis(colors, pairs, scopes.sortedBy { it.open })
    }

    private data class Frame(val first: Char, val offset: Int, val color: Int, val depth: Int)
}
