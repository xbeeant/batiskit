package io.github.xbeeant.batiskit.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Spring context path 静态探测的纯逻辑测试：多版本键、relaxed binding、properties / yaml、
 * profile / 多模块优先级、Nacos 识别，以及拼进 cURL / 完整 URL 的结果。
 */
class ContextPathRulesTest {

    private fun file(name: String, text: String, path: String = "src/main/resources/$name") =
        ContextPathRules.ConfigFile(name, text, path)

    private fun detect(vararg files: ContextPathRules.ConfigFile): ContextPathRules.Detection =
        ContextPathRules.resolve(files.toList())

    // ------------------------------------------------------------------ 归一化与拼接

    @Test
    fun `context path 归一化补前导斜杠去尾斜杠折叠重复斜杠`() {
        assertEquals("/api", ContextPathRules.normalize("api"))
        assertEquals("/api", ContextPathRules.normalize("/api/"))
        assertEquals("/api/v1", ContextPathRules.normalize("//api//v1//"))
        assertEquals("", ContextPathRules.normalize("/"))
        assertEquals("", ContextPathRules.normalize(""))
        assertEquals("", ContextPathRules.normalize(null))
        assertEquals("", ContextPathRules.normalize("\"/\""))
        assertEquals("/api", ContextPathRules.normalize("'/api'"))
        assertEquals("", ContextPathRules.normalize("/api with space"))
        assertEquals("", ContextPathRules.normalize("\${ctx}"))
    }

    @Test
    fun `完整地址拆成主机与 context path`() {
        assertEquals("http://localhost:8080" to "", ContextPathRules.splitBaseUrl("http://localhost:8080/"))
        assertEquals("http://localhost:8080" to "/api", ContextPathRules.splitBaseUrl("http://localhost:8080/api"))
        assertEquals("http://10.0.0.8:9000" to "/order/v1", ContextPathRules.splitBaseUrl("http://10.0.0.8:9000/order/v1/"))
    }

    @Test
    fun `用户手填地址补 scheme 去尾斜杠`() {
        assertEquals("http://localhost:8080", ContextPathRules.normalizeBaseUrl(null))
        assertEquals("http://localhost:8080", ContextPathRules.normalizeBaseUrl("  "))
        assertEquals("http://10.0.0.8:9000/api", ContextPathRules.normalizeBaseUrl("10.0.0.8:9000/api/"))
        assertEquals("https://api.example.com/api", ContextPathRules.normalizeBaseUrl("https://api.example.com/api/"))
    }

    @Test
    fun `compose 把主机 context path 与接口路径拼成完整 URL`() {
        val host = "http://localhost:8080"
        assertEquals("http://localhost:8080/users", ContextPathRules.compose(host, "", "/users"))
        assertEquals("http://localhost:8080/api/users", ContextPathRules.compose(host, "/api", "/users"))
        assertEquals("http://localhost:8080/api/users", ContextPathRules.compose("$host/", "api/", "users"))
        assertEquals("http://localhost:8080/api", ContextPathRules.compose(host, "/api", ""))
        assertEquals("http://localhost:8080", ContextPathRules.compose(host, "", ""))
        // 手动覆盖的完整地址已经含 context path，再传空前缀不能多出斜杠
        assertEquals("http://localhost:8080/api/users", ContextPathRules.compose("$host/api", "", "/users"))
    }

    // ------------------------------------------------------------------ properties：各版本键

    @Test
    fun `Boot 2 与 3 的 servlet context path`() {
        val detection = detect(file("application.properties", "server.servlet.context-path=/api/v1\n"))
        assertEquals("/api/v1", detection.contextPath)
        assertTrue(detection.source.contains("server.servlet.context-path"))
        assertNull(detection.note)
        assertFalse(detection.nacos)
    }

    @Test
    fun `Boot 1 的 server context path`() {
        val detection = detect(file("application.properties", "server.context-path=/legacy\n"))
        assertEquals("/legacy", detection.contextPath)
        assertTrue(detection.source.contains("Boot 1.x"))
    }

    @Test
    fun `WebFlux 的 spring webflux base path`() {
        val detection = detect(file("application.yml", "spring:\n  webflux:\n    base-path: /reactive\n"))
        assertEquals("/reactive", detection.contextPath)
        assertTrue(detection.source.contains("WebFlux"))
    }

    @Test
    fun `relaxed binding 兼容 camelCase 下划线与环境变量写法`() {
        val cases = listOf(
            "server.servlet.contextPath=/a" to "/a",
            "SERVER_SERVLET_CONTEXT_PATH=/b" to "/b",
            "server.servlet.context_path=/c" to "/c",
            "server.contextPath=/d" to "/d",
            "SPRING_WEBFLUX_BASE_PATH=/e" to "/e"
        )
        cases.forEach { (text, expected) ->
            assertEquals("配置 $text", expected, detect(file("application.properties", text)).contextPath)
        }
    }

