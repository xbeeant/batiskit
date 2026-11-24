package io.github.xbeeant.batiskit.api

/**
 * Spring MVC 注解约定与路径/方法解析（纯逻辑，便于单测）。
 *
 * 参考 spring-java-idea-plugin 的 MappingHelper：
 * 类上的 `@RequestMapping` 作为前缀，方法上的映射注解决定 HTTP 方法与子路径。
 */
object SpringMappings {

    /** 没有指定 `method` 的 `@RequestMapping` */
    const val REQUEST = "REQUEST"

    /** 定时任务（`@Scheduled`），与接口一起展示、可搜索 */
    const val SCHEDULE = "SCHEDULE"

    const val REQUEST_MAPPING = "org.springframework.web.bind.annotation.RequestMapping"

    const val SCHEDULED = "org.springframework.scheduling.annotation.Scheduled"

    /** 类级注解：只有这些类里的方法才算接口 */
    val CLASS_ANNOTATIONS: List<String> = listOf(
        "org.springframework.web.bind.annotation.RestController",
        "org.springframework.stereotype.Controller"
    )

    /**
     * 方法级映射注解 -> 隐含的 HTTP 方法；
     * `@RequestMapping` 需要再看 `method` 属性，所以值是 [REQUEST]，顺序保证具体注解优先。
     */
    val METHOD_ANNOTATIONS: Map<String, String> = linkedMapOf(
        "org.springframework.web.bind.annotation.GetMapping" to "GET",
        "org.springframework.web.bind.annotation.PostMapping" to "POST",
        "org.springframework.web.bind.annotation.PutMapping" to "PUT",
        "org.springframework.web.bind.annotation.DeleteMapping" to "DELETE",
        "org.springframework.web.bind.annotation.PatchMapping" to "PATCH",
        REQUEST_MAPPING to REQUEST
    )

    /** 可能承载路径的注解属性名 */
    val PATH_ATTRIBUTES: List<String> = listOf("value", "path")

    /**
     * 拼接类前缀与方法路径：
     * - 去掉多余的斜杠（`/api/` + `/users` -> `/api/users`）
     * - 结果一定以 `/` 开头，两边都为空时归一成 `/`
     */
    fun joinPath(base: String, sub: String): String {
        val head = base.trim().trim('/')
        val tail = sub.trim().trim('/')
        val joined = when {
            head.isEmpty() && tail.isEmpty() -> "/"
            head.isEmpty() -> "/$tail"
            tail.isEmpty() -> "/$head"
            else -> "/$head/$tail"
        }
        return joined.replace(Regex("/{2,}"), "/")
    }

    /**
     * `@RequestMapping(method = ...)` 的文本 -> HTTP 方法列表。
     * 解析不到（没写 method 或引用了常量）时返回 [REQUEST]。
     */
    fun parseRequestMethods(methodAttributeText: String): List<String> {
        val methods = Regex("RequestMethod\\.([A-Za-z]+)")
            .findAll(methodAttributeText)
            .map { it.groupValues[1].uppercase() }
            .distinct()
            .toList()
        return methods.ifEmpty { listOf(REQUEST) }
    }
}
