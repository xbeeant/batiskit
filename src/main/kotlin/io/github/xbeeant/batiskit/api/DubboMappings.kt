package io.github.xbeeant.batiskit.api

/**
 * Dubbo 注解约定（纯逻辑，便于单测）。
 *
 * 与 [SpringMappings] 同一条工具窗口里展示：
 *  - 提供者：`@DubboService` 标注的实现类，类里的每个公开方法是一条记录；
 *  - 消费者：`@DubboReference` 标注的字段（注入的远程服务引用），每个字段一条记录；
 *  - 兼容 Dubbo 2.x 的 `com.alibaba.dubbo` 包名与旧版 `@Service` 注解（按全限定名区分，不会与 Spring 的 @Service 混淆）。
 *
 * Dubbo 没有路径概念，记录的 `path` 为空，列表里显示 `类名#方法名`；
 * `version` / `group` / `timeout` 等注解属性放进 [ApiEndpoint.detail] 供搜索。
 */
object DubboMappings {

    /** 提供者方法 */
    const val DUBBO = "DUBBO"

    /** 消费者注入的远程服务引用（字段） */
    const val DUBBO_REFERENCE = "DUBBO_REF"

    /** 提供者实现类上的注解（Dubbo 3 / 2.7 的 DubboService，2.x 旧包名与旧版 @Service） */
    val SERVICE_ANNOTATIONS: List<String> = listOf(
        "org.apache.dubbo.config.annotation.DubboService",
        "com.alibaba.dubbo.config.annotation.DubboService",
        "org.apache.dubbo.config.annotation.Service",
        "com.alibaba.dubbo.config.annotation.Service"
    )

    /** 消费者字段上的注解（新旧包名都收） */
    val REFERENCE_ANNOTATIONS: List<String> = listOf(
        "org.apache.dubbo.config.annotation.DubboReference",
        "com.alibaba.dubbo.config.annotation.DubboReference",
        "org.apache.dubbo.config.annotation.Reference",
        "com.alibaba.dubbo.config.annotation.Reference"
    )

    /** Object 自带方法不作为 Dubbo 接口展示 */
    private val OBJECT_METHODS = setOf("equals", "hashCode", "toString", "getClass", "clone", "notify", "notifyAll", "wait")

    fun isObjectMethod(methodName: String): Boolean = methodName in OBJECT_METHODS
}
