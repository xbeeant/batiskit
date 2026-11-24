package io.github.xbeeant.batiskit.api

import com.intellij.openapi.application.ApplicationManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * 真实 PSI + 索引下的扫描测试：用桩注解还原 Spring 注解语义，
 * 保证 `@RestController` / `@Controller` / `@Scheduled` 都能被扫到。
 */
class SpringApiScanTest : BasePlatformTestCase() {

    private fun scan(): List<SpringApiService.Record> {
        val records = mutableListOf<SpringApiService.Record>()
        ApplicationManager.getApplication().runReadAction {
            records += SpringApiService(project).scanProject()
        }
        return records
    }

    private fun addAnnotation(path: String, text: String) {
        myFixture.addFileToProject(path, text.trimIndent())
    }

    private fun addSpringStubs() {
        addAnnotation(
            "org/springframework/web/bind/annotation/RequestMethod.java",
            """
            package org.springframework.web.bind.annotation;
            public enum RequestMethod { GET, POST, PUT, DELETE, PATCH }
            """
        )
        addAnnotation(
            "org/springframework/web/bind/annotation/RequestMapping.java",
            """
            package org.springframework.web.bind.annotation;
            import java.lang.annotation.*;
            @Target({ElementType.TYPE, ElementType.METHOD})
            @Retention(RetentionPolicy.RUNTIME)
            public @interface RequestMapping {
                String[] value() default {};
                String[] path() default {};
                RequestMethod[] method() default {};
            }
            """
        )
        addAnnotation(
            "org/springframework/web/bind/annotation/GetMapping.java",
            """
            package org.springframework.web.bind.annotation;
            import java.lang.annotation.*;
            @Target(ElementType.METHOD)
            @Retention(RetentionPolicy.RUNTIME)
            public @interface GetMapping {
                String[] value() default {};
                String[] path() default {};
            }
            """
        )
        addAnnotation(
            "org/springframework/web/bind/annotation/RestController.java",
            """
            package org.springframework.web.bind.annotation;
            import java.lang.annotation.*;
            @Target(ElementType.TYPE)
            @Retention(RetentionPolicy.RUNTIME)
            public @interface RestController {}
            """
        )
        addAnnotation(
            "org/springframework/stereotype/Controller.java",
            """
            package org.springframework.stereotype;
            import java.lang.annotation.*;
            @Target(ElementType.TYPE)
            @Retention(RetentionPolicy.RUNTIME)
            public @interface Controller {}
            """
        )
        addAnnotation(
            "org/springframework/scheduling/annotation/Scheduled.java",
            """
            package org.springframework.scheduling.annotation;
            import java.lang.annotation.*;
            @Target(ElementType.METHOD)
            @Retention(RetentionPolicy.RUNTIME)
            public @interface Scheduled {
                String cron() default "";
            }
            """
        )
        // cURL 参数解析用桩
        addAnnotation(
            "org/springframework/web/bind/annotation/RequestParam.java",
            """
            package org.springframework.web.bind.annotation;
            import java.lang.annotation.*;
            @Target(ElementType.PARAMETER)
            @Retention(RetentionPolicy.RUNTIME)
            public @interface RequestParam {
                String name() default "";
                String value() default "";
            }
            """
        )
        addAnnotation(
            "org/springframework/web/bind/annotation/PathVariable.java",
            """
            package org.springframework.web.bind.annotation;
            import java.lang.annotation.*;
            @Target(ElementType.PARAMETER)
            @Retention(RetentionPolicy.RUNTIME)
            public @interface PathVariable {
                String name() default "";
                String value() default "";
            }
            """
        )
        addAnnotation(
            "org/springframework/web/bind/annotation/RequestHeader.java",
            """
            package org.springframework.web.bind.annotation;
            import java.lang.annotation.*;
            @Target(ElementType.PARAMETER)
            @Retention(RetentionPolicy.RUNTIME)
            public @interface RequestHeader {
                String name() default "";
                String value() default "";
            }
            """
        )
        addAnnotation(
            "org/springframework/web/bind/annotation/RequestBody.java",
            """
            package org.springframework.web.bind.annotation;
            import java.lang.annotation.*;
            @Target(ElementType.PARAMETER)
            @Retention(RetentionPolicy.RUNTIME)
            public @interface RequestBody {}
            """
        )
    }

