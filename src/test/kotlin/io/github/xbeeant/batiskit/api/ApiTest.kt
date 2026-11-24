package io.github.xbeeant.batiskit.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 接口扫描的纯逻辑测试：路径拼接、@RequestMapping(method=...) 解析、搜索过滤、展示文本。
 */
class ApiTest {

    // ------------------------------------------------------------------ 路径拼接

    @Test
    fun `类前缀与方法路径拼接去掉多余斜杠`() {
        assertEquals("/api/users", SpringMappings.joinPath("/api/", "/users"))
        assertEquals("/api/users", SpringMappings.joinPath("/api", "users"))
        assertEquals("/api/users/{id}", SpringMappings.joinPath("/api/users", "/{id}/"))
    }

    @Test
    fun `前缀或子路径为空时的归一化`() {
        assertEquals("/", SpringMappings.joinPath("", ""))
        assertEquals("/", SpringMappings.joinPath("/", "/"))
        assertEquals("/users", SpringMappings.joinPath("", "/users"))
        assertEquals("/api", SpringMappings.joinPath("/api/", ""))
        assertEquals("/api", SpringMappings.joinPath("api", ""))
    }

    // ------------------------------------------------------------------ 请求方法解析

    @Test
    fun `RequestMapping 未写 method 时归为 REQUEST`() {
        assertEquals(listOf(SpringMappings.REQUEST), SpringMappings.parseRequestMethods(""))
        assertEquals(listOf(SpringMappings.REQUEST), SpringMappings.parseRequestMethods("@RequestMapping(\"/x\")"))
    }

    @Test
    fun `RequestMapping 的 method 数组解析成多个方法`() {
        assertEquals(
            listOf("GET", "POST"),
            SpringMappings.parseRequestMethods("{RequestMethod.GET, RequestMethod.POST}")
        )
        assertEquals(listOf("DELETE"), SpringMappings.parseRequestMethods("RequestMethod.DELETE"))
    }

    // ------------------------------------------------------------------ 搜索与过滤

    private fun sample() = listOf(
        ApiEndpoint("GET", "/api/users", "UserController", "list", "demo"),
        ApiEndpoint("POST", "/api/users", "UserController", "create", "demo"),
        ApiEndpoint("DELETE", "/api/users/{id}", "UserController", "delete", "demo"),
        ApiEndpoint("GET", "/api/orders", "OrderController", "page", "order"),
        ApiEndpoint(
            SpringMappings.SCHEDULE,
            "",
            "OrderJob",
            "sync",
            "order",
            detail = "cron=0 0/5 * * * ?"
        )
    )

    @Test
    fun `空格分隔的关键字需要全部命中`() {
        val result = ApiFilter.filter(sample(), "user get", emptySet())
        assertEquals(listOf("list"), result.map { it.methodName })
        assertTrue(ApiFilter.filter(sample(), "user get post", emptySet()).isEmpty())
    }

    @Test
    fun `搜索忽略大小写并覆盖类名与模块名`() {
        // 关键字大小写不敏感，且命中类名 + 方法名（AND 语义）
        assertEquals(1, ApiFilter.filter(sample(), "UserController DELETE", emptySet()).size)
        // 模块名同样参与匹配（"order" 是 OrderController 所属模块）
        assertEquals(1, ApiFilter.filter(sample(), "order page", emptySet()).size)
        assertEquals(2, ApiFilter.filter(sample(), "ORDER", emptySet()).size)
        assertEquals(1, ApiFilter.filter(sample(), "cron", emptySet()).size)
    }

    @Test
    fun `按 HTTP 方法过滤`() {
        val methods = setOf("GET")
        val result = ApiFilter.filter(sample(), "", methods)
        assertEquals(listOf("/api/users", "/api/orders"), result.map { it.path })
    }

    @Test
    fun `方法过滤与关键字可以叠加`() {
        val result = ApiFilter.filter(sample(), "users", setOf("POST"))
        assertEquals(listOf("create"), result.map { it.methodName })
        assertFalse(ApiFilter.matches(sample().first(), listOf("nomatch"), emptySet()))
    }

    // ------------------------------------------------------------------ 展示文本

    @Test
    fun `普通接口显示路径，定时任务显示类名与方法名`() {
        val endpoint = sample().first()
        assertEquals("/api/users", endpoint.label)
        assertEquals("GET /api/users", endpoint.qualifiedLabel)

        val schedule = sample().last()
        assertEquals("OrderJob#sync", schedule.label)
        assertEquals("SCHEDULE OrderJob#sync", schedule.qualifiedLabel)
    }

