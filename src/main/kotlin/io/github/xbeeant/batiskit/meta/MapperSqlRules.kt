package io.github.xbeeant.batiskit.meta

/**
 * Mapper XML 辅助功能的纯逻辑规则层：零平台依赖，可直接单测。
 *
 * 覆盖四件事：
 *  - 方法名前缀 → CRUD 语句类型（前缀表可在设置页改写）；
 *  - 表名推断 / 手写映射 / 记忆最近使用的表；
 *  - 按表名与列清单预填 CRUD SQL；
 *  - SQL 文本上下文解析（表名 / 已出现列 / 光标前的赋值列），用于 `#{}` 参数补全。
 *
 * 这里不做 SQL 语义解析，只做「足够给出有用候选」的词法级推断。
 */
object MapperSqlRules {

    /** 列描述：字段名给补全用，jdbcType / 注释给提示文案用 */
    data class Column(
        val name: String,
        val jdbcType: String = "",
        val comment: String = "",
        val primaryKey: Boolean = false,
        val source: String = "",
    )

    data class PrefixRule(val prefix: String, val kind: String)

    data class SqlContext(
        val verb: String,
        val tables: List<String>,
        val columns: List<String>,
    )

    val KINDS = listOf("select", "insert", "update", "delete")

    /** 默认前缀表：`前缀1,前缀2=类型`，一行一组；与旧版 statementKind 的分类保持一致并做了扩充 */
    const val DEFAULT_PREFIX_RULES: String =
        "select,find,get,query,list,page,count,load,fetch=select\n" +
            "insert,add,save,create,batch=insert\n" +
            "update,modify,set,enable,disable,edit=update\n" +
            "delete,remove,del,clear=delete"

    const val MAX_RECENT = 8
    const val MAX_SNAPSHOTS = 24
    const val MAX_COLUMNS = 300

    private val DEFAULT_RULES: List<PrefixRule> = parsePrefixRules(DEFAULT_PREFIX_RULES)

    private val TABLE = Regex(
        """\b(?:from|join|into|update)\s+([`"\[]?[A-Za-z_][\w$]*(?:\.[A-Za-z_][\w$]*)*[`"\]]?)""",
        RegexOption.IGNORE_CASE
    )
    private val ASSIGNMENT = Regex(
        """([A-Za-z_][\w$]*)\s*(?:=|<>|!=|>=|<=|>|<|\blike\b|\bin\b)\s*[#$]\{[^}]*$""",
        RegexOption.IGNORE_CASE
    )
    private val COMPARISON = Regex(
        """([A-Za-z_][\w$]*)\s*(?:=|<>|!=|>=|<=|>|<|\blike\b|\bin\b)""",
        RegexOption.IGNORE_CASE
    )
    private val IDENT = Regex("""[A-Za-z_][A-Za-z0-9_$]*""")

    private val SQL_KEYWORDS = setOf(
        "select", "from", "where", "and", "or", "not", "null", "is", "in", "like", "between",
        "insert", "into", "values", "update", "set", "delete", "join", "left", "right", "inner",
        "outer", "on", "as", "order", "group", "by", "having", "limit", "offset", "distinct",
        "count", "sum", "max", "min", "avg", "case", "when", "then", "else", "end", "if",
        "test", "foreach", "collection", "item", "index", "open", "close", "separator", "union",
        "all", "exists", "asc", "desc", "true", "false", "now", "sql", "include", "trim", "where",
        "jdbcType", "javaType", "mode", "value", "parameter", "databaseId"
    )

    // ------------------------------------------------------------------ 前缀规则

