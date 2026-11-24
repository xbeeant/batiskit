package io.github.xbeeant.batiskit.json

/**
 * JSON 解析错误，位置是 1-based 行列号。
 *
 * 从控制台日志里粘过来的文本经常缺半截括号，弹窗里必须能指到「第几行第几列」，
 * 否则一句「JSON 格式错误」等于没说。
 */
class JsonParseException(val line: Int, val column: Int, detail: String) :
    IllegalArgumentException("第 $line 行第 $column 列：$detail")

/**
 * 解析结果树。用 [LinkedHashMap] 保序：生成字段的顺序要跟接口返回的 JSON 一致，
 * 而不是被 HashMap 打乱成随机顺序（字段顺序是 DTO 可读性的大头）。
 *
 * 数字保留原始字面量 [Num.raw]：整数 / 小数、是否超出 Long、有没有指数，都要在推断阶段判断，
 * 提前转成 Double 会把 `9007199254740993` 这类 id 悄悄改掉。
 */
sealed interface JsonValue {
    data object Null : JsonValue
    data class Bool(val value: Boolean) : JsonValue
    data class Num(val raw: String) : JsonValue
    data class Str(val value: String) : JsonValue
    data class Arr(val items: List<JsonValue>) : JsonValue
    data class Obj(val fields: LinkedHashMap<String, JsonValue>) : JsonValue
}

/** 一段输入里可能有多份 JSON 样本（成功响应 / 失败响应 / 分页），逐份解析，错误不中断其它样本 */
class JsonSamples(val values: List<JsonValue>, val errors: List<String>) {
    val isValid: Boolean get() = values.isNotEmpty()
}

