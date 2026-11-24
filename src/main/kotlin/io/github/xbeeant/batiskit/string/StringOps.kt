package io.github.xbeeant.batiskit.string

import io.github.xbeeant.batiskit.string.StringOpGroup.CASE
import io.github.xbeeant.batiskit.string.StringOpGroup.CLEAN
import io.github.xbeeant.batiskit.string.StringOpGroup.ENCODE
import io.github.xbeeant.batiskit.string.StringOpGroup.LINES
import io.github.xbeeant.batiskit.string.StringOpGroup.NAMING
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.Locale

/**
 * 字符串处理（String Manipulation）的**纯逻辑层**：不依赖 IntelliJ 平台，普通 JUnit 可直接断言。
 *
 * 编辑器右键、工具窗口、模板里的 `$str.*` 共用这一份操作目录（[ALL]），
 * 所以「改一个转换规则」只需要改这里一处，不会出现菜单里和面板里结果不一致。
 */

/** 操作分组，声明顺序即菜单 / 列表里的展示顺序。 */
enum class StringOpGroup(val title: String) {
    CASE("大小写"),
    NAMING("命名风格"),
    ENCODE("编码 / 解码"),
    LINES("排序 / 去重 / 拼接"),
    CLEAN("文本整理")
}

/**
 * 影响「行类」与「拼接类」操作的选项。
 * 编辑器右键用默认值，工具窗口里的输入即这里的字段。
 */
data class StringOptions(
    /** 拼接分隔符；支持写 `\n` `\t` `\r` `\\` */
    val separator: String = ", ",
    /** 拆分分隔符，同上 */
    val splitSeparator: String = ",",
    /** 排序 / 去重时忽略大小写 */
    val ignoreCase: Boolean = false,
    /** 降序排序 */
    val descending: Boolean = false,
    /** 处理前逐行去首尾空白 */
    val trimItems: Boolean = true,
    /** 丢弃空行 / 空片段 */
    val skipEmpty: Boolean = true
) {
    companion object {
        /** UI 里输入的是可打印写法：`\n` 表示真换行、`\\` 表示一个反斜杠 */
        fun unescape(raw: String): String {
            val sentinel = "\u0000"
            return raw
                .replace("\\\\", sentinel)
                .replace("\\n", "\n")
                .replace("\\t", "\t")
                .replace("\\r", "\r")
                .replace(sentinel, "\\")
        }
    }
}

/** 输入不合法（Base64 / URL 解码失败等），UI 层据此给出提示而不是写回一堆乱码。 */
class StringOpException(message: String) : IllegalArgumentException(message)

/** 一个转换操作。`transform` 只接字符串与选项，不碰 PSI / 线程。 */
data class StringOp(
    val id: String,
    val title: String,
    val group: StringOpGroup,
    val description: String,
    val transform: (String, StringOptions) -> String
)

// --------------------------------------------------------------------------- 算法

private object Algo {

    private val WORD = Regex("[\\p{L}\\p{N}]+")
    private val NUMERIC_ENTITY = Regex("(?i)&#(x[0-9a-f]+|[0-9]+);")
    private val UNICODE_ESCAPE = Regex("\\\\u([0-9a-fA-F]{4})")

    /**
     * 把任意写法拆成单词：下划线 / 连字符 / 空格 / 点号等分隔符，
     * 外加大小写边界（`userName`）、缩写边界（`HTTPServer`）、字母与数字边界（`field2`）。
     * 统一小写后再交给各种命名风格拼装，避免 `userID` 与 `userId` 产生两套结果。
     */
    fun words(raw: String): List<String> {
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        fun flush() {
            if (sb.isNotEmpty()) {
                out += sb.toString()
                sb.setLength(0)
            }
        }
        for ((i, ch) in raw.withIndex()) {
            if (!ch.isLetterOrDigit()) {
                flush()
                continue
            }
            val prev = sb.lastOrNull()
            if (prev != null) {
                val nextLower = raw.getOrNull(i + 1)?.isLowerCase() == true
                val boundary = when {
                    (prev.isLowerCase() || prev.isDigit()) && ch.isUpperCase() -> true
                    prev.isUpperCase() && ch.isUpperCase() && nextLower -> true
                    prev.isDigit() != ch.isDigit() -> true
                    else -> false
                }
                if (boundary) flush()
            }
            sb.append(ch)
        }
        flush()
        return out.map { it.lowercase() }
    }

