package io.github.xbeeant.batiskit.log

/**
 * MyBatis 控制台日志里的一个参数，例如 `1(Integer)` / `abc(String)` / `null`。
 *
 * MyBatis 打印的格式是 `值(类型全名或简名)`；只有 null 参数不带类型，直接打印 `null`。
 */
data class MyBatisParameter(val value: String, val type: String? = null) {

    /** 无类型的 `null`：还原成 SQL 的 NULL */
    val isNull: Boolean get() = type == null && value.trim().equals("null", ignoreCase = true)

    /** 日志里的原始写法，用于展示 */
    val display: String get() = if (type == null) value else "$value($type)"
}

/**
 * 一条从日志里还原出来的语句。
 *
 * [parameters] / [total] / [updates] 是可变的：`==> Parameters:` 一到就先建条目（列表里立刻可见），
 * 紧跟着的 `<==      Total:` / `<==    Updates:` 再回填计数。
 */
class MyBatisLogEntry(
    /** 原始 SQL（带 `?` 占位符） */
    val sql: String,
    var parameters: List<MyBatisParameter> = emptyList(),
    val timestamp: Long = System.currentTimeMillis(),
    /** 来源标识：控制台名 / 手动导入 */
    val source: String = ""
) {
    /** 展示用的序号，由 [MyBatisLogService] 统一分配 */
    var seq: Int = 0

    /** `<==      Total: 3`：结果行数 */
    @Volatile
    var total: Int? = null

    /** `<==    Updates: 1`：影响行数 */
    @Volatile
    var updates: Int? = null

    val parameterCount: Int get() = parameters.size

    /** 占位符个数（跳过字符串与注释里的 `?`） */
    val placeholderCount: Int by lazy { SqlRestorer.countPlaceholders(sql) }

    /** 参数不够替换时的缺口，> 0 说明这条 SQL 还原不完整 */
    val missingCount: Int get() = (placeholderCount - parameterCount).coerceAtLeast(0)

    val isComplete: Boolean get() = missingCount == 0

    /** 列表行首展示用：压掉换行与多余空白，避免超长日志撑坏列表布局 */
    val headline: String
        get() = sql.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() }
            ?: ""

    /** 结果信息：`Total 3` / `Updates 1` / 空 */
    val resultLabel: String
        get() = when {
            total != null -> "结果 $total 行"
            updates != null -> "影响 $updates 行"
            else -> ""
        }
}

/** 一次 [MyBatisLogParser.feed] 的产出：新增的条目 + 是否回填了已有条目（Total/Updates） */
class MyBatisLogParseResult(
    val added: List<MyBatisLogEntry> = emptyList(),
    val updated: Boolean = false
) {
    val isEmpty: Boolean get() = added.isEmpty() && !updated
}
