package io.github.xbeeant.batiskit.meta

/**
 * 轻量 SQL 语义层：纯 Kotlin、零 IntelliJ 平台依赖，可直接 JUnit 单测。
 *
 * 只做词法级解析（正则 + 有限状态），**不做完整 SQL parser**：
 *  1. 先剥掉 MyBatis 动态标签、XML 注释、`#{}` / `${}` 占位符与 SQL 注释，只留 SQL 骨架；
 *  2. 用正则找 `FROM` / `JOIN` / `UPDATE` / `INTO` 后面的表名与别名（含子查询里的表）；
 *  3. 用正则找 `别名.列` / `表名.列` 以及 `INSERT INTO t (列…)`，按 owner 归并列名。
 *
 * 目标是给「`别名.` 之后的列补全」提供够用、且在任何输入下都不抛异常的上下文，
 * 不追求 100% 语义精确。列名与表名都是清洗过的标识符，不含 schema / 引号 / 占位符。
 */
object SqlSemantics {

    /** 超过这个长度直接返回空上下文：避免编辑器里粘贴超大文件时拖慢补全 */
    const val MAX_SQL_LENGTH = 200 * 1024

    /** 最多识别的表数量，防止病态输入（正常 SQL 两张表足够） */
    private const val MAX_TABLES = 8

    /** 解析出的表引用。[name] 是清洗后的表名（保留原文大小写），[alias] 为 null 表示没写别名 */
    data class TableRef(
        val name: String,
        val alias: String? = null,
        val offset: Int = 0,
    )

    /**
     * @param tables         按出现顺序的表引用（含子查询里的表）
     * @param columnsByOwner  key 是别名 / 表名的小写形式，value 是该 owner 下出现过的列名（小写）
     * @param mainTable      主表名：第一个 `FROM` / `UPDATE` / `INTO` 命中的表
     * @param aliases        别名（小写）→ 表名
     */
    data class SqlContext(
        val tables: List<TableRef> = emptyList(),
        val columnsByOwner: Map<String, Set<String>> = emptyMap(),
        val mainTable: String? = null,
        val aliases: Map<String, String> = emptyMap(),
    )

    private val EMPTY = SqlContext()

    // ------------------------------------------------------------------ 清洗（有限状态都用正则表达）

    private val XML_COMMENT = Regex("""<!--[\s\S]*?-->""")
    private val PLACEHOLDER = Regex("""(?:#|\$)\{[^}]*\}""")
    private val XML_TAG = Regex("""</?[A-Za-z_][^>]*>""")
    private val LINE_COMMENT = Regex("""--[^\n\r]*""")
    private val BLOCK_COMMENT = Regex("""/\*[\s\S]*?\*/""")

    /**
     * 标签 / 注释换成空格，占位符换成 ` ? `（保留词边界，避免把 `u.#{x}` 粘成 `u.?`）。
     * 顺序刻意先处理占位符：`<if test="a > #{b}">` 里的 `>` 不会提前截断标签。
     */
    private fun strip(raw: String): String = raw
        .replace(XML_COMMENT, " ")
        .replace(PLACEHOLDER, " ? ")
        .replace(XML_TAG, " ")
        .replace(LINE_COMMENT, " ")
        .replace(BLOCK_COMMENT, " ")

    // ------------------------------------------------------------------ 表 / 别名

    /**
     * 表名片段：支持 `sys_user`、`public.sys_user`、`` `public`.`sys_user` ``、`[dbo].[t_user]`。
     * 每一段都允许单独加引号；清洗时统一取最后一段（真正的表名）。
     */
    private val TABLE_NAME_PATTERN =
        """[`"\[]?[A-Za-z_][A-Za-z0-9_$]*[`"\]]?(?:\s*\.\s*[`"\[]?[A-Za-z_][A-Za-z0-9_$]*[`"\]]?)*"""

    /** `FROM sys_user u` / `JOIN t_order o` / `UPDATE t` / `INSERT INTO t` / `DELETE FROM t` */
    private val TABLE_CLAUSE = Regex(
        """\b(from|join|update|into)\s+(${TABLE_NAME_PATTERN})""",
        RegexOption.IGNORE_CASE
    )

    /** 表名后面紧跟的 token：`AS u` 或直接 `u`；`(a,b)` / `,` / `;` 等都不算别名 */
    private val ALIAS_AFTER = Regex("""^\s+(?:as\s+)?([A-Za-z_][A-Za-z0-9_$]*)""", RegexOption.IGNORE_CASE)

    /** 这些词出现在表名后面时是 SQL 结构，不是别名 */
    private val NON_ALIAS = setOf(
        "where", "on", "set", "values", "value", "inner", "left", "right", "full", "outer",
        "cross", "join", "group", "order", "having", "limit", "offset", "union", "all",
        "and", "or", "not", "as", "select", "from", "insert", "into", "update", "delete",
        "using", "returning", "when", "then", "else", "end", "case", "if", "foreach",
        "include", "trim", "sql", "by", "asc", "desc", "null", "is", "in", "like",
        "between", "exists", "distinct", "table", "index", "force", "use", "partition",
        "conflict", "do", "nothing"
    )

    /** `INSERT INTO t (a, b)` 的列清单；占位符已清洗成 `?`，不会混进来 */
    private val INSERT_COLUMNS = Regex(
        """\binto\s+(${TABLE_NAME_PATTERN})\s*\(([^)]*)\)""",
        RegexOption.IGNORE_CASE
    )

    /** `u.name` / `sys_user.id`：group1 是 owner，group2 是列名 */
    private val QUALIFIED_COLUMN = Regex("""([A-Za-z_][A-Za-z0-9_$]*)\.([A-Za-z_][A-Za-z0-9_$]*)""")

