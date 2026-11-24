package io.github.xbeeant.batiskit.format

/**
 * MyBatis Mapper XML 格式化引擎（纯 Kotlin，无平台依赖）。
 *
 * Mapper 文件是 **SQL 与 XML 混合** 的：`<select>` 里既有 SQL 正文，又有 `<if>` / `<foreach>` /
 * `<where>` 这类动态标签。两套缩进必须一起算，否则「SQL 正文按原文缩进、XML 标签按层级缩进」
 * 就会各说各话 —— 例如 `<select>` 在 4 空格、它里面的 SQL 也在 4 空格、而 `<foreach>` 却在
 * 8 空格，看起来就像缩进坏了。
 *
 * 本格式化器的核心规则（**XML 层级打底 + SQL 相对层次叠加**）：
 *
 * - 每个节点先按它所处的 XML 层级算出骨架缩进 `indent(level)`（默认每层 4 空格）；
 * - 一段 SQL 正文内部，取所有非空行前导宽度的最小值作基线，每行相对基线多出来的层次
 *   **完整保留**，最终前缀 = 骨架缩进 + 相对层次。于是 `SELECT` / 字段 / `JOIN` / `WHERE`
 *   的人工排版还在，但整体跟着 XML 层级平移，与旁边的动态标签严格对齐；
 * - 顶格或零散乱缩进（前导宽度不是缩进单位的整数倍）没有可信基线，统一按骨架缩进对齐；
 * - SQL 正文只动前导/行尾空白，不重排、不折行、不改任何非空白字符；
 * - 原文里 SQL 与标签写在同一行时**不拆行**（`AND id IN <foreach>…</foreach>`）；
 *   单个换行让连续的内联标签各自成行；
 * - 结构标签独占一行，短的叶子元素保持内联，标签属性压成单行（引号外空白），
 *   连续空行收敛为一行，多行 CDATA 保留内部行与相对层次；
 * - `<?xml … ?>` / `<!DOCTYPE …>` 原样保留在最顶部。
 *
 * 原理：把文本解析成元素 / 文本 / 注释 / CDATA 的节点树（引号感知，属性值里的 `>` 不会截断标签），
 * 再按上面的缩进规则重新拼装。拼装只增删换行与缩进空白，任何非空白字符都不会被改动。
 */
object MapperXmlFormatter {

    /** 每层缩进的空格数 */
    const val INDENT_UNIT: Int = 4

    /** 叶子元素保持内联的最大长度（`<tag attrs>text</tag>` 全长） */
    const val INLINE_MAX: Int = 120

    /**
     * 可配置项（纯数据，设置页与平台接入层共用）。
     * 平台传进来的值可能是用户在设置页里填错的，所以一律在读取时收敛到合法范围。
     */
    data class Options(val indentUnit: Int = INDENT_UNIT, val inlineMax: Int = INLINE_MAX) {
        val safeIndent: Int get() = indentUnit.coerceIn(1, 8)
        val safeInlineMax: Int get() = inlineMax.coerceIn(20, 1000)
        fun indent(level: Int): String = " ".repeat(safeIndent * level.coerceAtLeast(0))
    }

    // ------------------------------------------------------------------ 对外入口

    /** 是否是 Mapper XML：有 `<mapper … namespace=…>` 根标签，或声明了 MyBatis 的 DTD */
    fun isMapperXml(text: String): Boolean {
        if (!text.contains("<mapper")) return false
        if (text.contains("mybatis.org//DTD Mapper")) return true
        // 不用正则扫整段：属性值里出现裸 `>`（如 <mapper foo="a > b" namespace="…">）时，
        // `[^>]*` 会被提前截断，导致明明是 Mapper XML 却被判否、格式化整篇静默失效。
        var from = 0
        while (true) {
            val start = text.indexOf("<mapper", from)
            if (start < 0) return false
            val after = start + MAPPER_PREFIX.length
            if (after >= text.length || text[after].isWhitespace() || text[after] == '>' || text[after] == '/') {
                val end = tagEnd(text, start)
                if (end > start && NAMESPACE_ATTR.containsMatchIn(text.substring(start, end + 1))) return true
            }
            from = start + 1
        }
    }

    /** 格式化整段文本；不是 Mapper XML 时原样返回 */
    fun format(text: String, options: Options = Options()): String = formatOrNull(text, options) ?: text

