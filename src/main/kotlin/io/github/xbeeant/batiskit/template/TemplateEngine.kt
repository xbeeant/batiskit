package io.github.xbeeant.batiskit.template

import java.util.concurrent.ConcurrentHashMap

/**
 * 模板语法错误的定位信息：模板名 + 行列 + 原因。
 *
 * 用户自己写模板时，报错必须能指到具体位置，否则「模板渲染失败」等于没报。
 */
class TemplateException(
    val templateName: String,
    val line: Int,
    val column: Int,
    val reason: String
) : RuntimeException("$templateName:$line:$column $reason")

/** 模板里可调用的函数（由模型注入，例如 `$util.param($c)`） */
fun interface TemplateFunction {
    fun call(args: List<Any?>): Any?
}

/**
 * 轻量模板引擎：Velocity 语法的一个子集，零第三方依赖。
 *
 * 支持的语法：
 *  - 引用：`$name`、`$name.prop`、`$list.size()`、`${name}`、`$obj.method($arg)`
 *  - 大括号占位符：`{Model}`、`{pkg.entity}`、`{fields}` —— 编译前降级成 `${...}`，
 *    只认 [TemplatePlaceholders.NAMES] 里的词表，`/{id}`、`#{key}` 这类原样保留
 *  - 指令：`#if(...)` / `#elseif(...)` / `#else` / `#end`
 *          `#foreach($c in $list)` / `#end`（循环内可用 `$foreach.index` / `$foreach.count` /
 *          `$foreach.first` / `$foreach.last`）
 *          `#set($x = ...)`
 *  - 注释：`## 行注释`、`#* 块注释 *#`
 *  - 原样输出：`#[[ ... ]]#`（里面不解析任何语法，写 JS/CSS 模板时用）
 *  - 转义：`\$`、`\#`、`\\`
 *
 * 表达式：`+ - * /`（`+` 在任一侧是字符串时做拼接）、`== != < <= > >=`、`&& || !`、括号、字符串字面量、
 * 数字、布尔、null。真值判定与 Velocity 一致：null / false / 空串 / 空集合 / 0 为假。
 *
 * 未定义的变量输出空串而不是抛异常（Velocity 语义），但会记录在 [render] 的 warnings 里，
 * 便于把「模板写错了」和「这段本来就没有值」区分开。
 */