    /** 光标前是 `别名.` 或 `别名.部分列名` */
    private val PREFIX_DOT = Regex("""([A-Za-z_][A-Za-z0-9_$]*)\.$""")
    private val PREFIX_PARTIAL = Regex("""([A-Za-z_][A-Za-z0-9_$]*)\.([A-Za-z_][A-Za-z0-9_$]*)$""")

    // ------------------------------------------------------------------ 对外 API

    /**
     * 解析 SQL 上下文。
     *
     * @param caretOffset 只解析 `[0, caretOffset)` 这一段（补全时用户还在输入，光标后的文本可能不完整）；
     *                    默认传 [String.length] 即解析全文。越界值会被夹到合法范围。
     */
    fun parse(sql: String, caretOffset: Int = sql.length): SqlContext {
        if (sql.isEmpty() || sql.length > MAX_SQL_LENGTH) return EMPTY
        val end = caretOffset.coerceIn(0, sql.length)
        val source = sql.substring(0, end)
        if (source.isBlank()) return EMPTY
        // 词法级解析不该因为任何畸形输入抛异常；兜住后安静返回空上下文
        return runCatching { doParse(strip(source)) }.getOrElse { EMPTY }
    }

    /**
     * 判断 `u.name` 里的 `u` 属于哪张表。
     * 先查别名，再查表名（支持直接写 `sys_user.id`）；都不是已知 owner 时返回 null。
     */
    fun ownerOf(qualified: String, ctx: SqlContext): String? {
        val owner = qualified.trim().substringBefore('.').trim().lowercase()
        if (owner.isEmpty()) return null
        ctx.aliases[owner]?.let { return it }
        return ctx.tables.firstOrNull { it.name.equals(owner, ignoreCase = true) }?.name
    }

    /**
     * 光标是否正在输入 `别名.` 前缀：返回点号前的标识符（别名或表名），否则 null。
     * `u.` 与 `u.na` 都算；`u.name = 1` 这类已经写完的完整表达式不算。
     * 未闭合的 `#{` / `${` 内部不算，避免占位符里的 `item.` 误触发补全。
     */
    fun aliasPrefixAt(sql: String, caretOffset: Int): String? {
        if (sql.isEmpty() || sql.length > MAX_SQL_LENGTH) return null
        val before = sql.substring(0, caretOffset.coerceIn(0, sql.length))
        if (inOpenPlaceholder(before)) return null
        return PREFIX_DOT.find(before)?.groupValues?.get(1)
            ?: PREFIX_PARTIAL.find(before)?.groupValues?.get(1)
    }

    // ------------------------------------------------------------------ 内部实现

    private fun doParse(clean: String): SqlContext {
        val tables = ArrayList<TableRef>()
        val aliases = LinkedHashMap<String, String>()
        for (m in TABLE_CLAUSE.findAll(clean)) {
            val name = cleanTable(m.groupValues[2])
            if (name.isEmpty()) continue
            val tail = clean.substring(m.range.last + 1)
            val aliasWord = ALIAS_AFTER.find(tail)?.groupValues?.get(1)
            val alias = aliasWord?.takeIf { it.lowercase() !in NON_ALIAS }
            tables += TableRef(name = name, alias = alias, offset = m.range.first)
            if (alias != null) aliases.putIfAbsent(alias.lowercase(), name)
            if (tables.size >= MAX_TABLES) break
        }

        // 每个已知 owner 都先占位，保证「每个别名 / 表名下出现过的列名」有清晰的 key
        val owners = LinkedHashMap<String, MutableSet<String>>()
        tables.forEach { table ->
            owners.putIfAbsent(table.name.lowercase(), LinkedHashSet())
            table.alias?.let { owners.putIfAbsent(it.lowercase(), LinkedHashSet()) }
        }

        // 限定列：只认 owner 是已知别名 / 表名的，schema.table 之类的点号不会混进列名
        for (m in QUALIFIED_COLUMN.findAll(clean)) {
            val owner = m.groupValues[1].lowercase()
            val column = m.groupValues[2].lowercase()
            owners[owner]?.add(column)
        }

        // INSERT INTO t (a, b)：括号里的列明确属于 t
        for (m in INSERT_COLUMNS.findAll(clean)) {
            val owner = cleanTable(m.groupValues[1]).lowercase()
            val bucket = owners[owner] ?: continue
            m.groupValues[2].split(',').forEach { raw ->
                val column = cleanIdent(raw).lowercase()
                if (column.isNotEmpty()) bucket.add(column)
            }
        }

        return SqlContext(
            tables = tables,
            columnsByOwner = owners.mapValues { (_, columns) -> columns.toSet() },
            mainTable = tables.firstOrNull()?.name,
            aliases = aliases,
        )
    }

    /** 去掉 schema 前缀、引号与非法字符；`public`.`sys_user` → `sys_user` */
    private fun cleanTable(raw: String): String = raw
        .substringAfterLast('.')
        .trim()
        .trim('`', '"', '[', ']')
        .filter { it.isLetterOrDigit() || it == '_' || it == '$' }
        .trimStart { it.isDigit() }

    /** 单个列名清洗：INSERT 列清单里可能带反引号、括号残留或 schema 前缀 */
    private fun cleanIdent(raw: String): String = raw
        .trim()
        .trim('`', '"', '[', ']', '(', ')', '?')
        .substringAfterLast('.')
        .filter { it.isLetterOrDigit() || it == '_' || it == '$' }
        .trimStart { it.isDigit() }

    private fun inOpenPlaceholder(before: String): Boolean {
        val hash = before.lastIndexOf("#{")
        val dollar = before.lastIndexOf("\${")
        val last = maxOf(hash, dollar)
        return last >= 0 && before.indexOf('}', last) < 0
    }
}