    /**
     * 与 [format] 同一套规则，但解析失败（标签不自洽）时返回 null 而不是原样返回，
     * 调用方可以借此给用户明确提示，避免「按了格式化却整篇没动静」。
     */
    fun formatOrNull(text: String, options: Options = Options()): String? {
        if (!isMapperXml(text)) return text
        val nodes = parse(text) ?: return null
        val emitter = Emitter(options)
        for (node in nodes) {
            render(node, 0, emitter)
        }
        return emitter.finish()
    }

    /**
     * 格式化**选区片段**：不要求 `<mapper>` 根标签，但片段里的标签必须自洽
     * （未闭合 / 被截断时返回 null，调用方据此给用户明确提示，而不是静默跳过）。
     *
     * [baseIndent] 是片段在原文里的起始缩进，格式化结果整体平移，保持与上下文对齐。
     * 片段内部的行一律按 level 0 起算，所以平移是纯粹的「整体加前缀」，不会重复叠加。
     */
    fun formatFragment(text: String, baseIndent: Int = 0, options: Options = Options()): String? {
        val nodes = parse(text) ?: return null
        val emitter = Emitter(options)
        for (node in nodes) {
            render(node, 0, emitter)
        }
        emitter.finish()
        val lines = emitter.finishedLines()
        if (lines.all { it.isBlank() }) return null
        val pad = " ".repeat(baseIndent.coerceAtLeast(0))
        if (pad.isEmpty()) return lines.joinToString("\n").trimEnd('\n')
        return lines.map { line -> if (line.isBlank()) line else pad + line }
            .joinToString("\n").trimEnd('\n')
    }

    /** 计算某偏移处新行应有的缩进（给平台的 Enter / 缩进提示用） */
    fun indentAt(text: String, offset: Int, options: Options = Options()): String {
        if (!isMapperXml(text)) return ""
        val depth = depthAt(text, offset.coerceIn(0, text.length))
        return options.indent(depth)
    }

    // ------------------------------------------------------------------ 节点模型

    private abstract class Node(val blankBefore: Boolean) {
        /** 该节点前是否至少有一个换行：决定它接在上一行末尾，还是另起一行 */
        var lineBreakBefore: Boolean = false
    }

    private class Element(
        val name: String,
        val attrs: String,
        val children: List<Node>,
        val selfClosed: Boolean,
        blankBefore: Boolean,
    ) : Node(blankBefore)

    private class Comment(val raw: String, blankBefore: Boolean) : Node(blankBefore)

    private class CData(val raw: String, blankBefore: Boolean) : Node(blankBefore)

    /** [leading] 是第一行的前导空白：parse 的 skipWhitespace 会吃掉它，保留排版时要用它还原 */
    private class Text(val raw: String, val leading: String, blankBefore: Boolean) : Node(blankBefore)

    private class Pi(val raw: String, blankBefore: Boolean) : Node(blankBefore)

    // ------------------------------------------------------------------ 解析

    private class TagScanner(private val text: String) {
        var pos: Int = 0
        private var lineBreaks = 0

        /** 本次跳过的空白里，最后一行剩下的前导空白（文本节点第一行的缩进） */
        var lastLineIndent: String = ""
            private set

        /** 跳过空白，累计跳过的空行数 */
        fun skipWhitespace() {
            lineBreaks = 0
            val start = pos
            var lastNewline = -1
            while (pos < text.length && text[pos].isWhitespace()) {
                if (text[pos] == '\n') {
                    lineBreaks++
                    lastNewline = pos
                }
                pos++
            }
            val indentStart = if (lastNewline >= 0) lastNewline + 1 else start
            lastLineIndent = text.substring(indentStart, pos)
        }

        fun sawLineBreak(): Boolean = lineBreaks >= 1
        fun sawBlankLine(): Boolean = lineBreaks >= 2

        fun startsWith(prefix: String): Boolean = text.startsWith(prefix, pos)

        /** 引号感知地找到闭合 `>` 的位置（属性值里的 `>` 不算） */
        fun readTagEnd(): Int {
            var i = pos
            var quote: Char? = null
            while (i < text.length) {
                val ch = text[i]
                if (quote != null) {
                    if (ch == quote) quote = null
                } else {
                    if (ch == '"' || ch == '\'') {
                        quote = ch
                    } else if (ch == '>') {
                        return i
                    }
                }
                i++
            }
            return -1
        }
    }

