package io.github.xbeeant.batiskit.api

import java.net.URLEncoder

/**
 * 接口调试的纯逻辑层：请求规格、参数拼装与 IDEA `.http` 文件生成。
 *
 * 刻意不重造 Postman：URL / cURL / context path 都复用 [CurlBuilder]，这里只负责
 * 「把一份可编辑的请求规格变成一次 HTTP 调用」以及「另存成 IDE 原生 HTTP Client 文件」。
 * 零平台依赖，可纯 JVM 单测。
 */
object ApiRequestRules {

    const val MAX_BODY_CHARS = 512_000

    private val HTTP_METHODS = setOf("GET", "POST", "PUT", "DELETE", "PATCH", SpringMappings.REQUEST)
    private val BODY_METHODS = setOf("POST", "PUT", "PATCH")
    private val PATH_VAR = Regex("""\{([A-Za-z_][A-Za-z0-9_]*)\}""")

    data class Spec(
        val method: String,
        val url: String,
        val headers: List<Pair<String, String>> = emptyList(),
        val body: String? = null,
    )

    fun isHttp(method: String): Boolean = method.uppercase() in HTTP_METHODS

    /** `@RequestMapping` 未声明 method 时按 GET 处理，与 CurlBuilder 一致 */
    fun normalizedMethod(method: String): String =
        method.uppercase().let { if (it == SpringMappings.REQUEST) "GET" else it }

    fun supportsBody(method: String): Boolean = normalizedMethod(method) in BODY_METHODS

    fun pathVariables(text: String): List<String> =
        PATH_VAR.findAll(text).map { it.groupValues[1] }.distinct().toList()

    /** 只替换提供了值的占位符；没填的保留 `{name}`，让用户一眼看出还差什么 */
    fun applyPathVariables(url: String, values: Map<String, String>): String =
        PATH_VAR.replace(url) { match ->
            val value = values[match.groupValues[1]]
            if (value.isNullOrEmpty()) match.value else encode(value)
        }

    /** query 追加；已带 `?` 的 URL 用 `&` 连接，空值参数跳过 */
    fun appendQuery(url: String, params: List<Pair<String, String>>): String {
        val valid = params.filter { it.first.isNotBlank() && it.second.isNotEmpty() }
        if (valid.isEmpty()) return url
        val query = valid.joinToString("&") { (key, value) -> "${encode(key)}=${encode(value)}" }
        val separator = if (url.contains('?')) "&" else "?"
        return url + separator + query
    }

    fun defaultHeaders(method: String): List<Pair<String, String>> =
        buildList {
            add("Accept" to "application/json")
            if (supportsBody(method)) add("Content-Type" to "application/json")
        }

    /** 从列表条目构造初始规格；定时任务 / Dubbo 返回 null */
    fun initialSpec(
        endpoint: ApiEndpoint,
        baseUrl: String = CurlBuilder.DEFAULT_BASE_URL,
        params: CurlBuilder.Params? = null,
    ): Spec? {
        val url = CurlBuilder.buildUrl(endpoint, baseUrl, params) ?: return null
        val method = normalizedMethod(endpoint.httpMethod)
        val headers = (params?.headers.orEmpty() + defaultHeaders(method))
            .distinctBy { it.first.lowercase() }
        return Spec(method, url, headers, params?.body)
    }

    /** 解析 `key=value` 行；`#` 开头是注释，空行跳过，值里允许 `=` */
    fun parsePairs(text: String): List<Pair<String, String>> =
        text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .mapNotNull { line ->
                val at = line.indexOfFirst { it == '=' || it == ':' }
                if (at <= 0) null else line.substring(0, at).trim() to line.substring(at + 1).trim()
            }
            .filter { it.first.isNotEmpty() }
            .toList()

    /** 生成 IDEA HTTP Client 可直接打开的 `.http` 文本 */
    fun toHttpFile(spec: Spec, comment: String = ""): String = buildString {
        append("### ")
        if (comment.isNotBlank()) append(comment.trim()).append(" — ")
        append(spec.method).append(' ').append(spec.url).append('\n')
        append(spec.method).append(' ').append(spec.url).append('\n')
        spec.headers.filter { it.first.isNotBlank() }.forEach { (key, value) ->
            append(key).append(": ").append(value).append('\n')
        }
        if (!spec.body.isNullOrBlank() && supportsBody(spec.method)) {
            append('\n').append(spec.body.trim()).append('\n')
        }
        append('\n')
    }

    /** 轻量 JSON 缩进，只为让响应可读；不是 JSON 时原样返回 */
    fun prettyJson(text: String): String {
        val source = text.take(MAX_BODY_CHARS).trim()
        if (!source.startsWith("{") && !source.startsWith("[")) return text.take(MAX_BODY_CHARS)
        val out = StringBuilder(source.length + 64)
        var indent = 0
        var inString = false
        var escaped = false
        for (ch in source) {
            when {
                escaped -> {
                    out.append(ch)
                    escaped = false
                }
                ch == '\\' && inString -> {
                    out.append(ch)
                    escaped = true
                }
                ch == '"' -> {
                    inString = !inString
                    out.append(ch)
                }
                inString -> out.append(ch)
                ch == '{' || ch == '[' -> {
                    out.append(ch).append('\n')
                    indent++
                    out.append("  ".repeat(indent.coerceAtMost(32)))
                }
                ch == '}' || ch == ']' -> {
                    indent = (indent - 1).coerceAtLeast(0)
                    out.append('\n').append("  ".repeat(indent.coerceAtMost(32))).append(ch)
                }
                ch == ',' -> out.append(ch).append('\n').append("  ".repeat(indent.coerceAtMost(32)))
                ch == ':' -> out.append(": ")
                ch.isWhitespace() -> Unit
                else -> out.append(ch)
            }
        }
        return out.toString()
    }

    private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8)
}
