package io.github.xbeeant.batiskit.api

import java.net.URLEncoder

/**
 * 把接口条目转成 cURL 命令（纯逻辑，便于单测）。
 *
 * 没有从工程里推断服务端口的能力，主机部分默认 [DEFAULT_BASE_URL]，粘贴后自行改成真实地址；
 * [contextPath] 由 [ContextPathResolver] 从 Spring 配置里探测（或用户手动覆盖），
 * 既可以直接拼进 [baseUrl]，也可以单独传进来。
 * 路径里的 `{id}` 占位符在 [Params.pathVars] 命中时替换成样例值。
 * 定时任务 / Dubbo 接口不是 HTTP 请求，返回 null。
 */
object CurlBuilder {

    const val DEFAULT_BASE_URL = ContextPathRules.DEFAULT_BASE_URL

    private val HTTP_METHODS = setOf("GET", "POST", "PUT", "DELETE", "PATCH", SpringMappings.REQUEST)

    /** 带请求体的方法，追加 Content-Type 头（GET / DELETE 不加） */
    private val BODY_METHODS = setOf("POST", "PUT", "PATCH")

    /** 从方法签名解析出的参数信息（见 [CurlPsiReader]），为空时退化为纯路径形式 */
    data class Params(
        /** @PathVariable：路径占位符名 -> 样例值 */
        val pathVars: Map<String, String> = emptyMap(),
        /** @RequestParam（以及未注解的简单类型参数）：追加到 URL 的 query */
        val query: List<Pair<String, String>> = emptyList(),
        /** @RequestHeader */
        val headers: List<Pair<String, String>> = emptyList(),
        /** @RequestBody：按 DTO 字段生成的 JSON 骨架 */
        val body: String? = null
    )

    fun build(
        endpoint: ApiEndpoint,
        baseUrl: String = DEFAULT_BASE_URL,
        params: Params? = null,
        contextPath: String = ""
    ): String? {
        val method = endpoint.httpMethod.uppercase()
        if (method !in HTTP_METHODS) {
            return null
        }
        // @RequestMapping 未指定 method 时按 GET 请求
        val actual = if (method == SpringMappings.REQUEST) "GET" else method
        val url = buildUrl(endpoint, baseUrl, params, contextPath) ?: return null

        val requestBody = params?.body
        val command = StringBuilder("curl")
        // GET 携带 -d 时 curl 会改成 POST，用 -G 保持 GET 并把 -d 变成 query
        if (actual == "GET" && !requestBody.isNullOrEmpty()) {
            command.append(" -G")
        }
        if (actual != "GET") {
            command.append(" -X ").append(actual)
        }
        command.append(" '").append(url).append('\'')

        val headers = params?.headers.orEmpty()
        val hasContentType = headers.any { it.first.equals("Content-Type", ignoreCase = true) }
        if (actual in BODY_METHODS && !hasContentType) {
            command.append(" -H 'Content-Type: application/json'")
        }
        headers.forEach { (key, value) ->
            command.append(" -H '").append(key).append(": ").append(value).append('\'')
        }
        if (!requestBody.isNullOrEmpty()) {
            command.append(" -d '").append(requestBody.replace("'", "'\\''")).append('\'')
        }
        return command.toString()
    }

    /**
     * 只拼完整 URL（不含 `curl` 命令），供面板「复制完整 URL」复用。
     * 非 HTTP 接口返回 null；context path 单独传时由 [ContextPathRules.compose] 插入。
     */
    fun buildUrl(
        endpoint: ApiEndpoint,
        baseUrl: String = DEFAULT_BASE_URL,
        params: Params? = null,
        contextPath: String = ""
    ): String? {
        if (endpoint.httpMethod.uppercase() !in HTTP_METHODS) {
            return null
        }
        var path = endpoint.path
        params?.pathVars?.forEach { (name, value) -> path = path.replace("{$name}", value) }
        var url = ContextPathRules.compose(baseUrl, contextPath, path)
        val query = params?.query.orEmpty()
        if (query.isNotEmpty()) {
            url += "?" + query.joinToString("&") { (key, value) -> "${encode(key)}=${encode(value)}" }
        }
        return url
    }

    private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8)
}