    /**
     * 引号感知地找到标签闭合 `>` 的下标；找不到返回 -1。
     * 属性值里的裸 `>`（`test="size() > 0"`）不算标签结束 —— [depthAt] / [isMapperXml] 都依赖它。
     */
    private fun tagEnd(text: String, from: Int): Int {
        var quote: Char? = null
        var i = from
        while (i < text.length) {
            val ch = text[i]
            if (quote != null) {
                if (ch == quote) quote = null
            } else {
                when {
                    ch == '"' || ch == '\'' -> quote = ch
                    ch == '>' -> return i
                }
            }
            i++
        }
        return -1
    }

    /** 标签原文（跳过开头的 `<`）里是否还有引号外的裸 `<`：有就说明这不是一个标签 */
    private fun hasUnquotedLt(raw: String): Boolean {
        var quote: Char? = null
        for (i in 1 until raw.length) {
            val ch = raw[i]
            if (quote != null) {
                if (ch == quote) quote = null
            } else {
                when {
                    ch == '"' || ch == '\'' -> quote = ch
                    ch == '<' -> return true
                }
            }
        }
        return false
    }

    /**
     * `<` 是否真的开启一个标签。SQL 正文里裸写的 `<`（`a < 10`、`<=`）不是标签。
     * 引号内的裸 `<`（`test="age < 18"`）是合法属性值，不算标签截断。
     */
    private fun looksLikeTagStart(text: String, pos: Int): Boolean {
        if (pos + 1 >= text.length) return false
        val next = text[pos + 1]
        if (next == '/' || next == '!' || next == '?') return true
        if (!next.isLetter()) return false
        val end = tagEnd(text, pos)
        if (end < 0) return false
        val raw = text.substring(pos, end + 1)
        if (hasUnquotedLt(raw)) return false
        val inner = raw.removePrefix("<").removeSuffix(">")
        val name = inner.takeWhile { !it.isWhitespace() && it != '/' }
        if (name.isBlank() || !name[0].isLetter()) return false
        val rest = inner.substring(name.length)
        return rest.isEmpty() || rest[0].isWhitespace() || rest[0] == '/'
    }

    /** 从 [from] 起找下一个真标签起点；没有就返回文本末尾（中间的裸 `<` 都算正文） */
    private fun nextTagStart(text: String, from: Int): Int {
        var i = from
        while (i < text.length) {
            val lt = text.indexOf('<', i)
            if (lt < 0) return text.length
            if (looksLikeTagStart(text, lt)) return lt
            i = lt + 1
        }
        return text.length
    }

