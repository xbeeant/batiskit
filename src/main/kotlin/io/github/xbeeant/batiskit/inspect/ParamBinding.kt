package io.github.xbeeant.batiskit.inspect

/**
 * Mapper 方法参数（只保留与 XML 绑定相关的信息）。
 *
 * MyBatis 的绑定规则：参数写了 `@Param("x")` 时 XML 只能用 `x`；没写时用参数名（需要编译保留参数名）
 * 或 `param1..paramN` / `arg0..argN-1`。
 */
data class MapperParam(val name: String, val paramAnnotation: String? = null) {

    val hasParamAnnotation: Boolean get() = !paramAnnotation.isNullOrBlank()

    /** XML 里能用的名字（未注解时用参数名） */
    val xmlName: String? get() = if (hasParamAnnotation) paramAnnotation else name.ifBlank { null }
}

/**
 * Mapper XML 里 `#{}` / `${}` 与接口方法参数的绑定校验（纯逻辑，不依赖 IDE）。
 *
 * 刻意保守——宁可漏报也不能误报，这是编辑器检查能不能长期开着的关键：
 *  - 方法只有一个参数且没写 `@Param` 时**不校验**：单个 POJO 参数的 `#{name}` 是取属性，
 *    单个简单类型参数的 `#{anything}` 也合法，静态都判断不了；
 *  - 方法有 2 个以上参数、或单参数写了 `@Param`、或没有参数时才校验；
 *  - `param1..N` / `arg0..N-1`、`_parameter` / `_databaseId`、`<foreach item|index>`、`<bind name>` 都算合法。
 */
object ParamBinding {

    /** MyBatis 内置变量 */
    val BUILTIN_NAMES = setOf("_parameter", "_databaseId")

    /** 相似度阈值：编辑距离超过这个值就不给「改成 xxx」的建议，避免瞎猜 */
    private const val MAX_DISTANCE = 2

    private val PLACEHOLDER = Regex("""[#$]\{([^}]*)}""")

    /**
     * 一个占位符表达式。
     *
     * [start]/[end] 是包含 `#{…}` 的整段范围，[rootStart]/[rootEnd] 只包含根名字（`user.id` 取 `user`）。
     */
    data class Expression(
        val text: String,
        val start: Int,
        val end: Int,
        val root: String,
        val rootStart: Int,
        val rootEnd: Int,
        val placeholder: Boolean
    )

    /** 从一段 XML 文本里取出全部占位符（跳过空表达式与动态表达式） */
    fun expressions(source: String): List<Expression> {
        if (source.indexOf('#') < 0 && source.indexOf('$') < 0) {
            return emptyList()
        }
        return PLACEHOLDER.findAll(source).mapNotNull { match ->
            val body = match.groupValues[1]
            // `#{id,jdbcType=BIGINT}`：逗号后面是附加属性，不属于参数路径
            val path = body.substringBefore(',').trim()
            if (path.isEmpty() || path.contains('$') || path.contains('#')) {
                return@mapNotNull null
            }
            val rootLength = path.indexOfFirst { it == '.' || it == '[' }.let { if (it < 0) path.length else it }
            val root = path.substring(0, rootLength)
            if (root.isEmpty()) {
                return@mapNotNull null
            }
            // `#{ id }` 前有空格：按 path 在 body 里的位置定位，保证高亮范围准确
            val rootStart = match.range.first + 2 + body.indexOf(path)
            Expression(
                text = match.value,
                start = match.range.first,
                end = match.range.last + 1,
                root = root,
                rootStart = rootStart,
                rootEnd = rootStart + rootLength,
                placeholder = source[match.range.first] == '#'
            )
        }.toList()
    }

    /** 语句上下文：方法参数 + 语句内声明的临时变量（foreach / bind） */
    class Context(params: List<MapperParam>, eachVars: Set<String> = emptySet()) {

        val params: List<MapperParam> = params

        val eachVars: Set<String> = eachVars

        /** 是否具备静态校验的条件（见类注释里的保守策略） */
        val checkable: Boolean =
            params.isEmpty() || params.size >= 2 || params.first().hasParamAnnotation

        /** 合法名字全集 */
        val validNames: Set<String> = buildSet {
            addAll(BUILTIN_NAMES)
            addAll(eachVars)
            params.forEachIndexed { index, param ->
                add("param${index + 1}")
                add("arg$index")
                param.xmlName?.let { add(it) }
            }
        }