    private fun addDubboStubs() {
        addAnnotation(
            "org/apache/dubbo/config/annotation/DubboService.java",
            """
            package org.apache.dubbo.config.annotation;
            import java.lang.annotation.*;
            @Target(ElementType.TYPE)
            @Retention(RetentionPolicy.RUNTIME)
            public @interface DubboService {
                String version() default "";
                String group() default "";
                int timeout() default -1;
            }
            """
        )
        addAnnotation(
            "org/apache/dubbo/config/annotation/DubboReference.java",
            """
            package org.apache.dubbo.config.annotation;
            import java.lang.annotation.*;
            @Target({ElementType.FIELD, ElementType.METHOD})
            @Retention(RetentionPolicy.RUNTIME)
            public @interface DubboReference {
                String version() default "";
                String group() default "";
            }
            """
        )
    }

    fun `test 扫描 DubboService 提供者方法`() {
        addSpringStubs()
        addDubboStubs()
        myFixture.addFileToProject(
            "com/example/dubbo/UserDubboServiceImpl.java",
            """
            package com.example.dubbo;

            import org.apache.dubbo.config.annotation.DubboService;

            @DubboService(version = "1.0.0", group = "user")
            public class UserDubboServiceImpl {

                public String getUser(Long id) { return null; }

                public void saveUser(String name) {}

                @Override
                public String toString() { return "x"; }
            }
            """.trimIndent()
        )

        val endpoints = scan().map { it.endpoint }
        assertEquals(2, endpoints.size)
        assertTrue(endpoints.all { it.httpMethod == DubboMappings.DUBBO })
        assertEquals(listOf("getUser", "saveUser"), endpoints.map { it.methodName })
        assertEquals("UserDubboServiceImpl", endpoints[0].className)
        assertEquals("UserDubboServiceImpl#getUser", endpoints[0].label)
        assertTrue(endpoints[0].detail.contains("version=1.0.0"))
        assertTrue(endpoints[0].detail.contains("group=user"))
        // Object 自带方法不展示
        assertFalse(endpoints.any { it.methodName == "toString" })
    }

    fun `test 复制为 cURL 时按需读取方法参数`() {
        addSpringStubs()
        myFixture.addFileToProject(
            "com/example/web/UserController.java",
            """
            package com.example.web;

            import org.springframework.web.bind.annotation.*;

            @RestController
            public class UserController {

                @GetMapping({"/detail/{id}"})
                public ApiResponse<User> detail(@PathVariable("id") Long id,
                                                @RequestParam("name") String name,
                                                @RequestHeader("X-Token") String token,
                                                @RequestBody UserQuery query) {
                    return null;
                }
            }
            """.trimIndent()
        )
        myFixture.addFileToProject(
            "com/example/web/UserQuery.java",
            """
            package com.example.web;

            public class UserQuery {
                private Long id;
                private String name;
            }
            """.trimIndent()
        )

        val params = ApplicationManager.getApplication().runReadAction<CurlBuilder.Params> {
            val psiClass = com.intellij.psi.JavaPsiFacade.getInstance(project)
                .findClass("com.example.web.UserController", com.intellij.psi.search.GlobalSearchScope.allScope(project))!!
            val method = psiClass.findMethodsByName("detail", false).first()
            CurlPsiReader.readParams(method)
        }
        assertEquals(mapOf("id" to "1"), params.pathVars)
        assertEquals(listOf("name" to "text"), params.query)
        assertEquals(listOf("X-Token" to "text"), params.headers)
        assertEquals("{\"id\":1,\"name\":\"text\"}", params.body)

        val curl = CurlBuilder.build(
            ApiEndpoint("GET", "/user/detail/{id}", "UserController", "detail"),
            params = params
        )
        assertEquals(
            "curl -G 'http://localhost:8080/user/detail/1?name=text' -H 'X-Token: text' -d '{\"id\":1,\"name\":\"text\"}'",
            curl
        )
    }

