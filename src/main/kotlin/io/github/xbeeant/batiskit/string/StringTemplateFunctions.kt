package io.github.xbeeant.batiskit.string

import io.github.xbeeant.batiskit.template.TemplateFunction

/**
 * 模板里的 `$str.*`：与编辑器右键、工具窗口共用同一份 [StringOps] 操作目录，
 * 模板里不用再手写「驼峰 / 下划线 / URL 编码」这类转换逻辑。
 *
 * 用法：`$str.camel("user_name")`、`$str.snake($table.name)`、
 * `$str.urlEncode($table.comment)`、`$str.join($util.columnNames($tableColumns), " | ")`。
 * 第二个可选参数只影响「拼接 / 拆分」类操作，就是分隔符（支持 `\n` `\t` 写法）。
 */
fun stringTemplateFunctions(): Map<String, Any?> = StringOps.ALL.associate { op ->
    op.id to TemplateFunction { args ->
        val value = asText(args.getOrNull(0))
        val sep = args.getOrNull(1)?.let { asText(it) }?.takeIf { it.isNotEmpty() }
        val options = if (sep == null) {
            StringOptions()
        } else {
            StringOptions(separator = sep, splitSeparator = sep)
        }
        StringOps.apply(op, value, options)
    }
}

/** 列清单之类的 List 按行摊平，正好能直接喂给排序 / 去重 / 拼接类函数 */
private fun asText(value: Any?): String = when (value) {
    null -> ""
    is String -> value
    is List<*> -> value.joinToString("\n") { asText(it) }
    else -> value.toString()
}