        /** 用于提示 / 建议的名字（按参数顺序，不含 paramN / argN / 内置变量） */
        val suggestionNames: List<String> = params.mapNotNull { it.xmlName }.filter { it.isNotBlank() }

        /** 没有 @Param 的参数下标——只有这些参数能补注解 */
        val unannotatedIndices: List<Int> =
            params.indices.filterNot { params[it].hasParamAnnotation }

        /** 提示里展示的名字（先参数名，再 paramN / argN，便于用户照抄） */
        val displayNames: List<String> = buildList {
            addAll(suggestionNames)
            params.indices.forEach { index ->
                add("param${index + 1}")
                add("arg$index")
            }
        }
    }

    data class Problem(
        val message: String,
        /** 建议改成的名字（编辑距离最近的唯一候选），没有则为 null */
        val renameTo: String? = null,
        /** 建议补 @Param 的参数下标，没有则为 null */
        val addParamIndex: Int? = null,
        /** 建议补的 @Param 值，即 XML 里写的名字 */
        val addParamValue: String? = null
    )

    fun context(params: List<MapperParam>, eachVars: Set<String> = emptySet()): Context =
        Context(params, eachVars)

    /** 校验一个表达式，合法返回 null */
    fun validate(expression: Expression, context: Context): Problem? {
        if (!context.checkable) {
            return null
        }
        val root = expression.root
        if (root in context.validNames) {
            return null
        }
        // 纯数字（#{0}）按位置绑定，不判断
        if (root.all { it.isDigit() }) {
            return null
        }
        return Problem(
            message = message(root, context),
            renameTo = suggestRename(root, context),
            addParamIndex = suggestAddParam(root, context),
            addParamValue = root
        )
    }

    fun message(root: String, context: Context): String {
        val names = context.displayNames.take(8)
        return if (names.isEmpty()) {
            "参数 \"$root\" 绑定不上：该 Mapper 方法没有参数"
        } else {
            "参数 \"$root\" 绑定不上，可用：${names.joinToString("、")}"
        }
    }

    /**
     * 「修正表达式」建议：忽略大小写与下划线后相等，或编辑距离很近的唯一候选。
     * 只在参数名里挑，不会建议改成 `param1`——那不解决可读性问题。
     */
    fun suggestRename(root: String, context: Context): String? {
        val candidates = context.suggestionNames
        if (candidates.isEmpty()) {
            return null
        }
        val normalized = normalize(root)
        val exact = candidates.filter { normalize(it) == normalized }
        if (exact.size == 1) {
            return exact[0]
        }
        val scored = candidates.map { it to distance(normalized, normalize(it)) }
        val best = scored.minByOrNull { it.second } ?: return null
        if (best.second == 0 || best.second > MAX_DISTANCE) {
            return null
        }
        // 多个候选一样近就不猜
        return if (scored.count { it.second == best.second } == 1) best.first else null
    }

    /**
     * 「补 @Param」建议：给哪个参数加 `@Param("XML 里的名字")`。
     * 只有一个未注解参数时直接选它，否则取名字最相近且唯一的那个。
     */
    fun suggestAddParam(root: String, context: Context): Int? {
        val candidates = context.unannotatedIndices
        if (candidates.isEmpty()) {
            return null
        }
        if (candidates.size == 1) {
            return candidates.first()
        }
        val normalized = normalize(root)
        val scored = candidates.map { it to distance(normalized, normalize(context.params[it].name)) }
        val best = scored.minByOrNull { it.second } ?: return null
        if (best.second > MAX_DISTANCE) {
            return null
        }
        return if (scored.count { it.second == best.second } == 1) best.first else null
    }

    // ------------------------------------------------------------------ 内部

    private fun normalize(name: String): String =
        name.lowercase().filterNot { it == '_' || it == '-' }

    /** 编辑距离（只用于短名字，O(n·m) 足够） */
    private fun distance(a: String, b: String): Int {
        if (a == b) {
            return 0
        }
        if (a.isEmpty()) {
            return b.length
        }
        if (b.isEmpty()) {
            return a.length
        }
        var previous = IntArray(b.length + 1) { it }
        var current = IntArray(b.length + 1)
        for (i in 1..a.length) {
            current[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = minOf(current[j - 1] + 1, previous[j] + 1, previous[j - 1] + cost)
            }
            val swap = previous
            previous = current
            current = swap
        }
        return previous[b.length]
    }
}