    private fun cap(word: String): String = word.replaceFirstChar { it.uppercase() }

    fun pascal(raw: String): String = words(raw).joinToString("") { cap(it) }

    fun camel(raw: String): String = pascal(raw).replaceFirstChar { it.lowercase() }

    fun snake(raw: String): String = words(raw).joinToString("_")

    fun screamingSnake(raw: String): String = snake(raw).uppercase()

    fun kebab(raw: String): String = words(raw).joinToString("-")

    fun dot(raw: String): String = words(raw).joinToString(".")

    fun spaced(raw: String): String = words(raw).joinToString(" ")

    /**
     * 命名风格轮换：驼峰 → 帕斯卡 → 下划线 → 常量 → 连字符 → 点号 → 空格 → 驼峰。
     * 识别不出来时按「首字母大小写」猜是驼峰还是帕斯卡。
     */
    fun switchCase(raw: String): String = when {
        raw.contains('_') && raw == raw.uppercase() -> kebab(raw)
        raw.contains('_') -> screamingSnake(raw)
        raw.contains('-') -> dot(raw)
        raw.contains('.') -> spaced(raw)
        raw.contains(' ') -> camel(raw)
        raw.firstOrNull()?.isUpperCase() == true -> snake(raw)
        else -> pascal(raw)
    }

    fun titleCase(s: String): String = WORD.replace(s) { m ->
        m.value.lowercase().replaceFirstChar { it.uppercase() }
    }

    fun invertCase(s: String): String = buildString {
        for (c in s) {
            append(
                when {
                    c.isUpperCase() -> c.lowercaseChar()
                    c.isLowerCase() -> c.uppercaseChar()
                    else -> c
                }
            )
        }
    }

    fun alternatingCase(s: String): String = buildString {
        var upper = false
        for (c in s) {
            if (c.isLetter()) {
                append(if (upper) c.uppercaseChar() else c.lowercaseChar())
                upper = !upper
            } else {
                append(c)
            }
        }
    }

    /** 按码点反转，emoji / 增补平面字符不会被拆成两个乱码代理项 */
    fun reverse(s: String): String {
        val sb = StringBuilder()
        var i = s.length
        while (i > 0) {
            val cp = s.codePointBefore(i)
            sb.appendCodePoint(cp)
            i -= Character.charCount(cp)
        }
        return sb.toString()
    }

    // ---------------------------------------------------------------- 行

    /** 保留原来的换行符与「末尾是否有换行」，行类操作不该顺手改掉排版 */
    private class LineDoc(val lines: List<String>, val eol: String, val trailing: Boolean) {
        fun render(newLines: List<String>): String = buildString {
            append(newLines.joinToString(eol))
            if (trailing && newLines.isNotEmpty()) append(eol)
        }
    }

    private fun lineDoc(input: String): LineDoc {
        val eol = if (input.contains("\r\n")) "\r\n" else "\n"
        val normalized = input.replace("\r\n", "\n")
        val trailing = normalized.endsWith("\n")
        val parts = normalized.split('\n').toMutableList()
        if (trailing && parts.isNotEmpty()) parts.removeAt(parts.lastIndex)
        return LineDoc(parts, eol, trailing)
    }

    private fun items(doc: LineDoc, o: StringOptions): List<String> = doc.lines
        .map { if (o.trimItems) it.trim() else it }
        .filter { !o.skipEmpty || it.isNotBlank() }

