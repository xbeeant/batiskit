package io.github.xbeeant.batiskit.log

/**
 * MyBatis / iBatis 控制台日志的增量解析器。
 *
 * 标准 MyBatis（前缀可在设置里改）：
 * ```
 * ==>  Preparing: select id, name from t_user where id = ?
 * ==> Parameters: 1(Integer)
 * <==      Total: 1
 * ```
 * iBatis 2.x（三行一组，类型单独一行）：
 * ```
 * {pstm-1} PreparedStatement:    select * from t_user where name = ? and id = ?
 * {pstm-1} Parameters: [hello, 1]
 * {pstm-1} Types: [java.lang.String, java.lang.Integer]
 * ```
 *
 * 控制台送进来的是文本块，可能把一行截断，所以先缓冲到 `\n` 再逐行处理。
 * 遇到超长半行时只跳过「当前这一行」，不再把整块缓冲清空 —— 后面的日志不能被吞掉。
 *
 * 线程安全：控制台回调来自各个进程的读线程，`feed` 内部加锁。
 */
class MyBatisLogParser(private var config: MyBatisLogConfig = MyBatisLogConfig()) {

    private val lock = Any()

    /** 未消费的半行 */
    private val buffer = StringBuilder()

    /** 已经读到 Preparing、还在等 Parameters 的语句（多线程日志会交错，用队列按序配对） */
    private val pending = ArrayDeque<MyBatisLogEntry>()

    /** 最近一条已产出的语句，用来承接紧跟着的 Total / Updates */
    private var last: MyBatisLogEntry? = null

    /** iBatis 的 `Parameters: [...]` 值，等 `Types: [...]` 到达后一起配对 */
    private var iBatisValues: List<String>? = null

    /** 正在丢弃一行超长日志的残余部分；遇到下一个换行就恢复解析 */
    private var skipping = false

    /** 设置页 Apply 后热更新解析配置，已缓冲的半行与待配对语句保持不动 */
    fun updateConfig(next: MyBatisLogConfig) {
        synchronized(lock) { config = next }
    }

    /** 解析 [text]，返回本次新增的条目（以及是否回填了 Total / Updates） */
    fun feed(text: CharSequence, source: String = ""): MyBatisLogParseResult {
        if (text.isEmpty()) {
            return MyBatisLogParseResult()
        }
        synchronized(lock) {
            buffer.append(stripAnsi(text))

            // 上一次发现超长半行：丢掉这一行剩下的部分，直到换行符出现
            if (skipping) {
                val newline = buffer.indexOf("\n")
                if (newline < 0) {
                    buffer.setLength(0)
                    return MyBatisLogParseResult()
                }
                buffer.delete(0, newline + 1)
                skipping = false
            }

            val added = mutableListOf<MyBatisLogEntry>()
            var updated = false
            val limit = config.effectiveLineLimit
            while (true) {
                val newline = buffer.indexOf("\n")
                if (newline < 0) {
                    break
                }
                val raw = buffer.substring(0, newline)
                buffer.delete(0, newline + 1)
                // 单行过长：跳过这一行即可，绝不影响后续行
                if (raw.length > limit) {
                    continue
                }
                updated = handleLine(normalizeLine(raw), source, added) || updated
            }

            // 没有换行符的超长文本（比如巨大参数）不占着缓冲不放：
            // 清掉缓冲并标记「跳过当前行」，下一块文本里的正常日志仍能解析。
            if (buffer.indexOf("\n") < 0 && buffer.length > limit) {
                buffer.setLength(0)
                skipping = true
            }
            return MyBatisLogParseResult(added, updated)
        }
    }

    /** 丢弃缓冲与状态（清空列表时调用） */
    fun reset() {
        synchronized(lock) {
            buffer.setLength(0)
            pending.clear()
            last = null
            iBatisValues = null
            skipping = false
        }
    }

    // ------------------------------------------------------------------ 行处理

    /** 去掉行尾 CR、日志框架在 `%m` 与 `%n` 之间加的结束标识 */
    private fun normalizeLine(raw: String): String {
        var line = raw.trimEnd('\r')
        val mask = config.endMask
        if (mask.isNotEmpty() && line.endsWith(mask)) {
            line = line.removeSuffix(mask)
        }
        return line.trimEnd()
    }

    private fun handleLine(line: String, source: String, added: MutableList<MyBatisLogEntry>): Boolean {
        if (config.iBatisEnabled) {
            val statement = line.indexOf(MyBatisLogConfig.IBATIS_STATEMENT)
            if (statement >= 0) {
                flushIBatis(added)
                onPreparing(line.substring(statement + MyBatisLogConfig.IBATIS_STATEMENT.length), source, added)
                return false
            }
            val types = line.indexOf(MyBatisLogConfig.IBATIS_TYPES)
            if (types >= 0 && iBatisValues != null) {
                applyIBatisTypes(line.substring(types + MyBatisLogConfig.IBATIS_TYPES.length), added)
                return false
            }
        }

        val parametersPrefix = config.effectiveParametersPrefix
        val parameters = line.indexOf(parametersPrefix)
        if (parameters >= 0) {
            val rest = line.substring(parameters + parametersPrefix.length)
            val trimmed = rest.trim()
            if (config.iBatisEnabled && trimmed.startsWith("[") && trimmed.endsWith("]")) {
                flushIBatis(added)
                iBatisValues = parseBracketList(trimmed)
            } else {
                onParameters(rest, added)
            }
            return false
        }

        val preparingPrefix = config.effectivePreparingPrefix
        val preparing = line.indexOf(preparingPrefix)
        if (preparing >= 0) {
            flushIBatis(added)
            onPreparing(line.substring(preparing + preparingPrefix.length), source, added)
            return false
        }

        TOTAL.find(line)?.let { match ->
            flushIBatis(added)
            return onResult(match.groupValues[1].toIntOrNull(), null, added)
        }
        UPDATES.find(line)?.let { match ->
            flushIBatis(added)
            return onResult(null, match.groupValues[1].toIntOrNull(), added)
        }
        return false
    }