/**
 * 宽松 JSON 解析器，零第三方依赖。
 *
 * 相比标准 JSON 额外接受（接口调试时粘进来的东西基本都长这样）：
 *  - `//` 与 `/* */` 注释、尾逗号；
 *  - 单引号字符串、无引号的 key、字面换行；
 *  - `undefined` / `NaN` / `Infinity`（当 null 处理）；
 *  - 前后夹着日志前缀的文本（自动定位第一个完整的 `{…}` / `[…]`）；
 *  - 一次贴多份 JSON（换行 / 逗号 / 空白分隔），每份算一个样本；
 *  - 被转义成字符串的 JSON（`"{\"a\":1}"`）自动解包一层；
 *  - 直接从 `.json` 文件 / HTTP 响应里复制的片段。
 *
 * 解析与推断完全分离：这里只产出树，不猜 Java 类型。
 */
object JsonParser {

    /** 严格入口：整段就是一个 JSON 值（仍允许上面的宽松语法），尾部有多余内容即报错 */
    fun parse(text: String): JsonValue {
        val parser = Parser(text)
        parser.skipTrivia()
        if (parser.atEnd()) throw parser.fail("内容为空")
        val value = parser.parseValue()
        parser.skipTrivia()
        if (!parser.atEnd()) parser.fail("JSON 结束位置之后还有多余内容")
        return unwrap(value)
    }

    /**
     * 样本入口：先按「整段一个 JSON」处理（绝大多数情况），失败再按「日志 / 多段」扫描。
     *
     * 先整体解析很重要：如果一上来就找 `{`，嵌套对象会被误当成多份样本，
     * 字段并集里就会平白多出几个不属于顶层的 key。
     */
    fun parseSamples(text: String): JsonSamples {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return JsonSamples(emptyList(), listOf("请先粘贴 JSON，或从剪贴板 / 编辑器选区带入"))

        try {
            val parser = Parser(trimmed)
            parser.skipTrivia()
            val value = parser.parseValue()
            parser.skipTrivia()
            if (parser.atEnd()) return JsonSamples(listOf(unwrap(value)), emptyList())
        } catch (ignored: JsonParseException) {
            // 落到下面的日志 / 多段扫描
        }

        val scanner = Parser(trimmed)
        val values = mutableListOf<JsonValue>()
        val errors = mutableListOf<String>()
        while (true) {
            val start = scanner.nextValueStart()
            if (start < 0) break
            scanner.pos = start
            try {
                values += unwrap(scanner.parseValue())
            } catch (e: JsonParseException) {
                errors += e.message ?: "解析失败"
                // 出错后从起点的下一个字符继续找，绝不停在同一处造成死循环
                scanner.pos = start + 1
            }
        }
        if (values.isEmpty()) {
            errors += "没有找到完整的 JSON（需要 {…} 或 […]），或括号没有闭合"
        }
        return JsonSamples(values, errors)
    }

    /** 有些网关把 JSON 又包了一层字符串：`"{\"code\":0}"`。自动解开，只解一层 */
    fun unwrap(value: JsonValue): JsonValue {
        if (value !is JsonValue.Str) return value
        val inner = value.value.trim()
        if (!inner.startsWith("{") && !inner.startsWith("[")) return value
        return try {
            parse(inner)
        } catch (ignored: JsonParseException) {
            value
        }
    }

    // ------------------------------------------------------------------ 扫描器

    private class Parser(private val text: String) {

        var pos: Int = 0

        fun atEnd(): Boolean = pos >= text.length

        /**
         * 找下一个「值的起点」。只认 `{` / `[`：日志行里的引号、方括号提示之类的文字会被跳过，
         * 且会正确跳过字符串内部的花括号（`INFO msg={"a":"}"}` 不会误判）。
         */
        fun nextValueStart(): Int {
            var i = pos
            while (i < text.length) {
                when (text[i]) {
                    '"', '\'' -> i = skipString(i)
                    '{', '[' -> return i
                    else -> i++
                }
            }
            return -1
        }

        /** 跳过空白与注释。逗号 / 分号是对象与数组的内部语法，不能在这里吃掉 */
        fun skipTrivia() {
            while (pos < text.length) {
                when {
                    text[pos].isWhitespace() -> pos++
                    text.startsWith("//", pos) -> {
                        val nl = text.indexOf('\n', pos)
                        pos = if (nl < 0) text.length else nl + 1
                    }

                    text.startsWith("/*", pos) -> {
                        val end = text.indexOf("*/", pos + 2)
                        pos = if (end < 0) text.length else end + 2
                    }

                    else -> return
                }
            }
        }

        private fun skipString(from: Int): Int {
            val quote = text[from]
            var i = from + 1
            while (i < text.length) {
                when {
                    text[i] == '\\' -> i += 2
                    text[i] == quote -> return i + 1
                    else -> i++
                }
            }
            return text.length
        }

        fun parseValue(): JsonValue {
            skipTrivia()
            if (atEnd()) fail("内容意外结束，缺少值")
            return when (val c = text[pos]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"', '\'' -> JsonValue.Str(readString())
                't' -> literal("true", JsonValue.Bool(true))
                'f' -> literal("false", JsonValue.Bool(false))
                'n' -> literal("null", JsonValue.Null)
                'u' -> literal("undefined", JsonValue.Null)
                'N' -> literal("NaN", JsonValue.Null)
                'I' -> literal("Infinity", JsonValue.Null)
                '+', '-', '.', in '0'..'9' -> JsonValue.Num(readNumber())
                else -> fail("无法识别的字符 '$c'")
            }
        }

        private fun literal(word: String, value: JsonValue): JsonValue {
            if (!text.startsWith(word, pos)) fail("无法识别的字面量，期望 $word")
            pos += word.length
            return value
        }

        private fun parseObject(): JsonValue.Obj {
            val start = pos
            pos++ // {
            val fields = LinkedHashMap<String, JsonValue>()
            skipTrivia()
            while (!atEnd() && text[pos] != '}') {
                val key = when (text[pos]) {
                    '"', '\'' -> readString()
                    else -> readBareKey()
                }
                skipTrivia()
                if (atEnd() || text[pos] != ':') fail("key '$key' 后面缺少冒号")
                pos++
                val value = parseValue()
                // 重复 key 以最后一份为准（浏览器 / Jackson 的默认行为）
                fields[key] = value
                skipTrivia()
                if (!atEnd() && text[pos] == ',') {
                    pos++
                    skipTrivia()
                    continue
                }
                if (!atEnd() && text[pos] != '}') fail("对象里缺少逗号或右花括号")
            }
            if (atEnd()) fail("对象从第 ${lineOf(start)} 行开始，但没有闭合的 }")
            pos++ // }
            return JsonValue.Obj(fields)
        }

        private fun parseArray(): JsonValue.Arr {
            val start = pos
            pos++ // [
            val items = mutableListOf<JsonValue>()
            skipTrivia()
            while (!atEnd() && text[pos] != ']') {
                items += parseValue()
                skipTrivia()
                if (!atEnd() && text[pos] == ',') {
                    pos++
                    skipTrivia()
                    continue
                }
                if (!atEnd() && text[pos] != ']') fail("数组里缺少逗号或右方括号")
            }
            if (atEnd()) fail("数组从第 ${lineOf(start)} 行开始，但没有闭合的 ]")
            pos++ // ]
            return JsonValue.Arr(items)
        }

        private fun readBareKey(): String {
            val start = pos
            while (pos < text.length && (text[pos].isLetterOrDigit() || text[pos] in "_$-.")) pos++
            if (pos == start) fail("对象里缺少 key（需要字符串或标识符）")
            return text.substring(start, pos)
        }

        private fun readString(): String {
            val quote = text[pos]
            pos++
            val sb = StringBuilder()
            while (pos < text.length) {
                val c = text[pos++]
                when {
                    c == quote -> return sb.toString()
                    c == '\\' -> {
                        if (pos >= text.length) fail("字符串末尾的转义符没有内容")
                        when (val e = text[pos++]) {
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            '/' -> sb.append('/')
                            '\\' -> sb.append('\\')
                            '"' -> sb.append('"')
                            '\'' -> sb.append('\'')
                            'u' -> {
                                if (pos + 4 > text.length) fail("\\u 转义不完整")
                                val hex = text.substring(pos, pos + 4)
                                val code = hex.toIntOrNull(16) ?: fail("\\u 转义不是十六进制：$hex")
                                sb.append(code.toChar())
                                pos += 4
                            }

                            else -> sb.append(e)
                        }
                    }

                    else -> sb.append(c)
                }
            }
            fail("字符串没有闭合的 $quote")
        }

        private fun readNumber(): String {
            val start = pos
            while (pos < text.length && (text[pos].isDigit() || text[pos] in "+-.eE")) pos++
            val raw = text.substring(start, pos)
            if (!NUMBER.matches(raw)) fail("不是合法的数字：$raw")
            return raw
        }

        fun fail(detail: String): Nothing = throw JsonParseException(lineOf(pos), columnOf(pos), detail)

        private fun lineOf(offset: Int): Int {
            var line = 1
            for (i in 0 until minOf(offset, text.length)) if (text[i] == '\n') line++
            return line
        }

        private fun columnOf(offset: Int): Int {
            val safe = minOf(offset, text.length)
            val lastNl = text.lastIndexOf('\n', safe - 1)
            return safe - lastNl
        }

        companion object {
            val NUMBER = Regex("^[+-]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)(?:[eE][+-]?\\d+)?$")
        }
    }
}