    private fun chunks(s: String): List<Pair<String, Boolean>> {
        val out = mutableListOf<Pair<String, Boolean>>()
        var i = 0
        while (i < s.length) {
            val digit = s[i].isDigit()
            var j = i + 1
            while (j < s.length && s[j].isDigit() == digit) j++
            out += s.substring(i, j) to digit
            i = j
        }
        return out
    }

    /** 自然排序：item2 排在 item10 前面，而不是按字典序把 10 排到 2 前面 */
    private fun naturalCompare(a: String, b: String): Int {
        val ca = chunks(a)
        val cb = chunks(b)
        for (i in 0 until minOf(ca.size, cb.size)) {
            val (sa, da) = ca[i]
            val (sb, db) = cb[i]
            val cmp = if (da && db) {
                val na = sa.trimStart('0').ifEmpty { "0" }
                val nb = sb.trimStart('0').ifEmpty { "0" }
                na.length.compareTo(nb.length).takeIf { it != 0 } ?: na.compareTo(nb)
            } else {
                sa.compareTo(sb)
            }
            if (cmp != 0) return cmp
        }
        return ca.size.compareTo(cb.size)
    }

    private fun comparator(o: StringOptions, natural: Boolean): Comparator<String> = Comparator { a, b ->
        val x = if (o.ignoreCase) a.lowercase() else a
        val y = if (o.ignoreCase) b.lowercase() else b
        if (natural) naturalCompare(x, y) else x.compareTo(y)
    }

    private fun sorted(input: String, o: StringOptions, natural: Boolean = false): String {
        val doc = lineDoc(input)
        val cmp = comparator(o, natural)
        val result = items(doc, o).sortedWith(if (o.descending) cmp.reversed() else cmp)
        return doc.render(result)
    }

    fun sort(input: String, o: StringOptions): String = sorted(input, o)

    fun naturalSort(input: String, o: StringOptions): String = sorted(input, o, natural = true)

    private fun keepFirst(lines: List<String>, o: StringOptions): List<String> {
        val seen = HashSet<String>()
        return lines.filter { seen.add(if (o.ignoreCase) it.lowercase() else it) }
    }

    fun distinct(input: String, o: StringOptions): String {
        val doc = lineDoc(input)
        return doc.render(keepFirst(items(doc, o), o))
    }

    fun sortAndDistinct(input: String, o: StringOptions): String {
        val doc = lineDoc(input)
        val cmp = comparator(o, natural = false)
        val lines = items(doc, o).sortedWith(if (o.descending) cmp.reversed() else cmp)
        return doc.render(keepFirst(lines, o))
    }

    /** 只去掉紧挨着的重复行（排序前保留分组时更好用） */
    fun distinctAdjacent(input: String, o: StringOptions): String {
        val doc = lineDoc(input)
        val out = mutableListOf<String>()
        var previous: String? = null
        var hasPrevious = false
        for (line in items(doc, o)) {
            val key = if (o.ignoreCase) line.lowercase() else line
            if (!hasPrevious || key != previous) out += line
            previous = key
            hasPrevious = true
        }
        return doc.render(out)
    }

    fun reverseLines(input: String, o: StringOptions): String {
        val doc = lineDoc(input)
        return doc.render(items(doc, o).asReversed())
    }

    fun reverseEach(input: String, o: StringOptions): String {
        val doc = lineDoc(input)
        return doc.render(doc.lines.map { reverse(it) })
    }

    fun trimLines(input: String, o: StringOptions): String {
        val doc = lineDoc(input)
        return doc.render(doc.lines.map { if (o.trimItems) it.trim() else it })
    }

    fun removeEmptyLines(input: String, o: StringOptions): String {
        val doc = lineDoc(input)
        return doc.render(doc.lines.filter { it.isNotBlank() })
    }

    fun numberLines(input: String, o: StringOptions): String {
        val doc = lineDoc(input)
        val lines = doc.lines
        val width = lines.size.toString().length
        return doc.render(lines.mapIndexed { i, line -> "${(i + 1).toString().padStart(width)}. $line" })
    }

