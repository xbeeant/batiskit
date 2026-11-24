package io.github.xbeeant.batiskit.api

/**
 * 一个可搜索的接口条目（不含 PSI，便于单测与排序）。
 *
 * [httpMethod] 取 GET / POST / PUT / DELETE / PATCH / REQUEST / SCHEDULE / DUBBO / DUBBO_REF；
 * 定时任务与 Dubbo 方法没有路径，[path] 为空，用 [label] 显示 `类名#方法名`。
 */
data class ApiEndpoint(
    val httpMethod: String,
    val path: String,
    val className: String,
    val methodName: String,
    val moduleName: String = "",
    /** 附加信息，例如 `@Scheduled` 的 cron */
    val detail: String = ""
) {

    /** 列表里显示的定位文本 */
    val label: String
        get() = if (httpMethod == SpringMappings.SCHEDULE || path.isEmpty()) {
            "$className#$methodName"
        } else {
            path
        }

    /** 搜索用的全文（小写） */
    val searchText: String
        get() = "$httpMethod $path $className $methodName $moduleName $detail".lowercase()

    /** `GET /api/users` 形式，复制用 */
    val qualifiedLabel: String
        get() = if (label.isEmpty()) httpMethod else "$httpMethod $label"
}

/** 搜索与按 HTTP 方法过滤（纯函数） */
object ApiFilter {

    /** 空格分隔多个关键字（与参考实现一致：全部命中才算匹配） */
    fun terms(query: String): List<String> =
        query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }

    /** [enabledMethods] 为空表示不过滤方法 */
    fun matches(endpoint: ApiEndpoint, terms: List<String>, enabledMethods: Set<String>): Boolean {
        if (enabledMethods.isNotEmpty() && endpoint.httpMethod.uppercase() !in enabledMethods) {
            return false
        }
        if (terms.isEmpty()) {
            return true
        }
        val text = endpoint.searchText
        return terms.all { text.contains(it) }
    }

    fun filter(items: List<ApiEndpoint>, query: String, enabledMethods: Set<String>): List<ApiEndpoint> {
        val terms = terms(query)
        return items.filter { matches(it, terms, enabledMethods) }
    }
}