class Template private constructor(
    private val nodes: List<Node>,
    private val lineStarts: IntArray
) {

    companion object {

        /** 缓存上限：用户反复改同一份模板时不让它无限长（超了整表清空，重建代价很低） */
        private const val MAX_CACHED = 256

        /** 编译结果缓存：键是「模板名 + 源码 + 大括号词表」，用内容相等而不是 hashCode，避免撞键 */
        private val CACHE = ConcurrentHashMap<Key, Template>()

        private data class Key(val name: String, val source: String, val names: Set<String>)

        /**
         * 编译模板；结果按「源码 + 词表」缓存（内置模板每次生成都编译代价太高）。
         *
         * @param names 大括号占位符认可的根名字，传 [TemplatePlaceholders.namesOf] 的结果；
         *              不传时只用兜底词表（模板还没建模型、只做语法校验的场景）
         */
        fun compile(
            source: String,
            name: String = "template",
            names: Set<String> = TemplatePlaceholders.NAMES
        ): Template {
            val key = Key(name, source, names)
            CACHE[key]?.let { return it }
            // 大括号占位符先降级成 `${...}`：引擎只认一种引用语法，模板作者两种都能写
            val parser = Parser(TemplatePlaceholders.lower(source, names), name, names)
            val created = Template(parser.parse(), parser.lineStarts)
            if (CACHE.size >= MAX_CACHED) {
                CACHE.clear()
            }
            // 并发下可能有人先塞了同一份，那就用它的（两者内容必然一致）
            return CACHE.putIfAbsent(key, created) ?: created
        }
    }

    /**
     * 渲染模板。
     *
     * @param overlay 只对本次渲染可见、且优先于 [model] 的追加变量（例如按 output 路径推出的 `package`）。
     *                传这个参数比自己 `HashMap(model)` 复制一份再塞进去省一次复制——调用方的模型也不被改动。
     */
    fun render(model: Map<String, Any?>, overlay: Map<String, Any?> = emptyMap()): RenderResult {
        val scope = HashMap<String, Any?>(model.size + overlay.size)
        scope.putAll(model)
        scope.putAll(overlay)
        val out = StringBuilder()
        val warnings = mutableListOf<String>()
        appendTo(scope, out, warnings)
        return RenderResult(out.toString(), warnings)
    }

    /**
     * 在既有作用域里就地渲染，结果追加到 [out]。
     *
     * 双引号字符串的插值（`"${cfg.basePackage}.model.${entity}"`）走这里：不复制整份作用域，
     * 告警也回归同一个列表——否则字符串里写错变量名会静悄悄地渲染成空串。
     */
    private fun appendTo(scope: MutableMap<String, Any?>, out: StringBuilder, warnings: MutableList<String>) {
        Renderer(scope, out, warnings, this).renderNodes(nodes)
    }

    /** 渲染时出现的问题都不会中断渲染，收集起来交给调用方展示 */
    class RenderResult(val text: String, val warnings: List<String>) {
        val hasWarnings: Boolean get() = warnings.isNotEmpty()
    }

    // ------------------------------------------------------------------ AST

    private sealed interface Node

    private class Text(val text: String) : Node

    private class Ref(val expr: Expr) : Node

    private class If(val branches: List<Pair<Expr, List<Node>>>, val elseBody: List<Node>?, val offset: Int) : Node

    private class ForEach(val varName: String, val source: Expr, val body: List<Node>, val offset: Int) : Node

    private class Assign(val name: String, val value: Expr, val offset: Int) : Node

    private sealed interface Expr

    private class Literal(val value: Any?) : Expr

    /** 需要按当前作用域插值的字符串字面量（双引号） */
    private class Nested(val template: Template)

    /** `$a.b.c()` 拆成「根名 + 一串访问步骤」 */
    private class RefExpr(val name: String, val steps: List<Step>, val offset: Int = 0) : Expr

    private class Binary(val op: String, val left: Expr, val right: Expr) : Expr

    private class Unary(val op: String, val operand: Expr) : Expr

    private sealed interface Step

    private class Prop(val name: String) : Step

    private class Index(val expr: Expr) : Step

    private class Call(val name: String, val args: List<Expr>) : Step

    // ------------------------------------------------------------------ 渲染

    private class Renderer(
        private val scope: MutableMap<String, Any?>,
        private val out: StringBuilder,
        private val warnings: MutableList<String>,
        private val template: Template
    ) {

        fun renderNodes(nodes: List<Node>) {
            nodes.forEach { node ->
                when (node) {
                    is Text -> out.append(node.text)
                    is Ref -> out.append(stringify(evaluate(node.expr)))
                    is If -> renderIf(node)
                    is ForEach -> renderForEach(node)
                    is Assign -> scope[node.name] = evaluate(node.value)
                }
            }
        }

        private fun renderIf(node: If) {
            node.branches.forEach { (condition, body) ->
                if (truthy(evaluate(condition))) {
                    renderNodes(body)
                    return
                }
            }
            node.elseBody?.let { renderNodes(it) }
        }

        private fun renderForEach(node: ForEach) {
            val items = toIterable(evaluate(node.source))
            val previous = scope["foreach"]
            items.forEachIndexed { index, item ->
                scope[node.varName] = item
                scope["foreach"] = mapOf(
                    "index" to index,
                    "count" to index + 1,
                    "first" to (index == 0),
                    "last" to (index == items.size - 1)
                )
                renderNodes(node.body)
            }
            // 循环变量用完即弃，避免影响后续同名引用
            scope.remove(node.varName)
            if (previous == null) scope.remove("foreach") else scope["foreach"] = previous
        }

        private fun evaluate(expr: Expr): Any? = when (expr) {
            is Literal -> when (val value = expr.value) {
                // 双引号字符串的插值：直接用当前作用域渲染，不再复制一份
                is Nested -> StringBuilder().also { value.template.appendTo(scope, it, warnings) }.toString()
                else -> value
            }
            is RefExpr -> evaluateRef(expr)
            is Unary -> when (expr.op) {
                "!" -> !truthy(evaluate(expr.operand))
                "-" -> -(toNumber(evaluate(expr.operand)) ?: 0L)
                else -> null
            }

            is Binary -> evaluateBinary(expr)
        }

        private fun evaluateRef(expr: RefExpr): Any? {
            if (!scope.containsKey(expr.name)) {
                warnings += "第 ${template.lineOf(expr.offset)} 行：未定义的变量 \$${expr.name}"
                return null
            }
            var current = scope[expr.name]
            expr.steps.forEach { step ->
                current = applyStep(current, step)
            }
            return current
        }

        private fun applyStep(value: Any?, step: Step): Any? = when (step) {
            is Prop -> property(value, step.name)
            is Index -> when (value) {
                is List<*> -> toNumber(evaluate(step.expr))?.toInt()?.let { value.getOrNull(it) }
                is Map<*, *> -> value[evaluate(step.expr)]
                else -> null
            }

            is Call -> call(value, step.name, step.args.map { evaluate(it) })
        }

        private fun property(value: Any?, name: String): Any? = when (value) {
            is Map<*, *> -> value[name]
            is List<*> -> when (name) {
                "size", "length" -> value.size
                "isEmpty" -> value.isEmpty()
                else -> null
            }

            is String -> when (name) {
                "length", "size" -> value.length
                "isEmpty" -> value.isEmpty()
                else -> null
            }

            else -> null
        }

        private fun call(value: Any?, name: String, args: List<Any?>): Any? {
            if (value is Map<*, *>) {
                val fn = value[name]
                if (fn is TemplateFunction) {
                    return fn.call(args)
                }
            }
            // 常用字符串 / 集合方法，模板里做小加工时不必再往模型里塞函数
            return when (value) {
                is String -> when (name) {
                    "trim" -> value.trim()
                    "toUpperCase" -> value.uppercase()
                    "toLowerCase" -> value.lowercase()
                    "length", "size" -> value.length
                    "isEmpty" -> value.isEmpty()
                    "isNotEmpty" -> value.isNotEmpty()
                    "contains" -> value.contains(stringify(args.getOrNull(0)))
                    "startsWith" -> value.startsWith(stringify(args.getOrNull(0)))
                    "endsWith" -> value.endsWith(stringify(args.getOrNull(0)))
                    "replace" -> value.replace(
                        stringify(args.getOrNull(0)),
                        stringify(args.getOrNull(1))
                    )

                    "substring" -> {
                        val from = toNumber(args.getOrNull(0))?.toInt() ?: return null
                        val to = toNumber(args.getOrNull(1))?.toInt()
                        if (to == null) value.substring(from) else value.substring(from, to)
                    }

                    "cap" -> value.replaceFirstChar { it.uppercaseChar() }
                    "uncap" -> value.replaceFirstChar { it.lowercaseChar() }
                    else -> null
                }

                is List<*> -> when (name) {
                    "size", "length" -> value.size
                    "isEmpty" -> value.isEmpty()
                    "isNotEmpty" -> value.isNotEmpty()
                    "contains" -> value.contains(args.getOrNull(0))
                    "join" -> {
                        val sep = stringify(args.getOrNull(0))
                        value.joinToString(sep) { stringify(it) }
                    }

                    else -> null
                }

                else -> null
            }
        }

        private fun evaluateBinary(expr: Binary): Any? {
            // 短路
            if (expr.op == "&&") {
                return truthy(evaluate(expr.left)) && truthy(evaluate(expr.right))
            }
            if (expr.op == "||") {
                return truthy(evaluate(expr.left)) || truthy(evaluate(expr.right))
            }
            val left = evaluate(expr.left)
            val right = evaluate(expr.right)
            return when (expr.op) {
                "==" -> equalsValue(left, right)
                "!=" -> !equalsValue(left, right)
                "+" -> if (left is String || right is String) {
                    stringify(left) + stringify(right)
                } else {
                    arithmetic(left, right) { a, b -> a + b }
                }

                "-" -> arithmetic(left, right) { a, b -> a - b }
                "*" -> arithmetic(left, right) { a, b -> a * b }
                "/" -> arithmetic(left, right) { a, b -> if (b == 0L) 0L else a / b }
                "<" -> compare(left, right) < 0
                "<=" -> compare(left, right) <= 0
                ">" -> compare(left, right) > 0
                ">=" -> compare(left, right) >= 0
                else -> null
            }
        }

        private fun arithmetic(left: Any?, right: Any?, op: (Long, Long) -> Long): Any? {
            val a = toNumber(left) ?: return null
            val b = toNumber(right) ?: return null
            return op(a, b)
        }

        private fun compare(left: Any?, right: Any?): Int {
            val a = toNumber(left)
            val b = toNumber(right)
            if (a != null && b != null) {
                return a.compareTo(b)
            }
            return stringify(left).compareTo(stringify(right))
        }

        private fun equalsValue(left: Any?, right: Any?): Boolean {
            if (left == null || right == null) {
                return left == null && right == null
            }
            val a = toNumber(left)
            val b = toNumber(right)
            if (a != null && b != null) {
                return a == b
            }
            return if (left is String || right is String) {
                stringify(left) == stringify(right)
            } else {
                left == right
            }
        }

        private fun stringify(value: Any?): String = value?.toString() ?: ""

        private fun toIterable(value: Any?): List<Any?> = when (value) {
            null -> emptyList()
            is List<*> -> value
            is Collection<*> -> value.toList()
            is Array<*> -> value.toList()
            is Map<*, *> -> value.entries.toList()
            else -> listOf(value)
        }

        private fun toNumber(value: Any?): Long? = when (value) {
            is Number -> value.toLong()
            is String -> value.toLongOrNull()
            is Boolean -> if (value) 1L else 0L
            else -> null
        }

        private fun truthy(value: Any?): Boolean = when (value) {
            null -> false
            is Boolean -> value
            is String -> value.isNotEmpty()
            is Number -> value.toLong() != 0L
            is Collection<*> -> value.isNotEmpty()
            is Map<*, *> -> value.isNotEmpty()
            is Array<*> -> value.isNotEmpty()
            else -> true
        }
    }

    private fun lineOf(offset: Int): Int = lineOf(lineStarts, offset)

    // ------------------------------------------------------------------ 解析

    private class Parser(
        private val source: String,
        private val name: String,
        private val names: Set<String>
    ) {

        private var pos = 0

        /** 每行起始偏移，报错时把偏移量换成行列号；解析与报错共用这一份，不重复扫源码 */
        val lineStarts: IntArray = run {
            val starts = mutableListOf(0)
            source.forEachIndexed { index, c ->
                if (c == '\n') starts += index + 1
            }
            starts.toIntArray()
        }

        fun parse(): List<Node> {
            val result = parseBlock(emptySet())
            if (result.terminator != null) {
                // 多余的 #end / #else：语法错误
                fail(result.terminatorOffset, "多余的 #${result.terminator}")
            }
            return result.nodes
        }

        private class BlockResult(val nodes: List<Node>, val terminator: String?, val terminatorOffset: Int)

        private fun parseBlock(stopOn: Set<String>): BlockResult {
            val nodes = mutableListOf<Node>()
            val text = StringBuilder()

            fun flush() {
                if (text.isNotEmpty()) {
                    nodes += Text(text.toString())
                    text.clear()
                }
            }

            while (pos < source.length) {
                val c = source[pos]
                when {
                    c == '\\' && pos + 1 < source.length && source[pos + 1] in "\\$#{" -> {
                        text.append(source[pos + 1])
                        pos += 2
                    }

                    c == '#' && source.startsWith("#[[", pos) -> {
                        val end = source.indexOf("]]#", pos + 3)
                        if (end < 0) fail(pos, "#[[ 没有对应的 ]]#")
                        text.append(source, pos + 3, end)
                        pos = end + 3
                    }

                    c == '#' && source.startsWith("##", pos) -> {
                        // 行注释把行尾换行一起吃掉，模板排版才不会被空行撑开
                        val end = source.indexOf('\n', pos)
                        pos = if (end < 0) source.length else end + 1
                    }

                    c == '#' && source.startsWith("#*", pos) -> {
                        val end = source.indexOf("*#", pos + 2)
                        if (end < 0) fail(pos, "#* 没有对应的 *#")
                        pos = end + 2
                    }

                    c == '#' -> {
                        val start = pos
                        val word = readWord(pos + 1)
                        if (word.isEmpty()) {
                            text.append('#')
                            pos++
                        } else if (word in stopOn) {
                            val aloneOnLine = standalone(start)
                            if (aloneOnLine) trimLeader(text)
                            flush()
                            pos += 1 + word.length
                            // #else / #end 独占一行时连同换行一起吃掉（行级修剪）；
                            // 写在行中间的 `#if($x),#end` 不能吃换行，否则整段会挤成一行
                            if (aloneOnLine && word != "elseif") skipBlankTail()
                            return BlockResult(nodes, word, start)
                        } else {
                            when (word) {
                                "if" -> {
                                    trimLeader(text, start)
                                    flush()
                                    nodes += parseIf(start)
                                }

                                "foreach" -> {
                                    trimLeader(text, start)
                                    flush()
                                    nodes += parseForEach(start)
                                }

                                "set" -> {
                                    trimLeader(text, start)
                                    flush()
                                    nodes += parseSet(start)
                                }

                                "else", "elseif", "end" -> fail(start, "多余的 #$word")
                                else -> {
                                    // 不认识的 #word 原样输出（例如 #{} 之外的井号）
                                    text.append('#')
                                    pos++
                                }
                            }
                        }
                    }

                    c == '$' -> {
                        val expr = tryParseRef()
                        if (expr == null) {
                            text.append('$')
                            pos++
                        } else {
                            flush()
                            nodes += Ref(expr)
                        }
                    }

                    else -> {
                        text.append(c)
                        pos++
                    }
                }
            }
            flush()
            return BlockResult(nodes, null, pos)
        }

        private fun parseIf(offset: Int): Node {
            pos += 3 // if
            val branches = mutableListOf<Pair<Expr, List<Node>>>()
            val condition = parseCondition("if")
            var body = parseBlock(setOf("elseif", "else", "end"))
            branches += condition to body.nodes
            var elseBody: List<Node>? = null
            while (true) {
                when (body.terminator) {
                    "elseif" -> {
                        val condition2 = parseCondition("elseif")
                        body = parseBlock(setOf("elseif", "else", "end"))
                        branches += condition2 to body.nodes
                    }

                    "else" -> {
                        val result = parseBlock(setOf("end"))
                        elseBody = result.nodes
                        if (result.terminator != "end") fail(offset, "#if 缺少 #end")
                        break
                    }

                    "end" -> break
                    else -> fail(offset, "#if 缺少 #end")
                }
            }
            return If(branches, elseBody, offset)
        }

        /** 解析 `#if(...)` 的括号表达式，括号可省略（容错） */
        private fun parseCondition(directive: String): Expr {
            skipSpaces()
            if (pos < source.length && source[pos] == '(') {
                pos++
                val expr = parseExpression()
                skipSpaces()
                if (pos >= source.length || source[pos] != ')') fail(pos, "#$directive 的括号没有闭合")
                pos++
                skipBlankTail()
                return expr
            }
            fail(pos, "#$directive 后面缺少条件表达式")
        }

        private fun parseForEach(offset: Int): Node {
            pos += 1 + "foreach".length
            skipSpaces()
            if (pos >= source.length || source[pos] != '(') fail(pos, "#foreach 后面缺少 (")
            pos++
            skipSpaces()
            if (pos < source.length && source[pos] == '$') pos++
            val varName = readIdentifier()
            if (varName.isEmpty()) fail(pos, "#foreach 缺少循环变量")
            skipSpaces()
            val keyword = readWord(pos)
            if (keyword != "in") fail(pos, "#foreach 只支持 `in`，实际是 `$keyword`")
            pos += keyword.length
            val expr = parseExpression()
            skipSpaces()
            if (pos >= source.length || source[pos] != ')') fail(pos, "#foreach 的括号没有闭合")
            pos++
            skipBlankTail()
            val body = parseBlock(setOf("end"))
            if (body.terminator != "end") fail(offset, "#foreach 缺少 #end")
            return ForEach(varName, expr, body.nodes, offset)
        }

        private fun parseSet(offset: Int): Node {
            pos += 1 + "set".length
            skipSpaces()
            if (pos >= source.length || source[pos] != '(') fail(pos, "#set 后面缺少 (")
            pos++
            skipSpaces()
            val varStart = pos
            if (pos < source.length && source[pos] == '$') pos++
            val varName = readIdentifier()
            if (varName.isEmpty()) fail(varStart, "#set 缺少变量名")
            skipSpaces()
            if (pos >= source.length || source[pos] != '=') fail(pos, "#set 缺少 =")
            pos++
            val value = parseExpression()
            skipSpaces()
            if (pos >= source.length || source[pos] != ')') fail(pos, "#set 的括号没有闭合")
            pos++
            skipBlankTail()
            return Assign(varName, value, offset)
        }

        // -------------------------------------------------------------- 引用

        /** `$name.prop(...)[i]`；不是合法引用时返回 null（当成普通 `$` 字符） */
        private fun tryParseRef(): RefExpr? {
            val start = pos
            pos++ // $
            val braced = pos < source.length && source[pos] == '{'
            if (braced) pos++
            skipSpacesForRef()
            val name = readIdentifier()
            if (name.isEmpty()) {
                pos = start
                return null
            }
            val steps = mutableListOf<Step>()
            while (pos < source.length) {
                when (source[pos]) {
                    '.' -> {
                        val dotStart = pos
                        pos++
                        val member = readIdentifier()
                        if (member.isEmpty()) {
                            pos = dotStart
                            break
                        }
                        if (pos < source.length && source[pos] == '(') {
                            steps += Call(member, parseArguments())
                        } else {
                            steps += Prop(member)
                        }
                    }

                    '[' -> {
                        pos++
                        steps += Index(parseExpression())
                        if (pos >= source.length || source[pos] != ']') fail(pos, "下标表达式没有闭合")
                        pos++
                    }

                    else -> break
                }
            }
            if (braced) {
                if (pos >= source.length || source[pos] != '}') fail(pos, "\${...} 缺少 }")
                pos++
            }
            return RefExpr(name, steps, start)
        }

        private fun parseArguments(): List<Expr> {
            pos++ // (
            val args = mutableListOf<Expr>()
            skipSpaces()
            if (pos < source.length && source[pos] == ')') {
                pos++
                return args
            }
            while (true) {
                args += parseExpression()
                skipSpaces()
                if (pos < source.length && source[pos] == ',') {
                    pos++
                    continue
                }
                if (pos < source.length && source[pos] == ')') {
                    pos++
                    return args
                }
                fail(pos, "函数调用的参数列表没有闭合")
            }
        }

        // -------------------------------------------------------------- 表达式

        private fun parseExpression(): Expr = parseOr()

        private fun parseOr(): Expr {
            var left = parseAnd()
            while (true) {
                skipSpaces()
                if (source.startsWith("||", pos)) {
                    pos += 2
                    left = Binary("||", left, parseAnd())
                } else {
                    return left
                }
            }
        }

        private fun parseAnd(): Expr {
            var left = parseEquality()
            while (true) {
                skipSpaces()
                if (source.startsWith("&&", pos)) {
                    pos += 2
                    left = Binary("&&", left, parseEquality())
                } else {
                    return left
                }
            }
        }

        private fun parseEquality(): Expr {
            var left = parseRelational()
            while (true) {
                skipSpaces()
                when {
                    source.startsWith("==", pos) -> {
                        pos += 2
                        left = Binary("==", left, parseRelational())
                    }

                    source.startsWith("!=", pos) -> {
                        pos += 2
                        left = Binary("!=", left, parseRelational())
                    }

                    else -> return left
                }
            }
        }

        private fun parseRelational(): Expr {
            var left = parseAdditive()
            while (true) {
                skipSpaces()
                when {
                    source.startsWith("<=", pos) -> {
                        pos += 2
                        left = Binary("<=", left, parseAdditive())
                    }

                    source.startsWith(">=", pos) -> {
                        pos += 2
                        left = Binary(">=", left, parseAdditive())
                    }

                    source.startsWith("<", pos) && !source.startsWith("<=", pos) -> {
                        pos += 1
                        left = Binary("<", left, parseAdditive())
                    }

                    source.startsWith(">", pos) && !source.startsWith(">=", pos) -> {
                        pos += 1
                        left = Binary(">", left, parseAdditive())
                    }

                    else -> return left
                }
            }
        }

        private fun parseAdditive(): Expr {
            var left = parseMultiplicative()
            while (true) {
                skipSpaces()
                when {
                    source.startsWith("+", pos) -> {
                        pos += 1
                        left = Binary("+", left, parseMultiplicative())
                    }

                    source.startsWith("-", pos) -> {
                        pos += 1
                        left = Binary("-", left, parseMultiplicative())
                    }

                    else -> return left
                }
            }
        }

        private fun parseMultiplicative(): Expr {
            var left = parseUnary()
            while (true) {
                skipSpaces()
                when {
                    source.startsWith("*", pos) -> {
                        pos += 1
                        left = Binary("*", left, parseUnary())
                    }

                    source.startsWith("/", pos) -> {
                        pos += 1
                        left = Binary("/", left, parseUnary())
                    }

                    else -> return left
                }
            }
        }

        private fun parseUnary(): Expr {
            skipSpaces()
            if (pos < source.length && source[pos] == '!') {
                pos++
                return Unary("!", parseUnary())
            }
            if (pos < source.length && source[pos] == '-') {
                pos++
                return Unary("-", parseUnary())
            }
            return parsePrimary()
        }

        private fun parsePrimary(): Expr {
            skipSpaces()
            if (pos >= source.length) fail(pos, "表达式不完整")
            val c = source[pos]
            if (c == '(') {
                pos++
                val expr = parseExpression()
                skipSpaces()
                if (pos >= source.length || source[pos] != ')') fail(pos, "括号没有闭合")
                pos++
                return expr
            }
            if (c == '\'' || c == '"') {
                return Literal(readStringLiteral(c))
            }
            if (c == '$') {
                return tryParseRef() ?: fail(pos, "$ 后面不是合法的变量名")
            }
            if (c.isDigit()) {
                return Literal(readNumber())
            }
            val word = readIdentifier()
            return when (word) {
                "true" -> Literal(true)
                "false" -> Literal(false)
                "null" -> Literal(null)
                "" -> fail(pos, "无法解析的表达式")
                else -> {
                    // 不带 $ 的标识符按变量引用处理（Velocity 允许 #if($x) 之外写成 #if(x) 的容错）
                    RefExpr(word, emptyList())
                }
            }
        }

        /**
         * 字符串字面量：单引号是纯文本（Velocity 语义），双引号支持 `$` 插值——
         * 模板里写 `"${pkg.entity}.${entity}"` 才符合直觉，否则全靠 `+` 拼会很啰嗦。
         */
        private fun readStringLiteral(quote: Char): Any {
            pos++
            if (quote == '"') {
                val sb = StringBuilder()
                while (pos < source.length) {
                    val c = source[pos]
                    if (c == '\\' && pos + 1 < source.length && source[pos + 1] == '"') {
                        sb.append('"')
                        pos += 2
                        continue
                    }
                    if (c == quote) {
                        pos++
                        return Nested(Template.compile(sb.toString(), "$name:string", names))
                    }
                    sb.append(c)
                    pos++
                }
                fail(pos, "字符串字面量没有闭合")
            }
            val sb = StringBuilder()
            while (pos < source.length) {
                val c = source[pos]
                if (c == '\\' && pos + 1 < source.length) {
                    val next = source[pos + 1]
                    sb.append(
                        when (next) {
                            'n' -> '\n'
                            't' -> '\t'
                            'r' -> '\r'
                            else -> next
                        }
                    )
                    pos += 2
                    continue
                }
                if (c == quote) {
                    pos++
                    return sb.toString()
                }
                sb.append(c)
                pos++
            }
            fail(pos, "字符串字面量没有闭合")
        }

        private fun readNumber(): Any {
            val start = pos
            while (pos < source.length && (source[pos].isDigit() || source[pos] == '.')) pos++
            val text = source.substring(start, pos)
            return if (text.contains('.')) text.toDouble() else text.toLong()
        }

        // -------------------------------------------------------------- 扫描

        private fun readWord(offset: Int): String {
            var index = offset
            while (index < source.length && (source[index].isLetter() || source[index] == '_')) index++
            return source.substring(offset, index)
        }

        private fun readIdentifier(): String {
            val start = pos
            while (pos < source.length && (source[pos].isLetterOrDigit() || source[pos] == '_')) pos++
            return source.substring(start, pos)
        }

        /** 指令是否独占一行（前面只有空白） */
        private fun standalone(start: Int): Boolean {
            var index = start - 1
            while (index >= 0 && (source[index] == ' ' || source[index] == '\t')) index--
            return index < 0 || source[index] == '\n'
        }

        /** 去掉已经攒进正文的行首缩进，让独占一行的指令不留痕 */
        private fun trimLeader(buf: StringBuilder, start: Int) {
            if (standalone(start)) trimLeader(buf)
        }

        private fun trimLeader(buf: StringBuilder) {
            while (buf.isNotEmpty() && (buf.last() == ' ' || buf.last() == '\t')) {
                buf.setLength(buf.length - 1)
            }
        }

        /** 指令后面若只剩空白，把这一行（含换行）整行吃掉 */
        private fun skipBlankTail() {
            var index = pos
            while (index < source.length && (source[index] == ' ' || source[index] == '\t' || source[index] == '\r')) index++
            if (index < source.length && source[index] == '\n') {
                pos = index + 1
            }
        }

        private fun skipSpaces() {
            while (pos < source.length && (source[pos] == ' ' || source[pos] == '\t' || source[pos] == '\n' || source[pos] == '\r')) pos++
        }

        private fun skipSpacesForRef() {
            while (pos < source.length && (source[pos] == ' ' || source[pos] == '\t')) pos++
        }

        private fun fail(offset: Int, reason: String): Nothing {
            val line = lineOf(lineStarts, offset)
            val column = offset - lineStarts[line - 1] + 1
            throw TemplateException(name, line, column, reason)
        }
    }
}

/** 偏移量 → 行号（从 1 开始）：在每行起始偏移上二分。解析报错与渲染告警共用 */
private fun lineOf(lineStarts: IntArray, offset: Int): Int {
    var low = 0
    var high = lineStarts.size - 1
    while (low < high) {
        val mid = (low + high + 1) / 2
        if (lineStarts[mid] <= offset) low = mid else high = mid - 1
    }
    return low + 1
}