    /**
     * 解析前缀配置：支持 `a,b=select` 与 `a,b:select`，忽略空行 / `#` 注释 / 非法类型。
     * 同一前缀重复出现时保留第一次；排序按前缀长度降序，保证 `getById` 不会被 `get` 抢先误判之外
     * 的短前缀覆盖（例如 `batchInsert` 与 `insert`）。
     */
    fun parsePrefixRules(text: String?): List<PrefixRule> {
        if (text.isNullOrBlank()) return emptyList()
        val result = LinkedHashMap<String, String>()
        text.lineSequence().forEach { raw ->
            val line = raw.substringBefore('#').trim()
            if (line.isEmpty()) return@forEach
            val separator = if (line.contains('=')) '=' else ':'
            val left = line.substringBefore(separator)
            val kind = line.substringAfter(separator, "").trim().lowercase()
            if (!line.contains(separator) || kind !in KINDS) return@forEach
            left.split(',', '，', ' ', '\t')
                .map { it.trim().lowercase() }
                .filter { it.isNotEmpty() && it.first().isLetter() && it.all { c -> c.isLetterOrDigit() || c == '_' } }
                .forEach { prefix -> result.putIfAbsent(prefix, kind) }
        }
        return result.entries
            .map { (prefix, kind) -> PrefixRule(prefix, kind) }
            .sortedByDescending { it.prefix.length }
    }

    /** 设置页保存前规范化：丢掉非法行、还原成稳定格式；配置为空时回落到默认值 */
    fun normalizePrefixRules(text: String?): String {
        val rules = parsePrefixRules(text)
        if (rules.isEmpty()) return DEFAULT_PREFIX_RULES
        return KINDS.mapNotNull { kind ->
            val prefixes = rules.filter { it.kind == kind }.joinToString(",") { it.prefix }
            prefixes.takeIf { it.isNotEmpty() }?.let { "$it=$kind" }
        }.joinToString("\n")
    }

    /** 按最长匹配前缀判定语句类型；未命中或规则为空时按默认前缀表兜底（select 保底） */
    fun kindOf(methodName: String, rules: List<PrefixRule> = DEFAULT_RULES): String {
        val name = methodName.lowercase()
        val effective = rules.ifEmpty { DEFAULT_RULES }
        return effective
            .sortedByDescending { it.prefix.length }
            .firstOrNull { name.startsWith(it.prefix) }
            ?.kind ?: "select"
    }

    // ------------------------------------------------------------------ 表名与映射

    /** 只保留能安全写进 XML 文本 / SQL 的标识符字符 */
    fun sanitizeIdentifier(text: String): String =
        text.trim()
            .trim('`', '"', '[', ']', '\'')
            .substringAfterLast('.')
            .filter { it.isLetterOrDigit() || it == '_' || it == '$' }
            .trimStart { it.isDigit() }

    /** `userName` → `user_name`；`HTTPServer` → `http_server` */
    fun toSnakeCase(name: String): String {
        val source = name.trim()
        if (source.isEmpty()) return ""
        val out = StringBuilder()
        source.forEachIndexed { index, ch ->
            when {
                ch == '-' || ch == ' ' || ch == '.' -> {
                    if (out.isNotEmpty() && out.last() != '_') out.append('_')
                }

                ch.isUpperCase() -> {
                    val previous = source.getOrNull(index - 1)
                    val next = source.getOrNull(index + 1)
                    val needSeparator = index > 0 && out.lastOrNull() != '_' &&
                        (previous?.isLowerCase() == true || previous?.isDigit() == true ||
                            (previous?.isUpperCase() == true && next?.isLowerCase() == true))
                    if (needSeparator) out.append('_')
                    out.append(ch.lowercaseChar())
                }

                else -> out.append(ch.lowercaseChar())
            }
        }
        return out.toString().trim('_').replace(Regex("_+"), "_")
    }

    /** `user_name` → `userName`；已经是驼峰时保持首字母小写 */
    fun toCamelCase(name: String): String {
        val parts = name.trim().split(Regex("[^A-Za-z0-9]+")).filter { it.isNotEmpty() }
        if (parts.isEmpty()) return ""
        val head = parts.first().lowercase()
        return head + parts.drop(1).joinToString("") { part ->
            part.lowercase().replaceFirstChar { it.uppercaseChar() }
        }
    }

