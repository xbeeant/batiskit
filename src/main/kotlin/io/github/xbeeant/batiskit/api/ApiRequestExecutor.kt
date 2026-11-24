package io.github.xbeeant.batiskit.api

import com.intellij.openapi.application.ApplicationManager
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration

/**
 * 真正发请求的一层：用 JBR 自带的 `java.net.http.HttpClient`，零第三方依赖、不落任何凭据。
 *
 * 只做「写接口时顺手试一下」这一件事。环境变量、鉴权、测试集管理都交给 IDE 原生
 * HTTP Client（对话框里的「另存为 .http」），不在这里重造 Postman。
 */
object ApiRequestExecutor {

    /** JDK HttpClient 禁止手工设置的 header，传进去会直接抛异常 */
    private val RESTRICTED_HEADERS = setOf(
        "connection", "content-length", "host", "upgrade", "via", "date",
        "expect", "te", "trailer", "transfer-encoding"
    )

    data class Result(
        val status: Int = 0,
        val headers: String = "",
        val body: String = "",
        val elapsedMs: Long = 0,
        val error: String? = null,
    ) {
        val ok: Boolean get() = error == null && status in 200..299
    }

    /** 后台线程发送，回调在 EDT；任何异常都转成 [Result.error]，不往外抛 */
    fun send(spec: ApiRequestRules.Spec, timeoutSeconds: Int = 10, onDone: (Result) -> Unit) {
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = runCatching { execute(spec, timeoutSeconds) }
                .getOrElse { Result(error = it.message ?: it.javaClass.simpleName) }
            ApplicationManager.getApplication().invokeLater { onDone(result) }
        }
    }

    private fun execute(spec: ApiRequestRules.Spec, timeoutSeconds: Int): Result {
        val started = System.currentTimeMillis()
        val uri = runCatching { URI.create(spec.url.trim()) }
            .getOrElse { return Result(error = "URL 不合法：${it.message ?: "无法解析"}") }
        if (uri.scheme.isNullOrBlank() || !uri.scheme.equals("http", true) && !uri.scheme.equals("https", true)) {
            return Result(error = "只支持 http / https 地址")
        }

        val publisher = if (!spec.body.isNullOrEmpty() && ApiRequestRules.supportsBody(spec.method)) {
            HttpRequest.BodyPublishers.ofString(spec.body, StandardCharsets.UTF_8)
        } else {
            HttpRequest.BodyPublishers.noBody()
        }
        val builder = HttpRequest.newBuilder(uri)
            .timeout(Duration.ofSeconds(timeoutSeconds.coerceIn(1, 120).toLong()))
            .method(spec.method.uppercase(), publisher)
        spec.headers.forEach { (key, value) ->
            if (key.isNotBlank() && key.lowercase() !in RESTRICTED_HEADERS) {
                runCatching { builder.header(key, value) }
            }
        }

        val client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(timeoutSeconds.coerceIn(1, 120).toLong()))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build()
        val response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
        val headerText = response.headers().map().entries
            .sortedBy { it.key }
            .joinToString("\n") { (key, values) -> "$key: ${values.joinToString(", ")}" }
        return Result(
            status = response.statusCode(),
            headers = headerText,
            body = response.body().take(ApiRequestRules.MAX_BODY_CHARS),
            elapsedMs = System.currentTimeMillis() - started
        )
    }
}