    @Test
    fun `同一份配置里新键优先于旧键 重复键后者覆盖前者`() {
        val text = """
            server.context-path=/legacy
            server.servlet.context-path=/modern
        """.trimIndent()
        assertEquals("/modern", detect(file("application.properties", text)).contextPath)

        val duplicate = """
            server.servlet.context-path=/first
            # 下面这行生效
            server.servlet.context-path=/second
        """.trimIndent()
        assertEquals("/second", detect(file("application.properties", duplicate)).contextPath)
    }

    @Test
    fun `properties 支持冒号分隔 空格 引号与注释`() {
        val text = """
            # 注释
            ! 另一种注释
            server.servlet.context-path : "/quoted"
        """.trimIndent()
        assertEquals("/quoted", detect(file("application.properties", text)).contextPath)
        assertEquals("/spaced", detect(file("application.properties", "server.servlet.context-path   /spaced\n")).contextPath)
    }

    @Test
    fun `占位符能读默认值或同文件变量 读不出时给提示`() {
        assertEquals(
            "/fallback",
            detect(file("application.properties", "server.servlet.context-path=\${CTX:/fallback}\n")).contextPath
        )
        assertEquals(
            "/local",
            detect(
                file(
                    "application.properties",
                    "ctx=/local\nserver.servlet.context-path=\${ctx}\n"
                )
            ).contextPath
        )
        val unresolved = detect(file("application.properties", "server.servlet.context-path=\${CTX}\n"))
        assertEquals("", unresolved.contextPath)
        assertTrue(unresolved.note?.contains("占位符") == true)
    }

    // ------------------------------------------------------------------ yaml

    @Test
    fun `yaml 缩进嵌套与点分扁平写法等价`() {
        val nested = """
            server:
              port: 8080
              servlet:
                context-path: /nested
        """.trimIndent()
        assertEquals("/nested", detect(file("application.yml", nested)).contextPath)
        assertEquals("/flat", detect(file("application.yml", "server.servlet.context-path: /flat\n")).contextPath)
    }

    @Test
    fun `yaml 支持引号 注释 流式 map 与多文档`() {
        assertEquals(
            "/quoted",
            detect(file("application.yaml", "server:\n  servlet:\n    context-path: '/quoted' # 注释\n")).contextPath
        )
        assertEquals(
            "/flow",
            detect(file("application.yml", "server: {servlet: {context-path: /flow}}\n")).contextPath
        )
        val docs = """
            server:
              servlet:
                context-path: /base
            ---
            spring:
              config:
                activate:
                  on-profile: dev
            server:
              servlet:
                context-path: /dev
        """.trimIndent()
        val detection = detect(file("application.yml", docs))
        assertEquals("/base", detection.contextPath)
        assertEquals(listOf("/base", "/dev"), detection.candidates)
        assertTrue(detection.note?.contains("多个不同的 context path") == true)
    }

    @Test
    fun `spring profiles active 不会把主配置误判成 profile 专属`() {
        val text = """
            spring:
              profiles:
                active: dev
            server:
              servlet:
                context-path: /base
        """.trimIndent()
        val detection = detect(file("application.yml", text))
        assertEquals("/base", detection.contextPath)
        assertNull(detection.note)
    }

    // ------------------------------------------------------------------ 优先级 / 多模块

    @Test
    fun `主配置优先于 profile 专属与 bootstrap 测试资源权重最低`() {
        val base = file("application.properties", "server.servlet.context-path=/base\n")
        val profile = file("application-dev.yml", "server:\n  servlet:\n    context-path: /dev\n")
        val boot = file("bootstrap.yml", "server:\n  servlet:\n    context-path: /bootstrap\n")
        assertEquals("/base", detect(base, profile, boot).contextPath)

        val testOnly = file(
            "application.properties",
            "server.servlet.context-path=/test-only\n",
            "src/test/resources/application.properties"
        )
        assertEquals("/base", detect(base, testOnly).contextPath)
        assertTrue(detect(base, testOnly).candidates.contains("/test-only"))
    }

    @Test
    fun `多模块出现不同 context path 时给出候选与提示`() {
        val a = file("application.yml", "server:\n  servlet:\n    context-path: /order\n", "order/src/main/resources/application.yml")
        val b = file("application.yml", "server:\n  servlet:\n    context-path: /user\n", "user/src/main/resources/application.yml")
        val detection = detect(a, b)
        assertTrue(detection.candidates.containsAll(listOf("/order", "/user")))
        assertTrue(detection.note?.contains("多个不同") == true)
    }

    @Test
    fun `没有配置时按根上下文 显式配置为空也按根上下文`() {
        assertEquals("", detect().contextPath)
        assertEquals("", detect(file("application.properties", "server.port=8080\n")).contextPath)
        assertEquals("", detect(file("application.yml", "server:\n  servlet:\n    context-path: /\n")).contextPath)
    }