    fun join(input: String, o: StringOptions): String =
        items(lineDoc(input), o).joinToString(StringOptions.unescape(o.separator))

    fun split(input: String, o: StringOptions): String {
        val raw = StringOptions.unescape(o.splitSeparator)
        val parts = if (raw.isEmpty()) {
            input.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        } else {
            input.split(raw)
        }
        val result = parts
            .map { if (o.trimItems) it.trim() else it }
            .filter { !o.skipEmpty || it.isNotBlank() }
        return result.joinToString("\n")
    }

    // ---------------------------------------------------------------- 编解码

    /** 用 UTF-8 的 URL 组件编码：空格是 `%20` 而不是表单编码的 `+` */
    fun urlEncode(s: String): String = URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20")

    /** 字面量 `+` 先转成 `%2B`，否则 URLDecoder 会把它当空格吃掉 */
    fun urlDecode(s: String): String = decode("URL") {
        URLDecoder.decode(s.replace("+", "%2B"), StandardCharsets.UTF_8)
    }

    fun base64Encode(s: String): String =
        Base64.getEncoder().encodeToString(s.toByteArray(StandardCharsets.UTF_8))

    /** 容忍换行 / URL-safe 的 `-_` / 缺失的 `=` 填充 */
    fun base64Decode(s: String): String = decode("Base64") {
        val cleaned = s.filter { !it.isWhitespace() }
            .replace('-', '+')
            .replace('_', '/')
        val padded = cleaned + "=".repeat((4 - cleaned.length % 4) % 4)
        String(Base64.getDecoder().decode(padded), StandardCharsets.UTF_8)
    }

    private inline fun decode(kind: String, block: () -> String): String = try {
        block()
    } catch (ex: IllegalArgumentException) {
        throw StringOpException("$kind 解码失败：${ex.message ?: "输入不是合法的编码文本"}")
    }

    /** 非 ASCII 字符转成 `\uXXXX`（UTF-16 码元，代理对天然成对出现） */
    fun unicodeEscape(s: String): String = buildString {
        for (c in s) {
            if (c.code > 127) append("\\u%04X".format(Locale.ROOT, c.code)) else append(c)
        }
    }

    fun unicodeUnescape(s: String): String = UNICODE_ESCAPE.replace(s) { m ->
        m.groupValues[1].toInt(16).toChar().toString()
    }

    fun htmlEscape(s: String): String = buildString {
        for (c in s) {
            append(
                when (c) {
                    '&' -> "&amp;"
                    '<' -> "&lt;"
                    '>' -> "&gt;"
                    '"' -> "&quot;"
                    '\'' -> "&#39;"
                    else -> c
                }
            )
        }
    }

    fun htmlUnescape(s: String): String {
        val named = s
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
            .replace("&nbsp;", "\u00A0")
            .replace("&amp;", "&")
        return NUMERIC_ENTITY.replace(named) { m ->
            val body = m.groupValues[1]
            val cp = if (body.startsWith('x', ignoreCase = true)) {
                body.substring(1).toIntOrNull(16)
            } else {
                body.toIntOrNull(10)
            }
            if (cp == null || cp !in 0..Character.MAX_CODE_POINT) m.value else buildString { appendCodePoint(cp) }
        }
    }

    fun javaEscape(s: String): String = buildString {
        for (c in s) {
            append(
                when (c) {
                    '\\' -> "\\\\"
                    '"' -> "\\\""
                    '\n' -> "\\n"
                    '\r' -> "\\r"
                    '\t' -> "\\t"
                    '\b' -> "\\b"
                    '\u000C' -> "\\f"
                    else -> c
                }
            )
        }
    }