    @Test
    fun `Dubbo 方法与引用显示类名与方法名`() {
        val provider = ApiEndpoint("DUBBO", "", "UserDubboServiceImpl", "getUser", detail = "version=1.0.0")
        assertEquals("UserDubboServiceImpl#getUser", provider.label)
        assertEquals("DUBBO UserDubboServiceImpl#getUser", provider.qualifiedLabel)

        val reference = ApiEndpoint("DUBBO_REF", "", "UserController", "userDubboService")
        assertEquals("UserController#userDubboService", reference.label)
        // Dubbo 条目可按方法类型过滤与搜索（detail 参与 searchText）
        assertTrue(ApiFilter.matches(provider, listOf("dubbo", "getuser"), emptySet()))
        assertTrue(ApiFilter.matches(provider, listOf("version"), emptySet()))
        assertTrue(ApiFilter.matches(reference, listOf("dubbo_ref"), setOf("DUBBO_REF")))
    }

    // ------------------------------------------------------------------ 复制为 cURL

    @Test
    fun `GET 请求不带 -X 参数`() {
        val curl = CurlBuilder.build(ApiEndpoint("GET", "/api/users/{id}", "UserController", "getDetails"))
        assertEquals("curl 'http://localhost:8080/api/users/{id}'", curl)
    }

    @Test
    fun `POST 与 PUT 追加 -X 和 Content-Type`() {
        val post = CurlBuilder.build(ApiEndpoint("POST", "/api/users", "UserController", "post"))
        assertEquals(
            "curl -X POST 'http://localhost:8080/api/users' -H 'Content-Type: application/json'",
            post
        )
        val put = CurlBuilder.build(ApiEndpoint("PUT", "/api/users/{id}", "UserController", "put"))
        assertEquals(
            "curl -X PUT 'http://localhost:8080/api/users/{id}' -H 'Content-Type: application/json'",
            put
        )
    }

    @Test
    fun `DELETE 追加 -X 但不带 Content-Type`() {
        val curl = CurlBuilder.build(ApiEndpoint("DELETE", "/api/users/{id}", "UserController", "delete"))
        assertEquals("curl -X DELETE 'http://localhost:8080/api/users/{id}'", curl)
    }

    @Test
    fun `REQUEST 未指定 method 时按 GET`() {
        val curl = CurlBuilder.build(ApiEndpoint(SpringMappings.REQUEST, "/api/users", "UserController", "table"))
        assertEquals("curl 'http://localhost:8080/api/users'", curl)
    }

    @Test
    fun `自定义 baseUrl 处理收尾斜杠`() {
        val curl = CurlBuilder.build(
            ApiEndpoint("GET", "/api/users", "UserController", "list"),
            baseUrl = "http://127.0.0.1:9000/"
        )
        assertEquals("curl 'http://127.0.0.1:9000/api/users'", curl)
    }

    @Test
    fun `定时任务与 Dubbo 接口无法生成 cURL`() {
        assertNull(CurlBuilder.build(ApiEndpoint(SpringMappings.SCHEDULE, "", "OrderJob", "sync")))
        assertNull(CurlBuilder.build(ApiEndpoint("DUBBO", "", "UserDubboServiceImpl", "getUser")))
        assertNull(CurlBuilder.build(ApiEndpoint("DUBBO_REF", "", "UserController", "userDubboService")))
    }

    @Test
    fun `cURL 携带路径参数 query 与 header`() {
        val curl = CurlBuilder.build(
            ApiEndpoint("POST", "/api/users/{id}", "UserController", "post"),
            params = CurlBuilder.Params(
                pathVars = mapOf("id" to "1"),
                query = listOf("name" to "text"),
                headers = listOf("X-Token" to "abc"),
                body = "{\"keyword\":\"text\"}"
            )
        )
        assertEquals(
            "curl -X POST 'http://localhost:8080/api/users/1?name=text' " +
                "-H 'Content-Type: application/json' -H 'X-Token: abc' -d '{\"keyword\":\"text\"}'",
            curl
        )
    }

    @Test
    fun `GET 携带 body 时加 -G 防止 curl 变 POST`() {
        val curl = CurlBuilder.build(
            ApiEndpoint("GET", "/api/users", "UserController", "list"),
            params = CurlBuilder.Params(body = "{\"keyword\":\"text\"}")
        )
        assertEquals("curl -G 'http://localhost:8080/api/users' -d '{\"keyword\":\"text\"}'", curl)
    }

    @Test
    fun `无参数时保持原有纯路径形式`() {
        val curl = CurlBuilder.build(
            ApiEndpoint("POST", "/api/users", "UserController", "post"),
            params = CurlBuilder.Params()
        )
        assertEquals("curl -X POST 'http://localhost:8080/api/users' -H 'Content-Type: application/json'", curl)
    }

    @Test
    fun `搜索文本包含全部可搜字段`() {
        val text = ApiEndpoint("POST", "/api/users", "UserController", "create", "demo", "x=1").searchText
        listOf("post", "/api/users", "usercontroller", "create", "demo", "x=1").forEach {
            assertTrue("搜索文本应包含 $it", text.contains(it))
        }
    }
}