    // ------------------------------------------------------------------ Nacos / 配置中心

    @Test
    fun `识别 Nacos 旧写法 新版 import 嵌套 yaml 与 bootstrap`() {
        val old = file("bootstrap.properties", "spring.cloud.nacos.config.server-addr=127.0.0.1:8848\n")
        assertTrue(detect(old).nacos)
        assertTrue(detect(old).note?.contains("配置中心") == true)

        val imported = file("application.yml", "spring:\n  config:\n    import: optional:nacos:demo.yaml\n")
        assertTrue(detect(imported).nacos)

        val nested = file(
            "application.yml",
            "spring:\n  cloud:\n    nacos:\n      config:\n        server-addr: 127.0.0.1:8848\n"
        )
        assertTrue(detect(nested).nacos)

        // 只有 bootstrap 文件、没写 nacos 关键字也提示「可能在配置中心」
        val bootstrap = detect(file("bootstrap.yml", "spring:\n  application:\n    name: demo\n"))
        assertTrue(bootstrap.note?.contains("bootstrap") == true)
    }

    @Test
    fun `构建脚本里的 Nacos 依赖也会触发提示`() {
        val detection = ContextPathRules.resolve(
            listOf(file("application.properties", "server.port=8080\n")),
            listOf("implementation(\"com.alibaba.cloud:spring-cloud-starter-alibaba-nacos-config\")")
        )
        assertTrue(detection.nacos)
        assertTrue(detection.note?.contains("远端") == true)
    }

    @Test
    fun `本地 config import 与纯服务发现不算配置中心`() {
        assertFalse(ContextPathRules.hasNacosReference("spring.config.import=classpath:local.yml"))
        assertFalse(ContextPathRules.hasNacosReference("spring.cloud.nacos.discovery.server-addr=127.0.0.1:8848"))
        assertTrue(ContextPathRules.hasNacosReference("spring.cloud.nacos.config.context-path=/nacos-api"))
    }

    @Test
    fun `Nacos 的 context-path 是服务端 API 路径 不能当成应用 context path`() {
        val detection = detect(
            file(
                "bootstrap.properties",
                "spring.cloud.nacos.config.context-path=/nacos-api\n" +
                    "spring.cloud.nacos.config.server-addr=127.0.0.1:8848\n"
            )
        )
        assertEquals("", detection.contextPath)
        assertTrue(detection.nacos)
        assertTrue(detection.note?.contains("远端") == true)
    }

    @Test
    fun `本地已有 context path 时仍提示远端可能覆盖`() {
        val detection = detect(
            file(
                "application.yml",
                "server:\n  servlet:\n    context-path: /local\n" +
                    "spring:\n  cloud:\n    nacos:\n    config:\n      server-addr: 127.0.0.1:8848\n"
            )
        )
        assertEquals("/local", detection.contextPath)
        assertTrue(detection.nacos)
        assertTrue(detection.note?.contains("覆盖本地") == true)
    }

    // ------------------------------------------------------------------ 接进 cURL / 完整 URL

    @Test
    fun `cURL 带上探测到的 context path`() {
        val endpoint = ApiEndpoint("POST", "/users/{id}", "UserController", "update")
        assertEquals(
            "curl -X POST 'http://localhost:8080/order-api/users/1' -H 'Content-Type: application/json'",
            CurlBuilder.build(
                endpoint,
                params = CurlBuilder.Params(pathVars = mapOf("id" to "1")),
                contextPath = "order-api/"
            )
        )
    }

    @Test
    fun `复制完整 URL 与 cURL 共用同一个地址前缀`() {
        val endpoint = ApiEndpoint("GET", "/users", "UserController", "list")
        val params = CurlBuilder.Params(query = listOf("page" to "1"))
        assertEquals(
            "http://localhost:8080/api/users?page=1",
            CurlBuilder.buildUrl(endpoint, contextPath = "/api", params = params)
        )
        assertEquals(
            "curl 'http://localhost:8080/api/users?page=1'",
            CurlBuilder.build(endpoint, contextPath = "/api", params = params)
        )
        // 面板走的是「完整地址已含 context path」这条路径
        assertEquals(
            "http://localhost:8080/api/users?page=1",
            CurlBuilder.buildUrl(endpoint, baseUrl = "http://localhost:8080/api", params = params)
        )
    }

    @Test
    fun `非 HTTP 接口没有完整 URL`() {
        assertNull(CurlBuilder.buildUrl(ApiEndpoint(SpringMappings.SCHEDULE, "", "Job", "run")))
        assertNull(CurlBuilder.buildUrl(ApiEndpoint("DUBBO", "", "ServiceImpl", "get")))
    }
}