    fun javaUnescape(s: String): String = buildString {
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c != '\\' || i + 1 >= s.length) {
                append(c)
                i++
                continue
            }
            when (val n = s[i + 1]) {
                'n' -> append('\n')
                'r' -> append('\r')
                't' -> append('\t')
                'b' -> append('\b')
                'f' -> append('\u000C')
                '"' -> append('"')
                '\'' -> append('\'')
                '\\' -> append('\\')
                else -> {
                    append('\\')
                    append(n)
                }
            }
            i += 2
        }
    }
}

// --------------------------------------------------------------------------- 操作目录

private fun op(
    id: String,
    title: String,
    group: StringOpGroup,
    description: String,
    transform: (String, StringOptions) -> String
): StringOp = StringOp(id, title, group, description, transform)

object StringOps {

    /** 全量操作，按分组排好；菜单、搜索面板、工具窗口列表、模板函数都由它生成。 */
    val ALL: List<StringOp> = buildList {
        // 大小写
        add(op("upper", "全部大写", CASE, "hello World → HELLO WORLD") { s, _ -> s.uppercase() })
        add(op("lower", "全部小写", CASE, "Hello WORLD → hello world") { s, _ -> s.lowercase() })
        add(op("capitalize", "句首大写", CASE, "hello world → Hello world") { s, _ ->
            s.replaceFirstChar { it.uppercase() }
        })
        add(op("titleCase", "每个单词首字母大写", CASE, "user name → User Name") { s, _ -> Algo.titleCase(s) })
        add(op("invertCase", "大小写互换", CASE, "Hello World → hELLO wORLD") { s, _ -> Algo.invertCase(s) })
        add(op("alternatingCase", "大小写交替", CASE, "hello → hElLo") { s, _ -> Algo.alternatingCase(s) })

        // 命名风格
        add(op("camel", "驼峰 camelCase", NAMING, "user_name / UserName → userName") { s, _ -> Algo.camel(s) })
        add(op("pascal", "帕斯卡 PascalCase", NAMING, "user_name → UserName") { s, _ -> Algo.pascal(s) })
        add(op("snake", "下划线 snake_case", NAMING, "userName → user_name") { s, _ -> Algo.snake(s) })
        add(op("screamingSnake", "常量 SCREAMING_SNAKE", NAMING, "userName → USER_NAME") { s, _ ->
            Algo.screamingSnake(s)
        })
        add(op("kebab", "连字符 kebab-case", NAMING, "userName → user-name") { s, _ -> Algo.kebab(s) })
        add(op("dot", "点号 dot.case", NAMING, "userName → user.name") { s, _ -> Algo.dot(s) })
        add(op("spaced", "空格分词", NAMING, "userName → user name") { s, _ -> Algo.spaced(s) })
        add(op("switchCase", "命名风格轮换", NAMING, "驼峰 → 帕斯卡 → 下划线 → 常量 → 连字符 → 点号 → …") { s, _ ->
            Algo.switchCase(s)
        })

        // 编码 / 解码
        add(op("urlEncode", "URL 编码", ENCODE, "空格 → %20，中文按 UTF-8") { s, _ -> Algo.urlEncode(s) })
        add(op("urlDecode", "URL 解码", ENCODE, "%E4%B8%AD → 中") { s, _ -> Algo.urlDecode(s) })
        add(op("base64Encode", "Base64 编码", ENCODE, "按 UTF-8 字节编码") { s, _ -> Algo.base64Encode(s) })
        add(op("base64Decode", "Base64 解码", ENCODE, "容忍换行与 URL-safe 的 -_") { s, _ -> Algo.base64Decode(s) })
        add(op("unicodeEscape", "Unicode 转义", ENCODE, "中文 → \\u4E2D\\u6587") { s, _ -> Algo.unicodeEscape(s) })
        add(op("unicodeUnescape", "Unicode 反转义", ENCODE, "\\u4E2D\\u6587 → 中文") { s, _ ->
            Algo.unicodeUnescape(s)
        })
        add(op("htmlEscape", "HTML 转义", ENCODE, "<a> → &lt;a&gt;") { s, _ -> Algo.htmlEscape(s) })
        add(op("htmlUnescape", "HTML 反转义", ENCODE, "&lt;a&gt; → <a>") { s, _ -> Algo.htmlUnescape(s) })
        add(op("javaEscape", "Java 字符串转义", ENCODE, "换行 / 引号 / 反斜杠") { s, _ -> Algo.javaEscape(s) })
        add(op("javaUnescape", "Java 字符串反转义", ENCODE, "\\n → 真换行") { s, _ -> Algo.javaUnescape(s) })

        // 排序 / 去重 / 拼接
        add(op("sortAsc", "行排序", LINES, "按行排序，可切换忽略大小写 / 降序") { s, o -> Algo.sort(s, o) })
        add(op("naturalSort", "自然排序（数字按数值）", LINES, "item2 排在 item10 前面") { s, o ->
            Algo.naturalSort(s, o)
        })
        add(op("reverseLines", "反转行序", LINES, "最后一行变第一行") { s, o -> Algo.reverseLines(s, o) })
        add(op("distinct", "去重（保留首次）", LINES, "按行去重，保持原有顺序") { s, o -> Algo.distinct(s, o) })
        add(op("sortAndDistinct", "排序并去重", LINES, "先排序再去重") { s, o -> Algo.sortAndDistinct(s, o) })
        add(op("distinctAdjacent", "相邻行去重", LINES, "只去掉紧挨着的重复行") { s, o ->
            Algo.distinctAdjacent(s, o)
        })
        add(op("join", "拼接成一行", LINES, "按分隔符把多行拼起来") { s, o -> Algo.join(s, o) })
        add(op("split", "拆分成多行", LINES, "按分隔符把文本拆成行") { s, o -> Algo.split(s, o) })
        add(op("trimLines", "逐行去首尾空白", LINES, "保留空行，只修空白") { s, o -> Algo.trimLines(s, o) })
        add(op("removeEmptyLines", "去空行", LINES, "删掉全空白的行") { s, o -> Algo.removeEmptyLines(s, o) })
        add(op("numberLines", "行编号", LINES, "1. 第一行 / 2. 第二行") { s, o -> Algo.numberLines(s, o) })
        add(op("reverseEach", "每行字符反转", LINES, "abc → cba（不改变行序）") { s, o -> Algo.reverseEach(s, o) })

        // 文本整理
        add(op("trim", "去首尾空白", CLEAN, "整段 trim") { s, _ -> s.trim() })
        add(op("collapseWhitespace", "合并连续空白", CLEAN, "连续空格 / 换行 → 一个空格") { s, _ ->
            s.replace(Regex("\\s+"), " ")
        })
        add(op("removeWhitespace", "去掉全部空白", CLEAN, "空格 / 制表符 / 换行全删") { s, _ ->
            s.filter { !it.isWhitespace() }
        })
        add(op("reverse", "反转整段文本", CLEAN, "按 Unicode 码点反转，emoji 不散") { s, _ -> Algo.reverse(s) })
    }

    private val INDEX: Map<String, StringOp> = ALL.associateBy { it.id }

    fun byId(id: String): StringOp? = INDEX[id]

    fun of(group: StringOpGroup): List<StringOp> = ALL.filter { it.group == group }

    fun apply(op: StringOp, input: String, options: StringOptions = StringOptions()): String =
        op.transform(input, options)

    fun apply(id: String, input: String, options: StringOptions = StringOptions()): String {
        val target = byId(id) ?: throw StringOpException("未知的字符串操作：$id")
        return target.transform(input, options)
    }

    /** 列表 / 菜单里的结果预览：失败也不应该让整个弹窗崩掉。 */
    fun preview(op: StringOp, input: String, options: StringOptions = StringOptions()): String = try {
        op.transform(input, options)
    } catch (ex: RuntimeException) {
        "（${ex.message ?: "无法转换"}）"
    }
}