    private fun parse(text: String): List<Node>? {
        val scanner = TagScanner(text)
        val root = ArrayList<Node>()
        val stack = ArrayList<ElementBuilder>()
        try {
            while (scanner.pos < text.length) {
                scanner.skipWhitespace()
                if (scanner.pos >= text.length) break
                val blank = scanner.sawBlankLine()
                val lineBreak = scanner.sawLineBreak()
                when {
                    scanner.startsWith("<!--") -> {
                        val end = text.indexOf("-->", scanner.pos + 4)
                        if (end < 0) return null
                        val raw = text.substring(scanner.pos, end + 3)
                        scanner.pos = end + 3
                        add(root, stack, Comment(raw, blank), lineBreak)
                    }
                    scanner.startsWith("<![CDATA[") -> {
                        val end = text.indexOf("]]>", scanner.pos + 9)
                        if (end < 0) return null
                        val raw = text.substring(scanner.pos, end + 3)
                        scanner.pos = end + 3
                        add(root, stack, CData(raw, blank), lineBreak)
                    }
                    scanner.startsWith("<?") -> {
                        val end = text.indexOf("?>", scanner.pos + 2)
                        if (end < 0) return null
                        val raw = text.substring(scanner.pos, end + 2)
                        scanner.pos = end + 2
                        add(root, stack, Pi(raw, blank), lineBreak)
                    }
                    scanner.startsWith("<!") -> {
                        val end = scanner.readTagEnd()
                        if (end < 0) return null
                        val raw = text.substring(scanner.pos, end + 1)
                        scanner.pos = end + 1
                        add(root, stack, Pi(raw, blank), lineBreak)
                    }
                    scanner.startsWith("</") -> {
                        val end = scanner.readTagEnd()
                        if (end < 0) return null
                        if (stack.isEmpty()) return null
                        val builder = stack.removeAt(stack.lastIndex)
                        scanner.pos = end + 1
                        if (stack.isEmpty()) {
                            root += builder.build()
                        } else {
                            stack.last().children += builder.build()
                        }
                    }
                    scanner.startsWith("<") -> {
                        if (!looksLikeTagStart(text, scanner.pos)) {
                            // 裸 `<` 是 SQL 正文：连它带后面的内容一起读进同一个文本节点，不断行
                            val stop = nextTagStart(text, scanner.pos + 1)
                            add(
                                root, stack,
                                Text(text.substring(scanner.pos, stop), scanner.lastLineIndent, blank),
                                lineBreak
                            )
                            scanner.pos = stop
                            continue
                        }
                        val end = scanner.readTagEnd()
                        if (end < 0) return null
                        val raw = text.substring(scanner.pos, end + 1)
                        scanner.pos = end + 1
                        // 自闭合：<xx ... /> —— `>` 前最后一个非空白字符是 `/`
                        val selfClosed = raw.dropLast(1).trimEnd().endsWith("/")
                        val inner = raw.removePrefix("<").removeSuffix(">")
                        val name = inner.takeWhile { !it.isWhitespace() && it != '/' }
                        val attrs = if (inner.length > name.length) {
                            inner.substring(name.length).trim().removeSuffix("/")
                        } else {
                            ""
                        }
                        if (selfClosed) {
                            add(root, stack, Element(name, attrs, emptyList(), true, blank), lineBreak)
                        } else {
                            stack += ElementBuilder(name, attrs, blank, lineBreak)
                        }
                    }
                    else -> {
                        // 文本节点：读到下一个真标签起点，中间的裸 `<` 也属于正文
                        val stop = nextTagStart(text, scanner.pos)
                        val raw = text.substring(scanner.pos, stop)
                        scanner.pos = stop
                        add(root, stack, Text(raw, scanner.lastLineIndent, blank), lineBreak)
                    }
                }
            }
        } catch (_: RuntimeException) {
            return null
        }
        if (stack.isNotEmpty()) return null
        return root
    }

    private fun add(root: MutableList<Node>, stack: MutableList<ElementBuilder>, node: Node, lineBreak: Boolean) {
        node.lineBreakBefore = lineBreak
        if (stack.isEmpty()) {
            root += node
        } else {
            stack.last().children += node
        }
    }

    private class ElementBuilder(
        val name: String,
        val attrs: String,
        val blankBefore: Boolean,
        lineBreak: Boolean,
    ) {
        val children: MutableList<Node> = ArrayList()
        private val nodeLineBreak = lineBreak

        fun build(): Element = Element(name, attrs, children.toList(), false, blankBefore)
            .also { it.lineBreakBefore = nodeLineBreak }
    }

    // ------------------------------------------------------------------ 内联判定

    private fun isInline(element: Element, options: Options): Boolean {
        if (element.selfClosed) return true
        if (element.children.any { it !is Text }) return false
        val text = element.children.filterIsInstance<Text>().joinToString("") { it.raw }
        if (text.contains('\n')) return false
        val length = 1 + element.name.length + attrSuffix(element.attrs).length + text.trim().length + element.name.length + 3
        return length <= options.safeInlineMax
    }

    private fun attrSuffix(attrs: String): String {
        if (attrs.isEmpty()) return ""
        val normalized = normalizeAttrs(attrs)
        return if (normalized.isEmpty()) "" else " $normalized"
    }

