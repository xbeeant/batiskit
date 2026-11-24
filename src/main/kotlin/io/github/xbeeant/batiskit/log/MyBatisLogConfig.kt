package io.github.xbeeant.batiskit.log

/**
 * 日志解析配置的运行期快照。
 *
 * 与 [MyBatisLogSettingsState] 分开，是为了让 [MyBatisLogParser] 保持纯 JVM 对象：
 * 单测可以任意构造配置，不需要启动 IDE 的 Application / 持久化服务。
 */
data class MyBatisLogConfig(
    /** SQL 行的识别前缀，默认 `Preparing:` */
    val preparingPrefix: String = DEFAULT_PREPARING,
    /** 参数行的识别前缀，默认 `Parameters:` */
    val parametersPrefix: String = DEFAULT_PARAMETERS,
    /**
     * 日志框架在 `%m` 与 `%n` 之间额外写入的结束标识。
     * 例如 `logback` 配成 `%m | %n` 时这里填 ` | `，解析前会从每行末尾剥掉它。
     */
    val endMask: String = "",
    /** 单行日志超过这个长度就不解析；0 表示不限制 */
    val maxLineLength: Int = 0,
    /** 是否识别 iBatis 2.x 的 `PreparedStatement:` / `Parameters: [...]` / `Types: [...]` */
    val iBatisEnabled: Boolean = true,
    /** 命中任一关键字的语句不进入列表（按行写，大小写不敏感）；属于视图级过滤，原始条目仍保留 */
    val filteredKeywords: List<String> = emptyList(),
    /** 批量复制时带 `-- #序号` 头 */
    val indexLog: Boolean = true,
    /** 批量复制时带 `-- Total: n` / `-- Updates: n` 尾注 */
    val impactLog: Boolean = true,
) {
    val effectivePreparingPrefix: String get() = preparingPrefix.ifBlank { DEFAULT_PREPARING }
    val effectiveParametersPrefix: String get() = parametersPrefix.ifBlank { DEFAULT_PARAMETERS }

    val effectiveLineLimit: Int get() = if (maxLineLength > 0) maxLineLength else HARD_LINE_LIMIT

    companion object {
        const val DEFAULT_PREPARING = "Preparing:"
        const val DEFAULT_PARAMETERS = "Parameters:"
        const val IBATIS_STATEMENT = "PreparedStatement:"
        const val IBATIS_TYPES = "Types:"

        /** 即使不配置单行上限，也只丢「当前这一行」而不是整块文本 */
        const val HARD_LINE_LIMIT = 1 shl 20
    }
}
