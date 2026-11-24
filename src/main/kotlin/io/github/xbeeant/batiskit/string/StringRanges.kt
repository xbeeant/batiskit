package io.github.xbeeant.batiskit.string

/**
 * 没有选区时，光标所在行里「可以拿来转换」的文本片段，偏移相对这一行。
 *
 * 纯逻辑、不碰 PSI：Alt+Enter 弹窗用它把作用范围列成下拉，让用户自己决定是转一个词、
 * 一段字符串内容还是整行。放在这里是为了能直接用 JUnit 断言边界，不用起 IDE。
 */
data class StringSpan(
    val start: Int,
    val end: Int,
    /** 下拉里显示的名字，带字符数，选之前就知道会影响多少内容 */
    val label: String
)

object StringRanges {

    private val QUOTES = charArrayOf('"', '\'', '`')

    /**
     * 返回按「最可能想转的」排序的候选片段，重复的范围不列两次。
     *
     * 顺序：字符串字面量内容 → 光标处单词 → 当前行。
     * 例如 `private String user_name;` 光标停在 `user_name` 中间时，默认是 `user_name`
     * 而不是整行；想对整行做排序 / 去重时，在弹窗里切到「当前行」即可。
     */
    fun spans(line: String, caret: Int): List<StringSpan> {
        if (line.isEmpty()) return emptyList()
        val c = caret.coerceIn(0, line.length)
        val out = mutableListOf<StringSpan>()

        stringContent(line, c)?.let { (start, end) ->
            add(out, line, start, end, "字符串内容")
        }
        wordAt(line, c)?.let { (start, end) ->
            add(out, line, start, end, "当前单词")
        }
        add(out, line, 0, line.length, "当前行")
        return out
    }

    private fun add(out: MutableList<StringSpan>, line: String, start: Int, end: Int, name: String) {
        if (end <= start || out.any { it.start == start && it.end == end }) return
        out += StringSpan(start, end, "$name · ${end - start} 字符")
    }

    /** 字母 / 数字 / `_` `$` `-` `.` `#` 都算词内字符，这样 `user-name`、`user.name` 能整块转换。 */
    private fun isWordChar(c: Char): Boolean =
        c.isLetterOrDigit() || c == '_' || c == '$' || c == '-' || c == '.' || c == '#'

    /** 光标落在词内或紧贴词的右边界时，向两侧扩到非词字符为止。 */
    private fun wordAt(line: String, caret: Int): Pair<Int, Int>? {
        val anchor = if (caret < line.length && isWordChar(line[caret])) caret else caret - 1
        if (anchor < 0 || anchor >= line.length || !isWordChar(line[anchor])) return null
        var start = anchor
        while (start > 0 && isWordChar(line[start - 1])) start--
        var end = anchor + 1
        while (end < line.length && isWordChar(line[end])) end++
        return start to end
    }

    /**
     * 光标在一对同类引号之间时，返回**引号内部**的范围（不含引号）。
     *
     * 用「光标之前有几个未转义的同类引号」判断内外，奇数才是字符串内部；
     * 这样 `foo("a") + "b"` 里光标在 `+` 上时不会误把两段字符串中间的代码当内容。
     */
    private fun stringContent(line: String, caret: Int): Pair<Int, Int>? {
        for (quote in QUOTES) {
            val indices = mutableListOf<Int>()
            var i = 0
            while (i < line.length) {
                when (line[i]) {
                    '\\' -> i += 2 // 转义后的引号不算边界
                    quote -> {
                        indices += i
                        i++
                    }
                    else -> i++
                }
            }
            val before = indices.indexOfFirst { it >= caret }.let { if (it < 0) indices.size else it }
            if (before == 0 || before % 2 == 0) continue
            val start = indices[before - 1] + 1
            val end = indices.getOrNull(before) ?: continue
            if (end > start) return start to end
        }
        return null
    }
}