    /**
     * 归一属性区空白：属性名与 `=` 之间的空白去掉、换行与连续空白压成单空格。
     * 引号内（属性值）不动 —— `test="name != null"` 里的 `!=` 不能被当成赋值等号。
     */
    private fun normalizeAttrs(attrs: String): String {
        val builder = StringBuilder(attrs.length)
        var quote: Char? = null
        var pendingWhitespace = false
        var i = 0
        while (i < attrs.length) {
            val ch = attrs[i]
            if (quote != null) {
                builder.append(ch)
                if (ch == quote) quote = null
                i++
                continue
            }
            when {
                ch == '"' || ch == '\'' -> {
                    if (pendingWhitespace && builder.isNotEmpty()) builder.append(' ')
                    pendingWhitespace = false
                    quote = ch
                    builder.append(ch)
                    i++
                }
                ch == '=' -> {
                    while (builder.isNotEmpty() && builder.last().isWhitespace()) {
                        builder.setLength(builder.length - 1)
                    }
                    builder.append('=')
                    pendingWhitespace = false
                    i++
                    while (i < attrs.length && attrs[i].isWhitespace()) i++
                }
                ch.isWhitespace() -> {
                    pendingWhitespace = true
                    i++
                }
                else -> {
                    if (pendingWhitespace && builder.isNotEmpty()) builder.append(' ')
                    pendingWhitespace = false
                    builder.append(ch)
                    i++
                }
            }
        }
        return builder.toString().trim()
    }

    private fun openTag(element: Element, selfClosed: Boolean): String {
        val suffix = attrSuffix(element.attrs)
        return if (selfClosed) "<${element.name}$suffix/>" else "<${element.name}$suffix>"
    }

    private fun inlineText(element: Element): String {
        if (element.selfClosed) return openTag(element, selfClosed = true)
        val inner = element.children.filterIsInstance<Text>()
            .joinToString("") { it.raw }
            .trim()
        return openTag(element, selfClosed = false) + inner + "</${element.name}>"
    }

    // ------------------------------------------------------------------ 行拼装

    /**
     * 行缓冲：所有输出行都先经过它，以便「SQL 文本 + 内联标签」写在同一行时
     * 后者能直接接在前者末尾，而不是被拆成两行。
     */
    private class Emitter(val options: Options) {
        private val lines: MutableList<String> = ArrayList()
        private var current: StringBuilder? = null

        fun hasCurrent(): Boolean = current != null

        fun flushCurrent() {
            current?.let {
                lines += it.toString()
                current = null
            }
        }

        /** 往当前行末尾追加；没有当前行时按 prefix 起新行，但**不结束**这一行 */
        fun writeInline(prefix: String, text: String) {
            if (text.isEmpty()) return
            val sb = current ?: StringBuilder(prefix).also { current = it }
            sb.append(text)
        }

        /** 直接接在当前行末尾（与前面的内联元素同一行，不补缩进） */
        fun appendToCurrent(text: String) {
            val sb = current ?: StringBuilder().also { current = it }
            sb.append(text)
        }

        /** 结束当前行并写一整行 */
        fun writeLine(prefix: String, text: String) {
            flushCurrent()
            lines += prefix + text
        }

        fun blankLine() {
            flushCurrent()
            lines += ""
        }

        /** [finish] 之后可读取：去掉开头空行后的行内容，供选区整体平移 */
        fun finishedLines(): List<String> = lines.toList()

        fun finish(): String {
            flushCurrent()
            var drop = 0
            while (drop < lines.size && lines[drop].isEmpty()) drop++
            if (drop > 0) {
                val kept = lines.subList(drop, lines.size).toList()
                lines.clear()
                lines.addAll(kept)
            }
            return lines.joinToString("\n") + "\n"
        }
    }

    private data class ParsedLine(val prefix: String, val columns: Int, val body: String) {
        /** 去掉行尾空白后的内容；[body] 保留行尾空白，用于「后面还要接标签」的那一行 */
        val content: String get() = body.trimEnd()
    }

    /** 把一段原文拆成行，记录每行的前导空白与按缩进单位换算出的列数（tab 计一级） */
    private fun parseLines(raw: String, leading: String, unit: Int): List<ParsedLine> =
        raw.split('\n').mapIndexed { index, line ->
            val inlinePrefix = line.takeWhile { it == ' ' || it == '\t' }
            // 第一行的前导空白被 parse 的 skipWhitespace 吃掉了，用节点上记的那份还原
            val prefix = if (index == 0) leading else inlinePrefix
            val columns = prefix.sumOf { if (it == '\t') unit else 1 }
            ParsedLine(prefix, columns, line.substring(inlinePrefix.length))
        }