    fun `test 扫描 DubboReference 注入字段`() {
        addSpringStubs()
        addDubboStubs()
        myFixture.addFileToProject(
            "com/example/web/UserController.java",
            """
            package com.example.web;

            import org.apache.dubbo.config.annotation.DubboReference;

            public class UserController {

                @DubboReference(version = "2.0.0")
                private com.example.dubbo.UserDubboService userDubboService;
            }
            """.trimIndent()
        )
        myFixture.addFileToProject(
            "com/example/dubbo/UserDubboService.java",
            """
            package com.example.dubbo;

            public interface UserDubboService {
                String getUser(Long id);
            }
            """.trimIndent()
        )

        val records = scan()
        assertEquals(1, records.size)
        val endpoint = records[0].endpoint
        assertEquals(DubboMappings.DUBBO_REFERENCE, endpoint.httpMethod)
        assertEquals("UserController", endpoint.className)
        assertEquals("userDubboService", endpoint.methodName)
        assertEquals("UserController#userDubboService", endpoint.label)
        assertTrue(endpoint.detail.contains("com.example.dubbo.UserDubboService"))
        assertTrue(endpoint.detail.contains("version=2.0.0"))
        assertNotNull(records[0].pointer.element)
    }

    /** 注解类来自依赖 jar，解析 scope 必须允许搜库，否则一条接口都扫不到 */
    fun `test 注解解析 scope 必须包含依赖库`() {
        val service = SpringApiService(project)
        assertTrue(
            "注解类在依赖 jar 里，projectScope 解析不到，必须用包含库的 scope",
            service.annotationScope().isSearchInLibraries
        )
        assertFalse("扫描结果只应包含项目源码", service.resultScope().isSearchInLibraries)
    }

    fun `test 扫描 RestController 上的 GetMapping`() {
        addSpringStubs()
        myFixture.addFileToProject(
            "com/example/web/ApiKeysAuthController.java",
            """
            package com.example.web;

            import org.springframework.web.bind.annotation.*;

            @RestController
            @RequestMapping("api/auth")
            public class ApiKeysAuthController {

                @GetMapping("check-apikey")
                public void checkApiKey() {}
            }
            """.trimIndent()
        )

        val records = scan()
        assertEquals(1, records.size)
        val endpoint = records[0].endpoint
        assertEquals("GET", endpoint.httpMethod)
        assertEquals("/api/auth/check-apikey", endpoint.path)
        assertEquals("ApiKeysAuthController", endpoint.className)
        assertEquals("checkApiKey", endpoint.methodName)
        assertNotNull(records[0].pointer.element)
    }

    fun `test 扫描 Controller 与 RequestMapping method 属性`() {
        addSpringStubs()
        myFixture.addFileToProject(
            "com/example/web/PageController.java",
            """
            package com.example.web;

            import org.springframework.stereotype.Controller;
            import org.springframework.web.bind.annotation.*;

            @Controller
            @RequestMapping("/page")
            public class PageController {

                @RequestMapping(value = "/list", method = {RequestMethod.GET, RequestMethod.POST})
                public String list() { return null; }
            }
            """.trimIndent()
        )

        val endpoints = scan().map { it.endpoint }
        assertEquals(2, endpoints.size)
        assertEquals(listOf("GET", "POST"), endpoints.map { it.httpMethod })
        assertTrue(endpoints.all { it.path == "/page/list" })
    }

    fun `test 扫描 Scheduled 方法`() {
        addSpringStubs()
        myFixture.addFileToProject(
            "com/example/job/OrderJob.java",
            """
            package com.example.job;

            import org.springframework.scheduling.annotation.Scheduled;

            public class OrderJob {

                @Scheduled(cron = "0 0/5 * * * ?")
                public void sync() {}
            }
            """.trimIndent()
        )

        val records = scan()
        assertEquals(1, records.size)
        val endpoint = records[0].endpoint
        assertEquals(SpringMappings.SCHEDULE, endpoint.httpMethod)
        assertEquals("OrderJob#sync", endpoint.label)
        assertTrue(endpoint.detail.contains("cron"))
    }
}
