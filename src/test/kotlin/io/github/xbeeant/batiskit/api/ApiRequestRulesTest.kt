package io.github.xbeeant.batiskit.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiRequestRulesTest {

    private fun endpoint(method: String = "GET", path: String = "/api/users/{id}") =
        ApiEndpoint(httpMethod = method, path = path, className = "UserController", methodName = "get")

    @Test
    fun `test 只有 HTTP 方法可发请求`() {
        assertTrue(ApiRequestRules.isHttp("GET"))
        assertTrue(ApiRequestRules.isHttp("patch"))
        assertTrue(ApiRequestRules.isHttp(SpringMappings.REQUEST))
        assertFalse(ApiRequestRules.isHttp(SpringMappings.SCHEDULE))
        assertFalse(ApiRequestRules.isHttp(DubboMappings.DUBBO))
    }

    @Test
    fun `test REQUEST 归一化为 GET`() {
        assertEquals("GET", ApiRequestRules.normalizedMethod(SpringMappings.REQUEST))
        assertEquals("POST", ApiRequestRules.normalizedMethod("post"))
    }

    @Test
    fun `test 路径变量提取与去重`() {
        assertEquals(listOf("id", "type"), ApiRequestRules.pathVariables("/a/{id}/b/{type}/c/{id}"))
        assertTrue(ApiRequestRules.pathVariables("/a/no-var").isEmpty())
    }

    @Test
    fun `test 路径变量按提供值替换 未填的保留`() {
        val url = "http://localhost:8080/a/{id}/b/{type}"
        val result = ApiRequestRules.applyPathVariables(url, mapOf("id" to "1 2"))
        assertEquals("http://localhost:8080/a/1+2/b/{type}", result)
        assertEquals(url, ApiRequestRules.applyPathVariables(url, emptyMap()))
    }

    @Test
    fun `test query 追加与已有 query 合并`() {
        assertEquals("http://h/p?a=1&b=x+y", ApiRequestRules.appendQuery("http://h/p", listOf("a" to "1", "b" to "x y")))
        assertEquals("http://h/p?old=1&a=2", ApiRequestRules.appendQuery("http://h/p?old=1", listOf("a" to "2")))
        assertEquals("http://h/p", ApiRequestRules.appendQuery("http://h/p", listOf("" to "1", "a" to "")))
    }

    @Test
    fun `test 默认请求头按是否有请求体区分`() {
        assertEquals(listOf("Accept" to "application/json"), ApiRequestRules.defaultHeaders("GET"))
        assertEquals(2, ApiRequestRules.defaultHeaders("POST").size)
        assertTrue(ApiRequestRules.supportsBody("PATCH"))
        assertFalse(ApiRequestRules.supportsBody("DELETE"))
    }

    @Test
    fun `test initialSpec 复用 CurlBuilder 的 URL 与参数`() {
        val params = CurlBuilder.Params(
            pathVars = mapOf("id" to "42"),
            query = listOf("q" to "tom"),
            headers = listOf("X-Token" to "abc"),
            body = "{\"name\":\"a\"}"
        )
        val spec = ApiRequestRules.initialSpec(endpoint("POST"), "http://localhost:8080", params)!!
        assertEquals("POST", spec.method)
        assertTrue(spec.url.contains("/api/users/42"))
        assertTrue(spec.url.contains("q=tom"))
        assertTrue(spec.headers.any { it.first == "X-Token" })
        assertTrue(spec.headers.any { it.first.equals("Content-Type", true) })
        assertEquals("{\"name\":\"a\"}", spec.body)
    }

    @Test
    fun `test initialSpec 非 HTTP 接口返回 null`() {
        assertNull(ApiRequestRules.initialSpec(endpoint(SpringMappings.SCHEDULE, "")))
        assertNull(ApiRequestRules.initialSpec(endpoint(DubboMappings.DUBBO, "")))
    }

    @Test
    fun `test parsePairs 支持注释 空行与值内等号`() {
        val text = "# 注释\n\nid = 7\nfilter=a=b\n:bad\nkey: value\n"
        val pairs = ApiRequestRules.parsePairs(text)
        assertEquals(listOf("id" to "7", "filter" to "a=b", "key" to "value"), pairs)
    }

    @Test
    fun `test toHttpFile 生成 IDE 原生格式`() {
        val spec = ApiRequestRules.Spec(
            "POST", "http://localhost:8080/api/users",
            listOf("Content-Type" to "application/json"), "{\"name\":\"a\"}"
        )
        val file = ApiRequestRules.toHttpFile(spec, "创建用户")
        assertTrue(file.startsWith("### 创建用户 — POST "))
        assertTrue(file.contains("\nPOST http://localhost:8080/api/users\n"))
        assertTrue(file.contains("Content-Type: application/json"))
        assertTrue(file.endsWith("{\"name\":\"a\"}\n\n"))
    }

    @Test
    fun `test toHttpFile 不把 GET 的 body 写进去`() {
        val spec = ApiRequestRules.Spec("GET", "http://h/p", emptyList(), "should-not-appear")
        assertFalse(ApiRequestRules.toHttpFile(spec).contains("should-not-appear"))
    }

    @Test
    fun `test prettyJson 缩进且不破坏字符串里的括号`() {
        val pretty = ApiRequestRules.prettyJson("{\"a\":1,\"b\":\"{not,real}\"}")
        assertTrue(pretty.contains("\n  \"a\": 1"))
        assertTrue(pretty.contains("{not,real}"))
    }

    @Test
    fun `test prettyJson 非 JSON 原样返回且超长截断`() {
        assertEquals("plain text", ApiRequestRules.prettyJson("plain text"))
        val long = "x".repeat(ApiRequestRules.MAX_BODY_CHARS + 100)
        assertEquals(ApiRequestRules.MAX_BODY_CHARS, ApiRequestRules.prettyJson(long).length)
    }

    @Test
    fun `test 空输入与非法输入不抛异常`() {
        assertTrue(ApiRequestRules.pathVariables("").isEmpty())
        assertTrue(ApiRequestRules.parsePairs("").isEmpty())
        assertEquals("", ApiRequestRules.applyPathVariables("", mapOf("a" to "b")))
        assertEquals("http://h/p", ApiRequestRules.appendQuery("http://h/p", emptyList()))
    }
}