    /**
     * 联合缩进的核心：一段文本（SQL 正文 / CDATA 等）按行重排。
     *
     * - 首尾空行丢弃，中间连续空行收敛为一行，行尾空白去掉；
     * - 非空行的前导宽度都是缩进单位的整数倍时，取**最小值作基线**，每行输出
     *   `indent(level) + (该行宽度 - 基线)`：SQL 内部的相对层次逐行保留，整体挂在 XML 层级上；
     * - 出现不规整的零散空格（如 3 个空格）时没有可信基线，全部按 XML 层级对齐；
     * - [keepOpen] 表示这段文本后面还要接内联标签（`AND id IN <foreach>`），最后一行
     *   不结束，且保留行尾空白作为分隔；
     * - [lineBreakBefore] 为 false 且当前行还开着时，本段第一行接在上一行末尾（不另起行）。
     *
     * 幂等性：输出行的前导宽度 = 骨架宽度 + 相对层次，重新解析时基线正好是骨架宽度，
     * 相对层次不变，反复格式化结果稳定。
     */
    private fun emitLines(
        parsed: List<ParsedLine>,
        level: Int,
        emitter: Emitter,
        lineBreakBefore: Boolean,
        keepOpen: Boolean,
    ) {
        var first = 0
        var last = parsed.lastIndex
        while (first <= last && parsed[first].content.isEmpty()) first++
        while (last >= first && parsed[last].content.isEmpty()) last--
        if (first > last) return

        val unit = emitter.options.safeIndent
        val nonBlank = (first..last).filter { parsed[it].content.isNotEmpty() }
        val aligned = nonBlank.all { parsed[it].columns % unit == 0 }
        val baseline = if (aligned) nonBlank.minOf { parsed[it].columns } else 0
        val base = emitter.options.indent(level)
        var previousBlank = false
        for (i in first..last) {
            val line = parsed[i]
            if (line.content.isEmpty()) {
                if (!previousBlank) emitter.blankLine()
                previousBlank = true
                continue
            }
            previousBlank = false
            val relative = if (aligned) line.columns - baseline else 0
            val prefix = base + " ".repeat(relative.coerceAtLeast(0))
            val isFirst = i == first
            val isLast = i == last
            val body = if (keepOpen && isLast) line.body else line.content
            when {
                isFirst && !lineBreakBefore && emitter.hasCurrent() -> {
                    // 与前面的内联元素同一行；被 skipWhitespace 吃掉的分隔空白压成一个空格
                    val gap = if (line.prefix.isNotEmpty()) " " else ""
                    emitter.appendToCurrent(gap + body)
                }
                keepOpen && isLast -> emitter.writeInline(prefix, body)
                else -> emitter.writeLine(prefix, line.content)
            }
        }
    }

    private fun render(node: Node, level: Int, emitter: Emitter) {
        if (node.blankBefore) {
            emitter.blankLine()
        } else if (node.lineBreakBefore) {
            // 前面有换行：先结束当前行。否则「上一行还开着 + 本段文本末尾还要接内联标签」
            // 会把本该另起一行的 SQL 粘在上一行末尾。
            // 没有换行的文本节点不走这里，emitLines 才能把它接在前面的内联元素后面。
            emitter.flushCurrent()
        }
        when (node) {
            is Element -> renderElement(node, level, emitter)
            is Comment -> renderComment(node, level, emitter)
            is CData -> renderCData(node, level, emitter)
            is Pi -> node.raw.lines().forEach { line ->
                val trimmed = line.trim()
                if (trimmed.isNotEmpty()) emitter.writeLine("", trimmed)
            }
            is Text -> renderText(node, level, emitter)
        }
    }

    private fun renderElement(element: Element, level: Int, emitter: Emitter) {
        val indent = emitter.options.indent(level)
        if (element.selfClosed) {
            emitter.writeInline(indent, openTag(element, selfClosed = true))
            return
        }
        if (isInline(element, emitter.options)) {
            emitter.writeInline(indent, inlineText(element))
            return
        }
        emitter.writeLine(indent, openTag(element, selfClosed = false))
        for (child in element.children) {
            render(child, level + 1, emitter)
        }
        emitter.writeLine(indent, "</${element.name}>")
    }