    private fun onPreparing(sqlText: String, source: String, added: MutableList<MyBatisLogEntry>) {
        val sql = sqlText.trim()
        if (sql.isEmpty()) {
            return
        }
        // 已经没有占位符的语句（DDL、无参查询）不必等 Parameters 行
        val entry = MyBatisLogEntry(sql, emptyList(), System.currentTimeMillis(), source)
        if (SqlRestorer.countPlaceholders(sql) == 0) {
            emit(entry, added)
        } else {
            if (pending.size >= MAX_PENDING) {
                emit(pending.removeFirst(), added)
            }
            pending.addLast(entry)
        }
    }

    private fun onParameters(text: String, added: MutableList<MyBatisLogEntry>) {
        val parameters = SqlRestorer.parseParameters(text)
        if (parameters.isEmpty()) {
            return
        }
        emitWithParameters(parameters, added)
    }

    private fun emitWithParameters(parameters: List<MyBatisParameter>, added: MutableList<MyBatisLogEntry>) {
        // 参数必定属于最近一条还在等它的 Preparing；多线程交错时按先进先出配对
        val entry = pending.removeFirstOrNull() ?: return
        entry.parameters = parameters
        emit(entry, added)
    }

    /** iBatis 的 Types 行没等到（或日志被截断）时，按值推断类型把语句补出来 */
    private fun flushIBatis(added: MutableList<MyBatisLogEntry>) {
        val values = iBatisValues ?: return
        iBatisValues = null
        val parameters = values.map { MyBatisParameter(it, inferType(it)) }
        emitWithParameters(parameters, added)
    }

    private fun applyIBatisTypes(text: String, added: MutableList<MyBatisLogEntry>) {
        val values = iBatisValues ?: return
        iBatisValues = null
        val types = parseBracketList(text.trim())
        val parameters = values.mapIndexed { index, value ->
            val declared = types.getOrNull(index)
                ?.substringAfterLast('.')
                ?.takeIf { it.isNotBlank() }
                ?: inferType(value).orEmpty()
            // 类型写 null 时退回「无类型 null」的语义
            MyBatisParameter(value, declared.takeUnless { it.equals("null", ignoreCase = true) })
        }
        emitWithParameters(parameters, added)
    }

    private fun onResult(total: Int?, updates: Int?, added: MutableList<MyBatisLogEntry>): Boolean {
        // 有 SQL 但压根没有 Parameters 行：Total 到达时把它补出来，避免条目丢失
        if (pending.isNotEmpty()) {
            emit(pending.removeFirst(), added)
        }
        val entry = last ?: return false
        if (total != null) {
            if (entry.total == total) {
                return false
            }
            entry.total = total
        }
        if (updates != null) {
            if (entry.updates == updates) {
                return false
            }
            entry.updates = updates
        }
        return true
    }

    private fun emit(entry: MyBatisLogEntry, added: MutableList<MyBatisLogEntry>) {
        last = entry
        added += entry
    }

    companion object {
        private val TOTAL = Regex("""Total:\s*(\d+)""")
        private val UPDATES = Regex("""Updates:\s*(\d+)""")

        /** ANSI 颜色控制符（IDEA 控制台会带） */
        private val ANSI = Regex("""\u001B\[[0-9;?]*[ -/]*[@-~]""")

        private val NUMBER = Regex("""-?\d+(?:\.\d+)?""")

        private const val MAX_PENDING = 64

        /** `[a, b, null]` → 值列表；括号里的字符串不做复杂转义，够覆盖 iBatis 的日志形态 */
        fun parseBracketList(text: String): List<String> {
            val trimmed = text.trim()
            if (!trimmed.startsWith("[") || !trimmed.endsWith("]")) {
                return emptyList()
            }
            val body = trimmed.substring(1, trimmed.length - 1)
            if (body.isBlank()) {
                return emptyList()
            }
            return body.split(Regex(""",\s*""")).map { it.trim() }.filter { it.isNotEmpty() }
        }

        /** iBatis 没给 Types 行时按字面量猜一个类型，保证字符串仍然会被加引号 */
        fun inferType(value: String): String? = when {
            value.equals("null", ignoreCase = true) -> null
            NUMBER.matches(value) -> "Number"
            value.equals("true", ignoreCase = true) || value.equals("false", ignoreCase = true) -> "Boolean"
            else -> "String"
        }

        fun stripAnsi(text: CharSequence): String =
            if (text.indexOf('\u001B') < 0) text.toString() else ANSI.replace(text, "")
    }
}