    /** `UserMapper` / `UserDao` → `user`；优先使用 @TableName 与实体类名 */
    fun inferTableName(
        mapperSimpleName: String?,
        tableNameAnnotation: String? = null,
        entitySimpleName: String? = null,
    ): String? {
        annotated(tableNameAnnotation)?.let { return it }
        val entity = entitySimpleName?.takeIf { it.isNotBlank() }
            ?: mapperSimpleName?.takeIf { it.isNotBlank() }?.let {
                it.removeSuffix("Mapper").removeSuffix("Dao").removeSuffix("Repository")
            }
        val snake = entity?.let(::toSnakeCase)?.takeIf { it.isNotBlank() }
        return snake?.let(::sanitizeIdentifier)?.takeIf { it.isNotEmpty() }
    }

    private fun annotated(raw: String?): String? {
        val value = raw?.trim()?.trim('"', '\'')?.takeIf { it.isNotBlank() } ?: return null
        return sanitizeIdentifier(value).takeIf { it.isNotEmpty() }
    }

    /** 解析 `namespace=table` 多行映射；支持 `#` 注释与 `=` / `:` 分隔 */
    fun parseMappings(text: String?): Map<String, String> {
        if (text.isNullOrBlank()) return emptyMap()
        val result = LinkedHashMap<String, String>()
        text.lineSequence().forEach { raw ->
            val line = raw.substringBefore('#').trim()
            if (line.isEmpty()) return@forEach
            val separator = if (line.contains('=')) '=' else ':'
            if (!line.contains(separator)) return@forEach
            val key = line.substringBefore(separator).trim()
            val table = sanitizeIdentifier(line.substringAfter(separator))
            if (key.isNotEmpty() && table.isNotEmpty()) result.putIfAbsent(key, table)
        }
        return result
    }

    fun formatMappings(mappings: Map<String, String>): String =
        mappings.entries.joinToString("\n") { (key, table) -> "$key=$table" }

    /**
     * 映射查找：先按 namespace 精确命中，再退到「key 是 namespace 的最后一段或文件名」，
     * 方便用户只写 `UserMapper=user` 就覆盖整个文件。
     */
    fun mappingFor(namespace: String, fileName: String, mappings: Map<String, String>): String? {
        if (mappings.isEmpty()) return null
        mappings[namespace]?.let { return it }
        val simple = namespace.substringAfterLast('.')
        mappings[simple]?.let { return it }
        mappings[fileName]?.let { return it }
        mappings[fileName.substringBeforeLast('.')]?.let { return it }
        return mappings.entries.firstOrNull { (key, _) -> namespace.endsWith(key) }?.value
    }