    private fun renderText(node: Text, level: Int, emitter: Emitter) {
        val lastNewline = node.raw.lastIndexOf('\n')
        // 末尾是否以换行收束：不是的话，后面那个标签在原文里与这段 SQL 同一行
        val endsWithLineBreak = lastNewline >= 0 && node.raw.substring(lastNewline + 1).isBlank()
        val lines = parseLines(node.raw, node.leading, emitter.options.safeIndent)
        emitLines(lines, level, emitter, node.lineBreakBefore, keepOpen = !endsWithLineBreak)
    }

    private fun renderComment(node: Comment, level: Int, emitter: Emitter) {
        val raw = node.raw
        val body = raw.removePrefix("<!--").removeSuffix("-->")
        if (!raw.contains('\n')) {
            val trimmed = body.trim()
            emitter.writeLine(
                emitter.options.indent(level),
                "<!--${if (trimmed.isEmpty()) "" else " $trimmed "}-->"
            )
            return
        }
        emitter.writeLine(emitter.options.indent(level), "<!--")
        body.lines().map { it.trim() }.filter { it.isNotEmpty() }
            .forEach { emitter.writeLine(emitter.options.indent(level + 1), it) }
        emitter.writeLine(emitter.options.indent(level), "-->")
    }

    private fun renderCData(node: CData, level: Int, emitter: Emitter) {
        val raw = node.raw
        val body = raw.removePrefix("<![CDATA[").removeSuffix("]]>")
        if (!raw.contains('\n')) {
            emitter.writeLine(emitter.options.indent(level), "<![CDATA[${body.trim()}]]>")
            return
        }
        // 多行 CDATA 里常写着带比较运算符的 SQL：标记行按 XML 层级，内部行保留相对层次。
        // 基线只由内部行计算，否则 `<![CDATA[` 那一行的顶格会把整段 SQL 白推深一级。
        val unit = emitter.options.safeIndent
        val innerLines = body.split('\n').map { line ->
            val prefix = line.takeWhile { it == ' ' || it == '\t' }
            ParsedLine(prefix, prefix.sumOf { c -> if (c == '\t') unit else 1 }, line.substring(prefix.length).trimEnd())
        }.filter { it.content.isNotEmpty() }
        val baseline = innerLines.minOfOrNull { it.columns } ?: 0
        val outer = emitter.options.indent(level)
        val base = emitter.options.indent(level + 1)
        emitter.writeLine(outer, "<![CDATA[")
        for (line in innerLines) {
            emitter.writeLine(base + " ".repeat((line.columns - baseline).coerceAtLeast(0)), line.content)
        }
        emitter.writeLine(outer, "]]>")
    }

    // ------------------------------------------------------------------ 深度计算（Enter 缩进）

    private fun depthAt(text: String, offset: Int): Int {
        var depth = 0
        var i = 0
        while (i < text.length && i < offset) {
            when {
                text.startsWith("<!--", i) -> {
                    val end = text.indexOf("-->", i + 4)
                    i = if (end < 0) offset else end + 3
                }
                text.startsWith("<![CDATA[", i) -> {
                    val end = text.indexOf("]]>", i + 9)
                    i = if (end < 0) offset else end + 3
                }
                text.startsWith("<?", i) -> {
                    val end = text.indexOf("?>", i + 2)
                    i = if (end < 0) offset else end + 2
                }
                text.startsWith("</", i) -> {
                    val end = tagEnd(text, i)
                    if (end < 0) break
                    depth--
                    i = end + 1
                }
                text.startsWith("<!", i) -> {
                    // DOCTYPE / 其它声明：不参与层级
                    val end = tagEnd(text, i)
                    if (end < 0) break
                    i = end + 1
                }
                text[i] == '<' && i + 1 < text.length && text[i + 1].isLetter() -> {
                    // tagEnd 引号感知：test="size() > 0" 里的 `>` 不会把标签截在半路
                    val end = tagEnd(text, i)
                    if (end < 0) break
                    if (!text.substring(i, end).trimEnd().endsWith("/")) depth++
                    i = end + 1
                }
                else -> i++   // SQL 正文里的裸 `<` / `>` 都不参与层级
            }
        }
        return depth.coerceAtLeast(0)
    }

    private const val MAPPER_PREFIX: String = "<mapper"
    private val NAMESPACE_ATTR: Regex = Regex("""\bnamespace\s*=""")
}