    /** 最近优先、去重、限量；空的 value 不入列 */
    fun remember(existing: List<String>, value: String, max: Int = MAX_RECENT): List<String> {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) return existing.take(max)
        return (listOf(trimmed) + existing.filterNot { it.equals(trimmed, ignoreCase = true) })
            .distinctBy { it.lowercase() }
            .take(max.coerceAtLeast(1))
    }

    private val TABLE_PREFIX = Regex("""^(?:t|tb|tbl)_+""", RegexOption.IGNORE_CASE)

    /**
     * 数据源里的表名与 SQL/映射里写出的表名做匹配打分：
     * 精确 > 忽略大小写 > 去常见表前缀 > 业务前缀后缀（如 `user` ↔ `sys_user`）。
     * 返回 -1 表示不匹配。只认「后缀相同」不做任意子串，避免 `user` 误命中 `user_log`。
     */
    fun tableMatchScore(candidate: String, wanted: String): Int {
        val a = sanitizeIdentifier(candidate)
        val b = sanitizeIdentifier(wanted)
        if (a.isEmpty() || b.isEmpty()) return -1
        if (a == b) return 100
        if (a.equals(b, ignoreCase = true)) return 95
        val plainA = a.replaceFirst(TABLE_PREFIX, "")
        val plainB = b.replaceFirst(TABLE_PREFIX, "")
        if (plainA.equals(plainB, ignoreCase = true) && plainA.isNotEmpty()) return 70
        if (b.length >= 3 && a.length > b.length && a.endsWith(b, ignoreCase = true)) return 40
        if (a.length >= 3 && b.length > a.length && b.endsWith(a, ignoreCase = true)) return 35
        return -1
    }

    // ------------------------------------------------------------------ CRUD 预填 SQL

    /** 生成可直接执行的 SQL 文本（不含 `<` / `&`，可以安全放进 XML 文本节点） */
    fun sqlFor(kind: String, table: String, columns: List<Column>): String {
        val target = sanitizeIdentifier(table)
        if (target.isEmpty()) return ""
        val cols = columns.map { sanitizeIdentifier(it.name) }
            .filter { it.isNotEmpty() }.distinct()
        val primary = columns.firstOrNull { it.primaryKey }?.name
            ?.let(::sanitizeIdentifier)?.takeIf { it.isNotEmpty() }
            ?: cols.firstOrNull { it.equals("id", ignoreCase = true) }
        return when (kind.lowercase()) {
            "insert" -> insertSql(target, cols)
            "update" -> updateSql(target, cols, primary)
            "delete" -> deleteSql(target, primary)
            else -> selectSql(target, cols, primary)
        }
    }

    private fun selectSql(table: String, columns: List<String>, primary: String?): String {
        val projection = if (columns.isEmpty()) "    *" else columns.joinToString(",\n") { "    $it" }
        return buildString {
            append("SELECT\n").append(projection).append("\nFROM ").append(table)
            if (primary != null) {
                append("\nWHERE ").append(primary).append(" = #{").append(toCamelCase(primary)).append('}')
            } else {
                append("\n/* TODO: 补充 WHERE 条件 */")
            }
        }
    }

    private fun insertSql(table: String, columns: List<String>): String {
        if (columns.isEmpty()) {
            return "INSERT INTO $table (/* TODO: 列名 */)\nVALUES (/* TODO: #{属性名} */)"
        }
        return buildString {
            append("INSERT INTO ").append(table).append(" (\n")
            append(columns.joinToString(",\n") { "    $it" })
            append("\n) VALUES (\n")
            append(columns.joinToString(",\n") { "    #{${toCamelCase(it)}}" })
            append("\n)")
        }
    }

    private fun updateSql(table: String, columns: List<String>, primary: String?): String {
        val mutable = columns.filterNot { it.equals(primary, ignoreCase = true) }
        return buildString {
            append("UPDATE ").append(table).append("\nSET\n")
            if (mutable.isEmpty()) {
                append("    /* TODO: 列名 = #{属性名} */")
            } else {
                append(mutable.joinToString(",\n") { "    $it = #{${toCamelCase(it)}}" })
            }
            if (primary != null) {
                append("\nWHERE ").append(primary).append(" = #{").append(toCamelCase(primary)).append('}')
            } else {
                append("\n/* TODO: 补充 WHERE 条件 */")
            }
        }
    }

    private fun deleteSql(table: String, primary: String?): String = buildString {
        append("DELETE FROM ").append(table)
        if (primary != null) {
            append("\nWHERE ").append(primary).append(" = #{").append(toCamelCase(primary)).append('}')
        } else {
            append("\n/* TODO: 补充 WHERE 条件 */")
        }
    }

    /**
     * 组装语句标签文本。SQL 为空时退回旧的 TODO 骨架；`note` 用于 inspection 在缺 resultType 时补提醒。
     * 生成内容不含裸 `<` / `&`，可安全交给临时 XmlFile 解析。
     */
    fun statementXml(
        kind: String,
        id: String,
        resultType: String?,
        sql: String?,
        note: String? = null,
    ): String {
        val tag = kind.lowercase().takeIf { it in KINDS } ?: "select"
        val attrs = buildString {
            append("id=\"").append(escapeAttribute(id)).append('"')
            if (tag == "select" && !resultType.isNullOrBlank()) {
                append(" resultType=\"").append(escapeAttribute(resultType)).append('"')
            }
        }
        val body = sql?.takeIf { it.isNotBlank() }?.lineSequence()?.joinToString("\n") { "    $it" }
            ?: "    <!-- TODO: 补充 SQL -->"
        val reminder = note?.takeIf { it.isNotBlank() }?.let { "\n    <!-- TODO: $it -->" }.orEmpty()
        return "<$tag $attrs>\n$body$reminder\n</$tag>"
    }

    private fun escapeAttribute(value: String): String = value
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&apos;")

    // ------------------------------------------------------------------ SQL 上下文

    /** 清掉 XML 标签、注释、占位符，只留 SQL 骨架 */
    fun stripMarkup(text: String): String = text
        .replace(Regex("""<!--[\s\S]*?-->"""), " ")
        .replace(Regex("""<[^>]*>"""), " ")
        .replace(Regex("""(?:#|\$)\{[^}]*}"""), " ? ")
        .replace(Regex("""--[^\n]*"""), " ")
        .replace(Regex("""/\*[\s\S]*?\*/"""), " ")

    /**
     * 从 SQL 文本里解析动词、表名、已出现的列。
     * 列是「词法候选」而非语法树结果：过滤关键字 / 表名 / 单字符别名 / 数字，够用于提示排序。
     */
    fun parseSql(text: String): SqlContext {
        val clean = stripMarkup(text)
        val verb = Regex("""^\s*([A-Za-z]+)""").find(clean)
            ?.groupValues?.get(1)?.lowercase().orEmpty()
        val tables = TABLE.findAll(clean)
            .map { sanitizeIdentifier(it.groupValues[1]) }
            .filter { it.isNotEmpty() && !SQL_KEYWORDS.contains(it.lowercase()) }
            .distinct().toList()
        val comparisons = COMPARISON.findAll(clean).map { it.groupValues[1] }.toList()
        val words = IDENT.findAll(clean).map { it.value }.toList()
        val tableNames = tables.map { it.lowercase() }.toSet()
        val columns = (comparisons + words)
            .map { it.lowercase() }
            .filter { word ->
                word.length > 1 && word !in SQL_KEYWORDS && word !in tableNames &&
                    !word.all { c -> c.isDigit() } && !word.startsWith("jdbc") &&
                    !word.startsWith("param") && !word.startsWith("arg")
            }
            .distinct()
            .toList()
        return SqlContext(verb, tables, columns)
    }

    /**
     * `#{}` 里的上下文参数候选：
     *  - 若光标前是 `col = #{`，把该列对应的 Java 属性名排第一；
     *  - 其余按「SQL 里还没出现过的列」优先，给 camelCase 属性名与原始列名两种写法。
     */
    fun paramHints(sqlBeforeCaret: String, columns: List<Column>): List<String> {
        val context = parseSql(sqlBeforeCaret)
        val used = context.columns.toSet()
        val hints = LinkedHashSet<String>()
        ASSIGNMENT.find(sqlBeforeCaret)?.groupValues?.get(1)?.let { column ->
            val camel = toCamelCase(column)
            if (camel.isNotEmpty()) hints += camel
            hints += column
        }
        columns.sortedBy { it.name.lowercase() in used }.forEach { column ->
            val name = sanitizeIdentifier(column.name)
            if (name.isEmpty()) return@forEach
            val camel = toCamelCase(name)
            if (camel.isNotEmpty()) hints += camel
            hints += name
        }
        return hints.toList()
    }

    /** 表字段补全排序：SQL 里没出现过的列排前面，主键其次 */
    fun columnOrder(columns: List<Column>, sqlContext: SqlContext?): List<Column> {
        val used = sqlContext?.columns?.toSet().orEmpty()
        return columns.sortedWith(
            compareBy<Column> { it.name.lowercase() in used }
                .thenByDescending { it.primaryKey }
                .thenBy { it.name.lowercase() }
        )
    }
}
